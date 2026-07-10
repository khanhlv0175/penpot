;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.path.selection
  (:require
   [app.common.data.macros :as dm]
   [app.common.geom.rect :as grc]
   [app.main.data.workspace.path.helpers :as helpers]
   [app.main.data.workspace.path.state :as st]
   [app.main.streams :as ms]
   [app.util.mouse :as mse]
   [beicon.v2.core :as rx]
   [beicon.v2.operators :as rxo]
   [potok.v2.core :as ptk]))

(defn path-pointer-enter [index]
  (ptk/reify ::path-pointer-enter
    ptk/UpdateEvent
    (update [_ state]
      (let [id (st/get-path-id state)]
        (update-in state [:workspace-local :edit-path id :hover-nodes] (fnil conj #{}) index)))))

(defn path-pointer-leave [index]
  (ptk/reify ::path-pointer-leave
    ptk/UpdateEvent
    (update [_ state]
      (let [id (st/get-path-id state)]
        (update-in state [:workspace-local :edit-path id :hover-nodes] disj index)))))

(defn path-handler-enter [index prefix]
  (ptk/reify ::path-handler-enter
    ptk/UpdateEvent
    (update [_ state]
      (let [id (st/get-path-id state)]
        (update-in state [:workspace-local :edit-path id :hover-handlers] (fnil conj #{}) [index prefix])))))

(defn path-handler-leave [index prefix]
  (ptk/reify ::path-handler-leave
    ptk/UpdateEvent
    (update [_ state]
      (let [id (st/get-path-id state)]
        (update-in state [:workspace-local :edit-path id :hover-handlers] disj [index prefix])))))

(defn select-node-area
  [initial-set remove?]
  (ptk/reify ::select-node-area
    ptk/UpdateEvent
    (update [_ state]
      (let [selrect (dm/get-in state [:workspace-local :selrect])
            id      (dm/get-in state [:workspace-local :edition])
            content (st/get-path state :content)

            in-rect (if (some? selrect)
                      (helpers/nodes-in-rect content selrect)
                      #{})

            nodes   (if remove?
                      (apply disj initial-set in-rect)
                      (into initial-set in-rect))]

        (cond-> state
          (some? id)
          (assoc-in [:workspace-local :edit-path id :selected-nodes] nodes))))))

(defn select-node [index shift?]
  (ptk/reify ::select-node
    ptk/UpdateEvent
    (update [_ state]
      (let [id    (dm/get-in state [:workspace-local :edition])
            nodes (dm/get-in state [:workspace-local :edit-path id :selected-nodes] #{})
            nodes (cond
                    (and shift? (contains? nodes index))
                    (disj nodes index)

                    shift?
                    (conj nodes index)

                    :else
                    #{index})]
        (cond-> state
          (some? id)
          (assoc-in [:workspace-local :edit-path id :selected-nodes] nodes))))))

(defn deselect-all []
  (ptk/reify ::deselect-all
    ptk/UpdateEvent
    (update [_ state]
      (let [id (st/get-path-id state)]
        (-> state
            (assoc-in [:workspace-local :edit-path id :selected-nodes] #{})
            (assoc-in [:workspace-local :edit-path id :selected-segments] #{})
            (assoc-in [:workspace-local :edit-path id :selected-handlers] #{}))))))

(defn update-area-selection
  [rect]
  (ptk/reify ::update-area-selection
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-local :selrect] rect))))

(defn clear-area-selection
  []
  (ptk/reify ::clear-area-selection
    ptk/UpdateEvent
    (update [_ state]
      (update state :workspace-local dissoc :selrect))))

(defn handle-area-selection
  [append? remove?]
  (letfn [(valid-rect? [zoom {width :width height :height}]
            (or (> width (/ 10 zoom)) (> height (/ 10 zoom))))]

    (ptk/reify ::handle-area-selection
      ptk/WatchEvent
      (watch [_ state stream]
        (let [id      (dm/get-in state [:workspace-local :edition])
              zoom    (dm/get-in state [:workspace-local :zoom] 1)
              stopper (mse/drag-stopper stream)
              from-p  @ms/mouse-position

              initial-set
              (if (or append? remove?)
                (dm/get-in state [:workspace-local :edit-path id :selected-nodes] #{})
                #{})

              selrect-stream
              (->> ms/mouse-position
                   (rx/map #(grc/points->rect [from-p %]))
                   (rx/filter (partial valid-rect? zoom))
                   (rx/take-until stopper))]

          (rx/concat
           (if (or append? remove?)
             (rx/empty)
             (rx/of (deselect-all)))
           (rx/merge
            (->> selrect-stream
                 (rx/map update-area-selection))
            ;; Throttle node hit-testing to one animation frame so the
            ;; highlighted nodes track the selection box without recomputing
            ;; on every pointer move.
            (->> selrect-stream
                 (rx/buffer-time 16)
                 (rx/map last)
                 (rx/pipe (rxo/distinct-contiguous))
                 (rx/map #(select-node-area initial-set remove?))))
           (rx/of (clear-area-selection))))))))
