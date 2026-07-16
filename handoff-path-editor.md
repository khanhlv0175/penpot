# Handoff: Path editor investigation (event ordering / polishing)

Initial investigation: 2026-07-02. Last updated: 2026-07-15. Goal: polish the path
(pen tool) editor; several event-ordering problems cause unexpected results. This
document is the source of truth for work between sessions. A copy of the original
findings also lives in Serena memory
`frontend/path-editor-architecture-and-event-ordering`; treat this document as newer
when they differ.

Only track bugs that predate this branch in the bugs checklist. Regressions introduced
and fixed within this branch belong in the implementation/validation notes, not as
numbered bugs.

The original investigation was done on branch `alotor-plugin-api-contributions`.
The implementation described in the completed checklist items below is in the working
tree on branch `piweek-path-editor-enhancements` as of 2026-07-10. Do not assume it is
committed; inspect `git status` first.

## 1. File map

Data layer (`frontend/src/app/main/data/workspace/path/`), re-exported by
`frontend/src/app/main/data/workspace/path.cljs`:

| File | Role |
|---|---|
| `state.cljs` | State locators. `get-path-id` prefers `[:workspace-drawing :object :id]`; `get-path-location` always points to `[:workspace-drawing :object]`. Existing-path edition no longer mutates file objects. |
| `streams.cljs` | Rx building blocks: `drag-stream` (click-vs-drag discrimination via 5px/zoom threshold), `position-stream`, `move-points-stream`, `move-handler-stream`, pixel/path snapping, `finish-edition?` (matches the real `workspace.edition/clear-edition-mode`; bug 1 fixed). |
| `drawing.cljs` | Draw mode: `handle-drawing`, `start-edition` loop, `add-node`, `drag-handler`/`finish-drag`, `close-path-drag-start`, `start-draw-mode*` recursion, `check-changed-content`, `end-path-event?`, `handle-drawing-end`. `start-created-path-edition` installs the normal edit finalizer after a newly drawn shape is added. |
| `edition.cljs` | Move mode: `start-path-edit` copies/converts the stored shape into drawing state and installs the exit watcher; `stop-path-edit` finalizes once and clears drawing/edit state. Node/handler/arrow/split operations mutate only the drawing copy. |
| `selection.cljs` | Hover state (`path-pointer-enter/leave`, `path-handler-enter/leave`), `select-node`, rubber-band `handle-area-selection`, `update-selection` (position remap after commit). |
| `changes.cljs` | `finalize-path-content`: compares drawing content with the session baseline; unchanged content is a no-op, changed content emits one `dwsh/update-shapes`, and empty content emits one deferred removal commit. |
| `undo.cljs` | Session-local undo stack (separate from workspace undo), driven by `rx/from-atom` over drawing content; `merge-head` collapses node+handler-drag into one entry; `undo-lock` guards double subscription. Undo/redo no longer persist file changes during edition. |
| `tools.cljs` | `process-path-tool` → make-corner/curve, add/remove/merge/join/separate nodes, `toggle-snap`; tool results update drawing content only. |
| `common.cljs` | `finish-path` (cleans `:last-point/:prev-handler/:drag-handler/:preview`, close-subpaths). |
| `shortcuts.cljs` | Path-editor keymap; Esc/Enter/V emit `esc-pressed` → `cancel-pending-segment` when a segment is in progress, `:interrupt` otherwise; arrows → `move-selected`; cmd+Z → `undo-path` (the path-local undo). |

UI:
- `frontend/src/app/main/ui/workspace/shapes/path/editor.cljs` — `path-editor*` +
  `path-point*` / `path-handler*` / `path-preview*` / `path-snap*`. Node pointer-down
  handlers call `stop-propagation` and emit the drp events directly. Also installs a raw
  DOM `dblclick` listener that emits `:interrupt` in `:move` mode.
- `frontend/src/app/main/ui/workspace/viewport/drawarea.cljs` — mounts `path-editor*`
  with a derived lens on `[:edit-path id]`. Path-shaped drawing copies with no active
  drawing tool render their shape but skip the generic bounding-box outline.
- `frontend/src/app/main/ui/workspace/viewport/actions.cljs` — viewport pointer entry:
  emits `mse/->MouseEvent :down/:up/:click/:double-click` and one
  `mse/->PointerEvent :viewport` per pointermove into the potok store; when
  node-editing, pointer-down routes to `dwdp/handle-area-selection`.
- `frontend/src/app/main/ui/workspace/viewport/path_actions.cljs` — top bar buttons
  (`check-enabled` gating).
- `frontend/src/app/main/ui/workspace/viewport.cljs` and `viewport_wasm.cljs` — during
  path edition, pass the drawing copy to the path editor. The classic renderer marks
  the stored shape hidden in render-only objects; the WASM viewport temporarily hides
  the stored renderer shape and restores it on cleanup.

Entry points into edit mode:
- `frontend/src/app/main/data/workspace.cljs` `start-editing-selected` (~line 904):
  `(dwe/start-edition-mode id)` + `(dwdp/start-path-edit id)` for path-like shapes.
- `frontend/src/app/main/data/workspace/drawing.cljs`: `select-for-drawing :path` →
  `start-drawing :path` (draw lock) → `path/handle-drawing`.
- `frontend/src/app/main/data/workspace/edition.cljs`: `start-edition-mode` /
  `clear-edition-mode` lifecycle (see bug 3).

Geometry/content model: `common/src/app/common/types/path.cljc` plus
`common/src/app/common/types/path/{segment,subpath,helpers,impl,shape_to_path}.cljc`.

## 2. Ephemeral edit state

`[:workspace-local :edit-path <shape-id>]` keys: `:edit-mode` (`:draw`/`:move`),
`:selection` and `:hover` — each a single grouped map
`{:nodes #{index} :segments #{index} :handlers #{[index prefix]}}`
(`helpers/empty-selection` is the empty value); `:content-modifiers` (index → dx/dy/c1x… deltas applied at render
and then materialized into drawing content), `:moving-nodes`, `:moving-handler`,
`:drag-handler`, `:prev-handler`,
`:last-point`, `:preview` (next-segment preview), `:snap-toggled`, `:old-content`
(stable session baseline for finalization), `:undo-stack`, `:undo-lock`, `:current-move`.

The authoritative shape during path edition is
`[:workspace-drawing :object]`. `start-path-edit` converts a simple shape to a path
only in this copy and records its converted content in `:old-content`. The original
shape under `[:files ... :data :pages-index ... :objects]` remains structurally
unchanged until exit. This is essential because `update-shapes` must compare against
the real stored object. Path-local undo must never replace `:old-content`; otherwise
finalization can incorrectly decide that an edited path is unchanged.

**Node identity is index-based (stable-identity groundwork, done 2026-07-10).**
Selection/hover sets hold *content command indices*; a node's position is derived on
demand via `helpers/node-position`/`node-positions`. Indices are invariant under moves,
so dragging/arrow moves need **no** selection remap (the old `update-selection`
point-change remap is gone); only structural tool ops remap, once, by re-resolving the
old selected positions against the new content (`helpers/remap-selected-nodes`, called
in `process-path-tool`). The editor renders each node from its index (no more
`point->base`/`base->point` maps). The move/arrow/snap/tool pipelines and
`path-actions/check-enabled` stay position-based and convert indices→positions at their
boundary via `helpers/node-positions`, so the pre-existing duplicate-coordinate collapse
persists *only* inside those position-based common fns (acceptable; a later pass can make
the common tool fns index-based). Segments and handlers now use the same unified
selection/hover model: segment identity is the drawing command index and handler
identity is `[command-index prefix]`. Click/Shift+click, marquee selection, selected
rendering, and grouped handler movement are wired for features 1, 3, and 8. Remaining
wart: `create-node-at-position` still exists with split params in point metadata, but
the editor no longer renders the insertion preview or invokes it; segment clicks go to
segment selection.

## 3. Event transport & ordering model (the heart of it)

Everything — including every pointermove — goes through the single potok store.
Verified against pinned sources: potok2 `0f7e15a` and beicon2 `8744c66` in
`~/.gitlibs/libs/funcool/`.

