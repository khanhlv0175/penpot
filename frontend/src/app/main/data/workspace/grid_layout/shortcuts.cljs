;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.grid-layout.shortcuts)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Shortcuts
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Shortcuts format https://github.com/ccampbell/mousetrap

(def shortcuts
  {})

(defn get-tooltip [shortcut]
  (assert (contains? shortcuts shortcut) (str shortcut))
  (get-in shortcuts [shortcut :tooltip]))
