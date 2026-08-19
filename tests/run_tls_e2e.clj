;; run_tls_e2e.clj -- runs the TLS E2E battery (the TLS-server-dependent
;; tests moved out of mino's tests/ when the vendored BearSSL engine's
;; client-only scope made in-repo serving impossible).
;;
;; Each file under tests/tls/ is a normal clojure.test deftest suite
;; driving the mino binary's tls/http prims against the python3
;; fixture server (tests/tls/fixture_server.py). The bottom of this
;; file requires every test file and runs them in suite mode so the
;; per-file `(run-tests-and-exit)` calls inside each file are no-ops.
;; Requires python3 on PATH; every server is killed in a finally and
;; carries its own 300 s alarm orphan guard.
;;
;; Usage:
;;   ./mino/mino tests/run_tls_e2e.clj        (or: task tls-e2e)

(require "tests/test")

(defn- log-progress [tag s]
  (binding [*out* *err*]
    (println (str "[" tag "] " s))
    (flush)))

(defmacro require-with-progress [path]
  `(do
     (log-progress "load-start" ~path)
     (require ~path)
     (log-progress "load-done" ~path)))

(reset! clojure.test/suite-mode true)

;; --- moved from mino tests/tls_test.clj (58c4d4c0^) ---
(require-with-progress "tests/tls/tls_client_test")

;; --- moved from mino tests/pool_test.clj (58c4d4c0^) ---
(require-with-progress "tests/tls/pool_tls_test")

;; --- moved from mino tests/http_request_test.clj (58c4d4c0^) ---
(require-with-progress "tests/tls/http_request_tls_test")

;; --- moved from mino tests/http_integration_test.clj (58c4d4c0^) ---
(require-with-progress "tests/tls/http_integration_tls_test")

(reset! clojure.test/suite-mode false)

(log-progress "suite" "starting run-tests-and-exit")
(run-tests-and-exit)
