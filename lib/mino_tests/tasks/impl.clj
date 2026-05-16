(ns mino-tests.tasks.impl
  "Task implementation helpers. Uses mino's `sh` / `sh!` primitives
   and standard fs ops. Kept separate from the public task surface
   in mino-tests.tasks so it can be reshaped freely.")

(require '[clojure.string :as str])

(defn- repo-root []
  (or (getenv "PWD")
      (System/getProperty "user.dir")))

(defn mino-bin
  "Resolve the mino binary. Honors MINO_BIN override; falls back to
   the submodule's local build under mino/mino."
  []
  (or (getenv "MINO_BIN")
      (str (repo-root) "/mino/mino")))

(defn- runner-path [name]
  (str (repo-root) "/tests/adv/" name))

(defn run-script-suite
  "Run the script-side adversarial runner with the given mode/seed."
  [opts]
  (let [bin    (mino-bin)
        runner (runner-path "runner.clj")
        seed   (str (or (:seed opts) 0))
        mode   (name (or (:mode opts) :smoke))]
    (println "  exec:" bin runner "--seed" seed "--mode" mode)
    (try
      (println (sh! bin runner "--seed" seed "--mode" mode))
      (catch Throwable e
        (println "  runner failed:" (str e))
        (throw e)))))

(defn cov-run
  "Build mino_cov + run suite + emit llvm-cov html. Clang-only.
   The harness body lands in Cycle C v0.4.0; this stub detects
   clang/llvm-cov and reports the gap if either is missing."
  []
  (let [clang-out (try (sh! "which" "clang") (catch Throwable _ ""))
        prof-out  (try (sh! "which" "llvm-profdata") (catch Throwable _ ""))
        cov-out   (try (sh! "which" "llvm-cov") (catch Throwable _ ""))]
    (cond
      (str/blank? clang-out)
      (do (println "  ERROR: clang not found; coverage requires clang")
          (println "         (install Xcode / clang-19 / etc.)")
          1)

      (str/blank? prof-out)
      (do (println "  ERROR: llvm-profdata not found; install LLVM tools")
          1)

      (str/blank? cov-out)
      (do (println "  ERROR: llvm-cov not found; install LLVM tools")
          1)

      :else
      (do (println "  clang:        " (str/trim clang-out))
          (println "  llvm-profdata:" (str/trim prof-out))
          (println "  llvm-cov:     " (str/trim cov-out))
          (println "  TODO: build mino_cov, run suite, merge profraw, emit html")
          (println "  (full coverage binding lands in mino-tests v0.4.0)")
          0))))

(defn sanitizer-trinity
  "Run the C-side battery under three sanitizer recipes. Full
   binding lands in v0.3.4; for now reports the staged builds."
  []
  (let [bin (mino-bin)]
    (println "  resolving sanitizer builds via mino submodule:")
    (println "    mino_asan  -- ASan + LeakSan")
    (println "    mino_ubsan -- UndefinedBehaviorSanitizer")
    (println "    mino_tsan  -- ThreadSanitizer")
    (println "  (full battery lands in mino-tests v0.3.4)")
    0))

(defn build-cov-binary
  "Build the mino_cov instrumented binary only (no run)."
  []
  (println "  (binding lands in mino-tests v0.4.0)")
  0)

(defn bump-submodule
  "Update mino/ submodule to TAG; stages the new pin in parent index."
  [tag]
  (try
    (println (sh! "git" "-C" "mino" "fetch" "--tags" "origin"))
    (println (sh! "git" "-C" "mino" "checkout" tag))
    (println (sh! "git" "add" "mino"))
    (println "  submodule moved to" tag "; staged in parent index")
    0
    (catch Throwable e
      (println "  bump failed:" (str e))
      1)))

(defn ci-matrix
  "Run release-gate inside each Linux Docker image. Dev-host only;
   GHA owns the actual matrix in .github/workflows/ci.yml."
  []
  (let [imgs ["arm64-linux" "x86_64-linux"]]
    (println "  Docker images registered:")
    (doseq [i imgs] (println "   -" i))
    (println "  (full binding lands in Cycle D)")
    0))
