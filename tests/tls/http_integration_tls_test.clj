(require "tests/test")
(require "tests/tls/helpers")
(require '[clojure.string :as str])
(require '[mino.http :as http])

;; End-to-end TLS lane through the full stack: mino.http's plain-map
;; surface, the http-request prim, the pool, net, and TLS against a
;; localhost fixture. Ported 1:1 (assertions unchanged) from mino
;; 58c4d4c0^ tests/http_integration_test.clj per the manifest at
;; ~/.agentic-sdk/mino/runs/http-client/moved-tls-tests.md; the python
;; TLS listener of mino's tests/fixtures/http/server.py is this repo's
;; fixture_server.py "routes" mode (only the TLS base is needed by the
;; moved pair, so the plain listener stayed in mino).

(def ^:private posix? (nil? (getenv "OS")))

(defn- hi-with-tls-server
  "Run body with the https base URL; the server is killed after the
   body whether it passed, threw, or errored."
  [body]
  (fx-with-server "routes"
    (fn [srv]
      (body (str "https://localhost:" (:port srv))))))

;; ---- loopback end to end (POSIX) ----

(when posix?
  (deftest tls-happy-path-accepts-the-fixture-certificate
    (hi-with-tls-server
      (fn [tls]
        (let [r (http/get (str tls "/hello") {:insecure? true})]
          (is (= 200 (:status r)))
          (is (= "hello world" (:body r)))))))

  (deftest tls-default-verification-refuses-the-fixture-certificate
    (hi-with-tls-server
      (fn [tls]
        (let [e (try (http/get (str tls "/hello")) (catch Throwable e e))]
          (is (= :tls (-> (ex-data e) :error :kind)))
          (is (str/includes? (-> (ex-data e) :error :message)
                             "not trusted")))))))

(run-tests-and-exit)
