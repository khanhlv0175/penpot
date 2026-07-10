;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.path.changes
  (:require
   [app.common.files.changes-builder :as pcb]
   [app.common.types.path :as path]
   [app.main.data.changes :as dch]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.shapes :as dwsh]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

(defn- normalize-content
  [content preserve-move-to]
  (if (and (not preserve-move-to)
           (= (-> content last :command) :move-to))
    (path/content (take (dec (count content)) content))
    (path/content content)))

(defn finalize-path-content
  [id]
  (ptk/reify ::finalize-path-content
    ptk/WatchEvent
    (watch [it state _]
      (let [page-id      (:current-page-id state)
            objects      (dsh/lookup-page-objects state page-id)
            shape        (get objects id)
            old-content  (get-in state [:workspace-local :edit-path id :old-content])
            edit-content (get-in state [:workspace-drawing :object :content])
            new-content  (some-> edit-content (normalize-content false))]
        (cond
          (or (nil? shape)
              (nil? old-content)
              (nil? edit-content)
              (= old-content edit-content))
          (rx/empty)

          (empty? new-content)
          (let [changes (-> (pcb/empty-changes it page-id)
                            (pcb/with-objects objects)
                            (pcb/remove-objects [id])
                            (pcb/resize-parents [id]))]
            (rx/of (dch/commit-changes changes)))

          :else
          (rx/of
           (dwsh/update-shapes
            [id]
            (fn [shape]
              (-> shape
                  (path/convert-to-path)
                  (assoc :content new-content)
                  (path/update-geometry)))
            {:reg-objects? true})))))))
