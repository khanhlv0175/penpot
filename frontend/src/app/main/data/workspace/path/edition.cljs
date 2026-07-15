;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.path.edition
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.common.geom.point :as gpt]
   [app.common.types.path :as path]
   [app.common.types.path.helpers :as path.helpers]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.edition :as dwe]
   [app.main.data.workspace.path.changes :as changes]
   [app.main.data.workspace.path.helpers :as helpers]
   [app.main.data.workspace.path.selection :as selection]
   [app.main.data.workspace.path.state :as st]
   [app.main.data.workspace.path.streams :as streams]
   [app.main.data.workspace.path.tools :as tools]
   [app.main.data.workspace.path.undo :as undo]
   [app.main.streams :as ms]
   [app.render-wasm.svg-fills :as svg-fills]
   [app.util.mouse :as mse]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

(defn- handler-modifier-delta
  [modifiers index prefix]
  (let [[cx cy] (path.helpers/prefix->coords prefix)]
    (gpt/point (dm/get-in modifiers [index cx] 0)
               (dm/get-in modifiers [index cy] 0))))

(defn- remove-handler-modifier
  [modifiers [index prefix]]
  (let [[cx cy]  (path.helpers/prefix->coords prefix)
        modifiers (update modifiers index dissoc cx cy)]
    (cond-> modifiers
      (empty? (get modifiers index)) (dissoc index))))

