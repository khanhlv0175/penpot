;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.sidebar.options.shapes.path
  (:require
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.common.types.path :as cpath]
   [app.common.types.shape.layout :as ctl]
   [app.main.data.workspace.path :as drp]
   [app.main.data.workspace.path.helpers :as path.helpers]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.workspace.sidebar.options.menus.blur :refer [blur-menu*]]
   [app.main.ui.workspace.sidebar.options.menus.constraints :refer [constraint-attrs constraints-menu*]]
   [app.main.ui.workspace.sidebar.options.menus.exports :refer [exports-menu* exports-attrs]]
   [app.main.ui.workspace.sidebar.options.menus.fill :as fill]
   [app.main.ui.workspace.sidebar.options.menus.grid-cell :as grid-cell]
   [app.main.ui.workspace.sidebar.options.menus.layer :refer [layer-attrs layer-menu*]]
   [app.main.ui.workspace.sidebar.options.menus.layout-container :refer [layout-container-flex-attrs layout-container-menu*]]
   [app.main.ui.workspace.sidebar.options.menus.layout-item :refer [layout-item-attrs layout-item-menu*]]
   [app.main.ui.workspace.sidebar.options.menus.measures :refer [measure-attrs measures-menu* node-position-menu*]]
   [app.main.ui.workspace.sidebar.options.menus.shadow :refer [shadow-menu*]]
   [app.main.ui.workspace.sidebar.options.menus.stroke :refer [stroke-attrs stroke-menu*]]
   [app.main.ui.workspace.sidebar.options.menus.svg-attrs :refer [svg-attrs-menu*]]
   [rumext.v2 :as mf]))

(mf/defc options*
  [{:keys [shape file-id page-id]}]
  (let [id     (dm/get-prop shape :id)
        type   (dm/get-prop shape :type)
        ids    (mf/with-memo [id] [id])
        shapes (mf/with-memo [shape] [shape])

        applied-tokens
        (get shape :applied-tokens)

        measure-values
        (select-keys shape measure-attrs)

        stroke-values
        (select-keys shape stroke-attrs)

        layer-values
        (select-keys shape layer-attrs)

        constraint-values
        (select-keys shape constraint-attrs)

        layout-item-values
        (select-keys shape layout-item-attrs)

        layout-container-values
        (select-keys shape layout-container-flex-attrs)

        is-layout-child-ref
        (mf/with-memo [ids]
          (refs/is-layout-child? ids))

        is-layout-child?
        (mf/deref is-layout-child-ref)

        is-flex-parent-ref
        (mf/with-memo [ids]
          (refs/flex-layout-child? ids))

        is-flex-parent?
        (mf/deref is-flex-parent-ref)

        is-grid-parent-ref
        (mf/with-memo [ids]
          (refs/grid-layout-child? ids))

        is-grid-parent?
        (mf/deref is-grid-parent-ref)

        is-layout-child-absolute?
        (ctl/item-absolute? shape)

        parents-by-ids-ref
        (mf/with-memo [ids]
          (refs/parents-by-ids ids))

        parents
        (mf/deref parents-by-ids-ref)]

    [:*
     [:> layer-menu* {:ids ids
                      :applied-tokens applied-tokens
                      :type type
                      :values layer-values}]
     [:> measures-menu* {:ids ids
                         :type type
                         :applied-tokens applied-tokens
                         :values measure-values
                         :shapes shapes}]

     [:> layout-container-menu*
      {:type type
       :ids [(:id shape)]
       :values layout-container-values
       :applied-tokens applied-tokens
       :multiple false}]

     (when (and (= (count ids) 1) is-layout-child? is-grid-parent?)
       [:> grid-cell/options*
        {:shape-id (-> (first parents)
                       :id)
         :cell (ctl/get-cell-by-shape-id (first parents) (first ids))}])

     (when is-layout-child?
       [:> layout-item-menu* {:ids ids
                              :type type
                              :values layout-item-values
                              :is-layout-child true
                              :is-layout-container false
                              :is-flex-parent is-flex-parent?
                              :is-grid-parent is-grid-parent?
                              :applied-tokens applied-tokens
                              :shape shape}])

     (when (or (not ^boolean is-layout-child?) ^boolean is-layout-child-absolute?)
       [:> constraints-menu* {:ids ids
                              :values constraint-values}])

     [:> fill/fill-menu*
      {:ids ids
       :type type
       :values shape
       :applied-tokens applied-tokens}]

     [:> stroke-menu* {:ids ids
                       :type type
                       :show-caps true
                       :values stroke-values
                       :applied-tokens applied-tokens}]
     [:> shadow-menu* {:ids ids :values (get shape :shadow)}]
     [:> blur-menu* {:ids ids
                     :values (select-keys shape [:blur :background-blur])}]
     [:> svg-attrs-menu* {:ids ids
                          :values (select-keys shape [:svg-attrs])}]
     [:> exports-menu* {:type type
                        :ids ids
                        :shapes shapes
                        :values (select-keys shape exports-attrs)
                        :page-id page-id
                        :file-id file-id}]]))

