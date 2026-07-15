;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.viewport.path-actions
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.types.path.segment :as path.segm]
   [app.main.data.workspace.path :as drp]
   [app.main.data.workspace.path.helpers :as path.helpers]
   [app.main.data.workspace.path.shortcuts :as sc]
   [app.main.store :as st]
   [app.main.ui.icons :as deprecated-icon]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.timers :as ts]
   [rumext.v2 :as mf]))

(def ^:private pentool-icon
  (deprecated-icon/icon-xref :pentool (stl/css :pentool-icon :pathbar-icon)))

(def ^:private move-icon
  (deprecated-icon/icon-xref :move (stl/css :move-icon :pathbar-icon)))

(def ^:private merge-nodes-icon
  (deprecated-icon/icon-xref :merge-nodes (stl/css :merge-nodes-icon :pathbar-icon)))

(def ^:private join-nodes-icon
  (deprecated-icon/icon-xref :join-nodes (stl/css :join-nodes-icon :pathbar-icon)))

(def ^:private separate-nodes-icon
  (deprecated-icon/icon-xref :separate-nodes (stl/css :separate-nodes-icon :pathbar-icon)))

(def ^:private to-corner-icon
  (deprecated-icon/icon-xref :to-corner (stl/css :to-corner-icon :pathbar-icon)))

(def ^:private to-curve-icon
  (deprecated-icon/icon-xref :to-curve (stl/css :to-curve-icon :pathbar-icon)))

(def ^:private snap-icon
  (deprecated-icon/icon-xref :snap (stl/css :snap-icon :pathbar-icon)))

;; Handler-behaviour icons: mirror / aligned / independent.
;; :mirror = equal-length collinear handles → the equal-length glyph;
;; :aligned = mirrored angle, independent length → the unequal-length glyph.
(def ^:private handler-mirror-icon
  (deprecated-icon/icon-xref :handlers-equal (stl/css :pathbar-icon)))

(def ^:private handler-aligned-icon
  (deprecated-icon/icon-xref :handlers-mirror (stl/css :pathbar-icon)))

(def ^:private handler-independent-icon
  (deprecated-icon/icon-xref :handlers-independent (stl/css :pathbar-icon)))

(defn- handler-type-icon [type]
  (case type
    :mirror      handler-mirror-icon
    :aligned     handler-aligned-icon
    handler-independent-icon))

