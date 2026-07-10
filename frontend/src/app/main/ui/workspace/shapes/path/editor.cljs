;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.shapes.path.editor
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.geom.point :as gpt]
   [app.common.types.path :as path]
   [app.common.types.path.helpers :as path.helpers]
   [app.main.data.workspace.path :as drp]
   [app.main.data.workspace.path.helpers :as dwp.helpers]
   [app.main.snap :as snap]
   [app.main.store :as st]
   [app.main.ui.css-cursors :as cur]
   [app.util.dom :as dom]
   [app.util.keyboard :as kbd]
   [goog.events :as events]
   [rumext.v2 :as mf]))

(def point-radius 5)
(def point-radius-selected 4)
(def point-radius-active-area 15)
(def point-radius-stroke-width 1)

(def handler-side 6)
(def handler-stroke-width 1)

(def path-preview-dasharray 4)
(def path-snap-stroke-width 1)

(def accent-color "var(--color-accent-tertiary)")
(def secondary-color "var(--color-accent-quaternary)")
(def black-color "var(--app-black)")
(def white-color "var(--app-white)")
(def gray-color "var(--df-secondary)")
(def selected-segment-color "#F49EF7")

(mf/defc path-point*
  {::mf/private true}
  [{:keys [index position zoom edit-mode is-hover is-selected is-preview is-start-path is-last is-curve]}]
  (let [{:keys [x y]} position

        is-draw (= edit-mode :draw)
        is-move (= edit-mode :move)

        is-active
        (or ^boolean is-selected
            ^boolean is-hover)

        on-enter
        (mf/use-fn
         (mf/deps index)
         (fn [_]
           (when (some? index)
             (st/emit! (drp/path-pointer-enter index)))))

        on-leave
        (mf/use-fn
         (mf/deps index)
         (fn [_]
           (when (some? index)
             (st/emit! (drp/path-pointer-leave index)))))

        on-pointer-down
        (fn [event]
          (when (dom/left-mouse? event)
            (dom/stop-propagation event)
            (dom/prevent-default event)
            (let [is-shift (kbd/shift? event)
                  is-mod   (kbd/mod? event)]
              (cond
                is-last
                (st/emit! (drp/reset-last-handler))

                (and is-move is-mod (not is-curve))
                (st/emit! (drp/make-curve position))

                (and is-move is-mod is-curve)
                (st/emit! (drp/make-corner position))

                is-move
                ;; If we're dragging a selected item we don't change the selection
                (st/emit! (drp/start-move-path-point index is-shift))

                (and is-draw is-start-path)
                (st/emit! (drp/start-path-from-point position))

                (and is-draw (not is-start-path))
                (st/emit! (drp/close-path-drag-start position))))))]

    [:g.path-point
     [:circle.path-point
      {:cx x
       :cy y
       :r (if ^boolean is-active
            (/ point-radius zoom)
            (/ point-radius-selected zoom))
       :style {:stroke-width (/ point-radius-stroke-width zoom)
               :stroke (cond ^boolean is-active black-color
                             ^boolean is-preview secondary-color
                             :else accent-color)
               :fill (cond is-selected accent-color
                           :else white-color)}}]
     [:circle {:cx x
               :cy y
               :r (/ point-radius-active-area zoom)
               :on-pointer-down on-pointer-down
               :on-pointer-enter on-enter
               :on-pointer-leave on-leave
               :pointer-events (when-not ^boolean is-preview "visible")
               :class (cond ^boolean is-draw (cur/get-static "pen-node")
                            ^boolean is-move (cur/get-static "pointer-node"))
               :style {:stroke-width 0
                       :fill "none"}}]]))

(mf/defc path-handler*
  {::mf/private true}
  [{:keys [index prefix point handler zoom is-selected is-hover edit-mode snap-angle]}]
  (let [x       (dm/get-prop handler :x)
        y       (dm/get-prop handler :y)
        is-draw (= edit-mode :draw)
        is-move (= edit-mode :move)

        is-active
        (or ^boolean is-selected
            ^boolean is-hover)

        on-enter
        (mf/use-fn
         (mf/deps index prefix)
         (fn [_] (st/emit! (drp/path-handler-enter index prefix))))

        on-leave
        (mf/use-fn
         (mf/deps index prefix)
         (fn [_] (st/emit! (drp/path-handler-leave index prefix))))

        on-pointer-down
        (mf/use-fn
         (mf/deps index prefix is-move)
         (fn [event]
           (when (dom/left-mouse? event)
             (dom/stop-propagation event)
             (dom/prevent-default event)

             (when ^boolean is-move
               (st/emit! (drp/start-move-handler index
                                                 prefix
                                                 (kbd/shift? event)))))))]

    [:g.handler {:pointer-events (if ^boolean is-draw "none" "visible")}
     [:line
      {:x1 (:x point)
       :y1 (:y point)
       :x2 x
       :y2 y
       :style {:stroke (if ^boolean is-hover
                         black-color
                         gray-color)
               :stroke-width (/ point-radius-stroke-width zoom)}}]

     (when ^boolean snap-angle
       [:line
        {:x1 (:x point)
         :y1 (:y point)
         :x2 x
         :y2 y
         :style {:stroke secondary-color
                 :stroke-width (/ point-radius-stroke-width zoom)}}])

     [:rect
      {:x (- x (/ handler-side 2 zoom))
       :y (- y (/ handler-side 2 zoom))
       :width (/ handler-side zoom)
       :height (/ handler-side zoom)

       :style {:stroke-width (/ handler-stroke-width zoom)
               :stroke (cond ^boolean is-active black-color
                             :else accent-color)
               :fill (cond ^boolean is-selected accent-color
                           :else white-color)}}]
     [:circle {:cx x
               :cy y
               :r (/ point-radius-active-area zoom)
               :on-pointer-down on-pointer-down
               :on-pointer-enter on-enter
               :on-pointer-leave on-leave
               :class (when ^boolean is-move
                        (cur/get-static "pointer-move"))
               :style {:fill "none"
                       :stroke-width 0}}]]))

