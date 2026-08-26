;; jvm_core_capture.clj -- capture JVM-Clojure ground truth for the
;; core differential probe corpus.
;;
;; Runs in a single `clojure -M` process: startup amortised across the
;; whole corpus, each eval sub-millisecond. Writes one EDN map keyed by
;; probe key recording what JVM Clojure prints for each probe form.
;;
;;   clojure -M tests/adv/jvm_core_capture.clj
;;
;; Writes tests/adv/fixtures/jvm-core-jvm.edn. Dev-host only; the
;; fixture is committed so CI never needs a JVM.

;; The script itself starts in user; move out so the per-probe
;; fresh-user-ns dance never touches the capture code's own home.
(create-ns 'jvm-core-capture)
(in-ns 'jvm-core-capture)
(clojure.core/refer 'clojure.core)
(require '[clojure.pprint :as pp])

(def in-path  "tests/adv/fixtures/jvm-core-probes.edn")
(def out-path "tests/adv/fixtures/jvm-core-jvm.edn")

(def ^:private per-probe-ms 3000)

(defn- probe->entry
  "Evaluate one probe form in a fresh user namespace and record the
   printed result. :match probes record pr-str of the value; errors
   record :error plus the throwable's simple class name (never the
   message, which is a documented census exclusion). A slow probe
   records :timeout so the diff probe surfaces it rather than hanging."
  [{:keys [key form]}]
  (let [result
        (try
          (when (find-ns 'user) (remove-ns 'user))
          (create-ns 'user)
          (in-ns 'user)
          (clojure.core/refer 'clojure.core)
          ;; bound-fn conveys *ns* into the worker so eval interns and
          ;; syntax-quote resolution land in user, not the pool default.
          (let [work (bound-fn []
                       (pr-str (eval (read-string form))))
                r (try (deref (future (work)) per-probe-ms :timeout)
                       ;; the future wraps worker throws in
                       ;; ExecutionException; the diagnosis wants the
                       ;; underlying class
                       (catch Throwable e
                         (throw (or (.getCause e) e))))]
            (if (= r :timeout)
              {:status :timeout}
              {:status :ok :out r}))
          (catch Throwable e
            {:status :error :kind (.getSimpleName (class e))})
          (finally
            (in-ns 'jvm-core-capture)))]
    [key result]))

(let [probes (read-string (slurp in-path))
      entries (doall (map probe->entry probes))]
  (println "[jvm-core-capture]" (count probes) "probes")
  (spit out-path
        (with-out-str
          (pp/pprint {:probes (count probes)
                      :outputs (into {} entries)})))
  (println "[jvm-core-capture] wrote" out-path
           (frequencies (map (comp :status second) entries))))
