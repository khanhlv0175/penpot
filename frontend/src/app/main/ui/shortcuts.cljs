;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.shortcuts
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.config :as cf]
   [app.main.data.profile :as du]
   [app.main.data.shortcuts :as ds]
   [app.main.data.workspace.shortcuts.customize :as customize]
   [app.main.store :as st]
   [app.main.ui.context :as ctx]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.foundations.assets.icon :as i :refer [icon*]]
   [app.main.ui.ds.notifications.context-notification :refer [context-notification*]]
   [app.main.ui.ds.tooltip.tooltip :refer [tooltip*]]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [app.util.strings :refer [matches-search]]
   [cuerdas.core :as str]
   [goog.events :as events]
   [rumext.v2 :as mf]))

(def ^:private modifier-keys
  #{"Control" "Shift" "Alt" "Meta"})

(def ^:private key-name-map
  {"ArrowUp"    "up"
   "ArrowDown"  "down"
   "ArrowLeft"  "left"
   "ArrowRight" "right"
   "Escape"     "escape"
   "Enter"      "enter"
   "Backspace"  "backspace"
   "Delete"     "del"
   "Tab"        "tab"
   " "          "space"})

(defn- keyboard-event->mousetrap
  [^js event]
  (let [parts (cond-> []
                (and (.-ctrlKey event)
                     (not (cf/check-platform? :macos)))
                (conj "ctrl")

                (and (.-metaKey event)
                     (cf/check-platform? :macos))
                (conj "command")

                (.-altKey event)
                (conj "alt")

                (.-shiftKey event)
                (conj "shift"))
        key   (.-key event)
        key   (if (contains? modifier-keys key)
                nil
                (or (get key-name-map key)
                    (.toLowerCase key)))]
    (when key
      (str/join "+" (conj parts key)))))

(defn- keyboard-event->display-parts
  [^js event]
  (let [parts (cond-> []
                (and (.-ctrlKey event)
                     (not (cf/check-platform? :macos)))
                (conj "ctrl")

                (and (.-metaKey event)
                     (cf/check-platform? :macos))
                (conj "command")

                (.-altKey event)
                (conj "alt")

                (.-shiftKey event)
                (conj "shift"))
        key   (.-key event)]
    (if (contains? modifier-keys key)
      {:modifiers parts :finalized? false}
      {:modifiers parts
       :final-key (or (get key-name-map key) (.toLowerCase key))
       :finalized? true})))

(defn translation-keyname
  [type keyname]
  (let [translat-pre (case type
                       :sc      "shortcuts."
                       :sec     "shortcut-section."
                       :sub-sec "shortcut-subsection.")]
    (tr (str translat-pre (d/name keyname)))))

(defn- find-conflict
  [new-command all-shortcuts current-key]
  (let [command-index (ds/build-command-index all-shortcuts)]
    (when-let [conflicting-key (get command-index new-command)]
      (when (not= conflicting-key current-key)
        {:key  conflicting-key
         :name (translation-keyname :sc conflicting-key)}))))

(defn build-conflicts-map
  "Builds a map of shortcut-keys that have a conflict.
   A conflict is when a customized shortcut uses the same key combo as a
   non-customized shortcut's default command. Each shortcut-map is a
   separate context — conflicts are only checked within the same context.
   Returns a map of :keyword victim-key -> :keyword customized-key."
  [shortcut-maps custom-shortcuts]
  (reduce
   (fn [acc shortcuts]
     (let [defaults-index  (ds/build-command-index shortcuts)
           context-keys    (set (keys shortcuts))
           context-customs (into []
                                 (comp
                                  (filter (fn [[k _]] (contains? context-keys k)))
                                  (filter (fn [[k _]] (not (str/blank? (get custom-shortcuts k))))))
                                 custom-shortcuts)]
       (reduce
        (fn [result [sc-key custom-key]]
          (if-let [conflicting-key (get defaults-index custom-key)]
            (if (and (not= conflicting-key sc-key)
                     (not (contains? custom-shortcuts conflicting-key))
                     (str/blank? (get custom-shortcuts conflicting-key)))
              (assoc result conflicting-key sc-key)
              result)
            result))
        acc
        context-customs)))
   {}
   shortcut-maps))

(defn import-custom-shortcuts
  [shortcuts all-shortcuts]
  (let [current-customs (get-in shortcuts
                                [:profile :props :custom-shortcuts]
                                {})

        ;; imported shortcuts are in flat format, store as workspace group
        imported-ws     (reduce
                         (fn [acc [command recorded-command]]
                           (let [conflict (find-conflict recorded-command all-shortcuts command)]
                             (cond-> (assoc acc command recorded-command)
                               (:key conflict)
                               (assoc (:key conflict) ""))))
                         {}
                         (:workspace shortcuts))

        new-customs      (assoc current-customs :workspace imported-ws)]

    (du/update-profile-props {:custom-shortcuts new-customs})))

(defn add-translation
  [type item]
  (map (fn [[k v]] [k (assoc v :translation (translation-keyname type k))]) item))

(defn shortcuts->subsections
  [shortcuts]
  (let [subsections (into #{} (mapcat :subsections) (vals shortcuts))
        subsections (vec (sort-by name subsections))
        _ (prn "subsections" subsections)
        get-sc-by-subsection
        (fn [subsection [k v]]
          (when (some #(= subsection %) (:subsections v)) {k v}))
        reduce-sc
        (fn [acc subsection]
          (let [shortcuts-by-subsection (into {} (keep (partial get-sc-by-subsection subsection) shortcuts))]
            (assoc acc subsection {:children shortcuts-by-subsection})))]
    (reduce reduce-sc (sorted-map-by (fn [a b] (compare (name a) (name b)))) subsections)))

(defn build-all-shortcuts
  [workspace-sc dashboard-sc viewer-sc]
  (let [walk (fn walk [element parent-id]
               (if (nil? element)
                 element
                  (let [sorted-comp (when (sorted? element)
                                      #(compare (name %1) (name %2)))
                       rec-fn (fn [index [k item]]
                                (let [item-id (if (nil? parent-id)
                                                [index]
                                                (conj parent-id index))]
                                  [k (assoc item :id item-id :children (walk (:children item) item-id))]))]
                   (if sorted-comp
                     (into (sorted-map-by sorted-comp) (map-indexed (partial rec-fn) element))
                     (into {} (map-indexed (partial rec-fn) element))))))
        sorted-map-fn #(sorted-map-by (fn [a b] (compare (name a) (name b))))
        ws-subs (->> (shortcuts->subsections workspace-sc)
                     (add-translation :sub-sec)
                     (into (sorted-map-fn)))

        db-subs (->> (shortcuts->subsections dashboard-sc)
                     (add-translation :sub-sec)
                     (into (sorted-map-fn)))
        vw-subs (->> (shortcuts->subsections viewer-sc)
                     (add-translation :sub-sec)
                     (into (sorted-map-fn)))
        basics  (into {} (concat (:children (:basics ws-subs))
                                 (:children (:basics db-subs))
                                 (:children (:basics vw-subs))))
        ws-subs (dissoc ws-subs :basics)
        db-subs (dissoc db-subs :basics)
        vw-subs (dissoc vw-subs :basics)
        all     {:basics    {:id [1]
                             :children {:none {:children basics}}
                             :translation (tr "shortcut-section.basics")}
                 :workspace {:id [2]
                             :children ws-subs
                             :translation (tr "shortcut-section.workspace")}
                 :dashboard {:id [3]
                             :children db-subs
                             :translation (tr "shortcut-section.dashboard")}
                 :viewer    {:id [4]
                             :children vw-subs
                             :translation (tr "shortcut-section.viewer")}}
        all     (walk all nil)
        all-sc-names (map #(translation-keyname :sc %)
                          (concat (keys workspace-sc)
                                  (keys dashboard-sc)
                                  (keys viewer-sc)))
        all-sub-names (map #(translation-keyname :sub-sec %)
                           (concat (keys ws-subs)
                                   (keys db-subs)
                                   (keys vw-subs)))
        all-section-names (map #(translation-keyname :sec %) (keys all))]
    {:all-shortcuts all
     :all-sc-names all-sc-names
     :all-sub-names all-sub-names
     :all-section-names all-section-names}))

(defn build-all-shortcuts-without-basics
  [workspace-sc path-sc dashboard-sc viewer-sc]
  (let [walk (fn walk [element parent-id]
               (if (nil? element)
                 element
                  (let [sorted-comp (when (sorted? element)
                                      #(compare (name %1) (name %2)))
                       rec-fn (fn [index [k item]]
                                (let [item-id (if (nil? parent-id)
                                                [index]
                                                (conj parent-id index))]
                                  [k (assoc item :id item-id :children (walk (:children item) item-id))]))]
                   (if sorted-comp
                     (into (sorted-map-by sorted-comp) (map-indexed (partial rec-fn) element))
                     (into {} (map-indexed (partial rec-fn) element))))))
        sorted-map-fn #(sorted-map-by (fn [a b] (compare (name a) (name b))))
        ws-subs (->> (shortcuts->subsections workspace-sc)
                     (add-translation :sub-sec)
                     (into (sorted-map-fn)))

        pt-subs (->> (shortcuts->subsections path-sc)
                     (add-translation :sub-sec)
                     (into (sorted-map-fn)))
        db-subs (->> (shortcuts->subsections dashboard-sc)
                     (add-translation :sub-sec)
                     (into (sorted-map-fn)))
        vw-subs (->> (shortcuts->subsections viewer-sc)
                     (add-translation :sub-sec)
                     (into (sorted-map-fn)))
        ws-subs  (dissoc ws-subs :basics)
        pt-subs  (dissoc pt-subs :basics)
        db-subs  (dissoc db-subs :basics)
        vw-subs  (dissoc vw-subs :basics)
        ws-subs  (merge ws-subs pt-subs)
        all      {:workspace {:id [1]
                              :children ws-subs
                              :translation (tr "shortcut-section.workspace")}
                  :dashboard {:id [2]
                              :children db-subs
                              :translation (tr "shortcut-section.dashboard")}
                  :viewer    {:id [3]
                              :children vw-subs
                              :translation (tr "shortcut-section.viewer")}}
        all      (walk all nil)
        all-sc-names (map #(translation-keyname :sc %)
                          (concat (keys workspace-sc)
                                  (keys path-sc)
                                  (keys dashboard-sc)
                                  (keys viewer-sc)))
        all-sub-names (map #(translation-keyname :sub-sec %)
                           (concat (keys ws-subs)
                                   (keys db-subs)
                                   (keys vw-subs)))
        all-section-names (map #(translation-keyname :sec %) (keys all))]
    {:all-shortcuts all
     :all-sc-names all-sc-names
     :all-sub-names all-sub-names
     :all-section-names all-section-names}))

(mf/defc converted-chars*
  [{:keys [char command class]}]
  (let [modified-keys {:up    ds/up-arrow
                       :down  ds/down-arrow
                       :left  ds/left-arrow
                       :right ds/right-arrow
                       :plus "+"}
        macos-keys    {:command "\u2318"
                       :option  "\u2325"
                       :alt     "\u2325"
                       :delete  "\u232B"
                       :del     "\u232B"
                       :shift   "\u21E7"
                       :control "\u2303"
                       :esc     "\u238B"
                       :enter   "\u23CE"}
        is-macos?     (cf/check-platform? :macos)
        char          (if (contains? modified-keys (keyword char)) ((keyword char) modified-keys) char)
        char          (if (and is-macos? (contains? macos-keys (keyword char))) ((keyword char) macos-keys) char)
        unique-key    (str (d/name command) "-" char)]
    [:span {:class [class (stl/css :key)]
            :key unique-key} char]))

(mf/defc shortcuts-keys*
  [{:keys [content command is-customized has-conflict?]}]
  (let [managed-list    (if (coll? content)
                          content
                          (conj () content))
        chars-list      (map ds/split-sc managed-list)
        last-element    (last chars-list)
        short-char-list (if (= 1 (count chars-list))
                          chars-list
                          (drop-last chars-list))
        penultimate     (last short-char-list)]
    [:span {:class (stl/css :keys)}
     (for [chars short-char-list]
       [:* {:key (str/join chars)}
        (for [char chars]
          [:> converted-chars* {:key (dm/str char "-" (name command))
                                :char char
                                :command command
                                :class (cond
                                         has-conflict? (stl/css :conflict-key)
                                         is-customized (stl/css :customized-key))}])

        (when (not= chars penultimate) [:span {:class (stl/css :space)} ","])])
     (when (not= last-element penultimate)
       [:*
        [:span {:class (stl/css :space)} (tr "shortcuts.or")]
        (for [char last-element]
          [:> converted-chars* {:key (dm/str char "-" (name command))
                                :char char
                                :command command
                                :class (cond
                                         has-conflict? (stl/css :conflict-key)
                                         is-customized (stl/css :customized-key))}])])]))

(mf/defc shortcut-row-editable*
  [{:keys [elements custom-shortcuts command-translate conflicts section-key]}]
  (let [{:keys [workspace-sc-trans dashboard-sc-trans viewer-sc-trans]} (mf/use-ctx ctx/shortcuts-ctx)
        section-shortcuts (case section-key
                            (:workspace :path) workspace-sc-trans
                            :dashboard dashboard-sc-trans
                            :viewer viewer-sc-trans
                            workspace-sc-trans)
        sc-by-translate  (first (filter #(= (:translation (second %)) command-translate) elements))
        [command  comand-info] sc-by-translate
        content                (or (:show-command comand-info) (:command comand-info))
        group-map              (get custom-shortcuts section-key)
        group-map              (if (map? group-map) group-map {})
        customized?            (contains? group-map command)
        has-conflict?          (contains? conflicts command)
        is-editing*            (mf/use-state false)
        is-editing             (deref is-editing*)

        recording-ref          (mf/use-ref nil)

        display-parts*         (mf/use-state nil)
        display-parts          (deref display-parts*)

        recorded-command*      (mf/use-state nil)
        recorded-command       (deref recorded-command*)

        conflict*              (mf/use-state nil)
        conflict               (deref conflict*)

        clean-editing-state
        (fn []
          (reset! is-editing* false)
          (reset! display-parts* nil)
          (reset! recorded-command* nil)
          (reset! conflict* nil))

        on-reset-shortcut
        (mf/use-fn
         (mf/deps section-key)
         (fn [shortcut-key]
           (st/emit! (customize/reset-custom-shortcut shortcut-key section-key))
           (clean-editing-state)))

        start-editing
        (mf/use-fn
         (fn [_]
           (reset! is-editing* true)
           (reset! display-parts* nil)
           (reset! recorded-command* nil)
           (reset! conflict* nil)))

        stop-editing
        (mf/use-fn
         (fn [event]
           (dom/stop-propagation event)
           (clean-editing-state)))

        disabled-shortcut
        (mf/use-fn
         (mf/deps command section-key)
         (fn [event]
           (dom/prevent-default event)
           (st/emit! (customize/set-custom-shortcut
                      command
                      ""
                      nil
                      section-key))
           (clean-editing-state)))

        save-fn
        (mf/use-fn
         (mf/deps recorded-command conflict command section-key)
         (fn [event]
           (dom/prevent-default event)
           (when recorded-command
             (st/emit! (customize/set-custom-shortcut
                        command
                        recorded-command
                        (:key conflict)
                        section-key)))
           (clean-editing-state)))

        on-editable-container-blur
        (mf/use-fn
         (fn [e]
           (when-not (.contains (.-currentTarget e)
                                (.-relatedTarget e))
             (clean-editing-state))))]

    (mf/with-effect [is-editing]
      (when is-editing
        (some-> (mf/ref-val recording-ref) (dom/focus!))))

    (mf/with-effect [is-editing]
      (when is-editing
        (letfn [(on-keydown [^js event]
                  (.preventDefault event)
                  (.stopPropagation event)
                  (let [recorded-command (keyboard-event->mousetrap event)
                        parts   (keyboard-event->display-parts event)]
                    (reset! display-parts* parts)
                    (when recorded-command
                      (reset! recorded-command* recorded-command)
                      (reset! conflict* (find-conflict recorded-command section-shortcuts command)))))]
          (->> (events/listen (mf/ref-val recording-ref) "keydown" on-keydown)
               (partial events/unlistenByKey)))))


    [:li {:class (stl/css-case :shortcuts-name-editable true
                               :shortcuts-editing is-editing
                               :customized customized?)
          :key command-translate}
     [:button {:on-click start-editing
               :disabled is-editing
               :class (stl/css-case :shortcut-button-editable true
                                    :shortcut-button-editing is-editing)}
      [:span {:class (stl/css :command-name)}
       command-translate]
      [:div {:class (stl/css :shortcut-actions)}
       (if (and customized? (str/blank? content))
         [:> icon* {:icon-id i/broken-link
                    :size "s"
                    :class (stl/css :shortcut-detach-icon)}]

         (if has-conflict?
           (let [conflicting-key (get conflicts command)
                 conflict-name   (translation-keyname :sc conflicting-key)]
             ;;TODO Add translation for "This shortcut has been overridden by ..."
             [:> tooltip* {:content (str "This shortcut has been overridden by " conflict-name)
                           :id (dm/str (name command) "-conflict-tooltip")
                           :class (stl/css :option-text)}
              [:> shortcuts-keys* {:content content
                                   :command command
                                   :is-customized customized?
                                   :has-conflict? has-conflict?}]])
           [:> shortcuts-keys* {:content content
                                :command command
                                :is-customized customized?
                                :has-conflict? has-conflict?}]))]]
     (when is-editing
       [:div {:class (stl/css :shortcut-editing)
              :ref recording-ref
              :tab-index 0
              :on-blur on-editable-container-blur}
        [:div {:class (stl/css :shortcut-recording-area)}
         (if (nil? display-parts)
           [:span {:class (stl/css :shortcut-placeholder)}
            (tr "shortcuts.key-combo")]
           [:span {:class (stl/css :shortcut-recorded-keys)}
            (for [mod (:modifiers display-parts)]
              [:span {:class (stl/css-case :key true
                                           :customized-key customized?) :key mod} mod])
            (when (:final-key display-parts)
              [:span {:class (stl/css-case :key true
                                           :customized-key customized?)} (:final-key display-parts)])
            (when-not (:finalized? display-parts)
              [:span {:class (stl/css :recording-ellipsis)} "..."])])]
        (when recorded-command
          (if conflict
            [:> context-notification* {:level :warning :class (stl/css :modal-error-msg)}
             (tr "shortcuts.edit-modal.conflict" (:name conflict))]
            [:> context-notification* {:level :success :class (stl/css :modal-success-msg)}
             (tr "shortcuts.edit-modal.success")]))
        [:div {:class (stl/css :edit-buttons)}
         [:div {:class (stl/css :confirmation-buttons)}
          [:> icon-button* {:variant "secondary"
                            :aria-label (tr "labels.cancel")
                            :on-click stop-editing
                            :icon i/close
                            :icon-size "m"}]
          [:> icon-button* {:variant "primary"
                            :aria-label (tr "labels.save")
                            :on-click save-fn
                            :icon i/tick
                            :icon-size "m"}]]
         [:div {:class (stl/css :confirmation-buttons)}
          [:> icon-button* {:variant "secondary"
                            :aria-label "disable shortcut"
                            :on-click (fn [e]
                                        (dom/stop-propagation e)
                                        (disabled-shortcut command))
                            :icon i/broken-link
                            :icon-size "m"}]
          (when customized?
            [:> icon-button* {:variant "secondary"
                              :aria-label (tr "shortcuts.reset")
                              :on-click (fn [e]
                                          (dom/stop-propagation e)
                                          (on-reset-shortcut command))
                              :icon i/reload
                              :icon-size "m"}])]]])]))

(mf/defc shortcut-row*
  [{:keys [elements filter-term is-match-section is-match-subsection
           editable? custom-shortcuts section-key conflicts]}]
  (let [shortcut-translations (->> elements vals (map :translation) sort)
        match-shortcut?       (some #(matches-search % @filter-term) shortcut-translations)
        filtered              (if (and (or is-match-section is-match-subsection) (not match-shortcut?))
                                shortcut-translations
                                (filter #(matches-search % @filter-term) shortcut-translations))
        sorted-filtered       (sort filtered)
        trigger-ref            (mf/use-ref nil)]

    [:ul {:class (stl/css :sub-menu)}
     (for [command-translate sorted-filtered]
       (let [sc-by-translate  (first (filter #(= (:translation (second %)) command-translate) elements))
             [command  comand-info] sc-by-translate
             content                (or (:show-command comand-info) (:command comand-info))
             group-map              (get custom-shortcuts section-key)
             group-map              (if (map? group-map) group-map {})
             customized?            (contains? group-map command)
             has-conflict?          (contains? conflicts command)]
         (if editable?
           [:> shortcut-row-editable* {:elements elements
                                       :custom-shortcuts custom-shortcuts
                                       :section-key section-key
                                       :key command-translate
                                       :command-translate command-translate
                                       :conflicts conflicts}]
           [:li {:class (stl/css-case :shortcuts-name true
                                      :customized customized?)
                 :key command-translate}
            [:span {:class (stl/css :command-name)}
             command-translate]
            [:div {:class (stl/css :shortcut-actions)}
             (if (and customized? (str/blank? content))
               ;; TODO shortctus, add translation
               [:> tooltip* {:content "Not assigned shortcut"
                             :trigger-ref trigger-ref
                             :id (dm/str command-translate "-tooltip")
                             :class (stl/css :option-text)}
                [:> icon* {:icon-id i/broken-link
                           :aria-labelledby (dm/str command-translate "-tooltip")
                           :size "s"
                           :ref trigger-ref
                           :class (stl/css :shortcut-detach-icon)}]]
               (if has-conflict?
                 (let [conflicting-key (get conflicts command)
                       conflict-name   (translation-keyname :sc conflicting-key)]
                   [:> tooltip* {:content (str "This shortcut has been overridden by " conflict-name)
                                 :id (dm/str (name command) "-conflict-tooltip")
                                 :class (stl/css :option-text)}
                    [:> shortcuts-keys* {:content content
                                         :command command
                                         :is-customized customized?
                                         :has-conflict? has-conflict?}]])
                 [:> shortcuts-keys* {:content content
                                      :command command
                                      :is-customized customized?
                                      :has-conflict? has-conflict?}]))]])))]))


(mf/defc section-title*
  [{:keys [name is-visible is-sub on-click]}]
  [:div {:class (if is-sub
                  (stl/css :subsection-title)
                  (stl/css :section-title))
         :on-click on-click}
   [:> icon* {:icon-id (if is-visible i/arrow-down i/arrow-right)
              :size "s"}]
   [:span {:class (if is-sub
                    (stl/css :subsection-name)
                    (stl/css :section-name))} name]])

(defn- normalize-open-sections
  [open-sections]
  (if (satisfies? cljs.core/IDeref open-sections)
    @open-sections
    open-sections))

(mf/defc shortcut-subsection*
  [{:keys [subsections manage-sections filter-term is-match-section open-sections
           editable? custom-shortcuts section-key conflicts]}]
  (let [open-sections (normalize-open-sections open-sections)]
    (if (= :none (first (keys subsections)))
      [:> shortcut-row* {:elements (:children (:none subsections))
                         :filter-term filter-term
                         :is-match-section is-match-section
                         :is-match-subsection true
                         :editable? editable?
                         :custom-shortcuts custom-shortcuts
                         :section-key section-key
                         :conflicts conflicts}]

      [:ul {:class (stl/css :subsection-menu)}
       (for [[sub-name sub-info] subsections]
         (let [visible? (some #(= % (:id sub-info)) open-sections)]
           [:li {:key (name sub-name)}
            [:> section-title* {:name (:translation sub-info)
                                :is-visible visible?
                                :is-sub true
                                :on-click (manage-sections (:id sub-info))}]

            [:div {:style {:display (if visible? "initial" "none")}}
             [:> shortcut-row* {:elements (:children sub-info)
                                :filter-term filter-term
                                :is-match-section is-match-section
                                :is-match-subsection true
                                :editable? editable?
                                :custom-shortcuts custom-shortcuts
                                :section-key section-key
                                :conflicts conflicts}]]]))])))


(mf/defc shortcut-section*
  [{:keys [section manage-sections open-sections filter-term
           editable? custom-shortcuts conflicts]}]
  (let [open-sections (normalize-open-sections open-sections)
        [section-key section-info] section
        section-id          (:id section-info)
        section-translation (:translation section-info)
        subsections         (:children section-info)
        visible?            (some #(= % section-id) open-sections)]
    [:div {:class (stl/css :section)}
     [:> section-title* {:name section-translation
                         :is-visible visible?
                         :is-sub false
                         :on-click (manage-sections section-id)}]

     [:div {:style {:display (if visible? "initial" "none")}}
      [:> shortcut-subsection* {:subsections subsections
                                :open-sections open-sections
                                :manage-sections manage-sections
                                :is-match-section false
                                :filter-term filter-term
                                :editable? editable?
                                :custom-shortcuts custom-shortcuts
                                :section-key section-key
                                :conflicts conflicts}]]]))
