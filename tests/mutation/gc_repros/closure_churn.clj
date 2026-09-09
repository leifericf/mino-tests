;; Bounded GC verify repro: recompiled-closure churn.
;;
;; One of the bounded programs the `gc` oracle runs under
;; MINO_GC_VERIFY=1 (see transient_hamt.clj for the full rationale and
;; GUARDRAIL #1). Repeatedly eval'ing fresh fn forms allocates new
;; closures capturing fresh YOUNG environments, then stores them into an
;; OLD accumulator vector. A recompiled closure's captured-env slots and
;; the accumulator's element slots are OLD -> YOUNG edges once promoted;
;; a dropped barrier detaches the captured environment from the remset.
;;
;; Iteration count is deliberately low (eval + compile per iteration is
;; the costly part); it still drives enough minors under the tight
;; nursery to promote the accumulator and surface a missed barrier.

(let [acc (atom [])]
  (dotimes [i 800]
    (let [f (eval (list 'fn ['x] (list '+ 'x i [i (inc i)])))]
      ;; Store the fresh closure into the OLD accumulator; keep the
      ;; accumulator bounded so it stays live but does not grow forever.
      (swap! acc (fn [v] (if (> (count v) 32) [f] (conj v f))))))
  (println "closure_churn ok" (count @acc)))
