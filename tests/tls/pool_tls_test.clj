(require "tests/test")
(require "tests/tls/helpers")

;; TLS sessions through the keep-alive pool (pool-checkout /
;; pool-return / pool-close-all). Ported 1:1 (assertions unchanged)
;; from mino 58c4d4c0^ tests/pool_test.clj per the manifest at
;; ~/.agentic-sdk/mino/runs/http-client/moved-tls-tests.md; the python
;; "tls" pool fixture (wrap in server.pem, hold the connection open)
;; is this repo's fixture_server.py "hold" mode.

(def ^:private pool-posix? (nil? (getenv "OS")))

(def ^:private t-opts {:insecure? true :connect-timeout 3000
                       :read-timeout 3000 :write-timeout 3000})

(when pool-posix?
  (deftest tls-verification-mode-partitions-the-pool
    ;; A session opened with :insecure? true must never serve a
    ;; verifying request to the same endpoint: the Authorization
    ;; headers of a default-verified call would cross an unverified
    ;; hop. Insecure checkouts reuse insecure entries.
    (fx-with-server "hold"
      (fn [srv]
        (let [secure   {:scheme :https :host "localhost" :port (:port srv)}
              insecure (assoc secure :insecure? true)
              h1       (tls-connect (net-connect "127.0.0.1" (:port srv)
                                                t-opts)
                                    "localhost" t-opts)]
          (is (nil? (pool-return insecure h1 nil)))
          (is (identical? h1 (pool-checkout insecure nil))
              "an insecure entry serves the next insecure checkout")
          (is (nil? (pool-return insecure h1 nil)))
          (is (nil? (pool-checkout secure nil))
              "a verifying checkout must never reuse an insecure session")
          (is (nil? (pool-close-all)))))))

  (deftest tls-handles-pool-like-sockets
    ;; Real handshake against the self-signed fixture (tls fixture
    ;; set, :insecure? for local peers only).
    (fx-with-server "hold"
      (fn [srv]
        (let [e     {:scheme :https :host "localhost" :port (:port srv)}
              h1    (tls-connect (net-connect "127.0.0.1" (:port srv)
                                              t-opts)
                                 "localhost" t-opts)]
          (is (= :handle (type h1)))
          (is (nil? (pool-return e h1 nil)))
          (let [h2 (pool-checkout e nil)]
            (is (identical? h1 h2)
                "a pooled TLS session must be handed back whole")
            (tls-close h2)
            (is (nil? (pool-close-all)))))))))

(run-tests-and-exit)
