(require "tests/test")
(require '[mino-tests.tasks.impl :as impl])

;; The parser runs against real llvm-cov output captured from an
;; instrumented harness build (Apple LLVM, branch columns present).
;; The expected numbers cross-check against `llvm-cov export
;; -summary-only` JSON for the same profile: lines 450/539, regions
;; 480/596, functions 38/45.

(def fixture-path "tests/adv/coverage/fixtures/sample_llvm_cov_report.txt")
(def report-text (slurp fixture-path))
(def summary (impl/parse-coverage-report report-text))

(deftest parses-lines
  (is (= 450 (:lines-covered summary)))
  (is (= 539 (:lines-total summary))))

(deftest parses-regions
  (is (= 480 (:regions-covered summary)))
  (is (= 596 (:regions-total summary))))

(deftest parses-functions
  (is (= 38 (:functions-covered summary)))
  (is (= 45 (:functions-total summary))))

(deftest truncates-percent-to-whole-points
  (is (= 0.83 (:lines-percent summary)))
  (is (= 0.80 (:regions-percent summary)))
  (is (= 0.84 (:functions-percent summary))))

(deftest parses-pre-branch-column-layout
  (is (= {:lines-covered 61 :lines-total 100 :lines-percent 0.61
          :regions-covered 20 :regions-total 25 :regions-percent 0.8
          :functions-covered 8 :functions-total 10 :functions-percent 0.8}
         (impl/parse-coverage-report
           "TOTAL   25    5    80.00%   10    2    80.00%  100   39    61.00%"))))

(deftest returns-nil-for-no-total
  (is (nil? (impl/parse-coverage-report "no TOTAL line here"))))

(run-tests-and-exit)
