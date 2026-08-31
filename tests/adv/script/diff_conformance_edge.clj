;; diff_conformance_edge.clj -- mino-vs-canon diff over the curated
;; edge corpus.
;;
;; The clojuredocs differ covers what documentation examples reach; this
;; probe covers hand-reasoned semantic edges of individual vars
;; (laziness and chunk realization, numeric-tower corners, tie-breaking,
;; printf grammar, pad/step extremes) accumulated by the conformance
;; cycle. The corpus is small and curated, so every tuple runs in every
;; mode: no smoke sampling, a divergence can never hide behind a seed.
;;
;; Tuple shape matches the clojuredocs fixture, plus one optional key:
;; `:pending-bug "<one-line pointer>"` marks a tuple whose divergence is
;; a known open bug (tracked in mino/.local/BUGS.md). Pending tuples are
;; skipped and *counted*, never silently dropped: the summary reports
;; them so a green run still names how many known divergences remain,
;; and deleting the marker the moment the bug is fixed turns the tuple
;; back into a land-blocking assertion.
;;
;; Allowlist (tests/adv/fixtures/conformance_edge_allowlist.edn) is for
;; *intentional* divergences only, same two-tier key scheme as the
;; clojuredocs allowlist. Open bugs are :pending-bug, never allowlist.
;;
;; Fixture paths honor env overrides (CONFORMANCE_EDGE_FIXTURE,
;; CONFORMANCE_EDGE_JVM_FIXTURE, CONFORMANCE_EDGE_ALLOWLIST,
;; CONFORMANCE_EDGE_REGRESSIONS) so the teeth self-test
;; (tests/adv/conformance_edge_teeth.clj) can point the probe at a
;; planted corpus without touching the committed fixtures.

