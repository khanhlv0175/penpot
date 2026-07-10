;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.logic.path-edition-test
  (:require
   [app.common.geom.point :as gpt]
   [app.common.test-helpers.compositions :as ctho]
   [app.common.test-helpers.files :as cthf]
   [app.common.test-helpers.shapes :as cths]
   [app.common.types.path :as path]
   [app.main.data.workspace.drawing.common :as dwdc]
   [app.main.data.workspace.edition :as dwe]
   [app.main.data.workspace.path.common :as path.common]
   [app.main.data.workspace.path.drawing :as path.drawing]
   [app.main.data.workspace.path.edition :as path.edition]
   [app.main.data.workspace.path.shortcuts :as path.shortcuts]
   [app.main.data.workspace.path.state :as path.state]
   [app.main.data.workspace.path.streams :as path.streams]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.state :as ths]
   [potok.v2.core :as ptk]))

(defn- setup-rect-file
  []
  (ctho/add-rect (cthf/sample-file :file1)
                 :rect1
                 :x 10
                 :y 20
                 :width 100
                 :height 80))

(defn- start-path-edition-events
  [id]
  [(dwe/start-edition-mode id)
   (path.edition/start-path-edit id)])

(defn- move-drawing-content
  [delta]
  (fn [state]
    (path.state/set-content
     state
     (path/move-content (path.state/get-path state :content) delta))))

(t/deftest clear-edition-mode-finishes-path-streams
  (t/is (path.streams/finish-edition? (dwe/clear-edition-mode)))
  (t/is (not (path.streams/finish-edition? :interrupt))))

