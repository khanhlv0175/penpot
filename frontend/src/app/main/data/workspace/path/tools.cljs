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

             selected-nodes
             (dm/get-in state [:workspace-local :edit-path id :selected-nodes] #{})

             points
             (or points (helpers/node-positions old-content selected-nodes))]

         (if (and (seq points) (some? shape))
           (let [new-content
                 (-> (tool-fn old-content points)
                     (path/close-subpaths))]
             (-> (cond-> (st/set-content state new-content)
                   (seq new-content)
                   (update-in (st/get-path-location state) path/update-geometry))
                 (update-in [:workspace-local :edit-path id :selected-nodes]
                            #(helpers/remap-selected-nodes (or % #{}) old-content new-content))))
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

(defn toggle-snap []
  (ptk/reify ::toggle-snap
    ptk/UpdateEvent
    (update [_ state]
      (let [id (st/get-path-id state)]
        (update-in state [:workspace-local :edit-path id :snap-toggled] not)))))
