;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.path.tools
  (:require
   [app.common.data.macros :as dm]
   [app.common.types.path :as path]
   [app.main.data.workspace.edition :as dwe]
   [app.main.data.workspace.path.helpers :as helpers]
   [app.main.data.workspace.path.state :as st]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

(defn process-path-tool
  "Generic function that executes path transformations with the content and selected nodes.
  The common-layer tool fns are position-based, so the selected node indices are
  converted to positions at this boundary and the selection is remapped to the new
  indices once the structural change is applied."
  ([tool-fn]
   (process-path-tool nil tool-fn))
  ([points tool-fn]
   (ptk/reify ::process-path-tool
     ptk/UpdateEvent
     (update [_ state]
       (let [shape (st/get-path state)
             id    (st/get-path-id state)

             old-content (:content shape)

             ;; a selected segment contributes its endpoint nodes, so node ops
             ;; (merge / make-corner / separate / …) act on segment selections
             selected-nodes
             (helpers/selected-node-indices
              old-content
              (dm/get-in state [:workspace-local :edit-path id :selection]))

             points
             (or points (helpers/node-positions old-content selected-nodes))]

         (if (and (seq points) (some? shape))
           (let [new-content
                 (-> (tool-fn old-content points)
                     (path/close-subpaths))]
             (-> (cond-> (st/set-content state new-content)
                   (seq new-content)
                   (update-in (st/get-path-location state) path/update-geometry))
                 (update-in [:workspace-local :edit-path id :selection]
                            #(helpers/remap-selection % old-content new-content))))
           state)))

     ptk/WatchEvent
     (watch [_ state _]
       (when (empty? (st/get-path state :content))
         (rx/of (dwe/clear-edition-mode)))))))

(defn make-corner
  ([]
   (make-corner nil))
  ([point]
   (process-path-tool
    (when point #{point})
    (fn [content points]
      (reduce path/make-corner-point content points)))))

(defn make-curve
  ([]
   (make-curve nil))
  ([point]
   (process-path-tool
    (when point #{point})
    (fn [content points]
      (reduce path/make-curve-point content points)))))

(defn add-node []
  (process-path-tool (fn [content points] (path/split-segments content points 0.5))))

(defn remove-node []
  (process-path-tool path/remove-nodes))

(defn merge-nodes []
  (process-path-tool path/merge-nodes))

(defn join-nodes []
  (process-path-tool path/join-nodes))

(defn separate-nodes []
  (process-path-tool path/separate-nodes))

(defn delete-selected
  "Delete key / remove button: with a segment selection, opens the path at the
  selected segments (drops just those segments, keeping their nodes) via
  `separate-nodes`; otherwise removes the selected nodes via `remove-node`."
  []
  (ptk/reify ::delete-selected
    ptk/WatchEvent
    (watch [_ state _]
      (let [id       (st/get-path-id state)
            segments (dm/get-in state [:workspace-local :edit-path id :selection :segments] #{})]
        (rx/of (if (seq segments)
                 (separate-nodes)
                 (remove-node)))))))

(defn flip-nodes
  "Flips the selected nodes (or the whole path when nothing is selected)
  across the center of their bounding box. `axis` is `:horizontal` or
  `:vertical`. Node indices are preserved, so the selection stays valid."
  [axis]
  (ptk/reify ::flip-nodes
    ptk/UpdateEvent
    (update [_ state]
      (let [id       (st/get-path-id state)
            content  (st/get-path state :content)
            selected (helpers/selected-node-indices
                      content
                      (dm/get-in state [:workspace-local :edit-path id :selection]))
            indices  (if (seq selected)
                       selected
                       (helpers/node-indices content))
            content  (path/flip-content content indices axis)]
        (-> (st/set-content state content)
            (update-in (st/get-path-location state) path/update-geometry))))))

(defn align-nodes
  "Aligns the selected nodes (and their handles) to a common edge or center of
  their bounding box. `axis` is one of `:hleft`/`:hcenter`/`:hright`/`:vtop`/
  `:vcenter`/`:vbottom`. Needs at least two selected nodes. Indices are
  preserved, so the selection stays valid."
  [axis]
  (ptk/reify ::align-nodes
    ptk/UpdateEvent
    (update [_ state]
      (let [id       (st/get-path-id state)
            content  (st/get-path state :content)
            selected (dm/get-in state [:workspace-local :edit-path id :selection :nodes] #{})
            content  (path/align-content content selected axis)]
        (-> (st/set-content state content)
            (update-in (st/get-path-location state) path/update-geometry))))))

(defn distribute-nodes
  "Distributes the selected nodes evenly along `axis` (`:horizontal` or
  `:vertical`) between the two extreme selected nodes. Needs at least three
  selected nodes. Indices are preserved."
  [axis]
  (ptk/reify ::distribute-nodes
    ptk/UpdateEvent
    (update [_ state]
      (let [id       (st/get-path-id state)
            content  (st/get-path state :content)
            selected (dm/get-in state [:workspace-local :edit-path id :selection :nodes] #{})
            content  (path/distribute-content content selected axis)]
        (-> (st/set-content state content)
            (update-in (st/get-path-location state) path/update-geometry))))))

(defn toggle-snap []
  (ptk/reify ::toggle-snap
    ptk/UpdateEvent
    (update [_ state]
      (let [id (st/get-path-id state)]
        (update-in state [:workspace-local :edit-path id :snap-toggled] not)))))

(defn set-add-nodes
  "Enables/disables the add-nodes state (a sub-state of move mode: while active,
  hovering a segment previews a node and clicking inserts it)."
  [enabled?]
  (ptk/reify ::set-add-nodes
    ptk/UpdateEvent
    (update [_ state]
      (let [id (st/get-path-id state)]
        (assoc-in state [:workspace-local :edit-path id :add-nodes] enabled?)))))

(defn toggle-add-nodes []
  (ptk/reify ::toggle-add-nodes
    ptk/UpdateEvent
    (update [_ state]
      (let [id (st/get-path-id state)]
        (update-in state [:workspace-local :edit-path id :add-nodes] not)))))
