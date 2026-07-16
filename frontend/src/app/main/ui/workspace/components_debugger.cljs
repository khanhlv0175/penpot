;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.components-debugger
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.common.thumbnails :as thc]
   [app.common.types.component :as ctk]
   [app.common.types.container :as ctn]
   [app.common.types.file :as ctf]
   [app.config :as cf]
   [app.main.data.workspace.thumbnails :as dwt]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.foundations.assets.icon :as i :refer [icon*]]
   [app.main.ui.hooks :as hooks]
   [app.main.ui.hooks.floating-drag :as fd]
   [app.util.debug :as dbg]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [app.util.shape-icon :as usi]
   [app.util.timers :as tm]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(def ^:private panel-width 1024)
(def ^:private panel-height 768)
(def ^:private sidebar-offset 290)
(def ^:private min-z-index 300)

(def ^:private preview-x 16)
(def ^:private preview-y 16)
(def ^:private preview-width 400)
(def ^:private panels-gap 56)
(def ^:private marker-radius 11)
(def ^:private marker-dot-radius 4)
(def ^:private text-padding 8)
(def ^:private text-line-height 16)
(def ^:private icon-size 16)
(def ^:private labels-title-gap 12)
(def ^:private thumbnail-height 80)
(def ^:private thumbnail-margin 8)
(def ^:private inner-padding 8)
(def ^:private child-gap 20)
(def ^:private box-min-height 40)
(def ^:private legend-gap 20)
(def ^:private legend-row-gap 24)
(def ^:private legend-line-width 36)
(def ^:private legend-width 220)
(def ^:private legend-padding-x 12)
(def ^:private legend-padding-y 10)

(defn- canvas-content-width
  [column-count]
  (let [column-step (+ preview-x preview-width panels-gap)]
    (+ (* (dec column-count) column-step)
       preview-width
       (* 2 preview-x))))

(defn- column-x
  [column-idx]
  (+ preview-x (* column-idx (+ preview-width panels-gap))))

(defn- legend-total-height
  "Height of the arrows legend (fixed two-row layout)."
  []
  (+ (* 2 legend-padding-y) legend-row-gap (* 2 marker-radius)))

(defn- initial-position
  []
  {:top 40
   :left (- (:width (dom/get-window-size)) panel-width sidebar-offset)})

(defn- shape-box-labels
  [_shape & {:keys [file-id page-id file-name page-name]}]
  (cond-> []
    file-name
    (conj (str "File: " file-name))

    file-id
    (conj (str "File Id: " file-id))

    page-name
    (conj (str "Page: " page-name))

    page-id
    (conj (str "Page Id: " page-id))))

(defn- thumbnail-slot-height
  []
  (+ thumbnail-margin thumbnail-height thumbnail-margin))

(defn- shape-thumbnail-slot?
  [shape {:keys [file-id page-id]}]
  (and file-id page-id
       (or (cfh/frame-shape? shape)
           (ctk/instance-root? shape)
           (ctk/main-instance? shape))))

(defn- box-thumbnail-slot?
  "A box reserves a thumbnail slot when it is the root of a panel or the
   highlighted shape, and the shape itself qualifies for a thumbnail."
  [shape root? highlight-id thumbnail-context]
  (and (some? thumbnail-context)
       (or root? (= (:id shape) highlight-id))
       (shape-thumbnail-slot? shape thumbnail-context)))

(defn- labels-bg-height
  [label-count]
  (when (pos? label-count)
    (+ text-padding
       (* label-count text-line-height)
       text-padding)))

(defn- box-header-height
  [_label-count thumbnail-slot?]
  (+ (if thumbnail-slot?
       (+ thumbnail-margin
          (thumbnail-slot-height)
          labels-title-gap)
       text-padding)
     icon-size
     text-padding))

(defn- labels-footer-height
  [label-count]
  (if (pos? label-count)
    (+ labels-title-gap
       (labels-bg-height label-count))
    0))

(defn- header-thumbnail-y
  [y _label-count]
  (+ y thumbnail-margin))

