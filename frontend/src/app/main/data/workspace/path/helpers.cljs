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

(defn handlers-joined?
  "True when a node's two handlers are collinear on opposite sides (a smooth
  node): the angle between them is ~180 degrees. A node with a missing handler
  (a corner/line side) is not joined."
  [content index prefix]
  (let [[op-idx op-prefix] (path/opposite-index content index prefix)
        node     (path/handler->node content index prefix)
        handler  (path/get-handler-point content index prefix)
        opposite (when op-idx (path/get-handler-point content op-idx op-prefix))]
    (boolean
     (and (some? op-idx)
          (some? handler)
          (some? opposite)
          (not= handler node)
          (not= opposite node)
          (<= (mth/abs (- 180 (angle-points node handler opposite))) 0.1)))))

(defn move-handler-modifiers
  ([content index prefix match-distance? match-angle? dx dy]
   (move-handler-modifiers content index prefix match-distance? match-angle? false dx dy))
  ([content index prefix match-distance? match-angle? rejoin? dx dy]

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

         (cond->
          ;; Re-join: force the opposite handler to mirror the dragged one
          ;; (opposite angle, equal length) regardless of its current angle.
          (and (some? op-idx) rejoin? (not= opposite node))
           (update op-idx assoc
                   ocx (- (:x node) (:x hnv) (:x opposite))
                   ocy (- (:y node) (:y hnv) (:y opposite)))

           (and (some? op-idx) (not rejoin?) (not= opposite node))
           (update op-idx assoc ocx odx ocy ody)

           (and (some? op-idx) (= opposite node) match-distance? match-angle?)
           (update op-idx assoc ocx (- (:x hnv)) ocy (- (:y hnv))))))))

(defn align-handler-modifiers
  "Modifiers that move the handler by `[dx dy]` and rotate its opposite so the
  pair stays mirror-aligned (~180°), the opposite keeping its own length. The
  alignment is forced even when the pair is currently a corner."
  [content index prefix dx dy]
  (let [[cx cy]            (path.helpers/prefix->coords prefix)
        [op-idx op-prefix] (path/opposite-index content index prefix)
        node               (path/handler->node content index prefix)
        opposite           (when (some? op-idx)
                             (path/get-handler-point content op-idx op-prefix))
        handler            (path/get-handler-point content index prefix)
        modifiers          (-> {} (update index assoc cx dx cy dy))]
    (if (and (some? handler) (some? opposite) (not= opposite node))
      (let [hv (gpt/to-vec node (-> handler (update :x + dx) (update :y + dy)))]
        (if (mth/almost-zero? (gpt/length hv))
          modifiers
          (let [len       (gpt/distance node opposite)
                target    (gpt/subtract node (gpt/scale (gpt/unit hv) len))
                [ocx ocy] (path.helpers/prefix->coords op-prefix)]
            (update modifiers op-idx assoc
                    ocx (- (:x target) (:x opposite))
                    ocy (- (:y target) (:y opposite))))))
      modifiers)))

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

