;; conformance_edge_teeth.clj -- prove the edge-corpus differ has teeth
;; before it is allowed anywhere near a lane.
;;
;; Builds a tiny corpus in a temp dir with four planted tuples:
;;   1. a correct expectation        -> must pass
;;   2. a deliberately wrong one     -> must be flagged as a divergence
;;   3. a wrong one marked pending   -> must be skipped and counted, not failed
;;   4. a wrong one allowlisted      -> must be skipped as allowlisted
;; then runs tests/adv/script/diff_conformance_edge.clj through the adv
;; runner with the fixture paths overridden via env, and asserts on the
;; runner's exit code and emitted verdicts. Every assertion here can
;; fail: delete the probe (or its throw) and this script exits 1.
;;
;; Usage from mino-tests root:  bb tests/adv/conformance_edge_teeth.clj

(require '[clojure.java.shell :as sh]
         '[clojure.string :as str]
         '[clojure.java.io :as io])

(def tmp-dir (str (System/getProperty "java.io.tmpdir")
                  "/conformance-edge-teeth-" (System/currentTimeMillis)))

(def planted-corpus
  {:corpus {:source "conformance-edge-teeth" :total-tuples 4}
   :tuples
   [{:preamble-source "" :form-source "(+ 2 2)" :ns "clojure.core"
     :var-name "+" :expected "4" :gt {:status :ok :bb-out "4"}}
    {:preamble-source "" :form-source "(+ 1 1)" :ns "clojure.core"
     :var-name "+" :expected "999" :gt {:status :ok :bb-out "999"}}
    {:preamble-source "" :form-source "(* 3 3)" :ns "clojure.core"
     :var-name "*" :expected "888" :gt {:status :ok :bb-out "888"}
     :pending-bug "teeth: planted pending entry"}
    {:preamble-source "" :form-source "(- 5 1)" :ns "clojure.core"
     :var-name "-" :expected "777" :gt {:status :ok :bb-out "777"}}]})

;; Allowlist covers tuple 4 (clojure.core/-:0). Tuple 2 (+:1) is the
;; one the probe must flag.
(def planted-allowlist {"clojure.core/-" "teeth: planted allowlist entry"})

(defn- die [msg]
  (println "TEETH: FAIL --" msg)
  (System/exit 1))

(io/make-parents (str tmp-dir "/x"))
(spit (str tmp-dir "/edge-tuples.edn") (pr-str planted-corpus))
(spit (str tmp-dir "/edge-allowlist.edn") (pr-str planted-allowlist))
(.mkdirs (io/file (str tmp-dir "/regressions")))
;; No JVM fixture on purpose: the probe must run bb-only, like the
;; clojuredocs differ does when its JVM fixture is absent.

(let [mino-bin (or (System/getenv "MINO_BIN") "mino/mino")
      r (sh/sh mino-bin "tests/adv/runner.clj"
               "--seed" "0" "--mode" "soak"
               "--only" "script/diff_conformance_edge"
               "--continue-on-fail"
               :env (merge (into {} (System/getenv))
                           {"MINO_BIN" mino-bin
                            "CONFORMANCE_EDGE_FIXTURE" (str tmp-dir "/edge-tuples.edn")
                            "CONFORMANCE_EDGE_JVM_FIXTURE" (str tmp-dir "/absent-jvm.edn")
                            "CONFORMANCE_EDGE_ALLOWLIST" (str tmp-dir "/edge-allowlist.edn")
                            "CONFORMANCE_EDGE_REGRESSIONS" (str tmp-dir "/regressions")}))
      out (str (:out r) (:err r))]
  (when (zero? (:exit r))
    (die "runner exited 0; the planted divergence did not fail the probe"))
  (when-not (str/includes? out "clojure.core/+:1")
    (die "planted divergence key clojure.core/+:1 not reported"))
  (when (str/includes? out "clojure.core/+:0")
    (die "correct tuple clojure.core/+:0 was flagged"))
  (when-not (re-find #":pending 1" out)
    (die "pending-bug tuple was not counted as pending"))
  (when (str/includes? out "clojure.core/*:0")
    (die "pending-bug tuple clojure.core/*:0 was flagged instead of skipped"))
  (when-not (re-find #":allowlisted 1" out)
    (die "allowlisted tuple was not counted as allowlisted"))
  (when-not (re-find #":fail 1[^0-9]" out)
    (die "expected exactly one failing tuple in the summary"))
  (let [regs (seq (.listFiles (io/file (str tmp-dir "/regressions"))))]
    (when-not regs
      (die "no regression file auto-captured for the planted divergence")))
  (println "TEETH: PASS -- planted divergence flagged, pending skipped,"
           "allowlist honored, regression captured")
  (System/exit 0))
