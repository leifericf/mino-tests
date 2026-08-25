;; run_html_e2e.clj -- runs the HTML/XML binary-level E2E battery
;; (html-xml campaign p6t3, design A-5): spawns the real ./mino
;; binary as a child on scripts that parse, select, and serialize
;; the campaign fixtures, asserting exit codes and output shape.
;;
;; Usage:
;;   ./mino/mino tests/run_html_e2e.clj      (or: task html-e2e)

(require "tests/test")

(reset! clojure.test/suite-mode true)
(require "tests/html_xml/html_e2e_test")
(reset! clojure.test/suite-mode false)

(run-tests-and-exit)
