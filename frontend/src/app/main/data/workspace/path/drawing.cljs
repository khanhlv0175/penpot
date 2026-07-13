;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.path.drawing
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes.flex-layout :as gsl]
   [app.common.types.container :as ctn]
   [app.common.types.path :as path]
   [app.common.types.path.helpers :as path.helpers]
   [app.common.types.shape :as cts]
   [app.common.types.shape-tree :as ctst]
   [app.common.types.shape.layout :as ctl]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.drawing.common :as dwdc]
   [app.main.data.workspace.edition :as dwe]
   [app.main.data.workspace.pages :as-alias dwpg]
   [app.main.data.workspace.path.common :as common]
   [app.main.data.workspace.path.edition :as edition]
   [app.main.data.workspace.path.helpers :as helpers]
   [app.main.data.workspace.path.state :as st]
   [app.main.data.workspace.path.streams :as streams]
   [app.main.data.workspace.path.undo :as undo]
   [app.util.mouse :as mse]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

(declare close-path-drag-end)
(declare check-changed-content)
(declare change-edit-mode)

(defn start-created-path-edition
  [id]
  (ptk/reify ::start-created-path-edition
    ptk/WatchEvent
    (watch [_ _ _]
      (rx/of (dwe/start-edition-mode id)
             (edition/start-path-edit id)
             (change-edit-mode :draw)))))

;; --- Draw-loop stop / restart signals
;;
;; The draw loop (`start-edition`) runs until one of a small set of events
;; arrives. They fall into two kinds:
;;
;; - restart: end the loop and immediately re-enter continued edition on the
;;   same shape (an explicit finish via Enter / Esc-while-creating, or a
;;   double-click). `handle-drawing-end` threads this through as `:restart?`.
;; - exit: end the loop for good. `clear-edition-mode` is the canonical
;;   "edition ended" signal — `:interrupt` (Esc with nothing pending) funnels
;;   into it through `start-edition-mode`'s watch once an `:edition` exists, but
;;   during initial creation there is no `:edition` yet, so the bare `:interrupt`
;;   (and `finalize-page` on navigation) must also end the loop directly.
;;
;; `end-path-event?` is the union of the two.

(defn restart-draw-loop?
  "True for the events that end the draw loop and re-enter continued edition on
  the same shape."
  [event]
  (or (= (ptk/type event) ::common/finish-path)
      (and ^boolean (mse/mouse-event? event)
           ^boolean (mse/mouse-double-click-event? event))))

(defn- exit-draw-loop?
  "True for the events that end the draw loop for good (no re-entry)."
  [event]
  (let [type (ptk/type event)]
    (or (= type ::dwe/clear-edition-mode)   ;; canonical "edition ended"
        (= type ::dwpg/finalize-page)       ;; page navigation / teardown
        (dwe/interrupt? event))))           ;; Esc during initial creation

(defn- end-path-event?
  "Any reason the draw loop must end — a restart or an exit."
  [event]
  (or (restart-draw-loop? event)
      (exit-draw-loop? event)))

(defn preview-next-point
  [{:keys [x y shift?]}]
  (ptk/reify ::preview-next-point
    ptk/UpdateEvent
    (update [_ state]
      (let [id        (st/get-path-id state)
            edit-path (get-in state [:workspace-local :edit-path id])]
        ;; While a modifier drag is in progress (e.g. dragging a placed node's
        ;; handler while still drawing the path, feature 20) the next-point
        ;; preview must not follow the mouse.
        (if (seq (:content-modifiers edit-path))
          state
          (let [fix-angle? shift?
                {:keys [last-point prev-handler]} edit-path
                position   (cond-> (gpt/point x y)
                             fix-angle? (path.helpers/position-fixed-angle last-point))
                content    (st/get-path state :content)
                segment    (path/next-node content position last-point prev-handler)]
            (assoc-in state [:workspace-local :edit-path id :preview] segment)))))))

(defn add-node
  [{:keys [x y shift?]}]
  (ptk/reify ::add-node
    ptk/UpdateEvent
    (update [_ state]
      (let [id (st/get-path-id state)
            fix-angle? shift?
            {:keys [last-point prev-handler]} (get-in state [:workspace-local :edit-path id])
            position (cond-> (gpt/point x y)
                       fix-angle? (path.helpers/position-fixed-angle last-point))]
        (if-not (= last-point position)
          (-> state
              (assoc-in  [:workspace-local :edit-path id :last-point] position)
              (update-in [:workspace-local :edit-path id] dissoc :prev-handler)
              (update-in [:workspace-local :edit-path id] dissoc :preview)
              (update-in (st/get-path-location state) helpers/append-node position last-point prev-handler))
          state)))))