(defn check-enabled [content selected-points]
  (when content
    (let [segments (path.segm/get-segments-with-points content selected-points)
          num-segments (count segments)
          num-points (count selected-points)
          points-selected? (seq selected-points)
          segments-selected? (seq segments)
          ;; max segments for n points is (n × (n -1)) / 2
          max-segments (-> num-points
                           (* (- num-points 1))
                           (/ 2))
          is-curve? (some #(path.segm/is-curve? content %) selected-points)]

      {:make-corner (and points-selected? is-curve?)
       :make-curve (and points-selected? (not is-curve?))
       ;; Merge works on any 2+ selected nodes: connected ones collapse their
       ;; segment, disconnected ones are joined with a line first, then merged.
       :merge-nodes (and points-selected? (>= num-points 2))
       :join-nodes (and points-selected? (>= num-points 2) (< num-segments max-segments))
       ;; A single selected node separates into two offset nodes; two or
       ;; more separate the segments between them.
       :separate-nodes (or segments-selected? (= num-points 1))})))

(mf/defc topbar-button*
  "A node-operation button. Callers render it only when its action is available,
  so unavailable actions are removed from the bar rather than shown disabled."
  {::mf/private true}
  [{:keys [title on-click icon]}]
  [:button {:class (stl/css :topbar-btn)
            :title title
            :on-click on-click}
   icon])

(defn- cancel-timer!
  [timer-ref*]
  (when-let [timer (mf/ref-val timer-ref*)]
    (ts/dispose! timer)
    (mf/set-ref-val! timer-ref* nil)))

(mf/defc handler-type-menu*
  "Split button that sets the handler behaviour of the selected node(s): its
  icon shows the current type and it opens a flyout with the three options
  (mirror / aligned / independent). The flyout opens on hover and the trigger
  carries a bottom-right corner indicator, mirroring the main toolbar's
  grouped-tool control."
  {::mf/private true}
  [{:keys [active-type on-select]}]
  (let [open*        (mf/use-state false)
        open?        (deref open*)

        open-timer*  (mf/use-ref nil)
        close-timer* (mf/use-ref nil)

        select
        (mf/use-fn
         (mf/deps on-select)
         (fn [type]
           (reset! open* false)
           (on-select type)))

        on-trigger-click
        (mf/use-fn
         (mf/deps select active-type)
         (fn []
           (select active-type)))

        on-display-menu
        (mf/use-fn
         (fn []
           (cancel-timer! close-timer*)
           (cancel-timer! open-timer*)
           (mf/set-ref-val!
            open-timer*
            (ts/schedule 350
                         #(do
                            (reset! open* true)
                            (mf/set-ref-val! open-timer* nil))))))

        on-hide-menu
        (mf/use-fn
         (fn []
           (cancel-timer! open-timer*)
           (cancel-timer! close-timer*)
           (mf/set-ref-val!
            close-timer*
            (ts/schedule 350
                         #(do
                            (reset! open* false)
                            (mf/set-ref-val! close-timer* nil))))))]

    (mf/with-effect []
      (fn []
        (cancel-timer! open-timer*)
        (cancel-timer! close-timer*)))

    [:div {:class (stl/css :handler-menu)
           :on-pointer-enter on-display-menu
           :on-pointer-leave on-hide-menu}
     [:button {:class (stl/css :topbar-btn :handler-trigger)
               :title (tr "workspace.path.actions.handler-type")
               :aria-haspopup true
               :aria-expanded open?
               :on-click on-trigger-click}
      (handler-type-icon active-type)
      [:svg {:view-box "0 0 6 6"
             :aria-hidden true
             :class (stl/css :flyout-indicator)}
       [:path {:d "M4,2 L4,3.15 C4,3.62 3.62,4 3.15,4 L2,4"
               :stroke-linecap "round"}]]]
     [:div {:class (stl/css-case :handler-popover true :open open?)
            :data-dont-clear-path true}
      [:button {:class (stl/css-case :is-toggled (= active-type :mirror) :topbar-btn true)
                :title (tr "workspace.path.actions.handler-mirror")
                :on-click #(select :mirror)}
       handler-mirror-icon]
      [:button {:class (stl/css-case :is-toggled (= active-type :aligned) :topbar-btn true)
                :title (tr "workspace.path.actions.handler-aligned")
                :on-click #(select :aligned)}
       handler-aligned-icon]
      [:button {:class (stl/css-case :is-toggled (= active-type :independent) :topbar-btn true)
                :title (tr "workspace.path.actions.handler-independent")
                :on-click #(select :independent)}
       handler-independent-icon]]]))

(mf/defc path-actions*
  [{:keys [shape state]}]
  (let [{:keys [edit-mode selection snap-toggled]} state

        content (:content shape)

        ;; a selected segment contributes its endpoint nodes, so the node-op
        ;; buttons enable for segment selections too
        selected-nodes (path.helpers/selected-node-indices content selection)

        enabled-buttons
        (mf/use-memo
         (mf/deps content selection)
         #(check-enabled content (path.helpers/node-positions content selected-nodes)))

        on-select-draw-mode
        (mf/use-fn
         (fn [_]
           (st/emit! (drp/change-edit-mode :draw))))

        on-select-edit-mode
        (mf/use-fn
         (fn [_]
           (st/emit! (drp/change-edit-mode :move))))

        on-merge-nodes
        (mf/use-fn
         (mf/deps (:merge-nodes enabled-buttons))
         (fn [_]
           (when (:merge-nodes enabled-buttons)
             (st/emit! (drp/merge-nodes)))))

        on-join-nodes
        (mf/use-fn
         (mf/deps (:join-nodes enabled-buttons))
         (fn [_]
           (when (:join-nodes enabled-buttons)
             (st/emit! (drp/join-nodes)))))

        on-separate-nodes
        (mf/use-fn
         (mf/deps (:separate-nodes enabled-buttons))
         (fn [_]
           (when (:separate-nodes enabled-buttons)
             (st/emit! (drp/separate-nodes)))))

        on-make-corner
        (mf/use-fn
         (mf/deps (:make-corner enabled-buttons))
         (fn [_]
           (when (:make-corner enabled-buttons)
             (st/emit! (drp/make-corner)))))

        on-make-curve
        (mf/use-fn
         (mf/deps (:make-curve enabled-buttons))
         (fn [_]
           (when (:make-curve enabled-buttons)
             (st/emit! (drp/make-curve)))))

        on-toggle-snap
        (mf/use-fn
         (fn [_]
           (st/emit! (drp/toggle-snap))))

        ;; Node-op buttons are shown only when their action is available (the
        ;; bar has a fixed width, so it does not reflow). Group visibility drives
        ;; the divider layout below.
        structural-visible? (or (:merge-nodes enabled-buttons)
                                (:join-nodes enabled-buttons)
                                (:separate-nodes enabled-buttons))
        shape-visible?      (or (:make-corner enabled-buttons)
                                (:make-curve enabled-buttons))

        ;; Handler behaviour targets nodes, but a selected handler resolves to
        ;; its owning node, so the control is available for node OR handler
        ;; selections (in move mode). The button shows the current type (stored,
        ;; else derived from geometry). A corner node has no handler behaviour to
        ;; configure, so the control is hidden unless at least one target node is
        ;; a curve point (an actual offset handle, not a curve-to with collapsed
        ;; handles — `is-curve?`, the same test the make-corner/curve buttons use).
        handler-nodes       (path.helpers/handler-target-nodes content selection)
        handler-node        (some (fn [idx]
                                    (when (path.segm/is-curve? content (path.helpers/node-position content idx))
                                      idx))
                                  handler-nodes)
        active-handler-type (when handler-node
                              (or (get (:handler-types state) handler-node)
                                  (path.helpers/derive-handler-type content handler-node)))
        handler-visible?    (and (= edit-mode :move) (some? handler-node))

        ;; middle cluster = everything between the mode group and the snap group
        middle-visible?     (or structural-visible? shape-visible? handler-visible?)

        on-set-handler-type
        (mf/use-fn
         (fn [type]
           (st/emit! (drp/set-handler-type type))))]

    [:div {:class (stl/css :sub-actions)
           :data-dont-clear-path true}
     ;; Mode: draw / move (always visible)
     [:div {:class (stl/css :sub-actions-group)}
      [:button {:class (stl/css-case :is-toggled (= edit-mode :draw) :topbar-btn true)
                :title (tr "workspace.path.actions.draw-nodes" (sc/get-tooltip :draw-nodes))
                :on-click on-select-draw-mode}
       pentool-icon]
      [:button {:class (stl/css-case :is-toggled (= edit-mode :move) :topbar-btn true)
                :title (tr "workspace.path.actions.move-nodes" (sc/get-tooltip :move-nodes))
                :on-click on-select-edit-mode}
       move-icon]]

     [:div {:class (stl/css :separator)}]

     ;; Structural node ops: merge / join / separate
     (when structural-visible?
       [:div {:class (stl/css :sub-actions-group)}
        (when (:merge-nodes enabled-buttons)
          [:> topbar-button* {:title (tr "workspace.path.actions.merge-nodes" (sc/get-tooltip :merge-nodes))
                              :on-click on-merge-nodes
                              :icon merge-nodes-icon}])
        (when (:join-nodes enabled-buttons)
          [:> topbar-button* {:title (tr "workspace.path.actions.join-nodes" (sc/get-tooltip :join-nodes))
                              :on-click on-join-nodes
                              :icon join-nodes-icon}])
        (when (:separate-nodes enabled-buttons)
          [:> topbar-button* {:title (tr "workspace.path.actions.separate-nodes" (sc/get-tooltip :separate-nodes))
                              :on-click on-separate-nodes
                              :icon separate-nodes-icon}])])

     ;; Node shape ops: make corner / make curve
     (when shape-visible?
       [:div {:class (stl/css :sub-actions-group)}
        (when (:make-corner enabled-buttons)
          [:> topbar-button* {:title (tr "workspace.path.actions.make-corner" (sc/get-tooltip :make-corner))
                              :on-click on-make-corner
                              :icon to-corner-icon}])
        (when (:make-curve enabled-buttons)
          [:> topbar-button* {:title (tr "workspace.path.actions.make-curve" (sc/get-tooltip :make-curve))
                              :on-click on-make-curve
                              :icon to-curve-icon}])])

     ;; Handler behaviour of the selected node(s)
     (when handler-visible?
       [:> handler-type-menu* {:active-type active-handler-type
                               :on-select on-set-handler-type}])

     (when middle-visible?
       [:div {:class (stl/css :separator)}])

     ;; Toggle snap (always visible, pinned to the right)
     [:div {:class (stl/css :sub-actions-group :snap-group)}
      [:button {:class (stl/css-case :is-toggled snap-toggled :topbar-btn true)
                :title (tr "workspace.path.actions.snap-nodes" (sc/get-tooltip :snap-nodes))
                :on-click on-toggle-snap}
       snap-icon]]]))
