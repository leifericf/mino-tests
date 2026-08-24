;; run_digest_e2e.clj -- runs the digest/HMAC/crc32 E2E battery
;; (python3 hashlib/hmac/zlib oracle cross-checks that the mino repo's
;; C+mino-only rule keeps out of the in-repo suite).
;;
;; Usage:
;;   ./mino/mino tests/run_digest_e2e.clj     (or: task digest-e2e)

(require "tests/test")

(reset! clojure.test/suite-mode true)
(require "tests/digest/digest_e2e_test")
(reset! clojure.test/suite-mode false)

(run-tests-and-exit)