(defn drag-handler
  ([position]
   (drag-handler nil nil :c1 position))
  ([position index prefix {:keys [x y alt? shift?]}]
   (ptk/reify ::drag-handler
     ptk/UpdateEvent
     (update [_ state]
       (let [id (st/get-path-id state)
             content (st/get-path state :content)

             index (or index (count content))
             prefix (or prefix :c1)
             position (or position (path.helpers/segment->point (nth content (dec index))))

             old-handler (path/get-handler-point content index prefix)

             handler-position (cond-> (gpt/point x y)
                                shift? (path.helpers/position-fixed-angle position))

             {dx :x dy :y} (if (some? old-handler)
                             (gpt/add (gpt/to-vec old-handler position)
                                      (gpt/to-vec position handler-position))
                             (gpt/to-vec position handler-position))

             match-opposite? (not alt?)

             modifiers (helpers/move-handler-modifiers content index prefix match-opposite? match-opposite? dx dy)]
         (-> state
             (update-in [:workspace-local :edit-path id :content-modifiers] merge modifiers)
             (assoc-in [:workspace-local :edit-path id :drag-handler] handler-position)))))))

(defn finish-drag []
  (ptk/reify ::finish-drag
    ptk/UpdateEvent
    (update [_ state]
      (let [id (st/get-path-id state)

            modifiers (get-in state [:workspace-local :edit-path id :content-modifiers])
            content (-> (st/get-path state :content)
                        (path/apply-content-modifiers modifiers))

            handler (get-in state [:workspace-local :edit-path id :drag-handler])]
        (-> state
            (st/set-content content)
            (update-in [:workspace-local :edit-path id] dissoc :drag-handler)
            (update-in [:workspace-local :edit-path id] dissoc :content-modifiers)
            (assoc-in  [:workspace-local :edit-path id :prev-handler] handler)
            (update-in (st/get-path-location state) path/update-geometry))))

    ptk/WatchEvent
    (watch [_ state _]
      (let [id (st/get-path-id state)
            handler (get-in state [:workspace-local :edit-path id :prev-handler])]
        ;; Update the preview because can be outdated after the dragging
        (rx/of (preview-next-point handler)
               (undo/merge-head))))))

