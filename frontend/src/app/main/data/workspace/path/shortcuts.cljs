;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.path.shortcuts
  (:require
   [app.main.data.shortcuts :as ds]
   [app.main.data.workspace.path :as drp]
   [app.main.store :as st]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Shortcuts
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Shortcuts format https://github.com/ccampbell/mousetrap

(def shortcuts
  {:move-nodes      {:tooltip "M"
                     :command "m"
                     :subsections [:path-editor]
                     :section [:workspace]
                     :fn #(st/emit! (drp/change-edit-mode :move))}

   :draw-nodes      {:tooltip "P"
                     :command "p"
                     :subsections [:path-editor]
                     :section [:workspace]
                     :fn #(st/emit! (drp/change-edit-mode :draw))}

   :add-node        {:tooltip (ds/shift "+")
                     :command "shift++"
                     :subsections [:path-editor]
                     :section [:workspace]
                     :fn #(st/emit! (drp/add-node))}

   :delete-node     {:tooltip (ds/supr)
                     :command ["del" "backspace"]
                     :subsections [:path-editor]
                     :section [:workspace]
                     :fn #(st/emit! (drp/remove-node))}

   :merge-nodes     {:tooltip (ds/meta "J")
                     :command (ds/c-mod "j")
                     :subsections [:path-editor]
                     :section [:workspace]
                     :fn #(st/emit! (drp/merge-nodes))}

   :join-nodes      {:tooltip "J"
                     :command "j"
                     :subsections [:path-editor]
                     :section [:workspace]
                     :fn #(st/emit! (drp/join-nodes))}

   :separate-nodes  {:tooltip "K"
                     :command "k"
                     :subsections [:path-editor]
                     :section [:workspace]
                     :fn #(st/emit! (drp/separate-nodes))}

   :make-corner     {:tooltip "X"
                     :command "x"
                     :subsections [:path-editor]
                     :section [:workspace]
                     :fn #(st/emit! (drp/make-corner))}

   :make-curve      {:tooltip "C"
                     :command "c"
                     :subsections [:path-editor]
                     :section [:workspace]
                     :fn #(st/emit! (drp/make-curve))}

   :snap-nodes      {:tooltip (ds/meta "'")
                     ;;https://github.com/ccampbell/mousetrap/issues/85
                     :command [(ds/c-mod "'") (ds/c-mod "219")]
                     :subsections [:path-editor]
                     :section [:workspace]
                     :fn #(st/emit! (drp/toggle-snap))}

   ;; Arrow movement

   :move-fast-up    {:tooltip (ds/shift ds/up-arrow)
                     :command "shift+up"
                     :section [:workspace]
                     :fn #(st/emit! (drp/move-selected :up true))}

   :move-fast-down  {:tooltip (ds/shift ds/down-arrow)
                     :command "shift+down"
                     :section [:workspace]
                     :fn #(st/emit! (drp/move-selected :down true))}

   :move-fast-right {:tooltip (ds/shift ds/right-arrow)
                     :command "shift+right"
                     :section [:workspace]
                     :fn #(st/emit! (drp/move-selected :right true))}

   :move-fast-left  {:tooltip (ds/shift ds/left-arrow)
                     :command "shift+left"
                     :section [:workspace]
                     :fn #(st/emit! (drp/move-selected :left true))}

   :move-unit-up    {:tooltip ds/up-arrow
                     :command "up"
                     :section [:workspace]
                     :fn #(st/emit! (drp/move-selected :up false))}

   :move-unit-down  {:tooltip ds/down-arrow
                     :command "down"
                     :section [:workspace]
                     :fn #(st/emit! (drp/move-selected :down false))}

   :move-unit-left  {:tooltip ds/right-arrow
                     :command "right"
                     :section [:workspace]
                     :fn #(st/emit! (drp/move-selected :right false))}

   :move-unit-right {:tooltip ds/left-arrow
                     :command "left"
                     :section [:workspace]
                     :fn #(st/emit! (drp/move-selected :left false))}})

(defn get-tooltip [shortcut]
  (assert (contains? shortcuts shortcut) (str shortcut))
  (get-in shortcuts [shortcut :tooltip]))
