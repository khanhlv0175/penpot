;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.files.repair-test
  "Tests for the validate / repair functions in app.common.files.validate
   and app.common.files.repair. 

   The tests generate cases of broken files and check that the validation functions
   generate accurate errors, and that the repair functions return the file to
   a stable state."
  (:require
   [app.common.files.repair :as cfr]
   [app.common.files.validate :as cfv]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]
   [app.common.test-helpers.variants :as thv]
   [clojure.test :as t]))

(t/use-fixtures :each thi/test-fixture)

(t/deftest repair-not-a-variant
  (t/testing "detect and repair a variant component whose root shape is not a variant"
    (let [file    (-> (thf/sample-file :file1 :page-label :page1)
                      (thv/add-variant :variant1 :component1 :root1 :component2 :root2)
                      (ths/update-shape :root1 :variant-id nil))

          errors  (cfv/validate-file file {})
          changes (cfr/repair-file file {} errors)
          file'   (thf/apply-changes file {:redo-changes changes} :valdate? false)
          errors' (cfv/validate-file file' {})

          root1'  (ths/get-shape file' :root1 :page-label :page1)]

      (t/is (= 2 (count errors)))  ;; There are two different checks that detect the same problem
      (t/is (= :not-a-variant (:code (first errors))))

      (t/is (nil? errors'))
      (t/is (= (thi/id :variant1) (:variant-id root1'))))))
  
  