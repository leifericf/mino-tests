;; run_time_e2e.clj -- runs the time/date E2E battery (external-tool
;; oracle cross-checks that the mino repo's C+mino-only rule keeps
;; out of the in-repo suite: host date(1), python3 datetime).
;;
;; Usage:
;;   ./mino/mino tests/run_time_e2e.clj        (or: task time-e2e)

(require "tests/test")

(reset! clojure.test/suite-mode true)
(require "tests/time/time_e2e_test")
(reset! clojure.test/suite-mode false)

(run-tests-and-exit)
