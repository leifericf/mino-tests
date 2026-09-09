;; GC kill-signal for the mutation lane. THE HARDEST LANE, and its
;; kill-signal is DIFFERENT from the other three: a barrier/collector
;; mutant almost never trips an ordinary test assertion -- it produces a
;; silently corrupt heap that only heap verification catches. So the
;; oracle is a set of BOUNDED, OUT-OF-PROCESS verify repros: small mino
;; scripts (tests/mutation/gc_repros/*.clj) run with MINO_GC_VERIFY=1 and
;; a tight MINO_GC_NURSERY_BYTES. Each repro exits 0 on the unmutated
;; binary; on a mutant that drops an OLD -> YOUNG barrier edge, the next
;; minor's gc_verify_remset_complete finds an unreported YOUNG pointer
;; and aborts CLASS A (SIGABRT / non-zero exit). Mull reads this oracle's
;; exit code: ANY repro exiting non-zero => the mutant is KILLED.
;;
;; GUARDRAIL #1 -- ABSOLUTE, NON-NEGOTIABLE: NEVER run the full test
;; suite or any large shard under MINO_GC_VERIFY=1. Verify does a full-
;; heap mark save/restore on every minor collection; over thousands of
;; tests it exhausts memory/CPU and has crashed a host. ONLY the small
;; bounded repros run under verify, EACH under a hard per-repro
;; wall-clock cap (MINO_GC_REPRO_CAP seconds, default 20) enforced by
;; `timeout`. If a repro exceeds its cap that is a HARNESS BUG: shrink
;; the repro's iteration count, DO NOT raise the cap. The cap also
;; doubles as the kill for a mutant that livelocks the collector -- a
;; hung child is a killed mutant.
;;
;; Each repro runs in its OWN child process (like the vmbc oracle) that
;; re-invokes the SAME mutated binary (MINO_MUT_BIN, set by the
;; `mutation` task). MINO_GC_VERIFY / MINO_GC_NURSERY_BYTES are set on
;; the child so the mutant Mull selected is exercised under verify.
;;
;; Runs with the mino submodule as CWD (the `mutation` task cd's there);
;; the repros live in the mino-tests repo, so they are addressed by an
;; absolute path via MINO_GC_REPRO_DIR (set by the `mutation` task).

(def bin (or (getenv "MINO_MUT_BIN") "./mino"))

(def repro-dir
  (or (getenv "MINO_GC_REPRO_DIR")
      "../tests/mutation/gc_repros"))

;; Nursery small enough to promote containers fast (so a missed barrier
;; surfaces within tens of iterations) but NOT so tight that mino's
;; ~4000-def bootstrap thrashes the collector on the SLOW mull-
;; instrumented binary under verify. Measured on this host: at 64 KiB an
;; empty program's bootstrap-under-verify costs ~20 s on the instrumented
;; binary (thousands of minors, each a full-heap verify walk through the
;; ~30x-slower instrumented tracer); at 1 MiB it costs ~1.6 s, and the
;; barrier-inversion mutant STILL aborts the repros (promotion still
;; happens, the missed OLD->YOUNG edge still trips gc_verify). 1 MiB is
;; the calibrated point: fast baseline, intact kill-signal. This is a
;; measured harness choice, NOT a relaxation of guardrail #1 -- the
;; repros are still bounded (hundreds of iterations) and still run ONLY
;; the small out-of-process programs under verify, never the suite.
;; MINO_GC_NURSERY_BYTES overrides for a dedicated tighter window.
(def nursery (or (getenv "MINO_GC_NURSERY_BYTES") "1048576"))

;; Per-repro hard wall-clock cap (guardrail #1). Resolved by absolute
;; path -- Mull's PATH need not carry timeout.
(def timeout-bin
  (or (getenv "MINO_TIMEOUT_BIN")
      (first (filter file-exists?
                     ["/opt/homebrew/bin/timeout"
                      "/usr/local/bin/timeout"
                      "/opt/homebrew/bin/gtimeout"
                      "/usr/bin/timeout"]))))
(def repro-cap (or (getenv "MINO_GC_REPRO_CAP") "20"))

;; The bounded repros, in cheapest-first order so a mutant that any repro
;; kills is detected with the least wall-clock spent.
(def repros
  ["closure_churn.clj"
   "atom_churn.clj"
   "record_array.clj"
   "transient_hamt.clj"])

;; Run each repro as its own child under verify + tight nursery. ANY
;; non-zero child (abort under verify, or a timeout livelock) kills the
;; mutant. Short-circuit on the first kill: the score only needs the
;; boolean, and stopping early keeps the covered-mutant cost down.
(def killed-by
  (reduce
   (fn [_ r]
     (let [path (str repro-dir "/" r)
           run  (fn []
                  (if timeout-bin
                    (sh timeout-bin "-k" "2" repro-cap
                        "env" (str "MINO_GC_VERIFY=1")
                        (str "MINO_GC_NURSERY_BYTES=" nursery)
                        bin path)
                    (sh "env" "MINO_GC_VERIFY=1"
                        (str "MINO_GC_NURSERY_BYTES=" nursery)
                        bin path)))
           res  (run)]
       (if (zero? (:exit res))
         nil
         (reduced r))))
   nil
   repros))

(when killed-by
  (println "gc oracle: killed by" killed-by))

(System/exit (if killed-by 1 0))
