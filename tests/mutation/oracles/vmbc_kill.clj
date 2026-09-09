;; VM/JIT kill-signal for the mutation lane. Exercises the mino
;; submodule's bytecode-VM and JIT test subset (arithmetic / bits / tco /
;; control / function / arity-strict / bc_* / jit-parity) and exits
;; non-zero on any failure. Mull reads that exit code as the per-mutant
;; kill oracle: a vm.c / compile.c / jit mutant that flips an operand or
;; stack bound, an arity check, a safepoint condition, or an overflow
;; range-check trips one of these assertions and dies.
;;
;; Each test file runs in ITS OWN child process, not one shared load.
;; The bc/tco/jit files carry generic top-level names (countdown, n,
;; acc) and a single shared namespace lets one file's defs clobber
;; another's, so a combined load fails on the UNMUTATED binary -- which
;; would make every mutant look already-dead and the signal meaningless.
;; Per-file subprocesses isolate that, and are also faster (each child
;; loads only its own file). The child re-invokes the SAME mutated
;; binary (MINO_MUT_BIN, set by the `mutation` task) so the embedded
;; mutant Mull selected for this run is exercised; MINO_JIT is inherited
;; from Mull's per-mode environment, giving the jit=off / jit=on parity.
;;
;; Runs with the mino submodule as CWD (the `mutation` task cd's there)
;; so each child's own `(require "tests/test")` relative loads resolve.

(def bin (or (getenv "MINO_MUT_BIN") "./mino"))

;; Each child runs under a hard wall-clock cap. A compile.c mutant can
;; emit bytecode that loops forever in the child; without a cap that
;; child orphans and spins, starving Mull's workers and stalling the
;; whole run (observed). `timeout -k` reaps it and returns non-zero,
;; which is the correct verdict: a mutant that hangs a test is killed.
;; Resolved by absolute path -- Mull's PATH need not carry it.
(def timeout-bin
  (or (getenv "MINO_TIMEOUT_BIN")
      (first (filter file-exists?
                     ["/opt/homebrew/bin/timeout"
                      "/usr/local/bin/timeout"
                      "/opt/homebrew/bin/gtimeout"]))))
(def child-cap (or (getenv "MINO_CHILD_CAP") "15"))

(def files
  ["tests/arithmetic_test.clj"
   "tests/bits_test.clj"
   "tests/control_test.clj"
   "tests/function_test.clj"
   "tests/arity_strict_test.clj"
   "tests/bc_binding_test.clj"
   "tests/bc_bitwise_test.clj"
   "tests/bc_closure_test.clj"
   "tests/bc_destructure_test.clj"
   "tests/bc_let_fold_test.clj"
   "tests/bc_try_catch_test.clj"
   "tests/jit_parity_test.clj"])

;; SAMPLED OUT (logged, not silent): tco_test.clj and
;; bc_tail_multiarity_test.clj drive 100000-deep recursion, so on the
;; instrumented mutant binary they cost ~25s combined PER oracle
;; invocation -- multiplied over every covered mutant and both jit
;; modes that alone blows the dir's wall-clock budget by orders of
;; magnitude. They assert TCO stack-flatness at stress depth (a
;; harness/perf property); ordinary TCO correctness is still exercised
;; by function_test and control_test recursion. Kept here as a record
;; of what the score does NOT cover so the number stays honest.
(def sampled-out
  ["tests/tco_test.clj"
   "tests/bc_tail_multiarity_test.clj"])

;; Run each file as its own child of the mutant binary; OR the exit
;; codes. Any non-zero child fails the whole oracle (kills the mutant).
(def failed
  (reduce
   (fn [bad f]
     (let [expr (str "(require \"tests/test\")(load-file \"" f "\")"
                     "(run-tests-and-exit)")
           r    (if timeout-bin
                  (sh timeout-bin "-k" "2" child-cap bin "-e" expr)
                  (sh bin "-e" expr))]
       (if (zero? (:exit r)) bad (conj bad f))))
   []
   files))

(when (seq failed)
  (println "vmbc oracle: failing files" failed))

(System/exit (if (seq failed) 1 0))

;; sampled-out is referenced so the record above is not dead code;
;; the `mutation` task logs it into the report as :sampled-out.
sampled-out
