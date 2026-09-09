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

(defn run-script-suite-with-summary
  "Like run-script-suite but sets MINO_PROBE_SUMMARY so the runner
   writes a structured EDN summary to :summary-path before exiting."
  [opts]
  (let [bin    (mino-bin)
        runner (runner-path "runner.clj")
        seed   (str (or (:seed opts) 0))
        mode   (name (or (:mode opts) :smoke))
        only   (:only opts)
        out    (:summary-path opts "output/probe-results.edn")
        cmd    (cond-> (str "MINO_PROBE_SUMMARY=" out " "
                            bin " " runner " --seed " seed " --mode " mode)
                 only (str " --only " only))]
    (try (sh! "mkdir" "-p" "output") (catch Throwable e nil))
    (println "  exec: sh -c" cmd)
    (try
      (println (sh! "sh" "-c" cmd))
      (try
        (println "---")
        (println (slurp out))
        (catch e
          (println "  (no summary file produced)")))
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
;; lib-srcs -- intentionally inlined so a mino API drift surfaces here
;; as a build break rather than silently linking against an older
;; surface. Keep in sync with that list when mino adds/moves/splits a
;; translation unit (last synced against mino's v0.423.x lib-srcs).
(def mino-lib-srcs
  ["src/eval/eval.c" "src/diag/diag.c" "src/eval/special.c"
   "src/eval/special_registry.c"
   "src/eval/defs.c" "src/eval/bindings.c"
   "src/eval/bindings_dyn.c" "src/eval/bindings_destr.c"
   "src/eval/control.c" "src/eval/fn.c"
   "src/eval/fn_argv.c" "src/eval/fn_nonfn.c"
   "src/eval/bc/vm.c" "src/eval/bc/compile.c"
   "src/eval/bc/gc_handlers.c"
   "src/eval/bc/jit/entry.c" "src/eval/bc/jit/stats.c"
   "src/eval/bc/jit/helpers.c" "src/eval/bc/jit/helpers_loop.c"
   "src/eval/bc/jit/patcher.c"
   "src/eval/bc/jit/patcher_x86_64.c"
   "src/eval/bc/jit/emit.c" "src/eval/bc/jit/region.c"
   "src/runtime/state.c" "src/runtime/var.c"
   "src/runtime/error.c" "src/runtime/env.c"
   "src/runtime/ns_env.c"
   "src/runtime/path_buf.c"
   "src/runtime/host_threads.c"
   "src/runtime/capabilities.c"
   "src/runtime/image.c"
   "src/runtime/image_load.c"
   "src/gc/driver.c" "src/gc/roots.c" "src/gc/major.c"
   "src/gc/barrier.c" "src/gc/minor.c"
   "src/gc/trace.c" "src/gc/profile.c" "src/runtime/module.c"
   "src/public/gc.c" "src/public/embed.c"
   "src/values/val.c" "src/values/gc_handlers.c"
   "src/collections/vec.c" "src/collections/map.c"
   "src/collections/map_hash.c" "src/collections/map_owned.c"
   "src/collections/chunk.c"
   "src/collections/queue.c"
   "src/collections/bytes.c"
   "src/collections/rbtree.c"
   "src/collections/builders.c"
   "src/collections/gc_handlers.c"
   "src/collections/iter.c" "src/eval/read.c" "src/eval/read_numeric.c" "src/eval/print.c"
   "src/eval/print_dynvars.c" "src/eval/special_host.c"
   "src/prim/prim.c" "src/prim/install.c" "src/prim/install_stdlib.c"
   "src/prim/numeric.c" "src/prim/numeric_math.c"
   "src/prim/numeric_bit.c" "src/prim/numeric_coerce.c"
   "src/prim/collections.c" "src/prim/collections_transient.c"
   "src/prim/bits.c"
   "src/prim/sequences.c" "src/prim/sequences_seq.c"
   "src/prim/lazy.c"
   "src/prim/string.c" "src/prim/io.c"
   "src/prim/reflection.c" "src/prim/meta.c" "src/prim/regex.c"
   "src/prim/stateful.c" "src/prim/stateful_bindings.c" "src/prim/stm.c" "src/prim/agent.c" "src/prim/store.c" "src/prim/module.c"
   "src/prim/image.c"
   "src/prim/ns.c"
   "src/prim/fs.c" "src/prim/proc.c"
   "src/prim/host.c" "src/prim/jvm_statics.c" "src/interop/syntax.c"
   "src/collections/clone.c" "src/regex/re_compile.c" "src/regex/re_match.c" "src/collections/transient.c"
   "src/async/scheduler.c" "src/async/timer.c" "src/async/chan.c"
   "src/prim/async.c"
   "src/prim/bignum.c" "src/prim/ratio.c" "src/prim/bigdec.c"
   "src/vendor/imath/imath.c"])

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
        ;; The :release variant links against mino's prebuilt .o files
        ;; (fast incremental). Sanitizer and :cov variants recompile
        ;; mino sources alongside the harness so their flags reach
        ;; every TU; for :cov that means mino itself is instrumented
        ;; and the report covers the runtime, not just the harness.
        ;; mino's own build doesn't keep per-variant .o files (it
        ;; produces mino_asan / mino_ubsan / mino_tsan as single-cc
        ;; binaries), so this is the cleanest way to share the runtime
        ;; under a non-default build.
        compile-mino-from-source? (boolean (#{:asan :tsan :ubsan :cov} variant))
        mino-objs (mapv #(str mino-root "/" (src->obj %)) mino-lib-srcs)
        mino-srcs (mapv #(str mino-root "/" %) mino-lib-srcs)
        mino-pieces (if compile-mino-from-source? mino-srcs mino-objs)
        first-piece (first mino-pieces)
        flags     (concat ["-std=c99" "-Wall" "-Wno-extra-semi"
                           "-DMINO_CPJIT=1"]
                          sanitize)
        harness-c (map #(str root "/" %) harness-libs)
        embeds-c  (map #(str root "/" %) embed-probe-srcs)
        argv      (concat [cc] flags
                          (map #(str "-I" mino-root "/" %)
                               ["src" "src/public" "src/runtime"
                                "src/gc" "src/eval" "src/values"
                                "src/collections" "src/prim" "src/async"
                                "src/interop" "src/diag"
                                "src/vendor/imath"])
                          ["-I" (str root "/tests/adv")]
                          harness-c embeds-c
                          mino-pieces
                          ["-lm" "-lpthread" "-o" out])]
    (println "  cc:        " cc)
    (println "  variant:   " (name (or variant :release)))
    (println "  out:       " out)
    (sh! "mkdir" "-p" out-dir)
    (if (or compile-mino-from-source? (file-exists? first-piece))
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
  "Resolve a clang tool. Prefers a PATH hit (/usr/bin/clang is the
  xcrun wrapper, which resolves the macOS SDK itself); falls back to
  xcrun -f for tools that are not on PATH (llvm-cov, llvm-profdata).
  Returns the absolute path or nil."
  [name]
  (let [via-which (try (sh! "which" name) (catch _ nil))
        via-xcrun (try (sh! "xcrun" "-f" name) (catch _ nil))]
    (or (when via-which (str/trim via-which))
        (when via-xcrun (str/trim via-xcrun)))))

;; ---- Mutation lane: matched-toolchain resolvers -------------------
;;
;; Mull mutates LLVM IR via a pass plugin loaded into clang, then runs
;; one binary once per mutant. Apple clang cannot load the plugin and
;; the plugin's ABI is pinned to one LLVM major, so the lane MUST use a
;; real LLVM clang whose major matches the installed Mull. Phase 0
;; pinned llvm@19 + mull@19 0.34.0 on this host. Every tool is resolved
;; by ABSOLUTE path: Homebrew llvm@19 is keg-only (no clang symlink on
;; PATH) and the Phase 0 mull install is a plain prefix, so PATH must
;; never be relied on here.

(defn- llvm19-prefix
  "Absolute prefix of the matched keg-only LLVM. MINO_LLVM19_PREFIX
   overrides; defaults to the Homebrew llvm@19 keg."
  []
  (or (getenv "MINO_LLVM19_PREFIX")
      (let [via-brew (try (sh! "brew" "--prefix" "llvm@19") (catch _ nil))]
        (when via-brew (str/trim via-brew)))
      "/opt/homebrew/opt/llvm@19"))

(defn- mull-prefix
  "Absolute prefix of the matched Mull install. MINO_MULL_PREFIX
   overrides; defaults to the Phase 0 install under /tmp/mull19.
   mull-runner-N lives in <prefix>/bin, the IR-frontend plugin in
   <prefix>/lib."
  []
  (or (getenv "MINO_MULL_PREFIX")
      "/tmp/mull19"))

(defn mut-clang
  "Absolute path to the matched LLVM clang the mutation lane compiles
   with. Never a bare `clang` on PATH."
  []
  (str (llvm19-prefix) "/bin/clang"))

(defn mull-runner
  "Absolute path to mull-runner-19."
  []
  (str (mull-prefix) "/bin/mull-runner-19"))

(defn mull-ir-frontend
  "Absolute path to the mull-ir-frontend-19 pass plugin."
  []
  (str (mull-prefix) "/lib/mull-ir-frontend-19"))

(defn- llvm-major
  "Extract the LLVM major version from a `--version` blob. clang prints
   `... clang version 19.1.7`; mull-runner prints a `LLVM: 19.1.7`
   line. Returns the integer major or nil."
  [text]
  (when text
    (let [m (or (re-find #"clang version (\d+)\." text)
                (re-find #"LLVM:\s*(\d+)\." text)
                (re-find #"version (\d+)\." text))]
      (when m (long (read-string (second m)))))))

(defn mutation-doctor
  "Assert the matched (clang, mull) pair is present and their LLVM
   majors agree. Fails loudly (throws) on a missing tool or a major
   mismatch -- Mull's pass ABI is version-locked, so a mismatch would
   silently mutate a phantom IR. Returns the agreed major on success."
  []
  (let [clang    (mut-clang)
        runner   (mull-runner)
        frontend (mull-ir-frontend)]
    (println "  clang:           " clang)
    (println "  mull-runner:     " runner)
    (println "  mull-ir-frontend:" frontend)
    (when-not (file-exists? clang)
      (throw (ex-info (str "matched clang not found at " clang
                           " (install llvm@19 or set MINO_LLVM19_PREFIX)")
                      {:clang clang})))
    (when-not (file-exists? runner)
      (throw (ex-info (str "mull-runner-19 not found at " runner
                           " (install mull@19 or set MINO_MULL_PREFIX)")
                      {:runner runner})))
    (when-not (file-exists? frontend)
      (throw (ex-info (str "mull-ir-frontend-19 not found at " frontend
                           " (install mull@19 or set MINO_MULL_PREFIX)")
                      {:frontend frontend})))
    (let [clang-ver  (try (sh! clang "--version") (catch e (str e)))
          runner-ver (try (sh! runner "--version") (catch e (str e)))
          cmaj (llvm-major clang-ver)
          rmaj (llvm-major runner-ver)]
      (println "  clang LLVM major:      " cmaj)
      (println "  mull-runner LLVM major:" rmaj)
      (when (or (nil? cmaj) (nil? rmaj))
        (throw (ex-info "could not parse an LLVM major from --version"
                        {:clang-major cmaj :runner-major rmaj})))
      (when-not (= cmaj rmaj)
        (throw (ex-info (str "LLVM major mismatch: clang " cmaj
                             " != mull-runner " rmaj
                             " -- Mull's pass ABI is version-locked")
                        {:clang-major cmaj :runner-major rmaj})))
      (println "  mutation-doctor: matched pair OK (LLVM" cmaj ")")
      cmaj)))

;; ---- Mutation lane: mino_mut build --------------------------------
;;
;; mino's own build is a single `cc $(SRCS)` shot; it keeps no per-TU
;; .o files. The mutation lane needs the critical dir's TUs carrying
;; Mull's embedded mutants while the rest of the runtime stays clean,
;; so it compiles every TU to a .o (the critical dir's through the pass
;; plugin) and links `mino_mut`. Mull mutates post-preprocessing IR, so
;; the flag set MUST match mino's shipped build exactly (minus warning
;; flags, which have no IR effect); a divergent -D set would mutate a
;; program mino never ships.

;; The mino Makefile globs its SRCS from these directories (kept in
;; sync with mino/Makefile's SRCS wildcard). mino has no dir-walk
;; primitive, so the source list is gathered by shelling `find` inside
;; the submodule rather than hand-listing every TU (which drifts).
(def ^:private mino-src-globs
  ["src/eval/*.c" "src/eval/bc/*.c" "src/eval/bc/jit/*.c"
   "src/read/*.c" "src/print/*.c" "src/diag/*.c"
   "src/names/*.c" "src/state/*.c" "src/gc/*.c" "src/public/*.c"
   "src/values/*.c" "src/collections/*.c" "src/prim/*/*.c"
   "src/interop/*.c" "src/regex/*.c" "src/async/*.c"
   "src/vendor/imath/*.c" "src/vendor/bearssl/*.c"
   "src/vendor/miniz/*.c" "src/cli/*.c"])

;; The exact include set from mino/Makefile's INCDIRS, relative to the
;; mino source root. Kept in sync with that Makefile.
(def ^:private mino-incdirs
  ["src" "src/generated" "src/public" "src/runtime" "src/gc" "src/eval"
   "src/read" "src/print" "src/names" "src/state"
   "src/values" "src/collections" "src/prim" "src/async"
   "src/interop" "src/diag" "src/vendor/imath"
   "src/vendor/bearssl" "src/vendor/bearssl/inc"
   "src/vendor/miniz" "src/vendor/miniz/upstream"])

;; The IR-determining flag set. Matches mino/Makefile CFLAGS exactly
;; except for the warning flags (-Wall -Wpedantic -Wextra -Werror and
;; the -Wno-* mutes), which have no effect on emitted IR and would only
;; turn a warning into a build failure under a different clang.
(def ^:private mino-mut-cflags
  ["-std=c99" "-O2" "-fno-strict-aliasing" "-DMINO_CPJIT=1"])

(defn- list-mino-srcs
  "Enumerate mino's SRCS by shelling `ls` over the Makefile's globs
   inside the submodule. Returns paths relative to the mino source
   root (e.g. src/read/read.c)."
  [mino-root]
  (let [pat (str/join " " mino-src-globs)
        r   (sh "sh" "-c" (str "cd " mino-root " && ls -1 " pat " 2>/dev/null"))]
    (->> (str/split-lines (or (:out r) ""))
         (map str/trim)
         (remove str/blank?)
         vec)))

;; Per-lane instrument allow-list. When a lane names an explicit TU
;; subset, ONLY those exact TUs carry mutants (a scoped subset of the
;; prefix). Rationale + logged sampling for the vm/bc lane:
;;
;; The full bc tree (JIT emitter, patcher, region allocator, stats)
;; yields ~2800 covered mutants per mode; vm.c alone is ~1900. Worse,
;; vm.c is the interpreter dispatch loop, so a large fraction of its
;; relational/arithmetic mutants break loop TERMINATION -- each such
;; mutant runs until the per-mutant timeout, and under jit=off/on parity
;; that tail is paid twice. Measured on this host the vm.c+compile.c
;; parity run does not converge inside any reasonable window (hours).
;;
;; The committed vm/bc score is therefore scoped to compile.c -- the
;; bytecode compiler's integer-overflow->bignum promotion guards and
;; clause/arity match, which the plan names and which carry no
;; interpreter-loop hangs. vm.c's operand/stack/safepoint mutants stay
;; in scope for a DEDICATED nightly window (set MINO_MUT_VMBC_TUS to
;; "src/eval/bc/vm.c,src/eval/bc/compile.c" and budget hours); they are
;; NOT silently dropped -- the report records the scope. jit_parity_test
;; and the bc_* oracle files still exercise vm.c behaviourally under
;; both modes, so a vm.c dispatch bug that changes a result is caught by
;; the oracle even when vm.c is outside the mutant-instrument scope.
(def ^:private lane-instrument-tus
  {"src/eval/bc"
   (if-let [ov (getenv "MINO_MUT_VMBC_TUS")]
     (set (map str/trim (str/split ov #",")))
     #{"src/eval/bc/compile.c"})

   ;; The gc lane's candidate TUs are the barrier + collector units the
   ;; plan names, whose mutants the bounded verify repros can catch: the
   ;; write barrier and remembered set (barrier.c), the minor collector
   ;; and promotion (minor.c), the mark driver (driver.c), the tracer
   ;; (trace.c), remset ranges (ranges.c), and root scanning (roots.c).
   ;; All six ARE consumed by mull-ir-frontend (verified: the 6-TU build
   ;; links clean). major.c and profile.c are excluded: a full-major
   ;; mutant is exercised only by the repros' occasional majors, and
   ;; profile.c is pure instrumentation with no invariant the verify
   ;; repros probe.
   ;;
   ;; COST CONTROL (measured, not silent): the full 6-TU set yields ~685
   ;; covered mutants, and each mutant reruns the bounded-verify oracle
   ;; (~13 s baseline), so the full run is ~30 min at 4 workers -- well
   ;; over the ~6 min budget. The committed default SAMPLES to the two
   ;; TUs whose invariants the verify repros probe most directly:
   ;; barrier.c (the write barrier / remset -- carries the proven
   ;; barrier-trigger inversion kill) and minor.c (promotion + the minor
   ;; collector the repros drive every iteration). The other four TUs are
   ;; NOT silently dropped: they are recorded in the report's
   ;; :sampled-out-tus and stay in scope for a dedicated nightly window
   ;; (set MINO_MUT_GC_TUS to the full comma list and budget ~30 min).
   "src/gc"
   (if-let [ov (getenv "MINO_MUT_GC_TUS")]
     (set (map str/trim (str/split ov #",")))
     #{"src/gc/barrier.c" "src/gc/minor.c"})})

;; The full gc candidate set, recorded so the sampled default can name
;; exactly what it left for a dedicated window (no silent truncation).
(def ^:private gc-full-candidate-tus
  #{"src/gc/barrier.c" "src/gc/minor.c" "src/gc/driver.c"
    "src/gc/trace.c" "src/gc/ranges.c" "src/gc/roots.c"})

(defn mutation-build
  "Compile mino into `mino_mut` with the critical dir's TUs carrying
   Mull mutants. `dir` is a path prefix relative to the mino source
   root (default \"src/read\") -- only TUs under it are compiled
   through the pass plugin; every other TU is compiled normally. A dir
   with a lane-instrument-tus allow-list mutates only that exact subset.
   Links with -lm -lpthread. Returns 0 on success, 1 on failure."
  ([] (mutation-build "src/read"))
  ([dir]
   (mutation-doctor)
   (let [root      (repo-root)
         mino-root (mino-src-root)
         clang     (mut-clang)
         frontend  (mull-ir-frontend)
         out-dir   (str root "/tests/mutation/build")
         obj-dir   (str out-dir "/obj")
         out       (str out-dir "/mino_mut")
         incflags  (mapv #(str "-I" mino-root "/" %) mino-incdirs)
         allow     (get lane-instrument-tus dir)
         srcs      (list-mino-srcs mino-root)]
     (println "  clang:   " clang)
     (println "  path dir:" dir)
     (when allow (println "  scoped to:" (pr-str allow)))
     (println "  sources: " (count srcs) "TUs")
     (println "  out:     " out)
     (if (empty? srcs)
       (do (println "  ERROR: no mino sources found under" mino-root)
           1)
       (do
         (sh! "mkdir" "-p" obj-dir)
         (let [mutated (atom [])
               objs
               (mapv
                (fn [src]
                  (let [mutate? (if allow
                                  (contains? allow src)
                                  (str/starts-with? src (str dir "/")))
                        obj     (str obj-dir "/"
                                     (str/replace (src->obj src) "/" "__"))
                        base    (concat [clang] mino-mut-cflags incflags)
                        argv    (concat base
                                        (when mutate?
                                          ["-g" "-grecord-command-line"
                                           (str "-fpass-plugin=" frontend)])
                                        ["-c" (str mino-root "/" src)
                                         "-o" obj])]
                    (when mutate?
                      (swap! mutated conj src)
                      (println "  [mutate]" src))
                    (try
                      (apply sh! argv)
                      obj
                      (catch e
                        (println "  compile failed:" src)
                        (println "   " (str e))
                        (throw (ex-info (str "compile failed: " src)
                                        {:src src}))))))
                srcs)]
           (println "  mutated" (count @mutated) "TUs:" (pr-str @mutated))
           (println "  linking" out "...")
           (try
             (apply sh! (concat [clang] objs
                                ["-lm" "-lpthread" "-o" out]))
             (println "  built:" out)
             0
             (catch e
               (println "  link failed:" (str e))
               1))))))))

;; ---- Mutation lane: run + score -----------------------------------
;;
;; `mutation <dir>` runs mull-runner over mino_mut using the dir's
;; kill-signal command as the test oracle (Mull reads its exit code),
;; then scores the IDE reporter output into an EDN survivor summary --
;; the same shape/spirit as cov-summary's coverage EDN. Mutants live
;; only in the TUs mutation-build compiled through the pass plugin, so
;; the score is scoped to that dir without a runner-side path filter.

;; Per-dir kill-signal oracle registry. Each value is an argv vector
;; (relative to repo root, mino_mut prepended by the runner call) that
;; exercises the dir's invariants and exits non-zero on any mismatch.
;; The reader subset (populated in the reader-wiring commit) points at
;; a curated mino script under tests/mutation/oracles/.
(defn- kill-signal-argv
  "Return the oracle argv for a critical dir, or nil if unregistered."
  [dir]
  (let [root (repo-root)]
    (get {"src/read"    [(str root "/tests/mutation/oracles/read_kill.clj")]
          "src/values"  [(str root "/tests/mutation/oracles/values_kill.clj")]
          "src/eval/bc" [(str root "/tests/mutation/oracles/vmbc_kill.clj")]
          "src/gc"      [(str root "/tests/mutation/oracles/gc_kill.clj")]}
         dir)))

;; Dirs whose kill-signal must run under BOTH the interpreter and the
;; JIT. A vm/bc mutant is KILLED if it dies under either mode, so the
;; `mutation` task unions the two runs' killed sets and only a mutant
;; that survives BOTH is a genuine survivor. MINO_JIT toggles the mode;
;; mino_mut is built -DMINO_CPJIT=1 so both paths are live.
(def ^:private jit-parity-dirs #{"src/eval/bc"})

(defn parse-mutation-report
  "Parse mull-runner IDE reporter text into a scored summary map.
   The summary line is `[info] Survived mutants (S/T):` and each
   survivor is a `<file>:<line>:<col>: warning: Survived: <desc>
   [<operator>]` line. Returns nil if no summary line is present
   (a 100%-killed run prints no survivor block)."
  [text]
  (let [lines   (str/split-lines (or text ""))
        surv-re #"Survived mutants \((\d+)/(\d+)\)"
        summary (some #(re-find surv-re %) lines)
        ;; A survivor detail line carries the operator tag in brackets.
        det-re  #"^(.+?):(\d+):(\d+): warning: Survived: (.+) \[([a-z0-9_]+)\]$"
        survivors
        (->> lines
             (keep (fn [l]
                     (when-let [m (re-find det-re (str/trim l))]
                       {:file (nth m 1)
                        :line (long (read-string (nth m 2)))
                        :col  (long (read-string (nth m 3)))
                        :desc (nth m 4)
                        :operator (nth m 5)})))
             vec)]
    (if summary
      (let [survived (long (read-string (nth summary 1)))
            total    (long (read-string (nth summary 2)))
            killed   (- total survived)]
        {:total total
         :killed killed
         :survived survived
         :score (if (pos? total) (/ (double killed) total) 1.0)
         :by-operator (->> survivors
                           (group-by :operator)
                           (map (fn [[k v]] [k (count v)]))
                           (into (sorted-map)))
         :survivors survivors})
      ;; No survivor block: either a clean 100% run or a total we can
      ;; still recover from the "Mutation score" line if present.
      (when (some #(str/includes? % "All mutations have been killed") lines)
        {:total nil :killed nil :survived 0 :score 1.0
         :by-operator (sorted-map) :survivors []}))))

(defn- mull-workers
  "Optional --workers count for mull-runner. MINO_MULL_WORKERS bounds
   the per-mutant fan-out so a doubled-cost parity dir stays within the
   nightly budget. Returns [\"--workers\" \"N\"] or an empty vector."
  []
  (if-let [w (getenv "MINO_MULL_WORKERS")]
    ["--workers" (str/trim w)]
    []))

(defn- run-mull-once
  "Run mull-runner over mino_mut once with `oracle` as the test command,
   under the given env-pairs, capturing the IDE report to `raw`. Returns
   the parsed summary map (or nil if unparseable)."
  [runner binp mino-root oracle env-pairs raw]
  (let [;; MINO_MUT_BIN lets a subprocess-style oracle re-invoke the
        ;; SAME mutated binary for its per-file children (the vm/bc
        ;; oracle needs this; single-load oracles ignore it).
        env-pairs (concat env-pairs [["MINO_MUT_BIN" binp]])
        env-str (apply str (interpose " "
                             (map (fn [[k v]] (str k "=" v)) env-pairs)))
        ;; The vm/bc oracle spawns 14 children, so its baseline runs a
        ;; few seconds; mull's warmup timeout must clear that. --timeout
        ;; sets a generous ceiling (per-mutant is still bounded by
        ;; max(baseline*10, this)). MINO_MULL_TIMEOUT overrides.
        timeout (or (some-> (getenv "MINO_MULL_TIMEOUT") str/trim)
                    "60000")
        argv    (concat [runner "--reporters" "IDE"
                         "--ide-reporter-show-killed"
                         "--timeout" timeout]
                        (mull-workers)
                        [binp] oracle)
        cmd     (str "cd " (pr-str mino-root) " && "
                     (when (seq env-pairs) (str env-str " "))
                     (str/join " " (map pr-str argv)) " > "
                     (pr-str raw) " 2>&1")]
    (println "  exec:" cmd)
    (let [t0 (time-ms)
          _  (sh "sh" "-c" cmd)
          dt (- (time-ms) t0)]
      (println (format "  wall-clock: %.1fs" (/ dt 1000.0)))
      (parse-mutation-report (try (slurp raw) (catch e ""))))))

(defn- survivor-key [s]
  ;; A mutant is identified by its site + operator; parity unions on this.
  [(:file s) (:line s) (:col s) (:operator s)])

(defn- union-parity
  "Union two mode summaries into one: a mutant survives the dir only if
   it survives BOTH modes. Killed-under-either => killed. Recomputes the
   score over the intersection of survivor sets. The totals differ only
   if coverage differs between modes, so the larger total is authoritative."
  [a b]
  (let [sa (set (map survivor-key (:survivors a)))
        sb (set (map survivor-key (:survivors b)))
        both (into #{} (filter sa sb))
        survivors (filterv #(both (survivor-key %)) (:survivors a))
        total (max (or (:total a) 0) (or (:total b) 0))
        survived (count survivors)
        killed (- total survived)]
    {:total total
     :killed killed
     :survived survived
     :score (if (pos? total) (/ (double killed) total) 1.0)
     :by-operator (->> survivors (group-by :operator)
                       (map (fn [[k v]] [k (count v)]))
                       (into (sorted-map)))
     :survivors survivors}))

(defn- print-summary [dir edn summary]
  (println "  --- mutation summary (" dir ") ---")
  (println "  total:   " (:total summary))
  (println "  killed:  " (:killed summary))
  (println "  survived:" (:survived summary))
  (when (:total summary)
    (println "  score:   " (format "%.1f%%" (* 100.0 (:score summary)))))
  (println "  by-operator:" (pr-str (:by-operator summary)))
  (when (:instrument-scope summary)
    (println "  instrument-scope:" (pr-str (:instrument-scope summary))))
  (when (:sampled-out-tus summary)
    (println "  sampled-out-tus (dedicated window, logged):"
             (pr-str (:sampled-out-tus summary))))
  (println "  report:  " edn))

(defn mutation
  "Run mull-runner over mino_mut with `dir`'s kill-signal oracle and
   emit a scored survivor summary as EDN under
   tests/mutation/reports/<dir-tag>.edn. Assumes `mutation-build`
   already produced mino_mut for the same dir. Returns 0 on a
   completed run (a low score is a finding, not a task failure).

   For a jit-parity dir (src/eval/bc) the oracle runs twice, once under
   MINO_JIT=off and once under MINO_JIT=on; the reported survivors are
   those that survived BOTH modes."
  ([] (mutation "src/read"))
  ([dir]
   (mutation-doctor)
   (let [root      (repo-root)
         mino-root (mino-src-root)
         runner    (mull-runner)
         binp      (str root "/tests/mutation/build/mino_mut")
         oracle    (kill-signal-argv dir)
         dir-tag   (str/replace dir "/" "_")
         rpt-dir   (str root "/tests/mutation/reports")
         edn       (str rpt-dir "/" dir-tag ".edn")]
     (cond
       (not (file-exists? binp))
       (do (println "  ERROR: mino_mut not built; run mutation-build" dir "first")
           1)

       (nil? oracle)
       (do (println "  ERROR: no kill-signal oracle registered for" dir)
           1)

       :else
       (do
         (sh! "mkdir" "-p" rpt-dir)
         (println "  runner: " runner)
         (println "  binary: " binp)
         (println "  oracle: " (str/join " " oracle))
         (when (seq (mull-workers))
           (println "  workers:" (second (mull-workers))))
         (println "  running mull-runner (this takes a while)...")
         (if (jit-parity-dirs dir)
           ;; Parity: two modes, union the survivor sets. A mutant is a
           ;; genuine survivor only if it survives BOTH interpreter and JIT.
           (let [raw-off (str rpt-dir "/" dir-tag ".jitoff.ide.txt")
                 raw-on  (str rpt-dir "/" dir-tag ".jiton.ide.txt")
                 _   (println "  --- mode: MINO_JIT=off ---")
                 s-off (run-mull-once runner binp mino-root oracle
                                      [["MINO_JIT" "off"]] raw-off)
                 _   (println "  --- mode: MINO_JIT=on ---")
                 s-on (run-mull-once runner binp mino-root oracle
                                     [["MINO_JIT" "on"]] raw-on)]
             (cond
               (nil? s-off)
               (do (println "  ERROR: could not parse jit=off output; see" raw-off) 1)
               (nil? s-on)
               (do (println "  ERROR: could not parse jit=on output; see" raw-on) 1)
               :else
               (let [summary (assoc (union-parity s-off s-on)
                                    :jit-parity true
                                    :jit-off-survived (:survived s-off)
                                    :jit-on-survived (:survived s-on)
                                    :instrument-scope
                                    (vec (sort (get lane-instrument-tus dir)))
                                    :sampled-out
                                    ["tests/tco_test.clj"
                                     "tests/bc_tail_multiarity_test.clj"])]
                 (spit edn (with-out-str (println (pr-str summary))))
                 (println "  jit=off survived:" (:survived s-off)
                          " jit=on survived:" (:survived s-on)
                          " both:" (:survived summary))
                 (println "  sampled-out (stress depth, logged):"
                          (pr-str (:sampled-out summary)))
                 (print-summary dir edn summary)
                 0)))
           ;; Single mode.
           (let [raw (str rpt-dir "/" dir-tag ".ide.txt")
                 ;; The gc oracle's repros live in the mino-tests repo but
                 ;; run with the mino submodule as CWD, so pass an ABSOLUTE
                 ;; repro dir. MINO_GC_VERIFY / nursery / cap are set by the
                 ;; oracle on each child, never on the whole suite
                 ;; (guardrail #1).
                 env-pairs (if (= dir "src/gc")
                             [["MINO_GC_REPRO_DIR"
                               (str root "/tests/mutation/gc_repros")]]
                             [])
                 s (run-mull-once runner binp mino-root oracle env-pairs raw)
                 scope (get lane-instrument-tus dir)
                 summary (when s
                           (cond-> (assoc s :instrument-scope
                                          (vec (sort scope)))
                             (= dir "src/gc")
                             (assoc :sampled-out-tus
                                    (vec (sort (remove scope
                                                       gc-full-candidate-tus))))))]
             (if (nil? summary)
               (do (println "  ERROR: could not parse mull-runner output; see" raw)
                   1)
               (do
                 (spit edn (with-out-str (println (pr-str summary))))
                 (print-summary dir edn summary)
                 0)))))))))

;; ---- Mutation lane: aggregate over the ranked dirs ----------------
;;
;; `mutation-all` runs the ranked critical dirs in the plan's blast-
;; radius order and prints one aggregate score table. Each dir gets a
;; fresh mino_mut (the build reuses one output path), so the dirs run
;; strictly sequentially: build dir, score dir, next dir. gc's kill-
;; signal is a set of bounded out-of-process verify repros (Phase 3);
;; the repros run under MINO_GC_VERIFY=1 with a tight nursery and a hard
;; per-repro wall-clock cap, NEVER the whole suite (guardrail #1).

;; Blast-radius rank from the plan's "Highest-benefit target areas".
;; :ready? gates whether the dir has a registered oracle yet.
(def ^:private mutation-ranked-dirs
  [{:dir "src/gc"      :label "gc"     :ready? true}
   {:dir "src/read"    :label "read"   :ready? true}
   {:dir "src/eval/bc" :label "vm/bc"  :ready? true}
   {:dir "src/values"  :label "values" :ready? true}])

(defn mutation-all
  "Run the ranked critical dirs end to end (build then score each) and
   print an aggregate score summary. gc runs its bounded verify-repro
   oracle under guardrail #1 (never the whole suite). Returns 0 when
   every ready dir completed; a low score is a finding, not a failure."
  []
  (mutation-doctor)
  (let [results
        (mapv
         (fn [{:keys [dir label ready?]}]
           (if-not ready?
             (do (println)
                 (println "=== " label "(" dir ") : PENDING (Phase 3 oracle) ===")
                 {:dir dir :label label :status :pending})
             (do
               (println)
               (println "=== " label "(" dir ") : build ===")
               (let [brc (mutation-build dir)]
                 (if-not (zero? brc)
                   {:dir dir :label label :status :build-failed}
                   (do
                     (println "=== " label "(" dir ") : score ===")
                     (let [rc (mutation dir)
                           edn (str (repo-root) "/tests/mutation/reports/"
                                    (str/replace dir "/" "_") ".edn")
                           summary (try (read-string (slurp edn))
                                        (catch e nil))]
                       {:dir dir :label label :status :done
                        :rc rc :summary summary})))))))
         mutation-ranked-dirs)]
    (println)
    (println "=== mutation-all aggregate (ranked) ===")
    (doseq [{:keys [label status summary]} results]
      (case status
        :pending
        (println (format "  %-8s PENDING (Phase 3)" label))
        :build-failed
        (println (format "  %-8s BUILD FAILED" label))
        :done
        (if summary
          (println (format "  %-8s %d killed / %d survived / %s total  %s%s"
                           label
                           (or (:killed summary) 0)
                           (or (:survived summary) 0)
                           (str (:total summary))
                           (if (:total summary)
                             (format "%.1f%%" (* 100.0 (:score summary)))
                             "n/a")
                           (if (:jit-parity summary) "  (jit parity)" "")))
          (println (format "  %-8s (no report)" label)))))
    (let [failed (filter #(= :build-failed (:status %)) results)]
      (if (seq failed) 1 0))))

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

(defn parse-coverage-report
  "Parse the text output of `llvm-cov report` into a summary map.
   Returns nil if the TOTAL line is not found.

   The llvm-cov report TOTAL line has this shape:
     TOTAL  <regions> <missed-regions> <cover%>  <functions> <missed-fn> <exec%>  <lines> <missed-lines> <cover%>"
  [text]
  (let [total-line (->> (str/split-lines text)
                        (filter #(str/starts-with? (str/trim %) "TOTAL"))
                        first)]
    (when total-line
      (let [fields (vec (filter #(not (= % "")) 
                                (str/split (str/trim total-line) #"\s+")))
            parse-num (fn [s] (long (read-string s)))
            parse-pct (fn [s] (/ (parse-num (str/replace s "%" "")) 100.0))]
        (when (>= (count fields) 10)
          (let [regions-total    (parse-num (nth fields 1))
                regions-missed   (parse-num (nth fields 2))
                functions-total  (parse-num (nth fields 4))
                functions-missed (parse-num (nth fields 5))
                lines-total      (parse-num (nth fields 7))
                lines-missed     (parse-num (nth fields 8))]
            {:lines-covered      (- lines-total lines-missed)
             :lines-total        lines-total
             :lines-percent      (parse-pct (nth fields 9))
             :regions-covered    (- regions-total regions-missed)
             :regions-total      regions-total
             :regions-percent    (parse-pct (nth fields 3))
             :functions-covered  (- functions-total functions-missed)
             :functions-total    functions-total
             :functions-percent  (parse-pct (nth fields 6))}))))))

(defn extract-coverage-summary
  "Run llvm-cov report against the latest profdata and write the
    summary EDN. Resolves llvm-cov the same way cov-run does (xcrun
    first, PATH second) so it works on macOS hosts too."
  []
  (let [root (repo-root)
        cov  (detect-tool "llvm-cov")
        bin  (str root "/tests/adv/build/adv_test_cov")
        prof (str root "/tests/adv/coverage/mino.profdata")]
    (if (nil? cov)
      (println "  (coverage summary skipped: llvm-cov not found)")
      (if (and (file-exists? bin)
               (file-exists? prof))
        (try
          (println "  extracting coverage summary...")
          (let [report-text (sh! cov "report" bin
                                 (str "-instr-profile=" prof)
                                 "-use-color=false")
                summary     (parse-coverage-report report-text)]
            (if summary
              (do
                (mkdir-p "output")
                (spit "output/coverage-summary.edn" (pr-str summary))
                (println "coverage summary: output/coverage-summary.edn")
                (println (pr-str summary)))
              (println "  (coverage summary skipped: could not parse llvm-cov report)")))
          (catch e
            (println "  (coverage summary extraction skipped:" (str e) ")")))
        (println "  (coverage summary skipped: missing prerequisites)")))))

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
  "Run mino's tests/run.clj at a tight nursery. Catches bugs whose
   appearance depends on the GC's major-phase × test-position
   alignment -- the precise alignment that hides the bug at the
   default nursery might surface it at 64 KiB (perf-shape and fuzz
   files excluded; see the env note below)."
  []
  ;; The 64 KiB extreme carries nearly all of the alignment variety;
  ;; the larger sizes sit close to the default nursery the ordinary
  ;; suite lanes already exercise. As the suite grew a single 64 KiB
  ;; pass now nearly fills the lane's step budget on the slowest
  ;; runners, so run only the extreme.
  (let [sizes [65536]
        results
        ;; pipefail so a non-zero exit from mino propagates through
        ;; the tail truncation (otherwise the tail's exit masks any
        ;; test-suite abort). The perf-shape files are excluded: their
        ;; budgets assume a default nursery, and under a 64 KiB one the
        ;; allocation-heavy documents crawl through thousands of
        ;; collections -- measuring the harness, not correctness, while
        ;; eating the fuzz lane's runtime budget.
        ;;
        ;; MINO_THREAD_LIMIT grants ample worker headroom: under a
        ;; 64 KiB nursery on a low-core runner, GC-driven reclamation
        ;; of finished thread slots lags, so a concurrency test can
        ;; briefly hold more live workers than the cpu-count default
        ;; and trip MTH001. That is a thread-pool-sizing artifact of
        ;; the extreme nursery, orthogonal to the GC-alignment
        ;; correctness this lane exists to check; the headroom keeps it
        ;; from masking a real alignment failure. tail -40 keeps mino's
        ;; end-of-run "Failures:" block so a real failure is legible.
        ;;
        ;; image_test and tar_facade_test are excluded for the same
        ;; harness-not-correctness reason as the perf files: image_test
        ;; spawns child ./mino processes and asserts their stdout, so it
        ;; measures a child round-trip's behaviour under the runner's
        ;; stress (a rare tight-nursery image corruption is tracked in
        ;; mino/.local/BUGS.md, not an in-process alignment signal);
        ;; tar_facade_test asserts two file mtimes floor to the same
        ;; second, which straddles a boundary under the ~10x-slower
        ;; nursery -- wall-clock timing, not GC alignment.
        (mapv (fn [sz]
                (println "  gc-fuzz nursery=" sz "bytes")
                (let [r (run-in-mino [["MINO_GC_NURSERY_BYTES" sz]
                                       ["MINO_THREAD_LIMIT" "16"]
                                       ["MINO_TEST_EXCLUDE"
                                        "json_perf_test,regex_perf_test,string_perf_test,reduce_perf_test,csv_perf_test,toml_perf_test,yaml_perf_test,html_perf_test,xml_perf_test,html_fuzz_test,xml_fuzz_test,compress_perf_test,zip_perf_test,zip_fuzz_test,image_test,tar_facade_test"]]
                                      "set -o pipefail; ./mino tests/run.clj 2>&1 | tail -40")]
                  (println "    " (clojure.string/trim (or (:out r) "")))
                  {:nursery sz :exit (:exit r) :ok (zero? (:exit r))}))
              sizes)
        failed (filterv (fn [r] (not (:ok r))) results)]
    (if (empty? failed)
      (do (println "  gc-fuzz: OK across" (count sizes) "nursery sizes") 0)
      ;; run-task! ignores a returned exit code, so signal failure by
      ;; throwing -- otherwise a real nursery-sensitive regression rides
      ;; through the CI step as a false green.
      (throw (ex-info (str "gc-fuzz failed at " (pr-str failed))
                      {:failed failed})))))

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
   each surviving tuple through mino to record fresh ground truth.
   The result overwrites tests/adv/fixtures/clojuredocs-tuples.edn.

   Dev-host only. Uses mino's own clojure.data.json for parsing and
   ./mino/mino as the ground-truth evaluator."
  []
  (let [script (str (repo-root) "/tests/adv/clojuredocs_build.clj")]
    (println "  exec: ./mino/mino" script)
    (try
      (println (sh! "./mino/mino" script))
      0
      (catch e
        (println "  clojuredocs-refresh failed:" (str e))
        1))))

;; ---- JVM core ground-truth refresh ----

(defn jvm-core-refresh
  "Re-run the jvm-core probe corpus through real JVM Clojure and
   overwrite tests/adv/fixtures/jvm-core-jvm.edn. Dev-host only:
   needs the clojure CLI on PATH; CI uses the committed fixture."
  []
  (let [script (str (repo-root) "/tests/adv/jvm_core_capture.clj")]
    (println "  exec: clojure -M" script)
    (try
      (let [r (sh "clojure" "-M" script)]
        (println (:out r))
        (if (zero? (:exit r)) 0 1))
      (catch e
        (println "  jvm-core-refresh failed:" (str e))
        1))))

;; ---- Conformance edge corpus ----

(defn conformance-edge-refresh
  "Rebuild the conformance edge corpus from the authored forms file
   (bb ground truth), then re-capture JVM Clojure ground truth for it.
   Dev-host only: needs bb and the clojure CLI on PATH; CI and the
   pre-land lane use the committed fixtures."
  []
  (let [capture (str (repo-root) "/tests/adv/conformance_edge_capture.clj")
        jvm-cap (str (repo-root) "/tests/adv/clojuredocs_jvm_capture.clj")
        tuples  "tests/adv/fixtures/conformance-edge-tuples.edn"
        jvm-out "tests/adv/fixtures/conformance-edge-jvm-tuples.edn"]
    (println "  exec: bb" capture)
    (try
      (let [r1 (sh "bb" capture)]
        (println (:out r1))
        (if (zero? (:exit r1))
          (do (println "  exec: clojure -M" jvm-cap tuples jvm-out)
              (let [r2 (sh "clojure" "-M" jvm-cap tuples jvm-out)]
                (println (:out r2))
                (if (zero? (:exit r2)) 0 1)))
          1))
      (catch e
        (println "  conformance-edge-refresh failed:" (str e))
        1))))

(defn conformance-edge-teeth
  "Self-test of the edge differ: plants a wrong expectation in a temp
   corpus and asserts the probe flags it (plus pending and allowlist
   semantics). Run after any change to the differ or capture scripts."
  []
  (let [script (str (repo-root) "/tests/adv/conformance_edge_teeth.clj")]
    (println "  exec: bb" script)
    (try
      (let [r (sh "bb" script)]
        (println (:out r))
        (when (seq (:err r)) (println (:err r)))
        (if (zero? (:exit r)) 0 1))
      (catch e
        (println "  conformance-edge-teeth failed:" (str e))
        1))))
