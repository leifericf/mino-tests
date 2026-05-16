;; diff_random.clj -- four-way differential test against random programs.
;;
;; Generates N seeded random valid mino programs and runs each through
;; the four execution shapes mino ships:
;;   tree-walker via --jit=off direct eval (top-level forms),
;;   bytecode VM via --jit=off past eval threshold (fn bodies),
;;   bytecode VM + JIT via --jit=auto / --jit=on after warmup,
;;   mino-lean (separate binary, no JIT pipeline).
;;
;; The four are required to produce byte-identical {:exit :out} maps.
;; Any divergence is auto-captured to tests/adv/regressions/ with the
;; full program source, seed, and the four divergent outputs so a
;; future `--replay <seed>` reproduces deterministically.
;;
;; Smoke: 100 programs, fixed seed. Soak: configurable via the
;; runner's mode; defaults to 1000 in soak.

(load-file "tests/adv/edge_helpers.clj")
(load-file "tests/adv/gen.clj")
(load-file "tests/adv/gen_program.clj")
(require '[clojure.string :as s])

(def mino-bin (or (getenv "MINO_BIN") "mino/mino"))
(def lean-bin (or (getenv "MINO_LEAN_BIN") "mino/mino-lean"))

(def n-programs
  (case (:mode cli-opts)
    "soak"  1000
    "smoke" 100
    100))

;; Probe-specific seed offset keeps diff_random's program stream
;; independent of probe-load order. Re-running with the same --seed
;; reproduces the same N programs regardless of which other probes
;; the runner has loaded ahead of us.
(def diff-random-seed-tag 0xD1FFE0)
(seed! (bit-xor effective-seed diff-random-seed-tag))

(defn- write-program [p i]
  (let [tmp (str "/tmp/mino-tests-diff-" (now-ms) "-" i ".clj")]
    (spit tmp p)
    tmp))

(defn- regression-path [seed i]
  (str "tests/adv/regressions/diff-random-" seed "-" i ".clj"))

(defn- capture-divergence
  "Write a regression file that reproduces this exact divergence.
   The file is a mino script: re-running it via the runner replays
   the same program against the same four binaries and asserts the
   same byte-id check. Captured fields: program source, seed,
   per-variant {:exit :out}."
  [seed i program quad]
  (let [rfile (regression-path seed i)]
    (try
      (spit rfile
            (str ";; Auto-captured quad-divergence at " (now-ms) ".\n"
                 ";; seed=" seed " program-idx=" i "\n"
                 ";; To reproduce: ./mino tests/adv/runner.clj --replay "
                 seed " (and ensure this file is in tests/adv/regressions/)\n"
                 ";;\n"
                 ";; Captured per-variant {:exit :out}:\n"
                 ";; :auto " (pr-str (:auto quad)) "\n"
                 ";; :on   " (pr-str (:on quad)) "\n"
                 ";; :off  " (pr-str (:off quad)) "\n"
                 ";; :lean " (pr-str (:lean quad)) "\n"
                 ";;\n"
                 ";; Original program:\n"
                 (apply str (map #(str ";; " % "\n") (s/split-lines program)))
                 ";;\n"
                 ";; Re-run the program through the quad and assert byte-id\n"
                 ";; matches what was captured above.\n"
                 "(load-file \"tests/adv/edge_helpers.clj\")\n"
                 "(let [tmp \"/tmp/diff-random-replay.clj\"]\n"
                 "  (spit tmp " (pr-str program) ")\n"
                 "  (let [q (run-quad (or (getenv \"MINO_BIN\") \"mino/mino\")\n"
                 "                     (or (getenv \"MINO_LEAN_BIN\") \"mino/mino-lean\")\n"
                 "                     tmp)]\n"
                 "    (emit-verdict \"diff-random.replay-" seed "-" i "\"\n"
                 "                  (if (quad-byte-identical? q) \"pass\" \"fail\")\n"
                 "                  :quad q)))\n"))
      (catch e (println "WARN: failed to write regression file:" (str e))))
    rfile))

(let [start (now-ms)
      results (atom {:passed 0 :failed 0 :failures []})]
  (dotimes [i n-programs]
    (let [program (gen-program)
          tmp     (write-program program i)
          quad    (run-quad mino-bin lean-bin tmp)]
      (sh "rm" "-f" tmp)
      (if (quad-byte-identical? quad)
        (swap! results update :passed inc)
        (let [rfile (capture-divergence effective-seed i program quad)]
          (swap! results (fn [r] (-> r
                                     (update :failed inc)
                                     (update :failures conj
                                             {:i i :rfile rfile :quad quad}))))
          (emit-verdict "diff-random.program-diverges"
                        "fail"
                        :i i
                        :seed effective-seed
                        :rfile rfile
                        :auto-exit (:exit (:auto quad))
                        :on-exit   (:exit (:on quad))
                        :off-exit  (:exit (:off quad))
                        :lean-exit (:exit (:lean quad))
                        :out-lens [(count (:out (:auto quad)))
                                   (count (:out (:on   quad)))
                                   (count (:out (:off  quad)))
                                   (count (:out (:lean quad)))])))))
  (let [r @results]
    (emit-verdict "diff-random.summary"
                  (if (zero? (:failed r)) "pass" "fail")
                  :tested  n-programs
                  :passed  (:passed r)
                  :failed  (:failed r)
                  :seed    effective-seed
                  :elapsed (- (now-ms) start))
    (when (pos? (:failed r))
      ;; throw so runner's load-probe counts this script as failed
      (throw (ex-info (str "diff_random: " (:failed r)
                           " of " n-programs " programs diverged")
                      {:probe "diff-random"
                       :failed (:failed r)
                       :n n-programs})))))
