;; run_ws_e2e.clj -- runs the websocket client E2E battery: mino.ws
;; through a full connect/send/recv/close lifecycle against an echo
;; websocket server scripted from mino's own net prims, in process.
;;
;; Usage:
;;   ./mino/mino tests/run_ws_e2e.clj       (or: task ws-e2e)

(require "tests/test")

(reset! clojure.test/suite-mode true)
(require "tests/ws/ws_echo_e2e_test")
(reset! clojure.test/suite-mode false)

(run-tests-and-exit)
