(require "tests/test")
(require '[mino-tests.tasks.impl :as impl])

;; Test the llvm-cov report text parser against a fixture that
;; matches the sample JSON fixture's values. Verifies the parser
;; extracts the right numbers from the text format.

(def fixture-path "tests/adv/coverage/fixtures/sample_llvm_cov_report.txt")
(def report-text (slurp fixture-path))
(def summary (impl/parse-coverage-report report-text))

(deftest parses-lines
  (is (= 2780 (:lines-covered summary)))
  (is (= 3200 (:lines-total summary))))

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

(deftest returns-nil-for-no-total
  (is (nil? (impl/parse-coverage-report "no TOTAL line here"))))

(run-tests-and-exit)
