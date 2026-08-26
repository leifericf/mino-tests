;; run_zip_e2e.clj -- runs the zip/gzip binary-level E2E battery
;; (compression-zip campaign p6t2): spawns the real pinned binary as
;; a child across a timezone matrix, asserting the frozen archive
;; and gzip round trips come out byte-identical every time.
;;
;; Usage:
;;   ./mino/mino tests/run_zip_e2e.clj      (or: task zip-e2e)

(require "tests/test")

(reset! clojure.test/suite-mode true)
(require "tests/compress_zip/zip_e2e_test")
(reset! clojure.test/suite-mode false)

(run-tests-and-exit)