(defn selected-node-indices
  "The effective set of selected node indices for node operations: the
  explicitly selected `:nodes` plus the endpoint nodes of any selected
  `:segments`. Lets flip/merge/remove act on a segment selection."
  [content selection]
  (into (get selection :nodes #{})
        (segment-node-indices content (get selection :segments #{}))))

(def segment-insert-threshold
  "Distance (board px, divided by zoom at the call site) from a segment's exact
  middle within which a plain click inserts a node instead of selecting."
  12)

(defn- segment-base-curve
  "The `[start end h1 h2]` cubic of a segment entry (a line is the degenerate
  cubic with both handles at the endpoints)."
  [{:keys [from to segment]}]
  (if (= :curve-to (:command segment))
    [from to (path/get-handler segment :c1) (path/get-handler segment :c2)]
    [from to from to]))

(defn segment-mid-point
  "Position at the arc-length (visual) middle of a segment entry — the point
  where half the segment's length has been travelled, not the parametric
  t=0.5, which drifts toward the sharper end on uneven curves."
  [entry]
  (let [curve (segment-base-curve entry)]
    (path.helpers/curve-values curve (path.helpers/curve-arc-length-t curve))))

(defn insertion-point
  "The point that a click near `position` would insert into `content`, carrying
  `{:from-p :to-p :t}` split metadata, or nil when a click there would not
  insert. By default only a segment's arc-length (visual) middle within
  `threshold` qualifies; with `anywhere?` the closest point on the path within
  `threshold` qualifies instead. Close-path segments never insert."
  [content position threshold anywhere?]
  (if anywhere?
    (let [point (path/closest-point content position 0.01)]
      (when (and (some? point) (<= (gpt/distance position point) threshold))
        point))
    (let [candidates
          (->> (segment-entries content)
               (remove #(= :close-path (:command (:segment %))))
               (keep (fn [{:keys [from to] :as entry}]
                       (let [curve (segment-base-curve entry)
                             t     (path.helpers/curve-arc-length-t curve)
                             mid   (path.helpers/curve-values curve t)
                             dist  (gpt/distance position mid)]
                         (when (<= dist threshold)
                           [dist (with-meta mid {:from-p from :to-p to :t t})])))))]
      (when (seq candidates)
        (second (apply min-key first candidates))))))

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

(defn- fragment-covered-nodes
  "Node indices already duplicated as part of a segment fragment
  (`path/extract-content`): the endpoints of every selected segment and of
  every segment between two selected nodes."
  [content {:keys [nodes segments]}]
  (let [nodes    (or nodes #{})
        segments (or segments #{})]
    (into #{}
          (comp (filter (fn [{:keys [index from-index to-index]}]
                          (or (contains? segments index)
                              (and (contains? nodes from-index)
                                   (contains? nodes to-index)))))
                (mapcat (juxt :from-index :to-index)))
          (segment-entries content))))

(defn duplicate-selection-content
  "Duplicates the current node/segment selection. Returns
  `{:content <sub-content> :selected #{relative-node-indices}}` ready to splice
  as new subpaths. Selected segments (and segments between two selected nodes)
  are copied as fragments shifted by `offset`; every remaining lone selected
  node is copied with all its incident segments: each copy's far end stays
  attached to the original neighbor and the copies meet at one new node shifted
  by `offset`. `:selected` holds every genuinely new node (all fragment nodes
  plus each copied node's meeting points) but never a copy's attach point,
  which stays coincident with the original — selecting it would drag the
  original too (the move pipeline is position-based)."
  [content selection offset]
  (let [fragment (path/extract-content content selection)
        fragment (cond-> fragment
                   (and (seq fragment) (some? offset))
                   (path/move-content offset))
        fragment (vec fragment)
        covered  (fragment-covered-nodes content selection)
        free     (sort (remove covered (get selection :nodes #{})))]
    (reduce (fn [{:keys [sub selected]} node-index]
              (if-let [{ext :content ext-selected :selected}
                       (path/duplicate-node-content content node-index offset)]
                (let [start (count sub)]
                  {:sub      (into sub ext)
                   :selected (into selected (map #(+ start %)) ext-selected)})
                {:sub sub :selected selected}))
            {:sub fragment :selected (set (node-indices fragment))}
            free)))

(defn remap-selection
  "Adjust a grouped selection map after the content changed. With the same
  command layout (equal command count) node and segment indices remain valid;
  handlers whose command lost its control points and segments whose command
  became a subpath break are dropped. Otherwise node indices are re-resolved
  by position and segment/handler selections are cleared."
  [selection old-content new-content]
  (let [selection (or selection empty-selection)]
    (if (= (count old-content) (count new-content))
      (-> selection
          (update :handlers
                  (fn [handlers]
                    (into #{}
                          (filter (fn [[index _]]
                                    (= :curve-to (:command (nth new-content index nil)))))
                          handlers)))
          ;; a removed segment can keep the command count (its command becomes
          ;; a subpath-break move-to), so a selected index may stop being a
          ;; segment
          (update :segments
                  (fn [segments]
                    (into #{}
                          (remove (fn [index]
                                    (= :move-to (:command (nth new-content index nil)))))
                          segments))))
      (assoc empty-selection
             :nodes (remap-selected-nodes (get selection :nodes #{})
                                          old-content
                                          new-content)))))
