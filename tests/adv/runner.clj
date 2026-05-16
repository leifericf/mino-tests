;; runner.clj -- aggregator for script-side adversarial probes.
;;
;; Usage:
;;   ./mino/mino tests/adv/runner.clj --seed 0 --mode smoke
;;
;; Loads tests/adv/regressions/*.clj first (they reproduce previously
;; auto-captured failing seeds), then tests/adv/script/*.clj (the live
;; probe battery). Probes are normal mino scripts that emit one JSON
;; line per assertion via emit-verdict.
;;
;; The runner is intentionally simple: load the helpers, load each
;; probe with load-file, count results. Probes report their own
;; verdicts; the runner reports the aggregate.

(load-file "tests/adv/edge_helpers.clj")
(load-file "tests/adv/gen.clj")
(load-file "tests/adv/invariants.clj")

;; --- arg parsing ---

(defn- parse-args [args]
  (loop [args (vec args), opts {:mode "smoke" :seed 0}]
    (cond
      (empty? args)             opts
      (= (first args) "--seed") (recur (drop 2 args)
                                       (assoc opts :seed
                                              (parse-long (second args))))
      (= (first args) "--mode") (recur (drop 2 args)
                                       (assoc opts :mode (second args)))
      :else                     (recur (rest args) opts))))

(def cli-opts (parse-args *command-line-args*))

(println "[runner] mode=" (:mode cli-opts) " seed=" (:seed cli-opts))
(seed! (:seed cli-opts))

;; --- probe discovery via an explicit list ---
;;
;; mino has no list-dir primitive; the registry below grows as
;; probes land in Cycle A and beyond. New probes append themselves
;; here.

(def regression-files
  "Auto-captured failing seeds. Each file is a mino script that
   reproduces a prior bug; if any of these fail, the bug regressed."
  [])

(def script-probes
  "Live probe battery. Cycle A v0.2.0..v0.2.4 fills this."
  [])

;; --- driver ---

(def state (atom {:total 0 :passed 0 :failed 0 :errors []}))

(defn- load-probe [path]
  (println "  loading:" path)
  (try
    (load-file path)
    (swap! state update :passed inc)
    (catch Throwable e
      (println "  ERROR in" path ":" (str e))
      (swap! state #(-> %
                        (update :failed inc)
                        (update :errors conj
                                {:file path :error (str e)}))))
    (finally
      (swap! state update :total inc))))

(println)
(println "[runner] regressions:" (count regression-files))
(doseq [p regression-files] (load-probe p))

(println)
(println "[runner] script probes:" (count script-probes))
(doseq [p script-probes] (load-probe p))

(println)
(let [s @state]
  (println (pr-str {:total  (:total s)
                    :passed (:passed s)
                    :failed (:failed s)
                    :seed   (:seed cli-opts)
                    :mode   (:mode cli-opts)})))

(exit (if (zero? (:failed @state)) 0 1))