(defn- header-title-y
  [y _label-count thumbnail-slot?]
  (+ y
     (if thumbnail-slot?
       (+ thumbnail-margin
          (thumbnail-slot-height)
          labels-title-gap)
       text-padding)))

(defn- shape-children
  [objects shape]
  (->> (:shapes shape)
       (map #(get objects %))
       (remove nil?)))

(defn- shape-display-label
  [shape]
  (let [name     (:name shape)
        short-name (if (< (count name) 24)
                     name
                     (str (str/slice name 0 22) "..."))
        short-id (str/slice (str (:id shape)) 24)]
    (str short-name " | " short-id)))

(defn- near-match-source-shape
  "Shape used to resolve the near component match."
  [objects shape]
  (if (and (not (ctk/in-component-copy? shape))
           (ctn/has-any-copy-parent? objects shape))
    (last (ctn/get-parent-copy-heads objects shape))
    shape))

(defn- ref-shape->component-head
  [ref-shape]
  (when ref-shape
    (let [meta'     (meta ref-shape)
          container (:container meta')
          objects   (:objects container)
          head      (or (last (ctn/get-parent-heads objects ref-shape))
                        ref-shape)]
      (cond-> head
        meta' (with-meta meta')))))

(defn- near-component-render-data
  [near-head ref-shape]
  (when near-head
    (let [container (-> near-head meta :container)]
      {:shape near-head
       :objects (:objects container)
       :highlight-id (:id ref-shape)})))


(defn- resolve-near-component-shape
  [file page libraries objects shape]
  (when-let [source (near-match-source-shape objects shape)]
    (when-let [ref-shape (or (ctf/find-near-match file page libraries source :with-context? true)
                             (ctf/find-ref-shape file page libraries source :with-context? true))]
      (near-component-render-data (ref-shape->component-head ref-shape)
                                  ref-shape))))

(defn- resolve-file-name
  "File objects from with-context metadata often lack :name; look it up."
  [file libraries]
  (or (:name file)
      (when-let [file-id (:id file)]
        (get-in libraries [file-id :name]))))

(defn- enrich-near-component-data
  [near-data {:keys [file page]} libraries]
  (when near-data
    (let [shape      (:shape near-data)
          meta'      (meta shape)
          ;; Prefer the matched shape's file/page (where the tree lives).
          match-file (or (:file meta') file)
          match-page (or (:container meta') page)
          file-id    (:id match-file)]
      (assoc near-data
             :root-shape (ctn/get-instance-root (:objects near-data) shape)
             :file       match-file
             :page       match-page
             :file-name  (resolve-file-name match-file libraries)
             :file-id    file-id
             :page-name  (:name match-page)
             :page-id    (:id match-page)))))

(defn- resolve-swap-slot-main-data
  "When the highlighted shape has a swap-slot, return its component main tree."
  [file page libraries objects highlight-id]
  (when-let [shape (get objects highlight-id)]
    (when (ctk/get-swap-slot shape)
      (when-let [component (ctf/find-ref-component file page libraries shape
                                                   :include-deleted? true)]
        (let [head           (ctn/get-head-shape objects shape)
              component-file (ctf/find-component-file file libraries (:component-file head))
              file-data      (:data component-file)
              container      (ctf/get-component-container file-data component)
              root-shape     (ctf/get-component-root file-data component)
              component-page (ctf/get-component-page file-data component)
              highlight-main-id
              (or (ctf/find-ref-id-for-swapped shape page libraries)
                  (some-> (ctf/get-ref-shape file-data component shape) :id)
                  (ctk/get-swap-slot shape))]
          (when root-shape
            {:root-shape root-shape
             :objects (:objects container)
             :highlight-id highlight-main-id
             :file-name (resolve-file-name component-file libraries)
             :file-id (:id component-file)
             :page-name (:name component-page)
             :page-id (:id component-page)}))))))

(defn- near-match-context
  "Resolve file/page/objects for near-match lookup from shape metadata,
   falling back to the workspace context."
  [shape workspace-file workspace-page workspace-objects]
  (let [meta' (meta shape)]
    (if-let [container (:container meta')]
      {:file    (or (:file meta') workspace-file)
       :page    container
       :objects (:objects container)}
      {:file    workspace-file
       :page    workspace-page
       :objects workspace-objects})))

(defn- near-component-data-chain
  "Resolve near-component matches recursively until no more matches."
  [workspace-file workspace-page libraries workspace-objects initial-shape]
  (loop [shape  initial-shape
         result []]
    (let [{:keys [file page objects]}
          (near-match-context shape workspace-file workspace-page workspace-objects)]
      (if-let [near-data (when (and shape file page)
                           (enrich-near-component-data
                            (resolve-near-component-shape file page libraries objects shape)
                            {:file file :page page}
                            libraries))]
        (recur (:shape near-data) (conj result near-data))
        result))))

(defn- child-insets
  []
  {:left (* 2 inner-padding)
   :right inner-padding})

(defn- child-content-width
  [width]
  (let [{:keys [left right]} (child-insets)]
    (- width left right)))

(defn- shape-box-height
  ([objects shape width]
   (shape-box-height objects shape width nil nil nil false))
  ([objects shape width labels thumbnail-context highlight-id root?]
   (let [thumbnail-slot? (box-thumbnail-slot? shape root? highlight-id thumbnail-context)
         label-count (count labels)
         header      (box-header-height label-count thumbnail-slot?)
         footer      (labels-footer-height label-count)
         children (shape-children objects shape)]
     (if (empty? children)
       (max box-min-height (+ header footer))
       (let [child-width (child-content-width width)
             children-height
             (->> children
                  (map #(shape-box-height objects % child-width nil thumbnail-context highlight-id false))
                  (interpose child-gap)
                  (reduce + 0))]
         (+ header (* 2 inner-padding) children-height footer))))))

(defn- shape-child-layouts
  [objects shape x y width labels thumbnail-context highlight-id root?]
  (let [thumbnail-slot? (box-thumbnail-slot? shape root? highlight-id thumbnail-context)
        header    (box-header-height (count labels) thumbnail-slot?)
        children  (reverse (shape-children objects shape))
        {:keys [left]} (child-insets)
        inner-x   (+ x left)
        inner-w   (child-content-width width)
        start-y   (+ y header inner-padding)]
    (loop [cy start-y
           [child & rest] children
           result []]
      (if child
        (let [child-height (shape-box-height objects child inner-w nil thumbnail-context highlight-id false)]
          (recur (+ cy child-height child-gap)
                 rest
                 (conj result {:shape child :x inner-x :y cy :width inner-w})))
        result))))

(defn- root-panel-height
  [objects root-shape labels thumbnail-context highlight-id]
  (shape-box-height objects root-shape preview-width labels thumbnail-context highlight-id true))

(defn- swap-slot-panel
  "Build a swap-main panel for the highlight at `column-idx`.
   The panel is drawn one column to the right (`(inc column-idx)`), so
   arrows pair it with `highlight-panels` via `(dec column-idx)`."
  [column-idx swap-row-y file page libraries objects highlight-id]
  (when-let [{:keys [root-shape objects highlight-id
                     file-id page-id file-name page-name]}
             (resolve-swap-slot-main-data file page libraries objects highlight-id)]
    {:column-idx (inc column-idx)
     :y swap-row-y
     :root-shape root-shape
     :objects objects
     :highlight-id highlight-id
     :file-name file-name
     :file-id file-id
     :page-name page-name
     :page-id page-id
     :labels (shape-box-labels root-shape
                               :file-name file-name
                               :file-id file-id
                               :page-name page-name
                               :page-id page-id)}))

(defn- shape-highlight-anchor
  "Return {:left :right :y} for the highlighted shape box."
  [objects shape x y width labels thumbnail-context highlight-id root?]
  (if (= (:id shape) highlight-id)
    (let [height (shape-box-height objects shape width labels thumbnail-context highlight-id root?)]
      {:left x
       :right (+ x width)
       :y (+ y (/ height 2))})
    (some (fn [{:keys [shape x y width]}]
            (shape-highlight-anchor objects shape x y width nil thumbnail-context highlight-id false))
          (shape-child-layouts objects shape x y width labels thumbnail-context highlight-id root?))))

(defn- panel-highlight-anchor
  [{:keys [objects root-shape highlight-id labels thumbnail-context panel-x panel-y]}]
  (when highlight-id
    (shape-highlight-anchor objects root-shape panel-x panel-y preview-width
                            labels thumbnail-context highlight-id true)))

(defn- navigate-arrow-path
  [_arrow-dir]
  "M -6.5 0 L 5.5 0 M 6.715 0.715 L -0.5 -6.5 M 6.715 -0.715 L -0.365 6.635")

(defn- highlight-arrow-path
  [from-anchor to-anchor]
  (let [x1 (+ (:right from-anchor) marker-dot-radius)
        y1 (:y from-anchor)
        x2 (- (:left to-anchor) marker-radius)
        y2 (:y to-anchor)
        mid-x (/ (+ x1 x2) 2)]
    (dm/str "M " x1 " " y1
            " C " mid-x " " y1 ", "
            mid-x " " y2 ", "
            x2 " " y2)))

(defn- arrow-marker-styles
  [variant]
  (case variant
    :swap {:circle (stl/css :swap-arrow-marker-circle)
           :icon (stl/css :swap-arrow-marker-icon)
           :path (stl/css :swap-arrow)}
    {:circle (stl/css :highlight-arrow-marker-circle)
     :icon (stl/css :highlight-arrow-marker-icon)
     :path (stl/css :highlight-arrow)}))

(defn- swap-panel-as-highlight-panel
  [{:keys [column-idx y objects root-shape labels file-id page-id]}]
  {:objects objects
   :root-shape root-shape
   :highlight-id (:id root-shape)
   :labels labels
   :thumbnail-context {:file-id file-id :page-id page-id}
   :panel-x (column-x column-idx)
   :panel-y y})

(defn- build-swap-panel-pairs
  [highlight-panels swap-panels]
  (keep (fn [swap]
          (when-let [from (nth highlight-panels (dec (:column-idx swap)) nil)]
            [from (swap-panel-as-highlight-panel swap)]))
        swap-panels))

(mf/defc highlight-arrow-marker*
  [{:keys [x y arrow-dir? circle-class icon-class]}]
  [:g {:transform (dm/str "translate(" x "," y ")")}
   [:circle {:cx 0
             :cy 0
             :r (if arrow-dir? marker-radius marker-dot-radius)
             :class circle-class}]
   (when arrow-dir?
     [:path {:d (navigate-arrow-path arrow-dir?)
             :class icon-class}])])

(mf/defc connection-arrows*
  [{:keys [panel-pairs variant]}]
  (let [{:keys [circle icon path]} (arrow-marker-styles variant)]
    (for [[idx [from-panel to-panel]] (map-indexed vector panel-pairs)
          :let [from (panel-highlight-anchor from-panel)
                to (panel-highlight-anchor to-panel)]
          :when (and from to)]
      ^{:key (dm/str (name variant) "-arrow-" idx)}
      [:g
       [:path {:d (highlight-arrow-path from to)
               :class path
               :fill "none"}]
       [:> highlight-arrow-marker*
        {:x (+ (:right from) marker-dot-radius)
         :y (:y from)
         :circle-class circle}]
       [:> highlight-arrow-marker*
        {:x (- (:left to) marker-radius 2)
         :y (:y to)
         :arrow-dir? :right
         :circle-class circle
         :icon-class icon}]])))

(mf/defc highlight-arrows*
  [{:keys [highlight-pairs swap-pairs]}]
  [:g {:class (stl/css :highlight-arrows)}
   [:> connection-arrows* {:panel-pairs highlight-pairs :variant :highlight}]
   [:> connection-arrows* {:panel-pairs swap-pairs :variant :swap}]])

(mf/defc arrows-legend*
  [{:keys [x y]}]
  (let [rows [{:variant :highlight :label "Near shape ref"}
              {:variant :swap :label "Swapped by"}]
        row-count (count rows)
        row-start-y (+ y legend-padding-y marker-radius)
        marker-start-x (+ x legend-padding-x)
        marker-end-x (+ marker-start-x legend-line-width)
        legend-height (+ (* 2 legend-padding-y)
                         (* (dec row-count) legend-row-gap)
                         (* 2 marker-radius))]
    [:g {:class (stl/css :highlight-arrows)}
     [:rect {:x x
             :y y
             :width legend-width
             :height legend-height
             :rx 6
             :ry 6
             :class (stl/css :legend-bg)}]
     (for [[idx {:keys [variant label]}] (map-indexed vector rows)
           :let [row-y (+ row-start-y (* idx legend-row-gap))
                 {:keys [circle icon path]} (arrow-marker-styles variant)
                 marker-x marker-end-x]]
       ^{:key (dm/str "legend-" (name variant))}
       [:g
        [:path {:d (dm/str "M " marker-start-x " " row-y " L " marker-x " " row-y)
                :class path
                :fill "none"}]
        [:> highlight-arrow-marker*
         {:x marker-start-x
          :y row-y
          :circle-class circle}]
        [:> highlight-arrow-marker*
         {:x marker-x
          :y row-y
          :arrow-dir? :right
          :circle-class circle
          :icon-class icon}]
        [:text {:x (+ marker-x 18)
                :y row-y
                :dominant-baseline "middle"
                :class (stl/css :legend-text)}
         label]])]))

(mf/defc labeled-rect*
  [{:keys [x y width height name labels icon-id icon-component? selected?
           thumbnail-uri thumbnail-slot?]}]
  (let [has-icon?      (some? icon-id)
        label-count    (count labels)
        labels-height  (labels-bg-height label-count)
        labels-y       (when labels-height
                         (- (+ y height) labels-height))
        text-x         (+ x text-padding)
        thumbnail-slot-y (header-thumbnail-y y label-count)
        thumbnail-y      (+ thumbnail-slot-y thumbnail-margin)
        thumbnail-w      (- width (* 2 text-padding))
        title-y        (header-title-y y label-count thumbnail-slot?)]
    [:g
     [:rect {:x x
             :y y
             :width width
             :height height
             :class (stl/css-case :selection-preview-box true
                                  :selected selected?)}]

     (when labels-y
       [:rect {:x x
               :y labels-y
               :width width
               :height labels-height
               :class (stl/css :info-labels-bg)}])

     (for [[idx label] (map-indexed vector labels)
           :let [text (if (string? label) label (str label))]]
       ^{:key idx}
       [:text {:x text-x
               :y (+ labels-y text-padding (* idx text-line-height))
               :text-anchor "start"
               :dominant-baseline "hanging"
               :class (stl/css :info-text)}
        text])

     (when thumbnail-slot?
       [:g
        [:rect {:x (+ x text-padding)
                :y thumbnail-y
                :width thumbnail-w
                :height thumbnail-height
                :class (stl/css-case :shape-thumbnail-bg true
                                     :is-loading (nil? thumbnail-uri))}]
        (if thumbnail-uri
          [:image {:x (+ x text-padding)
                   :y thumbnail-y
                   :width thumbnail-w
                   :height thumbnail-height
                   :href thumbnail-uri
                   :preserveAspectRatio "xMidYMid meet"
                   :class (stl/css :shape-thumbnail-image)}]
          [:text {:x (+ x text-padding (/ thumbnail-w 2))
                  :y (+ thumbnail-y (/ thumbnail-height 2))
                  :text-anchor "middle"
                  :dominant-baseline "middle"
                  :class (stl/css :shape-thumbnail-loading)}
           "Loading…"])])

     (when has-icon?
       [:g {:transform (dm/str "translate(" text-x "," title-y ")")}
        [:> icon* {:icon-id icon-id
                   :size "m"
                   :class (stl/css-case :selection-preview-icon true
                                        :component icon-component?)}]])
     [:text {:x (+ text-x 20)
             :y title-y
             :dominant-baseline "hanging"
             :class (stl/css :selection-preview-text)}
      name]]))

(mf/defc shape-box*
  [{:keys [shape objects x y width selected-id labels thumbnail-context root?]}]
  (let [labels            (or labels (shape-box-labels shape))
        thumbnail-slot?   (box-thumbnail-slot? shape root? selected-id thumbnail-context)
        shape-id          (:id shape)
        file-id           (:file-id thumbnail-context)
        page-id           (:page-id thumbnail-context)
        file              (:file thumbnail-context)
        libraries         (:libraries thumbnail-context)
        frame-thumb-ref   (mf/with-memo [file-id page-id shape-id]
                            (refs/workspace-thumbnail-by-id
                             (thc/fmt-object-id file-id page-id shape-id "frame")))
        component-thumb-ref (mf/with-memo [file-id page-id shape-id]
                              (refs/workspace-thumbnail-by-id
                               (thc/fmt-object-id file-id page-id shape-id "component")))
        ;; Component copies don't have a thumbnail keyed by their own id
        ;; (those live under the main instance), so fall back to the main
        ;; component thumbnail when the shape is a component instance.
        main-comp-object-id
        (when (and file (ctk/instance-head? shape))
          (when-let [component (ctf/resolve-component shape file libraries)]
            (let [component-file (ctf/find-component-file file libraries (:component-file shape))]
              (thc/fmt-object-id (:id component-file)
                                 (:main-instance-page component)
                                 (:main-instance-id component)
                                 "component"))))
        main-comp-ref     (mf/with-memo [main-comp-object-id]
                            (refs/workspace-thumbnail-by-id main-comp-object-id))
        frame-thumb       (mf/deref frame-thumb-ref)
        component-thumb   (mf/deref component-thumb-ref)
        main-comp-thumb   (mf/deref main-comp-ref)
        thumbnail-uri     (when thumbnail-slot?
                            (or (:uri frame-thumb)
                                (:uri component-thumb)
                                (:uri main-comp-thumb)
                                (when-let [thumb-id (:thumbnail-id shape)]
                                  (cf/resolve-media thumb-id))))
        thumbnail-tag     (if (cfh/frame-shape? shape) "frame" "component")
        height            (shape-box-height objects shape width labels thumbnail-context selected-id root?)
        child-layouts     (shape-child-layouts objects shape x y width labels thumbnail-context selected-id root?)
        selected?         (= (:id shape) selected-id)
        icon-component?   (or (ctk/instance-head? shape)
                              (ctk/is-variant-container? shape))]

    ;; Generate a missing thumbnail on demand (same path as root-frame
    ;; wrappers). Only for shapes that already get a thumbnail slot.
    (mf/with-effect [thumbnail-slot? thumbnail-uri file-id page-id shape-id thumbnail-tag]
      (when (and thumbnail-slot?
                 (nil? thumbnail-uri)
                 file-id page-id shape-id)
        (tm/schedule-on-idle
         #(st/emit! (dwt/update-thumbnail file-id page-id shape-id thumbnail-tag
                                          "components-debugger")))))

    [:g
     [:> labeled-rect*
      {:x x
       :y y
       :width width
       :height height
       :name (shape-display-label shape)
       :labels labels
       :icon-id (usi/get-shape-icon shape)
       :icon-component? icon-component?
       :selected? selected?
       :thumbnail-uri thumbnail-uri
       :thumbnail-slot? thumbnail-slot?}]
     (for [{:keys [shape x y width]} child-layouts]
       ^{:key (dm/str (:id shape))}
       [:> shape-box*
        {:shape shape
         :objects objects
         :x x
         :y y
         :width width
         :selected-id selected-id
         :thumbnail-context thumbnail-context}])]))

(mf/defc components-debugger*
  []
  (let [container (hooks/use-portal-container :popup)

        _dbg (mf/deref dbg/state)
        visible? (dbg/enabled? :components-debugger)

        wrapper-ref (mf/use-ref nil)
        z-index-ref (mf/use-ref min-z-index)

        selected (mf/deref refs/selected-shapes)
        objects  (mf/deref refs/workspace-page-objects)
        file     (mf/deref refs/file)
        page     (mf/deref refs/workspace-page)
        libraries (mf/deref refs/files)

        selected-id
        (when (= 1 (count selected))
          (first selected))

        selected-shape
        (when selected-id
          (get objects selected-id))

        root-shape
        (when selected-shape
          (ctn/get-instance-root objects selected-shape))

        near-component-chain
        (when (and selected-shape file page)
          (near-component-data-chain file page libraries objects selected-shape))

        selection-labels
        (when root-shape
          (shape-box-labels root-shape
                            :file-name (:name file)
                            :file-id (:id file)
                            :page-name (:name page)
                            :page-id (:id page)))

        selection-panel-height
        (when root-shape
          (root-panel-height objects root-shape selection-labels
                             {:file-id (:id file) :page-id (:id page)}
                             selected-id))

        legend-x
        (+ preview-x (/ (- preview-width legend-width) 2))

        legend-y
        (when selection-panel-height
          (+ preview-y selection-panel-height legend-gap))

        first-row-heights
        (cond-> []
          selection-panel-height
          (conj selection-panel-height)

          near-component-chain
          (into (map (fn [{:keys [root-shape objects highlight-id
                                  file-name file-id page-name page-id]}]
                       (root-panel-height objects root-shape
                                          (shape-box-labels root-shape
                                                            :file-name file-name
                                                            :file-id file-id
                                                            :page-name page-name
                                                            :page-id page-id)
                                          {:file-id file-id :page-id page-id}
                                          highlight-id))
                     near-component-chain)))

        swap-row-y
        (when (seq first-row-heights)
          (+ preview-y (apply max first-row-heights) panels-gap))

        selection-swap-panel
        (when (and root-shape selected-id swap-row-y)
          (swap-slot-panel 0 swap-row-y
                           file page libraries objects selected-id))

        near-swap-panels
        (when (and near-component-chain swap-row-y)
          (keep-indexed
           ;; Near columns are 1-based (selection is column 0), matching
           ;; swap-slot-panel's (inc column-idx) placement and arrow pairing.
           (fn [idx {:keys [highlight-id file page objects]}]
             (swap-slot-panel (inc idx) swap-row-y
                              file page libraries objects highlight-id))
           near-component-chain))

        swap-panels
        (cond-> []
          selection-swap-panel (conj selection-swap-panel)
          (seq near-swap-panels) (into near-swap-panels))

        primary-column-count
        (if near-component-chain
          (inc (count near-component-chain))
          (if root-shape 1 0))

        max-column-idx
        (reduce max (dec primary-column-count)
                (map :column-idx swap-panels))

        canvas-width
        (canvas-content-width (inc max-column-idx))

        swap-panel-heights
        (map (fn [{:keys [objects root-shape labels highlight-id file-id page-id]}]
               (root-panel-height objects root-shape labels
                                  {:file-id file-id :page-id page-id}
                                  highlight-id))
             swap-panels)

        canvas-height
        (let [first-row-bottom (when (seq first-row-heights)
                                 (+ preview-y (apply max first-row-heights)))
              legend-bottom    (when legend-y
                                 (+ legend-y (legend-total-height)))
              swap-bottom      (when (and swap-row-y (seq swap-panel-heights))
                                 (+ swap-row-y (apply max swap-panel-heights)))
              bottoms          (remove nil? [first-row-bottom legend-bottom swap-bottom])]
          (when (seq bottoms)
            (+ (apply max bottoms) preview-y)))

        highlight-panels
        (cond-> []
          (and root-shape selected-id)
          (conj {:objects objects
                 :root-shape root-shape
                 :highlight-id selected-id
                 :labels selection-labels
                 :thumbnail-context {:file-id (:id file) :page-id (:id page)}
                 :panel-x preview-x
                 :panel-y preview-y})

          near-component-chain
          (into (map-indexed
                 (fn [idx {:keys [root-shape objects highlight-id
                                  file-name file-id page-name page-id]}]
                   {:objects objects
                    :root-shape root-shape
                    :highlight-id highlight-id
                    :labels (shape-box-labels root-shape
                                              :file-name file-name
                                              :file-id file-id
                                              :page-name page-name
                                              :page-id page-id)
                    :thumbnail-context {:file-id file-id :page-id page-id}
                    :panel-x (column-x (inc idx))
                    :panel-y preview-y})
                 near-component-chain)))

        bring-to-front
        (mf/use-fn
         (fn []
           (let [target (mf/ref-val wrapper-ref)]
             (when target
               (mf/set-ref-val! z-index-ref (inc (mf/ref-val z-index-ref)))
               (dom/set-css-property! target "z-index"
                                      (dm/str (mf/ref-val z-index-ref)))))))

        {:keys [on-pointer-down on-pointer-move on-pointer-up on-lost-pointer-capture]}
        (fd/use-floating-drag wrapper-ref
                              bring-to-front
                              (stl/css :is-dragging))

        {:keys [top left]}
        (initial-position)

        handle-close
        (mf/use-fn
         (fn []
           (dbg/disable! :components-debugger)))]

    (when visible?
      (mf/portal
       (mf/html
        [:div {:ref wrapper-ref
               :class (stl/css :wrapper)
               :style {:position "fixed"
                       :top (dm/str top "px")
                       :left (dm/str left "px")
                       :width (dm/str panel-width "px")
                       :height (dm/str panel-height "px")
                       :z-index min-z-index}}
         [:div {:class (stl/css :inner)}
          [:div {:class (stl/css :header)
                 :on-pointer-down on-pointer-down
                 :on-pointer-move on-pointer-move
                 :on-pointer-up on-pointer-up
                 :on-lost-pointer-capture on-lost-pointer-capture}
           [:h1 {:class (stl/css :title)} "COMPONENTS DEBUGGER"]
           [:> icon-button* {:variant "ghost"
                             :aria-label (tr "labels.close")
                             :on-click handle-close
                             :icon i/close}]]

          [:div {:class (stl/css :canvas)}
           [:svg {:class (stl/css :canvas-svg)
                  :width canvas-width
                  :height (if canvas-height (dm/str canvas-height "px") "100%")}
            (when (some? root-shape)
              [:g
               [:> shape-box*
                {:shape root-shape
                 :objects objects
                 :selected-id selected-id
                 :root? true
                 :thumbnail-context {:file-id (:id file)
                                     :page-id (:id page)
                                     :file file
                                     :libraries libraries}
                 :labels selection-labels
                 :x preview-x
                 :y preview-y
                 :width preview-width}]
               (for [[idx {:keys [root-shape objects highlight-id
                                  file-name file-id page-name page-id]}]
                     (map-indexed vector near-component-chain)
                     :let [column-x (column-x (inc idx))]]
                 ^{:key (dm/str "near-" (inc idx) "-" (:id root-shape))}
                 [:> shape-box*
                  {:shape root-shape
                   :objects objects
                   :selected-id highlight-id
                   :root? true
                   :thumbnail-context {:file-id file-id
                                       :page-id page-id
                                       :file file
                                       :libraries libraries}
                   :labels (shape-box-labels root-shape
                                             :file-name file-name
                                             :file-id file-id
                                             :page-name page-name
                                             :page-id page-id)
                   :x column-x
                   :y preview-y
                   :width preview-width}])
               (for [{:keys [column-idx y root-shape objects highlight-id file-id page-id labels]}
                     swap-panels
                     :let [swap-x (column-x column-idx)]]
                 ^{:key (dm/str "swap-" column-idx "-" (:id root-shape))}
                 [:> shape-box*
                  {:shape root-shape
                   :objects objects
                   :selected-id highlight-id
                   :root? true
                   :thumbnail-context {:file-id file-id
                                       :page-id page-id
                                       :file file
                                       :libraries libraries}
                   :labels labels
                   :x swap-x
                   :y y
                   :width preview-width}])
               (when legend-y
                 [:> arrows-legend* {:x legend-x
                                     :y legend-y}])
               [:> highlight-arrows*
                {:highlight-pairs (map vector highlight-panels (rest highlight-panels))
                 :swap-pairs (build-swap-panel-pairs highlight-panels swap-panels)}]])]]]])
       container))))
