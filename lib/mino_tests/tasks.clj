(ns mino-tests.tasks
  "Task entry points for mino-tests. Wired via mino.edn.

   Each public task delegates to one of three runners under tests/adv/:
     - script-side probes go through `runner.clj` (mino code)
     - C-side probes go through `harness` binaries built into tests/adv/build/
     - coverage / sanitizer / matrix variants compose those two

   The implementation lives in mino-tests.tasks.impl so this file
   stays a thin surface — tasks.impl can churn freely without
   changing mino.edn or wiring.")

(require '[mino-tests.tasks.impl :as impl])

(defn adv-test [& _]
  (println "[mino-tests] adv-test (smoke, seed=0)")
  (impl/run-script-suite {:mode :smoke :seed 0}))

(defn adv-test-soak [& _]
  ;; Wall-clock derived random seed -- mino's (time-ms) returns
  ;; double milliseconds since epoch; long-cast to int truncates
  ;; into the 0..999999 range.
  (let [seed (mod (long (time-ms)) 1000000)]
    (println "[mino-tests] adv-test-soak (random seed)")
    (println "  seed:" seed)
    (impl/run-script-suite {:mode :soak :seed seed})))

(defn diff-test [& _]
  (println "[mino-tests] diff-test (smoke, diff probes only, seed=0)")
  (impl/run-script-suite {:mode :smoke :seed 0 :only "diff_"}))

(defn diff-test-soak [& _]
  (let [seed (mod (long (time-ms)) 1000000)]
    (println "[mino-tests] diff-test-soak (diff probes, random seed)")
    (println "  seed:" seed)
    (impl/run-script-suite {:mode :soak :seed seed :only "diff_"})))

(defn adv-test-coverage [& _]
  (println "[mino-tests] adv-test-coverage (clang llvm-cov)")
  (impl/cov-run))

(defn adv-test-sanitizers [& _]
  (println "[mino-tests] adv-test-sanitizers (ASan / UBSan / TSan)")
  (impl/sanitizer-trinity))

(defn build-cov [& _]
  (println "[mino-tests] build-cov")
  (impl/build-cov-binary))

(defn bump-mino [& args]
  (let [tag (first args)]
    (when-not tag
      (throw (ex-info "bump-mino requires a tag argument"
                      {:usage "./mino/mino task bump-mino v0.253.0"})))
    (println "[mino-tests] bump-mino ->" tag)
    (impl/bump-submodule tag)))

(defn ci-matrix [& _]
  (println "[mino-tests] ci-matrix (Linux Docker images)")
  (impl/ci-matrix))

(defn test-migrated [& _]
  (println "[mino-tests] test-migrated (tests moved out of mino)")
  (impl/run-clj-file "tests/run_migrated.clj"))

(defn test-fault-inject [& _]
  (println "[mino-tests] test-fault-inject (simulated OOM)")
  (impl/run-clj-file "tests/run_fault_inject.clj"))

(defn test-gc-stress [& _]
  (println "[mino-tests] test-gc-stress (MINO_GC_STRESS=1)")
  (impl/run-clj-file-with-env {"MINO_GC_STRESS" "1"}
                              "tests/run_gc_stress.clj"))

;; ---- GC safeguards (added 2026-05-16 after the CI hang at
;; transient-survives-gc-yield revealed how easily a GC bug can hide
;; behind full-suite Heisenbug masking) ----

(defn gc-fuzz [& _]
  (println "[mino-tests] gc-fuzz (mino tests/run.clj at varied nursery sizes)")
  (impl/gc-fuzz))

(defn gc-stress-subset [& _]
  (println "[mino-tests] gc-stress-subset (MINO_GC_STRESS=1 on transient_test + gc_test)")
  (impl/gc-stress-subset))

(defn gc-verify [& _]
  (println "[mino-tests] gc-verify (MINO_GC_VERIFY=1 on mino tests/run.clj)")
  (impl/gc-verify))

(defn asan-per-file [& _]
  (println "[mino-tests] asan-per-file (mino tests/ files, one ASan subprocess each)")
  (impl/asan-per-file))

(defn clojuredocs-refresh [& _]
  (println "[mino-tests] clojuredocs-refresh (re-download corpus, re-run bb ground truth)")
  (impl/clojuredocs-refresh))
