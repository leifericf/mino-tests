;; run_tar_e2e.clj -- runs the tar binary-level E2E battery: spawns
;; the real pinned binary across a timezone matrix, asserting the
;; frozen tar archive and .tar.gz round trips come out byte-identical
;; every time.
;;
;; Usage:
;;   ./mino/mino tests/run_tar_e2e.clj      (or: task tar-e2e)

(require "tests/test")

(reset! clojure.test/suite-mode true)
(require "tests/tar_archive/tar_e2e_test")
(reset! clojure.test/suite-mode false)

(run-tests-and-exit)