(load-file "tests/adv/edge_helpers.clj")
(require '[clojure.string :as s])

(def mino-bin (or (getenv "MINO_BIN") "mino/mino"))

(def fixture-path (or (getenv "CONFORMANCE_EDGE_FIXTURE")
                      "tests/adv/fixtures/conformance-edge-tuples.edn"))
(def jvm-fixture-path (or (getenv "CONFORMANCE_EDGE_JVM_FIXTURE")
                          "tests/adv/fixtures/conformance-edge-jvm-tuples.edn"))
(def allowlist-path (or (getenv "CONFORMANCE_EDGE_ALLOWLIST")
                        "tests/adv/fixtures/conformance_edge_allowlist.edn"))
(def regressions-dir (or (getenv "CONFORMANCE_EDGE_REGRESSIONS")
                         "tests/adv/regressions"))

(defn- safe-name [s]
  (apply str
         (map (fn [c]
                (let [s (str c)]
                  (cond
                    (or (and (>= (compare s "a") 0) (<= (compare s "z") 0))
                        (and (>= (compare s "A") 0) (<= (compare s "Z") 0))
                        (and (>= (compare s "0") 0) (<= (compare s "9") 0))
                        (= s ".") (= s "-") (= s "_"))
                    s
                    :else "_")))
              s)))

(defn- regression-path [seed key]
  (str regressions-dir "/conformance-edge-" seed "-" (safe-name key) ".clj"))

(defn- load-fixture []
  (try
    (read-string (slurp fixture-path))
    (catch e
      (println "ERROR: failed to read fixture at" fixture-path ":" (str e))
      (println "Build it with: bb tests/adv/conformance_edge_capture.clj")
      (exit 2))))

(defn- load-jvm-fixture []
  (try
    (let [m (read-string (slurp jvm-fixture-path))]
      (or (:outputs m) {}))
    (catch e {})))

(defn- load-allowlist []
  (try
    (read-string (slurp allowlist-path))
    (catch e {})))

;; Same prelude and render as the clojuredocs differ so the bb/JVM
;; ground truth captured for this corpus is apples-to-apples.
(def script-prelude
  (str "(require '[clojure.string :as str]"
       " '[clojure.set :as set]"
       " '[clojure.walk :as walk]"
       " '[clojure.pprint :as pp]"
       " '[clojure.math])\n"))

(defn- render-script [tuple]
  (str script-prelude
       (:preamble-source tuple)
       "\n(binding [*print-length* 200 *print-level* 20]"
       " (println (pr-str " (:form-source tuple) ")))\n"))

(def timeout-bin
  (let [probe (fn [bin]
                (zero? (:exit (sh "sh" "-c" (str "command -v " bin " >/dev/null")))))]
    (cond (probe "timeout")  "timeout"
          (probe "gtimeout") "gtimeout"
          :else nil)))

(def per-example-timeout-s "5")

(defn- run-mino [tuple]
  (let [script (render-script tuple)
        tmp (str "/tmp/mino-ce-" (now-ms) "-" (rand-int 100000) ".clj")]
    (spit tmp script)
    (let [r (if timeout-bin
              (sh timeout-bin per-example-timeout-s mino-bin tmp)
              (sh mino-bin tmp))]
      (sh "rm" "-f" tmp)
      r)))

(defn- norm [x]
  (when x (s/trim x)))

(defn- allow-reason [allow tuple]
  (let [vk (str (:ns tuple) "/" (:var-name tuple))
        ek (str vk ":" (:idx tuple))]
    (or (get allow ek) (get allow vk))))

(defn- compare-one [allow jvm-fx key tuple]
  (let [bb-exp   (:bb-out (:gt tuple))
        jvm-rec  (get jvm-fx key)
        jvm-exp  (when (= :ok (:status jvm-rec)) (:jvm-out jvm-rec))
        pending  (:pending-bug tuple)
        reason   (when-not pending (allow-reason allow tuple))
        {:keys [exit out err]} (when-not (or pending reason) (run-mino tuple))
        actual-n (norm out)
        bb-n     (norm bb-exp)
        jvm-n    (norm jvm-exp)]
    (cond
      pending
      {:status :pending :reason pending}

      reason
      {:status :allowlisted :reason reason}

      (not (zero? exit))
      {:status :mino-fail :exit exit :err err :expected bb-exp
       :jvm-expected jvm-exp :tuple tuple}

      (or (= actual-n bb-n)
          (and jvm-n (= actual-n jvm-n)))
      {:status :pass}

      :else
      {:status :fail :expected bb-exp :jvm-expected jvm-exp
       :actual out :tuple tuple})))

(defn- key-of [t]
  (str (:ns t) "/" (:var-name t) ":" (:idx t)))

(defn- assign-indices [tuples]
  (let [counters (atom {})]
    (mapv (fn [t]
            (let [k [(:ns t) (:var-name t)]
                  i (get @counters k 0)]
              (swap! counters update k (fnil inc 0))
              (assoc t :idx i)))
          tuples)))

(defn- capture-failure [seed key result]
  (let [rfile (regression-path seed key)
        tuple (:tuple result)]
    (try
      (spit rfile
            (str ";; Auto-captured conformance-edge divergence at " (now-ms) ".\n"
                 ";; seed=" seed " key=" key "\n"
                 ";; ns=" (:ns tuple) " var=" (:var-name tuple)
                 " idx=" (:idx tuple) "\n"
                 ";;\n"
                 ";; Expected (bb):\n"
                 ";;   " (pr-str (:expected result)) "\n"
                 ";; Expected (JVM Clojure):\n"
                 ";;   " (pr-str (:jvm-expected result)) "\n"
                 ";; Actual (mino):\n"
                 ";;   " (pr-str (:actual result)) "\n"
                 ";;\n"
                 ";; Form:\n"
                 ";;   " (:form-source tuple) "\n"
                 ";;\n"
                 ";; Re-run via load-file on this regression file --\n"
                 ";; the form below reproduces the divergence:\n"
                 "(let [script "
                 (pr-str (str (:preamble-source tuple)
                              "\n(println (pr-str "
                              (:form-source tuple) "))"))
                 "\n      tmp \"/tmp/conformance-edge-replay.clj\"]\n"
                 "  (spit tmp script)\n"
                 "  (let [r (sh (or (getenv \"MINO_BIN\") \"mino/mino\") tmp)]\n"
                 "    (println :mino-out (:out r))\n"
                 "    (println :expected " (pr-str (:expected result)) ")))\n"))
      (catch e (println "WARN: failed to write regression file:" (str e))))
    rfile))

(let [start (now-ms)
      data (load-fixture)
      jvm-fx (load-jvm-fixture)
      allow (load-allowlist)
      _ (println "[diff-conformance-edge] jvm-ground-truth:"
                 (if (seq jvm-fx)
                   (str (count jvm-fx) " tuples")
                   "(absent -- using bb-only)"))
      all-ok (->> (:tuples data)
                  (filter #(= :ok (:status (:gt %))))
                  assign-indices)
      n (count all-ok)
      _ (println "[diff-conformance-edge] corpus:" n
                 "tuples, running all (curated corpus, no sampling)")
      results (atom {:pass 0 :fail 0 :mino-fail 0 :allowlisted 0 :pending 0
                     :failures []})]
  (doseq [t all-ok]
    (let [k (key-of t)
          r (compare-one allow jvm-fx k t)]
      (case (:status r)
        :pass        (swap! results update :pass inc)
        :allowlisted (swap! results update :allowlisted inc)
        :pending     (swap! results update :pending inc)
        :fail        (do (swap! results #(-> %
                                              (update :fail inc)
                                              (update :failures conj
                                                      {:key k :result r})))
                         (emit-verdict "diff-conformance-edge.divergence"
                                       "fail"
                                       :key k
                                       :bb-expected (:expected r)
                                       :jvm-expected (:jvm-expected r)
                                       :actual (:actual r)))
        :mino-fail   (do (swap! results #(-> %
                                              (update :mino-fail inc)
                                              (update :failures conj
                                                      {:key k :result r})))
                         (emit-verdict "diff-conformance-edge.mino-error"
                                       "fail"
                                       :key k
                                       :exit (:exit r)
                                       :err (when (:err r)
                                              (subs (:err r)
                                                    0 (min 200 (count (:err r)))))
                                       :expected (:expected r))))))
  (let [r @results
        n-bad (+ (:fail r) (:mino-fail r))]
    (doseq [{:keys [key result]} (take 20 (:failures r))]
      (capture-failure effective-seed key result))
    (emit-verdict "diff-conformance-edge.summary"
                  (if (zero? n-bad) "pass" "fail")
                  :tested n
                  :pass (:pass r)
                  :fail (:fail r)
                  :mino-fail (:mino-fail r)
                  :allowlisted (:allowlisted r)
                  :pending (:pending r)
                  :seed effective-seed
                  :elapsed (- (now-ms) start))
    (when (pos? (:pending r))
      (println "[diff-conformance-edge]" (:pending r)
               "known divergences pending (see :pending-bug markers)"))
    (when (pos? n-bad)
      (throw (ex-info (str "diff-conformance-edge: " n-bad " of " n
                           " tuples diverged")
                      {:probe "diff-conformance-edge"
                       :fail (:fail r)
                       :mino-fail (:mino-fail r)
                       :n n})))))
