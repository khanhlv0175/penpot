;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.viewport.drawarea
  "Drawing components."
  (:require
   [app.common.data.macros :as dm]
   [app.common.math :as mth]
   [app.common.types.path :as path]
   [app.main.refs :as refs]
   [app.main.ui.shapes.path :refer [path-shape]]
   [app.main.ui.workspace.shapes :as shapes]
   [app.main.ui.workspace.shapes.path.editor :refer [path-editor*]]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

(defn- make-edit-path-ref [id]
  (let [get-fn #(dm/get-in % [:edit-path id])]
    (l/derived get-fn refs/workspace-local)))

(mf/defc generic-draw-area*
  {::mf/private true}
  [{:keys [shape zoom]}]
  (let [{:keys [x y width height]} (get shape :selrect)]
    (when (and x y
               (not (mth/nan? x))
               (not (mth/nan? y)))

      [:rect.main {:x x :y y
                   :width width
                   :height height
                   :style {:stroke "var(--color-accent-tertiary)"
                           :fill "none"
                           :stroke-width (/ 1 zoom)}}])))

(mf/defc path-draw-area*
  {::mf/private true}
  [{:keys [shape] :as props}]
  (let [shape-id
        (dm/get-prop shape :id)

        edit-path-ref
        (mf/with-memo [shape-id]
          (make-edit-path-ref shape-id))

        edit-path-state
        (mf/deref edit-path-ref)

        props
        (mf/spread-props props {:state edit-path-state})]

    [:> path-editor* props]))

(mf/defc draw-area*
  [{:keys [shape zoom tool] :as props}]
  (let [;; While drawing or editing, the path content is kept line-closed (a
        ;; trailing line-to landing back on the subpath start, no close-path
        ;; command) so the editor's per-node/segment interaction on the closing
        ;; seam keeps working. The rendered fill/stroke, though, needs the
        ;; close-path materialized: a coincident-endpoint loop otherwise renders
        ;; two butt caps at the seam instead of a join. Close the loops only for
        ;; this rendered copy; the editor overlay uses the raw content.
        render-shape
        (mf/with-memo [shape]
          (if (= :path (dm/get-prop shape :type))
            (update shape :content path/close-loops)
            shape))]
    [:g.draw-area
     [:g {:style {:pointer-events "none"}}
      [:& shapes/shape-wrapper {:shape render-shape}]]

     (cond
       (= tool :path)
       [:> path-draw-area* props]

       (= tool :curve)
       [:& path-shape {:shape shape :zoom zoom}]

       (= (:type shape) :path)
       nil

       :else
       [:> generic-draw-area* props])]))