(defn- segment-content
  [{:keys [from to segment]}]
  (path/content
   [{:command :move-to
     :params from}
    (if (= :close-path (:command segment))
      {:command :line-to
       :params to}
      segment)]))

(mf/defc path-segment*
  {::mf/private true}
  [{:keys [entry zoom edit-mode is-selected is-hover]}]
  (let [index    (:index entry)
        content  (mf/with-memo [entry] (segment-content entry))
        is-move  (= edit-mode :move)
        is-active (or ^boolean is-selected ^boolean is-hover)
        on-enter (mf/use-fn
                  (mf/deps index)
                  (fn [_]
                    (st/emit! (drp/path-segment-enter index))))
        on-leave (mf/use-fn
                  (mf/deps index)
                  (fn [_]
                    (st/emit! (drp/path-segment-leave index))))
        on-pointer-down
        (mf/use-fn
         (mf/deps index is-move)
         (fn [event]
           (when (and ^boolean is-move (dom/left-mouse? event))
             (dom/stop-propagation event)
             (dom/prevent-default event)
             (st/emit! (drp/start-move-path-segment index (kbd/shift? event))))))]
    [:g.path-segment {:pointer-events (if ^boolean is-move "visible" "none")}
     (when ^boolean is-active
       [:path {:d (.toString content)
               :pointer-events "none"
               :style {:fill "none"
                       :stroke (if ^boolean is-selected
                                 selected-segment-color
                                 black-color)
                       :stroke-width (/ 2 zoom)}}])
     [:path {:d (.toString content)
             :on-pointer-down on-pointer-down
             :on-pointer-enter on-enter
             :on-pointer-leave on-leave
             :pointer-events "stroke"
             :style {:fill "none"
                     :stroke "transparent"
                     :stroke-width (/ point-radius-active-area zoom)}}]]))

(mf/defc path-preview*
  {::mf/private true}
  [{:keys [zoom segment from]}]

  (let [path
        (when (not= :move-to (:command segment))
          (let [segments [{:command :move-to
                           :params from}]
                segments (conj segments segment)]
            (path/content segments)))

        position
        (mf/with-memo [segment]
          ;; FIXME: use a helper from common for this
          (gpt/point (:params segment)))]

    [:g.preview {:style {:pointer-events "none"}}
     (when (some? path)
       [:path {:style {:fill "none"
                       :stroke black-color
                       :stroke-width (/ handler-stroke-width zoom)
                       :stroke-dasharray (/ path-preview-dasharray zoom)}
               :d (str path)}])

     [:> path-point* {:position position
                      :is-preview true
                      :zoom zoom}]]))

(mf/defc path-snap*
  {::mf/private true}
  [{:keys [selected points zoom]}]
  (let [ranges
        (mf/with-memo [selected points]
          (snap/create-ranges points selected))

        snap-matches
        (snap/get-snap-delta-match selected ranges (/ 1 zoom))

        matches
        (concat (second (:x snap-matches)) (second (:y snap-matches)))]

    [:g.snap-paths
     (for [[idx [from to]] (d/enumerate matches)]
       [:line {:key (dm/str "snap-" idx "-" from "-" to)
               :x1 (:x from)
               :y1 (:y from)
               :x2 (:x to)
               :y2 (:y to)
               :style {:stroke secondary-color
                       :stroke-width (/ path-snap-stroke-width zoom)}}])]))

(defn- matching-handler? [content node handlers]
  (when (= 2 (count handlers))
    (let [[[i1 p1] [i2 p2]] handlers
          p1 (path/get-handler-point content i1 p1)
          p2 (path/get-handler-point content i2 p2)

          v1 (gpt/to-vec node p1)
          v2 (gpt/to-vec node p2)

          angle (gpt/angle-with-other v1 v2)]
      (<= (- 180 angle) 0.1))))

