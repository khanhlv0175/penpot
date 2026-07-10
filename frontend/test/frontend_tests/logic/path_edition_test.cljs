;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.logic.path-edition-test
  (:require
   [app.common.geom.point :as gpt]
   [app.common.geom.rect :as grc]
   [app.common.test-helpers.compositions :as ctho]
   [app.common.test-helpers.files :as cthf]
   [app.common.test-helpers.shapes :as cths]
   [app.common.types.path :as path]
   [app.main.data.workspace.drawing.common :as dwdc]
   [app.main.data.workspace.edition :as dwe]
   [app.main.data.workspace.path.changes :as path.changes]
   [app.main.data.workspace.path.clipboard :as path.clipboard]
   [app.main.data.workspace.path.common :as path.common]
   [app.main.data.workspace.path.drawing :as path.drawing]
   [app.main.data.workspace.path.edition :as path.edition]
   [app.main.data.workspace.path.helpers :as path.helpers]
   [app.main.data.workspace.path.selection :as path.selection]
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

(defn- selectable-path-content
  []
  (path/content
   [{:command :move-to
     :params {:x 0 :y 0}}
    {:command :curve-to
     :params {:c1x 2 :c1y 0
              :c2x 8 :c2y 0
              :x 10 :y 0}}
    {:command :curve-to
     :params {:c1x 12 :c1y 0
              :c2x 18 :c2y 0
              :x 20 :y 0}}]))

(defn- selectable-path-state
  [id content selection]
  {:workspace-local {:edition id
                     :edit-path {id {:selection selection}}}
   :workspace-drawing {:object {:id id
                                :type :path
                                :content content}}})

(t/deftest plain-and-shift-selection-work-across-path-element-types
  (let [id      (random-uuid)
        content (selectable-path-content)
        state   (selectable-path-state
                 id content
                 {:nodes #{0}
                  :segments #{1}
                  :handlers #{[1 :c1]}})
        state'  (ptk/update (path.selection/select-handler 1 :c2 false) state)
        state'' (ptk/update (path.selection/select-segment 2 true) state')
        state''' (ptk/update (path.selection/select-handler 1 :c2 true) state'')
        state'''' (ptk/update (path.selection/select-handler 2 :c1 true) state''')]
    (t/is (= {:nodes #{}
              :segments #{}
              :handlers #{[1 :c2]}}
             (get-in state' [:workspace-local :edit-path id :selection])))
    (t/is (= {:nodes #{}
              :segments #{2}
              :handlers #{[1 :c2]}}
             (get-in state'' [:workspace-local :edit-path id :selection])))
    (t/is (= {:nodes #{}
              :segments #{2}
              :handlers #{[2 :c1]}}
             (get-in state'''' [:workspace-local :edit-path id :selection])))))

(t/deftest path-area-selection-prioritizes-nodes-over-segments-over-handlers
  (let [id        (random-uuid)
        content   (selectable-path-content)
        selection path.helpers/empty-selection
        state     (selectable-path-state id content selection)
        ;; Contains segment 1 and handler [1 :c1] but no node
        rect      (grc/make-rect 1 -2 3 4)
        state'    (ptk/update (path.selection/select-path-area
                               rect selection false)
                              state)
        ;; Contains node 0, segment 1 and handler [1 :c1]
        node-rect (grc/make-rect -1 -1 4 2)
        state''   (ptk/update (path.selection/select-path-area
                               node-rect selection false)
                              state)]
    (t/is (= {:nodes #{}
              :segments #{1}
              :handlers #{}}
             (get-in state' [:workspace-local :edit-path id :selection])))
    (t/is (= {:nodes #{0}
              :segments #{}
              :handlers #{}}
             (get-in state'' [:workspace-local :edit-path id :selection])))))

(t/deftest path-area-selection-picks-handlers-only-when-nothing-else-is-inside
  (let [id        (random-uuid)
        ;; Curve bulging up to y 7.5 with both handlers on y 10, away
        ;; from the curve itself
        content   (path/content
                   [{:command :move-to
                     :params {:x 0 :y 0}}
                    {:command :curve-to
                     :params {:c1x 0 :c1y 10
                              :c2x 10 :c2y 10
                              :x 10 :y 0}}])
        selection path.helpers/empty-selection
        state     (selectable-path-state id content selection)
        ;; Contains only the [1 :c1] handler control point
        handler-rect (grc/make-rect -1 9 2 2)
        state'    (ptk/update (path.selection/select-path-area
                               handler-rect selection false)
                              state)
        ;; Contains both handlers and the top of the curve
        mixed-rect (grc/make-rect -1 5 12 7)
        state''   (ptk/update (path.selection/select-path-area
                               mixed-rect selection false)
                              state)]
    (t/is (= {:nodes #{}
              :segments #{}
              :handlers #{[1 :c1]}}
             (get-in state' [:workspace-local :edit-path id :selection])))
    (t/is (= {:nodes #{}
              :segments #{1}
              :handlers #{}}
             (get-in state'' [:workspace-local :edit-path id :selection])))))

(t/deftest path-area-selection-ignores-empty-buffer-emissions
  (let [id        (random-uuid)
        content   (selectable-path-content)
        selection path.helpers/empty-selection
        state     (selectable-path-state id content selection)]
    (t/is (= state
             (ptk/update (path.selection/select-path-area
                          nil selection false)
                         state)))))

(t/deftest selected-segments-resolve-to-unique-endpoint-nodes
  (let [content (selectable-path-content)]
    (t/is (= #{0 1}
             (path.helpers/segment-node-indices content #{1})))
    (t/is (= #{0 1 2}
             (path.helpers/segment-node-indices content #{1 2})))))

(t/deftest moving-selected-segments-translates-endpoints-and-handlers
  (let [id        (random-uuid)
        content   (selectable-path-content)
        selection {:nodes #{}
                   :segments #{1}
                   :handlers #{}}
        state     (selectable-path-state id content selection)
        event     (path.edition/move-selected-path-segment
                   (gpt/point 5 0)
                   (gpt/point 8 4))
        state'    (ptk/update event state)
        modifiers (get-in state' [:workspace-local :edit-path id :content-modifiers])
        content'  (path/apply-content-modifiers content modifiers)]
    (t/is (= (gpt/point 3 4) (path.helpers/node-position content' 0)))
    (t/is (= (gpt/point 13 4) (path.helpers/node-position content' 1)))
    (t/is (= (gpt/point 5 4) (path/get-handler-point content' 1 :c1)))
    (t/is (= (gpt/point 11 4) (path/get-handler-point content' 1 :c2)))
    (t/is (= (gpt/point 15 4) (path/get-handler-point content' 2 :c1)))))

(t/deftest moving-selected-opposite-handlers-translates-both
  (let [id        (random-uuid)
        content   (selectable-path-content)
        selection {:nodes #{}
                   :segments #{}
                   :handlers #{[1 :c2] [2 :c1]}}
        state     (selectable-path-state id content selection)
        event     (path.edition/modify-selected-handlers
                   id [1 :c2] {} 3 4 true)
        state'    (ptk/update event state)
        modifiers (get-in state' [:workspace-local :edit-path id :content-modifiers])
        content'  (path/apply-content-modifiers content modifiers)]
    (t/is (= {:c2x 3 :c2y 4} (get modifiers 1)))
    (t/is (= {:c1x 3 :c1y 4} (get modifiers 2)))
    (t/is (= (gpt/point 11 4) (path/get-handler-point content' 1 :c2)))
    (t/is (= (gpt/point 15 4) (path/get-handler-point content' 2 :c1)))))

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

(defn- with-dangling-subpath-start
  [content]
  (path/content (conj (vec content)
                      {:command :move-to
                       :params {:x 30 :y 40}})))

(t/deftest cancel-pending-segment-drops-dangling-subpath-start
  (let [id      (random-uuid)
        content (selectable-path-content)
        state   {:workspace-local
                 {:edition id
                  :edit-path {id {:edit-mode :draw
                                  :last-point (gpt/point 30 40)
                                  :preview {:command :line-to
                                            :params {:x 50 :y 50}}}}}
                 :workspace-drawing
                 {:object {:id id
                           :type :path
                           :content (with-dangling-subpath-start content)}}}
        state'  (ptk/update (path.common/cancel-pending-segment) state)]
    (t/is (= (vec content)
             (vec (get-in state' [:workspace-drawing :object :content]))))
    (t/is (nil? (get-in state' [:workspace-local :edit-path id :last-point])))
    (t/is (nil? (get-in state' [:workspace-local :edit-path id :preview])))))

(t/deftest finish-path-drops-dangling-subpath-start
  (let [id      (random-uuid)
        content (selectable-path-content)
        state   {:workspace-local
                 {:edition id
                  :edit-path {id {:edit-mode :draw
                                  :last-point (gpt/point 30 40)}}}
                 :workspace-drawing
                 {:object {:id id
                           :type :path
                           :content (with-dangling-subpath-start content)}}}
        state'  (ptk/update (path.common/finish-path) state)]
    (t/is (= (vec (path/close-subpaths content))
             (vec (get-in state' [:workspace-drawing :object :content]))))
    (t/is (nil? (get-in state' [:workspace-local :edit-path id :last-point])))))

(t/deftest finalize-ignores-dangling-subpath-start
  (let [file        (setup-rect-file)
        rect        (cths/get-shape file :rect1)
        id          (:id rect)
        path-shape  (path/convert-to-path rect)
        old-content (:content path-shape)
        state       {:current-file-id (:id file)
                     :current-page-id (cthf/current-page-id file)
                     :files {(:id file) file}
                     :workspace-local
                     {:edition id
                      :edit-path {id {:old-content old-content}}}
                     :workspace-drawing
                     {:object (assoc path-shape
                                     :content
                                     (with-dangling-subpath-start old-content))}}
        emissions   (atom [])]
    (->> (ptk/watch (path.changes/finalize-path-content id) state nil)
         (rx/subs! #(swap! emissions conj %)))
    (t/is (empty? @emissions))))

(t/deftest remap-selection-follows-content-structure
  (let [content     (selectable-path-content)
        ;; Same command layout: index 1 turned into a line-to
        corner      (path/content
                     (assoc (vec content) 1 {:command :line-to
                                             :params {:x 10 :y 0}}))
        ;; Different layout: the middle node was removed
        shorter     (path/content
                     [{:command :move-to
                       :params {:x 0 :y 0}}
                      {:command :line-to
                       :params {:x 20 :y 0}}])
        selection   {:nodes #{1 2}
                     :segments #{2}
                     :handlers #{[1 :c1] [2 :c2]}}]
    (t/is (= {:nodes #{1 2}
              :segments #{2}
              :handlers #{[2 :c2]}}
             (path.helpers/remap-selection selection content corner)))
    (t/is (= {:nodes #{1}
              :segments #{}
              :handlers #{}}
             (path.helpers/remap-selection selection content shorter)))))

(t/deftest group-handler-drag-ignores-stale-handler-identities
  (let [id        (random-uuid)
        content   (path/content
                   [{:command :move-to
                     :params {:x 0 :y 0}}
                    {:command :line-to
                     :params {:x 10 :y 0}}
                    {:command :curve-to
                     :params {:c1x 12 :c1y 0
                              :c2x 18 :c2y 0
                              :x 20 :y 0}}])
        selection {:nodes #{}
                   :segments #{}
                   ;; [1 :c1] points to a line-to and [9 :c2] is out of range
                   :handlers #{[1 :c1] [2 :c1] [9 :c2]}}
        state     (selectable-path-state id content selection)
        event     (path.edition/modify-selected-handlers
                   id [2 :c1] {} 3 4 true)
        state'    (ptk/update event state)
        modifiers (get-in state' [:workspace-local :edit-path id :content-modifiers])]
    (t/is (= {:c1x 3 :c1y 4} (get modifiers 2)))
    (t/is (nil? (get modifiers 1)))
    (t/is (nil? (get modifiers 9)))))

(t/deftest pasting-path-content-splices-and-selects-new-nodes
  (let [id       (random-uuid)
        content  (selectable-path-content)
        state    (selectable-path-state id content
                                        {:nodes #{1}
                                         :segments #{}
                                         :handlers #{}})
        sub      [{:command :move-to :params {:x 30 :y 30}}
                  {:command :line-to :params {:x 40 :y 30}}]
        state'   (ptk/update (path.clipboard/paste-content sub) state)
        content' (get-in state' [:workspace-drawing :object :content])]
    (t/is (= (into (vec content) sub) (vec content')))
    (t/is (= {:nodes #{3 4}
              :segments #{}
              :handlers #{}}
             (get-in state' [:workspace-local :edit-path id :selection])))))

(t/deftest pasting-over-identical-nodes-offsets-the-fragment
  (let [id       (random-uuid)
        content  (selectable-path-content)
        state    (selectable-path-state id content path.helpers/empty-selection)
        ;; Same coordinates as the existing segment between nodes 0 and 1
        sub      [{:command :move-to :params {:x 0 :y 0}}
                  {:command :curve-to :params {:c1x 2 :c1y 0
                                               :c2x 8 :c2y 0
                                               :x 10 :y 0}}]
        state'   (ptk/update (path.clipboard/paste-content sub) state)
        content' (vec (get-in state' [:workspace-drawing :object :content]))
        pasted   (subvec content' 3)]
    ;; The pasted nodes cannot collide with existing node positions, so the
    ;; position-based move pipeline never drags original and pasted together
    (t/is (= {:x 10 :y 10} (select-keys (:params (first pasted)) [:x :y])))
    (t/is (= {:x 20 :y 10} (select-keys (:params (second pasted)) [:x :y])))))