1. **Processing is synchronous and re-entrant.** Per event: `UpdateEvent` swap! runs,
   then `watch` result is subscribed and its synchronous emissions (`rx/of …`) are
   pushed into the input subject which delivers **immediately, depth-first** — not
   queued. Code needing "after the current cascade" must opt out explicitly:
   `rx/observe-on :async` (used in `handle-drawing` before `handle-drawing-end`) and
   `rx/delay 0` (in `dwdc/handle-finish-drawing`'s `clear-drawing`, "so the mouse event
   can read the drawing state"). Those are load-bearing workarounds.
2. **`rx/from-atom` = `add-watch`** → fires synchronously *inside the swap!* of the
   event that changed state. The path undo (`start-path-undo` → `add-undo-entry`) is
   therefore processed nested inside the content-changing event, before that event's
   own `watch` runs. Same mechanism feeds `snap-toggled-stream` and `position-stream`'s
   content/ranges watcher.
3. **A `watch` subscribing to `stream` misses the event that spawned it** — hence the
   explicit re-emit hack in `move-selected` ("First event is not read by the stream so
   we need to send it again").
4. **A `watch` receives post-update state of its own event.** Bug 3 is fixed by
   capturing the active path id inside the clear event during its UpdateEvent. Its
   WatchEvent can therefore emit `finish-path` after `:edition` is removed but while
   path drawing/edit state is deliberately retained; the `start-path-edit` watcher
   subsequently emits `stop-path-edit` and finalizes it.
5. `ms/mouse-position` & modifier streams (`app.main.streams`) are behavior-subjects
   fed from `st/stream`; `@ms/mouse-position` reads are common (e.g. `drag-stream`
   start point, `start-move-path-point`).
6. `mse/drag-stopper` = blur ∪ mouse-up ∪ `:interrupt`.

## 4. Main workflows

1. **Draw new path**: toolbar → `dd/start-drawing :path` (draw lock) →
   `path/handle-drawing` (shape into `:workspace-drawing`) → `start-edition`:
   mousemove → `preview-next-point`; mousedown → `rx/switch-map` over
   `rx/race(make-node-events-stream, make-drag-stream)` → `add-node` → optional
   `drag-handler`s → `finish-drag` (+ `undo/merge-head`). Ends on `end-path-event?`
   (finish-path / esc-pressed / clear-edition-mode / finalize-page / `:interrupt` /
   double-click) → `::end-edition` data event → (async) `handle-drawing-end` → if >1
   segment: `setup-frame` + `dwdc/handle-finish-drawing` (creates the real shape) +
   `start-created-path-edition`. That event emits `dwe/start-edition-mode`,
   `edition/start-path-edit`, then `change-edit-mode :draw`; installing
   `start-path-edit` is mandatory so exit finalizes and clears the cursor state.
   `start-draw-mode*` then re-enters `start-edition` recursively while all further
   content changes stay in the drawing copy.
2. **Edit existing**: double-click shape → `start-editing-selected` →
   `start-edition-mode` + `start-path-edit` (copy and convert shape into
   `:workspace-drawing :object`, close subpaths there, enter `:move` mode, snapshot the
   converted `:old-content`, start path undo; clear/`:interrupt` → `stop-path-edit`).
3. **Node drag/click**: node pointer-down (UI, stops propagation) →
   `start-move-path-point` → `drag-stream`: drag ⇒ maybe `select-node` +
   `drag-selected-points` → `move-selected-path-point` accumulates
   `:content-modifiers` → on stop `apply-content-modifiers` updates drawing content and
   remaps selection; no-drag ⇒ `select-node`. No file commit occurs here.
4. **Handler drag**: `start-move-handler` → `move-handler-stream` (snap + opposite-angle
   matching) → `modify-handler` → `apply-content-modifiers`.
5. **Rubber band**: viewport pointer-down while node-editing → `handle-area-selection`
   (selrect live; node selection via `buffer-time 100`; `clear-area-selection` at end).
6. **Arrow keys**: `move-selected` with same-event `js/Symbol` guard + self-re-emit +
   `rx/debounce 100` stopper → `set-move-modifier` → `apply-content-modifiers` +
   `finish-move-selected`.
7. **Tools**: `process-path-tool` updates drawing content and geometry only
   (+ `clear-edition-mode` if content becomes empty; deletion is committed by the
   finalizer).
8. **Exit**: Esc → `esc-pressed`. With a pending segment it only emits
   `cancel-pending-segment` (draw loop keeps running; next click starts a new
   subpath). Without one it emits `:interrupt` (matched by `drag-stopper` and by
   `start-edition-mode`'s watch → `clear-edition-mode`); `end-path-event?` matches
   `:interrupt`, not `esc-pressed`. During path exit, generic clear removes `:edition` but keeps
   the drawing copy and `:edit-path` until the `start-path-edit` watcher emits
   `stop-path-edit`. Finalization compares `:old-content` with drawing content: equal is
   a no-op (so a rectangle stays a rectangle); changed content emits exactly one
   `update-shapes`; empty content emits one removal commit. Cleanup then removes the
   drawing object and edit state. Double-click in move mode emits `:interrupt` via the
   DOM listener in `path-editor*`.

## 5. Confirmed bugs / hazards checklist

- [x] **Bug 1 — stale event keyword in `streams.cljs` `finish-edition?`.** Fixed
  2026-07-10. `streams.cljs` now uses `::dwe/clear-edition-mode` through an alias-only
  require, so `drag-stream` and `start-move-handler` stop on the real event.
  `drawing/end-path-event?` now uses the same alias and its duplicate dead keyword was
  removed. Regression coverage asserts that the real clear event matches and
  `:interrupt` does not.
- [x] **Bug 2 — `start-path-edit` stopper discarded the clear comparison.** Fixed as
  part of the drawing-copy refactor: the exit stream explicitly accepts either
  `:interrupt` or `::dwe/clear-edition-mode`, takes one, and emits `stop-path-edit`;
  starting another `start-path-edit` stops the old listener.
- [x] **Bug 3 — generic `clear-edition-mode` `finish-path` branch was dead.** Fixed
  2026-07-10. The event captures the active path id in a volatile during its
  pre-cleanup update. Its watch emits `(rx/of (finish-path))` only for path edition;
  non-path edition returns an empty stream and cannot create path state. Live ordering
  validation observed drawing/edit state present and transient preview/last-point
  cleared at `finish-path`, followed by complete cleanup at `stop-path-edit`.
- [x] **Bug 4 — undo-entry re-entrancy.** Fixed 2026-07-10. `add-undo-entry` is still
  injected synchronously from the content-changing swap (via `rx/from-atom`
  `path-content-ref`), before that event's watch, but this is now provably safe: every
  field an entry captures — content/selrect/points and the `:last-point`/`:prev-handler`
  draw cursors — is written in the *same swap!* as the content change (`add-node`,
  `finish-drag`), so the re-entrant capture is consistent. The one genuinely transient,
  render-only field, `:preview` (the dashed next-segment hint that follows the mouse),
  was the only thing that could be captured stale — e.g. applying a `process-path-tool`
  operation while a draw-mode preview was showing, then undoing, restored a phantom
  segment. `make-entry` no longer stores `:preview`, and `load-entry` clears it to nil on
  undo/redo (it is recomputed on the next `preview-next-point`). Focused unit suite green
  (14 tests / 34 assertions).
- [x] **Bug 5 — two racing stop signals per Esc.** Fixed 2026-07-10. The original race
  (the `::esc-pressed` event vs the `:interrupt` it emits) was already dissolved by the
  bug-11 rework: `esc-pressed` is now a pure translator emitting exactly one of
  `finish-path` / `cancel-pending-segment` / `:interrupt`, and nothing keys on
  `::esc-pressed` except its own shortcut. The remaining smell was `start-path-edit`'s
  exit watcher keying on **both** `:interrupt` and `::dwe/clear-edition-mode`, even
  though `:interrupt` always produces `clear-edition-mode` through the
  `start-edition-mode` watch (edition.cljs), and — because that watch subscribes before
  `start-path-edit` and re-entrant delivery is depth-first — `clear-edition-mode` is
  already what triggers the exit. Keyed the exit watcher on the single canonical
  `::dwe/clear-edition-mode` signal, which also covers the draw loop's direct
  `clear-edition-mode` on exit. Focused unit suite green (14 tests / 34 assertions,
  incl. escape/interrupt/created-path exit cases). Live-gesture re-check was blocked by
  WebGL context loss after repeated hot reloads (read-only mode; recovered by reload but
  hit-testing stayed flaky).
- [x] **Bug 6 — DOM-order race when closing a path.** Fixed 2026-07-10. The draw loop
  raced `make-node-events-stream` (waiting for `close-path-drag-start` to cancel a
  spurious add-node) against `make-drag-stream` inside `rx/switch-map` over `mouse-down`.
  That guard was inert: (1) `make-drag-stream` emits `add-node` synchronously on
  subscription, so `rx/race` always resolves to it before `make-node-events-stream` can
  react; and (2) `mouse-down` only fires for viewport clicks — node handlers
  `stop-propagation` and the viewport's `on-pointer-down` only emits `:down` when the
  event target's own class is `viewport-controls` (`dom/class?` checks the node itself,
  not ancestors), so a node click never produces a `mouse-down`, never spawns a
  `make-drag-stream`, and never needs cancelling. Closing is driven entirely by
  `close-path-drag-start`'s own watch. Removed `make-node-events-stream` and the
  `rx/race`; the `switch-map` now uses `make-drag-stream` directly (behaviour-identical).
  Verified live: drawing adds exactly one node per click (no duplicates), and clicking
  the start node closes to a clean 3-node triangle with a single closing segment. Focused
  unit suite green (14 tests / 34 assertions).
- [x] **Bug 7 — bare global `:interrupt` from `check-changed-content`.** Fixed
  2026-07-10. When a draw-mode restart produced no new content, `check-changed-content`
  emitted a bare global `:interrupt`, which every unrelated interrupt subscriber also
  reacts to. That `:interrupt` only reached path teardown via the `start-edition-mode`
  watch → `clear-edition-mode` anyway, so it now emits `dwe/clear-edition-mode` directly
  — the scoped signal the draw loop (`end-path-event?`) and path-edit exit watcher both
  key on — decoupling draw-recursion teardown from unrelated editor teardown. Draw →
  finish → exit lifecycle verified live (clean state, no exception); focused unit suite
  green (14 tests / 34 assertions).
- [x] **Bug 8 — `apply-content-modifiers` page-id fallback typo.** The problematic
  commit-building path was removed by the drawing-copy refactor; modifiers now update
  drawing content directly.
- [x] **Bug 9 — rubber-band selection lags by up to 100 ms.** Fixed 2026-07-10.
  `handle-area-selection` used `buffer-time 100` on the node hit-testing branch, so
  the highlighted nodes trailed the visible selection box by up to 100 ms during the
  drag. The visible selrect (`update-area-selection`) already updated live off
  `ms/mouse-position`; only the selection branch was throttled. The final selection at
  release was already correct because `buffer-time` (RxJS `bufferTime`) flushes its
  pending buffer on source completion (the `drag-stopper`). Fix: tighten the throttle
  to one animation frame (`buffer-time 16`), preserving the same structure — completion
  flush and `distinct-contiguous` guard — so hit-testing tracks the box in real time
  without recomputing on every pointer move.
  **Rubber-band box regression fixed alongside (from the drawing-copy refactor):** the
  viewport `show-selrect?` gate was `(and selrect (empty? drawing) …)`, but path edition
  now populates `[:workspace-drawing :object]` with the edit copy, so `(empty? drawing)`
  was false during node editing and the selection box never rendered. Relaxed to
  `(or (empty? drawing) path-editing?)` in both `viewport.cljs` and
  `viewport_wasm.cljs`; `selrect` is only set by the path area-selection while editing,
  so this doesn't affect normal (non-path) area selection. Live-validated on the WASM
  viewport: entered node editing (`edit-mode :move`, drawing object present), dragged a
  rubber band over empty canvas — mid-drag `[:workspace-local :selrect]` set, the
  `workspace-selection-rect` element rendered, and `:selected-points` reached 2 during
  the drag; on release the box cleared and the selection was retained.
- [x] **Bug 10 — exiting a newly drawn path left the edition cursor active.** State
  reproduced on 2026-07-10: `:edition` was nil, but `:edit-path` and
  `:workspace-drawing :object` remained, so viewport cursor logic still considered the
  path active. Root cause: new paths used `start-edition-mode` + draw mode without
  installing the `start-path-edit` finalizer. Fixed by `start-created-path-edition`,
  which emits `start-edition-mode`, `start-path-edit`, then `change-edit-mode :draw`.
- [x] **Bug 11 — Escape restarted drawing on the edited path.** Fixed 2026-07-10.
  `start-edition` collapsed every stop reason into an unqualified `::end-edition`, and
  `start-draw-mode*` therefore recursed on Escape before the later `:interrupt`
  finalized the edited shape. This produced a hanging preview from the edited path
  instead of leaving the drawing tool ready for a new path. `::end-edition` now
  carries `:restart?`; only explicit `finish-path` and draw-mode double-click restart
  the same path. Escape, interrupt, clear-edition, page teardown, and superseding
  edition events end the loop without recursion. Legitimate restarts first clear
  transient endpoint/preview state.
  **Second pass (same day):** the first fix only covered `start-draw-mode*`; the
  *initial* draw loop persisted the bug. `handle-drawing`'s continuation ignored the
  stop reason: `handle-drawing-end` runs after `rx/observe-on :async`, so Esc's
  synchronous `:interrupt` had already passed before `start-created-path-edition`
  installed any exit watcher, and the unconditional re-entry left a live draw loop
  previewing from the stale `:last-point`. Reproduced live (preview tracked the mouse
  after Esc). Fix: `handle-drawing` threads `(:restart? (deref event))` into
  `handle-drawing-end`; non-restart ends commit the shape and emit
  `dwe/clear-edition-mode` (which also stops the path-undo watcher and clears
  `:edit-path`, drawing object, and lock) instead of re-entering edition. The short
  (<2 segment) branch gets the same cleanup. Double-click/`finish-path` ends still
  re-enter continued edition via `start-created-path-edition`.
  **Escape semantics (decided same day):** Esc must NOT exit the editor while a
  segment is in progress. `esc-pressed` now branches on `:last-point` and `:edition`:
  - pending segment and **no `:edition`** (a path still being created): emit
    `common/finish-path`, which ends the initial draw loop as a restart — the shape
    is created, `start-created-path-edition` enters continued edition in draw mode,
    and the path edition top bar appears (it is keyed on `:edition`, so a plain
    cancel here would leave the editor without its menu);
  - pending segment with `:edition` (continued/existing edition): emit
    `common/cancel-pending-segment`, which clears
    `:last-point/:prev-handler/:drag-handler/:preview` **without stopping the draw
    loop** — the next preview is a `:move-to` and the next click starts a new
    subpath in the same shape;
  - no pending segment: emit `:interrupt`; the edition exits/commits as before.
  `esc-pressed` was therefore removed from `end-path-event?` — the draw loop never
  ends on Esc directly, only through `:interrupt`/clear-edition/finalize-page/
  superseding-edition (non-restart) or `finish-path`/double-click (restart).
  Restarting `handle-drawing-end` also emits `finish-path` first, so restarts don't
  carry pending state into the continued edition.
- [x] **Bug 12 — closing a path does not join the last point cleanly (visual).** Fixed
  2026-07-12. Confirmed cause: a closed loop was persisted as `M A … L A` (a line-to
  landing back on the start) with no `:close-path` command, so the stroke rendered two
  butt caps at the seam instead of a join. `close-subpaths`/`is-closed?` only compare
  endpoint positions and never emit a `:close-path`. Fix (common): new
  `subpath/close-loops` + `path/close-loops` — for every subpath whose endpoints
  coincide (within the 0.1 `pt=` tolerance), replace a trailing `line-to` landing on the
  start with a `:close-path` command, or append one after any other coincident ending
  (e.g. a `curve-to`). `extract-content`'s ad-hoc `close-extracted-loops` was removed and
  now delegates to `close-loops`. The editor keeps working with line-closed loops during
  edition (the editor draws its own thin outline, so the seam is never visibly broken
  mid-edit); the close-path is materialized only at persistence:
  - **Draw flow**: `drawing/close-drawn-loops` runs in `handle-drawing-end` (both the
    restart and the non-restart >1-segment branches) right before the shape is created,
    so a freshly drawn+closed path is created already joined. This is required because a
    draw→close→continued-edition→exit with no further changes has
    `old-content == edit-content` and `finalize-path-content` would short-circuit,
    never closing it.
  - **Edit-existing flow**: `changes/normalize-content` runs `close-loops` before
    persisting a genuinely changed path. The no-op guard now compares
    `(close-loops old-content)` against `new-content` so opening an already
    line-closed shape and exiting without changes (or with only a dangling trailing
    move-to) is still a no-op and does not rewrite the shape / convert a rect to a path.
  Verified: common suite 1089 tests / 24524 assertions (6 new `subpath-close-loops`
  cases + `path-close-loops-path-data`); frontend focused suite 28 tests / 68
  assertions (draw-end emission tests now assert `close-drawn-loops` fires;
  `changed-path-edition-is-persisted-when-finalized` asserts the persisted content is
  close-looped); lint/format clean. Live (running dev build, `close-loops` called on
  real store shapes): the exact bug shape `M394,149…L389,214L394,149` →
  `…L389,214Z`; a multi-subpath icon closes each of its 3 coincident loops
  independently, preserving a `curve-to` ending before its `Z`; genuinely open paths are
  left unchanged.
  **Reopened + fixed 2026-07-14 — editor view now shows the join.** The *persisted* shape
  closed correctly, but **while the path was being edited** the seam still rendered open
  (two butt caps / no join); it only displayed correctly after exiting edition. Root cause:
  during draw/edit both viewports render the drawing copy's fill/stroke through
  `drawarea/draw-area*` → `shapes/shape-wrapper` (SVG), and that copy is deliberately kept
  **line-closed** (a trailing line-to back to the subpath start, no `:close-path`) so the
  editor's per-node/segment interaction on the closing seam keeps working — so the SVG
  stroke drew two butt caps at the seam. Fix (`viewport/drawarea.cljs`, renderer-agnostic —
  both viewports route through it): `draw-area*` now close-loops (`path/close-loops`) the
  path content of **only the rendered copy** it passes to `shape-wrapper`; the editor
  overlay (`path-editor*`) still receives the raw line-closed content, so interaction is
  unchanged. The close is recomputed per render, so dragging endpoints apart re-opens the
  path live, and open paths are untouched. Verified live (WASM viewport, real gestures): a
  line-closed triangle with a 24px center stroke rendered a butt-cap notch at the seam in
  the normal render, and a clean miter join at the same vertex once in edit mode; exiting
  with no change left the stored content line-closed (fix is render-only, no shape
  mutation). Compiler `:ok`, `clj-kondo` 0/0, `cljfmt` clean.
- [x] **Bug 13 — make-curve can curve adjacent nodes.** Fixed 2026-07-13. Root cause was in
  `common.types.path.segment/make-curve-point`: its normal branch (a node with two distinct
  neighbours, `count = 2`) correctly gives only the target node handles and keeps each
  neighbour's shared-segment handle coincident (a corner), but the **fallback else branch**
  (`count ≠ 2` — chiefly an **endpoint**) converted the adjacent segment(s) with
  `line->curve`, which offsets the handles at **both** endpoints — so the neighbour got a
  real handle and appeared curved. Reproduced: curving the first node of `M0,0 L100,0 L200,0`
  produced `c2=(68.4,10.5)` on the shared segment (neighbour `(100,0)` curved). Fix: the
  else branch now mirrors the `count = 2` branch — for each segment adjacent to the target it
  writes only the target's handle (`update-curve-to` with the neighbour coincident for a
  `line-to`, or `update-handler` on just the target's `:c1`/`:c2` for a `curve-to`), placing
  the target handle 1/3 of the way toward the neighbour. The now-unused `line->curve` helper
  was removed. Verified (JVM + REPL): endpoint/last-node/middle all keep neighbours coincident
  (e.g. endpoint → `c1=(33.3,0)`, `c2=(100,0)` coincident). New test
  `segment-make-curve-point-keeps-neighbors-corners`; full common suite 1100 tests / 24570
  assertions, 0 failures; `cljfmt`/`clj-kondo` clean.
- [x] **Bug 14 — no-fill path in an SVG group renders black in normal view but
  transparent while editing.** Fixed 2026-07-14. A path inside an imported SVG group with no
  `:fills` renders **black**: the imported `:group` carries `:svg-attrs`, so CLJS
  `render-wasm/svg-fills/resolve-shape-fills` gives it the SVG-default black fill (its
  `:group` branch), and the Rust renderer pushes the group's fills onto `nested_fills`
  (`render.rs:2532`) so the empty-fill child path renders with the group's black
  (`render.rs:1754`). **User decision (2026-07-14): keep the black in normal view** — it is
  correct SVG-spec behaviour (default `fill` is black, cascaded to children), so
  `resolve-shape-fills` is left unchanged. The real defect was the **inconsistency during
  edition**: `start-path-edit` copies the path into `[:workspace-drawing :object]` and it is
  rendered **standalone** via `drawarea/draw-area*` → classic `shape-wrapper`, so the group's
  `nested_fills` cascade no longer applies and the copy (own `:fills []`) rendered
  transparent — the fill vanished the moment you entered edition. Fix (`path/edition.cljs`):
  new `resolve-edit-fills` resolves the copy's effective fills, mirroring the renderer
  cascade — a shape with its own fills keeps them, otherwise it inherits the nearest ancestor
  **group's** `resolve-shape-fills` (frames reset the cascade, matching `render.rs`) — and
  `start-path-edit` assocs the result onto the drawing copy's `:fills`. Purely visual:
  `finalize-path-content` only ever writes `:content` back to the stored shape and the no-op
  guard compares content, so the resolved fill is never persisted; the design panel reads the
  stored shape, not the copy. Verified live (WASM viewport, real gestures): a no-fill path in
  an SVG group rendered black normally and **stayed black while editing** (was transparent
  before), and on exit the stored `:fills` were still `[]` (no mutation). Compiler `:ok`,
  `clj-kondo` 0/0, `cljfmt` clean.

## 6. Refactors and features checklist

Work planned on top of the polished editor, with required groundwork:

- [x] **Isolate path edition in drawing state and persist once on exit.** Completed
  2026-07-10. `start-path-edit` copies/converts the shape into
  `:workspace-drawing :object`; node/handler/arrow/tool/undo operations do not touch
  file objects; `finalize-path-content` emits zero updates for unchanged content and
  exactly one `update-shapes` for changed content. An unchanged simple shape keeps its
  original type. Empty content is removed only at finalization. Classic and WASM
  viewports render the drawing copy and hide the stored original during edition.
- [x] **Feature 1 — make segments selectable.** Completed 2026-07-10. Segments use
   their drawing-command index as identity. `path-segment*` renders per-segment hit
   targets plus hover/selected styling, including synthesized closing lines for
   `close-path`; selected segments render pink (`#F49EF7`). Plain segment clicks replace
   selection and Shift+click toggles. Dragging a selected segment moves the unique
   endpoint nodes of every selected segment, including their anchored Bézier handlers.
   The insertion preview is disabled for now, so clicking a segment selects it instead
   of creating a node.
- [x] **Feature 2 — copy/paste of nodes and segments.** Completed 2026-07-11. Common
   layer: `segment-entries` moved into `common.types.path.segment` (frontend
   `helpers/segment-entries` now delegates), plus `path/extract-content` (selected
   segments + segments whose two endpoint nodes are selected → chained subpaths with
   move-to normalization; a trailing line-to landing on its subpath start becomes an
   explicit close-path) and `path/splice-content`. Frontend:
   `data/workspace/path/clipboard.cljs` — `copy-selected-nodes` writes
   `{:type :copied-path-content :content <PathData>}` as transit to the system
   clipboard (PathData has transit handlers); `paste-nodes` reads the clipboard,
   ignores anything that is not that payload, cancels a pending draw segment, and
   `paste-content` splices in place, offsetting by +10/+10 until no pasted node
   coincides with an existing node position (the position-based move pipeline would
   otherwise drag original and pasted nodes together — found live), selects the pasted
   node indices, and switches to `:move` mode. Bound to mod+C/mod+V in the path
   shortcuts (only active during path edition; the generic workspace paste ignores the
   payload type, so pasting outside the editor is a silent no-op for now). Verified
   live end-to-end with real gestures: node-pair copy, segment copy, paste+drag moves
   only the pasted fragment, text-clipboard paste is a no-op while editing,
   empty-selection copy leaves the clipboard untouched, and exit persists one commit
   with the new subpaths.
- [x] **Feature 3 — drag selection of segments, nodes and handlers.** Completed
   2026-07-10. The marquee computes the identity sets once per animation frame.
   Nodes and visible handler control points use rect containment; lines and approximated
   Bézier segments use rect intersection. Replace, append, and remove selection modes
   operate on the complete grouped selection map.
   **Priority rework (2026-07-11, user requirement):** the marquee selects a single
   element type with priority nodes > segments > handlers — any node inside the
   rectangle selects only nodes, segments are selected only when no node is inside,
   and handlers only when the rectangle contains neither nodes nor segments
   (`select-path-area` computes the lower-priority sets lazily). Live-verified with
   real drags on a bowed curve: curve-only rect → segment, rect with a node → that
   node only, handler-only rect → that handler, rect over everything → all nodes.
- **Feature 4 — border radius in path corners (round corners). Dropped 2026-07-13**
   (user decision): no further work planned here. (Was: a corner-fillet geometry fn in
   `common.types.path` on the `process-path-tool` pipeline, reusing
   `helpers/fit-cubic`/`fit-curve`.)
- **Feature 5 — alignment of nodes (snap). Dropped 2026-07-11:** the current snapping
   behaviour is considered good enough; no further work planned here.
- [x] **Feature 6 — improve curve-tool smoothing.** Completed 2026-07-11. Common:
   `helpers/fit-curve` implements the full Schneider FitCurves algorithm on top of the
   feature-7 `fit-cubic` (which gained a `[points params tan1 tan2]` arity): per region
   it fits one cubic against chord-length parameters, computes the max squared sample
   error, refines the parameterization with up to 4 Newton-Raphson passes while the
   error is within 4× tolerance, and otherwise splits at the worst sample sharing the
   center tangent (`(p[i-1] − p[i+1])`, sign-flipped for the right side) so joins stay
   G1-smooth; consecutive points closer than 0.01 are deduped up front. Returns chained
   `[start end h1 h2]` curves. `segment/smooth-points->content` (+ `path.cljc` wrapper)
   converts the fit into `move-to`+`curve-to` content, falling back to the polyline
   `points->content` below 3 points. Frontend: `drawing/curve.cljs` `finish-drawing`
   now calls `smooth-points->content` with `smooth-tolerance` 1 (board units) after the
   existing RDP `ups/simplify` 0.3 pass; the live preview while drawing stays the cheap
   polyline. Verified live: a freehand S-wave (11 sampled points) committed as 4 chained
   curve-to segments rendering a smooth wave; a V stroke kept straight arms with a
   softly rounded valley (single tangent-aligned cubic within tolerance — dense
   sharp-corner data still splits, unit-tested).
   **Corner-preservation fix (2026-07-13).** Sharp corners (zigzags) looked strange:
   Schneider always shares a *center tangent* at a split (`(p[i-1] − p[i+1])`) to keep
   joins G1-smooth, so at a sharp vertex both legs' handles pointed along that center
   tangent (e.g. horizontal across a `/\` apex) instead of along their own legs — the
   curve bulged over the corner. Fix (`fit-curve`): a corner pre-pass splits the point
   sequence at vertices whose turn angle exceeds `default-corner-angle` (60°, new
   `corner-index?` on `gpt/angle-with-other` of the in/out segment vectors), and each
   span is fitted independently with its **own** end tangents (`fit-curve*` unchanged per
   span). Corner joins therefore get non-collinear handles that follow each leg (a real
   corner); smooth spans are byte-identical to before (single span ⇒ same tan1/tan2 ⇒
   same output). 60° is principled for RDP-0.3 input (curves of radius > ~2px keep
   per-segment turns < 60°, so no false corners; the sine/single-curve tolerance tests
   still fit smoothly). New `helpers-fit-curve-keeps-sharp-corners` test (corner handles
   ~90°, not ~180°); full common suite 137/448 green. `fit-curve` gained an optional
   `corner-angle` arity (default 60°).
- [x] **Feature 7 — remove node with curve approximation.** Completed 2026-07-11.
   Common layer only. `helpers/fit-cubic` (in `common.types.path.helpers`) is the
   shared least-squares bezier fit (Graphics Gems FitCurves generate-bezier step:
   fixed endpoints, chord-length parameterization, solve the two handler lengths
   along given end tangents, Wu/Barsky chord/3 fallback on degenerate solutions) —
   feature 6 should reuse it. `segment/remove-nodes` was rewritten: consecutive
   removed nodes accumulate into a chain; when the next kept node arrives the whole
   chain (8 samples per original segment) is replaced by one segment — a plain
   `line-to` when the old geometry is flat against the chord (< 0.01 px), otherwise a
   `curve-to` fitted through the samples with tangents taken from the original end
   segments (fallback to nearest-distinct-sample direction when a tangent is
   degenerate). Removed nodes at the ends of a subpath still just drop their
   segments; a chain ending on `close-path` fits toward the subpath start and keeps
   the `close-path` (a zero-length flat closing line is skipped); when the close
   target itself was removed the path closes straight onto its new start (no
   wrap-around fit across the seam — same asymmetry as before). Verified live with
   real gestures: corner polyline → symmetric fitted curve with handlers exactly
   along the old segment directions, path-local undo/redo restores/reapplies it,
   exit persists one commit; collinear middle node → stays a plain line; ellipse
   right node → the two arcs merge into one closed fitted curve.
- [x] **Feature 8 — multi-select and move path handlers.** Completed 2026-07-10.
   Plain click replaces handler selection; Shift+click toggles it; selected handles are
   filled. Dragging any selected handler translates the full selected set using the
   snapped primary delta. Existing opposite-handler angle matching is preserved for
   unselected opposites and suppressed between two selected opposites so neither can
   overwrite the other's movement.
- [x] **Feature 9 — improve single line selection and transformation.** Completed
   2026-07-12. A single selected straight-line path (`path/single-line?`: content is a
   `move-to` + one `line-to`) now shows path-editor-style controls instead of the
   degenerate bounding-box resize/rotate handles: two endpoint handles (`line-handlers*`
   in `viewport/selection.cljs`, rendered by `single-handlers*` when `line?`), and the
   existing selection rect for a middle drag-to-move. Dragging an endpoint runs
   `transforms/start-move-line-point` (re-exported as `dw/start-move-line-point`): the
   grabbed endpoint follows the pointer while the opposite endpoint stays fixed —
   snapping to nearby objects, or constrained to 15° increments around the fixed endpoint
   while Shift is held (reuses `path.helpers/position-fixed-angle`). Preview is live
   (throttled `update-shapes` with `:save-undo? false` on the real shape, no ephemeral
   modifier); each frame rebuilds from the captured original content via
   `path/apply-content-modifiers` + `path/update-geometry` (idempotent, WASM world-coord
   content). The whole gesture collapses to **one** undo entry via preview-then-commit: on
   release (`position-stream … rx/last`) it silently resets to the original geometry
   (`:save-undo? false`) then commits the final position (`:save-undo? true`). The two
   drag branches are combined with **`rx/merge`, not `rx/concat`** — with concat the
   release branch only subscribes after the preview completes at the stopper, so `rx/last`
   never fires and the endpoint never commits (bug caught + fixed 2026-07-12). An earlier
   `dwu/start-/commit-undo-transaction` was dropped: the stream-driven undo watcher
   (workspace.cljs) is async and races the commit, leaving several entries per drag.
   **Rotation handles (added 2026-07-12):** `single-handlers*` for a line renders
   `controls-handlers*` with a new `only-rotation?` flag (threaded into
   `calculate-handlers`, which then returns just the four corner rotation handles) wrapped
   in `[:g.line-controls]` next to `line-handlers*`; `line-handlers*` gained the same
   `refs/current-transform` :move/:rotate gate as the other controls so endpoints hide
   while the line rotates. Verified: node-0 and node-1 endpoint drags (opposite endpoint
   fixed) with selrect recompute; middle drag translates both endpoints equally; Shift
   snaps to exactly 0°/-15°; object snapping; rotation-handle drag 0°→30° (content rotated
   about centre); exactly one undo entry per gesture that fully reverts. Rendering (4
   rotation handles + 2 endpoint handles + 0 resize handles) and the rotation gesture were
   confirmed live via DOM+screenshot; the endpoint-drag undo behaviour was confirmed by
   store-driving (primed `ms/mouse-position` + `:interrupt` stopper) after the WASM canvas
   render died mid-session from repeated hot reloads. Pure-fn test `single-line-predicate`.
   Scope note: WASM-correct (content in world coords); rotated lines keep their transform
   metadata (endpoint edit still works but the oriented bbox is not re-fitted). Only the
   resize handles are replaced (rotation kept); marquee/normal selection is unchanged.
   **Polish (2026-07-12, uncommitted):** a selected single line no longer shows the
   bounding-rectangle outline or the `W × H` size badge. `selection.cljs`
   `single-selection*` detects `path/single-line?` and passes `:hide-outline? true`
   through `controls-selection*` to `selection-rect`, which then draws no stroke and sets
   `pointer-events: all` so the whole selrect stays a draggable body-move target (feature
   9's middle-drag preserved — this *widens* the grab area from the thin outline to the
   full bbox for lines). `measurements.cljs` `selection-size-badge*` returns nil for a
   single-line selection (added `cfh`/`path` requires). Endpoint + rotation handles are
   untouched.
   **WASM render fix (2026-07-12, uncommitted, `render-wasm/src/shapes.rs`):** a *rotated*
   line vanished while being moved. During an interactive (modifier) drag the renderer
   blits a cached snapshot of the shape clipped to `drag_crop_clip_path` (document space).
   That clip double-applied the shape transform to the stroke region: `stroke_to_path`
   already returns the region in document space (it undoes `path_transform` internally),
   but the code then applied `shape_document_transform()` a second time. For a rotated
   shape this pushed the stroke clip off the shape; a line has an empty fill clip (open
   path), so the mis-placed stroke clip was the *only* clip → the blit was clipped to
   nothing and the line disappeared. Non-rotated lines escaped (identity transform =
   no-op; axis-aligned lines are also skipped from the cache via degenerate bounds at
   `render.rs:1950`). Fix: drop the extra `make_transform(shape_document_transform())` on
   the stroke region (fill stays local→doc via `get_skia_path` + `shape_document_transform`;
   both end in document space, consistent). Also fixes stroke-clip placement for rotated
   *closed* stroked paths during drag (previously masked by their non-empty fill clip).
   Requires a `render-wasm/./build` + browser hard-refresh.
   **Hide controls during endpoint drag (2026-07-12, uncommitted):**
   `transforms/start-move-line-point` now sets `[:workspace-local :transform] :move` for
   the duration of the drag (wrapping the merged preview+commit stream in `rx/concat`:
   set `:move` → merge → clear to nil). Both `line-handlers*` and the `:only-rotation?`
   `controls-handlers*` already hide when `refs/current-transform` is `:move`/`:rotate`,
   so this suppresses the rotation handles (and their rotation cursor) while an endpoint is
   being dragged. The `rx/concat` wraps the *whole* merge, so both drag branches still
   subscribe together at drag start (the `rx/last` release branch keeps firing — the
   earlier concat-splitting bug is not reintroduced).
- [x] **Feature 10 — move segments without moving the nodes.** Completed 2026-07-12.
   A segment drag with the platform modifier held (`ms/mouse-position-mod`, ctrl on
   win/linux, meta on mac) bends the grabbed segment through the pointer while its
   endpoint nodes stay fixed; without the modifier it translates the endpoint nodes as
   before (feature 1). Common (`helpers.cljc`): `curve-closest-t` (recursive subdivision,
   parameter of the grabbed point) and `bend-curve-deltas` (handle deltas that move the
   on-curve point at `t` onto the target, distributed to both control points as the
   minimum-movement least-squares solution weighted by the Bernstein basis, so a straight
   segment bulges symmetrically). Frontend (`edition.cljs`): `start-move-path-segment`
   now dispatches through `start-segment-drag`, which reads the modifier at drag-confirm
   and routes to either `drag-selected-segments` (translate) or `bend-selected-segment`
   (bend). The bend captures the grab `t` once (so that exact point tracks the pointer),
   represents a `line-to` as the degenerate cubic `[start end start end]`, and each tick
   sets `content-modifiers` `{index {:c1x… :c2x…}}`; `path/apply-content-modifiers`
   promotes the `line-to` to `curve-to` on the handle deltas. `close-path` segments carry
   no handles and always translate. Verified live: Ctrl-dragging a line segment produced
   a `curve-to` whose point at t=0.5 landed exactly on the target with both endpoint nodes
   fixed; pure-fn tests cover `curve-closest-t` and the passes-through-target property.
- [x] **Feature 11 — flip selected nodes horizontally / vertically.** Completed
   2026-07-12 (requested mid-session). `Shift+H` / `Shift+V` mirror the selected nodes
   (or the whole path when nothing is selected) across the center of their bounding box:
   horizontal reflects x, vertical reflects y. Common (`segment/flip-content`, wrapped by
   `path/flip-content`): reflects each selected node's anchor and the handles owned by a
   selected node (`c1` of command i belongs to node i-1, `c2` to node i), so a fully
   selected path/subpath mirrors cleanly. Frontend: `tools/flip-nodes` (indices are
   preserved, so the selection stays valid; no structural change). Bound in
   `shortcuts.cljs`; `Shift+H/V` were free (`Ctrl+V` stays node-paste, plain `v` stays on
   the Escape binding). Verified live: mirrors nodes+handles across the bbox center, is
   its own inverse (double-flip restores exactly), and the event round-trip exits clean.

- [x] **Feature 12 — flatten a single path (reset its selrect to the x/y axes).**
   Completed 2026-07-12. Flatten now works on a single selected path and resets its
   rotated bounding box back to the standard axes without moving the shape. The context
   menu (`context_menu.cljs`) gate was relaxed to also show flatten for a single path
   (`(or (not has-path?) (and single? has-path?))`). `convert-selected-to-path`
   (`path/shapes_to_path.cljs`) gained `flatten-path` for the already-`:path` case (WASM
   branch): **WASM stores path `:content` in world coordinates** (rotation baked into the
   content; `:transform`/`:selrect`/`:points` are oriented-bbox metadata), so flattening
   must *not* re-bake the transform — it only clears `:rotation`/`:flip-x`/`:flip-y`/
   `:transform`/`:transform-inverse` and re-derives `:selrect`/`:points` from the content
   via `update-geometry`. (An initial attempt that baked `transform-matrix` into the
   content double-rotated it — found and fixed live.) Verified live on WASM: rotate a
   triangle 30°, flatten → content unchanged (shape stays put), `:rotation` 0, and
   `:points` become an axis-aligned rectangle tightly wrapping the geometry. The
   non-WASM/legacy branch is left as the original `convert-to-path` (unverified there, so
   the reset is WASM-only for now).
- [x] **Feature 13 — separate a single selected node.** Completed 2026-07-12.
   `segment/separate-nodes` now dispatches on selection size: two or more selected
   points behave as before (drop the segments between them); a **single** selected node
   is split by the new `separate-node`, which finds the interior node (a non-`move-to`
   whose next command is a real segment), keeps the incoming side in place, and starts a
   new subpath at the node offset by `separate-node-offset` (8,8), carrying the outgoing
   curve's leading `c1` handle along so the tangent is preserved. `check-enabled`
   (`path_actions.cljs`) enables the button for the single-node case
   (`(or segments-selected? (= num-points 1))`); the `k` shortcut already fires
   unconditionally. Note: `process-path-tool` runs `close-subpaths` afterwards, so on a
   closed subpath the two offset ends stay a small distance apart (no re-merge) while the
   loop opens at that node. Verified live: separating a triangle node opened the loop
   with the two ends ~11 px apart; endpoint nodes and curve handlers covered by
   pure-fn tests. Limitation: a node whose following command is the `close-path` seam is
   not separable (its outgoing "segment" is the implicit closing line).
- [x] **Feature 14 — 15° angle constraint (was 45°).** Completed 2026-07-12.
   `closest-angle` in `common/src/app/common/types/path/helpers.cljc` now snaps to the
   nearest 15° via `(mth/round angle 15)` (was 8 fixed 45° buckets). This feeds
   `position-fixed-angle`, so **every** Shift-constrained path operation uses the finer
   angle: draw preview (`preview-next-point`/`add-node`), Shift node-drag, `drag-handler`,
   and `move-handler-stream` (intended per the request). Verified live:
   (10,6)@≈31°→30°, (10,2)@≈11°→15°, (3,10)@≈73°→75° — all on 15° increments (the old
   code snapped 31° to 45°).

- [x] **Feature 15 — show the path edition top bar during path creation.** Completed
   2026-07-13 (requested mid-session). The top bar (`path-actions*`) previously appeared
   only while editing a committed path, because its visibility gate keyed on
   `[:workspace-local :edition]` (`path-editing? (some? edit-path-state)`, with
   `edit-path-state (get edit-path edition)`), and `:edition` is nil during initial
   creation. Now it is also shown while drawing a new path, so node snap can be toggled
   mid-draw. Key insight: during creation the ephemeral edit state already exists — it is
   keyed by the **in-progress drawing object id** (`st/get-path-id` prefers
   `[:workspace-drawing :object :id]` over `:edition`), which is where `start-edition`
   writes `:edit-mode :draw` and where `toggle-snap`/`snap-toggled-stream` read/write
   `:snap-toggled`. Both viewports (`viewport.cljs`, `viewport_wasm.cljs`) gained two
   bindings — `path-bar-state` (`edit-path-state` when editing, else
   `(get edit-path (get drawing-obj :id))` when `path-drawing?`) and `path-bar-shape`
   (`editing-shape` when editing, else `drawing-obj`) — and the render gate became
   `(or (and path-editing? single-select?) (and path-drawing? (some? path-bar-state)))`.
   **`path-editing?` semantics were deliberately left unchanged** (it drives `on-pointer-down`
   routing, path-editor rendering, `show-measures?`/`show-snap-points?`, etc.), so only the
   bar gate is affected. During creation the node-editing buttons are disabled (empty
   selection), "Draw nodes" shows toggled, and the snap toggle is live. Limitation: the
   "Move nodes" button is a no-op mid-creation because `change-edit-mode` keys on
   `:edition` (nil during creation) — acceptable, draw is the only meaningful mode before
   the shape is committed. Verified live (WASM viewport, real pointer gestures): picked the
   Path tool, placed two nodes → the bar rendered with "Draw nodes" toggled and "Snap
   nodes" enabled; clicking snap flipped `:snap-toggled` (button `is-toggled` false→true);
   exiting cleared the bar (no leftover). `cljfmt` clean, `clj-kondo` 0/0, main build `:ok`.
   **Polish (2026-07-13, uncommitted): collapse the main toolbar during creation.**
   The top toolbar (`top_toolbar.cljs`) and the path edition bar are both centered at the
   top, so they overlapped while drawing a new path. `top_toolbar/toolbar-hidden-ref`
   already collapsed the toolbar for existing-path editing (`is-path-editing`, keyed on
   `:edition` + single selection), but during creation `:edition` is nil and the edit
   state is keyed by the in-progress drawing object id, so the toolbar stayed expanded.
   The ref now derives from `st/state` (was `refs/workspace-local`) and adds
   `is-path-drawing` — `edition` nil and edit state present for `pst/get-path-id state`
   (which prefers `[:workspace-drawing :object :id]`); it self-gates because `:edit-path`
   only holds entries for paths, so a plain rect draw is unaffected. Toolbar collapses to
   its thin strip (same animation as existing-path editing) in both cases; the
   `:hide-toolbar` user preference is still respected otherwise. `cljfmt` clean,
   `clj-kondo` 0/0, main build `:ok`; REPL-verified the ref returns hidden for
   creation/editing and respects the preference for a rect draw / idle.

- [x] **Feature 16 — insert a node by clicking a segment's middle (with hover preview).**
   Completed 2026-07-13, spec settled same day. Behaviour (the user's exact requirement):
   a plain click on a segment **selects** it (feature 1 unchanged), **except** near the
   segment's exact **middle** (t=0.5), where it **inserts** a node instead; the preview
   appears **only** when a click would insert (i.e. near the middle) — **no Shift needed**.
   Shared helpers in `data/workspace/path/helpers.cljs`: `segment-mid-point` (via
   `path.helpers/curve-values` at t=0.5, lines→degenerate cubic) and `insertion-point`
   (content → the nearest segment-middle within `segment-insert-threshold` (12 px / zoom),
   carrying `{:from-p :to-p :t 0.5}` split meta, or nil; close-path segments excluded).
   `editor.cljs` `use-stream` renders the dashed preview at `insertion-point` (so it only
   shows near a middle). Insertion runs through the segment's own pointer-down
   (`edition/start-move-path-segment`): the drag-stream **click** branch uses the same
   `insertion-point` — non-nil → `create-node-at-position (meta)` → `split-segments`, else
   `select-segment`; a **drag** still translates/bends (features 1/10). The preview node is
   always non-interactive: `.hover-point` group pointer-events none AND the `is-new` hit
   circle pointer-events none (an explicit "visible" on the child would override the group),
   so the click reaches the segment. Verified live: preview absent at ¼ point, present at
   the exact middle (150,2950); off-middle click selected the segment; middle click inserted
   at exactly the middle (`M-50 L350` → `M-50 L150 L350`); no Shift used.
- [x] **Feature 17 — "add node" toggle (shortcut `n`, toolbar toggle).** Completed
   2026-07-13. A toggle *state of Move mode* (`:add-nodes` flag; `tools/toggle-add-nodes` +
   `tools/set-add-nodes`, init false in `start-path-edit`). It relaxes feature 16 from
   middle-only to **insert anywhere**: while ON, `insertion-point` is called with
   `anywhere?` true (closest point on the path within threshold, via `path/closest-point`),
   so the preview follows the cursor and a click inserts anywhere on a segment (never
   selects); OFF is the middle-only behaviour above. Both use the same
   `insertion-point`/`start-move-path-segment` path; drag still bends/translates in both.
   Toolbar toggle button (`path_actions.cljs`, is-toggled, disabled outside move mode;
   placeholder `pentool` icon — no dedicated glyph yet). Shortcut **`n`** (`p` stays Draw).
   `esc-pressed` first branch: while ON, Escape only turns it off and keeps the editor open.
   i18n `workspace.path.actions.add-nodes` in `en.po` (dev bundle shows the raw key until
   translations recompiled). `cljfmt` clean, `clj-kondo` 0/0, main build `:ok`.
- [x] **Feature 18 — default path stroke alignment to center.** Completed 2026-07-13.
   `minimal-path-attrs` (`common/src/app/common/types/shape.cljc`) — the path-creation
   default merged in by `setup-shape` for `:path`/`:curve` — now sets
   `:stroke-alignment :center` instead of `:inner`. Scoped to creation only: every other
   shape type keeps its own minimal default, the manual "+ add stroke" (`cts/default-stroke`
   / `colors/build-stroke-style-attrs`) is untouched, shape→path conversion preserves
   existing strokes, and an explicit `:strokes` in the creation props (e.g. the Line/Arrow
   tool, already center) still wins over the minimal default via `setup-shape`'s merge.
   REPL-verified: `(-> (cts/setup-shape {:type :path}) :strokes first :stroke-alignment)`
   → `:center`.
- [x] **Feature 19 — Alt toggles handler join in both directions.** Completed 2026-07-13.
   The join is *derived*, not stored: two handles are "joined" when collinear on opposite
   sides (~180°, `helpers/handlers-joined?`). Alt now toggles relative to that state — a
   joined pair **breaks** (opposite stays put), an independent (corner) pair **re-joins**
   into a full mirror (opposite angle + equal length). `move-handler-modifiers` gained a
   `rejoin?` arity that forces the opposite handle onto the mirror
   `node - hnv - opposite` regardless of current angle; `modify-selected-handlers` takes
   `alt?` (was `match-opposite?`) and per-node derives
   `match-opposite? = (and joined? (not alt?) (not opp-selected?))` and
   `rejoin? = (and (not joined?) alt? (not opp-selected?))`. Keep-smooth (no-alt on a
   joined node) is unchanged (incremental angle match). Re-join persists in geometry, so
   the node stays smooth on the next no-alt drag. Pure-fn + event tests added
   (`handlers-joined-detects-…`, `handler-drag-without-alt-keeps-…`,
   `handler-drag-with-alt-breaks-…`, `handler-drag-with-alt-rejoins-…`).
- [x] **Feature 20 — move handlers while drawing/creating a path.** Completed 2026-07-13.
   Placed-node Bézier handlers are now grabbable in draw mode, not just move mode. Two
   parts: (1) **id resolution** — `start-move-handler`/`drag-selected-handlers` (and
   `modify-selected-handlers` via its passed id) resolved the path id from
   `[:workspace-local :edition]`, which is **nil during creation**; switched to
   `st/get-path-id` (prefers `[:workspace-drawing :object :id]`), so the handler-drag
   pipeline targets the drawing-object-keyed edit-path in both draw and move mode.
   (2) **editor interactivity** — `path-handler*` keys pointer-events / pointer-down /
   move-cursor on `is-interactive (some? index)` (placed-node handlers, `visible`;
   transient draw/prev cursors have no index → inert) instead of `is-move`/`is-draw`, and
   the `:g.point-handlers` wrapper dropped its draw-mode `pointer-events none`. Handlers
   still `stop-propagation` and the viewport only emits `:down` for `viewport-controls`,
   so grabbing a handler mid-draw never spawns a spurious `add-node`. The next-point
   preview is suppressed while a modifier drag is active (`preview-next-point` no-ops when
   `:content-modifiers` is non-empty) so the pending segment doesn't chase the cursor
   during a handler drag. Verified: REPL store-drive (draw-mode state, `:edition` nil →
   modifiers written under the drawing-object id; preview suppressed during a drag) and an
   event test (`next-point-preview-is-suppressed-during-a-modifier-drag`).
- [x] **Feature 21 — move segments and handlers with arrow keys.** Completed 2026-07-13.
   Arrow / Shift+arrow now nudge selected **segments** and **handlers** too, not just
   nodes (1px, or 10px with Shift, same pipeline). `move-selected` (the arrow event) now
   reads the whole grouped selection: `node-indices = selected-nodes ∪
   helpers/segment-node-indices(selected-segments)` moved rigidly as positions (endpoint
   nodes + their handlers), and `selected-handlers` (filtered to `:curve-to` commands, so
   a stale id can't re-curve a line) moved as lone control points. `set-move-modifier`
   gained a `handler-ids` param: it still accumulates the delta into the node `points` via
   `modify-content-point` (node + attached handlers rigid) and now also `update`s each
   `[index prefix]` handler's `cx/cy` by the same delta. Node/segment moves stay
   position-based (existing duplicate-coordinate collapse unchanged); handler moves are
   index-based. A handler that belongs to a moved node just gets the same delta written
   twice (idempotent). No opposite-angle matching on arrow nudges (pure translation, like
   nodes). Tests `arrow-move-nudges-selected-handlers` and
   `arrow-move-nudges-selected-segment-endpoints`; focused suite 35/89 green.
- [x] **Feature 22 — merge nodes across subpaths (not just connected nodes).** Completed
   2026-07-13. The merge-nodes button previously only worked on nodes that already share a
   segment (`get-segments-with-points` empty ⇒ no-op) and was disabled otherwise. Now
   merging disconnected nodes behaves exactly as if a line were drawn between them and then
   merged. Common (`segment/merge-nodes`): always `join-nodes` first (creates straight
   segments between selected points that aren't already connected; a no-op for connected
   pairs, so connected merges are unchanged), then run the existing merge (group the
   segments, `calculate-merge-points` = `gpt/center-points`, `separate-nodes` +
   `replace-points`). Result: every selected node collapses onto the group centroid and the
   join line leaves no leftover (it becomes degenerate and is filtered by `separate-nodes`).
   Frontend gate (`path_actions/check-enabled`): `:merge-nodes` relaxed from
   `segments-selected?` to `(and points-selected? (>= num-points 2))` so the button is
   enabled for any 2+ selected nodes. Verified JVM: two disconnected subpaths, merge two
   endpoints → both land on the midpoint `(5,5)`, join line gone; connected chain still
   collapses to its centroid unchanged. Test `path-merge-disconnected-nodes`; common suite
   138/449 green.
- [x] **Feature 23 — separate a junction node into one node per line.** Completed
   2026-07-13. Generalizes feature 13: separating a single selected node where **several**
   lines meet (e.g. the coincident endpoints of different subpaths, or a merged point) now
   produces one offset open end per incident segment, not just the first interior split.
   Common (`segment/separate-node` rewritten as a single content walk): for position P it
   handles every occurrence — a `:move-to` at P (subpath start) shifts so its departing
   line leaves offset; an interior arriving segment (real next segment) keeps its arriving
   end and breaks the departing one into a new subpath (as before); an arriving segment
   that ends its subpath shifts its end. The first end keeps P and each following end is
   shifted by an increasing multiple of `separate-node-offset` (8,8), carrying the adjacent
   handler. A lone endpoint (k=0, zero offset) is still a no-op, and a node before a
   `close-path` seam is still left alone. Verified JVM: interior single node unchanged
   (`M0,0 L5,5 M13,13 L10,10`); two subpaths meeting → each line offset; three lines →
   three distinct ends `(5,5)/(13,13)/(21,21)`. Tests
   `segment-separate-single-node-junction{,-three-lines}`; common suite 140/454.
- [x] **Feature 24 — drag a node or segment onto another to auto-merge.** Completed
   2026-07-13 (extended to segments same day). After a node **or segment** drag, if any
   moved node was dropped within `merge-drop-distance` (10px / zoom) of another node, the
   closest such pair is merged automatically (reuses feature 22's `merge-nodes` via
   `tools/process-path-tool`). Frontend (`edition.cljs`): both `drag-selected-points` and
   `drag-selected-segments` now emit `(apply-content-modifiers)` then
   `(merge-dragged-on-drop)`. That event computes the moved nodes as the selected `:nodes`
   **plus** the endpoint nodes of any selected `:segments` (`helpers/segment-node-indices`),
   then picks the closest (moved, non-moved) node pair within threshold and merges it.
   Dropping the end of one subpath onto the start of another welds them into one continuous
   path (end→start weld); other topologies collapse to the midpoint per feature 22.
   Exact-coincident nodes are deduped by `get-points` (no separate target ⇒ already merged,
   no-op). Required a new `edition → tools` require (verified acyclic:
   `tools`→`dwe/helpers/state/path`, none lead back to `path.edition`). Test
   `dragging-a-node-or-segment-onto-another-merges-them` (node near ⇒ weld; segment
   endpoint near ⇒ weld; far ⇒ no-op); frontend suite 36/94.
- [x] **Feature 25 — half-pixel node movement at high zoom (> 300%).** Completed
   2026-07-14. When `:snap-pixel-grid` is on (the default), the path drag pipeline quantised
   every position to whole pixels via `streams/to-pixel-snap` (`gpt/round`), which is too
   coarse when zoomed in. `to-pixel-snap` now reads the current zoom and rounds with
   `gpt/round-step` to **0.5** above the threshold (`half-pixel-snap-zoom` = 3, i.e. zoom
   strictly `> 300%`) and **1** at/below it. Since `to-pixel-snap` is the shared snap step
   for `drag-stream`'s start point, `move-points-stream` (nodes) and `move-handler-stream`
   (handlers), the finer step applies uniformly to every drag while pixel-snap is on; with
   pixel-snap off, positions still pass through untouched. Arrow-key nudges are a separate
   pipeline (fixed 1px/10px steps) and are intentionally left discrete. Verified with a new
   deterministic unit test `to-pixel-snap-quantises-to-half-pixels-past-the-zoom-threshold`
   (drives the global store: zoom 1 & exactly-3 → whole pixel, zoom 6 → half pixel, snap-off
   → passthrough); focused suite 41 tests / 110 assertions, 0 failures; `clj-kondo` 0/0,
   `cljfmt` clean, compiler `:ok`. (Note: feature 26 will separately drop pixel snapping for
   handlers entirely, superseding the handler part of this shared step.)
- [x] **Feature 26 — disable pixel snapping for handler movement.** Completed 2026-07-14.
   `streams/move-handler-stream` (the move-mode handler drag, also used while drawing per
   feature 20) mapped every pointer position through `to-pixel-snap`, so Bézier handles
   snapped to the whole/half-pixel grid. Replaced that `(rx/map to-pixel-snap)` with
   `(rx/filter gpt/point?)`, so handle positions are raw sub-pixel; object/path snapping
   (`check-path-snap`, gated on the node snap toggle) and the opposite-handle angle match are
   preserved. The filter also drops nil positions defensively (previously a nil pointer value
   could slip through `to-pixel-snap` into an x/y-less position map) — strictly safer, and no
   behaviour change during a real drag where the pointer is always a valid point. **Scope:**
   only the handler stream — node moves still go through `to-pixel-snap` (feature 25), and the
   draw-mode "drag out a new handle right after placing a node" gesture uses `position-stream`
   (tied to the node position) and stays pixel-snapped. Not unit-testable at the stream level
   (reads the global `ms/mouse-position`, the documented pointer-stream sim limitation in §7);
   verified by no-regression (focused suite 41 tests / 110 assertions, 0 failures), `clj-kondo`
   0/0, `cljfmt` clean, compiler `:ok`. Live sub-pixel confirmation deferred (blocked by the
   flaky browser session this session).
- [x] **Feature 27 — alignment / distribute options for path nodes.** Completed 2026-07-14.
   The align (hleft/hcenter/hright/vtop/vcenter/vbottom) and distribute (horizontal/vertical)
   actions operate on the **selected path nodes** during path edition. Common
   (`segment/align-content` + `distribute-content`, wrapped by `path/…`): mirror
   `flip-content`'s handle ownership (`c1` of command i belongs to node i-1, `c2` to node i)
   via a shared `translate-nodes` that moves each selected node's anchor **and its attached
   handles rigidly** by a per-node delta — align sets the relevant coord to the bbox
   edge/center (needs ≥2 nodes), distribute spaces the sorted nodes evenly between the two
   extremes (needs ≥3). Indices are preserved, so the selection stays valid (no remap).
   Frontend: `tools/align-nodes`/`distribute-nodes` events (mirror `flip-nodes` — set-content
   + update-geometry on the drawing copy), re-exported from `path.cljs`. The align buttons
   (`menus/align.cljs` `align-options*`) gained a `path-edit?`/`node-count` mode: while editing
   they emit the node tools and enable on 2+/3+ selected nodes instead of `dw/align-objects`.
   Verified: 4 common pure-fn tests (`segment-align-content{,-partial-and-guard,-moves-handles}`,
   `segment-distribute-content`) + 2 frontend store tests
   (`align-nodes-aligns-selected-nodes-to-an-edge`, `distribute-nodes-spaces-selected-nodes-evenly`,
   exercising the event → drawing-content path); common suite 144/462, frontend focused 43/116,
   0 failures; `clj-kondo` 0/0, `cljfmt` clean, compiler `:ok`.
   **Refinement (2026-07-14): coincident nodes distribute as one.** `distribute-content` groups
   the selected indices by position, so nodes sharing a position (e.g. a line-closed loop's
   seam, where the start and the trailing line-to coincide) count as a **single** node — they
   receive the same delta and stay together instead of splitting the path open. The `≥3` guard
   now counts **distinct positions**. Align was already correct for coincident nodes (they move
   to the same target line together), so it is unchanged. Test
   `segment-distribute-content-keeps-coincident-nodes-together`; common suite 145/464.
- [x] **Feature 28 — right options panel content during path editing.** Completed 2026-07-14.
   The panel looked empty during edition because the drawing copy populates
   `[:workspace-drawing :object]`, so `options/design-menu*`'s `cond` fell to the
   `(d/not-empty? drawing)` → `drawing-options*` branch. Now `design-menu*` detects
   path editing (new `refs/workspace-edit-path` ⇒ `(get edit-path edition)`; scoped to
   **existing-path** edition via `:edition`, not new-path creation) and: renders
   `align-options*` with `:path-edit?`/`:node-count` (feature 27), **hides** `bool-options*`
   (wrapped in `when-not path-editing?`), and adds a path-editing branch **before** the drawing
   branch that renders the new `options/shapes/path/path-edition-options*` — measures, fill,
   stroke, shadow, blur only (layout, export, constraints, layer, svg-attrs, boolean omitted).
   The menus target the **stored** shape `(get objects edition)` (real fills/strokes, not the
   drawing copy). The align/distribute row is **always shown while editing a path** (its buttons
   just disable when too few nodes are selected): `align-options*`'s visibility gate became
   `(when (or path-edit? (not (and disabled-align disabled-distribute))) …)`, so it no longer
   hides when nothing is selected mid-edit. `clj-kondo` 0/0, `cljfmt` clean, compiler `:ok`.
   **Verified live 2026-07-14** (Playwright screenshot, editable workspace): the path-edition
   panel renders align/distribute + measures + fill + stroke + shadow + blur, no
   boolean/layout/export.

   **Panel-edit polish (2026-07-14):**
   - **Read-only measures.** The measures block in `path-edition-options*` is wrapped in a
     `{:style {:pointer-events "none" :opacity 0.6}}` container (user decision = option 2):
     size / position / rotation are **visible but non-interactive**. Geometry edits during node
     editing neither preview nor persist reliably (`finalize-path-content` overwrites
     `:content`, which for WASM has rotation baked in), so they must not be editable here.
     Chosen over a `read-only?` flag through `measures-menu*` (its inputs use inconsistent
     `:is-disabled`/`:disabled` — the `:y` field's `:disabled` is a pre-existing no-op).
   - **Live preview via render-time merge (NOT a store watcher).** Panel edits commit to the
     **stored** shape, but the editor renders the **drawing copy** — so styling changes didn't
     preview until exit. Both viewports (`viewport.cljs`, `viewport_wasm.cljs`) now compute
     `draw-area-shape`: while path-editing, `(merge drawing-obj (select-keys stored [:strokes
     :shadow :blur :background-blur :opacity :blend-mode]))` plus `:fills` overridden **only
     when the stored shape has its own** (so bug 14's inherited/SVG-default fill resolved into
     the drawing copy is preserved), and pass it to `draw-area*` instead of the raw drawing-obj.
     Reactive on `base-objects`, so a panel edit → objects change → viewport re-render →
     `draw-area-shape` recomputes with the new attrs → live preview. **Crucially this is a pure
     rendering change** (no watcher / event / `swap!`), so — unlike the reverted store-watcher
     attempt — it cannot touch the edit lifecycle or re-enter a store swap. **A first attempt
     used a `start-path-edit` watcher + `rx/from-atom` sync (`edit-shape-attrs-stream` /
     `sync-drawing-attrs`) that passed unit tests but broke live node-editing/exit — `from-atom`
     fires re-entrantly inside the store swap (§3), a hazard the static test store never
     exercised; it was reverted and replaced by this render-merge.** Geometry (rotation /
     position / size) is intentionally NOT merged — owned by the edited drawing copy — matching
     the read-only measures. `clj-kondo` 0/0, `cljfmt` clean, compiler `:ok`.
     **Live edit-mode not re-verified this session** — automation can't enter path edit
     (keyboard focus unavailable + synthetic pointer events don't feed the store's hit-test /
     hover, so double-click doesn't trigger edit-mode entry); **needs a quick by-hand check**:
     edit a path, change fill/stroke → previews live; confirm nodes still drag and Esc exits.
- [x] **Feature 29 — visual (arc-length) half-point for mid-segment node insertion.**
   Completed 2026-07-14. Feature 16 inserted at the Bézier parameter `t = 0.5`, which on uneven
   curves drifts toward the sharper end (not the visual middle). Common
   (`helpers/curve-arc-length-t`): flattens the cubic into `arc-length-samples` (100) chords,
   accumulates length, and returns the `t` where cumulative length reaches half the total
   (linearly interpolated within the crossing sample interval); a straight/degenerate cubic
   returns 0.5. Frontend (`data/workspace/path/helpers.cljs`): `segment-mid-point` and
   `insertion-point` now compute `t = curve-arc-length-t(base-curve)` once and use it for
   **both** the preview point (`curve-values` at that `t`) and the `:t` split meta — so the
   hover preview and the inserted node land on the same arc-length midpoint. The split itself
   already honours the meta `t` (`segment/split-segments` → `split-curve-to`/`split-line-to` at
   `value`). Lines are unaffected (arc-length `t` = 0.5). Verified: common test
   `helpers-curve-arc-length-t` (line → 0.5; a slow-start curve where `h1=start` → `t > 0.5`
   with the length-up-to-`t` = half-total property holding within 1%); common suite 146/467,
   `clj-kondo` 0/0, `cljfmt` clean, compiler `:ok`. (Pure geometry + a direct `0.5 → t`
   substitution feeding the existing split path — no lifecycle/render risk; live hover/insert
   by-hand check still owed given automation can't enter edit mode this session.)
- [x] **Feature 30 — segment-targeted operations (flip, delete, merge).** Completed
   2026-07-14. A selected segment now contributes its endpoint nodes to node operations. New
   `helpers/selected-node-indices content selection` = `:nodes` ∪
   `segment-node-indices(:segments)`; wired into `tools/process-path-tool` (so merge / make-
   corner / make-curve / separate / … act on segment selections) and `tools/flip-nodes`
   (Shift+H/V flips the segment's endpoint nodes), and `path_actions/check-enabled` is fed the
   effective positions so the top-bar buttons enable for segment selections.
   - **Merge** (segment) collapses the segment's two endpoints to their centroid (via the
     existing `merge-nodes`).
   - **Delete** is now `tools/delete-selected` (bound to the Delete key **and** the remove-node
     button): with a **segment** selection it **opens the path at those segments**
     (`separate-nodes` — drops just the selected segments, keeping the nodes) rather than
     healing them away; with a **node** selection it removes the nodes (`remove-node`) as
     before. Chosen over remove-nodes-of-endpoints because that heals a fitted curve across the
     gap, removing the two neighbouring segments too — not "delete *the* segment". (Edge case:
     deleting a subpath's first/last segment drops the now-dangling end node instead of making
     an empty subpath.)
   Verified: frontend store tests `selected-node-indices-folds-segment-endpoints`,
   `flip-nodes-includes-selected-segment-endpoints`,
   `merge-nodes-includes-selected-segment-endpoints`,
   `delete-selected-opens-segments-else-removes-nodes`; focused suite 47/125, 0 failures;
   `clj-kondo` 0/0, `cljfmt` clean, compiler `:ok`. Live by-hand check still owed (automation
   can't enter edit mode this session).
- [x] **Feature 31 — temporary 80% fill opacity while editing a path.** Completed 2026-07-14.
   Reused the feature-28 render-time merge point: the previously-duplicated `draw-area-shape`
   logic in both viewports was extracted to `drawarea/path-edit-shape [drawing-obj stored]`,
   which merges the stored shape's live visual attrs (feature 28 preview) **and** dims the
   fills to `edit-fill-opacity` (0.8) via `dim-fills` (multiplies each fill's `:fill-opacity`,
   so a fill already below 100% still dims). Both viewports now call it while path-editing.
   **Purely visual** — it only affects the shape `draw-area*` renders (the drawing copy), never
   the stored `:fills` or the drawing copy's own `:fills`, so nothing persists on exit and no
   restore step is needed; it also naturally reverts because `draw-area-shape` falls back to the
   raw `drawing-obj` once `path-editing?` is false. Rendering-only, no lifecycle/persistence
   risk. `clj-kondo` 0/0, `cljfmt` clean, compiler `:ok`. (No unit test — a 4-line pure fn in a
   UI namespace; live by-hand check owed as automation can't enter edit mode this session.)
- [x] **Feature 32 — snap-to-node on by default when creating / editing a path.** Completed
   2026-07-14. The node snap toggle (`:snap-toggled`) now defaults **on** for both flows.
   Existing-path edition (`edition/start-path-edit`): the fresh-state literal flipped
   `:snap-toggled false` → `true` (only the newly-created `:edit-path` map, so re-entering
   an already-open edition preserves the current value). New-path creation
   (`drawing/start-edition`): its `assoc-in :edit-mode :draw` became an `update-in` that also
   `(update :snap-toggled (fnil identity true))` — defaults to `true` when the flag is unset
   (first draw) yet **keeps the user's explicit toggle across draw-loop restarts** (`fnil`
   only replaces nil, so a mid-draw turn-off survives a `start-created-path-edition` /
   `start-draw-mode*` re-entry). Feature 15 already surfaces the toggle mid-draw. Compiler
   `:ok`, `clj-kondo` 0/0, `cljfmt` clean. (Trivial initial-value flip; live by-hand check
   owed with the other path-edition behaviours — automation can't enter edit mode this
   session.)
- [x] **Feature 33 — duplicate nodes & segments (Ctrl+D, Alt+drag).** Completed 2026-07-14.
   `Ctrl+D` (`clipboard/duplicate-selected`, shortcut `:duplicate` = `(ds/c-mod "d")`) and
   **Alt+drag** both duplicate the current node/segment selection.
   **Node-duplication geometry (user decision + correction this session):** duplicating a node
   copies its **incoming segment** into a new subpath that starts **attached to the node's
   predecessor** (so the copy's line still comes from the same place — the original complaint
   was that it came from the node itself) and ends in **one** new node shifted by the offset (the
   only genuinely new point). Common `path/duplicate-node-content [content index node-offset]`:
   finds the node's incoming `segment-entry` (`from` = predecessor, `segment` = incoming
   command), returns `[move-to(from) (move-segment-end segment node-offset)]` where
   `move-segment-end` shifts only the endpoint anchor and its **arriving** handle (`:c2`),
   leaving the departing handle (`:c1`, near the predecessor) in place. Returns nil for a subpath
   start (no incoming segment) — duplicating a subpath's first node is a no-op (acceptable;
   end/interior nodes all have an incoming segment).
   **Selection → sub-content** (frontend `helpers/duplicate-selection-content
   [content selection offset]` → `{:sub <commands> :selected #{rel-indices}}`): selected segments
   (and segments between two selected nodes) copy via `path/extract-content` (preserves curves),
   shifted by `offset`; every **remaining** lone node (not already covered by a duplicated
   segment, via `fragment-covered-nodes`) is copied. `:selected` holds every genuinely **new**
   node — all fragment nodes plus each copied node's endpoint — but **never a copied node's
   attach point**, which stays coincident with the original. This is load-bearing: the move
   pipeline is position-based, so selecting the coincident attach point would drag the original
   node along with the copy (the bug that made Alt+drag/node-Ctrl+D appear "not working").
   **Flows:** `edition/splice-duplicated {:sub :selected}` appends the sub-content and selects
   `base + selected`. Both `Ctrl+D` and Alt+drag use `edition/duplicate-offset = (10,10)` so the
   copy never lands exactly on the original (position-based collision). `Ctrl+D`
   (`duplicate-selected`) then `change-edit-mode :move` (only when `:edition` present).
   **Alt+drag** — `edition/start-node-drag` (new dispatcher in `start-move-path-point`) and
   `start-segment-drag` decide on Alt at drag-confirm; when held they emit
   `duplicate-and-drag` → `splice-duplicated` → `drag-selected-points`. **Reliability fix:** the
   Alt flag is now **captured from the pointer-down DOM event** (`kbd/alt? event`, threaded as a
   new `alt?` arg through `start-move-path-point`/`start-move-path-segment`) instead of deref-ing
   `@ms/mouse-position-alt` at drag-confirm — that behavior subject is fed from the same shared
   `pointer` stream but subscribed *after* `ms/mouse-position`, so the depth-first re-entrant
   cascade into `start-node-drag` reads it one pointer event stale (Alt worked only when it had
   been held during hover; flaky on fast drags / late Alt — the reported "sometimes doesn't
   work"). Dragging the pasted
   endpoint(s) translates a copied segment; a copied node's new endpoint tracks the cursor (delta
   from its own start; one-frame ~10px jump from the offset start) while its line stays attached
   to the predecessor. Bend (platform-mod) and translate branches unchanged.
   **Verified:** common `path-duplicate-node-content` (predecessor-attach line/curve, verbatim
   no-offset, subpath-start nil — 1 test/4 assertions) and frontend
   `duplicate-selection-content-copies-nodes-and-segments` +
   `splice-duplicated-appends-copies-and-selects-only-new-nodes` (focused suite **49 tests / 131
   assertions, 0 failures**). Compiler `:ok`, `clj-kondo` 0/0, `cljfmt` clean. **Live by-hand
   check still owed** (Ctrl+D node → line from predecessor; Alt+drag node/segment; the reported
   segment-move artifact after Ctrl+D) — frontend REPL unresponsive and automation can't enter
   edit mode this session.
- [x] **Feature 34 — keyboard-modifier interaction matrix (click / drag × node / segment /
   handler × move / draw).** Specified and **implemented 2026-07-15** (uncommitted). Full
   requirement, all user decisions, both mode matrices, shared operation semantics,
   existing-feature impact, open-point resolutions, and the implementation summary are in
   **§14**. Reworks how ctrl/alt/shift modify the six pointer actions (three targets ×
   click/drag) in both edit modes. Consequences: removes feature 17 (`n` toggle),
   supersedes feature 19's alt-rejoin, replaces feature 33's node-duplication geometry
   (all incident segments, welded to original neighbors; Ctrl+D inherits it). **Per-action
   cursors are still pending** (the user will define them later) and the whole matrix owes
   a live by-hand pass (automation cannot enter path edit mode).
- [x] **Refactor — make the edition stop model explicit + document deferred ordering.**
   Completed 2026-07-13. **Scope note:** `clear-edition-mode` is *already* the single
   canonical stop (bugs 1/5/7 routed the exit watcher, `finish-edition?`, and most keying
   onto `::dwe/clear-edition-mode`). A literal collapse to one event was deliberately **not**
   done — the remaining signals encode genuinely distinct, deliberately-designed behaviors
   (each a bug fix): `:interrupt` must end the loop *directly* during initial creation
   because no `:edition` exists yet to funnel it through `clear-edition-mode`; `finish-path`
   is restart+cleanup; `esc-pressed` has four context-dependent meanings (bug 11 UX).
   Collapsing them would regress. Instead, behavior-preserving: (1) restructured the draw-loop
   predicates into an explicit taxonomy — `end-path-event? = restart-draw-loop? ∪
   exit-draw-loop?` (new `exit-draw-loop?`), removing the duplicated finish-path/double-click
   literals, and `(= event :interrupt)` → `(dwe/interrupt? event)`; (2) documented the model
   in a doc block above the predicates and in `finish-edition?`/`esc-pressed` docstrings
   (esc-pressed's 4-way translation table is now spelled out); (3) documented both
   load-bearing async boundaries in-code (`rx/observe-on :async` in `handle-drawing`,
   `rx/delay 0` in `handle-finish-drawing`). Zero behavior change: all five escape/interrupt/
   restart tests plus the full focused suite (36/94) green; lint/format clean; build `:ok`.

**Groundwork conclusions:** the stable index identity and unified selection prerequisite
for features 1–3, 8, 10, and 11 is complete.

**Remaining work:**

- Features 1–24 are done or dropped (features 4 and 5 dropped by user decision).
- **New batch requested 2026-07-14 — partially done:** **Features 25–31 done** (half-pixel
  node movement above 300% zoom; no pixel-snap for handler drags; node alignment/distribute;
  path-edition options panel incl. read-only measures + live styling preview; arc-length
  half-point insertion; segment flip/delete/merge; temporary 80% fill opacity — see §6;
  several path-edition live behaviours still owe a by-hand pass given automation can't enter
  edit mode this session). **Features 32 and 33 done** (2026-07-14: snap-to-node on by default
  for both creation and edition; Ctrl+D / Alt+drag duplicate of nodes/segments — see §6). All
  planned features (1–33, minus dropped 4/5) are now done. See §5/§6 for each. **Bug 12 reopened is fixed** (2026-07-14,
  editor now close-loops the rendered drawing copy — see §5). **Bug 14 is fixed** (2026-07-14:
  keep SVG-default black in normal view per user decision; edition copy now inherits the
  group's resolved fill so it no longer goes transparent — see §5).
- **Feature 34 — keyboard-modifier interaction matrix — specified AND implemented
  2026-07-15 (uncommitted).** The complete spec and the implementation summary live in
  **§14**. It removed feature 17, superseded feature 19's alt-rejoin, and changed
  feature 33's node-duplication geometry (Ctrl+D included). **The per-cell cursor
  mapping is now also implemented (2026-07-15, user-provided spec — see §15).** Still
  open: the **live by-hand pass** over the whole matrix + cursors.
- **New batch requested 2026-07-15 (features 35–41, see §16): ALL DONE.** **35 DONE
  2026-07-16** (the node was always on-curve; the *draw-mode preview* drifted — it now
  snaps to the on-segment insertion point when a segment is hovered, see §16/§17). **36
  DONE 2026-07-16** (`Ctrl+A` select-all-nodes + `Ctrl+Shift+A` deselect-all). **37 DONE
  2026-07-16** (`Shift+Delete` → new `delete-selected-with-segments`, selection-based).
  **38 DONE 2026-07-16** (selected nodes use the segment pink; segment hover is now green
  2px). **39 DONE 2026-07-16** (paste path nodes outside the editor → new path shape;
  cross-editor splice already worked). **40 DONE** (top-bar refactor: fixed 480px width,
  separators, add/remove-node buttons removed, unavailable node-op icons hidden not
  disabled). **41 DONE** (handler-behavior split button + popover — mirror/aligned/
  independent — with ephemeral per-node type). Full analysis/pointers in §16.
- **Mouse-action & cursor review — DONE 2026-07-16 (§17).** A by-item pass over the
  move/draw pointer matrix and cursors; behaviour + cursor changes landed and are the
  current source of truth over the §14 matrices where they differ.
- **Live by-hand pass — DONE 2026-07-16 (user).** The user walked the full editor by hand
  (mouse/cursor matrix, cursors, features 25–41) and confirmed it works. The "owed by-hand"
  notes scattered per-feature/per-cursor (here and in §14/§15/§16/§17) are now closed —
  treat them as verified; residual issues get filed as separate bugs. **No path-editor work
  items remain open.** The two parked decisions (§13 loop-close migration; Feature 41
  cross-session persistence) were both DROPPED 2026-07-16 by user decision — not doing
  either. Two optional non-functional **pending refactors** remain (dup-coord collapse;
  large-path per-frame perf) — see §19; not started.
- **Feature 42 — editable node coordinates in the right sidebar — DONE 2026-07-16 (§18).**
  The selected node's x/y show in the right panel as fully-featured editable inputs (like a
  shape's x/y); editing moves the node; multi-selection shows shared values or "mixed" and an
  edit applies to all selected nodes (set-absolute-for-all). Live by-hand pass owed.
- **Cursor sizing fix (2026-07-15):** the `draw-add`/`draw-node`/`draw-remove` badge
  cursors rendered the pen smaller than `draw` (their SVGs pack pen+badge into the
  16-unit viewBox at `scale(0.762)` vs `draw`'s `scale(0.941)`). Fixed by rendering the
  three badge cursors larger (25×25px) instead of shrinking the artwork:
  `ui/cursors.clj` `encode-svg-cursor`/`cursor-ref` gained a `width` param (default 20),
  and `ui/cursors.cljs` sets `draw-add/node/remove` to `(cursor-ref … 25 25)`. Pen now
  matches `draw` physically; badge keeps its clean corner position; SVGs unchanged.
  Cursors inline at macro-expansion (`io/resource slurp`), so SVG edits need a forced
  recompile of `ui/cursors.clj` + a hard refresh (css `init-styles` runs once at boot).
- The stop-signal / deferred-ordering refactor is done (see §6 entry above).
- **Testing — store-driven safety net expanded 2026-07-13** (see §7): added path-local
  undo/redo and tool-op-through-lifecycle (`make-curve`/`remove-node`) coverage on top of
  the existing selection/marquee/modifier/arrow/paste/merge/lifecycle tests (now 40/106).
  Remaining, and blocked at the unit level (documented in §7): true pointer-stream drag
  simulation and the auto-capture undo watcher both depend on the *global* `ms/mouse-position`
  / `store/state`, so they need the **live-gesture layer** — a small Playwright spec for
  draw-path + node-drag (recipes: Serena memory `frontend/playwright-gestures`; crashes:
  `frontend/handling-crashes`), plus a by-hand devenv pass over the full workflow (pen draw,
  close path, drags, rubber band, arrows, undo, and the Esc/double-click/toolbar exits).

## 7. Testing status

Continue growing the safety net alongside refactors. Current coverage:

- **Common layer**: `common/test/common_tests/types/path_data_test.cljc` (~1600 lines,
  good coverage of PathData/segment/subpath primitives) and `svg_path_test.cljc`.
  New geometry (extract/splice, fillet, bezier fit, remove-with-approximation) should
  land here as pure-function tests — cheapest and most valuable.
- **Frontend event layer**: `frontend/test/frontend_tests/logic/path_edition_test.cljs`
  (40 tests / 106 assertions) covers drawing-copy isolation, unchanged simple-shape exit,
  changed-content finalization, created-path cleanup, the full grouped selection/marquee
  model, node/segment/handler modifier application, arrow moves, copy/paste, the Alt
  handler-join toggle, the draw-mode preview suppression, drag-to-merge (node + segment),
  and — added 2026-07-13 — **path-local undo/redo** (`undo-path`/`redo-path` restore/reapply
  content and drop the transient preview; the undo entry never captures `:preview`) and
  **tool operations end-to-end through the real store** (`make-curve`/`remove-node` selected
  → `:interrupt` → persisted `:path`). It is registered in `frontend_tests/runner.cljs`;
  the lifecycle/tool tests use a real potok store via `frontend-tests.helpers.state`'s
  `run-store` (the `:interrupt` exercises the whole refactored stop chain end-to-end). Bugs
  1, 3, 4, and 11 have direct event-level coverage.
  **Known limitation — pointer-stream simulation is blocked:** the drag streams read the
  *global* `ms/mouse-position` / `st/stream` behavior subjects and `undo/path-content-ref`
  is `(l/derived … store/state)` on the **global** store — none are driven by the local
  test store. So real `mse/->PointerEvent` drag sequences and the auto-capture undo watcher
  can't be simulated at the unit level; those paths are covered at the modifier/event level
  instead (and would need the live-gesture layer below for true pointer coverage).
- **E2E**: only `frontend/playwright/ui/specs/stroke-to-path.spec.js` touches paths.
  Real pointer-gesture recipes are in Serena memory `frontend/playwright-gestures`;
  a small Playwright spec for draw-path + node-drag would catch DOM-ordering issues
  (bug §5 6) that store-level tests can't.
- Frontend unit tests run from `frontend/`: `pnpm run test` (see
  `mem:frontend/testing` for details); common tests from `common/`.

Validation performed for the drawing-copy refactor on 2026-07-10 through Penpot MCP:

- Main CLJS compiler output: `:ok`; all touched CLJS delimiters balanced.
- Isolated live potok store, unchanged rectangle: zero `update-shapes`, zero commits,
  exact original shape retained as `:rect`, drawing/edit state cleared.
- Isolated live potok store, changed rectangle: stored object unchanged before exit;
  exactly one `update-shapes` and one commit on exit; final type `:path`; state cleared.
- Created-path lifecycle: `start-created-path-edition` + `:interrupt` clears
  `:edition`, `:edit-path`, and drawing object without changing unchanged content.
- Bug 1 predicate: the real `workspace.edition/clear-edition-mode` matches
  `finish-edition?`, `:interrupt` does not, main compiler remains `:ok`, and the live
  runtime has no exception.
- Bug 3 ordering: path clear emits exactly one `finish-path`; at that event edit and
  drawing state are retained while transient preview/last-point are cleaned; final
  state removes edition/edit/drawing data. Non-path clear emits no path event.
- Bug 11 stop classification: Escape produces `::end-edition {:restart? false}` and
  cannot recurse on the edited path. Explicit subpath completion uses `:restart? true`,
  emits `finish-path` before recursion, clears endpoint/preview state, and makes the
  next same-shape preview a `:move-to`.
- The pnpm unit runner cannot run on the host (`ERR_PNPM_ABORTED_REMOVE_MODULES_DIR_NO_TTY`
  on the stale install); run it inside the devenv container instead, where it works:
  `cd /home/penpot/penpot/frontend && pnpm run test:quiet -- --focus
  frontend-tests.logic.path-edition-test --log-level warn`.

Validation for the bug 11 second pass and the position-stream guard on 2026-07-10
(live gestures through Playwright, state inspected via the in-page `debug`/cljs
runtime; note real input needs `document.hasFocus()`, use `page.bringToFront()` after
reloads):

- Branch hazard found and fixed: entering path draw mode while `ms/mouse-position`
  still holds nil (pointer never over the viewport, e.g. toolbar click or unfocused
  window) crashed `path-preview*` — `position-stream` did `(assoc nil :shift? …)`,
  yielding a coordinate-less map that became a `{:x nil :y nil}` preview segment.
  `position-stream` now filters non-point values before snapping.
- Esc while creating a new path with a pending segment: shape created, `:edition`
  set, the path edition top bar renders (asserted in the DOM), editor continues in
  `:draw` mode with pending state cleared; the next clicks appended `M…L…` as a new
  subpath of the same shape. A further Esc with a pending segment only cancels it
  (bar stays); Esc with nothing pending exits and commits once, clearing `:edition`,
  `:edit-path`, drawing object, tool, and lock. No crash.
- Testing caveat: the WASM viewport sometimes loses its WebGL context on hot
  reloading — the workspace then flips to read-only and replaces the top toolbar
  with "Rendering unavailable", which mimics missing editor UI (path bar included).
  Check `refs/render-context-lost?` before trusting UI assertions; a page reload
  recovers.
- Double-click finish: shape committed and continued edition entered in `:draw` mode;
  a later Esc exits it fully without recursion or extra shapes.
- Double-click an existing path: `:move` edition on the drawing copy; Esc exits
  cleanly with no content change.
- Focused unit namespace run in devenv: 13 tests, 34 assertions, 0 failures,
  including new tests driving `handle-drawing` through async `::end-edition`
  with `:restart?` false/true (no re-entry + `clear-edition-mode` vs
  `finish-path` + `start-created-path-edition`) and `esc-pressed` with/without a
  pending segment (`cancel-pending-segment` vs `:interrupt`).
- `pnpm run lint:clj` clean; `cljfmt` clean after fixing pre-existing
  `runner.cljs` formatting.

Validation for segment/handler selection and grouped movement on 2026-07-10:

- Focused namespace: 20 tests, 51 assertions, 0 failures. New coverage verifies
  cross-type plain/Shift selection, marquee hit-testing for nodes/segments/handlers,
  and translating two selected opposite handlers without derived opposite deltas
  cancelling either move. Empty `buffer-time` marquee emissions are filtered and the
  update event safely ignores a non-rect value (regression for click-outside assertion).
  Segment movement coverage verifies endpoint identity deduplication and translation of
  both endpoints plus their anchored/shared handlers.
- Main CLJS compiler output: `:ok`; all touched CLJS delimiters balanced.
- `pnpm run lint:clj`: 0 errors, 0 warnings; `pnpm run check-fmt:clj`: clean.
- Real pointer gestures were not re-run in this pass; by-hand verification remains part
  of the outstanding testing work (see §6 "Remaining work").

Validation for feature 7 (remove node with curve approximation) on 2026-07-11:

- New pure-function tests in `common/test/common_tests/types/path_data_test.cljc`:
  `helpers-fit-cubic-recovers-curve` plus eight `segment-remove-nodes-*` cases
  (collinear→line, corner→fitted curve with preserved 45° tangents, two circle arcs →
  single curve within 0.5 px of the apex, multiple consecutive removals, endpoint
  drops, closed-path closure kept, chain ending on close-path, removed close target).
- Full common suite (JVM kaocha): 1083 tests / 24497 assertions, 0 failures. CLJS
  (shadow `:test` build, node): 942 tests / 23856 assertions, 0 failures.
- `pnpm run lint:clj` 0/0 and `check-fmt:clj` clean in `common/`; main CLJS build `:ok`;
  frontend focused suite `frontend-tests.logic.path-edition-test` 28 tests /
  68 assertions, 0 failures (devenv container).
- Live gestures (Playwright, fresh devenv workspace page): pen-drew `M162,640 L282,500
  L402,640`, exit, re-enter, node click → `{:nodes #{1}}`, Delete →
  `C283.08,498.74 280.92,498.74` fitted curve (handlers exactly along the removed
  segments' directions, curve bulging to the old corner); path-local Ctrl+Z/Ctrl+Shift+Z
  restored/reapplied it; exit persisted exactly that content with edition/edit-path/
  drawing all cleared and no exception. Collinear 3-node line → Delete healed to
  `M162,800 L402,800` (plain line). Ellipse → Enter (drawing-copy conversion), removed
  the right node → both right arcs merged into one closed fitted curve
  (`C339.92,880 339.92,980`, horizontal tangents kept at top/bottom); committed on exit.
  Note: the Delete key drives the removal (path shortcuts), no toolbar click needed;
  browser↔penpot coords on that session were a plain −318 px x-offset at 100% zoom.
- Root-owned stale caches (`.shadow-cljs/.../clipboard.cljs.cache.transit.json`,
  `target/tests/cljs-runtime/app.main.data.workspace.path.clipboard.js{,.map}`) from a
  root-run build had to be deleted before the frontend test build could run.

Validation for feature 6 (curve-tool smoothing) on 2026-07-11:

- New tests in `path_data_test.cljc`: `helpers-fit-curve-single-curve` (samples of one
  cubic recovered as one curve), `helpers-fit-curve-splits-and-chains` (sharp corner
  splits into chained curves with shared endpoints), `helpers-fit-curve-respects-tolerance`
  (max deviation of every input point ≤ tolerance against densely sampled fit), and
  `segment-smooth-points->content` (2-point fallback to line; wave → move-to + all
  curve-to, fewer commands than points, endpoints preserved).
- Full common suite: JVM 1087 / 24516, CLJS 946 / 23875, all 0 failures; common and
  frontend `lint:clj` 0/0; `check-fmt:clj` clean; main build `:ok`.
- Live (Playwright): freehand S-wave with the curve tool (Shift+C) committed
  `:move-to` + 4 `:curve-to` and rendered smooth; V stroke → straight arms, rounded
  valley. **Environment trap found:** the Playwright-managed browser tab reports
  `document.visibilityState === "hidden"`, so Chrome intensive timer throttling reduces
  `rx/sample 10` (RxJS `sampleTime`) to ~2-3 emissions/s — input events still flow
  (pointermove and `ms/mouse-position` update normally), but the curve tool's sampled
  point stream starves and fast drags record only 2-3 points, committing as a short
  line. `page.bringToFront()` does not fix visibility in this setup. Workaround for
  gesture tests of the curve tool: drag slowly (~300 ms per step, ~5-10 s per stroke)
  so even throttled sampling collects enough points. Root-owned `.clj-kondo` cache for
  the clipboard namespace also had to be deleted (same root-run-build cause as above).

## 8. Context for the new session

- Activate Serena project `penpot`; read `critical-info` and `frontend/core` memories.
- Read this handoff before source exploration; it is the cross-session source of truth.
  Serena memory `frontend/path-editor-architecture-and-event-ordering` contains the
  original investigation and may lag this document.
- Work lives on `piweek-path-editor-enhancements`; some of it is uncommitted. Inspect
  `git status` and the full diff before continuing (don't assume the tree matches this
  document).
- Key invariant: while `[:workspace-local :edit-path id]` exists, path content belongs
  to `[:workspace-drawing :object]`; file objects must remain unchanged until
  `finalize-path-content` runs. A new path must enter continued edition through
  `start-created-path-edition`, not only `start-edition-mode`.
- If the editor looks closed but the pen cursor remains, inspect `:edition`,
  `:edit-path`, `:workspace-drawing :object`, and `:workspace-drawing :tool`. The stale
  state reproduced for bug 10 had nil `:edition` but non-nil edit-path and drawing
  object.
- Recent related commits: `892869b039` (stroke caps on mid-path node insert),
  `388775413e` (preview next-node arg), `6f0685ba8e` (several path edition issues),
  `d470d96833` (dragging handlers), `55752d361f` (right click).
- Lint/format from `frontend/`: `pnpm run lint:clj`, `pnpm run check-fmt:clj`; run
  `tools/paren-repair.bb` first if delimiter errors are suspected. Commit only when
  explicitly asked.

## 9. Stable node identity refactor (completed 2026-07-10, commit `6f03049135`)

Groundwork for roadmap features 1–3 and 8. Replaces position-based node identity with stable
content-command indices and scaffolds a unified node/segment/handler selection model.
Scope decisions (confirmed with the user): **nodes end-to-end + scaffold** segments/
handlers (their drag-select interaction is deferred to feature 3), and the common-layer
tool fns stay position-based with **conversion at the boundary**.

### Identity model
- **Node = content command index.** Position is derived on demand
  (`helpers/node-position` = `segment->point (nth content i)`). This is unambiguous even
  with duplicate coordinates or `close-path` (which `get-points` skips), unlike the old
  position keys.
- **Handler = `[command-index prefix]`** (`:c1`/`:c2`), unchanged.
- **Segment = the drawing command's index** (scaffolded, not yet interactive).
- Indices are invariant under moves and shift only on structural edits.

### State (`[:workspace-local :edit-path id]`)
Selection and hover are each a single grouped map
`{:nodes #{index} :segments #{index} :handlers #{[index prefix]}}`, stored under
`:selection` and `:hover` (empty value = `helpers/empty-selection`). `start-path-edit`
initialises both; `deselect-all` resets `:selection` to the empty map; `:segments` is
scaffolded (not yet interactive). This replaced the flat `:selected-points`/
`:hover-points` (positions) → `:selected-nodes`… (three parallel keys) → the current
unified maps, so per-type reads are `(get-in … [:selection :nodes])` and type-agnostic
ops touch one value.

### New pure helpers (`data/workspace/path/helpers.cljs`)
`node?`, `node-indices`, `node-position`, `node-positions`, `nodes-in-rect`,
`remap-selected-nodes` (re-resolves indices against new content by matching old selected
positions — the one position→index remap done at commit time).

### Remap policy
Moves keep indices, so the per-move `selection/update-selection` remap was **removed**
(`apply-content-modifiers*` no longer computes a point-change). Only `process-path-tool`
remaps, once, after the structural change. `selection/update-selection` is deleted.

### Files touched
- `helpers.cljs` — new identity helpers (adds `app.common.data`, `geom.shapes` requires).
- `selection.cljs` — hover/select/area-select/deselect are index-based
  (`nodes-in-rect`); `update-selection` removed; drops `gpt`/`gsh` requires.
- `edition.cljs` — drag/arrow/`start-move-path-point` take/derive indices;
  `apply-content-modifiers[*]` no longer remaps selection.
- `tools.cljs` — `process-path-tool` converts indices→positions for the common fns and
  remaps `:selected-nodes` afterwards.
- `editor.cljs` — renders each node from its index; `point->base`/`base->point` and the
  `map-invert` require are gone; `path-point*` gains `:index` and emits index-based
  hover/select.
- `path_actions.cljs` — `check-enabled` stays position-based, fed derived positions.

### Boundary conversion (still position-based, indices→positions at the edge)
`move-selected-path-point`, `drag-selected-points`, `move-selected` (arrows),
`streams/move-points-stream`, `snap/create-ranges`, and `path-actions/check-enabled`.
Consequence: the duplicate-coordinate collapse persists **only** inside those
position-based common fns (no worse than before); a later pass can push indices into the
common tool fns to remove it.

### Verification
Live (real gestures + store inspection): click/shift-click select (correct indices),
rubber-band area-select, node drag (index stable across the move, no remap), `make-curve`
(selection kept), `remove-node` (selection remapped, removed node dropped), arrow move.
Focused unit suite 14/14; `cljfmt` clean; `clj-kondo` 0/0; build `:ok`.

### Subsequent implementation for features 1, 3, and 8 (2026-07-10)
Segment + handler click/Shift+click/marquee selection, grouped handler movement, and
segment hit-testing/rendering are now implemented. The optional follow-up is to push
indices into the remaining position-based common tool functions.

## 10. Segment and handler selection implementation (2026-07-10, uncommitted)

- `helpers.cljs`: `segment-entries`, endpoint-node identities, polyline rect intersection
  for line/curve/closing segments, and visible-handler entries/containment.
- `selection.cljs`: generic cross-type plain/Shift selection; segment hover; one
  `select-path-area` event that updates all three sets from the same marquee rect.
- `edition.cljs`: click-vs-drag discrimination for segments and handlers. Segment drags
  reuse the node-modifier pipeline for all unique endpoints; handler groups move from a
  single snapped primary delta. Selected opposite handlers do not receive each other's
  derived angle-preservation modifier.
- `editor.cljs`: per-segment hit targets and selected/hover rendering; selected handler
  rendering; selected segments are pink; Shift is passed into segment/handler dragging.
  The insertion preview is not rendered; click-to-insert-node is temporarily disabled.
- `path_edition_test.cljs`: state-level selection, marquee, grouped-handler movement,
  and segment movement coverage. Focused suite result: 20 tests / 51 assertions.

## 11. Branch self-review fixes (2026-07-10, committed)

A full self-review of the branch against `origin/develop` found five issues, all fixed:

1. **Stale segment/handler selection after structural tool ops.** `process-path-tool`
   only remapped `:selection :nodes`. New `helpers/remap-selection` adjusts the whole
   grouped map: with an unchanged command count node/segment indices stay and handlers
   whose command is no longer `:curve-to` are pruned; with a changed count nodes are
   re-resolved by position and segment/handler selections are cleared.
2. **Group handler drag corrupted paths through stale handler ids.**
   `path/apply-content-modifiers` converts a `line-to` into a `curve-to` when it
   receives `c1x/c1y` deltas, so a stale selected handler id (make-corner on another
   selected node, undo of make-curve) silently re-curved a straight segment.
   `modify-selected-handlers` now filters selected handler ids to commands that are
   still `:curve-to` (also guards out-of-range ids).
3. **Dangling trailing `move-to` accumulated orphan nodes.** `add-node` appends a
   `move-to` when starting a subpath, but `cancel-pending-segment` only cleared
   `:last-point/:preview`; the next click then appended another `move-to`, leaving
   phantom orphan nodes that got persisted. `cancel-pending-segment` and `finish-path`
   now drop a trailing `move-to` (via `update-object-content`, which also refreshes
   geometry and no longer touches a nonexistent drawing object).
4. **`finalize-path-content` compared un-normalized content.** A lone trailing
   `move-to` made unchanged content look changed, converting an untouched simple shape
   to `:path` on exit. The no-op condition now also compares the normalized
   `new-content`.
5. **Path-local undo/redo left index selections stale.** `undo/load-entry` now applies
   `helpers/remap-selection` against the restored content.

Regression tests added for all five (`cancel-pending-segment-drops-dangling-subpath-start`,
`finish-path-drops-dangling-subpath-start`, `finalize-ignores-dangling-subpath-start`,
`remap-selection-follows-content-structure`,
`group-handler-drag-ignores-stale-handler-identities`). Focused suite: 25 tests /
62 assertions, 0 failures (devenv container). `lint:clj` 0/0, `check-fmt:clj` clean,
main build `:ok`.

## 12. Branch review — correctness & performance pass (2026-07-11)

Full re-review of the branch (committed work incl. copy/paste `8f17e6ad82` and marquee
priority `412d98c609`, plus the uncommitted features 6/7 common-layer work) and of every
path-editor surface file. No correctness bug requiring a fix was found. Notes worth
keeping:

Correctness observations (accepted behaviour, not defects):

- `fit-curve` with only 2 points left after the 0.01 dedup emits a `curve-to` for a
  straight chord (handlers at thirds). Harmless, slightly heavier content than a
  `line-to`.
- The documented position-based collapses persist at the common-fn boundary:
  `remove-nodes` selects victims by position, the editor's handler grouping
  (`path/get-handlers`) and the `is-last` check are position-keyed. Duplicate-coordinate
  nodes remain ambiguous only there.
- `path_actions/check-enabled` and Delete only consider `:selection :nodes`: with a
  segment- or handler-only selection the top bar is fully disabled and Delete is a
  no-op. UX gap to revisit (e.g. Delete on a segment selection could remove the
  segment's endpoints or open feature-10-style behaviour).
- Pre-existing on develop (not branch work): the `:move-unit-left`/`:move-unit-right`
  path-shortcut map keys and tooltips are swapped relative to their direction
  (behaviour is correct; only the shortcut-panel labelling is off).

Performance observations (no action needed yet; relevant for very large paths):

- During node/segment/handler drags every pointermove re-runs in `path-editor*`:
  `apply-content-modifiers` over the full content, `get-points`, node/segment/handler
  entry recomputation, a full-path `.toString`, and two PathData+`toString` builds per
  segment hit target. O(n) work + allocation churn per frame. If it ever matters:
  memoize per-segment content on stable values or skip segment hit targets while a drag
  is active.
- With snap toggled on, `path-snap*` recomputes `snap/create-ranges` every frame during
  a drag (its memo key includes the moving points); the drag pipeline itself already
  builds ranges once per gesture in `move-points-stream`. Deriving the editor ranges
  from the drag-start content would make it once per gesture too.
- Marquee hit-testing is already frame-throttled (16 ms); `segments-in-rect` flattens
  curves per tick, which is fine at that rate.

## 13. Maybe — ideas worth considering (not committed)

A parking lot for things that might be worth doing but haven't been decided. Each entry
records the trade-off, not a plan; promote to a numbered bug/feature only once decided.

- **DROPPED 2026-07-16 (user decision): the one-time loop-close migration below will NOT
  be done.** Bug 12's forward-only fix is considered sufficient; already-saved shapes with
  the old open seam are left as-is. Kept here only for the trade-off record.
- **One-time migration to close existing coincident-endpoint loops.** Bug 12 only fixes
  loops going forward (at draw/edit persistence); shapes already saved with the broken
  `M A … L A` seam (no `:close-path`) stay broken until they are next edited. A file-data
  migration could run `path/close-loops` over every path shape so already-created shapes
  render a proper stroke join on load.
  - **Pro:** those shapes are objectively incorrect today — the stroke seam has two butt
    caps instead of a join; the migration makes them render correctly with no user action.
  - **Con:** it rewrites already-created shapes. Changing persisted user content in a
    migration is a heavier commitment than a forward-only fix — it must be exactly
    shape-preserving (only line-to→close-path / append close-path on genuinely coincident
    endpoints, within the `0.1` `pt=` tolerance) and needs the usual migration care
    (version bump, validation across consumers, undo/history implications, library-shared
    files).
  - **Cost:** `close-loops` is O(n) in commands and cheap per element (one `gpt/distance`
    per subpath; no curve flattening). Measured on the dev build: ~8 ms / 1k commands,
    ~70 ms / 10k, ~360 ms / 50k — dominated by `PathData` unpack/repack, not the check.
    Real paths are tens–hundreds of commands (sub-ms). Whole-file cost is linear in total
    command count, negligible next to file load.
  - **If pursued:** make the migration short-circuit unchanged content — as written
    `close-loops` always repacks the `PathData` even for open paths (a semantic no-op), so
    a naive pass would emit a change record for *every* path in the file. Add a cheap
    "did any subpath actually close?" guard before the repack/write-back so the change set
    (and sync/history churn) is limited to the shapes that genuinely need fixing.

## 14. Feature 34 — keyboard-modifier interaction matrix (specified + implemented 2026-07-15)

The matrices below are the user's requirement; every ambiguity was resolved with the user
in a Q&A on 2026-07-15 and the decisions are inlined (marked "user-confirmed" where they
came from that Q&A rather than the original request). **Implemented the same day** — see
the implementation summary at the end of this section. **Cursors:** several cells will
need a dedicated pointer cursor; the user will define the cursor set later — the hovered
target kind + live modifiers can drive a per-cell cursor (the insert-anywhere preview
already derives from `ms/keyboard-mod` live), but the cursor mapping itself is still
open.

### General rules (user-confirmed)

- Three interactive object kinds — **segments, nodes, handlers** — each with **click**
  and **click+drag** = six pointer actions, with different tables for **move** and
  **draw** modes. Some cells are today's behavior, restated for completeness.
- **`ctrl` = the platform modifier** (Ctrl on win/linux, Cmd on macOS — the existing
  `ms/mouse-position-mod` / feature-10 convention). **`alt`** = Alt/Option.
- **Modifier timing:** `ctrl`/`alt` are captured from the **pointer-down DOM event**
  (the feature-33 capture pattern — do NOT deref the modifier behavior-subjects at
  drag-confirm, they can be one pointer event stale) and decide WHICH action runs; the
  action cannot change mid-drag. **`shift` is evaluated live** during the drag
  (press/release mid-drag works) and always means the same constraint: direction
  quantized to **15° increments** from the reference point (via
  `position-fixed-angle`). The request's "horizontal/vertical snapping" is just the
  0°/90° buckets of that 15° fan — nothing extra (user-confirmed).
- **Selection scope (user-confirmed):** no-modifier and shift **drags move the whole
  selection** (as today); **alt drags duplicate the whole selection**; `ctrl` drag
  actions (bend, curve-config, handler mirror) and ALL click actions
  (toggles/removals) apply **only to the grabbed/clicked element**.
- **Feature 24 drop-merge stays (user-confirmed):** auto-merge runs after plain/shift
  moves AND after alt-duplicate drags (dropping a copy within threshold of a node welds
  it there); it never runs after bend/curve-config drags.
- **"nothing" cells are literal no-ops (user-confirmed):** pointer-down with that combo
  starts no drag and changes no selection; releasing without having dragged still
  performs that combo's CLICK action.
- **Draw mode + shift (user-confirmed):** shift has no per-cell meaning in draw mode; it
  is uniformly the 15° constraint from the previous point for every draw action (which
  is why the draw table has no shift column, and why there is no shift-click selection
  toggle in draw mode).
- **"PP" = pending-preserving** (draw mode): the operation leaves the line in progress
  untouched — `:last-point`/`:prev-handler`/`:preview` and the draw loop keep running,
  and no new line is started.

### Operation semantics (shared definitions, all user-confirmed)

| Term | Meaning |
|---|---|
| remove node | current Delete / feature 7 semantics: node removed, neighbors joined by a fitted curve (heal). |
| remove node + segments | draw-mode ctrl+alt only: the node AND its adjacent segments are dropped — leaves an open gap, no healing. |
| remove segment | feature-30 Delete-on-segment semantics: drop just that segment, keep both endpoint nodes (opens the path; a now-dangling end node is dropped, as today). |
| remove handler | collapse that control point onto its node — that side of the segment becomes straight; if the segment's other control point is also degenerate, store the command as a plain `line-to`. The matching/opposite handler is untouched. |
| flat/curved node toggle | reuse make-corner / make-curve. "Curved" = the node has ≥1 non-collapsed handle. To-flat = make-corner (collapse the node's handles); to-curve = make-curve (target-only handles 1/3 toward each neighbor — bug-13 semantics, neighbors untouched). |
| curve-config drag (ctrl+drag on a node) | drag out the node's curve as if it were being created for the first time: symmetric, equal-length mirrored handles following the drag vector (replaces the node's existing handles). |
| duplicate node (alt+drag) | EVERY segment incident to the node is copied; each copy's far end stays welded to the **same original neighbor node** it had, and the copies meet at the single new (dragged) node — the duplicate has the original's connectivity. **Replaces feature 33's incoming-segment-only node geometry.** |
| duplicate segment (alt+drag) | as feature 33 today (extract-content copy of the segment, dragged as a new subpath). |
| bend segment | feature 10 semantics: the segment curves so the grabbed point tracks the cursor; its endpoint nodes stay fixed. |
| handler drag modes | **default** = current smart join: a joined pair stays joined (opposite mirrors the angle, keeps its own length), a corner pair moves independently. **ctrl** = force FULL mirror (opposite gets same length AND mirrored angle — also re-joins a corner pair). **alt** = force independent (opposite never touched). **ctrl+alt** = force aligned (opposite mirrors the angle but keeps its own length — forces alignment even on a corner pair). Selected-opposite suppression (feature 8) still applies. |
| weld (draw mode) | the pending segment lands on the clicked/new node (subpaths weld into one); **pending then clears** (user-confirmed) — the next click starts a brand-new subpath. |
| insert node on segment | the feature 16/17/29 pipeline: hover previews the node to create; click splits the segment at that point (arc-length midpoint for the near-middle case). |

### MOVE mode

| Target · action | no modifier | shift | ctrl | alt | ctrl+alt |
|---|---|---|---|---|---|
| **Segment click** | select only that segment; near the arc-length middle → insert node there, hover previews it (features 1/16/29, unchanged) | toggle the segment in/out of the selection (unchanged) | **insert node at ANY point** on the segment; hover preview follows the cursor (replaces feature 17) | select the segment (click fallback of alt+drag; no middle-insert) | **remove segment** |
| **Segment drag** | move it (selection-wide) | move with the live 15° constraint | **bend** to the cursor (feature 10, unchanged) | **duplicate** (selection-wide) and drag the copy; +shift = 15° constraint on the copy | nothing |
| **Node click** | select only that node | toggle the node in/out of the selection | **toggle flat/curved** | select the node (click fallback of alt+drag) | **remove node** (heal) |
| **Node drag** | move it (selection-wide) | move with the live 15° constraint | **curve-config** (symmetric equal handles, as on first creation) | **duplicate** (selection-wide; new all-incident-segments geometry) and drag; +shift = 15° | nothing |
| **Handler click** | select only that handler | toggle the handler in/out of the selection | **remove handler** | **remove handler** | nothing |
| **Handler drag** | move (smart join — current default) | move with the live 15° constraint | **full mirror** (opposite = same length + mirrored angle) | **independent** (opposite untouched) | **aligned** (opposite mirrors the angle, keeps its own length) |

### DRAW mode

Shift = 15° constraint from the previous point, uniformly (see general rules).

| Target · action | no modifier | ctrl | alt | ctrl+alt |
|---|---|---|---|---|
| **Canvas click** | line in progress → line-to that point; else start a line there (unchanged) | — unspecified (see open point 2) | — unspecified | — unspecified |
| **Segment click** | insert a node at that point; line in progress → **weld** the pending segment onto it (pending clears); no line in progress → the new node becomes the start of the next segment | **toggle segment straight↔curved** (user-confirmed): line-to → curve-to with default 1/3 handles at both endpoints; curve-to → line-to (drops both control points). No node inserted. PP | **remove segment**. PP | **remove segment**. PP |
| **Segment drag** | insert a node at the click point, **weld** the pending segment onto it (pending clears), then the drag pulls out the new node's symmetric handles — like drawing a smooth point (user-confirmed) | **bend** the segment to the drag position (feature 10 semantics). PP | — unspecified (see open point 2) | — unspecified |
| **Node click** | line in progress → close/**weld** the pending segment onto that node — ANY node, not just the subpath start (pending clears); no line in progress → that node becomes the starting point of the next segment | **toggle flat/curved**. PP | **remove node** (heal). PP — except: if the removed node IS the pending segment's origin, also clear the pending start (the next click starts fresh, per the original request) | **remove node + adjacent segments** (gap); same pending-origin caveat |
| **Node drag** | path in progress → finish/weld the path onto that node and shape the closing curve while dragging; at the very beginning of a path → create the start handler for the future segment (current prev-handler behavior) | **curve-config** (symmetric equal handles). PP | — unspecified | — unspecified |
| **Handler click** | select the handler | **remove handler**. PP | **remove handler**. PP | nothing (unspecified in the request; aligned with move mode) |
| **Handler drag** | move (smart join) | **full mirror**. PP | **independent**. PP | **aligned**. PP |

### Impact on existing features

- **Features 16/29** (near-middle insert at the arc-length midpoint): kept as the
  no-modifier segment click.
- **Feature 17** (`n` insert-anywhere toggle): **REMOVED** (user-confirmed) — delete the
  `n` shortcut, the toolbar add-nodes toggle button, the `:add-nodes` flag
  (`toggle-add-nodes`/`set-add-nodes`), and `esc-pressed`'s turn-it-off branch;
  ctrl+hover/click on a segment becomes the only insert-anywhere path.
- **Feature 19** (Alt re-joins a corner pair): **superseded** — alt is now always
  "independent"; the re-join behavior lives in ctrl (full mirror) and ctrl+alt
  (angle-only align).
- **Feature 33**: Alt+drag stays the duplication gesture, but the NODE geometry changes
  to the all-incident-segments rule (segment duplication unchanged). Ctrl+D: see open
  point 1.
- **Feature 10** (ctrl bend), **feature 14** (15° shift), **feature 24** (drop-merge —
  now explicitly also after duplicate drags, never after bend/curve-config), **feature
  30** (segment-removal semantics), **feature 7** (heal removal), and the features
  1/3/8 selection model are reused as the corresponding cells.

### Open points — resolutions taken by the implementation (2026-07-15)

1. **Ctrl+D node duplication** — resolved by construction: `duplicate-selected` shares
   `helpers/duplicate-selection-content`, which now uses the new all-incident-segments
   geometry, so Ctrl+D and Alt+drag are automatically consistent.
2. **Unspecified draw-mode cells** — draw-mode alt / mod+alt on nodes and segments act
   **immediately at pointer-down** (the removal happens whether or not the user then
   drags; there is no drag action for those combos). Canvas clicks with modifiers keep
   the no-modifier behavior (the draw loop does not inspect modifiers).
3. **Cursor set** — assets landed 2026-07-15: 12 new cursor SVGs (`draw`,
   `draw-add`, `draw-node`, `draw-remove`, `move`, `move-add`, `move-copy`,
   `move-curve`, `move-handles`, `move-move`, `move-node`, `move-remove`) in
   `frontend/resources/images/cursors/`, normalized to the existing cursor
   structure (bare `<svg xmlns fill="none" viewBox="0 0 16 16">`, no
   width/height/clip-path/defs; content wrapped in a `scale()` group because
   `cursor-ref` re-wraps content in a fixed 16-unit viewBox and would clip the
   originals' 17/21-unit coordinates). Registered as statics in `cursors.cljs`
   (hotspot 0 0, the pointer/pen tip) and `css_cursors.cljs`, so
   `(cur/get-static "move-node")` etc. work. Validated live on the ws0 dev
   build: all 12 `.cursor-*` classes compute to `url("data:image/svg+xml,…")
   0 0, auto` and every data-URI renders visibly/unclipped.
   **Per-cell mapping implemented 2026-07-15 (same session as the user's
   cursor spec) — see §15 for the full mapping and implementation.**
4. **Weld onto the current subpath's own start** — the draw-mode no-modifier node click
   keeps routing through `close-path-drag-start` (which always accepted any node
   position), so the close-on-start-node flow and bug 12's close-loops treatment are
   untouched.
5. **Selection side-effects of toggle/removal clicks** — toggles/removals leave the
   selection unchanged except pruning: `remap-selection` drops handler ids whose command
   is no longer a curve and (new) segment ids whose command became a subpath-break
   move-to; structural changes re-resolve nodes by position as before.
6. **Mirror/aligned with no opposite handle** (found during implementation) — a handler
   whose node's other side is a plain `line-to` has no opposite handle
   (`opposite-index` only yields curve handlers); `:mirror`/`:aligned` then move only
   the dragged handler and never create a handle on the line side — the same limitation
   feature 19's rejoin had. Promote to a follow-up only if the user asks for
   mirror-creating-a-handle.

### Implementation surface (pointers, not a plan)

- `ui/workspace/shapes/path/editor.cljs` pointer-down handlers must thread `ctrl`/`alt`
  (and keep `shift`) from the DOM event into the drp events — the feature-33 capture
  pattern; hover handlers need the same modifier awareness for previews and future
  cursors (ctrl-hover on a segment must show the insert preview anywhere).
- `data/workspace/path/edition.cljs` `start-node-drag` / `start-segment-drag` are the
  existing at-drag-confirm dispatchers to extend; a matching handler-drag dispatcher
  (around `start-move-handler` / `modify-selected-handlers`) is needed for the four
  handler-drag modes; `move-handler-modifiers` grows explicit
  mirror/independent/aligned modes (it already has the `rejoin?` arity from feature 19).
- Click-vs-drag discrimination stays `streams/drag-stream` (5px/zoom); the click branch
  of each dispatcher gets the per-combo click actions.
- Tool-op reuse: `tools/process-path-tool` (make-corner / make-curve / remove-node /
  separate), `helpers/insertion-point` + `create-node-at-position` (insert),
  feature-30 `delete-selected` segment branch (remove segment), feature-33
  `splice-duplicated` (duplication; node sub-content builder needs the new geometry),
  the feature-10 bend pipeline (`bend-selected-segment`).
- Draw-mode weld / segment-insert interacts with the draw loop (`drawing.cljs`
  `start-edition`, `close-path-drag-start`): the pending-clear rule maps onto the
  existing cancel-pending/close flows; ctrl/alt draw-mode ops must NOT emit `add-node`
  or otherwise feed the pending-segment state (PP cells).
- Removing feature 17 touches `tools.cljs`, `shortcuts.cljs`, `path_actions.cljs`, the
  `esc-pressed` first branch, and the `workspace.path.actions.add-nodes` i18n key.

### Implementation summary (2026-07-15, uncommitted)

Everything in the matrices above is implemented except the cursor set (open point 3).

**Common layer** (`types/path/segment.cljc` + `path.cljc` wrappers):
- `collapse-handler [content index prefix]` — handler onto its node;
  `remove-line-curves` then stores a fully degenerate curve as `line-to`.
- `toggle-segment-curve [content index]` — line-to ↔ curve-to (1/3 default handles);
  move-to/close-path untouched.
- `remove-segments [content indices]` — removed line/curve becomes a subpath-break
  move-to (a removed close-path is just dropped); a close-path of a subpath broken
  elsewhere is first materialized as a line to its start (geometry preserved); lone
  move-to subpaths dropped. `path/is-curve-point?` wraps `segment/is-curve?`.
- `duplicate-node-content` **rewritten** to the all-incident rule; new contract
  `{:content <commands> :selected #{relative new-node indices}}` (an outgoing segment is
  copied reversed via the existing `reverse-segment`, arriving handle shifted along).

**Frontend data layer:**
- `helpers.cljs`: `align-handler-modifiers` (angle-mirror keeping the opposite's own
  length, forced on corners); `duplicate-selection-content` consumes the new
  duplicate contract; `remap-selection` also prunes segment ids that became move-tos.
- `tools.cljs`: `remove-node` gained a point arity; new events `toggle-node-curve`
  (index → make-corner/make-curve via `is-curve-point?`), `remove-segments`/
  `remove-segment`, `remove-node-with-segments` (incident entries → `remove-segments`),
  `toggle-segment-curve`, `remove-handler`; `set-add-nodes`/`toggle-add-nodes` deleted.
- `edition.cljs`: `modify-selected-handlers` takes a **mode** (`:smart`/`:mirror`/
  `:independent`/`:aligned`; non-smart modes act on the grabbed handler only, mirror =
  `move-handler-modifiers` with `rejoin?`+match flags, aligned = the new helper);
  `drag-selected-handlers [primary mode]` (shift stays live, alt no longer read
  mid-drag); `start-move-handler [index prefix shift? alt? mod?]`,
  `start-move-path-point [index shift? alt? mod?]` and
  `start-move-path-segment [index shift? alt? mod?]` are the move-mode dispatchers
  implementing the matrix (mod+alt cells = `drag-stream(rx/empty, click-action)`);
  `start-segment-drag`/`start-node-drag` dissolved into them (`ms/mouse-position-mod`
  deref gone — everything captured from the DOM event); new
  `curve-config-node-drag [index]` + `curve-config-modifier` (symmetric handles: `:c1`
  of the outgoing command at cursor, `:c2` of the incoming command mirrored; delta base
  of a missing handle = the node, matching `apply-content-modifiers`' line→curve
  promotion); `start-path-edit` no longer seeds `:add-nodes`.
- `drawing.cljs`: new draw-mode dispatchers `on-draw-node-pointer-down [index position
  alt? mod?]` (mod+alt/alt remove immediately with the pending-origin
  `cancel-pending-segment` caveat; mod = toggle/curve-config via drag-stream; otherwise
  the pre-existing reset-last-handler / start-path-from-point / close-path-drag-start
  flow) and `on-draw-segment-pointer-down [index alt? mod?]` (alt/mod+alt remove; mod =
  toggle-segment-curve on click / bend on drag, close-path guarded; no-modifier =
  `insertion-point` anywhere (16px/zoom) → `create-node-at-position` then
  `close-path-drag-start` (pending, weld+clear) or `start-path-from-point` (no
  pending)). Requires `tools` + `ms` (acyclic).
- `shortcuts.cljs`: `n` binding and the esc add-nodes branch removed.
- `path.cljs`: exports updated (new tools events + draw dispatchers; add-nodes gone).

**UI:**
- `editor.cljs`: `path-point*`/`path-segment*`/`path-handler*` pointer-downs thread
  shift/alt/mod from the DOM event into the dispatchers; the inline mod-click
  make-curve/corner branch and the `is-last`/`is-start-path`/`is-curve` props are gone
  (that logic lives in the events now); segments are **interactive in draw mode too**
  (`:edit-mode` prop routes to the draw dispatcher); the insert preview keys on
  `(rx/combine-latest ms/mouse-position ms/keyboard-mod)` so pressing/releasing the
  platform modifier flips middle-only ↔ anywhere without moving the pointer.
- `path_actions.cljs`: add-nodes toolbar button gone; `en.po` key removed.

**Verification:** clj-kondo 0/0 (full frontend+common lint), `cljfmt` clean both
modules, main CLJS build `:ok`; common suite **1114 tests / 24610 assertions** (focused
path-data 150/484 — new `segment-collapse-handler`, `segment-toggle-segment-curve`,
`segment-remove-segments`, rewritten `path-duplicate-node-content`); frontend focused
suite **54 tests / 146 assertions, 0 failures** (renamed handler-mode tests
`handler-drag-{smart,independent,mirror}-…`, new `handler-drag-aligned-…`,
`remove-handler-collapses-…`, `toggle-segment-curve-switches-…`,
`remove-segment-opens-…`, `remove-node-with-segments-opens-a-gap`, interior-node case in
`duplicate-selection-content-…`). **Owed:** the live by-hand pass over the whole matrix
(hover preview with ctrl, curve-config drag feel, draw-mode weld + pending-clear, the
four handler modes, mod+alt no-ops, removals in both modes) and the cursor set.

## 15. Feature 34 cursors — per-cell mapping (implemented 2026-07-15, uncommitted)

The user provided the cursor definitions this session; implemented the same day.
Closes §14 open point 3. Cursor names below are the 12 static assets from §14
(`cur/get-static`). Three layers:

1. **Viewport default** (shared `hooks/setup-cursor`, both viewports): path **draw**
   mode / pen tool → `draw` (replaces the old `pen` cursor for paths; the `:curve`
   tool keeps `pencil`); path **move** mode (`path-editing?`, after the drawing-tool
   branches) → `move`. New `utils/get-cursor` keys `:draw-path`/`:edit-path`. The
   redundant `(not path-editing?)` in the alt-duplicate branch was dropped (the new
   branch precedes it).
2. **Hover cursors** (`editor.cljs`): pure fns `node-cursor` / `segment-cursor` /
   `handler-cursor` map `(edit-mode, live modifiers, selected?, …)` → cursor name;
   each element passes it as a `cursor` prop replacing the old static
   `pen-node`/`pointer-node`/`pointer-move` classes (`pointer-node` is still used by
   feature 9's line endpoints in `viewport/selection.cljs`). `path-editor*` subscribes
   to `(rx/combine-latest ms/keyboard-shift ms/keyboard-mod ms/keyboard-alt)` into a
   `modifiers*` use-state, so cursors react to pressing/releasing modifiers without
   pointer movement. **Trap found live:** the subscription callback closes over the
   first-render `use-state` cursor, whose deref always returns the initial value — a
   `(when (not= value @modifiers*) …)` guard therefore swallowed every key *release*
   (downs rendered, ups didn't). Removed the guard (the keyboard streams are already
   distinct-contiguous, so emissions only happen on real changes).
   - Move node: `move-node`; selected → `move-move`; shift ∧ some node selected →
     `move-add`; ctrl → `move-handles`; alt → `move-copy`; ctrl+alt → `draw-remove`
     (unspecified in the user spec; inferred from the segment/draw removal cells).
   - Move segment: no class (inherits viewport `move`); selected → `move-move`; near
     middle w/ insert preview → `draw-add`; shift → `move-add`; ctrl → `move-curve`;
     alt → `move-copy`; ctrl+alt → `draw-remove`.
   - Handler (both modes): `move`; shift ∧ not selected (move mode) → `move-add`;
     ctrl or alt → `move-remove`; ctrl+alt → `move` (no-op cell).
   - Draw node: `draw-node`; ctrl → `move-handles`; alt / ctrl+alt → `draw-remove`.
   - Draw segment: `draw-add`; ctrl → `move-curve`; alt / ctrl+alt → `draw-remove`.
   - **Insert-preview gating tightened alongside:** the move-mode hover preview now
     also hides while shift or alt are held (those clicks select/duplicate, never
     insert — matches the click branch's existing `when-not shift?` guard).
3. **Drag cursors** (`edition.cljs` `set-drag-cursor`/`clear-drag-cursor`): the drag
   branch of every dispatcher emits `set-drag-cursor` as its first event; the state
   lives at `[:edit-path id :drag-cursor]` and **self-clears** via the event's watch
   (`mse/drag-stopper` ∪ `finish-edition?` → `clear-drag-cursor`), so no drag flow
   needs explicit cleanup (covers the curve-config empty-guard path too). While set,
   it overrides both the viewport cursor (passed as a new `setup-cursor` arg from
   `path-bar-state`, so it works during creation too) and every element's hover
   cursor (`(or drag-cursor …)` in `path-editor*`).
   - Move mode: node drag `move-move` / ctrl `move-handles` / alt `move-copy`;
     segment drag `move-move` / ctrl `move-curve` / alt `move-copy`; handler drag
     `move-move` (smart) / `move-handles` (mirror, independent, aligned).
   - Draw mode: node ctrl (curve-config) `move-handles`; segment ctrl (bend)
     `move-curve`; the no-modifier weld/insert flows thread a cursor through new
     arities `close-path-drag-start [position cursor]` / `start-path-from-point
     [position cursor]` (default `draw-node`; the segment dispatcher passes
     `draw-add`). The canvas add-node drag keeps the viewport `draw` default.

**Verification (2026-07-15):** `clj-kondo` 0/0, `cljfmt` clean, main build `:ok`,
frontend focused suite 54/146 0 failures (root-owned `.shadow-cljs/builds/test` had to
be sudo-removed again — same stale-cache trap as §7). Live on the ws0 WASM dev build
(store-driven, real DOM): pen tool → viewport `cursor-draw`; store-entered move-mode
edition → viewport `cursor-move`, nodes `cursor-move-node`, handlers `cursor-move`,
segments class-less; store-fed keyboard events cycled ctrl/alt/ctrl+alt/shift down
AND up correctly on all element kinds (matrix cells verified: ctrl →
handles/curve/remove, alt → copy/copy/remove, ctrl+alt → draw-remove/draw-remove/move,
shift → handler `move-add`); every helper-fn cell checked against the spec via
page-evaluated calls; `set-drag-cursor` flipped viewport+node+handler to
`cursor-move-copy` and a Blur (drag-stopper) cleared it; exit left clean state, no
exception. **Owed:** the by-hand pass with real pointer gestures (hover transitions,
drag cursors during actual drags, draw-mode cells — synthetic key/pointer events can't
drive hover, and entering edit mode needed store events).

## 16. New feature batch (requested 2026-07-15) — NOT yet implemented

Six new requests. This section is analysis + pointers for a fresh session; none are
coded yet. Continue the feature numbering (34 was the last). Cross-references use the
existing §5/§6 entries. As always: work in the drawing copy, persist once on exit, and
run the frontend suite inside the devenv container (`mem:frontend/testing`).

### Feature 35 — mid-segment node creation must beat pixel-grid snap at high zoom

**Requirement:** when zoomed in a lot, pixel-grid snapping (`:snap-pixel-grid`) currently
takes precedence over "snap to the line segment", so an inserted node drifts off the
line. Node **creation on a segment** should win: the new node lands exactly on the
segment, not on the pixel grid.

**Where it lives:**
- `data/workspace/path/streams.cljs`: `to-pixel-snap` (quantises to 0.5px above
  `half-pixel-snap-zoom` = 3, i.e. zoom > 300%; feature 25) — the shared snap step for
  `drag-stream`'s start point and `move-points-stream`.
- `data/workspace/path/helpers.cljs`: `insertion-point` (nearest segment arc-length
  middle within `segment-insert-threshold` = 12/zoom, or closest point when
  `anywhere?`), `segment-mid-point`, `segment-base-curve`.
- `data/workspace/path/edition.cljs` ~485–525: segment pointer-down cond — the `:else`
  and `mod?` branches call `(helpers/insertion-point content position threshold …)` with
  `position = @ms/mouse-position` (raw), then `create-node-at-position (meta insert-point)`
  → `split-segments` (~917) which splits at the exact arc-length `:t` on the curve.
- `data/workspace/path/drawing.cljs` ~595–605: the draw-mode segment insert path.
- `ui/workspace/shapes/path/editor.cljs`: the dashed insert preview (`use-stream` over
  `insertion-point`, keyed on `ms/mouse-position`/`ms/keyboard-mod`).

**Analysis / hypothesis:** the split itself is parametric (on-curve), so the *created*
node should already be on the line — the drift is most likely in what position feeds the
decision/preview, or a follow-up snap. Repro at zoom > 300% and pin the exact spot:
either (a) the hit/threshold or preview position is being read through `to-pixel-snap`
and rounding pushes it off the segment (so the preview and the split `t` disagree), or
(b) after insertion the node picks up a pixel-snapped position. Fix = the insertion
position must bypass `to-pixel-snap` (segment/on-curve position wins); pixel snapping
should only govern free node/handle placement, not mid-segment insertion. Keep feature
25/29 (half-pixel node moves, arc-length midpoint) intact. Pure enough to unit-test the
"inserted node lies on the segment at high zoom" property; also needs a live check
because the streams read global `ms/mouse-position` (§7 limitation).

### Feature 36 — `Ctrl+A` selects all nodes in the path editor

**Requirement:** add `Ctrl/Cmd+A` in path edition that selects every node.

**Where:** new event in `data/workspace/path/selection.cljs` (alongside `select-node`
~80 and `deselect-all` ~141) — e.g. `select-all-nodes` that sets
`[:workspace-local :edit-path id :selection]` to
`(assoc helpers/empty-selection :nodes (into #{} (helpers/node-indices content)))`
(`helpers/node-indices` already exists; content from `st/get-path`). Export from
`data/workspace/path.cljs` and bind in `data/workspace/path/shortcuts.cljs`:
`:select-all {:command (ds/c-mod "a") :subsections [:path-editor] :fn #(st/emit! (drp/select-all-nodes))}`.

**Gotcha:** workspace-global `Ctrl+A` = "select all shapes"
(`data/workspace/shortcuts` / selection). The path-editor keymap must take precedence
while editing — verify the path shortcuts are the active keymap during edition and that
this binding shadows the global one (check how the other path shortcuts, e.g. `copy`,
already override their global namesakes; same mechanism).

### Feature 37 — `Shift+Delete` deletes the node **and** its adjacent segments (opens the path)

**Requirement:** a second delete shortcut that removes the node together with its
adjacent segments, leaving an open gap (no heal), vs the plain Delete which heals.

**Where:** the operation already exists — `tools/remove-node-with-segments` (feature 34,
§14 "remove node + segments"; incident entries → `remove-segments`), exported from
`path.cljs`. Just add the binding in `shortcuts.cljs`:
`:delete-node-and-segments {:command ["shift+del" "shift+backspace"] :subsections [:path-editor] :fn #(st/emit! (drp/remove-node-with-segments))}`.
Plain `:delete-node` stays `["del" "backspace"] → delete-selected` (heal / feature-30
segment-open semantics). Confirm mousetrap resolves `shift+del` distinctly from `del`
(it does; shift is an explicit modifier). Behaviour parity with the draw-mode `ctrl+alt`
node cell in §14.

### Feature 38 — selected nodes use the segment pink, not the green accent

**Requirement:** selected nodes should be the same pink as the line/segment selection
instead of green.

**Where:** `ui/workspace/shapes/path/editor.cljs`. `accent-color`
(`"var(--color-accent-tertiary)"`, the green) is the selected fill for nodes
(`path-point*` ~148 `:fill (cond is-selected accent-color …)`) and handlers (~235);
`selected-segment-color "#F49EF7"` (pink) is the selected segment stroke (~292). Change
the node selected-fill branch to the pink. Cleanest: rename `selected-segment-color` →
a shared `selected-color` (or add `selected-node-color` = same pink) and use it for the
`is-selected` node fill; decide whether selected **handlers** should match too (request
says nodes — keep handlers as-is unless it looks inconsistent). Trivial + purely visual;
verify live that hover (still `accent-color`) and selected (pink) are distinguishable.

### Feature 39 — paste path nodes **outside** the editor (new shape) and into **another** path editor — DONE 2026-07-16

**Implemented.** (a) New `data/workspace/path/clipboard.cljs` `paste-nodes-as-shape [sub-content]`:
centres the fragment at the pointer (`ms/mouse-position`, else `dsh/get-viewport-center`) by
`path/move-content` (delta = target − `grc/rect->center` of `path/calc-selrect`), then
`dwsh/create-and-add-shape :path` with the moved content (passes `:x/:y/:width/:height` so it
doesn't fall back to the viewport centre; `setup-path` derives geometry from the content, and
`check-shape` tolerates the extra x/y — JVM-verified). It **self-guards**: a no-op while a path
is being edited (`st/get-path-id` + `:edit-path` present), because the editor paste splices
instead. Wired in `data/workspace/clipboard.cljs` `create-paste-from-blob`'s transit `case`:
new `:copied-path-content` branch → `(path-cp/paste-nodes-as-shape (:content pdata))` (requires
`path.clipboard`; acyclic — path.* don't require the generic clipboard). Routing has no
double-paste: the global `:paste` (Ctrl+V) shortcut is `:disabled`/no-op, so outside a path editor
only the DOM paste event fires → one new shape; while editing, the path `:paste` shortcut
(`paste-nodes`) splices AND the DOM event fires but the guard makes the new-shape path a no-op.
(b) **Cross-editor splice** — `paste-nodes`/`paste-content` target the *currently* edited path
(`st/get-path-id`), so pasting into a different open editor splices in. **Paste-at-pointer added
2026-07-16:** `paste-content` now centres the fragment on `@ms/mouse-position` (shared
`center-content-at` helper, before the collision offset) instead of dropping it at the copied
world coords — applies to any editor including the original path (the old +10/+10 in-place
behaviour is gone; the collision offset still nudges an exact overlap). `center-content-at`
computes the centre from the exact node min/max (NOT `points->rect`/`calc-selrect`, which pad a
degenerate axis by 0.01 → half-pixel drift that also defeats the exact-position collision check —
caught by the tests). New shape is dropped **selected**, no auto-edit (the default suggestion).
Tests
`pasting-path-nodes-outside-editor-creates-a-new-path-shape` (centred at pointer + selected) and
`pasting-path-nodes-while-editing-does-not-create-a-shape` (guard), plus updated
`paste-content` position tests; focused suite 56/152, 0
failures; `clj-kondo` 0/0, `cljfmt` clean, build `:ok`. **Owed:** live by-hand pass (copy nodes
in one editor, paste on empty canvas → new selected path; paste into another editor → splice).

Original analysis, for reference:

**Requirement:** copied nodes/segments (feature 2) can currently only be pasted inside
the same path edition. Wanted: (a) pasting **outside** any path editor creates a **new
path shape** whose content is the clipboard path fragment; (b) pasting inside a
**different** path editor splices the nodes into that path.

**Current state:**
- `data/workspace/path/clipboard.cljs`: `copy-selected-nodes` writes
  `{:type :copied-path-content :content <PathData>}` (transit `:json-verbose`) to the
  system clipboard; `paste-nodes` reads it and `paste-content` splices into
  `st/get-path-id` (the *currently* edited path). **Sub-requirement (b) likely already
  works** — `paste-content` targets whatever path is in edition — so mostly needs
  verification, not new code (the `:paste` path shortcut is only bound while editing).
- The generic workspace paste is `data/workspace/clipboard.cljs`
  `paste-from-clipboard`/`paste-from-event` → `create-paste-from-blob` (~266). Its
  `application/transit+json` branch decodes the map and `case`s on `:type`:
  `:copied-props` / `:copied-shapes`, else `(rx/empty)` — so a `:copied-path-content`
  payload is silently dropped today (that's why outside-editor paste is a no-op).

**Approach for (a):** add a `:copied-path-content` case to `create-paste-from-blob`'s
`case` that builds and inserts a new path shape from the fragment. Reuse
`cts/setup-shape {:type :path :content <PathData> …}` (picks up `minimal-path-attrs` —
centre stroke default, feature 18), derive `:selrect`/`:points` via
`path/update-geometry`, place it (centre of viewport or the pointer, mirroring how
`paste-shapes` positions), and commit through the normal add-shape/undo flow
(`dwsh/add-shapes` or the create pipeline used by `paste-shapes`). The fragment is
absolute/world-coord `PathData` (WASM content convention). Keep the new shape selected.
Consider factoring the shape-build into `path/clipboard.cljs` (e.g.
`paste-nodes-as-shape`) and just dispatching to it from the generic `case`, so the path
logic stays in the path namespace. Watch the transit round-trip: path copy uses
`{:type :json-verbose}` and PathData has transit handlers (feature 2) — the generic
`t/decode-str` must reconstruct the `PathData` (verify, and that the `map?` filter +
`:type` dispatch survive before `paste-data-valid?`, which only runs inside
`paste-transit-shapes`).

**Decision needed from the user later:** placement (viewport centre vs pointer vs
+10/+10 offset) and whether an outside paste should also enter path edition on the new
shape or just drop it selected. Default suggestion: drop it selected, no auto-edit.

### Feature 40 — path-editor top-bar refactor — DONE 2026-07-15 (uncommitted)

Implemented in `ui/workspace/viewport/path_actions.cljs` + `.scss`: removed the
Add-node/Remove-node buttons (and their `check-enabled` keys/handlers and the unused
`add-icon`/`remove-icon`); node-op buttons (merge/join/separate/make-corner/make-curve)
are now **conditionally rendered** via a `topbar-button*` component wrapped in `when`
(unavailable → removed, not disabled); groups are separated by a `separator` element
(`.separator` = 1px vertical rule) rendered between the mode group, the (collapsible)
structural + shape groups, and the always-on snap toggle; the bar has a **fixed width**
(`.sub-actions { width: 480px; justify-content: center; }`) so it no longer reflows as
buttons appear/disappear (structural/shape groups + their in-between separator collapse
when empty). Draw/move and snap stay always visible. `clj-kondo` 0/0, build `:ok`.
The snap toggle's icon was swapped 2026-07-16 from the placeholder `:snap-nodes` glyph to a
new dedicated `resources/images/icons/snap.svg` (user-drawn, normalized to the 16×16 standard;
def renamed `snap-nodes-icon` → `snap-icon`, xref key `:snap`). The snap action/shortcut/i18n
key stays `snap-nodes` — only the glyph changed.
**Owed:** the live look (esp. mid-creation per feature 15, and the 480px width once the
feature-41 handler button is added — bump it then); by-hand pass.

Original requirement, for reference: (1) fixed size for the bar; (2) a vertical separator between the
draw/move icons and the rest; (3) remove the **add-node** and **remove-node** buttons;
(4) the remaining node-op icons should be **removed when unavailable** rather than shown
disabled (draw/move/snap stay always visible).

**Where:** `ui/workspace/viewport/path_actions.cljs` (`path-actions*`, `check-enabled`)
and its style module `ui/workspace/viewport/path_actions.scss`.
- **(3)** delete the "Add Node"/"Remove node" `[:button]`s (the `:sub-actions-group` at
  ~171–184) and drop `:add-node`/`:remove-node` from `check-enabled` (~64–65) and the
  `on-add-node`/`on-remove-node` handlers. Note deletion is still available via the
  Delete key (`shortcuts` `:delete-node` → `delete-selected`) and insertion via clicking
  a segment (features 16/29) — only the toolbar buttons go. (The `:add-node` shortcut
  `shift++` can stay or go; confirm with the user. The feature-17 add-nodes *toggle* was
  already removed in §14.)
- **(4)** replace each remaining node-op button's `:disabled (not (:x enabled-buttons))`
  with conditional rendering: `(when (:x enabled-buttons) [:button …])` for merge / join /
  separate / make-corner / make-curve. Keep draw, move and snap always rendered (they are
  mode/toggle controls, not selection-gated). Empty `:sub-actions-group`s should
  contribute nothing (render `nil`), so a group with no enabled buttons collapses.
- **(1)+(2)** in `path_actions.scss`: give `.sub-actions` a fixed width (so it doesn't
  reflow as buttons appear/disappear — important now that buttons are conditionally
  rendered) and add a vertical divider between the draw/move group and the following
  groups (a `border-left`/`::before` on the first group after draw/move, or a dedicated
  separator element, matching the existing design-system separators — see
  `ui-conventions-and-style-system` mem).

**Gotcha (layout):** with buttons appearing/disappearing (4) the bar width would jump; the
fixed size (1) is what stabilises it, so implement them together. Feature 15 renders this
same bar during path *creation* (buttons mostly disabled/empty then) — verify the
conditional-render + fixed-width still looks right mid-draw (empty groups collapse but the
bar keeps its width).

### Feature 41 — handler-behavior button (mirror / aligned / independent) — DONE 2026-07-15 (uncommitted, ephemeral / Option B)

**Implemented** with ephemeral per-node handler-type state (user chose session-scoped).
Files:
- `data/workspace/path/helpers.cljs`: `handler-node-index`, `node-primary-handler`,
  `handlers-equal-length?`, `derive-handler-type` (:mirror = joined+equal, :aligned =
  joined+unequal, else :independent), `remap-handler-types` (keep on same command count,
  drop on structural change).
- `data/workspace/path/tools.cljs`: `set-handler-type` — for each selected node
  (`selected-node-indices`) reshapes handlers in place via zero-delta
  `move-handler-modifiers …rejoin?=true` (mirror) / `align-handler-modifiers` (aligned) /
  `{}` (independent), applies through `path/apply-content-modifiers` + `update-geometry`,
  and records `{node-index → type}` under `[:workspace-local :edit-path id :handler-types]`.
  `process-path-tool` now also `remap-handler-types` (clears on structural change).
- `data/workspace/path/edition.cljs`: `start-path-edit` seeds `:handler-types {}`;
  `start-move-handler`'s plain branch maps the dragged handler's node's stored type
  **directly** to the drag mode — `:independent`→`:independent`, `:aligned`→`:aligned`,
  `:mirror`→`:mirror` (the last guarded to a still-`handlers-joined?` pair so a stale entry
  can't re-mirror a broken corner), no stored type → `:smart`. (An earlier version fell
  back to `:smart` for aligned/independent; that was a bug — `:smart` keeps a *collinear*
  pair joined, so `:independent` failed to detach the opposite handle. REPL-verified: a
  `:independent` drag now leaves the opposite fixed.)
- `path.cljs`: exports `set-handler-type`.
- UI `ui/workspace/viewport/path_actions.cljs` + `.scss`: `handler-type-menu*` split
  button (icon = active type) opening a flyout with the three modes; shown in move mode
  when a node **or handler** is selected; active type = stored, else `derive-handler-type`.
  **Corner nodes are excluded (2026-07-16):** a corner node has no handler behaviour to
  configure, so `handler-visible?` now requires at least one target node that is a curve
  point (`handler-node` = the first such node, which also drives the active-type readout);
  a pure corner-node selection hides the control. The test is `segment/is-curve?` (a handler
  actually offset from the node — the same predicate the make-corner/curve buttons use), NOT
  `node-primary-handler`: `make-corner-point` collapses the handles onto the node but leaves
  the command as `:curve-to` (only `remove-line-curves` demotes to `line-to`, and only when
  *both* sides collapse), so a made-corner node still has a curve-to command with coincident
  handles — `is-curve?` returns false for it, `node-primary-handler` did not (JVM-verified).
  **Flyout reworked 2026-07-16 to mirror the main toolbar's grouped path/line tool**
  (`top_toolbar/group-tool*`): opens **on hover** (`on-pointer-enter`/`-leave` + 350ms
  open/close timers via `app.util.timers`/`cancel-timer!`, disposed in a `with-effect`), and
  the trigger carries the same **bottom-right corner flyout indicator** (the DS `icon-button*`
  `.flyout-indicator` glyph, restyled locally `stroke: var(--pathbar-icon-color)`). The popover
  is always in the DOM and animates via `opacity`/`visibility`/`pointer-events` on an `.open`
  class (80ms, like the toolbar flyout). Trigger **click applies the active type** (analogue of
  the toolbar main button selecting the default tool); item click selects+closes. Indicator
  placement (6px, 4px insets, bottom-right) and open/closed popover visibility verified live
  against the real compiled CSS.
  The control targets nodes, so a handler selection resolves to its owning node via
  `helpers/handler-target-nodes` (`selected-node-indices` ∪ `handler-node-index` of each
  selected handler) — used by both the button visibility/indicator and `set-handler-type`.
  The bar is left-aligned with the snap toggle pinned right (`.snap-group { margin-left: auto }`).
  **Real handle glyphs landed 2026-07-16** (user-drawn paths, exported + normalized to the
  16×16 icon standard): `resources/images/icons/handlers-equal.svg` (:mirror = equal-length
  collinear handles), `handlers-mirror.svg` (:aligned = mirrored angle / independent length),
  `handlers-independent.svg` (:independent = corner). Mapping is now **by geometry** (the
  glyph matches what the mode does): an initial by-name wiring was **swapped 2026-07-16** on
  the user's request so the equal-length mode shows the equal-length glyph. Registered in
  `ui/icons.cljs`; the three `handler-*-icon` defs in `path_actions.cljs` point at them.
  i18n keys `workspace.path.actions.handler-*` in `en.po`.

**Verified (REPL, geometry):** node↔handler index mapping, `derive-handler-type`, and the
mirror/aligned reshape (mirror equalises the two handle lengths; aligned keeps them). **Owed:**
live by-hand pass (hover-open flyout + corner indicator, mirror surviving a drag,
multi-select); frontend focused-suite cases (`set-handler-type`, `derive-handler-type`, `remap-handler-types`);
the real handle icons; and bump the feature-40 bar width (480px) now that this group exists.
**Limitations (ephemeral):** types are lost on editor exit (re-derived on reopen) and dropped
on structural edits (make-corner/curve/merge/split/…); split/paste that shift indices without
changing count are an edge (mitigated for drags by the still-joined guard). **DROPPED
2026-07-16 (user decision): cross-session persistence (design-fork option C) will NOT be
done — the ephemeral behaviour is final.**

**IMPORTANT rumext gotcha found here (applies to feature 40 too):** rumext v2 compiles
hiccup at macro-expansion, so **runtime-built hiccup does not render** — `(into [:div …] …)`
as a component root/child and let-bound `[:> comp …]` vectors + `interpose` silently fail
(the build still says `:ok`). The render was rewritten to **all-literal hiccup with inline
`(when … [:div …])`** children and inline `[:div {:class (stl/css :separator)}]` dividers
(no `into`, no `interpose`, no let-bound hiccup, no `def`'d hiccup). Use `mf/html` if a
runtime structure is ever unavoidable.

**Requirement (added 2026-07-15, with the feature-40 refactor):** a new top-bar button
(a split/popover control) sets the **handler behaviour of the selected node(s)**, with
three options shown in a popover (matching the reference mock — three handle glyphs):
- **left = joined, equal size** → `:mirror` (handles collinear through the node, equal
  length);
- **centre = joined, independent size** → `:aligned` (collinear, each keeps its own
  length);
- **right = independent** → `:independent` (corner; handles move independently).
The button reflects the current node's type. The user note: *"probably this will require
saving the state of the handler types instead of relying on their position."*

**Reuse — the three modes already exist as drag behaviours (feature 34/§14):**
`edition/modify-selected-handlers` `mode` = `:smart|:mirror|:aligned|:independent`;
`helpers/move-handler-modifiers` (rejoin arity = mirror), `helpers/align-handler-modifiers`
(aligned), `helpers/handlers-joined?` (collinear ~180°). `start-move-handler` picks the
mode from ctrl/alt today.

**Why geometry alone is insufficient (the crux):** `handlers-joined?` is a *binary*
collinearity test, so mirror and aligned are indistinguishable by angle (both ~180°;
they differ only in length), and a corner that is *momentarily* collinear reads as
joined. So an "independent" click has no geometric effect to persist, `:smart` drags
degrade a mirror node to aligned (smart keeps the opposite's own length), and the active
indicator can't reliably tell mirror from aligned. Hence the user's "save the state".

**Design fork — where the per-node handler type is stored (NEEDS A DECISION):**
- **A. Derive from geometry, no storage.** Button applies mirror/aligned as an immediate
  geometry op; indicator = derived (equal-len collinear→mirror, unequal→aligned,
  else independent). Cheapest, but `:independent` click is a no-op, mirror doesn't
  survive a later drag, and the indicator is fragile. Does *not* satisfy "save the state".
- **B. Ephemeral edit-state (recommended).** Store `{node → :mirror|:aligned|:independent}`
  under `[:workspace-local :edit-path id :handler-types]`; init empty in
  `edition/start-path-edit`; remap on structural edits (alongside `helpers/remap-selection`);
  the click both applies the geometry AND records the type; `start-move-handler` reads the
  stored type as the default drag mode (ctrl/alt still override); the indicator reads it
  (fallback to derived). Full UX within an editing session, **no data-model change**; lost
  on exit (reopening re-derives). Medium size, self-contained in the path data layer.
  Key detail: key the map by the node **anchor** and resolve a handler `[index prefix]` →
  its node via `path/handler->node` / `path/opposite-index` (mind index shifts).
- **C. Persist on the shape.** Store the handler type in the path data model so it
  survives save/reload. Requires `common/types/path` schema + validation +
  render-wasm + a file migration (see `common/data-model-change-checklist`,
  `file-change-validation-migration-subtleties`, `render-wasm/*` mems). Largest/riskiest.

**Recommendation: B** (ephemeral) unless cross-session persistence is explicitly needed
(then C). Regardless of storage, the **UI** is the same: a new `path_actions*` group
before the snap toggle — a button whose icon = the active mode, opening a small popover
with the three mode buttons; on select emit a new `tools`/`edition` event
`set-handler-type` (applies geometry via the existing mirror/align helpers with a zero
drag delta to reshape in place, and — B/C — records the type). Add the three handle icons
(`resources/images/icons/…`, register in the icon set) and an i18n key per mode. The bar's
fixed width (feature 40, 480px) must grow to fit the new group.

**Immediate-apply geometry (for the click):** mirror = `move-handler-modifiers … rejoin?=true`
with dx=dy=0 on one handler of the node (forces the opposite onto the exact mirror);
aligned = `align-handler-modifiers` dx=dy=0 (opposite rotated collinear, keeps its length);
independent = no geometry change (type-only) — which is exactly why A can't express it.
Apply through `process-path-tool`/`set-content` + `update-geometry` like the other tools.

**Verification for the batch:** `clj-kondo` 0/0 + `cljfmt` on every touched ns; frontend
focused suite green (add cases: `select-all-nodes`, the shift+delete binding →
`remove-node-with-segments`, the outside-editor paste building a `:path` shape,
`set-handler-type` applies the mode + records the type); common suite if any `types/path`
fn changes. Then the live by-hand pass (high-zoom insert for 35, Ctrl+A/Shift+Del, node
colour, outside/cross-editor paste, toolbar look + handler-mode popover) — automation
can't enter edit mode this session (§7).

## 17. Mouse-action & cursor review (2026-07-16) — DONE

A by-item review of the move/draw pointer matrix and cursors, done interactively one
change at a time. These supersede the §14 matrices / §15 cursors where they differ.
All items: compiler `:ok`, `clj-kondo` 0/0, `cljfmt` clean; frontend focused suite 57/154
and common suite 150/484 green.

**Live by-hand pass DONE 2026-07-16 (user).** The user walked the whole path editor by
hand — the mouse-action/cursor matrix (§17), the cursors, and features 25–41 — and
confirmed it all works as intended. This closes the outstanding "live by-hand" item that
the per-feature/per-cursor notes below (and in §14/§15/§16) still mention as owed; treat
those as verified. Any residual issues will be filed as separate bugs rather than tracked
here.

Also landed just before the review: **move cursors enlarged to 25×25** (`ui/cursors.cljs`)
so the pointer matches the `draw` pen physically (same fix as the draw badges; the base
`move` SVG is also `scale(0.762)`); and **handler-drag matching modifiers made live** —
`drag-selected-handlers` computes the mode per frame from the stream's live `:mod?/:alt?`
(see the live-modifier item below, which generalised this).

1. **Move-mode SEGMENT operations** (`edition/start-move-path-segment`,
   `editor/segment-cursor`, hover-preview hook): **ctrl = curve only** (click →
   `tools/toggle-segment-curve`, drag → bend); **alt = add node** (click → insert-anywhere
   `create-node-at-position`, drag → duplicate); **ctrl+alt = remove segment**. Cursors:
   add → `draw-add`, remove → `draw-remove`, ctrl → `move-curve`, alt-drag → `move-copy`.
   The insert **preview** now shows under alt (anywhere) and no-modifier (middle), hidden
   under ctrl.
2. **ctrl+click curve is visibly bowed** (`segment/toggle-segment-curve`): the line→curve
   default handles are offset perpendicular by `curve-toggle-bow` (0.25 × length) instead
   of collinear, so the toggle produces a visible arc, not a straight curve-to. Tests
   updated (common + frontend).
3. **Shift (and the other drag modifiers) apply live mid-drag** (`streams/move-points-stream`,
   `move-handler-stream`, `position-stream`; `edition/curve-config-node-drag`): swapped the
   pointer-derived `ms/mouse-position-*` for the **keyboard** streams via
   `rx/combine-latest-with`, so pressing/releasing a modifier re-emits immediately — the
   15° constraint (and the handler matching mode) engage without needing pointer movement.
4. **make-curve on an acute corner is smooth** (`segment/make-curve-point`, count=2 branch):
   handles are built from the **unit** leg directions — tangent `= unit(u_next − u_prev)`
   (perpendicular to the bisector), equal length `⅓·min(leg)`, opposite sides via
   `dot(dir, tangent)` sign. Fixes the old chord-projection that put both handles on the
   same side (cusp) for acute corners. New test
   `segment-make-curve-point-acute-corner-is-smooth`.
5. **Move-mode NODE operations** (`edition/start-move-path-point`, `editor/node-cursor`):
   **alt+click = remove node (heal)** `tools/remove-node`; **alt+drag = duplicate** (kept);
   **ctrl+alt+click = remove node + surrounding segments (no heal)**
   `tools/remove-node-with-segments`. Cursor: alt/ctrl+alt hover → `draw-remove`, alt-drag →
   `move-copy`. (Mirrors Delete = alt-click, Shift+Delete = ctrl+alt-click.)
6. **ctrl+drag curve-config is smooth whichever way you drag**
   (`edition/curve-config-modifier` + `curve-config-node-drag`): the drag direction picks a
   sign `s = sign(dot(v, unit(→out) − unit(→in)))`; outgoing handle `= node + s·v`, incoming
   `= node − s·v`. The handle on the drag side follows the pointer, the other mirrors, and
   each stays on its own segment's side — no more loop/cusp when dragging toward the
   incoming side. Endpoints keep a single pointer-following handle. New `in-neighbour`/
   `out-neighbour` params.
7. **Draw-mode: removing the segment/node the pending line hangs from clears the pending**
   (`drawing/on-draw-segment-pointer-down`; node case already did this via `pending-origin?`).
   The segment alt/ctrl+alt removal now also emits `common/cancel-pending-segment` when
   `last-point` is one of the removed segment's endpoints, so the next click starts a fresh
   subpath instead of previewing from a removed point.

## 18. Feature 42 — editable node coordinates in the right sidebar — DONE 2026-07-16

**Implemented.** Decisions taken: multi-select = **set-absolute-for-all** (each selected
node's chosen axis is set to the entered value); the read-only shape measures are **kept**
and the editable node x/y block is added **above** them; a **segment** selection folds in
its endpoint nodes (via `selected-node-indices`). Summary:
- **Common** `segment/set-nodes-coordinate [content indices axis value]` (+ `path/…` wrapper):
  per-node delta `value − current` on `axis`, applied through the existing `translate-nodes`
  (moves each node's anchor + attached handles rigidly). Works for one or many nodes. Test
  `segment-set-nodes-coordinate`.
- **Frontend** `tools/set-nodes-coordinate [axis value]` (exported from `path.cljs`): resolves
  the selection with `helpers/selected-node-indices`, applies `path/set-nodes-coordinate` to
  the drawing-copy content + `update-geometry` — same pipeline as `align-nodes`, so it
  previews live and persists once on exit; path-local undo captures it. Store test
  `set-nodes-coordinate-moves-selected-nodes` (single + multi).
- **UI** `node-position-menu*` (in `menus/measures.cljs`, presentational: `{:values :on-x-change
  :on-y-change}`, reuses the shape position inputs' markup/`numeric-input*`, so drag-scrub/
  arithmetic come for free). Wired in `options/shapes/path.cljs path-edition-options*`: derefs
  `refs/workspace-edit-path` (selection) + `refs/current-drawing-shape` (live content), computes
  the selected nodes' shared x/y or `:multiple`, and emits `drp/set-nodes-coordinate`. Shown
  only when ≥1 node resolves. Node coords are **world coords** (the content's own space).
- Verified: compiler `:ok`, `clj-kondo` 0/0, `cljfmt` clean, common 152/488, frontend 58/160.

**Refinements (2026-07-16, same session):**
- **Live during a node drag.** The panel applies the in-progress `content-modifiers`
  (`cpath/apply-content-modifiers base-content modifiers`) before reading positions, so the
  x/y update every frame while dragging (the modifiers only materialize into `:content` on
  release). Reactive because `path-edition-options*` derefs `refs/workspace-edit-path` (which
  contains `[id :content-modifiers]`) and `refs/current-drawing-shape`.
- **Container-relative coordinates.** Node content is world coords; the panel now shows them
  relative to the parent frame like shapes do (`update-position`/`calculate-delta` treat the
  shape x/y input as parent-frame-relative). Offset `ox/oy = parent frame :x/:y`, or `0` when
  the parent is the page root (`cfh/get-parent-frame` + `cfh/root?`) so a top-level path stays
  absolute. Subtracted for display, added back on edit (`set-nodes-coordinate` still takes a
  world value).
- **Layout.** `node-position-menu*` wraps its `:position` row in a `:section.element-set` so
  the `subgrid` resolves — X and Y sit in one row / two columns, matching the shape measures.
- **Handlers too.** The block covers selected **handlers** as well as nodes: display folds in
  each selected handler's control-point position (`cpath/get-handler-point`, reflects live drag
  deltas), and the event became `tools/set-selection-coordinate` — nodes move with their
  handles (`path/set-nodes-coordinate`), handlers move only their own control point (new common
  `segment/set-handlers-coordinate` — sets one axis of `:c1x/:c1y/:c2x/:c2y`, ignores non-curve
  commands). A selection with both applies to all. Tests `segment-set-handlers-coordinate`
  (common) + a handler case in `set-selection-coordinate-moves-selected-points` (frontend).

- **Handler mirror/aligned opposite.** A handler coordinate edit now updates the matching
  (opposite) handler when the node's handler type (feature 41) is `:mirror` (exact opposite:
  `2·node − H'`) or `:aligned` (mirrored angle keeping the opposite's own length); `:independent`
  moves the handler alone. The effective type is the stored `:handler-types[node]` else
  `derive-handler-type`. Built in `tools/handler-target-points` (frontend), applied via the new
  common `segment/set-handler-points` (a `[index prefix] -> point` map). Tests
  `set-selection-coordinate-mirrors-opposite-handler` (frontend) + `segment-set-handler-points`.
- **Hide shape measures when a node/handler/segment is selected.** `path-edition-options*` now
  renders the read-only shape measures only when nothing is selected (`(when (nil? node-values) …)`);
  the interactive coordinate block replaces them while editing.
- **Segments.** With segments selected the block shows the **top-left of the rectangle
  surrounding the selected segments** (`path/calc-selrect` of `path/extract-content
  {:segments …}`); editing **translates** the selected segments (their endpoint nodes + handles,
  via new common `segment/translate-selected-nodes` with a uniform delta) so that corner reaches
  the value. Point mode (nodes/handlers) is unchanged. Tests
  `set-selection-coordinate-translates-segments` (frontend) + `segment-translate-selected-nodes`.

**Owed:** live by-hand (edit a node/handler/segment x/y → it moves + persists; handler on a
mirror/aligned node moves its opposite; drag → panel tracks live; board-relative coords; measures
hidden while selecting; multi-select shared/mixed + apply-all).

### Original spec / analysis (for reference)

**Requirement (user's words, faithfully):** when a node is selected during path edition,
its **x/y coordinates appear in the right sidebar**. The coordinates are **editable and
have all the features of a shape's x/y** (the numeric input: drag-to-scrub, arithmetic,
etc.). Editing a coordinate **moves the node**. It must also work for **multi-selection**:
if the selected nodes share the same x (or y) show that value, otherwise show **mixed**;
changing a coordinate with a multi-selection **applies to all selected nodes**.

**Distinction from feature 28 (important).** Feature 28's `path-edition-options*`
(`ui/workspace/sidebar/options/shapes/path.cljs`) already renders the **shape's** measures
(size/position/rotation) via `measures-menu*` but wrapped **read-only**
(`{:style {:pointer-events "none" :opacity 0.6}}`) — because the shape geometry is owned by
the drawing copy during edition and editing it there neither previews nor persists reliably
(`finalize-path-content` only writes `:content`; WASM bakes rotation into content). Feature
42 is **different**: it adds an editable block for the **selected node's own coordinates**
(a point in the path content), not the shape bbox. The shape size/rotation stay read-only;
only a new **node x/y** block is interactive. (Optionally the read-only shape *position*
row could be replaced by this node block while nodes are selected — a UI decision.)

**Coordinate space.** Node positions come from `helpers/node-position` / `node-positions`
(content-command index → point) and are in **world coords** (WASM content convention),
i.e. the same space arrow-move / drag use — NOT the shape bbox top-left that `measures-menu*`
shows. Display and edit the node's world x/y directly. Watch the classic-vs-WASM
distinction if the non-WASM renderer is ever in play.

**Move pipeline to reuse (already exists, drawing-copy isolated).** Setting a coordinate =
moving the node by `delta = target − current` on that axis. Route it through the same
modifier pipeline the arrow-nudge uses so it previews live and commits once on exit:
`edition/set-move-modifier` (accumulates `content-modifiers`; already takes a `handler-ids`
param and moves a node rigidly with its handles) → `apply-content-modifiers` (materializes
into the drawing copy + `update-geometry`). See `edition/move-selected` (arrow event, line
~724) and `move-node-indices` (~276) for the exact grouped-selection move; a coordinate
edit is just an absolute-position variant of the same thing. Selection lives at
`[:workspace-local :edit-path id :selection :nodes]` (index set); resolve to positions with
`helpers/node-positions`.

**Multi-select semantics (needs a confirmation, but the wording points one way).** "applied
to all selected nodes" + "same → value, else mixed" reads as **set-absolute-for-all**: an
entered x sets **every** selected node's x to that value (align them to x = value); the
other axis is untouched. This differs from shape multi-select (which moves the group's bbox
origin). Confirm with the user before building; the align-all reading is the primary
interpretation. "mixed" display: compute whether all selected node xs are equal (and same
for y); if not, feed the input `:multiple` (the existing measures input already renders
`:multiple` as the mixed placeholder — reuse that convention).

**UI approach.** Reuse the shape x/y **numeric input widget** (the `:x`/`:y` rows inside
`measures-menu*` / the shared `numeric-input*` component) rather than the whole
`measures-menu*` (which is bound to the bbox position via `:points`). Likely a small new
component (e.g. `node-position-menu*`) that: reads the selected node indices + content from
the edit-path state, computes shared/`:multiple` x and y, renders two inputs with
`on-change` → a new `path` event (e.g. `set-nodes-coordinate axis value`) that applies the
absolute set to all selected nodes via the move pipeline above. Render it in
`path-edition-options*` (gated on ≥1 selected node), above or replacing the read-only
position row.

**Pointers:**
- `ui/workspace/sidebar/options/shapes/path.cljs` `path-edition-options*` — where to add the
  node block; it already receives the stored `shape`. It needs the **edit-path selection**
  too (new `refs`/`use-deref`, cf. feature 28's `refs/workspace-edit-path`), since the panel
  currently only sees the stored shape, not the ephemeral selection/drawing-copy content.
- `ui/workspace/sidebar/options/menus/measures.cljs` — the x/y input rows + `:multiple`
  handling to mimic/reuse.
- `data/workspace/path/edition.cljs` — `set-move-modifier`, `move-node-indices`,
  `move-selected`, `apply-content-modifiers` (the move pipeline); add the coordinate-set
  event here (or in `tools.cljs`) and export from `path.cljs`.
- `data/workspace/path/helpers.cljs` — `node-position`, `node-positions`, `node-indices`,
  `selected-node-indices` (fold segment endpoints too if a segment selection should count).
- Tests: a common/pure test isn't needed (no new geometry); add a frontend store test that
  sets a coordinate on a single and a multi node selection and asserts the drawing-copy
  content moved the right node(s), plus a `:multiple` display test.

**Owed decisions before coding:** (1) confirm multi-select = set-absolute-for-all vs
move-as-group; (2) whether to also show/keep the read-only shape position row or replace it
with the node block while editing; (3) whether a **segment** selection contributes its
endpoint nodes to the coordinate block (consistent with other ops via
`selected-node-indices`) or only pure node selections show it.

## 19. Pending refactors (optional, non-functional — not started)

Neither is required for correctness; they are cleanups to pick up if/when worth it.

- **Push node indices into the common path tool fns (remove the dup-coordinate collapse).**
  The common-layer tool fns (`remove-nodes`, the editor's handler grouping `path/get-handlers`,
  the `is-last` checks) are still position-based, so node identities are converted
  indices→positions at the boundary (`move-selected-path-point`, `drag-selected-points`,
  `move-selected` arrows, `streams/move-points-stream`, `snap/create-ranges`,
  `path-actions/check-enabled` — see §9). Consequence: nodes with duplicate coordinates stay
  ambiguous **only** inside those position-based common fns. Pushing stable content-command
  indices into them removes the collapse. Groundwork/context: §9, §12.
- **Reduce per-frame O(n) recompute in `path-editor*` during drags (large paths).** Every
  pointermove re-runs `apply-content-modifiers` over the full content, `get-points`,
  node/segment/handler entry recomputation, a full-path `.toString`, and two PathData +
  `toString` builds per segment hit target — O(n) work + allocation churn per frame; and with
  snap on, `path-snap*` recomputes `snap/create-ranges` every frame (the drag pipeline already
  builds ranges once per gesture in `move-points-stream`). Ideas: memoize per-segment content
  on stable values or skip segment hit targets while a drag is active; derive the editor snap
  ranges from the drag-start content (once per gesture). Only matters for very large paths.
  Detail: §12.