(defn modify-selected-handlers
  "Applies a handler drag. `mode` decides what happens to the matching
  (opposite) handler of each dragged one:

  - `:smart`       preserve the node's current state — a joined pair stays
                   joined (the opposite mirrors the angle, keeping its own
                   length), a corner pair moves independently; acts on the
                   whole handler selection.
  - `:mirror`      force the opposite onto the exact mirror (same length and
                   opposite angle), re-joining a corner pair.
  - `:independent` never touch the opposite.
  - `:aligned`     force the opposite to mirror the angle while keeping its
                   own length, even on a corner pair.

  The non-`:smart` modes act only on the grabbed handler."
  [id primary start-modifiers dx dy mode]
  (ptk/reify ::modify-selected-handlers
    ptk/UpdateEvent
    (update [_ state]
      (let [content           (st/get-path state :content)
            selected-handlers (if (= mode :smart)
                                (dm/get-in state
                                           [:workspace-local :edit-path id :selection :handlers]
                                           #{})
                                #{primary})
            ;; A stale identity whose command is no longer a curve must not
            ;; receive deltas: handler modifiers over a line-to would convert
            ;; it back into a curve.
            selected-handlers (into #{}
                                    (filter (fn [[index _]]
                                              (= :curve-to (:command (nth content index nil)))))
                                    selected-handlers)
            selected-handlers (cond-> selected-handlers
                                (empty? selected-handlers) (conj primary))
            move-delta        (gpt/point dx dy)
            moved-modifiers
            (reduce
             (fn [modifiers [index prefix :as handler-id]]
               (let [start-delta    (handler-modifier-delta start-modifiers index prefix)
                     delta          (gpt/add start-delta move-delta)
                     opposite-id    (path/opposite-index content index prefix)
                     opposite-selected? (and (= mode :smart)
                                             (contains? selected-handlers opposite-id))
                     joined?        (helpers/handlers-joined? content index prefix)
                     handler-modifiers
                     (case mode
                       :aligned
                       (helpers/align-handler-modifiers content
                                                        index
                                                        prefix
                                                        (:x delta)
                                                        (:y delta))

                       :mirror
                       (helpers/move-handler-modifiers content
                                                       index
                                                       prefix
                                                       true
                                                       true
                                                       true
                                                       (:x delta)
                                                       (:y delta))

                       :independent
                       (helpers/move-handler-modifiers content
                                                       index
                                                       prefix
                                                       false
                                                       false
                                                       false
                                                       (:x delta)
                                                       (:y delta))

                       ;; :smart
                       (helpers/move-handler-modifiers content
                                                       index
                                                       prefix
                                                       false
                                                       (and joined? (not opposite-selected?))
                                                       false
                                                       (:x delta)
                                                       (:y delta)))
                     handler-modifiers
                     (cond-> handler-modifiers
                       opposite-selected?
                       (remove-handler-modifier opposite-id))]
                 (d/deep-merge modifiers handler-modifiers)))
             {}
             selected-handlers)
            modifiers         (d/deep-merge start-modifiers moved-modifiers)
            [primary-index primary-prefix] primary
            primary-handler   (path/get-handler-point content primary-index primary-prefix)
            primary-delta     (gpt/add
                               (handler-modifier-delta start-modifiers
                                                       primary-index
                                                       primary-prefix)
                               move-delta)
            moving-handler    (gpt/add primary-handler primary-delta)]
        (-> state
            (assoc-in [:workspace-local :edit-path id :content-modifiers] modifiers)
            (assoc-in [:workspace-local :edit-path id :moving-handler] moving-handler))))))

(defn- apply-content-modifiers*
  [id new-content]
  (ptk/reify ::apply-content-modifiers*
    ptk/UpdateEvent
    (update [_ state]
      (cond-> (-> state
                  (st/set-content new-content)
                  (update-in [:workspace-local :edit-path id]
                             dissoc
                             :content-modifiers
                             :moving-nodes
                             :moving-handler))
        (seq new-content)
        (update-in (st/get-path-location state) path/update-geometry)))

    ptk/WatchEvent
    (watch [_ _ _]
      ;; Modifiers only move existing nodes/handlers, they never change the
      ;; content structure, so node indices (the selection identity) stay valid
      ;; and need no remap. Empty content means the last node was removed.
      (when (empty? new-content)
        (rx/of (dwe/clear-edition-mode))))))

(defn apply-content-modifiers []
  (ptk/reify ::apply-content-modifiers
    ptk/WatchEvent
    (watch [_ state _]
      (let [id    (st/get-path-id state)
            shape (st/get-path state)

            content-modifiers
            (dm/get-in state [:workspace-local :edit-path id :content-modifiers])]
        (if (or (nil? shape) (nil? content-modifiers))
          (rx/of (dwe/clear-edition-mode))
          (let [content     (get shape :content)
                new-content (path/apply-content-modifiers content content-modifiers)]
            (when (some? new-content)
              (rx/of (apply-content-modifiers* id new-content)))))))))

(def ^:private merge-drop-distance
  "Screen-space distance (px) within which dropping a dragged node onto another
  node merges the two."
  10)

(defn merge-dragged-on-drop
  "After a node or segment drag, if any moved node was dropped onto another node
  (within `merge-drop-distance`) merge the closest such pair together — the same
  result as selecting both and merging."
  []
  (ptk/reify ::merge-dragged-on-drop
    ptk/WatchEvent
    (watch [_ state _]
      (let [id        (st/get-path-id state)
            content   (st/get-path state :content)
            selection (dm/get-in state [:workspace-local :edit-path id :selection])

            ;; nodes that just moved: selected nodes plus the endpoints of any
            ;; selected (dragged) segment
            moved-indices (into (get selection :nodes #{})
                                (helpers/segment-node-indices content (get selection :segments #{})))
            moved     (helpers/node-positions content moved-indices)
            moved-set (set moved)

            zoom      (dm/get-in state [:workspace-local :zoom] 1)
            threshold (/ merge-drop-distance zoom)
            others    (remove moved-set (path/get-points content))

            pairs     (->> moved
                           (keep (fn [p]
                                   (let [near (filter #(<= (gpt/distance % p) threshold) others)]
                                     (when (seq near)
                                       (let [t (apply min-key #(gpt/distance % p) near)]
                                         [p t (gpt/distance t p)]))))))
            best      (when (seq pairs)
                        (apply min-key #(nth % 2) pairs))]
        (if (some? best)
          (let [[p t _] best]
            (rx/of (tools/process-path-tool #{p t} path/merge-nodes)))
          (rx/empty))))))

(defn modify-content-point
  [content {dx :x dy :y} modifiers point]
  (let [point-indices (path/point-indices content point) ;; [indices]
        handler-indices (path/handler-indices content point) ;; [[index prefix]]

        modify-point
        (fn [modifiers index]
          (-> modifiers
              (update index assoc :x dx :y dy)))

        modify-handler
        (fn [modifiers [index prefix]]
          (let [cx (d/prefix-keyword prefix :x)
                cy (d/prefix-keyword prefix :y)]
            (-> modifiers
                (update index assoc cx dx cy dy))))]

    (as-> modifiers $
      (reduce modify-point   $ point-indices)
      (reduce modify-handler $ handler-indices))))

(defn set-move-modifier
  "Accumulates a `move-modifier` delta into the content modifiers for the
  given node `points` (each moved rigidly with its handlers) and the given
  `handler-ids` (`[index prefix]`, each control point moved on its own)."
  [points handler-ids move-modifier]
  (ptk/reify ::set-modifiers
    ptk/UpdateEvent
    (update [_ state]
      (let [id      (st/get-path-id state)
            content (st/get-path state :content)
            {dx :x dy :y} move-modifier

            content-modifiers (dm/get-in state [:workspace-local :edit-path id :content-modifiers] {})

            content-modifiers
            (->> points
                 (reduce (partial modify-content-point content move-modifier) content-modifiers))

            content-modifiers
            (->> handler-ids
                 (reduce (fn [modifiers [index prefix]]
                           (let [cx (d/prefix-keyword prefix :x)
                                 cy (d/prefix-keyword prefix :y)]
                             (update modifiers index assoc cx dx cy dy)))
                         content-modifiers))]

        (-> state
            (assoc-in [:workspace-local :edit-path id :content-modifiers] content-modifiers))))))

(defn- move-node-indices
  [state node-indices from-point to-point]
  (let [id        (st/get-path-id state)
        content   (st/get-path state :content)
        to-point  (cond-> to-point
                    (:shift? to-point) (path.helpers/position-fixed-angle from-point))
        delta     (gpt/subtract to-point from-point)
        points    (helpers/node-positions content node-indices)
        reducer   (partial modify-content-point content delta)
        modifiers (dm/get-in state [:workspace-local :edit-path id :content-modifiers] {})
        modifiers (reduce reducer modifiers points)]
    (-> state
        (assoc-in [:workspace-local :edit-path id :moving-nodes] true)
        (assoc-in [:workspace-local :edit-path id :content-modifiers] modifiers))))

(defn move-selected-path-point [from-point to-point]
  (ptk/reify ::move-point
    ptk/UpdateEvent
    (update [_ state]
      (let [id             (st/get-path-id state)
            selected-nodes (dm/get-in state
                                      [:workspace-local :edit-path id :selection :nodes]
                                      #{})]
        (move-node-indices state selected-nodes from-point to-point)))))

(defn move-selected-path-segment [from-point to-point]
  (ptk/reify ::move-segment
    ptk/UpdateEvent
    (update [_ state]
      (let [id                (st/get-path-id state)
            content           (st/get-path state :content)
            selected-segments (dm/get-in state
                                         [:workspace-local :edit-path id :selection :segments]
                                         #{})
            node-indices      (helpers/segment-node-indices content selected-segments)]
        (move-node-indices state node-indices from-point to-point)))))

(defn- clear-drag-cursor []
  (ptk/reify ::clear-drag-cursor
    ptk/UpdateEvent
    (update [_ state]
      (let [id (st/get-path-id state)]
        (d/update-in-when state [:workspace-local :edit-path id] dissoc :drag-cursor)))))

(defn set-drag-cursor
  "Stores the cursor (a static cursor name) shown while the current pointer
  gesture lasts; the viewports and the editor elements render it in place of
  the hover cursors. It clears itself when the drag stops."
  [cursor]
  (ptk/reify ::set-drag-cursor
    ptk/UpdateEvent
    (update [_ state]
      (let [id (st/get-path-id state)]
        (d/update-in-when state [:workspace-local :edit-path id] assoc :drag-cursor cursor)))

    ptk/WatchEvent
    (watch [_ _ stream]
      (->> (rx/merge
            (mse/drag-stopper stream)
            (rx/filter streams/finish-edition? stream))
           (rx/take 1)
           (rx/map #(clear-drag-cursor))))))

(declare drag-selected-points)

(def duplicate-offset
  "Displacement applied to a duplicated node/segment so the copy does not land
  exactly on the original (the position-based move pipeline would otherwise drag
  both together)."
  (gpt/point 10 10))

(defn splice-duplicated
  "Splices duplicate content (`{:sub <commands> :selected <relative indices>}`,
  as produced by `helpers/duplicate-selection-content`) into the edited path as
  new subpaths and selects the new nodes."
  [{:keys [sub selected]}]
  (ptk/reify ::splice-duplicated
    ptk/UpdateEvent
    (update [_ state]
      (let [id (st/get-path-id state)]
        (if (and (some? id) (seq sub))
          (let [content     (st/get-path state :content)
                base        (count content)
                new-content (path/splice-content content sub)
                pasted      (into #{} (map #(+ base %)) selected)]
            (-> state
                (st/set-content new-content)
                (update-in (st/get-path-location state) path/update-geometry)
                (assoc-in [:workspace-local :edit-path id :selection]
                          (assoc helpers/empty-selection :nodes pasted))))
          state)))))

(defn- duplicate-and-drag
  "Alt+drag: duplicates the current node/segment selection and drags the copy
  from `start-position`. Dragging the duplicated endpoint nodes translates a
  copied segment; a duplicated node tracks the pointer while its copied
  incident segments stay attached to the original neighbors."
  [start-position]
  (ptk/reify ::duplicate-and-drag
    ptk/WatchEvent
    (watch [_ state _]
      (let [id        (st/get-path-id state)
            content   (st/get-path state :content)
            selection (dm/get-in state [:workspace-local :edit-path id :selection])
            result    (helpers/duplicate-selection-content content selection duplicate-offset)]
        (if (seq (:sub result))
          (rx/of (splice-duplicated result)
                 (drag-selected-points start-position))
          (rx/of (drag-selected-points start-position)))))))

(declare curve-config-node-drag)

(defn start-move-path-point
  "Node pointer-down dispatcher (move mode). The action is decided from the
  modifiers captured on the DOM event: a plain/shift gesture selects on click
  and drags the selection; alt selects on click and duplicates the selection
  on drag; the platform modifier toggles corner/curve on click and reshapes
  the node's curve on drag; mod+alt removes the node (healing the gap) on
  click and does nothing on drag."
  [index shift? alt? mod?]
  (ptk/reify ::start-move-path-point
    ptk/WatchEvent
    (watch [_ state _]
      (let [id (st/get-path-id state)
            selected-nodes (dm/get-in state [:workspace-local :edit-path id :selection :nodes] #{})
            selected? (contains? selected-nodes index)
            content   (st/get-path state :content)
            position  (when (and (some? content)
                                 (< index (count content))
                                 (helpers/node? content index))
                        (helpers/node-position content index))]
        (cond
          (and mod? alt?)
          (streams/drag-stream
           (rx/empty)
           (if (some? position)
             (rx/of (tools/remove-node position))
             (rx/empty)))

          mod?
          (streams/drag-stream
           (rx/of (set-drag-cursor "move-handles")
                  (curve-config-node-drag index))
           (rx/of (tools/toggle-node-curve index)))

          alt?
          (streams/drag-stream
           (rx/of
            (set-drag-cursor "move-copy")
            (when-not selected? (selection/select-node index false))
            (duplicate-and-drag @ms/mouse-position))
           (rx/of (selection/select-node index false)))

          :else
          (streams/drag-stream
           (rx/of
            (set-drag-cursor "move-move")
            (when-not selected? (selection/select-node index shift?))
            (drag-selected-points @ms/mouse-position))
           (rx/of (selection/select-node index shift?))))))))

(defn drag-selected-points
  [start-position]
  (ptk/reify ::drag-selected-points
    ptk/WatchEvent
    (watch [_ state stream]
      (let [stopper (mse/drag-stopper stream)

            id (dm/get-in state [:workspace-local :edition])

            content (st/get-path state :content)

            selected-nodes (dm/get-in state [:workspace-local :edit-path id :selection :nodes] #{})
            selected-points (helpers/node-positions content selected-nodes)

            start-position (apply min-key #(gpt/distance start-position %) selected-points)

            points  (path/get-points content)]

        (rx/concat
         ;; This stream checks the consecutive mouse positions to do the dragging
         (->> points
              (streams/move-points-stream start-position selected-points)
              (rx/map #(move-selected-path-point start-position %))
              (rx/take-until stopper))
         (rx/of (apply-content-modifiers)
                (merge-dragged-on-drop)))))))

(declare drag-selected-segments)
(declare bend-selected-segment)
(declare create-node-at-position)

(defn start-move-path-segment
  "Segment pointer-down dispatcher (move mode). The action is decided from
  the modifiers captured on the DOM event: a plain click selects the segment
  (near its visual middle it inserts a node instead) and a plain/shift drag
  translates the selection; the platform modifier inserts a node anywhere on
  click and bends the segment on drag; alt selects on click and duplicates the
  selection on drag; mod+alt removes the segment on click and does nothing on
  drag."
  [index shift? alt? mod?]
  (ptk/reify ::start-move-path-segment
    ptk/WatchEvent
    (watch [_ state _]
      (let [id                (st/get-path-id state)
            zoom              (dm/get-in state [:workspace-local :zoom] 1)
            content           (st/get-path state :content)
            selected-segments (dm/get-in state
                                         [:workspace-local :edit-path id :selection :segments]
                                         #{})
            selected?         (contains? selected-segments index)
            position          @ms/mouse-position
            threshold         (/ helpers/segment-insert-threshold zoom)]
        (cond
          (and mod? alt?)
          (streams/drag-stream
           (rx/empty)
           (rx/of (tools/remove-segment index)))

          mod?
          (let [entry        (d/seek #(= index (:index %)) (helpers/segment-entries content))
                bend?        (and (some? entry)
                                  (not= :close-path (:command (:segment entry))))
                insert-point (helpers/insertion-point content position threshold true)]
            (streams/drag-stream
             (rx/of (set-drag-cursor "move-curve")
                    (if bend?
                      (bend-selected-segment index position)
                      (drag-selected-segments position)))
             (if (some? insert-point)
               (rx/of (create-node-at-position (meta insert-point)))
               (rx/empty))))

          alt?
          (streams/drag-stream
           (rx/of
            (set-drag-cursor "move-copy")
            (when-not selected? (selection/select-segment index false))
            (duplicate-and-drag position))
           (rx/of (selection/select-segment index false)))

          :else
          (let [insert-point (when-not shift?
                               (helpers/insertion-point content position threshold false))
                click-event  (if (some? insert-point)
                               (create-node-at-position (meta insert-point))
                               (selection/select-segment index shift?))]
            (streams/drag-stream
             (rx/of
              (set-drag-cursor "move-move")
              (when-not selected? (selection/select-segment index shift?))
              (drag-selected-segments position))
             (rx/of click-event))))))))

(defn- segment-entry
  [content index]
  (d/seek #(= index (:index %)) (helpers/segment-entries content)))

(defn- segment-base-curve
  "The `[start end h1 h2]` cubic of a segment entry; a `line-to` is the
  degenerate cubic with both control points at its endpoints."
  [{:keys [from to segment]}]
  (if (= :curve-to (:command segment))
    [from to (path/get-handler segment :c1) (path/get-handler segment :c2)]
    [from to from to]))

(defn drag-selected-segments
  [start-position]
  (ptk/reify ::drag-selected-segments
    ptk/WatchEvent
    (watch [_ state stream]
      (let [stopper           (mse/drag-stopper stream)
            id                (dm/get-in state [:workspace-local :edition])
            content           (st/get-path state :content)
            selected-segments (dm/get-in state
                                         [:workspace-local :edit-path id :selection :segments]
                                         #{})
            node-indices      (helpers/segment-node-indices content selected-segments)
            selected-points   (helpers/node-positions content node-indices)
            points            (path/get-points content)]
        (if (empty? selected-points)
          (rx/empty)
          (rx/concat
           (->> points
                (streams/move-points-stream start-position selected-points)
                (rx/map #(move-selected-path-segment start-position %))
                (rx/take-until stopper))
           (rx/of (apply-content-modifiers)
                  (merge-dragged-on-drop))))))))

(defn bend-segment-modifier
  "Sets the content-modifiers that bend segment `index` (its base cubic
  captured in `base-curve`) so the point grabbed at parameter `t` follows
  `target`. `apply-content-modifiers` promotes a `line-to` to a `curve-to`
  on receiving the handle deltas."
  [index base-curve t target]
  (ptk/reify ::bend-segment-modifier
    ptk/UpdateEvent
    (update [_ state]
      (let [id        (st/get-path-id state)
            deltas    (path.helpers/bend-curve-deltas base-curve t target)
            modifiers (dm/get-in state [:workspace-local :edit-path id :content-modifiers] {})]
        (assoc-in state [:workspace-local :edit-path id :content-modifiers]
                  (assoc modifiers index deltas))))))

(defn bend-selected-segment
  [index start-position]
  (ptk/reify ::bend-selected-segment
    ptk/WatchEvent
    (watch [_ state stream]
      (let [stopper    (mse/drag-stopper stream)
            content    (st/get-path state :content)
            entry      (segment-entry content index)
            base-curve (segment-base-curve entry)
            ;; parameter of the grabbed point, held fixed for the drag so
            ;; that exact point tracks the pointer
            t          (path.helpers/curve-closest-t base-curve start-position 0.001)]
        (rx/concat
         (->> ms/mouse-position
              (rx/filter gpt/point?)
              (rx/map streams/to-pixel-snap)
              (rx/map #(bend-segment-modifier index base-curve t %))
              (rx/take-until stopper))
         (rx/of (apply-content-modifiers)))))))

(defn- curve-config-modifier
  "Sets the content-modifiers that give a node symmetric handles: the outgoing
  handle (`:c1` of `out-index`) lands on `position` and the incoming handle
  (`:c2` of `in-index`) mirrors it across the node."
  [node in-index in-base out-index out-base position]
  (ptk/reify ::curve-config-modifier
    ptk/UpdateEvent
    (update [_ state]
      (let [id        (st/get-path-id state)
            v         (gpt/to-vec node position)
            modifiers (dm/get-in state [:workspace-local :edit-path id :content-modifiers] {})
            modifiers (cond-> modifiers
                        (some? in-index)
                        (assoc in-index
                               (let [target (gpt/subtract node v)]
                                 {:c2x (- (:x target) (:x in-base))
                                  :c2y (- (:y target) (:y in-base))}))

                        (some? out-index)
                        (assoc out-index
                               (let [target (gpt/add node v)]
                                 {:c1x (- (:x target) (:x out-base))
                                  :c1y (- (:y target) (:y out-base))})))]
        (assoc-in state [:workspace-local :edit-path id :content-modifiers] modifiers)))))

(defn curve-config-node-drag
  "Mod+drag on a node: reshapes its curve as if the node were being created
  for the first time — mirrored equal handles following the pointer (the
  outgoing handle tracks the cursor, the incoming one mirrors it across the
  node). Any existing handles of the node are replaced."
  [index]
  (ptk/reify ::curve-config-node-drag
    ptk/WatchEvent
    (watch [_ state stream]
      (let [content  (st/get-path state :content)
            node     (when (and (some? content)
                                (< index (count content))
                                (helpers/node? content index))
                       (helpers/node-position content index))
            in-cmd   (nth content index nil)
            out-cmd  (nth content (inc index) nil)
            in?      (contains? #{:line-to :curve-to} (:command in-cmd))
            out?     (contains? #{:line-to :curve-to} (:command out-cmd))
            ;; a promoted line-to gets its handles at the segment endpoints,
            ;; so the delta base of a missing handle is the node itself
            in-base  (when in?
                       (if (= :curve-to (:command in-cmd))
                         (path/get-handler in-cmd :c2)
                         node))
            out-base (when out?
                       (if (= :curve-to (:command out-cmd))
                         (path/get-handler out-cmd :c1)
                         node))
            stopper  (rx/merge
                      (mse/drag-stopper stream)
                      (->> stream
                           (rx/filter streams/finish-edition?)))]
        (if (and (some? node) (or in? out?))
          (rx/concat
           (->> ms/mouse-position
                (rx/filter gpt/point?)
                (rx/with-latest-from
                  (fn [position shift?]
                    (assoc position :shift? shift?))
                  ms/mouse-position-shift)
                (rx/map
                 (fn [{:keys [x y shift?]}]
                   (let [position (cond-> (gpt/point x y)
                                    shift? (path.helpers/position-fixed-angle node))]
                     (curve-config-modifier node
                                            (when in? index)
                                            in-base
                                            (when out? (inc index))
                                            out-base
                                            position))))
                (rx/take-until stopper))
           (rx/of (apply-content-modifiers)))
          (rx/empty))))))

(defn- get-displacement
  "Retrieve the correct displacement delta point for the
  provided direction speed and distances thresholds."
  [direction]
  (case direction
    :up (gpt/point 0 (- 1))
    :down (gpt/point 0 1)
    :left (gpt/point (- 1) 0)
    :right (gpt/point 1 0)))

(defn finish-move-selected []
  (ptk/reify ::finish-move-selected
    ptk/UpdateEvent
    (update [_ state]
      (let [id (dm/get-in state [:workspace-local :edition])]
        (-> state
            (update-in [:workspace-local :edit-path id] dissoc :current-move))))))

(defn move-selected
  [direction shift?]

  (let [same-event (js/Symbol "same-event")]
    (ptk/reify ::move-selected
      IDeref
      (-deref [_] direction)

      ptk/UpdateEvent
      (update [_ state]
        (let [id (dm/get-in state [:workspace-local :edition])
              current-move (dm/get-in state [:workspace-local :edit-path id :current-move])]
          (if (nil? current-move)
            (-> state
                (assoc-in [:workspace-local :edit-path id :moving-nodes] true)
                (assoc-in [:workspace-local :edit-path id :current-move] same-event))
            state)))

      ptk/WatchEvent
      (watch [_ state stream]
        (let [id (dm/get-in state [:workspace-local :edition])
              current-move (dm/get-in state [:workspace-local :edit-path id :current-move])]
          ;; id can be null if we just selected the tool but we didn't start drawing
          (if (and id (= same-event current-move))
            (let [content           (st/get-path state :content)
                  selection         (dm/get-in state [:workspace-local :edit-path id :selection])
                  selected-nodes    (get selection :nodes #{})
                  selected-segments (get selection :segments #{})
                  selected-handlers (get selection :handlers #{})

                  ;; nodes and segment endpoints move as rigid nodes (with their
                  ;; handlers); selected handlers move as lone control points
                  node-indices      (into selected-nodes
                                          (helpers/segment-node-indices content selected-segments))
                  points            (helpers/node-positions content node-indices)
                  handler-ids       (into #{}
                                          (filter (fn [[index _]]
                                                    (= :curve-to (:command (nth content index nil)))))
                                          selected-handlers)

                  move-events (->> stream
                                   (rx/filter (ptk/type? ::move-selected))
                                   (rx/filter #(= direction (deref %))))

                  stopper (->> move-events (rx/debounce 100) (rx/take 1))

                  scale (if shift? (gpt/point 10) (gpt/point 1))

                  mov-vec (gpt/multiply (get-displacement direction) scale)]

              (rx/concat
               (rx/merge
                (->> move-events
                     (rx/take-until stopper)
                     (rx/scan #(gpt/add %1 mov-vec) (gpt/point 0 0))
                     (rx/map #(set-move-modifier points handler-ids %)))

                ;; First event is not read by the stream so we need to send it again
                (rx/of (move-selected direction shift?)))

               (rx/of (apply-content-modifiers)
                      (finish-move-selected))))
            (rx/empty)))))))

(declare drag-selected-handlers)

(defn start-move-handler
  "Handler pointer-down dispatcher (both edit modes). The action is decided
  from the modifiers captured on the DOM event: a plain/shift gesture selects
  on click and drags with the `:smart` join behavior; mod or alt remove the
  handler on click and drag with the `:mirror` / `:independent` behavior;
  mod+alt does nothing on click and drags with the `:aligned` behavior."
  [index prefix shift? alt? mod?]
  (ptk/reify ::start-move-handler
    ptk/WatchEvent
    (watch [_ state _]
      (let [id                (st/get-path-id state)
            handler-id        [index prefix]
            content           (st/get-path state :content)
            selected-handlers (dm/get-in state
                                         [:workspace-local :edit-path id :selection :handlers]
                                         #{})
            selected?         (contains? selected-handlers handler-id)

            ;; The stored per-node handler type drives the plain drag so it does
            ;; not depend on fragile geometry: :independent never touches the
            ;; opposite (smart would still move it while the pair is collinear),
            ;; :aligned forces the angle match keeping the opposite's own length,
            ;; and :mirror forces the exact mirror (guarded to a still-joined pair
            ;; so a stale entry can't re-mirror a broken corner). No stored type
            ;; falls back to smart (the group-aware default).
            node-index        (helpers/handler-node-index index prefix)
            node-type         (dm/get-in state [:workspace-local :edit-path id :handler-types node-index])
            plain-mode        (case node-type
                                :mirror      (if (helpers/handlers-joined? content index prefix)
                                               :mirror
                                               :smart)
                                :aligned     :aligned
                                :independent :independent
                                :smart)]
        (cond
          (and mod? alt?)
          (streams/drag-stream
           (rx/of (set-drag-cursor "move-handles")
                  (drag-selected-handlers handler-id :aligned))
           (rx/empty))

          (or mod? alt?)
          (streams/drag-stream
           (rx/of (set-drag-cursor "move-handles")
                  (drag-selected-handlers handler-id (if mod? :mirror :independent)))
           (rx/of (tools/remove-handler index prefix)))

          :else
          (streams/drag-stream
           (rx/of
            (set-drag-cursor "move-move")
            (when-not selected?
              (selection/select-handler index prefix shift?))
            (drag-selected-handlers handler-id plain-mode))
           (rx/of (selection/select-handler index prefix shift?))))))))

(defn drag-selected-handlers
  [[index prefix :as primary] mode]
  (ptk/reify ::drag-selected-handlers
    ptk/WatchEvent
    (watch [_ state stream]
      (let [id              (st/get-path-id state)
            content         (st/get-path state :content)
            points          (path/get-points content)
            start-modifiers (dm/get-in state
                                       [:workspace-local :edit-path id :content-modifiers]
                                       {})
            start-delta     (handler-modifier-delta start-modifiers index prefix)
            point           (path/handler->node content index prefix)
            handler         (-> (path/get-handler-point content index prefix)
                                (gpt/add start-delta))
            [op-idx op-prefix] (path/opposite-index content index prefix)
            opposite        (when op-idx
                              (-> (path/get-handler-point content op-idx op-prefix)
                                  (gpt/add (handler-modifier-delta start-modifiers
                                                                   op-idx
                                                                   op-prefix))))
            stopper         (rx/merge
                             (mse/drag-stopper stream)
                             (->> stream
                                  (rx/filter streams/finish-edition?)))]
        (rx/concat
         (->> (streams/move-handler-stream handler point handler opposite points)
              (rx/map
               (fn [{:keys [x y shift?]}]
                 (let [position (cond-> (gpt/point x y)
                                  shift? (path.helpers/position-fixed-angle point))
                       delta    (gpt/subtract position handler)]
                   (modify-selected-handlers id
                                             primary
                                             start-modifiers
                                             (:x delta)
                                             (:y delta)
                                             mode))))
              (rx/take-until stopper))
         (rx/of (apply-content-modifiers)))))))

(declare stop-path-edit)

(defn- resolve-edit-fills
  "Resolves the fills the editing copy must render so it matches the committed
  view. A shape with its own fills keeps them; an empty-fill shape inherits the
  nearest ancestor group's resolved fills, mirroring the renderer's nested-fill
  cascade, so an SVG-group child that renders black (the SVG default) stays
  black while edited instead of turning transparent. Frames reset the cascade."
  [shape objects]
  (let [own (svg-fills/resolve-shape-fills shape)]
    (if (seq own)
      own
      (loop [parent-id (:parent-id shape)]
        (let [parent (get objects parent-id)]
          (cond
            (nil? parent)             []
            (cfh/group-shape? parent) (svg-fills/resolve-shape-fills parent)
            (cfh/frame-shape? parent) []
            :else                     (recur (:parent-id parent))))))))

(defn start-path-edit
  [id]
  (ptk/reify ::start-path-edit
    ptk/UpdateEvent
    (update [_ state]
      (let [objects   (dsh/lookup-page-objects state)
            shape     (get objects id)
            shape     (-> shape
                          (path/convert-to-path objects)
                          (update :content path/close-subpaths)
                          (path/update-geometry))
            shape     (assoc shape :fills (resolve-edit-fills shape objects))]

        (-> state
            (assoc-in [:workspace-drawing :object] shape)
            (update-in [:workspace-local :edit-path id]
                       (fn [state]
                         (let [state (if state
                                       (if (= :move (:edit-mode state))
                                         (assoc state :edit-mode :draw)
                                         state)
                                       {:edit-mode :move
                                        :selection helpers/empty-selection
                                        :hover helpers/empty-selection
                                        :handler-types {}
                                        :snap-toggled true})]
                           (assoc state :old-content (:content shape))))))))

    ptk/WatchEvent
    (watch [_ _ stream]
      (let [stopper (rx/filter (ptk/type? ::start-path-edit) stream)]
        (rx/concat
         (rx/of (undo/start-path-undo))
         ;; `clear-edition-mode` is the single canonical "edition ended" signal:
         ;; `:interrupt` (Esc, etc.) always yields it through the
         ;; `start-edition-mode` watch, and the draw loop emits it directly on
         ;; exit. Keying only on it avoids reacting to two racing stop signals.
         (->> stream
              (rx/filter (ptk/type? ::dwe/clear-edition-mode))
              (rx/take 1)
              (rx/map #(stop-path-edit id))
              (rx/take-until stopper)))))))

(defn stop-path-edit
  [id]
  (ptk/reify ::stop-path-edit
    ptk/WatchEvent
    (watch [_ _ _]
      (rx/of
       (changes/finalize-path-content id)
       (fn [state]
         (-> state
             (update-in [:workspace-local :edit-path] dissoc id)
             (update :workspace-drawing dissoc :object :lock)))
       (ptk/data-event :layout/update {:ids [id]})))))

(defn- split-segments
  [_id {:keys [from-p to-p t]}]
  (ptk/reify ::split-segments
    ptk/UpdateEvent
    (update [_ state]
      (let [content (st/get-path state :content)]
        (-> state
            (st/set-content (-> content
                                (path/split-segments #{from-p to-p} t)
                                (path/content)))
            (update-in (st/get-path-location state) path/update-geometry))))))

(defn create-node-at-position
  [params]
  (ptk/reify ::create-node-at-position
    ptk/WatchEvent
    (watch [_ state _]
      (let [id (st/get-path-id state)]
        (rx/of (split-segments id params))))))
