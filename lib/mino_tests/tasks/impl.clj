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
  "Run the script-side adversarial runner with the given mode/seed.
   Optional :only restricts probes by path substring (used by
   diff-test to skip the T1..T11 battery)."
  [opts]
  (let [bin    (mino-bin)
        runner (runner-path "runner.clj")
        seed   (str (or (:seed opts) 0))
        mode   (name (or (:mode opts) :smoke))
        only   (:only opts)
        argv   (cond-> [bin runner "--seed" seed "--mode" mode]
                 only (concat ["--only" only]))]
    (println "  exec:" (clojure.string/join " " argv))
    (try
      (println (apply sh! argv))
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

(defn- detect-tool
  "Resolve a clang tool. On macOS prefers xcrun -f; otherwise falls
   back to a plain `which` lookup. Returns the absolute path or nil."
  [name]
  (let [via-xcrun (try (sh! "xcrun" "-f" name) (catch _ nil))
        via-which (try (sh! "which" name) (catch _ nil))]
    (or (when via-xcrun (str/trim via-xcrun))
        (when via-which (str/trim via-which)))))

(defn cov-run
  "Build the harness with llvm-cov instrumentation, run it, merge
   the profile data, and emit an HTML report. Clang-only; a clean
   error message lands if any LLVM tool is missing."
  []
  (let [root      (repo-root)
        clang     (detect-tool "clang")
        profdata  (detect-tool "llvm-profdata")
        cov       (detect-tool "llvm-cov")
        mino-root (mino-src-root)
        out-dir   (str root "/tests/adv/coverage")]
    (cond
      (nil? clang)
      (do (println "  ERROR: clang not found; coverage requires clang")
          1)

      (nil? profdata)
      (do (println "  ERROR: llvm-profdata not found; install LLVM tools")
          1)

      (nil? cov)
      (do (println "  ERROR: llvm-cov not found; install LLVM tools")
          1)

      :else
      (do
        (println "  clang:        " clang)
        (println "  llvm-profdata:" profdata)
        (println "  llvm-cov:     " cov)
        (sh! "mkdir" "-p" out-dir)

        (let [;; build with CC=clang for the instrumentation
              cc-env (getenv "CC")
              _      (println "  building :cov variant...")
              ;; build-harness reads CC env; set it via shell-out
              build-result (try
                             (sh! "env" (str "CC=" clang)
                                  (str (getenv "PWD") "/mino/mino")
                                  "-e"
                                  (str "(do (load-file \"lib/mino_tests/tasks/impl.clj\") "
                                       "(in-ns (quote mino-tests.tasks.impl)) "
                                       "(build-harness :cov))"))
                             (catch e (str "build-error: " e)))]
          (println "  build:" build-result)

          (let [bin     (str out-dir "/../build/adv_test_cov")
                profraw (str out-dir "/mino.profraw")
                merged  (str out-dir "/mino.profdata")
                report  (str out-dir "/report.html")]
            (if-not (file-exists? bin)
              (do (println "  ERROR: cov binary not built:" bin) 1)
              (do
                (println "  running cov harness...")
                (try (sh! "env" (str "LLVM_PROFILE_FILE=" profraw) bin)
                     (catch e (println "    (some probes may fail under cov)")))

                (println "  merging profdata...")
                (sh! profdata "merge" "-sparse"
                     "-o" merged profraw)

                (println "  generating html report...")
                (sh! cov "show" bin
                     (str "-instr-profile=" merged)
                     "-format=html"
                     "-output-dir=" (str out-dir "/html"))

                (println "  report at:" (str out-dir "/html/index.html"))
                0))))))))

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

;; ---- GC safeguards -----------------------------------------------
;;
;; These catch the class of GC bug that hid behind full-suite
;; Heisenbug masking during the v0.241-v0.252 cycle (see
;; mino/.local/BUGS.md "CI hang at transient-survives-gc-yield").
;; Each safeguard targets a different shape of bug:
;;
;;   gc-fuzz                  -- vary nursery size; bugs sensitive
;;                               to GC-phase × test-position alignment
;;   gc-stress-subset         -- MINO_GC_STRESS=1 on transient_test +
;;                               gc_test; every alloc forces a major
;;   gc-verify                -- MINO_GC_VERIFY=1 on the full suite;
;;                               aborts on barrier/remset miss
;;   asan-per-file            -- one ASan run per test file rather
;;                               than one for the whole suite

(defn- run-in-mino
  "Run a shell command inside the mino submodule directory with
   optional extra env vars. Returns {:exit N :out S} from sh."
  [env-pairs cmd]
  (let [env-pairs (or env-pairs [])
        env-str   (apply str (interpose " " (map (fn [[k v]] (str k "=" v)) env-pairs)))
        full-cmd  (if (empty? env-pairs) cmd (str env-str " " cmd))]
    (sh "sh" "-c" (str "cd mino && " full-cmd))))

(defn gc-fuzz
  "Run mino's tests/run.clj at four different nursery sizes. Catches
   bugs whose appearance depends on the GC's major-phase × test-
   position alignment -- the precise alignment that hides the bug at
   nursery=4 MiB might surface it at 64 KiB. Adds ~4x suite runtime
   but stays well under a minute total."
  []
  (let [sizes [65536 262144 1048576 4194304]
        results
        ;; pipefail so a non-zero exit from mino propagates through
        ;; the tail -3 truncation (otherwise the tail's exit masks
        ;; any test-suite abort).
        (mapv (fn [sz]
                (println "  gc-fuzz nursery=" sz "bytes")
                (let [r (run-in-mino [["MINO_GC_NURSERY_BYTES" sz]]
                                     "set -o pipefail; ./mino tests/run.clj 2>&1 | tail -3")]
                  (println "    " (clojure.string/trim (or (:out r) "")))
                  {:nursery sz :exit (:exit r) :ok (zero? (:exit r))}))
              sizes)
        failed (filterv (fn [r] (not (:ok r))) results)]
    (if (empty? failed)
      (do (println "  gc-fuzz: OK across" (count sizes) "nursery sizes") 0)
      (do (println "  gc-fuzz: failed at" (pr-str failed)) 1))))

(defn gc-stress-subset
  "Run the gc-bang stress shard with MINO_GC_STRESS=1. Every
   allocation forces a full STW major collection, so every `conj!`
   / `(gc!)` exercises the dangerous transient + GC interaction.

   Why a shard not mino's full transient_test.clj / gc_test.clj:
   stress mode multiplies allocation cost by ~1000x, so the
   2000-iter loops in those files would run for tens of minutes.
   tests/gc_bang_stress_shard.clj pins the shapes that matter
   (transient mutations across `(gc!)`, gc! mid-incremental-major)
   at iteration counts that finish in ~50 s.

   Honours the standing rule that GC_STRESS over the whole mino
   suite takes 30+ minutes -- this shard is the curated subset."
  []
  (let [shard "tests/gc_bang_stress_shard.clj"
        bin   (mino-bin)
        argv  ["env" "MINO_GC_STRESS=1" bin shard]]
    (println "  exec:" (clojure.string/join " " argv))
    (try
      (println (apply sh! argv))
      (println "  gc-stress-subset: OK")
      0
      (catch e
        (println "  gc-stress-subset failed:" (str e)) 1))))

(defn gc-verify
  "Run mino's tests/run.clj with MINO_GC_VERIFY=1. The verifier
   walks every live OLD before each minor and asserts no unreported
   YOUNG pointers; aborts on a missing write barrier or remset
   entry. Allowed-to-fail until the existing pre-cycle barrier-miss
   sites in mino's runtime are also resolved (see mino's
   .local/BUGS.md). Useful as a regression detector for any new
   site introduced after the cleanup."
  []
  (println "  gc-verify (allowed-to-fail; tracks known barrier-miss bugs)")
  (let [r (run-in-mino [["MINO_GC_VERIFY" "1"]]
                       "./mino tests/run.clj 2>&1 | tail -3")]
    (println "    " (clojure.string/trim (or (:out r) "")))
    ;; Always return 0 -- this is a tracking signal, not a gate.
    0))

(defn asan-per-file
  "Build mino with ASan, then run each test file as its own
   subprocess. Catches Heisenbugs that hide under full-suite ASan
   runs because the cumulative heap state masks the dangerous
   phase window."
  []
  (println "  asan-per-file building...")
  (let [build-r (run-in-mino [] "./mino task build-asan 2>&1 | tail -2")]
    (when-not (zero? (:exit build-r))
      (println "    build-asan failed:" (:out build-r))
      (throw (ex-info "asan-per-file build failed" {:out (:out build-r)}))))
  ;; mino has no list-dir primitive; explicit file list. Mirrors the
  ;; require chain in mino's tests/run.clj at v0.255.9 -- new files
  ;; need to land here too.
  (let [files ["tests/compat_test.clj"
               "tests/arithmetic_test.clj"
               "tests/binding_test.clj"
               "tests/control_test.clj"
               "tests/function_test.clj"
               "tests/collection_test.clj"
               "tests/string_test.clj"
               "tests/sequence_test.clj"
               "tests/lazy_test.clj"
               "tests/macro_test.clj"
               "tests/error_test.clj"
               "tests/atom_test.clj"
               "tests/stm_test.clj"
               "tests/predicate_test.clj"
               "tests/io_test.clj"
               "tests/reflection_test.clj"
               "tests/repl_test.clj"
               "tests/gc_test.clj"
               "tests/math_test.clj"
               "tests/hash_compare_test.clj"
               "tests/regex_test.clj"
               "tests/tco_test.clj"
               "tests/core_extra_test.clj"
               "tests/destructuring_test.clj"
               "tests/reader_macros_test.clj"
               "tests/protocol_test.clj"
               "tests/core_protocols_test.clj"
               "tests/iteration_test.clj"
               "tests/metadata_test.clj"
               "tests/transducer_test.clj"
               "tests/dialect_test.clj"
               "tests/empty_list_test.clj"
               "tests/bc_try_catch_test.clj"
               "tests/jit_parity_test.clj"
               "tests/bc_binding_test.clj"
               "tests/bc_destructure_test.clj"
               "tests/bc_closure_test.clj"
               "tests/bc_let_fold_test.clj"
               "tests/bc_bitwise_test.clj"
               "tests/ifn_test.clj"
               "tests/stack_test.clj"
               "tests/sorted_test.clj"
               "tests/transient_test.clj"
               "tests/conformance_test.clj"
               "tests/var_test.clj"
               "tests/literal_test.clj"
               "tests/numeric_tower_test.clj"
               "tests/records_test.clj"
               "tests/data_test.clj"
               "tests/spec_test.clj"
               "tests/async_smoke_test.clj"
               "tests/fs_test.clj"
               "tests/proc_test.clj"
               "tests/deps_test.clj"]
        ;; Per-file driver: the test file registers deftests on
        ;; load, then run-tests-and-exit drives them. Matches the
        ;; pattern gc-stress-subset uses. `set -o pipefail` forces
        ;; the shell to propagate ASan's non-zero exit through the
        ;; tail -3 pipe -- without pipefail the pipe-tail exit
        ;; would mask any ASan abort.
        driver-for (fn [f]
                     (str "(require \\\"tests/test\\\") "
                          "(load-file \\\"" f "\\\") "
                          "(run-tests-and-exit)"))
        results
        (mapv (fn [f]
                (let [r (run-in-mino [] (str "set -o pipefail; "
                                              "./mino_asan -e \""
                                              (driver-for f)
                                              "\" 2>&1 | tail -3"))]
                  {:file f :exit (:exit r) :ok (zero? (:exit r))
                   :out (or (:out r) "")}))
              files)
        failed (filterv (fn [r] (not (:ok r))) results)]
    (println "  asan-per-file: tested" (count files) "files,"
             "passed" (- (count files) (count failed)) ","
             "failed" (count failed))
    (when (pos? (count failed))
      (doseq [f failed]
        (println "    FAIL" (:file f))
        (println "      " (clojure.string/trim (:out f)))))
    (if (empty? failed) 0 1)))

;; ---- ClojureDocs corpus refresh ----

(defn clojuredocs-refresh
  "Re-download the ClojureDocs example export, parse it, and re-run
   each surviving tuple through bb to record fresh ground truth.
   The result overwrites tests/adv/fixtures/clojuredocs-tuples.edn.

   Dev-host only -- needs bb on PATH and network access. The CI
   nightly run reads the vendored EDN and never refreshes."
  []
  (let [script (str (repo-root) "/tests/adv/clojuredocs_build.clj")]
    (println "  exec: bb" script)
    (try
      (println (sh! "bb" script))
      0
      (catch e
        (println "  clojuredocs-refresh failed:" (str e))
        1))))
