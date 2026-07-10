;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.path.helpers
  (:require
   [app.common.data :as d]
   [app.common.geom.point :as gpt]
   [app.common.geom.rect :as grc]
   [app.common.geom.shapes :as gsh]
   [app.common.geom.shapes.intersect :as gsi]
   [app.common.math :as mth]
   [app.common.types.path :as path]
   [app.common.types.path.helpers :as path.helpers]))

(defn append-node
  "Creates a new node in the path. Usually used when drawing."
  [shape position prev-point prev-handler]
  (let [segment (path/next-node (:content shape) position prev-point prev-handler)]
    (-> shape
        (update :content path/append-segment segment)
        (path/update-geometry))))

(defn angle-points [common p1 p2]
  (mth/abs
   (gpt/angle-with-other
    (gpt/to-vec common p1)
    (gpt/to-vec common p2))))

(defn- calculate-opposite-delta [node handler opposite match-angle? match-distance? dx dy]
  (when (and (some? handler) (some? opposite))
    (let [;; To match the angle, the angle should be matching (angle between points 180deg)
          angle-handlers (angle-points node handler opposite)

          match-angle? (and match-angle? (<= (mth/abs (- 180 angle-handlers)) 0.1))

          ;; To match distance the distance should be matching
          match-distance? (and match-distance? (mth/almost-zero? (- (gpt/distance node handler)
                                                                    (gpt/distance node opposite))))

          new-handler (-> handler (update :x + dx) (update :y + dy))

          v1 (gpt/to-vec node handler)
          v2 (gpt/to-vec node new-handler)

          delta-angle (gpt/angle-with-other v1 v2)
          delta-sign (gpt/angle-sign v1 v2)

          distance-scale (/ (gpt/distance node handler)
                            (gpt/distance node new-handler))

          new-opposite (cond-> opposite
                         match-angle?
                         (gpt/rotate node (* delta-sign delta-angle))

                         match-distance?
                         (gpt/scale-from node distance-scale))]
      [(- (:x new-opposite) (:x opposite))
       (- (:y new-opposite) (:y opposite))])))

(defn move-handler-modifiers
  [content index prefix match-distance? match-angle? dx dy]

  (let [[cx cy] (path.helpers/prefix->coords prefix)
        [op-idx op-prefix] (path/opposite-index content index prefix)

        node (path/handler->node content index prefix)
        handler (path/get-handler-point content index prefix)
        opposite (path/get-handler-point content op-idx op-prefix)

        [ocx ocy] (path.helpers/prefix->coords op-prefix)
        [odx ody] (calculate-opposite-delta node handler opposite match-angle? match-distance? dx dy)

        hnv (if (some? handler)
              (gpt/to-vec node (-> handler (update :x + dx) (update :y + dy)))
              (gpt/point dx dy))]

    (-> {}
        (update index assoc cx dx cy dy)

        (cond-> (and (some? op-idx) (not= opposite node))
          (update op-idx assoc ocx odx ocy ody)

          (and (some? op-idx) (= opposite node) match-distance? match-angle?)
          (update op-idx assoc ocx (- (:x hnv)) ocy (- (:y hnv)))))))

