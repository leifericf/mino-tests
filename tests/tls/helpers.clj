;; helpers.clj -- shared spawn/teardown for the python TLS fixture
;; server. Loaded via string require, which defines these publics
;; into the caller's namespace (the tests/test.clj convention); every
;; test file under tests/tls/ loads this once.
(require '[clojure.string :as str])

(defn fx-start-server
  "Spawn one python3 fixture server in `mode` (see
   tests/tls/fixture_server.py). Returns {:port n :pid \"...\"}; the
   fixture prints its port line only after the listener is bound, so
   sh! returns with the server ready and no startup wait is needed."
  [mode]
  (let [out  (sh! "python3" "tests/tls/fixture_server.py" mode)
        bits (str/split out #" ")]
    (when (not= 2 (count bits))
      (throw (str "tls fixture printed no port line: " out)))
    {:port (parse-long (nth bits 0))
     :pid  (nth bits 1)}))

(defn fx-stop-server [srv]
  (when (and srv (:pid srv))
    (sh "kill" (:pid srv))))

(defn fx-with-server
  "Run (body srv) with a live fixture server; the server is killed in
   a finally whether the body passed, threw, or errored. The fixture's
   own 300 s alarm is the orphan guard if this process itself dies."
  [mode body]
  (let [srv (fx-start-server mode)]
    (try
      (body srv)
      (finally
        (fx-stop-server srv)))))