(mf/defc path-editor*
  [{:keys [shape zoom state]}]
  (let [editor-ref    (mf/use-ref nil)

        {:keys [edit-mode
                drag-handler
                prev-handler
                preview
                content-modifiers
                last-point
                selection
                moving-nodes
                moving-handler
                hover
                snap-toggled]}
        state

        selected-nodes    (get selection :nodes #{})
        selected-segments (get selection :segments #{})
        selected-handlers (get selection :handlers #{})
        hover-nodes       (get hover :nodes #{})
        hover-segments    (get hover :segments #{})
        hover-handlers    (get hover :handlers #{})

        base-content
        (get shape :content)

        content
        (mf/with-memo [base-content content-modifiers]
          (path/apply-content-modifiers base-content content-modifiers))

        content-points
        (mf/with-memo [content]
          (path/get-points content))

        ;; Node identity is the content command index; modifiers preserve
        ;; indices, so a node is rendered from its index regardless of where it
        ;; has been dragged. `content-points` (which skips close-path) aligns
        ;; positionally with `node-indices`.
        node-entries
        (mf/with-memo [content content-points]
          (mapv vector (dwp.helpers/node-indices content) content-points))

        segment-entries
        (mf/with-memo [content]
          (dwp.helpers/segment-entries content))

        selected-segment-nodes
        (mf/with-memo [content selected-segments]
          (dwp.helpers/segment-node-indices content selected-segments))

        points
        (mf/with-memo [content-points]
          (into #{} content-points))

        last-p
        (->> content last path.helpers/segment->point)

        handlers
        (mf/with-memo [content]
          (path/get-handlers content))

        is-path-start
        (not (some? last-point))

        show-snap?
        (and ^boolean snap-toggled
             (or (some? drag-handler)
                 (some? preview)
                 (some? moving-handler)
                 moving-nodes))]

    (mf/with-layout-effect [edit-mode]
      (let [key (events/listen (dom/get-root) "dblclick"
                               #(when (= edit-mode :move)
                                  (st/emit! :interrupt)))]
        #(events/unlistenByKey key)))

    [:g.path-editor {:ref editor-ref}
     [:path {:d (.toString content)
             :style {:fill "none"
                     :stroke accent-color
                     :strokeWidth (/ 1 zoom)}}]
     (for [{:keys [index] :as entry} segment-entries]
       [:> path-segment*
        {:key (dm/str "segment-" index)
         :entry entry
         :zoom zoom
         :edit-mode edit-mode
         :is-selected (contains? selected-segments index)
         :is-hover (contains? hover-segments index)}])
     (when (and preview (not drag-handler))
       [:> path-preview* {:segment preview
                          :from last-p
                          :zoom zoom}])

     (when (and drag-handler last-p)
       [:g.drag-handler {:pointer-events "none"}
        [:> path-handler* {:point last-p
                           :handler drag-handler
                           :edit-mode edit-mode
                           :zoom zoom}]])

     (for [[index position] node-entries]
       (let [show-handler?
             (fn [[index prefix]]
               ;; FIXME: get-handler-point is executed twice for each
               ;; render, this can be optimized
               (let [handler-position (path/get-handler-point content index prefix)]
                 (not= position handler-position)))

             position-handlers
             (->> (get handlers position)
                  (filter show-handler?)
                  (not-empty))

             point-selected?
             (contains? selected-nodes index)

             point-hover?
             (contains? hover-nodes index)

             is-last
             (= last-point position)

             is-curve
             (boolean position-handlers)]

         [:g.path-node {:key (dm/str "node-" index)}
          [:g.point-handlers {:pointer-events (when (= edit-mode :draw) "none")}
           (for [[hindex prefix] position-handlers]
             (let [handler-position  (path/get-handler-point content hindex prefix)
                   handler-hover?    (contains? hover-handlers [hindex prefix])
                   moving-handler?   (= handler-position moving-handler)
                   matching-handler? (matching-handler? content position position-handlers)]

               (when (and position handler-position)
                 [:> path-handler*
                  {:key (dm/str hindex "-" (d/name prefix))
                   :point position
                   :handler handler-position
                   :index hindex
                   :prefix prefix
                   :zoom zoom
                   :is-selected (contains? selected-handlers [hindex prefix])
                   :is-hover handler-hover?
                   :snap-angle (and moving-handler? matching-handler?)
                   :edit-mode edit-mode}])))]

          [:> path-point* {:index index
                           :position position
                           :zoom zoom
                           :edit-mode edit-mode
                           :is-selected point-selected?
                           :is-hover point-hover?
                           :is-last is-last
                           :is-start-path is-path-start
                           :is-curve is-curve}]]))

     (when (and prev-handler last-p)
       [:g.prev-handler {:pointer-events "none"}
        [:> path-handler*
         {:point last-p
          :edit-mode edit-mode
          :handler prev-handler
          :zoom zoom}]])

     (when ^boolean show-snap?
       (let [moving-node-indices (into selected-nodes selected-segment-nodes)
             selected-positions (dwp.helpers/node-positions content moving-node-indices)
             [snap-selected snap-points]
             (cond
               (some? drag-handler) [#{drag-handler} points]
               (some? preview) [#{(path.helpers/segment->point preview)} points]
               (some? moving-handler) [#{moving-handler} points]
               :else
               [selected-positions
                (->> points (remove selected-positions) (into #{}))])]
         [:g.path-snap {:pointer-events "none"}
          [:> path-snap* {:selected snap-selected
                          :points snap-points
                          :zoom zoom}]]))]))
