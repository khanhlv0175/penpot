(ns app.main.ui.settings.shortcuts
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.i18n :refer [tr]]
   [app.common.json :as json]
   [app.main.data.dashboard.shortcuts]
   [app.main.data.modal :as modal]
   [app.main.data.shortcuts :as ds]
   [app.main.data.viewer.shortcuts]
   [app.main.data.workspace.path.shortcuts]
   [app.main.data.workspace.shortcuts]
   [app.main.store :as st]
   [app.main.ui.components.dropdown-menu :refer [dropdown-menu*
                                                 dropdown-menu-item*]]
   [app.main.ui.components.search-bar :refer [search-bar*]]
   [app.main.ui.context :as ctx]
   [app.main.ui.ds.buttons.button :refer [button*]]
   [app.main.ui.ds.foundations.assets.icon :refer [icon*] :as i]
   [app.main.ui.ds.foundations.typography :as t]
   [app.main.ui.ds.foundations.typography.heading :refer [heading*]]
   [app.main.ui.ds.layout.tab-switcher :refer [tab-switcher*]]
   [app.main.ui.ds.product.empty-state :refer [empty-state*]]
   [app.main.ui.settings.restore-shortcuts-modal]
   [app.main.ui.shortcuts :as ss]
   [app.util.dom :as dom]
   [app.util.strings :refer [matches-search]]
   [app.util.webapi :as wapi]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(mf/defc search-section*
  [{:keys [filter-term on-search-term-change on-search-clear-click on-restore-all show-restore-all? has-custom-shortcuts]}]
  [:div {:class (stl/css :shortcuts-search-section)}
   [:> search-bar* {:on-change on-search-term-change
                    :on-clear on-search-clear-click
                    :value filter-term
                    :placeholder (tr "shortcuts.title")
                    :icon-id i/search
                    :auto-focus true}]
   (when (and show-restore-all? has-custom-shortcuts)
     [:> button*
      {:variant "secondary"
       :on-click on-restore-all
       :class (stl/css :restore-all-button)
       :icon i/at}
      (tr "dashboard.restore-all-deleted-button")])])

