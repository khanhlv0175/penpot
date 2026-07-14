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

(def ^:private edit-fill-opacity
  "Fills are dimmed to this fraction of their opacity while a path is edited so
  elements behind the (otherwise opaque) edited path show through."
  0.8)

(def ^:private synced-edit-attrs
  "Stored-shape visual attrs mirrored into the drawing copy while editing so
  options-panel edits preview live. Geometry stays with the edited copy."
  [:strokes :shadow :blur :background-blur :opacity :blend-mode])

(defn- dim-fills
  [fills]
  (mapv (fn [fill]
          (update fill :fill-opacity #(* (or % 1) edit-fill-opacity)))
        fills))

(defn path-edit-shape
  "The shape `draw-area*` renders while editing a path: the drawing copy with
  the stored shape's live visual attrs merged in (so options-panel edits
  preview) and its fills dimmed (so the background shows through). Fills are
  only taken from the stored shape when it has its own, so an inherited /
  SVG-default fill resolved into the drawing copy is preserved (bug 14)."
  [drawing-obj stored]
  (-> (cond-> (merge drawing-obj (select-keys stored synced-edit-attrs))
        (seq (:fills stored)) (assoc :fills (:fills stored)))
      (update :fills dim-fills)))

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
