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

(defn run-clj-file
  "Invoke MINO_BIN against a top-level .clj file in this repo.
   Returns 0 on green, 1 on non-zero exit. Used by test-migrated /
   test-fault-inject."
  [rel-path]
  (let [bin (mino-bin)
        path (str (repo-root) "/" rel-path)]
    (println "  exec:" bin path)
    (try (println (sh! bin path)) 0
         (catch e
           (println "  failed:" (str e)) 1))))

(defn run-clj-file-with-env
  "Like run-clj-file but with extra env vars set for the child."
  [env-map rel-path]
  (let [bin (mino-bin)
        path (str (repo-root) "/" rel-path)
        argv (concat ["env"]
                     (mapcat (fn [[k v]] [(str k "=" v)]) env-map)
                     [bin path])]
    (println "  exec:" (clojure.string/join " " argv))
    (try (println (apply sh! argv)) 0
         (catch e
           (println "  failed:" (str e)) 1))))

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
      (catch e
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
  ["tests/adv/embed/adv_smoke.c"
   "tests/adv/embed/adv_topo_ring_smoke.c"
   "tests/adv/embed/adv_clone_zoo.c"
   "tests/adv/embed/adv_pool_topology.c"
   "tests/adv/embed/adv_stm_mix.c"
   "tests/adv/embed/adv_fault_replay.c"])

;; Shared library files compiled in alongside the probes.
(def harness-libs
  ["tests/adv/harness.c"
   "tests/adv/topology.c"
   "tests/adv/driver.c"])

;; --- C-side harness build ---
;;
;; Links the harness binary against the mino submodule's runtime
;; sources. mino doesn't produce a libmino.a -- only the binary and
;; per-module .o files. We pick up the .o files directly so the
;; harness shares the same compile units.

;; The lib-srcs list mirrors mino's own lib/mino/tasks/builtin.clj
;; lib-srcs at v0.252.3 -- intentionally inlined so a mino API drift
;; surfaces here as a build break rather than silently linking
;; against an older surface.
(def mino-lib-srcs
  ["src/eval/eval.c" "src/diag/diag.c" "src/eval/special.c"
   "src/eval/special_registry.c" "src/eval/defs.c" "src/eval/bindings.c"
   "src/eval/control.c" "src/eval/fn.c" "src/eval/bc/vm.c"
   "src/eval/bc/compile.c" "src/eval/bc/jit/entry.c"
   "src/eval/bc/jit/stats.c" "src/eval/bc/jit/helpers.c"
   "src/eval/bc/jit/patcher.c" "src/eval/bc/jit/patcher_x86_64.c"
   "src/eval/bc/jit/emit.c" "src/runtime/state.c" "src/runtime/var.c"
   "src/runtime/error.c" "src/runtime/env.c" "src/runtime/ns_env.c"
   "src/runtime/path_buf.c" "src/runtime/host_threads.c"
   "src/runtime/capabilities.c" "src/gc/driver.c" "src/gc/roots.c"
   "src/gc/major.c" "src/gc/barrier.c" "src/gc/minor.c"
   "src/gc/trace.c" "src/gc/profile.c" "src/runtime/module.c"
   "src/public/gc.c" "src/public/embed.c" "src/collections/val.c"
   "src/collections/vec.c" "src/collections/map.c"
   "src/collections/chunk.c" "src/collections/rbtree.c"
   "src/collections/builders.c" "src/collections/iter.c"
   "src/eval/read.c" "src/eval/print.c" "src/prim/prim.c"
   "src/prim/install.c" "src/prim/install_stdlib.c"
   "src/prim/numeric.c" "src/prim/collections.c"
   "src/prim/sequences.c" "src/prim/lazy.c" "src/prim/string.c"
   "src/prim/io.c" "src/prim/reflection.c" "src/prim/meta.c"
   "src/prim/regex.c" "src/prim/stateful.c" "src/prim/stm.c"
   "src/prim/agent.c" "src/prim/module.c" "src/prim/ns.c"
   "src/prim/fs.c" "src/prim/proc.c" "src/prim/host.c"
   "src/interop/syntax.c" "src/collections/clone.c"
   "src/regex/re.c" "src/collections/transient.c"
   "src/async/scheduler.c" "src/async/timer.c" "src/prim/async.c"
   "src/prim/bignum.c" "src/vendor/imath/imath.c"])