(defn- filter-shortcuts-tree
  [tree shortcut-filter search-term]
  (let [sorted-comp (when (sorted? tree)
                      #(compare (name %1) (name %2)))
        result (keep (fn [[k node]]
                       (let [children (:children node)]
                         (cond
                           (map? children)
                           (let [filtered-children
                                 (filter-shortcuts-tree children shortcut-filter search-term)]
                             (when (seq filtered-children)
                               [k (assoc node :children filtered-children)]))

                           (shortcut-filter k node search-term)
                           [k node])))
                     tree)]
    (if sorted-comp
      (into (sorted-map-by sorted-comp) result)
      (into {} result))))

(defn- collect-open-section-ids
  [tree]
  (letfn [(walk [nodes]
            (mapcat
             (fn [node]
               (let [children (:children node)]
                 (concat
                  (when (:children node)
                    [(:id node)])
                  (when (map? children)
                    (walk (vals children))))))
             nodes))]
    (->> (walk (vals tree))
         (remove nil?)
         set)))

(mf/defc shortcuts-list*
  [{:keys [shortcuts open-sections filter-term custom-shortcuts editable? manage-sections conflicts]}]
  (let [sections (keep (fn [section]
                         (when (seq (get-in section [1 :children]))
                           section))
                       shortcuts)]
    [:div {:class (stl/css :shortcuts-list)
           :aria-label (tr "shortcuts.title")}
     (for [section sections]
       (let [[section-key _] section]
         [:> ss/shortcut-section* {:key (name section-key)
                                   :section section
                                   :manage-sections manage-sections
                                   :open-sections open-sections
                                   :filter-term filter-term
                                   :editable? editable?
                                   :custom-shortcuts custom-shortcuts
                                   :conflicts conflicts}]))]))

(mf/defc shortcuts-tab-section*
  [{:keys [shortcut-filter show-restore-all? on-restore-all empty-str custom-shortcuts conflicts]}]
  (let [{:keys [all-shortcuts]} (mf/use-ctx ctx/shortcuts-ctx)
        open-sections*   (mf/use-state [[0]])
        open-sections    (deref open-sections*)

        filter-term*     (mf/use-state "")
        filter-term      (deref filter-term*)

        section-has-content?
        (fn [section]
          (let [children (:children section)]
            (if (and (= (count children) 1) (contains? children :none))
              (seq (:children (:none children)))
              (seq children))))

        all-shortcuts           (into {} (filter (fn [[_ v]] (section-has-content? v)) all-shortcuts))
        filtered-shortcuts      (filter-shortcuts-tree all-shortcuts shortcut-filter filter-term)
        search-open-sections    (collect-open-section-ids filtered-shortcuts)
        effective-open-sections (if (str/blank? filter-term)
                                  open-sections
                                  search-open-sections)

        on-search-term-change
        (mf/use-fn
         (mf/deps all-shortcuts shortcut-filter)
         (fn [term]
           (reset! filter-term* term)
           (if (str/blank? term)
             (reset! open-sections* [[1]])
             (let [filtered-tree (filter-shortcuts-tree all-shortcuts shortcut-filter term)
                   open-ids (collect-open-section-ids filtered-tree)]
               (reset! open-sections* open-ids)))))

        on-search-clear-click
        (mf/use-fn
         (fn [_]
           (reset! open-sections* [[1]])
           (reset! filter-term* "")))

        manage-sections
        (fn [item]
          (fn [event]
            (dom/stop-propagation event)
            (let [is-present? (some #(= % item) open-sections)
                  new-value (if is-present?
                              (filterv (fn [element] (not= element item)) open-sections)
                              (conj open-sections item))]
              (reset! open-sections* new-value))))]

    [:div {:class (stl/css :shortcuts-section)}
     [:> search-section* {:filter-term filter-term
                          :on-search-term-change on-search-term-change
                          :on-search-clear-click on-search-clear-click
                          :on-restore-all on-restore-all
                          :show-restore-all? show-restore-all?
                          :has-custom-shortcuts (some #(seq (val %)) custom-shortcuts)}]
     (if (seq filtered-shortcuts)
       [:> shortcuts-list* {:shortcuts filtered-shortcuts
                            :open-sections effective-open-sections
                            :filter-term filter-term*
                            :custom-shortcuts custom-shortcuts
                            :editable? true
                            :manage-sections manage-sections
                            :conflicts conflicts}]

       [:> empty-state* {:text empty-str
                         :class (stl/css :shortcuts-empty-state)}])]))

(mf/defc shortcuts-page*
  [{:keys [profile]}]
  (let [section*        (mf/use-state :all)
        section         (deref section*)

        show-menu* (mf/use-state false)
        show-menu? (deref show-menu*)

        open-menu
        (mf/use-fn
         (fn [event]
           (dom/stop-propagation event)
           (reset! show-menu* true)))

        close-menu
        (mf/use-fn
         (fn [event]
           (dom/stop-propagation event)
           (reset! show-menu* false)))

        input-ref (mf/use-ref nil)

        tabs
        (mf/with-memo []
          [{:label (tr "labels.all")
            :id "all"}
           {:label (tr "shortcuts.personalized")
            :data-testid "personalized"
            :id "personalized"}
           {:label (tr "shortcuts.disabled")
            :data-testid "disabled"
            :id "disabled"}])

        handle-change-tab
        (mf/use-fn
         (fn [new-section]
           (reset! section* (keyword new-section))))

        custom-shortcuts           (get-in profile [:props :custom-shortcuts])

        path-shortcuts-custom (ds/apply-custom-overrides app.main.data.workspace.path.shortcuts/shortcuts custom-shortcuts :path)
        path-shortcuts-with-translation        (->> path-shortcuts-custom
                                                    (ss/add-translation :sc)
                                                    (into {}))

        workspace-shortcuts-custom (ds/apply-custom-overrides app.main.data.workspace.shortcuts/shortcuts custom-shortcuts :workspace)

        workspace-shortcuts-with-translation        (->> workspace-shortcuts-custom
                                                         (ss/add-translation :sc)
                                                         (into {}))

        dashboard-shortcuts-custom (ds/apply-custom-overrides app.main.data.dashboard.shortcuts/shortcuts custom-shortcuts :dashboard)

        dashboard-shortcuts-with-translation        (->> dashboard-shortcuts-custom
                                                         (ss/add-translation :sc)
                                                         (into {}))

        viewer-shortcuts-custom    (ds/apply-custom-overrides app.main.data.viewer.shortcuts/shortcuts custom-shortcuts :viewer)

        viewer-shortcuts-with-translation           (->> viewer-shortcuts-custom
                                                         (ss/add-translation :sc)
                                                         (into {}))

        all-shortcuts-raw (d/deep-merge workspace-shortcuts-custom path-shortcuts-custom path-shortcuts-custom dashboard-shortcuts-custom viewer-shortcuts-custom)

        {:keys [all-shortcuts]}
        (ss/build-all-shortcuts-without-basics
         workspace-shortcuts-with-translation
         path-shortcuts-with-translation
         dashboard-shortcuts-with-translation
         viewer-shortcuts-with-translation
         :workspace-orig app.main.data.workspace.shortcuts/shortcuts
         :path-orig app.main.data.workspace.path.shortcuts/shortcuts
         :dashboard-orig app.main.data.dashboard.shortcuts/shortcuts
         :viewer-orig app.main.data.viewer.shortcuts/shortcuts)

        shortcuts-ctx-value
        {:workspace-sc-trans (d/deep-merge workspace-shortcuts-with-translation path-shortcuts-with-translation)
         :dashboard-sc-trans dashboard-shortcuts-with-translation
         :viewer-sc-trans viewer-shortcuts-with-translation
         :all-shortcuts all-shortcuts
         :all-sc-raw all-shortcuts-raw}

        on-restore-all
        (mf/use-fn
         (mf/deps custom-shortcuts)
         (fn [event]
           (dom/stop-propagation event)
           (st/emit! (modal/show {:type :restore-all-modal
                                  :custom-shortcuts custom-shortcuts}))))

        on-import-file
        (mf/use-fn
         (fn [_]
           (dom/click (mf/ref-val input-ref))))

        shortcuts-json
        (mf/with-memo
          [custom-shortcuts]
          (some-> custom-shortcuts
                  (json/encode :key-fn d/name :indent 2)))

        on-export
        (mf/use-fn
         (mf/deps shortcuts-json)
         (fn []
           (->> (wapi/create-blob (or shortcuts-json "{}") "application/json")
                (dom/trigger-download "penpot-shortcuts.json"))))

        on-file-selected
        (mf/use-fn
         (mf/deps all-shortcuts)
         (fn [event]
           (let [file (-> (dom/get-target event)
                          (dom/get-files)
                          (first))]
             (->> (wapi/read-file-as-text file)
                  (rx/subs!
                   (fn [content]
                     (let [shortcuts (js->clj (.parse js/JSON content)
                                              :keywordize-keys true)]
                       (st/emit!
                        (ss/import-custom-shortcuts shortcuts all-shortcuts))))))

             (-> (mf/ref-val input-ref)
                 (dom/set-value! "")))))]

    [:section {:class (stl/css :shortcuts-page)
               :aria-label (tr "shortcuts.page")}
     [:div {:class (stl/css :shortcuts-content)}
      [:> heading* {:level 2
                    :typography t/title-large
                    :class (stl/css :page-title)}
       (tr "label.shortcuts")]

      [:> (mf/provider ctx/shortcuts-ctx) {:value shortcuts-ctx-value}
       [:div {:class (stl/css :shortcuts-content)}
        [:> tab-switcher* {:tabs tabs
                           :selected (name section)
                           :on-change handle-change-tab
                           :class (stl/css :shortcuts-switcher)}
         (case section
           :all
           [:> shortcuts-tab-section* {:shortcut-filter (fn [_ shortcut search-term]
                                                          (or (str/blank? search-term)
                                                              (matches-search (:translation shortcut) search-term)))
                                       :show-restore-all? true
                                       :empty-str (tr "shortcuts.no-shortcuts")
                                       :on-restore-all on-restore-all
                                       :custom-shortcuts custom-shortcuts}]

           :personalized
           [:> shortcuts-tab-section* {:shortcut-filter (fn [shortcut-key shortcut search-term]
                                                          (let [shortcut-group (first (:section shortcut))
                                                                group-map (get custom-shortcuts shortcut-group)
                                                                group-map (if (map? group-map) group-map {})
                                                                customized? (and (contains? group-map shortcut-key)
                                                                                 (not (str/blank? (get group-map shortcut-key))))]
                                                            (and customized?
                                                                 (or (str/blank? search-term)
                                                                     (matches-search (:translation shortcut) search-term)))))
                                       :show-restore-all? true
                                       :empty-str (tr "shortcuts.no-personalized")
                                       :on-restore-all on-restore-all
                                       :custom-shortcuts custom-shortcuts}]

           :disabled
           [:> shortcuts-tab-section* {:shortcut-filter (fn [shortcut-key shortcut search-term]
                                                          (let [shortcut-group (first (:section shortcut))
                                                                group-map (get custom-shortcuts shortcut-group)
                                                                group-map (if (map? group-map) group-map {})
                                                                in-group? (contains? group-map shortcut-key)
                                                                blank? (str/blank? (get group-map shortcut-key))]
                                                            (and in-group? blank?
                                                                 (or (str/blank? search-term)
                                                                     (matches-search (:translation shortcut) search-term)))))
                                       :show-restore-all? true
                                       :empty-str (tr "shortcuts.no-disabled")
                                       :on-restore-all on-restore-all
                                       :custom-shortcuts custom-shortcuts}])]]

       [:div {:class (stl/css :shortcuts-page-footer)}
        [:div {:class (stl/css :shortcuts-info)}
         [:div {:class (stl/css :shortcuts-info-wrapper)}
          [:div {:class (stl/css :dot-wrapper)}
           [:div {:class (stl/css :shortcuts-customized-dot)}]]
          [:p {:class (stl/css :shortcuts-text)}
           (tr "shortcuts.personalized")]]

         [:div {:class (stl/css :shortcuts-info-wrapper)}
          [:> icon* {:class (stl/css :shortcuts-not-assigned-icon)
                     :icon-id i/broken-link}]
          [:p {:class (stl/css :shortcuts-text)}
           (tr "shortcuts.disabled")]]]

        [:div {:class (stl/css :export-wrapper)}

         [:> button* {:variant "secondary"
                      :on-click open-menu
                      :icon i/import-export}
          (tr "shortcuts.import-export")]
         [:> dropdown-menu* {:show show-menu?
                             :on-close close-menu
                             :id "tokens-menu"
                             :class (stl/css :import-export-menu)}
          [:> dropdown-menu-item* {:class (stl/css :import-export-menu-item)
                                   :on-click on-import-file}
           [:div {:class (stl/css :import-menu-item)}
            [:div (tr "labels.import")]]]
          [:> dropdown-menu-item* {:class (stl/css :import-export-menu-item)
                                   :on-click on-export}
           (tr "labels.export")]]

         [:input
          {:type "file"
           :accept ".json,application/json"
           :ref input-ref
           :style {:display "none"}
           :on-change on-file-selected}]]]]]]))
