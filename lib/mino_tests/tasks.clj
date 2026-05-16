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
  (let [seed (mod (System/currentTimeMillis) 1000000)]
    (println "[mino-tests] adv-test-soak (random seed)")
    (println "  seed:" seed)
    (impl/run-script-suite {:mode :soak :seed seed})))

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