(t/deftest clear-edition-mode-finishes-active-path-before-finalizing
  (let [id         (random-uuid)
        event      (dwe/clear-edition-mode)
        state      {:workspace-local {:edition id
                                      :edit-path {id {:edit-mode :move}}}
                    :workspace-drawing {:object {:id id}}}
        state'     (ptk/update event state)
        emissions  (atom [])]
    (->> (ptk/watch event state' nil)
         (rx/subs! #(swap! emissions conj %)))
    (t/is (nil? (get-in state' [:workspace-local :edition])))
    (t/is (some? (get-in state' [:workspace-local :edit-path id])))
    (t/is (= [::path.common/finish-path]
             (mapv ptk/type @emissions)))))

(t/deftest clear-non-path-edition-does-not-emit-finish-path
  (let [id        (random-uuid)
        event     (dwe/clear-edition-mode)
        state     {:workspace-local {:edition id}}
        state'    (ptk/update event state)
        emissions (atom [])]
    (->> (ptk/watch event state' nil)
         (rx/subs! #(swap! emissions conj %)))
    (t/is (nil? (get-in state' [:workspace-local :edition])))
    (t/is (empty? @emissions))))

(t/deftest restarting-draw-mode-finishes-pending-subpath
  (let [file        (setup-rect-file)
        shape       (-> (cths/get-shape file :rect1)
                        (path/convert-to-path))
        id          (:id shape)
        last-point  (last (path/get-points (:content shape)))
        state       {:workspace-local
                     {:edition id
                      :edit-path
                      {id {:edit-mode :draw
                           :last-point last-point
                           :preview {:command :line-to
                                     :params {:x 150 :y 150}}
                           :old-content (:content shape)}}}
                     :workspace-drawing {:object shape}}
        stream      (rx/subject)
        emissions   (atom [])]
    (->> (ptk/watch (path.drawing/start-draw-mode*) state stream)
         (rx/take 4)
         (rx/subs! #(swap! emissions conj %)))
    (rx/push! stream (ptk/data-event ::path.drawing/end-edition
                                     {:restart? true}))
    (t/is (= [::path.drawing/start-edition
              ::path.common/finish-path
              ::path.drawing/check-changed-content
              ::path.drawing/start-draw-mode*]
             (mapv ptk/type @emissions)))
    (let [state'  (ptk/update (second @emissions) state)
          state'' (ptk/update (path.drawing/preview-next-point
                               {:x 200 :y 200})
                              state')]
      (t/is (nil? (get-in state' [:workspace-local :edit-path id :last-point])))
      (t/is (nil? (get-in state' [:workspace-local :edit-path id :preview])))
      (t/is (= :move-to
               (get-in state'' [:workspace-local :edit-path id :preview :command]))))))

(t/deftest escape-does-not-restart-edited-path-draw-loop
  (let [id        (random-uuid)
        state     {:workspace-local
                   {:edition id
                    :edit-path {id {:edit-mode :draw}}}}
        stream    (rx/subject)
        emissions (atom [])]
    (t/is (path.drawing/restart-draw-loop? (path.common/finish-path)))
    (->> (ptk/watch (path.drawing/start-draw-mode*) state stream)
         (rx/subs! #(swap! emissions conj %)))
    (rx/push! stream (ptk/data-event ::path.drawing/end-edition
                                     {:restart? false}))
    (t/is (= [::path.drawing/start-edition]
             (mapv ptk/type @emissions)))))

(defn- drawing-path-state
  []
  (let [file  (setup-rect-file)
        shape (-> (cths/get-shape file :rect1)
                  (path/convert-to-path))]
    {:workspace-drawing {:object shape}}))

(defn- run-handle-drawing-end
  "Drives the handle-drawing continuation with an ::end-edition carrying
  the given :restart? flag and calls back with the drawing-end emissions."
  [restart? callback]
  (let [state     (drawing-path-state)
        stream    (rx/subject)
        emissions (atom [])]
    (->> (ptk/watch (path.drawing/handle-drawing) state stream)
         (rx/subs! #(swap! emissions conj %)))
    (rx/push! stream (ptk/data-event ::path.drawing/end-edition
                                     {:restart? restart?}))
    ;; The drawing-end event is emitted through an async boundary
    (js/setTimeout
     (fn []
       (let [end-event     (last @emissions)
             end-emissions (atom [])]
         (->> (ptk/watch end-event state stream)
              (rx/subs! #(swap! end-emissions conj %)))
         (callback @end-emissions))))))

(t/deftest escape-ending-new-path-draw-does-not-reenter-edition
  (t/async
    done
    (run-handle-drawing-end
     false
     (fn [emissions]
       (t/is (= [::path.drawing/setup-frame
                 ::dwdc/handle-finish-drawing
                 ::dwe/clear-edition-mode]
                (mapv ptk/type emissions)))
       (done)))))

(t/deftest finishing-new-path-draw-reenters-edition
  (t/async
    done
    (run-handle-drawing-end
     true
     (fn [emissions]
       (t/is (= [::path.common/finish-path
                 ::path.drawing/setup-frame
                 ::dwdc/handle-finish-drawing
                 ::path.drawing/start-created-path-edition]
                (mapv ptk/type emissions)))
       (done)))))

(t/deftest escape-with-pending-segment-cancels-it-and-keeps-drawing
  (let [id        (random-uuid)
        state     {:workspace-local
                   {:edition id
                    :edit-path {id {:edit-mode :draw
                                    :last-point (gpt/point 10 10)
                                    :preview {:command :line-to
                                              :params {:x 20 :y 20}}}}}}
        emissions (atom [])]
    (->> (ptk/watch (path.shortcuts/esc-pressed) state nil)
         (rx/subs! #(swap! emissions conj %)))
    (t/is (= [::path.common/cancel-pending-segment]
             (mapv ptk/type @emissions)))
    (let [state' (ptk/update (first @emissions) state)]
      (t/is (nil? (get-in state' [:workspace-local :edit-path id :last-point])))
      (t/is (nil? (get-in state' [:workspace-local :edit-path id :preview])))
      (t/is (= :draw (get-in state' [:workspace-local :edit-path id :edit-mode]))))))

(t/deftest escape-while-creating-path-finishes-it-into-edition
  (let [id        (random-uuid)
        state     {:workspace-local
                   {:edit-path {id {:edit-mode :draw
                                    :last-point (gpt/point 10 10)}}}
                   :workspace-drawing {:object {:id id :type :path}}}
        emissions (atom [])]
    (->> (ptk/watch (path.shortcuts/esc-pressed) state nil)
         (rx/subs! #(swap! emissions conj %)))
    ;; finish-path both ends the initial draw loop as a restart (creating
    ;; the shape and entering its edition) and clears the pending segment
    (t/is (= [::path.common/finish-path]
             (mapv ptk/type @emissions)))))

(t/deftest escape-without-pending-segment-interrupts-edition
  (let [id        (random-uuid)
        state     {:workspace-local
                   {:edition id
                    :edit-path {id {:edit-mode :draw}}}}
        emissions (atom [])]
    (->> (ptk/watch (path.shortcuts/esc-pressed) state nil)
         (rx/subs! #(swap! emissions conj %)))
    (t/is (= [:interrupt] @emissions))))

(t/deftest editing-path-only-updates-drawing-copy
  (t/async
    done
    (let [file          (setup-rect-file)
          original-rect (cths/get-shape file :rect1)
          id            (:id original-rect)
          delta         (gpt/point 10 5)
          store         (ths/setup-store file)
          events        (conj (start-path-edition-events id)
                              (move-drawing-content delta))]
      (ths/run-store
       store done events
       (fn [new-state]
         (let [file'        (ths/get-file-from-state new-state)
               stored-shape (cths/get-shape file' :rect1)
               drawing-copy (get-in new-state [:workspace-drawing :object])]
           (t/is (= original-rect stored-shape))
           (t/is (= :path (:type drawing-copy)))
           (t/is (= (path/move-content
                     (:content (path/convert-to-path original-rect))
                     delta)
                    (:content drawing-copy)))))))))

(t/deftest unchanged-path-edition-preserves-simple-shape
  (t/async
    done
    (let [file          (setup-rect-file)
          original-rect (cths/get-shape file :rect1)
          id            (:id original-rect)
          store         (ths/setup-store file)
          events        (conj (start-path-edition-events id) :interrupt)]
      (ths/run-store
       store done events
       (fn [new-state]
         (let [file'        (ths/get-file-from-state new-state)
               stored-shape (cths/get-shape file' :rect1)]
           (t/is (= original-rect stored-shape))
           (t/is (= :rect (:type stored-shape)))
           (t/is (nil? (get-in new-state [:workspace-drawing :object])))))))))

(t/deftest changed-path-edition-is-persisted-when-finalized
  (t/async
    done
    (let [file            (setup-rect-file)
          original-rect   (cths/get-shape file :rect1)
          id              (:id original-rect)
          delta           (gpt/point 10 5)
          original-path   (path/convert-to-path original-rect)
          changed-content (path/move-content (:content original-path) delta)
          expected-shape  (-> original-path
                              (assoc :content changed-content)
                              (path/update-geometry))
          store           (ths/setup-store file)
          events          (into (start-path-edition-events id)
                                [(move-drawing-content delta)
                                 :interrupt])]
      (ths/run-store
       store done events
       (fn [new-state]
         (let [file'        (ths/get-file-from-state new-state)
               stored-shape (cths/get-shape file' :rect1)]
           (t/is (= expected-shape stored-shape))
           (t/is (= :path (:type stored-shape)))
           (t/is (nil? (get-in new-state [:workspace-drawing :object])))))))))

(t/deftest created-path-edition-cleans-drawing-state-on-exit
  (t/async
    done
    (let [file          (setup-rect-file)
          original-rect (cths/get-shape file :rect1)
          id            (:id original-rect)
          store         (ths/setup-store file)
          events        [(path.drawing/start-created-path-edition id)
                         :interrupt]]
      (ths/run-store
       store done events
       (fn [new-state]
         (let [file'        (ths/get-file-from-state new-state)
               stored-shape (cths/get-shape file' :rect1)]
           (t/is (= original-rect stored-shape))
           (t/is (nil? (get-in new-state [:workspace-local :edition])))
           (t/is (nil? (get-in new-state [:workspace-local :edit-path id])))
           (t/is (nil? (get-in new-state [:workspace-drawing :object])))))))))