(defn- src->obj [src]
  (str (subs src 0 (- (count src) 2)) ".o"))

(defn build-harness
  "Compile the C-side probe binary at tests/adv/build/adv_test_<variant>.
   Variant selects the sanitizer recipe: :asan / :tsan / :ubsan / :cov
   / nil for the default release build."
  [variant]
  (let [root      (repo-root)
        mino-root (mino-src-root)
        cc        (or (getenv "CC") "cc")
        out-dir   (str root "/tests/adv/build")
        out       (str out-dir "/adv_test_" (name (or variant :release)))
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
        ;; For the :release variant, link against mino's prebuilt .o
        ;; files (fast incremental). For sanitizer variants, recompile
        ;; mino sources alongside the harness so the sanitizer flags
        ;; reach every TU. mino's own build doesn't keep
        ;; per-sanitizer .o files (it produces mino_asan / mino_ubsan
        ;; / mino_tsan as single-cc binaries), so this is the cleanest
        ;; way to share the runtime under a sanitizer build.
        is-sanitizer? (boolean (#{:asan :tsan :ubsan} variant))
        mino-objs (mapv #(str mino-root "/" (src->obj %)) mino-lib-srcs)
        mino-srcs (mapv #(str mino-root "/" %) mino-lib-srcs)
        mino-pieces (if is-sanitizer? mino-srcs mino-objs)
        first-piece (first mino-pieces)
        flags     (concat ["-std=c99" "-Wall" "-Wno-extra-semi"
                           "-DMINO_CPJIT=1"]
                          sanitize)
        harness-c (map #(str root "/" %) harness-libs)
        embeds-c  (map #(str root "/" %) embed-probe-srcs)
        argv      (concat [cc] flags
                          (map #(str "-I" mino-root "/" %)
                               ["src" "src/public" "src/runtime"
                                "src/gc" "src/eval" "src/collections"
                                "src/prim" "src/async" "src/interop"
                                "src/diag" "src/vendor/imath"])
                          ["-I" (str root "/tests/adv")]
                          harness-c embeds-c
                          mino-pieces
                          ["-lm" "-lpthread" "-o" out])]
    (println "  cc:        " cc)
    (println "  variant:   " (name (or variant :release)))
    (println "  out:       " out)
    (sh! "mkdir" "-p" out-dir)
    (if (or is-sanitizer? (file-exists? first-piece))
      (try
        (println (apply sh! argv))
        (println "  built:" out)
        0
        (catch e
          (println "  build failed:" (str e))
          1))
      (do
        (println "  SKIP: mino .o files not present at" first-piece)
        (println "  (run `cd " mino-root " && make` first)")
        1))))

(defn cov-run
  "Build mino_cov + run suite + emit llvm-cov html. Clang-only.
   The harness body lands in Cycle C v0.4.0; this stub detects
   clang/llvm-cov and reports the gap if either is missing."
  []
  (let [clang-out (try (sh! "which" "clang") (catch _ ""))
        prof-out  (try (sh! "which" "llvm-profdata") (catch _ ""))
        cov-out   (try (sh! "which" "llvm-cov") (catch _ ""))]
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
  "Run the C-side battery under three sanitizer recipes. Each variant
   builds the harness from source (mino's per-sanitizer .o files
   aren't kept) and runs the registry once."
  []
  (let [root (repo-root)
        results (atom {})]
    (doseq [variant [:asan :ubsan :tsan]]
      (println "  --- sanitizer:" (name variant) "---")
      (let [build-rc (build-harness variant)
            bin     (str root "/tests/adv/build/adv_test_" (name variant))]
        (if (and (zero? build-rc) (file-exists? bin))
          (let [out (try (sh bin)
                         (catch e {:exit 1 :out (str e)}))]
            (swap! results assoc variant out)
            (println "    exit:" (:exit out))
            (println "    out (last 5 lines):")
            (doseq [line (take-last 5 (str/split-lines
                                       (or (:out out) "")))]
              (println "     " line)))
          (do (println "  build failed for" variant)
              (swap! results assoc variant {:exit -1})))))
    (let [all-zero (every? #(zero? (:exit (val %))) @results)]
      (println "  --- summary ---")
      (doseq [[k v] @results]
        (println "   " (name k) ":" (:exit v)))
      (if all-zero 0 1))))

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
    (catch e
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
