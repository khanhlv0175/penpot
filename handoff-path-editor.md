# Handoff: Path editor investigation (event ordering / polishing)

Initial investigation: 2026-07-02. Last updated: 2026-07-11. Goal: polish the path
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
- [ ] **Bug 13 — make-curve can curve adjacent nodes.** Converting a node to a curve
  (make-curve) sometimes also curves the neighbouring node(s); only the targeted node(s)
  should ever change. Suspect the position-based collapse at the common-fn boundary
  (handler grouping / duplicate-coordinate ambiguity noted in the branch review, §12) or a
  mis-scoped handler write in `process-path-tool` make-curve. It must never affect adjacent
  nodes.

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
- [ ] **Feature 4 — border radius in path corners (round corners).** Fits the existing
   `process-path-tool` pipeline (like make-corner/make-curve); main work is a
   corner-fillet geometry fn in `common.types.path`. If a per-node radius becomes a
   persistent, editable property (rather than a one-shot transform), node identity
   matters again — per-node params can't be keyed by position.
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

- [ ] **Feature 16 — insert a node by clicking a segment (recover the insertion preview).**
   Clicking the middle of a segment should split it and create a node at the click point,
   with a live preview of the node to be added while the pointer hovers the segment. This
   was disabled temporarily for feature 1 (segment selection): `create-node-at-position`
   still exists with split params in the point metadata (§2), but the editor no longer
   renders the insertion preview or invokes it, and segment clicks currently select the
   segment instead. Re-enable the preview + click-to-insert and reconcile it with segment
   selection (likely gated behind feature 17's "add node" mode).
- [ ] **Feature 17 — "add node" mode (shortcut `p`, toolbar toggle).** A dedicated editor
   mode that recovers the full add-node-while-hovering behaviour (feature 16's preview +
   click-to-insert). Toggled on by pressing `p` or by a new toolbar icon in the path
   edition bar (`path_actions.cljs`), and off again by Escape or the same menu toggle.
   Model it on the existing `:draw`/`:move` edit modes (`:edit-mode` in the ephemeral edit
   state) and the `toggle-snap` toolbar pattern.
- [ ] **Feature 18 — default path stroke alignment to center.** New paths should be created
   with a centered stroke; every other shape type keeps the current inside (`:inner`)
   default. Scope the changed default to path creation only.
- [ ] **Feature 19 — Alt toggles handler join in both directions.** Today, holding Alt
   while dragging a joined (mirrored) handler breaks the pair so the two move
   independently. Add the reverse: holding Alt while dragging an already-independent
   handler re-joins the pair (restores mirrored / opposite-angle movement). Lives in the
   handler-drag pipeline (`move-handler-stream` / `modify-handler`, opposite-angle
   matching).
- [ ] **Feature 20 — move handlers while drawing/creating a path.** During draw mode (not
   only move mode) the user should be able to grab and drag the Bézier handlers of
   already-placed nodes.

**Groundwork conclusions:** the stable index identity and unified selection prerequisite
for features 1–3, 8, 10, and 11 is complete. Feature 4 can reuse the same node identity,
and its fillet geometry can build on `helpers/fit-cubic`/`fit-curve`
(`common/src/app/common/types/path/helpers.cljc`), landed with features 7 and 6.

**Remaining work:**

- [ ] **Open features** (see §6 entries above): 4 (round corners), 16 (insert node on
  segment click + recover insertion preview), 17 ("add node" mode: `p` / toolbar / Escape),
  18 (default path stroke to center), 19 (Alt toggles handler join both ways), 20 (move
  handlers while drawing).
- [ ] **Bug 13 — make-curve can curve adjacent nodes** (see §5).
- [ ] **Refactor — unify edition stop signals and make deferred ordering explicit.**
  Replace the `esc-pressed`/`:interrupt`/`clear-edition-mode`/`finish-path` split with a
  single canonical stop path, and document or replace the load-bearing
  `rx/observe-on :async` / `rx/delay 0` boundaries (see §3). Not started.
- [ ] **Testing — expand the store-driven safety net** (see §7). Add pointer sequences
  for node drag, handler drag, arrows, tool ops, path-local undo/redo, Esc, double-click,
  and draw↔move switching, plus a by-hand devenv pass over the full workflow (pen draw,
  close path, drags, rubber band, arrows, undo, and the Esc/double-click/toolbar exits).
  Playwright recipes: Serena memory `frontend/playwright-gestures`; crash detection:
  `frontend/handling-crashes`.

## 7. Testing status

Continue growing the safety net alongside refactors. Current coverage:

- **Common layer**: `common/test/common_tests/types/path_data_test.cljc` (~1600 lines,
  good coverage of PathData/segment/subpath primitives) and `svg_path_test.cljc`.
  New geometry (extract/splice, fillet, bezier fit, remove-with-approximation) should
  land here as pure-function tests — cheapest and most valuable.
- **Frontend event layer**: `frontend/test/frontend_tests/logic/path_edition_test.cljs`
  now covers drawing-copy isolation, unchanged simple-shape exit, changed-content
  finalization, and created-path cleanup. It is registered in
  `frontend_tests/runner.cljs`. The tests use a real potok store through
  `frontend-tests.helpers.state`. Still missing: pointer-driven node/handler drags,
  path-local undo/redo, and draw/close gestures. Bugs 1, 3, and 11 have direct
  event-level coverage. Simulated
  `mse/->PointerEvent`/`mse/->MouseEvent` sequences remain the right
  next layer.
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
