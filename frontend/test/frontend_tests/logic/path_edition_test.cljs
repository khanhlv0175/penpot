;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.logic.path-edition-test
  (:require
   [app.common.geom.point :as gpt]
   [app.common.test-helpers.compositions :as ctho]
   [app.common.test-helpers.files :as cthf]
   [app.common.test-helpers.shapes :as cths]
   [app.common.types.path :as path]
   [app.main.data.workspace.edition :as dwe]
   [app.main.data.workspace.path.drawing :as path.drawing]
   [app.main.data.workspace.path.edition :as path.edition]
   [app.main.data.workspace.path.state :as path.state]
   [app.main.data.workspace.path.streams :as path.streams]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.state :as ths]))

(defn- setup-rect-file
  []
  (ctho/add-rect (cthf/sample-file :file1)
                 :rect1
                 :x 10
                 :y 20
                 :width 100
                 :height 80))

(defn- start-path-edition-events
  [id]
  [(dwe/start-edition-mode id)
   (path.edition/start-path-edit id)])

(defn- move-drawing-content
  [delta]
  (fn [state]
    (path.state/set-content
     state
     (path/move-content (path.state/get-path state :content) delta))))

(t/deftest clear-edition-mode-finishes-path-streams
  (t/is (path.streams/finish-edition? (dwe/clear-edition-mode)))
  (t/is (not (path.streams/finish-edition? :interrupt))))

(t/deftest editing-path-only-updates-drawing-copy
  (t/async
    done
    (let [file          (setup-rect-file)
          original-rect (cths/get-shape file :rect1)
          id            (:id original-rect)
          delta         (gpt/point 10 5)
          store         (ths/setup-store file)
          events        (conj (start-path-edition-events id)
                              (move-drawing-content delta))]
      (ths/run-store
       store done events
       (fn [new-state]
         (let [file'        (ths/get-file-from-state new-state)
               stored-shape (cths/get-shape file' :rect1)
               drawing-copy (get-in new-state [:workspace-drawing :object])]
           (t/is (= original-rect stored-shape))
           (t/is (= :path (:type drawing-copy)))
           (t/is (= (path/move-content
                     (:content (path/convert-to-path original-rect))
                     delta)
                    (:content drawing-copy)))))))))

(t/deftest unchanged-path-edition-preserves-simple-shape
  (t/async
    done
    (let [file          (setup-rect-file)
          original-rect (cths/get-shape file :rect1)
          id            (:id original-rect)
          store         (ths/setup-store file)
          events        (conj (start-path-edition-events id) :interrupt)]
      (ths/run-store
       store done events
       (fn [new-state]
         (let [file'        (ths/get-file-from-state new-state)
               stored-shape (cths/get-shape file' :rect1)]
           (t/is (= original-rect stored-shape))
           (t/is (= :rect (:type stored-shape)))
           (t/is (nil? (get-in new-state [:workspace-drawing :object])))))))))

(t/deftest changed-path-edition-is-persisted-when-finalized
  (t/async
    done
    (let [file            (setup-rect-file)
          original-rect   (cths/get-shape file :rect1)
          id              (:id original-rect)
          delta           (gpt/point 10 5)
          original-path   (path/convert-to-path original-rect)
          changed-content (path/move-content (:content original-path) delta)
          expected-shape  (-> original-path
                              (assoc :content changed-content)
                              (path/update-geometry))
          store           (ths/setup-store file)
          events          (into (start-path-edition-events id)
                                [(move-drawing-content delta)
                                 :interrupt])]
      (ths/run-store
       store done events
       (fn [new-state]
         (let [file'        (ths/get-file-from-state new-state)
               stored-shape (cths/get-shape file' :rect1)]
           (t/is (= expected-shape stored-shape))
           (t/is (= :path (:type stored-shape)))
           (t/is (nil? (get-in new-state [:workspace-drawing :object])))))))))

(t/deftest created-path-edition-cleans-drawing-state-on-exit
  (t/async
    done
    (let [file          (setup-rect-file)
          original-rect (cths/get-shape file :rect1)
          id            (:id original-rect)
          store         (ths/setup-store file)
          events        [(path.drawing/start-created-path-edition id)
                         :interrupt]]
      (ths/run-store
       store done events
       (fn [new-state]
         (let [file'        (ths/get-file-from-state new-state)
               stored-shape (cths/get-shape file' :rect1)]
           (t/is (= original-rect stored-shape))
           (t/is (nil? (get-in new-state [:workspace-local :edition])))
           (t/is (nil? (get-in new-state [:workspace-local :edit-path id])))
           (t/is (nil? (get-in new-state [:workspace-drawing :object])))))))))
