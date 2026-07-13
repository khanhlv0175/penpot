;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.path.edition
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
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
   [app.main.data.workspace.path.undo :as undo]
   [app.main.streams :as ms]
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
  [id primary start-modifiers dx dy match-opposite?]
  (ptk/reify ::modify-selected-handlers
    ptk/UpdateEvent
    (update [_ state]
      (let [content           (st/get-path state :content)
            selected-handlers (dm/get-in state
                                         [:workspace-local :edit-path id :selection :handlers]
                                         #{})
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
                     opposite-selected? (contains? selected-handlers opposite-id)
                     match-opposite? (and match-opposite? (not opposite-selected?))
                     handler-modifiers
                     (cond-> (helpers/move-handler-modifiers content
                                                             index
                                                             prefix
                                                             false
                                                             match-opposite?
                                                             (:x delta)
                                                             (:y delta))
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
  [points move-modifier]
  (ptk/reify ::set-modifiers
    ptk/UpdateEvent
    (update [_ state]
      (let [id (st/get-path-id state)
            content (st/get-path state :content)
            modifiers-reducer (partial modify-content-point content move-modifier)
            content-modifiers (dm/get-in state [:workspace-local :edit-path id :content-modifiers] {})
            content-modifiers (->> points
                                   (reduce modifiers-reducer content-modifiers))]

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

(declare drag-selected-points)

(defn start-move-path-point
  [index shift?]
  (ptk/reify ::start-move-path-point
    ptk/WatchEvent
    (watch [_ state _]
      (let [id (dm/get-in state [:workspace-local :edition])
            selected-nodes (dm/get-in state [:workspace-local :edit-path id :selection :nodes] #{})
            selected? (contains? selected-nodes index)]
        (streams/drag-stream
         (rx/of
          (when-not selected? (selection/select-node index shift?))
          (drag-selected-points @ms/mouse-position))
         (rx/of (selection/select-node index shift?)))))))

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
         (rx/of (apply-content-modifiers)))))))

(declare drag-selected-segments)
(declare start-segment-drag)
(declare bend-selected-segment)
(declare create-node-at-position)

(defn start-move-path-segment
  [index shift?]
  (ptk/reify ::start-move-path-segment
    ptk/WatchEvent
    (watch [_ state _]
      (let [id                (dm/get-in state [:workspace-local :edition])
            zoom              (dm/get-in state [:workspace-local :zoom] 1)
            add-nodes?        (dm/get-in state [:workspace-local :edit-path id :add-nodes])
            content           (st/get-path state :content)
            selected-segments (dm/get-in state
                                         [:workspace-local :edit-path id :selection :segments]
                                         #{})
            selected?         (contains? selected-segments index)

            ;; A plain click selects the segment, except near its exact middle
            ;; where it inserts a node instead (feature 16); with the add-nodes
            ;; toggle a click anywhere on the path inserts. A drag still
            ;; bends/translates the segment (handled by the drag branch).
            insert-point      (helpers/insertion-point
                               content @ms/mouse-position
                               (/ helpers/segment-insert-threshold zoom)
                               add-nodes?)

            click-event       (if (some? insert-point)
                                (create-node-at-position (meta insert-point))
                                (selection/select-segment index shift?))]
        (streams/drag-stream
         (rx/of
          (when-not selected? (selection/select-segment index shift?))
          (start-segment-drag index @ms/mouse-position))
         (rx/of click-event))))))

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

(defn- start-segment-drag
  "Dispatches a segment pointer-drag: with the platform modifier held, the
  grabbed segment bends through the pointer while its endpoint nodes stay
  fixed; otherwise the selected segments translate. Closing (`close-path`)
  segments carry no handles and always translate."
  [index start-position]
  (ptk/reify ::start-segment-drag
    ptk/WatchEvent
    (watch [_ state _]
      (let [content (st/get-path state :content)
            entry   (segment-entry content index)
            bend?   (and (deref ms/mouse-position-mod)
                         (some? entry)
                         (not= :close-path (:command (:segment entry))))]
        (if bend?
          (rx/of (bend-selected-segment index start-position))
          (rx/of (drag-selected-segments start-position)))))))

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
           (rx/of (apply-content-modifiers))))))))

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
            (let [content        (st/get-path state :content)
                  selected-nodes (dm/get-in state [:workspace-local :edit-path id :selection :nodes] #{})
                  points         (helpers/node-positions content selected-nodes)

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
                     (rx/map #(set-move-modifier points %)))

                ;; First event is not read by the stream so we need to send it again
                (rx/of (move-selected direction shift?)))

               (rx/of (apply-content-modifiers)
                      (finish-move-selected))))
            (rx/empty)))))))

(declare drag-selected-handlers)

(defn start-move-handler
  ([index prefix]
   (start-move-handler index prefix false))
  ([index prefix shift?]
   (ptk/reify ::start-move-handler
     ptk/WatchEvent
     (watch [_ state _]
       (let [id                (dm/get-in state [:workspace-local :edition])
             handler-id        [index prefix]
             selected-handlers (dm/get-in state
                                          [:workspace-local :edit-path id :selection :handlers]
                                          #{})
             selected?         (contains? selected-handlers handler-id)]
         (streams/drag-stream
          (rx/of
           (when-not selected?
             (selection/select-handler index prefix shift?))
           (drag-selected-handlers handler-id))
          (rx/of (selection/select-handler index prefix shift?))))))))

(defn drag-selected-handlers
  [[index prefix :as primary]]
  (ptk/reify ::drag-selected-handlers
    ptk/WatchEvent
    (watch [_ state stream]
      (let [id              (dm/get-in state [:workspace-local :edition])
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
               (fn [{:keys [x y alt? shift?]}]
                 (let [position (cond-> (gpt/point x y)
                                  shift? (path.helpers/position-fixed-angle point))
                       delta    (gpt/subtract position handler)]
                   (modify-selected-handlers id
                                             primary
                                             start-modifiers
                                             (:x delta)
                                             (:y delta)
                                             (not alt?)))))
              (rx/take-until stopper))
         (rx/of (apply-content-modifiers)))))))

(declare stop-path-edit)


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
                          (path/update-geometry))]

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
                                        :snap-toggled false
                                        :add-nodes false})]
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
