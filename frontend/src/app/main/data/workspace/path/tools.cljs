;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.path.tools
  (:require
   [app.common.data :as d]
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
                            #(helpers/remap-selection % old-content new-content))
                 (update-in [:workspace-local :edit-path id :handler-types]
                            #(helpers/remap-handler-types % old-content new-content))))
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

(defn- apply-handler-type-modifiers
  "Content-modifier map that reshapes the node at anchor `node-index` to `type`
  in place (zero drag delta): mirror forces the opposite onto the exact mirror,
  aligned rotates it collinear keeping its length, independent leaves geometry."
  [content node-index type]
  (if-let [[idx prefix] (helpers/node-primary-handler content node-index)]
    (case type
      :mirror  (helpers/move-handler-modifiers content idx prefix true true true 0 0)
      :aligned (helpers/align-handler-modifiers content idx prefix 0 0)
      {})
    {}))

(defn set-handler-type
  "Sets the handler behaviour (`:mirror` / `:aligned` / `:independent`) of the
  selected node(s): reshapes their handlers to match and records the type in the
  ephemeral edit-path state so it is honoured on later drags and reflected in the
  top bar."
  [type]
  (ptk/reify ::set-handler-type
    ptk/UpdateEvent
    (update [_ state]
      (let [id        (st/get-path-id state)
            content   (st/get-path state :content)
            selection (dm/get-in state [:workspace-local :edit-path id :selection])
            nodes     (helpers/handler-target-nodes content selection)]
        (if (and (some? content) (seq nodes))
          (let [modifiers   (reduce (fn [acc node-index]
                                      (d/deep-merge acc (apply-handler-type-modifiers content node-index type)))
                                    {} nodes)
                new-content (path/apply-content-modifiers content modifiers)]
            (-> (st/set-content state new-content)
                (update-in (st/get-path-location state) path/update-geometry)
                (update-in [:workspace-local :edit-path id :handler-types]
                           (fn [ht] (reduce #(assoc %1 %2 type) (or ht {}) nodes)))))
          state)))))

(defn add-node []
  (process-path-tool (fn [content points] (path/split-segments content points 0.5))))

(defn remove-node
  "Removes the selected nodes (or the node at `point`), healing the gap with a
  fitted curve between the surviving neighbors."
  ([]
   (process-path-tool path/remove-nodes))
  ([point]
   (process-path-tool #{point} path/remove-nodes)))

(defn toggle-node-curve
  "Toggles the node at the given content index between a corner (its handles
  collapsed) and a curve (default handles toward its neighbors)."
  [index]
  (ptk/reify ::toggle-node-curve
    ptk/WatchEvent
    (watch [_ state _]
      (let [content (st/get-path state :content)]
        (when (and (some? content)
                   (< index (count content))
                   (helpers/node? content index))
          (let [point (helpers/node-position content index)]
            (rx/of (if (path/is-curve-point? content point)
                     (make-corner point)
                     (make-curve point)))))))))

(defn- update-path-content
  "Replaces the edited path content, refreshing its geometry and remapping the
  grouped selection to the new structure."
  [state new-content]
  (let [id          (st/get-path-id state)
        old-content (st/get-path state :content)]
    (-> (cond-> (st/set-content state new-content)
          (seq new-content)
          (update-in (st/get-path-location state) path/update-geometry))
        (update-in [:workspace-local :edit-path id :selection]
                   #(helpers/remap-selection % old-content new-content)))))

(defn remove-segments
  "Removes the given segment command indices, opening the path there: the
  endpoint nodes stay in place and ends left dangling are dropped."
  [indices]
  (ptk/reify ::remove-segments
    ptk/UpdateEvent
    (update [_ state]
      (let [content (st/get-path state :content)]
        (if (and (some? content) (seq indices))
          (update-path-content state (path/remove-segments content indices))
          state)))

    ptk/WatchEvent
    (watch [_ state _]
      (when (empty? (st/get-path state :content))
        (rx/of (dwe/clear-edition-mode))))))

(defn remove-segment
  [index]
  (remove-segments #{index}))

(defn remove-node-with-segments
  "Removes the node at the given index together with every segment incident to
  it, leaving an open gap (no healing)."
  [index]
  (ptk/reify ::remove-node-with-segments
    ptk/WatchEvent
    (watch [_ state _]
      (let [content  (st/get-path state :content)
            incident (into #{}
                           (comp (filter #(or (= index (:to-index %))
                                              (= index (:from-index %))))
                                 (map :index))
                           (helpers/segment-entries content))]
        (when (seq incident)
          (rx/of (remove-segments incident)))))))

(defn toggle-segment-curve
  "Toggles the segment at the given index between a straight line and a curve
  with default handles a third of the way from each endpoint."
  [index]
  (ptk/reify ::toggle-segment-curve
    ptk/UpdateEvent
    (update [_ state]
      (let [content (st/get-path state :content)]
        (if (some? content)
          (update-path-content state (path/toggle-segment-curve content index))
          state)))))

(defn remove-handler
  "Collapses the clicked handler onto its node, making that side of the
  segment straight; the matching handler is untouched."
  [index prefix]
  (ptk/reify ::remove-handler
    ptk/UpdateEvent
    (update [_ state]
      (let [content (st/get-path state :content)]
        (if (some? content)
          (update-path-content state (path/collapse-handler content index prefix))
          state)))))

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
