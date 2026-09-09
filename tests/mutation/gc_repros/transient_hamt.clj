;; Bounded GC verify repro: transient-HAMT assoc!/conj! churn.
;;
;; The GC mutation kill-signal is DIFFERENT from the other lanes: most
;; barrier mutants produce a wrong answer only under heap verification,
;; not a failing assertion in an ordinary test. This repro is one of the
;; bounded, out-of-process programs the `gc` oracle runs under
;; MINO_GC_VERIFY=1 with a tight MINO_GC_NURSERY_BYTES. It exits 0 on the
;; unmutated binary and ABORTS (SIGABRT / non-zero) on a binary whose
;; write barrier drops an OLD -> YOUNG edge.
;;
;; Mechanism: a transient map promoted OLD by a mid-loop minor keeps
;; receiving fresh YOUNG value vectors via assoc!/conj!. Each such store
;; is an OLD -> YOUNG edge that MUST route through gc_write_barrier into
;; the remset. Flip the remset-add trigger in barrier.c
;; (`h_new->gen == GC_GEN_OLD` -> `!=`, or negate the dirty/young guard)
;; and the next minor collector never reaches the YOUNG children;
;; gc_verify_remset_complete walks the live OLD, finds an unreported
;; YOUNG pointer, and aborts CLASS A. That is the kill.
;;
;; GUARDRAIL #1 (LOAD-BEARING): iteration counts are SMALL (thousands,
;; not millions) and the nursery is tight so a missed barrier surfaces
;; fast. NEVER raise these to "make it pass" -- verify does a full-heap
;; mark save/restore every minor, so large counts exhaust the host. If a
;; repro exceeds the oracle's per-repro wall-clock ceiling that is a
;; harness bug: shrink the counts, do not raise the ceiling.

;; Iteration count is deliberately SMALL. A missed barrier surfaces on
;; the FIRST promoted container that receives a YOUNG child bypassing the
;; remset -- empirically within tens of iterations, not thousands. The
;; count is sized so the repro finishes in a few seconds on the SLOW
;; mull-instrumented binary under verify (the instrumented tracer is
;; ~30x the shipped binary), while still forcing enough minors with
;; promotion to abort the barrier-inversion mutant. See GUARDRAIL #1.
(dotimes [i 150]
  (let [t (transient {})]
    (loop [j 0 t t]
      (if (< j 32)
        ;; Fresh YOUNG vector value each store; the transient container
        ;; is promoted OLD by a mid-loop minor.
        (recur (inc j) (assoc! t (str "k" j) [j (inc j) (dec j)]))
        (persistent! t))))
  ;; Interleave a transient-vector conj! churn so the barrier is
  ;; exercised on vector containers too, not only maps.
  (let [tv (transient [])]
    (loop [j 0 tv tv]
      (if (< j 24)
        (recur (inc j) (conj! tv [i j]))
        (persistent! tv)))))

(println "transient_hamt ok")
