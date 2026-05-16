;; diff_bc_arith_shapes.clj -- arithmetic/bitwise quad parity.

(load-file "tests/adv/edge_helpers.clj")
(load-file "tests/adv/gen.clj")
(load-file "tests/adv/gen_bc_arith_shapes.clj")
(load-file "tests/adv/shrink.clj")
(require '[clojure.string :as s])

(def mino-bin (or (getenv "MINO_BIN") "mino/mino"))
(def lean-bin (or (getenv "MINO_LEAN_BIN") "mino/mino-lean"))

(def n-progs (case (:mode cli-opts) "soak" 200 "smoke" 20 20))

(def diff-arith-seed-tag 0xA21E7)
(seed! (bit-xor effective-seed diff-arith-seed-tag))

(let [start (now-ms)
      results (atom {:passed 0 :failed 0})]
  (dotimes [i n-progs]
    (let [program (gen-arith-shape)
          tmp     (str "/tmp/mino-tests-diff-as-" i ".clj")
          _       (spit tmp program)
          quad    (run-quad mino-bin lean-bin tmp)
          _       (sh "rm" "-f" tmp)]
      (if (quad-byte-identical? quad)
        (swap! results update :passed inc)
        (let [shrunk (try (shrink-divergent mino-bin lean-bin program
                                            {:budget-ms 10000})
                          (catch e program))
              rfile (str "tests/adv/regressions/diff-arith-"
                         effective-seed "-" i ".clj")]
          (try
            (spit rfile
                  (str ";; quad-divergence; arith-shape probe.\n"
                       ";; seed=" effective-seed " i=" i "\n"
                       ";; quad: " (pr-str quad) "\n"
                       ";; witness:\n"
                       (apply str (map #(str ";; " % "\n") (s/split-lines shrunk)))))
            (catch e nil))
          (swap! results update :failed inc)
          (emit-verdict "diff-arith.divergence" "fail"
                        :i i :seed effective-seed :rfile rfile)))))
  (let [r @results]
    (emit-verdict "diff-arith.summary"
                  (if (zero? (:failed r)) "pass" "fail")
                  :tested n-progs
                  :passed (:passed r)
                  :failed (:failed r)
                  :elapsed (- (now-ms) start))
    (when (pos? (:failed r))
      (throw (ex-info "diff-arith: divergences"
                      {:probe "diff-arith" :failed (:failed r)})))))
