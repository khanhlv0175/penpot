;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.logic.path-edition-test
  (:require
   [app.common.data.undo-stack :as u]
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
   [app.main.data.workspace.path.tools :as path.tools]
   [app.main.data.workspace.path.undo :as path.undo]
   [app.main.store :as st]
   [app.main.streams :as ms]
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

(t/deftest to-pixel-snap-quantises-to-half-pixels-past-the-zoom-threshold
  ;; `to-pixel-snap` reads the global store for the pixel-snap layout flag and
  ;; the current zoom. Above 300% zoom fine positioning needs half-pixel steps.
  (let [original @st/state
        snap     (fn [zoom p]
                   (reset! st/state {:workspace-layout #{:snap-pixel-grid}
                                     :workspace-local {:zoom zoom}})
                   (path.streams/to-pixel-snap p))]
    (try
      ;; at or below the threshold, snapping rounds to whole pixels
      (t/is (= (gpt/point 100 100) (snap 1 (gpt/point 100.4 100.4))))
      (t/is (= (gpt/point 100 100) (snap 3 (gpt/point 100.4 100.4))))
      ;; above 300% zoom it rounds to the nearest half pixel
      (t/is (= (gpt/point 100.5 100.5) (snap 6 (gpt/point 100.4 100.4))))
      ;; with pixel snapping off the position passes through unchanged
      (reset! st/state {:workspace-layout #{} :workspace-local {:zoom 6}})
      (t/is (= (gpt/point 100.4 100.4)
               (path.streams/to-pixel-snap (gpt/point 100.4 100.4))))
      (finally
        (reset! st/state original)))))

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
                   id [1 :c2] {} 3 4 :smart)
        state'    (ptk/update event state)
        modifiers (get-in state' [:workspace-local :edit-path id :content-modifiers])
        content'  (path/apply-content-modifiers content modifiers)]
    (t/is (= {:c2x 3 :c2y 4} (get modifiers 1)))
    (t/is (= {:c1x 3 :c1y 4} (get modifiers 2)))
    (t/is (= (gpt/point 11 4) (path/get-handler-point content' 1 :c2)))
    (t/is (= (gpt/point 15 4) (path/get-handler-point content' 2 :c1)))))

(t/deftest arrow-move-nudges-selected-handlers
  ;; an arrow key nudges a selected handler on its own (set-move-modifier is the
  ;; per-step accumulator move-selected drives for handler selections)
  (let [id       (random-uuid)
        content  (selectable-path-content)
        state    (selectable-path-state id content
                                        {:nodes #{} :segments #{} :handlers #{[2 :c1]}})
        state'   (ptk/update (path.edition/set-move-modifier [] #{[2 :c1]} (gpt/point 0 5))
                             state)
        mods     (get-in state' [:workspace-local :edit-path id :content-modifiers])
        content' (path/apply-content-modifiers content mods)]
    (t/is (= {:c1x 0 :c1y 5} (get mods 2)))
    ;; [2 :c1] base (12,0) -> (12,5); node 1 and its opposite handle stay put
    (t/is (= (gpt/point 12 5) (path/get-handler-point content' 2 :c1)))
    (t/is (= (gpt/point 10 0) (path.helpers/node-position content' 1)))
    (t/is (= (gpt/point 8 0) (path/get-handler-point content' 1 :c2)))))

(t/deftest arrow-move-nudges-selected-segment-endpoints
  ;; an arrow key nudges a selected segment by moving its endpoint nodes (with
  ;; their handlers), resolved the way move-selected does for segment selections
  (let [id       (random-uuid)
        content  (selectable-path-content)
        state    (selectable-path-state id content
                                        {:nodes #{} :segments #{1} :handlers #{}})
        node-idx (path.helpers/segment-node-indices content #{1})
        points   (path.helpers/node-positions content node-idx)
        state'   (ptk/update (path.edition/set-move-modifier points #{} (gpt/point 0 5))
                             state)
        mods     (get-in state' [:workspace-local :edit-path id :content-modifiers])
        content' (path/apply-content-modifiers content mods)]
    ;; segment 1 connects node 0 (0,0) and node 1 (10,0); both move by (0,5)
    (t/is (= (gpt/point 0 5) (path.helpers/node-position content' 0)))
    (t/is (= (gpt/point 10 5) (path.helpers/node-position content' 1)))
    ;; the endpoint nodes' handles move rigidly with them
    (t/is (= (gpt/point 2 5) (path/get-handler-point content' 1 :c1)))
    (t/is (= (gpt/point 12 5) (path/get-handler-point content' 2 :c1)))))

(t/deftest align-nodes-aligns-selected-nodes-to-an-edge
  ;; the align-nodes tool moves the selected nodes' anchors onto a common edge
  ;; of their bounding box, updating the drawing copy's content
  (let [id       (random-uuid)
        content  (path/content
                  [{:command :move-to :params {:x 0 :y 0}}
                   {:command :line-to :params {:x 10 :y 4}}
                   {:command :line-to :params {:x 4 :y 20}}])
        state    (selectable-path-state id content
                                        {:nodes #{0 1 2} :segments #{} :handlers #{}})
        state'   (ptk/update (path.tools/align-nodes :hleft) state)
        content' (get-in state' [:workspace-drawing :object :content])]
    ;; every selected node's x becomes the min x (0), y is untouched
    (t/is (= (gpt/point 0 0)  (path.helpers/node-position content' 0)))
    (t/is (= (gpt/point 0 4)  (path.helpers/node-position content' 1)))
    (t/is (= (gpt/point 0 20) (path.helpers/node-position content' 2)))))

(t/deftest distribute-nodes-spaces-selected-nodes-evenly
  (let [id       (random-uuid)
        content  (path/content
                  [{:command :move-to :params {:x 0 :y 0}}
                   {:command :line-to :params {:x 3 :y 5}}
                   {:command :line-to :params {:x 10 :y 9}}])
        state    (selectable-path-state id content
                                        {:nodes #{0 1 2} :segments #{} :handlers #{}})
        state'   (ptk/update (path.tools/distribute-nodes :horizontal) state)
        content' (get-in state' [:workspace-drawing :object :content])]
    ;; the middle node is centered on x between the two extremes (0 and 10)
    (t/is (= (gpt/point 5 5)  (path.helpers/node-position content' 1)))
    ;; the extreme nodes stay put
    (t/is (= (gpt/point 0 0)  (path.helpers/node-position content' 0)))
    (t/is (= (gpt/point 10 9) (path.helpers/node-position content' 2)))))

(t/deftest selected-node-indices-folds-segment-endpoints
  (let [content (selectable-path-content)]
    ;; segment index 1 connects nodes 0 and 1
    (t/is (= #{0 1}
             (path.helpers/selected-node-indices content {:nodes #{} :segments #{1}})))
    ;; explicit nodes and segment endpoints are unioned
    (t/is (= #{0 1 2}
             (path.helpers/selected-node-indices content {:nodes #{2} :segments #{1}})))))

(t/deftest flip-nodes-includes-selected-segment-endpoints
  (let [id       (random-uuid)
        content  (selectable-path-content)
        state    (selectable-path-state id content
                                        {:nodes #{} :segments #{1} :handlers #{}})
        state'   (ptk/update (path.tools/flip-nodes :horizontal) state)
        content' (get-in state' [:workspace-drawing :object :content])]
    ;; segment 1's endpoints (nodes 0 and 1) mirror across their bbox centre (x=5)
    (t/is (= (gpt/point 10 0) (path.helpers/node-position content' 0)))
    (t/is (= (gpt/point 0 0)  (path.helpers/node-position content' 1)))
    ;; node 2 is not an endpoint of segment 1, so it stays put
    (t/is (= (gpt/point 20 0) (path.helpers/node-position content' 2)))))

(t/deftest merge-nodes-includes-selected-segment-endpoints
  (let [id       (random-uuid)
        content  (selectable-path-content)
        state    (selectable-path-state id content
                                        {:nodes #{} :segments #{1} :handlers #{}})
        state'   (ptk/update (path.tools/merge-nodes) state)
        content' (get-in state' [:workspace-drawing :object :content])
        pts      (path/get-points content')]
    ;; segment 1's endpoints (0,0) and (10,0) merge to their midpoint (5,0)
    (t/is (some #(= (gpt/point 5 0) %) pts))
    (t/is (< (count pts) 3))))

(t/deftest delete-selected-opens-segments-else-removes-nodes
  (let [id       (random-uuid)
        content  (path/content
                  [{:command :move-to :params {:x 0 :y 0}}
                   {:command :line-to :params {:x 10 :y 0}}
                   {:command :line-to :params {:x 20 :y 0}}
                   {:command :line-to :params {:x 30 :y 0}}])
        run      (fn [selection]
                   (let [state  (selectable-path-state id content selection)
                         events (atom [])]
                     (->> (ptk/watch (path.tools/delete-selected) state nil)
                          (rx/subs! #(swap! events conj %)))
                     ;; delete-selected emits a single node-tool event; apply it
                     (ptk/update (first @events) state)))
        move-tos (fn [st] (->> (get-in st [:workspace-drawing :object :content])
                               vec
                               (filter #(= :move-to (:command %)))
                               count))
        nodes    (fn [st] (count (path/get-points
                                  (get-in st [:workspace-drawing :object :content]))))]
    ;; deleting the middle segment (index 2) opens the path into two subpaths
    (t/is (> (move-tos (run {:nodes #{} :segments #{2} :handlers #{}})) 1))
    ;; deleting a node removes it (fewer than the original 4 nodes)
    (t/is (< (nodes (run {:nodes #{1} :segments #{} :handlers #{}})) 4))))

(t/deftest delete-selected-with-segments-opens-a-gap-around-the-node
  (let [id       (random-uuid)
        content  (path/content
                  [{:command :move-to :params {:x 0 :y 0}}
                   {:command :line-to :params {:x 10 :y 0}}
                   {:command :line-to :params {:x 20 :y 0}}
                   {:command :line-to :params {:x 30 :y 0}}
                   {:command :line-to :params {:x 40 :y 0}}])
        state    (selectable-path-state id content {:nodes #{2} :segments #{} :handlers #{}})
        events   (atom [])
        _        (->> (ptk/watch (path.tools/delete-selected-with-segments) state nil)
                      (rx/subs! #(swap! events conj %)))
        state'   (ptk/update (first @events) state)
        content' (get-in state' [:workspace-drawing :object :content])
        move-tos (->> content' vec (filter #(= :move-to (:command %))) count)
        nodes    (count (path/get-points content'))]
    ;; the middle node's two incident segments are dropped: the node disappears
    ;; and the path opens into two subpaths (a gap, no heal)
    (t/is (= 4 nodes))
    (t/is (= 2 move-tos))))

(t/deftest duplicate-selection-content-copies-nodes-and-segments
  (let [content (path/content
                 [{:command :move-to :params {:x 0 :y 0}}
                  {:command :line-to :params {:x 10 :y 0}}
                  {:command :line-to :params {:x 20 :y 0}}])]
    ;; a lone node copies its incoming segment: attach at the predecessor (10,0)
    ;; and one new node — the shifted endpoint (30,10) — is the only selectable
    (let [{:keys [sub selected]}
          (path.helpers/duplicate-selection-content
           content {:nodes #{2} :segments #{}} (gpt/point 10 10))]
      (t/is (= [[:move-to {:x 10 :y 0}]
                [:line-to {:x 30 :y 10}]]
               (mapv (juxt :command :params) sub)))
      ;; only the new endpoint (index 1) is selected, not the attach point
      (t/is (= #{1} selected)))
    ;; an interior node copies every incident segment: both copies end at the
    ;; single new node (the shifted (10,0) -> (20,10)) and both meeting points
    ;; are selected, while the far ends stay attached to the original neighbors
    (let [{:keys [sub selected]}
          (path.helpers/duplicate-selection-content
           content {:nodes #{1} :segments #{}} (gpt/point 10 10))]
      (t/is (= [[:move-to {:x 0 :y 0}]
                [:line-to {:x 20 :y 10}]
                [:move-to {:x 20 :y 0}]
                [:line-to {:x 20 :y 10}]]
               (mapv (juxt :command :params) sub)))
      (t/is (= #{1 3} selected)))
    ;; a selected segment is copied as a fragment shifted by the offset, both
    ;; endpoints selectable
    (let [{:keys [sub selected]}
          (path.helpers/duplicate-selection-content
           content {:nodes #{} :segments #{1}} (gpt/point 10 10))]
      (t/is (= [[:move-to {:x 10 :y 10}]
                [:line-to {:x 20 :y 10}]]
               (mapv (juxt :command :params) sub)))
      (t/is (= #{0 1} selected)))))

(t/deftest splice-duplicated-appends-copies-and-selects-only-new-nodes
  (let [id       (random-uuid)
        content  (path/content
                  [{:command :move-to :params {:x 0 :y 0}}
                   {:command :line-to :params {:x 10 :y 0}}
                   {:command :line-to :params {:x 20 :y 0}}])
        result   (path.helpers/duplicate-selection-content
                  content {:nodes #{2} :segments #{}} (gpt/point 10 10))
        state    (selectable-path-state id content {:nodes #{2} :segments #{} :handlers #{}})
        state'   (ptk/update (path.edition/splice-duplicated result) state)
        content' (get-in state' [:workspace-drawing :object :content])]
    ;; the copy is appended as a new subpath (3 original + 2 spliced commands)
    (t/is (= 5 (count content')))
    ;; only the new endpoint (absolute index 4) is selected; the attach point
    ;; (index 3, coincident with the original node) is not, so a drag never
    ;; moves the original along with the copy
    (t/is (= #{4}
             (get-in state' [:workspace-local :edit-path id :selection :nodes])))))

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
       (t/is (= [::path.drawing/close-drawn-loops
                 ::path.drawing/setup-frame
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
                 ::path.drawing/close-drawn-loops
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
          ;; A rect converts to a loop closed with a trailing line-to; on
          ;; persistence that loop gets an explicit close-path so the stroke
          ;; joins at the seam.
          changed-content (-> (:content original-path)
                              (path/move-content delta)
                              (path/close-loops))
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
                   id [2 :c1] {} 3 4 :smart)
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
        ;; the pasted fragment is dropped centred at the pointer (its bbox
        ;; centre (35,30) lands on (100,100))
        _        (rx/push! ms/mouse-position (gpt/point 100 100))
        state'   (ptk/update (path.clipboard/paste-content sub) state)
        content' (vec (get-in state' [:workspace-drawing :object :content]))
        pasted   (subvec content' 3)]
    (t/is (= (vec content) (subvec content' 0 3)))
    (t/is (= {:x 95 :y 100} (select-keys (:params (first pasted)) [:x :y])))
    (t/is (= {:x 105 :y 100} (select-keys (:params (second pasted)) [:x :y])))
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
        ;; pointer at the fragment's own centre → it lands back on the existing
        ;; nodes and must be nudged off them by the collision offset
        _        (rx/push! ms/mouse-position (gpt/point 5 0))
        state'   (ptk/update (path.clipboard/paste-content sub) state)
        content' (vec (get-in state' [:workspace-drawing :object :content]))
        pasted   (subvec content' 3)]
    ;; The pasted nodes cannot collide with existing node positions, so the
    ;; position-based move pipeline never drags original and pasted together
    (t/is (= {:x 10 :y 10} (select-keys (:params (first pasted)) [:x :y])))
    (t/is (= {:x 20 :y 10} (select-keys (:params (second pasted)) [:x :y])))))

(defn- corner-path-content
  "Like selectable-path-content but the node at (10,0) is a corner: its
  outgoing handle [2 :c1] is bent off the incoming handle [1 :c2]."
  []
  (path/content
   [{:command :move-to
     :params {:x 0 :y 0}}
    {:command :curve-to
     :params {:c1x 2 :c1y 0
              :c2x 8 :c2y 0
              :x 10 :y 0}}
    {:command :curve-to
     :params {:c1x 12 :c1y 6
              :c2x 18 :c2y 0
              :x 20 :y 0}}]))

(t/deftest handlers-joined-detects-smooth-vs-corner-nodes
  ;; node (10,0): incoming [1 :c2]=(8,0), outgoing [2 :c1]
  (t/is (path.helpers/handlers-joined? (selectable-path-content) 2 :c1))
  (t/is (not (path.helpers/handlers-joined? (corner-path-content) 2 :c1))))

(t/deftest handler-drag-smart-keeps-a-smooth-node-smooth
  (let [id        (random-uuid)
        content   (selectable-path-content)
        state     (selectable-path-state id content {:nodes #{} :segments #{}
                                                     :handlers #{[2 :c1]}})
        ;; the default :smart mode on a joined node preserves the
        ;; opposite-angle match
        event     (path.edition/modify-selected-handlers id [2 :c1] {} 0 4 :smart)
        state'    (ptk/update event state)
        modifiers (get-in state' [:workspace-local :edit-path id :content-modifiers])
        content'  (path/apply-content-modifiers content modifiers)]
    (t/is (= (gpt/point 12 4) (path/get-handler-point content' 2 :c1)))
    ;; the opposite rotated to stay collinear -> still a smooth node
    (t/is (not= (gpt/point 8 0) (path/get-handler-point content' 1 :c2)))
    (t/is (path.helpers/handlers-joined? content' 2 :c1))))

(t/deftest handler-drag-independent-breaks-a-smooth-node
  (let [id        (random-uuid)
        content   (selectable-path-content)
        state     (selectable-path-state id content {:nodes #{} :segments #{}
                                                     :handlers #{[2 :c1]}})
        ;; alt (:independent) on a joined node breaks the pair: the opposite
        ;; stays put
        event     (path.edition/modify-selected-handlers id [2 :c1] {} 0 4 :independent)
        state'    (ptk/update event state)
        modifiers (get-in state' [:workspace-local :edit-path id :content-modifiers])
        content'  (path/apply-content-modifiers content modifiers)]
    (t/is (= (gpt/point 12 4) (path/get-handler-point content' 2 :c1)))
    (t/is (= (gpt/point 8 0) (path/get-handler-point content' 1 :c2)))
    (t/is (not (path.helpers/handlers-joined? content' 2 :c1)))))

(t/deftest handler-drag-mirror-rejoins-a-corner-node
  (let [id        (random-uuid)
        content   (corner-path-content)
        state     (selectable-path-state id content {:nodes #{} :segments #{}
                                                     :handlers #{[2 :c1]}})
        ;; mod (:mirror) on an independent (corner) node re-joins into a full
        ;; mirror (same length, opposite angle): base [2 :c1]=(12,6) dragged by
        ;; (2,-6) -> (14,0); the opposite [1 :c2]=(8,0) mirrors it around node
        ;; (10,0) -> (6,0)
        event     (path.edition/modify-selected-handlers id [2 :c1] {} 2 -6 :mirror)
        state'    (ptk/update event state)
        modifiers (get-in state' [:workspace-local :edit-path id :content-modifiers])
        content'  (path/apply-content-modifiers content modifiers)]
    (t/is (= (gpt/point 14 0) (path/get-handler-point content' 2 :c1)))
    (t/is (= (gpt/point 6 0) (path/get-handler-point content' 1 :c2)))
    (t/is (path.helpers/handlers-joined? content' 2 :c1))))

(t/deftest handler-drag-aligned-mirrors-angle-keeping-length
  (let [id        (random-uuid)
        content   (corner-path-content)
        state     (selectable-path-state id content {:nodes #{} :segments #{}
                                                     :handlers #{}})
        ;; mod+alt (:aligned) forces the opposite onto the mirrored angle while
        ;; keeping its own length: [2 :c1]=(12,6) dragged by (-2,-2) -> (10,4);
        ;; the opposite [1 :c2]=(8,0) (length 2 from node (10,0)) rotates to
        ;; (10,-2)
        event     (path.edition/modify-selected-handlers id [2 :c1] {} -2 -2 :aligned)
        state'    (ptk/update event state)
        modifiers (get-in state' [:workspace-local :edit-path id :content-modifiers])
        content'  (path/apply-content-modifiers content modifiers)]
    (t/is (= (gpt/point 10 4) (path/get-handler-point content' 2 :c1)))
    (t/is (= (gpt/point 10 -2) (path/get-handler-point content' 1 :c2)))
    (t/is (path.helpers/handlers-joined? content' 2 :c1))))

(t/deftest remove-handler-collapses-the-clicked-handler
  (let [id       (random-uuid)
        content  (selectable-path-content)
        state    (selectable-path-state id content
                                        {:nodes #{} :segments #{}
                                         :handlers #{[1 :c2]}})
        state'   (ptk/update (path.tools/remove-handler 1 :c2) state)
        content' (get-in state' [:workspace-drawing :object :content])]
    ;; the clicked handler rests on its node (10,0); the others are untouched
    (t/is (= (gpt/point 10 0) (path/get-handler-point content' 1 :c2)))
    (t/is (= (gpt/point 2 0) (path/get-handler-point content' 1 :c1)))
    (t/is (= (gpt/point 12 0) (path/get-handler-point content' 2 :c1)))))

(t/deftest toggle-segment-curve-switches-line-and-curve
  (let [id        (random-uuid)
        content   (path/content
                   [{:command :move-to :params {:x 0 :y 0}}
                    {:command :line-to :params {:x 30 :y 0}}])
        state     (selectable-path-state id content path.helpers/empty-selection)
        state'    (ptk/update (path.tools/toggle-segment-curve 1) state)
        content'  (get-in state' [:workspace-drawing :object :content])
        state''   (ptk/update (path.tools/toggle-segment-curve 1) state')
        content'' (get-in state'' [:workspace-drawing :object :content])]
    (t/is (= :curve-to (:command (nth content' 1))))
    ;; handles a third along, offset perpendicular (0.25 * length) into a bow
    (t/is (= (gpt/point 10 7.5) (path/get-handler-point content' 1 :c1)))
    (t/is (= (gpt/point 20 7.5) (path/get-handler-point content' 1 :c2)))
    (t/is (= :line-to (:command (nth content'' 1))))))

(t/deftest remove-segment-opens-the-path-keeping-nodes
  (let [id       (random-uuid)
        content  (path/content
                  [{:command :move-to :params {:x 0 :y 0}}
                   {:command :line-to :params {:x 10 :y 0}}
                   {:command :line-to :params {:x 20 :y 0}}
                   {:command :line-to :params {:x 30 :y 0}}])
        state    (selectable-path-state id content
                                        {:nodes #{} :segments #{2} :handlers #{}})
        state'   (ptk/update (path.tools/remove-segment 2) state)
        content' (get-in state' [:workspace-drawing :object :content])]
    (t/is (= [[:move-to {:x 0 :y 0}] [:line-to {:x 10 :y 0}]
              [:move-to {:x 20 :y 0}] [:line-to {:x 30 :y 0}]]
             (mapv (juxt :command :params) content')))
    ;; the removed segment's now-stale selection is pruned
    (t/is (= #{} (get-in state' [:workspace-local :edit-path id :selection :segments])))))

(t/deftest remove-node-with-segments-opens-a-gap
  (let [id       (random-uuid)
        content  (path/content
                  [{:command :move-to :params {:x 0 :y 0}}
                   {:command :line-to :params {:x 10 :y 0}}
                   {:command :line-to :params {:x 20 :y 0}}
                   {:command :line-to :params {:x 30 :y 0}}])
        state    (selectable-path-state id content path.helpers/empty-selection)
        emitted  (atom [])
        _        (->> (ptk/watch (path.tools/remove-node-with-segments 1) state nil)
                      (rx/subs! #(swap! emitted conj %)))
        state'   (reduce #(ptk/update %2 %1) state @emitted)
        content' (get-in state' [:workspace-drawing :object :content])]
    ;; node (10,0) and both incident segments are gone; the (0,0) start is
    ;; left dangling and dropped too, the rest of the path survives
    (t/is (= [[:move-to {:x 20 :y 0}] [:line-to {:x 30 :y 0}]]
             (mapv (juxt :command :params) content')))))

(t/deftest next-point-preview-is-suppressed-during-a-modifier-drag
  (let [id      (random-uuid)
        content (path/content [{:command :move-to :params {:x 0 :y 0}}
                               {:command :line-to :params {:x 100 :y 0}}])
        mk      (fn [modifiers]
                  ;; draw-mode creation: :edition is nil, the path lives under
                  ;; the drawing object and the preview is driven by the loop
                  {:workspace-drawing {:object {:id id :type :path :content content}}
                   :workspace-local {:edition nil
                                     :zoom 1
                                     :edit-path {id {:edit-mode :draw
                                                     :last-point (gpt/point 100 0)
                                                     :content-modifiers modifiers}}}})
        event   (path.drawing/preview-next-point {:x 150 :y 40 :shift? false})
        idle    (ptk/update event (mk {}))
        during  (ptk/update event (mk {1 {:c1x 5 :c1y 5}}))]
    ;; no active drag: the next-point preview updates as usual
    (t/is (some? (get-in idle [:workspace-local :edit-path id :preview])))
    ;; a placed handler is being dragged mid-draw: the preview must not move
    (t/is (nil? (get-in during [:workspace-local :edit-path id :preview])))))

(t/deftest dragging-a-node-or-segment-onto-another-merges-them
  (let [id      (random-uuid)
        content (path/content
                 [{:command :move-to :params {:x 0 :y 0}}
                  {:command :line-to :params {:x 10 :y 0}}
                  {:command :move-to :params {:x 12 :y 0}}
                  {:command :line-to :params {:x 30 :y 0}}])
        mk      (fn [selection]
                  (-> (selectable-path-state id content selection)
                      (assoc-in [:workspace-local :zoom] 1)))
        emit-of (fn [state]
                  (let [out (atom [])]
                    (->> (ptk/watch (path.edition/merge-dragged-on-drop)
                                    state (rx/subject))
                         (rx/subs! #(swap! out conj %)))
                    @out))
        welded  [{:x 0 :y 0} {:x 11 :y 0} {:x 30 :y 0}]]
    (t/testing "a single node dropped within range of another node merges them"
      (let [state    (mk {:nodes #{1} :segments #{} :handlers #{}})
            events   (emit-of state)
            content' (vec (get-in (ptk/update (first events) state)
                                  [:workspace-drawing :object :content]))]
        (t/is (= 1 (count events)))
        ;; the end of the first subpath (10,0) dropped onto the start of the
        ;; second (12,0): they weld into one continuous path at the midpoint
        (t/is (= welded (mapv :params content')))))
    (t/testing "a dragged segment whose endpoint lands on a node merges too"
      ;; segment 1 (nodes (0,0)-(10,0)); its (10,0) end is within range of (12,0)
      (let [state    (mk {:nodes #{} :segments #{1} :handlers #{}})
            events   (emit-of state)
            content' (vec (get-in (ptk/update (first events) state)
                                  [:workspace-drawing :object :content]))]
        (t/is (= 1 (count events)))
        (t/is (= welded (mapv :params content')))))
    (t/testing "a node dropped with no neighbour in range does not merge"
      (t/is (empty? (emit-of (mk {:nodes #{3} :segments #{} :handlers #{}})))))))

;; --- Path-local undo / redo
;;
;; The auto-capture watcher (`start-path-undo`) tracks the *global* store's
;; content ref, so it can't be driven through the local test store. The undo
;; events themselves are plain UpdateEvents over `:undo-stack`, so we drive them
;; directly: seed the stack with `add-undo-entry`, then undo/redo.

(t/deftest path-local-undo-redo-restores-content-and-clears-preview
  (let [id        (random-uuid)
        content-a (path/content [{:command :move-to :params {:x 0 :y 0}}
                                 {:command :line-to :params {:x 10 :y 0}}])
        content-b (path/content [{:command :move-to :params {:x 0 :y 0}}
                                 {:command :line-to :params {:x 10 :y 5}}])
        base      (-> (selectable-path-state id content-a path.helpers/empty-selection)
                      (assoc-in [:workspace-local :edit-path id :undo-stack] (u/make-stack)))
        ;; capture A, change to B (leaving a stale preview), capture B
        s1        (ptk/update (path.undo/add-undo-entry) base)
        s2        (-> (path.state/set-content s1 content-b)
                      (assoc-in [:workspace-local :edit-path id :preview]
                                {:command :line-to :params {:x 99 :y 99}}))
        s3        (ptk/update (path.undo/add-undo-entry) s2)
        s4        (ptk/update (path.undo/undo-path) s3)
        s5        (ptk/update (path.undo/redo-path) s4)]
    (t/is (= content-b (path.state/get-path s3 :content)))
    (t/is (= content-a (path.state/get-path s4 :content)))
    ;; the render-only preview is dropped when an entry is restored
    (t/is (nil? (get-in s4 [:workspace-local :edit-path id :preview])))
    (t/is (= content-b (path.state/get-path s5 :content)))))

(t/deftest path-undo-entry-never-captures-the-transient-preview
  (let [id     (random-uuid)
        state  (-> (selectable-path-state id (selectable-path-content)
                                          path.helpers/empty-selection)
                   (assoc-in [:workspace-local :edit-path id :undo-stack] (u/make-stack))
                   (assoc-in [:workspace-local :edit-path id :preview]
                             {:command :line-to :params {:x 99 :y 99}}))
        state' (ptk/update (path.undo/add-undo-entry) state)
        entry  (u/peek (get-in state' [:workspace-local :edit-path id :undo-stack]))]
    (t/is (some? entry))
    (t/is (not (contains? entry :preview)))))

;; --- Tool operations through the full edition lifecycle (real store)

(t/deftest tool-make-curve-persists-through-edition-lifecycle
  (t/async
    done
    (let [file   (setup-rect-file)
          rect   (cths/get-shape file :rect1)
          id     (:id rect)
          store  (ths/setup-store file)
          events (into (start-path-edition-events id)
                       [(path.selection/select-node 1 false)
                        (path.tools/make-curve)
                        :interrupt])]
      (ths/run-store
       store done events
       (fn [new-state]
         (let [stored (cths/get-shape (ths/get-file-from-state new-state) :rect1)]
           (t/is (= :path (:type stored)))
           ;; make-curve on a corner introduces at least one curve segment
           (t/is (some #(= :curve-to (:command %)) (seq (:content stored))))
           (t/is (nil? (get-in new-state [:workspace-drawing :object])))))))))

(t/deftest tool-remove-node-persists-through-edition-lifecycle
  (t/async
    done
    (let [file       (setup-rect-file)
          rect       (cths/get-shape file :rect1)
          id         (:id rect)
          orig-nodes (count (path/get-points (:content (path/convert-to-path rect))))
          store      (ths/setup-store file)
          events     (into (start-path-edition-events id)
                           [(path.selection/select-node 1 false)
                            (path.tools/remove-node)
                            :interrupt])]
      (ths/run-store
       store done events
       (fn [new-state]
         (let [stored (cths/get-shape (ths/get-file-from-state new-state) :rect1)]
           (t/is (= :path (:type stored)))
           ;; removing a node leaves fewer nodes than the converted rect had
           (t/is (< (count (path/get-points (:content stored))) orig-nodes))
           (t/is (nil? (get-in new-state [:workspace-drawing :object])))))))))

(defn- page-paths
  [state]
  (->> (:objects (cthf/current-page (ths/get-file-from-state state)))
       vals
       (filter #(= :path (:type %)))))

(t/deftest pasting-path-nodes-outside-editor-creates-a-new-path-shape
  (t/async
    done
    (let [file    (setup-rect-file)
          store   (ths/setup-store file)
          content (path/content [{:command :move-to :params {:x 0 :y 0}}
                                 {:command :line-to :params {:x 40 :y 0}}
                                 {:command :line-to :params {:x 40 :y 40}}])
          target  (gpt/point 300 300)]
      ;; a new path shape is centred at the pointer position
      (rx/push! ms/mouse-position target)
      (ths/run-store
       store done
       [(path.clipboard/paste-nodes-as-shape content)]
       (fn [new-state]
         (let [paths  (page-paths new-state)
               pasted (first paths)]
           (t/is (= 1 (count paths)))
           (when pasted
             (t/is (= target (grc/rect->center (:selrect pasted))))
             (t/is (contains? (get-in new-state [:workspace-local :selected]) (:id pasted))))))))))

(t/deftest pasting-path-nodes-while-editing-does-not-create-a-shape
  (t/async
    done
    (let [file    (setup-rect-file)
          id      (:id (cths/get-shape file :rect1))
          store   (ths/setup-store file)
          content (path/content [{:command :move-to :params {:x 0 :y 0}}
                                 {:command :line-to :params {:x 40 :y 0}}])
          ;; while a path is being edited the editor paste splices instead, so
          ;; the outside-editor new-shape paste must be a no-op
          events  (conj (start-path-edition-events id)
                        (path.clipboard/paste-nodes-as-shape content))]
      (rx/push! ms/mouse-position (gpt/point 300 300))
      (ths/run-store
       store done events
       (fn [new-state]
         (t/is (empty? (page-paths new-state))))))))
