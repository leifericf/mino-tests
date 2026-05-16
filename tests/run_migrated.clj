;; run_migrated.clj -- runs tests that migrated out of mino's tests/.
;;
;; Each file under tests/migrated/ is a normal clojure.test deftest
;; suite. Load suite-mode and require them all; the final
;; (run-tests-and-exit) drains the registry.
;;
;; Usage:
;;   ./mino/mino tests/run_migrated.clj

(require "tests/test")

(reset! clojure.test/suite-mode true)

;; --- concurrency-heavy (migrated in v0.253.0 from mino) ---
(require "tests/migrated/stm_concurrent_test")
(require "tests/migrated/host_threads_test")
(require "tests/migrated/agent_test")
(require "tests/migrated/regex_reentrant_test")
(require "tests/migrated/async_alts_test")
(require "tests/migrated/async_api_test")
(require "tests/migrated/async_blocking_test")
(require "tests/migrated/async_buffer_test")
(require "tests/migrated/async_combinators_test")
(require "tests/migrated/async_conformance_test")
(require "tests/migrated/async_go_test")
(require "tests/migrated/async_mult_pub_test")
(require "tests/migrated/async_timer_test")

(reset! clojure.test/suite-mode false)

(run-tests-and-exit)
