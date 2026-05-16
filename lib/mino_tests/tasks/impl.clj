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
      0
      (catch Throwable e
        (println "  runner failed:" (str e))
        1))))

(defn mino-src-root
  "Where to find the runtime source / mino.h for harness compilation.
   MINO_DEV_ROOT lets developers point at a local working copy of mino
   that's ahead of the submodule pin (the standing case during the
   no-push-without-ask freeze); defaults to the submodule under mino/."
  []
  (or (getenv "MINO_DEV_ROOT")
      (str (repo-root) "/mino")))

;; Explicit list of probe TUs to compile in. New probes register
;; themselves here so we don't depend on a directory walk (mino's
;; file primitives only cover existence + mtime, not enumeration).
(def embed-probe-srcs
  ["tests/adv/embed/adv_smoke.c"])

(defn build-harness
  "Compile the C-side probe binary at tests/adv/build/adv_test_<variant>.
   Links harness.c + driver.c + embed-probe-srcs against mino's
   static lib (mino/libmino.a). Variant selects the sanitizer recipe."
  [variant]
  (let [root      (repo-root)
        mino-root (mino-src-root)
        cc        (or (getenv "CC") "cc")
        out-dir   (str root "/tests/adv/build")
        out       (str out-dir "/adv_test_" (name variant))
        sanitize  (case variant
                    :asan  ["-fsanitize=address" "-fsanitize=undefined"
                            "-fno-omit-frame-pointer" "-g" "-O1"]
                    :tsan  ["-fsanitize=thread"
                            "-fno-omit-frame-pointer" "-g" "-O1"]
                    :ubsan ["-fsanitize=undefined"
                            "-fno-omit-frame-pointer" "-g" "-O1"]
                    :cov   ["-fprofile-instr-generate" "-fcoverage-mapping"
                            "-g" "-O1"]
                    ["-O2"])
        lib-a     (str mino-root "/libmino.a")
        includes  ["-I" (str mino-root "/src")
                   "-I" (str mino-root "/src/public")
                   "-I" (str mino-root "/src/runtime")
                   "-I" (str root "/tests/adv")]
        flags     (concat ["-std=c99" "-Wall" "-Wpedantic"]
                          sanitize
                          includes)
        srcs      (concat [(str root "/tests/adv/harness.c")
                           (str root "/tests/adv/driver.c")]
                          (map #(str root "/" %) embed-probe-srcs))
        argv      (concat [cc] flags srcs [lib-a "-lm" "-lpthread"
                                            "-o" out])]
    (println "  cc:" cc)
    (println "  variant:" variant)
    (println "  out:" out)
    (println "  sources:" (count srcs) "files")
    (sh! "mkdir" "-p" out-dir)
    (try
      (println (apply sh! argv))
      (println "  built:" out)
      0
      (catch Throwable e
        (println "  build failed:" (str e))
        1))))

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