;; --- Stable node/segment/handler identity
;;
;; Nodes, segments and handlers in the path editor are identified by their
;; content command index (a handler additionally by its :c1/:c2 prefix), not by
;; their geometric position. Indices are stable while nodes are moved and only
;; shift when the content structure changes, so the position-based collapse
;; (two nodes at the same coordinates were indistinguishable) is avoided.
;; `close-path` commands are not selectable nodes: their endpoint is the subpath
;; start and they carry no anchor of their own.
;;
;; Selection and hover are each a single grouped map of index sets:
;;   {:nodes #{index} :segments #{index} :handlers #{[index prefix]}}
;; stored at [... :edit-path id :selection] and [... :edit-path id :hover].

(def empty-selection
  {:nodes #{} :segments #{} :handlers #{}})

(defn node?
  "True when the command at the given content index is a selectable node."
  [content index]
  (not= :close-path (:command (nth content index nil))))

(defn node-indices
  "Indices of every selectable node in the content."
  [content]
  (into []
        (comp (remove (fn [[_ seg]] (= :close-path (:command seg))))
              (map first))
        (d/enumerate content)))

(defn node-position
  "Position of the node at the given content command index."
  [content index]
  (path.helpers/segment->point (nth content index)))

(defn node-positions
  "Set of positions for the given node indices in the content."
  [content indices]
  (let [indices (set indices)]
    (into #{}
          (comp (filter (fn [[index _]] (contains? indices index)))
                (map (fn [[_ seg]] (path.helpers/segment->point seg))))
          (d/enumerate content))))

(defn nodes-in-rect
  "Indices of the nodes whose position falls inside the given rect."
  [content rect]
  (into #{}
        (comp (remove (fn [[_ seg]] (= :close-path (:command seg))))
              (filter (fn [[_ seg]] (gsh/has-point-rect? rect (path.helpers/segment->point seg))))
              (map first))
        (d/enumerate content)))

(def segment-entries
  "Selectable path segments with their command index and endpoints; see
  `app.common.types.path/segment-entries`."
  path/segment-entries)

(defn segment-node-indices
  "Unique endpoint-node indices for the selected segment command indices."
  [content segment-indices]
  (let [segment-indices (set segment-indices)]
    (into #{}
          (comp (filter #(contains? segment-indices (:index %)))
                (mapcat (juxt :from-index :to-index))
                (remove nil?))
          (segment-entries content))))

(defn- segment-lines
  [{:keys [from to segment]}]
  (if (= :curve-to (:command segment))
    (path.helpers/curve->lines from
                               to
                               (path/get-handler segment :c1)
                               (path/get-handler segment :c2))
    [[from to]]))

(defn segments-in-rect
  "Command indices of path segments that cross or fall inside `rect`. Curves
  use the same polyline approximation as the common path hit-testing code."
  [content rect]
  (let [rect-lines (gsi/points->lines (grc/rect->points rect))]
    (into #{}
          (comp
           (filter
            (fn [entry]
              (let [lines (segment-lines entry)]
                (or (some (fn [[from to]]
                            (or (grc/contains-point? rect from)
                                (grc/contains-point? rect to)))
                          lines)
                    (gsi/intersects-lines? rect-lines lines)))))
           (map :index))
          (segment-entries content))))

(defn handler-entries
  "Visible path handlers as `{:identity [index prefix] :point p}` entries."
  [content]
  (into []
        (comp
         (mapcat
          (fn [[index segment]]
            (when (= :curve-to (:command segment))
              (keep
               (fn [prefix]
                 (let [handler (path/get-handler-point content index prefix)
                       node    (path/handler->node content index prefix)]
                   (when (and handler (not= handler node))
                     {:identity [index prefix]
                      :point handler})))
               [:c1 :c2])))))
        (d/enumerate content)))

(defn handlers-in-rect
  "Identities of visible path handlers whose control point is inside `rect`."
  [content rect]
  (into #{}
        (comp (filter #(grc/contains-point? rect (:point %)))
              (map :identity))
        (handler-entries content)))

(defn remap-selected-nodes
  "Re-resolve selected node indices against `new-content` after a structural
  change, matching the positions the indices had in `old-content`. This is the
  one position->index remapping done at commit time; pure moves keep their
  indices and need no remap."
  [selected-nodes old-content new-content]
  (if (empty? selected-nodes)
    selected-nodes
    (let [positions (node-positions old-content selected-nodes)]
      (into #{}
            (comp (remove (fn [[_ seg]] (= :close-path (:command seg))))
                  (filter (fn [[_ seg]] (contains? positions (path.helpers/segment->point seg))))
                  (map first))
            (d/enumerate new-content)))))

(defn remap-selection
  "Adjust a grouped selection map after the content changed. With the same
  command layout (equal command count) node and segment indices remain valid
  and only handlers whose command lost its control points are dropped;
  otherwise node indices are re-resolved by position and segment/handler
  selections are cleared."
  [selection old-content new-content]
  (let [selection (or selection empty-selection)]
    (if (= (count old-content) (count new-content))
      (update selection :handlers
              (fn [handlers]
                (into #{}
                      (filter (fn [[index _]]
                                (= :curve-to (:command (nth new-content index nil)))))
                      handlers)))
      (assoc empty-selection
             :nodes (remap-selected-nodes (get selection :nodes #{})
                                          old-content
                                          new-content)))))