(defn close-path-drag-start
  [position]
  (ptk/reify ::close-path-drag-start
    ptk/WatchEvent
    (watch [_ state stream]
      (let [content  (st/get-path state :content)
            handlers (-> (path/get-handlers content)
                         (get position))

            [idx prefix] (when (= (count handlers) 1)
                           (first handlers))

            drag-events-stream
            (->> (streams/position-stream state)
                 (rx/map #(drag-handler position idx prefix %))
                 (rx/take-until
                  (rx/merge
                   (mse/drag-stopper stream)
                   (rx/filter end-path-event? stream))))]

        (rx/concat
         (rx/of (add-node position))
         (streams/drag-stream
          (rx/concat
           drag-events-stream
           (rx/of (finish-drag))
           (rx/of (close-path-drag-end))))
         (rx/of (common/finish-path)))))))

(defn close-path-drag-end []
  (ptk/reify ::close-path-drag-end
    ptk/UpdateEvent
    (update [_ state]
      (let [id (st/get-path-id state)]
        (update-in state [:workspace-local :edit-path id] dissoc :prev-handler)))))

(defn start-path-from-point [position]
  (ptk/reify ::start-path-from-point
    ptk/WatchEvent
    (watch [_ state stream]
      (let [stopper (rx/merge
                     (mse/drag-stopper stream)
                     (rx/filter end-path-event? stream))

            drag-events (->> (streams/position-stream state)
                             (rx/map #(drag-handler %))
                             (rx/take-until stopper))]
        (rx/concat
         (rx/of (add-node position))
         (streams/drag-stream
          (rx/concat
           drag-events
           (rx/of (finish-drag)))))))))

(defn make-drag-stream
  [state stream down-event]

  (assert (gpt/point? down-event)
          "should be a point instance")

  (let [stopper (rx/merge
                 (mse/drag-stopper stream)
                 (rx/filter end-path-event? stream))

        drag-events
        (->> (streams/position-stream state)
             (rx/map #(drag-handler %))
             (rx/take-until stopper))]
    (rx/concat
     (rx/of (add-node down-event))
     (streams/drag-stream
      (rx/concat
       drag-events
       (rx/of (finish-drag)))))))

(defn- start-edition
  [_id]
  (ptk/reify ::start-edition
    ptk/UpdateEvent
    (update [_ state]
      (let [id (st/get-path-id state)]
        (assoc-in state [:workspace-local :edit-path id :edit-mode] :draw)))

    ptk/WatchEvent
    (watch [_ state stream]
      (let [mouse-down
            (->> stream
                 (rx/filter mse/mouse-event?)
                 (rx/filter mse/mouse-down-event?))

            end-stream
            (->> stream
                 (rx/filter end-path-event?)
                 (rx/share))

            stop-event
            (volatile! nil)

            stoper-stream
            (->> stream
                 (rx/filter (ptk/type? ::start-edition))
                 (rx/merge end-stream)
                 (rx/tap #(vreset! stop-event %))
                 (rx/share))

            ;; Mouse move preview
            mousemove-events
            (->> (streams/position-stream state)
                 (rx/map #(preview-next-point %)))

            ;; A viewport mouse-down starts an add-node + optional handler drag.
            ;; Node clicks stop propagation and never reach here (the viewport
            ;; only emits :down for its own controls), so closing a path is
            ;; driven entirely by close-path-drag-start's own watch.
            mousedown-events
            (->> mouse-down
                 ;; We just ignore the mouse event and stream down the
                 ;; last position event
                 (rx/with-latest-from #(-> %2) (streams/position-stream state))
                 (rx/switch-map
                  #(make-drag-stream state stream %))
                 (rx/take-until end-stream))]

        (->> (rx/concat
              (rx/of (undo/start-path-undo))
              (->> (rx/merge mousemove-events
                             mousedown-events)
                   (rx/take-until stoper-stream))
              (->> (rx/of nil)
                   (rx/map (fn [_]
                             (ptk/data-event
                              ::end-edition
                              {:restart? (restart-draw-loop? @stop-event)}))))))))))

(defn setup-frame
  []
  (ptk/reify ::setup-frame
    ptk/UpdateEvent
    (update [_ state]
      (let [objects      (dsh/lookup-page-objects state)
            content      (get-in state [:workspace-drawing :object :content] [])

            ;; FIXME: use native operation for retrieve the first position
            position     (-> (nth content 0)
                             (get :params)
                             (gpt/point))

            frame-id     (->> (ctst/top-nested-frame objects position)
                              (ctn/get-first-valid-parent objects) ;; We don't want to change the structure of component copies
                              :id)
            flex-layout? (ctl/flex-layout? objects frame-id)
            drop-index   (when flex-layout? (gsl/get-drop-index frame-id objects position))]

        (update-in state [:workspace-drawing :object]
                   (fn [object]
                     (-> object
                         (assoc :frame-id frame-id)
                         (assoc :parent-id frame-id)
                         (cond-> (some? drop-index)
                           (with-meta {:index drop-index})))))))))

(defn- close-drawn-loops
  "Marks coincident-endpoint loops of the drawn shape with an explicit
  close-path right before the shape is created. A loop closed with a plain
  line-to renders two stroke caps at the seam instead of a join. This runs
  after `finish-path` (which strips a dangling subpath start) and before
  the shape is committed, so the created shape is already joined at the
  seam even when it continues straight into edition without content
  changes."
  []
  (ptk/reify ::close-drawn-loops
    ptk/UpdateEvent
    (update [_ state]
      (d/update-in-when state [:workspace-drawing :object]
                        (fn [object]
                          (-> object
                              (update :content path/close-loops)
                              (path/update-geometry)))))))

(defn- handle-drawing-end
  [shape-id restart?]
  (ptk/reify ::handle-drawing-end
    ptk/UpdateEvent
    (update [_ state]
      (let [content (some-> (dm/get-in state [:workspace-drawing :object :content])
                            (path/check-content))]
        (if (> (count content) 1)
          (assoc-in state [:workspace-drawing :object :initialized?] true)
          state)))

    ptk/WatchEvent
    (watch [_ state _]
      (when-let [content (dm/get-in state [:workspace-drawing :object :content])]
        (cond
          (and (> (count content) 1) restart?)
          (rx/of (common/finish-path)
                 (close-drawn-loops)
                 (setup-frame)
                 (dwdc/handle-finish-drawing)
                 (start-created-path-edition shape-id))

          (> (count content) 1)
          (rx/of (close-drawn-loops)
                 (setup-frame)
                 (dwdc/handle-finish-drawing)
                 (dwe/clear-edition-mode))

          :else
          (rx/of (dwdc/handle-finish-drawing)
                 (dwe/clear-edition-mode)))))))

(defn handle-drawing
  "Hanndle the start of drawing new path shape"
  []
  (ptk/reify ::handle-new-shape
    ptk/UpdateEvent
    (update [_ state]
      (let [shape (cts/setup-shape {:type :path})]
        (update state :workspace-drawing assoc :object shape)))

    ptk/WatchEvent
    (watch [_ state stream]
      (let [shape-id (dm/get-in state [:workspace-drawing :object :id])]
        (rx/concat
         (rx/of (start-edition shape-id))
         (->> stream
              (rx/filter (ptk/type? ::end-edition))
              (rx/take 1)
              ;; Load-bearing boundary: the draw loop ends synchronously inside
              ;; the cascade of its stop event (e.g. Esc's `:interrupt`).
              ;; Deferring `handle-drawing-end` to the next microtask (`:async`)
              ;; lets that cascade fully settle first, so a non-restart exit
              ;; tears the shape down instead of re-entering a live draw loop off
              ;; stale `:last-point`/preview state (bug 11, second pass).
              (rx/observe-on :async)
              (rx/map (fn [event]
                        (handle-drawing-end shape-id (:restart? (deref event)))))))))))

(declare start-draw-mode*)

(defn start-draw-mode
  []
  (ptk/reify ::start-draw-mode
    ptk/UpdateEvent
    (update [_ state]
      (let [id      (dm/get-in state [:workspace-local :edition])
            objects (dsh/lookup-page-objects state)
            shape   (get objects id)
            drawing (dm/get-in state [:workspace-drawing :object])
            drawing (or drawing
                        (some-> shape
                                (path/convert-to-path objects)
                                (update :content path/close-subpaths)
                                (path/update-geometry)))]
        (cond-> state
          drawing
          (assoc-in [:workspace-drawing :object] drawing)

          (and drawing
               (nil? (dm/get-in state [:workspace-local :edit-path id :old-content])))
          (assoc-in [:workspace-local :edit-path id :old-content] (:content drawing)))))

    ptk/WatchEvent
    (watch [_ _ _]
      (rx/of (start-draw-mode*)))))

(defn start-draw-mode*
  []
  (ptk/reify ::start-draw-mode*
    ptk/WatchEvent
    (watch [_ state stream]
      (let [local (get state :workspace-local)
            id    (get local :edition)
            mode  (dm/get-in local [:edit-path id :edit-mode])]

        (if (= :draw mode)
          (rx/concat
           (rx/of (start-edition id))
           (->> stream
                (rx/filter (ptk/type? ::end-edition))
                (rx/take 1)
                (rx/mapcat (fn [event]
                             (if (:restart? (deref event))
                               (rx/of (common/finish-path)
                                      (check-changed-content)
                                      (start-draw-mode*))
                               (rx/empty))))))
          (rx/empty))))))

(defn change-edit-mode
  [mode]
  (ptk/reify ::change-edit-mode
    ptk/UpdateEvent
    (update [_ state]
      (if-let [id (dm/get-in state [:workspace-local :edition])]
        (d/update-in-when state [:workspace-local :edit-path id] assoc :edit-mode mode)
        state))

    ptk/WatchEvent
    (watch [_ state _]
      (when-let [id (dm/get-in state [:workspace-local :edition])]
        (let [mode (dm/get-in state [:workspace-local :edit-path id :edit-mode])]
          (case mode
            :move (rx/of (common/finish-path))
            :draw (rx/of (start-draw-mode))
            (rx/empty)))))))

(defn reset-last-handler
  []
  (ptk/reify ::reset-last-handler
    ptk/UpdateEvent
    (update [_ state]
      (let [id (st/get-path-id state)]
        (assoc-in state [:workspace-local :edit-path id :prev-handler] nil)))))

(defn check-changed-content
  []
  (ptk/reify ::check-changed-content
    ptk/WatchEvent
    (watch [_ state _]
      (let [id (st/get-path-id state)
            content (st/get-path state :content)
            old-content (get-in state [:workspace-local :edit-path id :old-content])
            mode (get-in state [:workspace-local :edit-path id :edit-mode])
            empty-content? (empty? content)]

        (cond
          (and (not= content old-content) (not empty-content?))
          (rx/empty)

          ;; Nothing new was drawn: exit the editor. Emit the scoped
          ;; clear-edition-mode (the draw loop and path-edit teardown both key
          ;; on it) instead of a bare global :interrupt that every unrelated
          ;; interrupt subscriber would also react to.
          (= mode :draw)
          (rx/of (dwe/clear-edition-mode))

          :else
          (rx/of
           (common/finish-path)
           (dwdc/clear-drawing)))))))
