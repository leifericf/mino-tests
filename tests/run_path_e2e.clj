;; run_path_e2e.clj -- runs the path/glob E2E battery (external-tool
;; oracle cross-checks that the mino repo's C+mino-only rule keeps
;; out of the in-repo suite: find(1), python3 glob/os.path).
;;
;; Usage:
;;   ./mino/mino tests/run_path_e2e.clj        (or: task path-e2e)

(require "tests/test")

(reset! clojure.test/suite-mode true)
(require "tests/path/path_e2e_test")
(reset! clojure.test/suite-mode false)

(run-tests-and-exit)
