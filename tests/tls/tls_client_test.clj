(require "tests/test")
(require "tests/tls/helpers")
(require '[clojure.string :as str])

;; TLS client layer (tls-connect / tls-read / tls-read-all / tls-write /
;; tls-close) over mino's vendored BearSSL engine, against live TLS
;; peers: every case mino's own tls_test.clj could no longer host once
;; the engine's client-only nature forced the server side out of that
;; repo. Ported 1:1 (assertions unchanged) from mino 58c4d4c0^
;; tests/tls_test.clj per the manifest at
;; ~/.agentic-sdk/mino/runs/http-client/moved-tls-tests.md.
;;
;; Verification defaults ON: chain against the vendored Mozilla roots
;; plus SAN/CN host match, with SNI always sent. The fixture CA is
;; unknown to that root store, so the default path must refuse every
;; fixture server; :insecure? true skips chain and host checks (still
;; full TLS) so the behaviour tests can drive real handshakes against
;; the fixtures.
;;
;; Servers are python3 on 127.0.0.1 (tests/tls/fixture_server.py),
;; kernel-chosen port, detached stdio, an alarm so a crashed run never
;; leaks the process, kill in finally. POSIX-only (os.fork); Windows
;; would run nothing here.

(def ^:private tls-posix? (nil? (getenv "OS")))

;; Fast fixtures: the behaviour suite should not sit in default
;; timeouts anywhere.
(def ^:private t-opts {:insecure? true :connect-timeout 3000
                       :read-timeout 3000 :write-timeout 3000})

(defn- bytes-text
  "Fixture responses are ASCII; map bytes through char for the
   substring assertions."
  [b]
  (apply str (map char (seq b))))

(defn- tls-socket
  "Connected, verified-skipping TLS socket against the fixture server,
   socket-arity form."
  [srv]
  (tls-connect (net-connect "127.0.0.1" (:port srv) t-opts)
               "localhost" t-opts))

;; ---- loopback behaviour (POSIX) ----

(when tls-posix?
  (deftest tls-get-round-trips-over-verified-skipping-handshake
    (fx-with-server "tls"
      (fn [srv]
        (let [s    (tls-socket srv)
              req  (str "GET /echo-path HTTP/1.1\r\nHost: localhost\r\n"
                        "Connection: close\r\n\r\n")]
          (is (= :handle (type s)))
          (is (= (count req) (tls-write s req)))
          (let [r (bytes-text (tls-read-all s))]
            (is (str/includes? r "HTTP/1.1 200 OK"))
            (is (str/includes? r "/echo-path")))
          (is (nil? (tls-close s)))))))

  (deftest tls-host-port-arity-connects-and-reads
    (fx-with-server "tls"
      (fn [srv]
        (let [s (tls-connect "127.0.0.1" (:port srv) t-opts)]
          (is (= :handle (type s)))
          (tls-write s (str "GET /arity HTTP/1.1\r\nHost: localhost"
                            "\r\n\r\n"))
          (is (str/includes? (bytes-text (tls-read-all s)) "/arity"))
          (is (nil? (tls-close s)))))))

  (deftest tls-default-verification-refuses-untrusted-chain
    (fx-with-server "tls"
      (fn [srv]
        (let [r (try (tls-connect (net-connect "127.0.0.1"
                                               (:port srv) t-opts)
                                  "localhost"
                                  (dissoc t-opts :insecure?))
                     (catch e e))]
          (is (= :tls (:mino/kind r)))
          (is (str/includes? (:mino/message r) "certificate"))
          (is (str/includes? (:mino/message r) "not trusted"))))))

  (deftest tls-host-port-arity-default-verification-refuses
    ;; Mirror of the socket-arity refusal above through the host+port
    ;; arity: the two arities have separate dispatch, so each needs
    ;; its own coverage of the default-verification path.
    (fx-with-server "tls"
      (fn [srv]
        (let [r (try (tls-connect "localhost" (:port srv)
                                  (dissoc t-opts :insecure?))
                     (catch e e))]
          (is (= :tls (:mino/kind r)))
          (is (str/includes? (:mino/message r) "certificate"))
          (is (str/includes? (:mino/message r) "not trusted"))))))

  (deftest tls-insecure-skips-verification-but-still-tls
    (fx-with-server "tls"
      (fn [srv]
        ;; Same server that default verification refused above.
        (let [s (tls-socket srv)]
          (tls-write s (str "GET /ok HTTP/1.1\r\nHost: localhost"
                            "\r\n\r\n"))
          (is (str/includes? (bytes-text (tls-read-all s)) "200 OK"))
          (tls-close s)))))

  (deftest tls-hostname-mismatch-refused
    (fx-with-server "wronghost"
      (fn [srv]
        (let [r (try (tls-connect (net-connect "127.0.0.1"
                                               (:port srv) t-opts)
                                  "localhost"
                                  (dissoc t-opts :insecure?))
                     (catch e e))]
          ;; Default verification: the SNI name check runs even though
          ;; the chain is also untrusted, and the certificate covers
          ;; other.example, not localhost.
          (is (= :tls (:mino/kind r)))
          (is (str/includes? (:mino/message r) "server name"))))))

  (deftest tls-expired-certificate-refused
    (fx-with-server "expired"
      (fn [srv]
        (let [r (try (tls-connect (net-connect "127.0.0.1"
                                               (:port srv) t-opts)
                                  "localhost"
                                  (dissoc t-opts :insecure?))
                     (catch e e))]
          (is (= :tls (:mino/kind r)))
          (is (str/includes? (:mino/message r) "expired"))))))

  (deftest tls-byte-transfer-fidelity
    (fx-with-server "blob"
      (fn [srv]
        (let [s      (tls-socket srv)
              got    (tls-read-all s)
              expect (byte-array (mapv (fn [i] (mod (+ (* i 7) 13) 256))
                                       (range 10240)))]
          (is (= 10240 (count got)))
          (is (= expect got))
          (is (nil? (tls-close s)))))))

  (deftest tls-read-returns-short-reads-and-nil-on-eof
    (fx-with-server "blob"
      (fn [srv]
        (let [s (tls-socket srv)]
          (is (= 10 (count (tls-read s 10))))
          (is (= 4096 (count (tls-read s 4096))))
          (is (= 0 (count (tls-read s 0))))
          ;; drain the rest, then EOF
          (is (= 6134 (count (tls-read-all s 65536))))
          (is (nil? (tls-read s 1)))
          (tls-close s)))))

  (deftest tls-read-all-cap-throws-overflow
    (fx-with-server "blob"
      (fn [srv]
        (let [s (tls-socket srv)
              r (try (tls-read-all s 100) (catch e e))]
          (is (= :net/overflow (:mino/kind r)))
          (tls-close s)))))

  (deftest tls-read-timeout-classifies-and-fires-on-schedule
    (fx-with-server "silent"
      (fn [srv]
        (let [s (tls-connect (net-connect "127.0.0.1" (:port srv)
                                          {:read-timeout 400
                                           :write-timeout 2000})
                             "localhost"
                             {:insecure? true :read-timeout 400
                              :write-timeout 2000})
              t0 (time-ms)
              r  (try (do (tls-write s "GET /slow HTTP/1.1\r\n\r\n")
                          (tls-read s 10))
                      (catch e e))
              dt (- (time-ms) t0)]
          (is (= :net/timeout (:mino/kind r)))
          (is (< dt 2400) (str "tls read timeout fired at " dt " ms"))
          (tls-close s)))))

  (deftest tls-close-is-idempotent-and-marks-the-socket
    (fx-with-server "tls"
      (fn [srv]
        (let [s (tls-socket srv)]
          (tls-write s "GET /x HTTP/1.1\r\nHost: localhost\r\n\r\n")
          (is (nil? (tls-close s)))
          (is (nil? (tls-close s)))
          (is (= :tls (try (tls-read s 1) (catch e (:mino/kind e)))))
          (is (= :tls (try (tls-write s "y") (catch e (:mino/kind e)))))))))

  (deftest tls-socket-finalizer-cleans-dropped-sockets
    (fx-with-server "blob"
      (fn [srv]
        ((fn []
           (let [doomed (tls-socket srv)]
             (tls-read doomed 64)
             :dropped)))
        (gc!)
        (let [s (tls-socket srv)]
          (is (= 32 (count (tls-read s 32))))
          (is (nil? (tls-close s))))))))

(run-tests-and-exit)
