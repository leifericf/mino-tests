;; run_migrated.clj -- runs tests that migrated out of mino's tests/.
;;
;; Each file under tests/migrated/ is a normal clojure.test deftest
;; suite. The bottom of this file requires every test file in two
;; groups and then runs them in suite mode so the per-file
;; `(run-tests-and-exit)` calls inside each file are no-ops.
;;
;; Progress logging: every (require "tests/migrated/...") is wrapped
;; in `(require-with-progress ...)` which writes a `[loaded] file`
;; line to stderr before and after the require. Final flush at exit
;; so the last printed file before a CI step timeout identifies
;; where the hang occurred.
;;
;; Usage:
;;   ./mino/mino tests/run_migrated.clj

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

;; --- concurrency-heavy (migrated in v0.253.0 from mino) ---
(require-with-progress "tests/migrated/stm_concurrent_test")
(require-with-progress "tests/migrated/host_threads_test")
(require-with-progress "tests/migrated/agent_test")
(require-with-progress "tests/migrated/regex_reentrant_test")
(require-with-progress "tests/migrated/async_alts_test")
(require-with-progress "tests/migrated/async_api_test")
(require-with-progress "tests/migrated/async_blocking_test")
(require-with-progress "tests/migrated/async_buffer_test")
(require-with-progress "tests/migrated/async_combinators_test")
(require-with-progress "tests/migrated/async_conformance_test")
(require-with-progress "tests/migrated/async_go_test")
(require-with-progress "tests/migrated/async_mult_pub_test")
(require-with-progress "tests/migrated/async_timer_test")

;; --- fuzz / GC stress / fault-injection (migrated in v0.253.1) ---
(require-with-progress "tests/migrated/reader_fuzz_test")
(require-with-progress "tests/migrated/gc_generational_test")
(require-with-progress "tests/migrated/gc_incremental_test")
(require-with-progress "tests/migrated/regression_hamt_str_churn")
(require-with-progress "tests/migrated/fault_inject_test")

;; --- borderline E2E (migrated in v0.253.3) ---
;; creative_test.clj had no deftests (script-only); removed in
;; v0.2.5 dedup audit. ns_parity_run.clj returned to mino because
;; it depends on mino's own tests/ns_*_test.clj files; it isn't a
;; standalone test.
(require-with-progress "tests/migrated/doc_examples_test")
(require-with-progress "tests/migrated/bc_jit_deopt_test")
(require-with-progress "tests/migrated/spawn_stress_regression")

(reset! clojure.test/suite-mode false)

(log-progress "suite" "starting run-tests-and-exit")
(run-tests-and-exit)
