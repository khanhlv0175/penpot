;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.components-debugger
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.common.types.component :as ctk]
   [app.common.types.container :as ctn]
   [app.common.types.file :as ctf]
   [app.main.refs :as refs]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.foundations.assets.icon :as i :refer [icon*]]
   [app.main.ui.hooks :as hooks]
   [app.main.ui.hooks.floating-drag :as fd]
   [app.util.debug :as dbg]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [app.util.shape-icon :as usi]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(def ^:private panel-width 1024)
(def ^:private panel-height 768)
(def ^:private sidebar-offset 290)
(def ^:private min-z-index 300)

(def ^:private preview-x 16)
(def ^:private preview-y 16)
(def ^:private preview-width 400)
(def ^:private panels-gap 24)
(def ^:private text-padding 8)
(def ^:private text-line-height 16)
(def ^:private icon-size 16)
(def ^:private icon-gap 4)
(def ^:private labels-title-gap 12)
(def ^:private inner-padding 8)
(def ^:private child-gap 20)
(def ^:private box-min-height 40)

(defn- canvas-content-width
  [near-count]
  (let [column-step (+ preview-x preview-width panels-gap)]
    (+ (if (zero? near-count)
         preview-x
         (* near-count column-step))
       preview-width
       preview-x)))

(defn- initial-position
  []
  {:top 40
   :left (- (:width (dom/get-window-size)) panel-width sidebar-offset)})

(defn- shape-box-labels
  [shape & {:keys [file-id page-id]}]
  (cond-> []
    file-id
    (conj (str "File Id: " file-id))

    page-id
    (conj (str "Page Id: " page-id))))

(defn- labels-bg-height
  [label-count]
  (when (pos? label-count)
    (+ text-padding
       (* label-count text-line-height)
       text-padding)))

(defn- box-header-height
  [label-count]
  (+ (if-let [labels-height (labels-bg-height label-count)]
       (+ labels-height labels-title-gap)
       text-padding)
     icon-size
     text-padding))

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

(defn- enrich-near-component-data
  [near-data {:keys [file page]}]
  (when near-data
    (let [shape (:shape near-data)]
      (assoc near-data
             :root-shape (ctn/get-instance-root (:objects near-data) shape)
             :file-id (:id file)
             :page-id (:id page)))))

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
                            {:file file :page page}))]
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
   (shape-box-height objects shape width (shape-box-labels shape)))
  ([objects shape width labels]
   (let [header   (box-header-height (count labels))
         children (shape-children objects shape)]
     (if (empty? children)
       (max box-min-height header)
       (let [child-width (child-content-width width)
             children-height
             (->> children
                  (map #(shape-box-height objects % child-width))
                  (interpose child-gap)
                  (reduce + 0))]
         (+ header (* 2 inner-padding) children-height))))))

(defn- shape-child-layouts
  [objects shape x y width labels]
  (let [header    (box-header-height (count labels))
        children  (reverse (shape-children objects shape))
        {:keys [left]} (child-insets)
        inner-x   (+ x left)
        inner-w   (child-content-width width)
        start-y   (+ y header inner-padding)]
    (loop [cy start-y
           [child & rest] children
           result []]
      (if child
        (let [child-height (shape-box-height objects child inner-w)]
          (recur (+ cy child-height child-gap)
                 rest
                 (conj result {:shape child :x inner-x :y cy :width inner-w})))
        result))))

(mf/defc labeled-rect*
  [{:keys [x y width height name labels icon-id icon-component? selected?]}]
  (let [has-icon?     (some? icon-id)
        label-count   (count labels)
        text-x        (+ x text-padding)
        text-x-right  (- (+ x width) text-padding)
        title-y       (+ y
                         (if-let [labels-height (labels-bg-height label-count)]
                           (+ labels-height labels-title-gap)
                           text-padding))]
    [:g
     [:rect {:x x
             :y y
             :width width
             :height height
             :class (stl/css-case :selection-preview-box true
                                  :selected selected?)}]

     (when-let [labels-height (labels-bg-height label-count)]
       [:rect {:x x
               :y y
               :width width
               :height labels-height
               :class (stl/css :info-labels-bg)}])

     (for [[idx label] (map-indexed vector labels)
           :let [text (if (string? label) label (str label))]]
       ^{:key idx}
       [:text {:x text-x-right
               :y (+ y text-padding (* idx text-line-height))
               :text-anchor "end"
               :dominant-baseline "hanging"
               :class (stl/css :info-text)}
        text])

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
  [{:keys [shape objects x y width selected-id labels]}]
  (let [labels           (or labels (shape-box-labels shape))
        height           (shape-box-height objects shape width labels)
        child-layouts    (shape-child-layouts objects shape x y width labels)
        selected?        (= (:id shape) selected-id)
        icon-component?  (or (ctk/instance-head? shape)
                             (ctk/is-variant-container? shape))]
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
       :selected? selected?}]
     (for [{:keys [shape x y width]} child-layouts]
       ^{:key (dm/str (:id shape))}
       [:> shape-box*
        {:shape shape
         :objects objects
         :x x
         :y y
         :width width
         :selected-id selected-id}])]))

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

        canvas-width
        (canvas-content-width (if near-component-chain
                                (count near-component-chain)
                                0))

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
                  :height "100%"}
            (when (some? root-shape)
              [:g
               [:> shape-box*
                {:shape root-shape
                 :objects objects
                 :selected-id selected-id
                 :labels (shape-box-labels root-shape
                                           :file-id (:id file)
                                           :page-id (:id page))
                 :x preview-x
                 :y preview-y
                 :width preview-width}]
               (for [[idx {:keys [root-shape objects highlight-id file-id page-id]}]
                     (map-indexed vector near-component-chain)
                     :let [column-x (* (inc idx) (+ preview-x preview-width panels-gap))]]
                 ^{:key (dm/str "near-" (inc idx) "-" (:id root-shape))}
                 [:> shape-box*
                  {:shape root-shape
                   :objects objects
                   :selected-id highlight-id
                   :labels (shape-box-labels root-shape
                                             :file-id file-id
                                             :page-id page-id)
                   :x column-x
                   :y preview-y
                   :width preview-width}])])]]]])
       container))))
