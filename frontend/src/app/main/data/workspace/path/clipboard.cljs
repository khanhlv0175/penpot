;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.path.clipboard
  (:require
   [app.common.data.macros :as dm]
   [app.common.geom.point :as gpt]
   [app.common.transit :as t]
   [app.common.types.path :as path]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.path.common :as common]
   [app.main.data.workspace.path.drawing :as drawing]
   [app.main.data.workspace.path.edition :as edition]
   [app.main.data.workspace.path.helpers :as helpers]
   [app.main.data.workspace.path.state :as st]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.streams :as ms]
   [app.util.clipboard :as clipboard]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

(def ^:private clipboard-type :copied-path-content)

(defn- on-clipboard-error
  [cause]
  (js/console.error "clipboard blocked:" cause)
  (rx/empty))

(defn copy-selected-nodes
  "Copies the sub-content induced by the current node/segment selection
  (selected segments plus segments whose two endpoint nodes are selected)
  into the system clipboard."
  []
  (ptk/reify ::copy-selected-nodes
    ptk/WatchEvent
    (watch [_ state _]
      (let [id        (st/get-path-id state)
            content   (st/get-path state :content)
            selection (dm/get-in state [:workspace-local :edit-path id :selection])
            fragment  (some-> content (path/extract-content selection))]
        (when (seq fragment)
          (let [data (t/encode-str {:type clipboard-type
                                    :content fragment}
                                   {:type :json-verbose})]
            (->> (rx/from (clipboard/to-clipboard data))
                 (rx/catch on-clipboard-error)
                 (rx/ignore))))))))

(def ^:private paste-offset (gpt/point 10 10))

(defn- center-content-at
  "Translate `sub-content` so the bounding box of its nodes is centred on
  `target`. The centre is computed from the exact node min/max — `points->rect`
  and `calc-selrect` pad a degenerate axis by 0.01, which would drift the centre
  by half that and defeat the exact-position collision offset below."
  [sub-content target]
  (let [pts    (path/get-points sub-content)
        xs     (map :x pts)
        ys     (map :y pts)
        center (gpt/point (/ (+ (reduce min xs) (reduce max xs)) 2)
                          (/ (+ (reduce min ys) (reduce max ys)) 2))]
    (path/move-content sub-content (gpt/subtract target center))))

(defn- offset-pasted-content
  "The node-moving pipeline matches nodes by position, so a pasted node at
  the exact coordinates of an existing one would drag both. Shift the pasted
  content until no node position collides."
  [content sub-content]
  (let [existing (into #{} (path/get-points content))]
    (loop [sub-content sub-content
           iteration   0]
      (if (and (< iteration 100)
               (some existing (path/get-points sub-content)))
        (recur (path/move-content sub-content paste-offset)
               (inc iteration))
        sub-content))))

(defn paste-content
  "Splices the given sub-content into the edited path as new subpaths and
  selects the pasted nodes."
  [sub-content]
  (ptk/reify ::paste-content
    ptk/UpdateEvent
    (update [_ state]
      (let [id (st/get-path-id state)]
        (if (and (some? id)
                 (some? (dm/get-in state [:workspace-local :edit-path id]))
                 (seq sub-content))
          (let [content     (st/get-path state :content)
                base        (count content)
                target      (deref ms/mouse-position)
                ;; drop the pasted fragment at the pointer (centred), not at the
                ;; world position it was copied from
                sub-content (cond-> sub-content
                              (some? target) (center-content-at target))
                sub-content (offset-pasted-content content sub-content)
                new-content (path/splice-content content sub-content)
                pasted      (into #{}
                                  (map #(+ base %))
                                  (helpers/node-indices sub-content))]
            (-> state
                (st/set-content new-content)
                (update-in (st/get-path-location state) path/update-geometry)
                (assoc-in [:workspace-local :edit-path id :selection]
                          (assoc helpers/empty-selection :nodes pasted))))
          state)))

    ptk/WatchEvent
    (watch [_ state _]
      ;; Move mode allows dragging the pasted fragment right away
      (when (some? (dm/get-in state [:workspace-local :edition]))
        (rx/of (drawing/change-edit-mode :move))))))

(defn paste-nodes-as-shape
  "Creates a new path shape from copied path sub-content, centred at the pointer
  (or the viewport centre), and selects it. Used when pasting copied path nodes
  **outside** a path editor; while a path is being edited `paste-nodes` splices
  the fragment into that path instead, so this is a no-op then."
  [sub-content]
  (ptk/reify ::paste-nodes-as-shape
    ptk/WatchEvent
    (watch [_ state _]
      (let [content  (path/content sub-content)
            id       (st/get-path-id state)
            editing? (and (some? id)
                          (some? (dm/get-in state [:workspace-local :edit-path id])))]
        (when (and (not editing?) (seq (path/get-points content)))
          (let [target (or (deref ms/mouse-position)
                           (dsh/get-viewport-center state))
                moved  (center-content-at content target)
                mrect  (path/calc-selrect moved)]
            (rx/of
             (dwsh/create-and-add-shape
              :path (:x target) (:y target)
              {:content moved
               ;; a path positions itself from its content, but pass x/y/size so
               ;; create-and-add-shape does not fall back to the viewport centre
               :x (:x mrect)
               :y (:y mrect)
               :width (:width mrect)
               :height (:height mrect)
               :name "Path"}))))))))

(defn paste-nodes
  "Reads the clipboard and, when it holds copied path sub-content, pastes it
  into the edited path. Any other clipboard content is ignored."
  []
  (ptk/reify ::paste-nodes
    ptk/WatchEvent
    (watch [_ _ _]
      (->> (clipboard/from-navigator)
           (rx/filter #(= (.-type ^js %) "application/transit+json"))
           (rx/mapcat #(rx/from (.text ^js %)))
           (rx/map t/decode-str)
           (rx/filter #(and (map? %) (= clipboard-type (:type %))))
           (rx/take 1)
           (rx/mapcat (fn [{:keys [content]}]
                        ;; A pending draw segment would leave its subpath
                        ;; start dangling before the spliced content
                        (rx/of (common/cancel-pending-segment)
                               (paste-content content))))
           (rx/catch on-clipboard-error)))))

(defn duplicate-selected
  "Duplicates the current node/segment selection in place: selected segments
  are copied offset next to the original; each lone selected node is copied as a
  new subpath keeping its incoming segment (its line stays attached to the
  original's predecessor) with one new offset node. Selects the copies and
  switches to move mode so they can be dragged right away."
  []
  (ptk/reify ::duplicate-selected
    ptk/WatchEvent
    (watch [_ state _]
      (let [id        (st/get-path-id state)
            content   (st/get-path state :content)
            selection (dm/get-in state [:workspace-local :edit-path id :selection])
            result    (helpers/duplicate-selection-content
                       content selection edition/duplicate-offset)]
        (when (seq (:sub result))
          (rx/concat
           ;; A pending draw segment would leave its subpath start dangling
           ;; before the spliced content
           (rx/of (common/cancel-pending-segment)
                  (edition/splice-duplicated result))
           (when (some? (dm/get-in state [:workspace-local :edition]))
             (rx/of (drawing/change-edit-mode :move)))))))))
