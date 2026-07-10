# Handoff: Path editor investigation (event ordering / polishing)

Initial investigation: 2026-07-02. Last updated: 2026-07-10. Goal: polish the path
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
`:selected-points` (set of *positions*), `:hover-points`, `:hover-handlers`
(`[index prefix]`), `:content-modifiers` (index → dx/dy/c1x… deltas applied at render
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

**Node identity is position-based, not index-based.** Selection/hover sets hold
geometric points; after modifier application `update-selection` remaps them via an
old-point→new-point map; the editor UI builds `point->base`/`base->point` maps by
zipping point lists. Consequences: duplicate positions collapse into one; two nodes at
the same coordinates are indistinguishable; `create-node-at-position` smuggles split
params in point *metadata*, which breaks value equality (FIXME comment in
`editor.cljs` `on-pointer-down`). This is a systemic source of weirdness and a
candidate for the deep fix (stable indices).

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
- [ ] **Bug 4 — undo-entry re-entrancy.** `add-undo-entry` is injected synchronously
  from the swap that changed drawing content, before that event's watch. Persisting
  during path-local undo has been removed, and `:old-content` now stays stable, but
  entries can still capture transient preview/last-point state.
- [ ] **Bug 5 — two racing stop signals per Esc.** `esc-pressed` and the `:interrupt`
  it emits arrive at different points; different loops still key on different events.
- [ ] **Bug 6 — DOM-order race when closing a path.** Node handlers stop propagation;
  `make-node-events-stream` + `rx/race` cancels `make-drag-stream`, and `rx/race`
  resolves on any first notification including completion.
- [ ] **Bug 7 — bare global `:interrupt` from `check-changed-content`.** Every other
  global interrupt subscriber also reacts, so draw-recursion teardown remains coupled
  to unrelated editor teardown.
- [x] **Bug 8 — `apply-content-modifiers` page-id fallback typo.** The problematic
  commit-building path was removed by the drawing-copy refactor; modifiers now update
  drawing content directly.
- [ ] **Bug 9 — rubber-band selection lags by up to 100 ms.** Caused by
  `handle-area-selection` using `buffer-time 100` for node selection.
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

## 6. Refactors and features checklist

Work planned on top of the polished editor, with required groundwork:

- [x] **Isolate path edition in drawing state and persist once on exit.** Completed
  2026-07-10. `start-path-edit` copies/converts the shape into
  `:workspace-drawing :object`; node/handler/arrow/tool/undo operations do not touch
  file objects; `finalize-path-content` emits zero updates for unchanged content and
  exactly one `update-shapes` for changed content. An unchanged simple shape keeps its
  original type. Empty content is removed only at finalization. Classic and WASM
  viewports render the drawing copy and hide the stored original during edition.
- [ ] **Feature 1 — make segments selectable.** Today selection state is only `:selected-points`
   (positions); segments have *no identity* in state — `path-actions/check-enabled`
   derives them transiently from selected points via
   `path.segm/get-segments-with-points`. Needs: a `:selected-segments` representation,
   segment hit-testing (`path/closest-point` exists for hover; need point→segment-index
   resolution), and rendering of selected segments in `path-editor*`. **Blocked-ish by
   position-based identity**: segments are naturally `[from-idx to-idx]` index pairs,
   which go stale under position-keyed remapping. Strongest argument for the
   index-based-identity groundwork (§2).
- [ ] **Feature 2 — copy/paste of nodes and segments.** Needs a serializable selection (again indices,
   not positions-with-metadata) and common-layer functions to extract a sub-content
   (subset of segments/nodes → valid new `PathData` with move-to normalization) and to
   splice content in. Clipboard plumbing follows the existing workspace copy/paste
   flows. Groundwork: stable identity + `common.types.path` extract/splice API.
- [ ] **Feature 3 — drag selection of segments, nodes and handlers.** Extends
   `selection/handle-area-selection` + `select-node-area`, which today only collect
   *points*. Handlers already have `[index prefix]` identity (hover uses it) — reuse it
   for selection. Segments need rect↔segment intersection in common geometry. Also the
   place to fix the `buffer-time 100` selection lag (bug 9) and unify the selection
   state shape (points + segments + handlers) *before* building three variants on the
   current point-only set.
- [ ] **Feature 4 — round corners in a path.** Fits the existing `process-path-tool` pipeline
   (like make-corner/make-curve); main work is a corner-fillet geometry fn in
   `common.types.path`. If a per-node radius becomes a persistent, editable property
   (rather than a one-shot transform), node identity matters again — per-node params
   can't be keyed by position.
- [ ] **Feature 5 — alignment of nodes (snap).** `app.main.snap` ranges already power node/handler
   snapping (`move-points-stream`, `path-snap*` guides). This adds alignment guides
   between nodes and possibly explicit "align/distribute selected nodes" tools (the
   latter is a simple `process-path-tool`). Modest groundwork; mostly independent.
- [ ] **Feature 6 — improve curve-tool smoothing** (currently produces line segments only). NOTE: the
   freehand curve tool lives *outside* the path editor:
   `frontend/src/app/main/data/workspace/drawing/curve.cljs`. Work = curve fitting
   (e.g. Schneider/least-squares bezier fit) in common, applied on finish. Independent
   of the event-ordering polish, but shares the fitting math with feature 7.
- [ ] **Feature 7 — remove node with curve approximation.** Change `path/remove-nodes` (common) so the
   surviving segment approximates the removed geometry (fit a cubic through the old
   points instead of straight-joining). Pure common-layer geometry; shares the
   bezier-fitting utility with feature 6. Test-first friendly (pure functions).

**Groundwork conclusions:** features 1–3 (and partially 4) all converge on the same
prerequisite — replace position-based node identity with stable index-based identity
and a unified selection model (`{:nodes #{idx} :segments #{[i j]} :handlers #{[i prefix]}}`
or similar), plus the position→index remapping done once at commit time. With the
event-cleanup fixes complete, this is the next structural prerequisite before features
1–3. Features 5–7 are mostly common-layer geometry and can proceed in parallel. A
shared bezier-fitting utility in `common.types.path` serves both 6 and 7.

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

## 8. Suggested plan

Ordered to serve the roadmap in §6:

- [x] **Drawing-state isolation and one final persistent update.** Includes existing
  shapes, newly created paths, unchanged simple-shape preservation, deferred empty-path
  removal, viewport rendering, and initial lifecycle tests.
- [x] **Run the focused frontend unit namespace in a prepared environment.** Done
  2026-07-10 inside the devenv container (host pnpm install is unusable):
  `pnpm run test:quiet -- --focus frontend-tests.logic.path-edition-test --log-level warn`.
- [ ] **Expand the store-driven safety net.** Add pointer sequences for node drag,
  handler drag, arrows, tool operations, path-local undo/redo, Esc, double-click, and
  switching draw↔move.
- [x] **Fix stale clear-edition event matching (bug 1).** Use the
  `workspace.edition` event keyword consistently in path stream stoppers and draw-loop
  termination.
- [x] **Fix post-update cleanup ordering (bug 3).** Capture the active path id before
  removing `:edition`, emit `finish-path` with `rx/of`, and emit nothing for non-path
  edition.
- [ ] **Unify edition stop signals and make deferred ordering explicit.** Replace the
  esc-pressed/`:interrupt`/clear-edition-mode/finish-path split and document or replace
  the load-bearing `observe-on :async` / `delay 0` boundaries.
- [ ] **Introduce stable node/segment/handler identity and a unified selection model.**
  This is the prerequisite for roadmap features 1–3 and part of feature 4.
- [ ] **Implement roadmap features.** Geometry-heavy features can proceed alongside
  structural work; share one bezier-fitting utility between smoothing and
  remove-with-approximation.
- [ ] **Verify the full workflow by hand in devenv.** Pen tool draw, close path,
  node/handler drag, rubber band, arrows, undo, Esc/double-click/toolbar exits, and
  switching draw↔move. Playwright gesture recipes: Serena memory
  `frontend/playwright-gestures`. Crash detection via cljs REPL:
  `frontend/handling-crashes`.

## 9. Context for the new session

- Activate Serena project `penpot`; read `critical-info` and `frontend/core` memories.
- Read this handoff before source exploration; it is the cross-session source of truth.
  Serena memory `frontend/path-editor-architecture-and-event-ordering` contains the
  original investigation and may lag this document.
- At the 2026-07-10 handoff, work is on `piweek-path-editor-enhancements` and is
  uncommitted. Inspect `git status` and the full diff before continuing.
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
