;; diff_bc_collection_shapes.clj -- vec/map/set quad parity.

(load-file "tests/adv/edge_helpers.clj")
(load-file "tests/adv/gen.clj")
(load-file "tests/adv/gen_bc_collection_shapes.clj")
(load-file "tests/adv/shrink.clj")
(require '[clojure.string :as s])

(def mino-bin (or (getenv "MINO_BIN") "mino/mino"))
(def lean-bin (or (getenv "MINO_LEAN_BIN") "mino/mino-lean"))

(def n-progs (case (:mode cli-opts) "soak" 200 "smoke" 20 20))

(def diff-coll-seed-tag 0xC011)
(seed! (bit-xor effective-seed diff-coll-seed-tag))

(let [start (now-ms)
      results (atom {:passed 0 :failed 0})]
  (dotimes [i n-progs]
    (let [program (gen-collection-shape)
          tmp     (str "/tmp/mino-tests-diff-coll-" i ".clj")
          _       (spit tmp program)
          quad    (run-quad mino-bin lean-bin tmp)
          _       (sh "rm" "-f" tmp)]
      (if (quad-byte-identical? quad)
        (swap! results update :passed inc)
        (let [shrunk (try (shrink-divergent mino-bin lean-bin program
                                            {:budget-ms 10000})
                          (catch e program))
              rfile (str "tests/adv/regressions/diff-coll-"
                         effective-seed "-" i ".clj")]
          (try
            (spit rfile
                  (str ";; quad-divergence; collection-shape probe.\n"
                       ";; seed=" effective-seed " i=" i "\n"
                       ";; quad: " (pr-str quad) "\n"
                       ";; witness:\n"
                       (apply str (map #(str ";; " % "\n") (s/split-lines shrunk)))))
            (catch e nil))
          (swap! results update :failed inc)
          (emit-verdict "diff-coll.divergence" "fail"
                        :i i :seed effective-seed :rfile rfile)))))
  (let [r @results]
    (emit-verdict "diff-coll.summary"
                  (if (zero? (:failed r)) "pass" "fail")
                  :tested n-progs
                  :passed (:passed r)
                  :failed (:failed r)
                  :elapsed (- (now-ms) start))
    (when (pos? (:failed r))
      (throw (ex-info "diff-coll: divergences"
                      {:probe "diff-coll" :failed (:failed r)})))))
