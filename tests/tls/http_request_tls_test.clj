(require "tests/test")
(require "tests/tls/helpers")
(require '[clojure.string :as str])

;; The http-request orchestration prim over TLS: full response maps,
;; default-verification refusal, pool partitioning by verification
;; mode, and per-request read timeouts on pooled sessions. Ported 1:1
;; (assertions unchanged) from mino 58c4d4c0^ tests/http_request_test.clj
;; per the manifest at
;; ~/.agentic-sdk/mino/runs/http-client/moved-tls-tests.md; the python
;; "tls" route-table fixture is this repo's fixture_server.py "routes"
;; mode (HTTP/1.1 keep-alive, /slow sleeps 3 s then answers "late").

(def ^:private hr-posix? (nil? (getenv "OS")))

(defn- hr-https-get
  [srv target opts]
  (http-request (merge {:method "GET" :scheme :https
                        :host "localhost" :port (:port srv)
                        :target target :insecure? true
                        :connect-timeout 3000 :read-timeout 3000
                        :write-timeout 3000}
                       opts)))

(defn- hr-text
  "Fixture bodies are ASCII; widen bytes through char for assertions."
  [b]
  (apply str (map char (seq b))))

(when hr-posix?
  (deftest https-happy-path-against-fixture-tls-server
    (fx-with-server "routes"
      (fn [srv]
        (let [r (hr-https-get srv "/hello" nil)]
          (is (= 200 (:status r)))
          (is (= "hello world" (hr-text (:body-bytes r))))
          (is (= "1.1" (:http-version r)))))))

  (deftest https-default-verification-refuses-fixture-certificate
    ;; The fixture CA is not in the vendored Mozilla root store, so the
    ;; default verification path must refuse it (tls fixture precedent).
    (fx-with-server "routes"
      (fn [srv]
        (let [r (try (http-request {:method "GET" :scheme :https
                                    :host "localhost" :port (:port srv)
                                    :target "/hello"
                                    :connect-timeout 3000
                                    :read-timeout 3000
                                    :write-timeout 3000})
                     (catch e e))]
          (is (= :tls (:mino/kind r)))
          (is (str/includes? (:mino/message r) "not trusted"))))))

  (deftest secure-request-never-reuses-insecure-pooled-session
    ;; An :insecure? request succeeds against the self-signed fixture
    ;; and pools its unverified session; a default-verified request to
    ;; the same endpoint must not send anything over that session --
    ;; it opens a fresh handshake and refuses the certificate.
    (fx-with-server "routes"
      (fn [srv]
        (let [r1 (hr-https-get srv "/hello" nil)]
          (is (= 200 (:status r1))))
        (let [r1b (hr-https-get srv "/hello" nil)]
          (is (= 200 (:status r1b)))
          (is (true? (:from-pool? r1b))
              "same-mode checkouts keep reusing the pooled session"))
        (let [r2 (try (http-request {:method "GET" :scheme :https
                                     :host "localhost" :port (:port srv)
                                     :target "/hello"
                                     :connect-timeout 3000
                                     :read-timeout 3000
                                     :write-timeout 3000})
                      (catch e e))]
          (is (= :tls (:mino/kind r2)))
          (is (str/includes? (:mino/message r2) "not trusted")
              "the verifying request must refuse the fixture cert"))
        (let [r3 (hr-https-get srv "/hello" nil)]
          (is (= 200 (:status r3))
              "insecure requests keep working after a verifying one")))))

  (deftest pooled-https-second-request-honors-its-own-read-timeout
    ;; The pooled session carries the first request's SO_RCVTIMEO on
    ;; its descriptor; the second request's tighter :read-timeout must
    ;; be re-applied and fire on schedule.
    (fx-with-server "routes"
      (fn [srv]
        (let [r1 (hr-https-get srv "/slow" {:read-timeout 8000})]
          (is (= 200 (:status r1))))
        (let [t0 (time-ms)
              r2 (try (hr-https-get srv "/slow" {:read-timeout 400})
                      (catch e e))
              dt (- (time-ms) t0)]
          (is (= :net/timeout (:mino/kind r2)))
          (is (< dt 2400)
              (str "pooled TLS read timeout fired at " dt " ms")))))))

(run-tests-and-exit)
