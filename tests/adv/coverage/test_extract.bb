#!/usr/bin/env bb
;;
;; Test the coverage extraction parsing logic against a sample
;; llvm-cov JSON fixture. Verifies field names and EDN shape match
;; the real llvm-cov export format.
;;
;; Usage: bb tests/adv/coverage/test_extract.bb

(require '[cheshire.core :as json])
(require '[clojure.test :refer [deftest is run-tests]])

(def fixture-path "tests/adv/coverage/fixtures/sample_llvm_cov.json")
(def json-str (slurp fixture-path))
(def data (json/parse-string json-str true))
(def totals (get-in data [:data 0 :totals]))

(defn extract-summary [totals]
  {:lines-covered     (get-in totals [:lines :covered] 0)
   :lines-total       (get-in totals [:lines :count] 0)
   :lines-percent     (get-in totals [:lines :percent] 0.0)
   :regions-covered   (get-in totals [:regions :covered] 0)
   :regions-total     (get-in totals [:regions :count] 0)
   :regions-percent   (get-in totals [:regions :percent] 0.0)
   :functions-covered (get-in totals [:functions :covered] 0)
   :functions-total   (get-in totals [:functions :count] 0)
   :functions-percent (get-in totals [:functions :percent] 0.0)})

(def summary (extract-summary totals))

(deftest parses-lines
  (is (= 2780 (:lines-covered summary)))
  (is (= 3200 (:lines-total summary)))
  (is (= 0.86875 (:lines-percent summary))))

(deftest parses-regions
  (is (= 1540 (:regions-covered summary)))
  (is (= 1800 (:regions-total summary))))

(deftest parses-functions
  (is (= 142 (:functions-covered summary)))
  (is (= 150 (:functions-total summary))))

(deftest summary-has-all-keys
  (doseq [k [:lines-covered :lines-total :lines-percent
             :regions-covered :regions-total :regions-percent
             :functions-covered :functions-total :functions-percent]]
    (is (contains? summary k) (str "missing key " k))))

(run-tests)
