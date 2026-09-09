;; Bounded GC verify repro: record + array/vector allocation churn.
;;
;; One of the bounded programs the `gc` oracle runs under
;; MINO_GC_VERIFY=1 (see transient_hamt.clj for the full rationale and
;; GUARDRAIL #1). Records carry field slots and vectors carry element
;; slots; assoc onto a promoted record and into/mapv over ranges churn
;; fresh YOUNG children into containers that a mid-loop minor promotes
;; OLD. The valarr / record slot stores route through the barrier
;; (gc_valarr_set and the record assoc path); a dropped edge here leaves
;; a field or element pointing at a YOUNG object outside the remset.

(defrecord Pt [x y z])

(dotimes [i 3000]
  (let [p  (->Pt i (inc i) (dec i))
        v  (into [] (map (fn [k] (->Pt k k k)) (range 8)))
        p2 (assoc p :x (get-in v [3 :y]))
        v2 (mapv (fn [q] (assoc q :z i)) v)]
    ;; Touch the promoted structures so they stay live across the minor.
    (+ (:x p2) (count v2))))

(println "record_array ok")
