;; Bounded GC verify repro: atom churn.
;;
;; One of the bounded programs the `gc` oracle runs under
;; MINO_GC_VERIFY=1 (see transient_hamt.clj for the full rationale and
;; GUARDRAIL #1). An atom is a mutable MINO_ATOM whose `val` slot is
;; stored through gc_write_barrier on every swap!/reset!. Once the atom
;; itself is promoted OLD, each swap! that installs a fresh YOUNG map or
;; vector is an OLD -> YOUNG edge the remset must record.
;;
;; A barrier that drops the atom-slot edge leaves the swapped-in YOUNG
;; value unreachable from the next minor's remset walk; verify aborts
;; CLASS A. Confirmed to abort the `h_new->gen == GC_GEN_OLD` -> `!=`
;; barrier inversion mutant.

(let [a (atom {})
      b (atom [])]
  (dotimes [i 3000]
    ;; Fresh YOUNG value into the OLD atom slot each iteration.
    (swap! a assoc (str "k" (mod i 64)) [i (inc i) (dec i)])
    (swap! b (fn [v] (if (> (count v) 48) [i] (conj v [i i])))))
  (println "atom_churn ok" (count @a) (count @b)))
