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
(load-file "tests/adv/gen_program.clj")
(load-file "tests/adv/invariants.clj")
(require '[clojure.string :as str])

;; --- arg parsing ---

(defn- parse-args [args]
  (loop [args (vec args), opts {:mode "smoke" :seed 0 :replay nil
                                :max-ms nil :continue-on-fail false}]
    (cond
      (empty? args)               opts
      (= (first args) "--seed")   (recur (drop 2 args)
                                         (assoc opts :seed
                                                (parse-long (second args))))
      (= (first args) "--mode")   (recur (drop 2 args)
                                         (assoc opts :mode (second args)))
      (= (first args) "--replay") (recur (drop 2 args)
                                         (assoc opts :replay
                                                (parse-long (second args))))
      (= (first args) "--max-ms") (recur (drop 2 args)
                                         (assoc opts :max-ms
                                                (parse-long (second args))))
      (= (first args) "--continue-on-fail")
                                  (recur (drop 1 args)
                                         (assoc opts :continue-on-fail true))
      :else                       (recur (rest args) opts))))

(def cli-opts (parse-args *command-line-args*))

;; --replay <seed> overrides --seed for deterministic re-execution.
;; Useful when a soak run flagged a probe at a specific seed -- pass
;; that seed back via --replay to reproduce.
(def effective-seed (or (:replay cli-opts) (:seed cli-opts)))

(println "[runner] mode=" (:mode cli-opts)
         " seed=" effective-seed
         (if (:replay cli-opts) " (replay)" ""))
(seed! effective-seed)

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
  "Live probe battery. Each probe is a mino script that emits one or
   more verdict lines via emit-verdict. Probes are deliberately small
   and self-contained; the taxonomy lives in the file names."
  ["tests/adv/script/diag_carry.clj"
   "tests/adv/script/mode_err_shape.clj"
   "tests/adv/script/repl_snippets.clj"
   "tests/adv/script/cli_parity.clj"
   "tests/adv/script/lean_parity.clj"
   "tests/adv/script/depth_cliff.clj"
   "tests/adv/script/buffer_caps.clj"
   "tests/adv/script/closure_tco_jit.clj"
   "tests/adv/script/conc_deadlock.clj"
   "tests/adv/script/mem_jit.clj"
   "tests/adv/script/gc_invariant.clj"
   "tests/adv/script/diff_random.clj"])

;; --- driver ---

(def state (atom {:total 0 :passed 0 :failed 0 :errors []}))

(defn- auto-capture-failure
  "Write a regression file under tests/adv/regressions/ that
   reproduces the failing probe at the captured seed. The file is a
   minimal mino script that runs the probe -- not the input that
   produced the failure -- because regressions in this codebase live
   at the probe-script granularity."
  [probe-file seed error-str]
  (let [name (last (str/split probe-file "/"))
        ts   (time-ms)
        rfile (str "tests/adv/regressions/" ts "-"
                   (subs name 0 (max 0 (- (count name) 4))) ".clj")]
    (try
      (spit rfile
            (str ";; Auto-captured regression at " ts ".\n"
                 ";; Reproduces the failure observed running\n"
                 ";; " probe-file " at seed " seed ".\n"
                 ";; Error: " error-str "\n;;\n"
                 ";; Usage: ./mino tests/adv/runner.clj --replay "
                 seed "\n"
                 "(load-file \"" probe-file "\")\n"))
      (println "  auto-captured regression at:" rfile)
      (catch e nil))))

(defn- load-probe [path]
  (println "  loading:" path)
  (try
    (load-file path)
    (swap! state update :passed inc)
    (catch e
      (println "  ERROR in" path ":" (str e))
      (auto-capture-failure path effective-seed (str e))
      (swap! state #(-> %
                        (update :failed inc)
                        (update :errors conj
                                {:file path :error (str e)}))))
    (finally
      (swap! state update :total inc))))

(def start-ms (time-ms))

(defn- deadline-hit? []
  (when-let [budget (:max-ms cli-opts)]
    (> (- (time-ms) start-ms) budget)))

(defn- stop-early? []
  (or (deadline-hit?)
      (and (not (:continue-on-fail cli-opts))
           (pos? (:failed @state)))))

(println)
(println "[runner] regressions:" (count regression-files))
(doseq [p regression-files]
  (when-not (stop-early?) (load-probe p)))

(println)
(println "[runner] script probes:" (count script-probes))
(doseq [p script-probes]
  (when-not (stop-early?) (load-probe p)))

(println)
(let [s @state]
  (println (pr-str {:total   (:total s)
                    :passed  (:passed s)
                    :failed  (:failed s)
                    :seed    effective-seed
                    :mode    (:mode cli-opts)
                    :replay  (boolean (:replay cli-opts))
                    :elapsed (- (time-ms) start-ms)})))

(exit (if (zero? (:failed @state)) 0 1))