(mf/defc path-edition-options*
  "Options shown while editing a path's nodes (feature 28): measurements, fill,
  stroke, shadow and blur. Layout, export, constraints, boolean and svg-attrs
  are intentionally omitted; alignment/distribute is rendered separately by the
  design menu's `align-options*`."
  [{:keys [shape]}]
  (let [id     (dm/get-prop shape :id)
        type   (dm/get-prop shape :type)
        ids    (mf/with-memo [id] [id])
        shapes (mf/with-memo [shape] [shape])

        applied-tokens
        (get shape :applied-tokens)

        measure-values
        (select-keys shape measure-attrs)

        stroke-values
        (select-keys shape stroke-attrs)

        ;; Editable coordinates of the selected node(s) (feature 42). Read the
        ;; live drawing-copy content + ephemeral selection; apply the in-progress
        ;; `content-modifiers` so the values track the node WHILE dragging (the
        ;; modifiers only materialize into the content on release). Node content
        ;; is world coords; the panel shows them relative to the parent frame,
        ;; like the shape measures, so `ox`/`oy` (the frame origin) are
        ;; subtracted for display and added back on edit.
        edit-path (mf/deref refs/workspace-edit-path)
        drawing   (mf/deref refs/current-drawing-shape)
        objects   (mf/deref refs/workspace-page-objects)
        selection (get-in edit-path [id :selection])
        modifiers (get-in edit-path [id :content-modifiers])

        content
        (mf/with-memo [drawing modifiers]
          (when-let [base (get drawing :content)]
            (cpath/apply-content-modifiers base modifiers)))

        ;; parent frame origin; the page root (a top-level path) keeps absolute
        ;; coords, a real frame/board makes them relative to it
        frame    (cfh/get-parent-frame objects shape)
        in-frame? (and (some? frame) (not (cfh/root? frame)))
        ox       (if in-frame? (dm/get-prop frame :x) 0)
        oy       (if in-frame? (dm/get-prop frame :y) 0)

        ;; Coordinate values for the selection: with segments selected it is the
        ;; top-left of the rectangle surrounding them; otherwise the shared (or
        ;; :multiple) position of the selected node anchors + handler control
        ;; points. All relative to the parent frame.
        node-values
        (mf/with-memo [content selection ox oy]
          (when (and (some? content) (some? selection))
            (let [segments (get selection :segments)
                  handlers (get selection :handlers)
                  nodes    (get selection :nodes)]
              (cond
                (seq segments)
                (when-let [rect (cpath/calc-selrect
                                 (cpath/extract-content content {:segments segments}))]
                  {:x (- (dm/get-prop rect :x) ox)
                   :y (- (dm/get-prop rect :y) oy)})

                (or (seq nodes) (seq handlers))
                (let [positions (into (path.helpers/node-positions content (set nodes))
                                      (keep (fn [[i p]] (cpath/get-handler-point content i p)))
                                      handlers)]
                  (when (seq positions)
                    (let [xs (into #{} (map #(- (:x %) ox)) positions)
                          ys (into #{} (map #(- (:y %) oy)) positions)]
                      {:x (if (= 1 (count xs)) (first xs) :multiple)
                       :y (if (= 1 (count ys)) (first ys) :multiple)})))))))

        on-node-x-change
        (mf/use-fn (mf/deps ox)
                   (fn [value] (when (some? value) (st/emit! (drp/set-selection-coordinate :x (+ value ox))))))

        on-node-y-change
        (mf/use-fn (mf/deps oy)
                   (fn [value] (when (some? value) (st/emit! (drp/set-selection-coordinate :y (+ value oy))))))]

    [:*
     (when (some? node-values)
       [:> node-position-menu* {:values node-values
                                :on-x-change on-node-x-change
                                :on-y-change on-node-y-change}])
     ;; Shape measures (read-only) are shown only when nothing is selected. While
     ;; a node/handler/segment is selected the interactive coordinate block above
     ;; replaces them — the shape geometry can't be edited reliably here anyway
     ;; (it is owned by the drawing copy during edition).
     (when (nil? node-values)
       [:div {:style {:pointer-events "none" :opacity 0.6}}
        [:> measures-menu* {:ids ids
                            :type type
                            :applied-tokens applied-tokens
                            :values measure-values
                            :shapes shapes}]])
     [:> fill/fill-menu*
      {:ids ids
       :type type
       :values shape
       :applied-tokens applied-tokens}]
     [:> stroke-menu* {:ids ids
                       :type type
                       :show-caps true
                       :values stroke-values
                       :applied-tokens applied-tokens}]
     [:> shadow-menu* {:ids ids :values (get shape :shadow)}]
     [:> blur-menu* {:ids ids
                     :values (select-keys shape [:blur :background-blur])}]]))

