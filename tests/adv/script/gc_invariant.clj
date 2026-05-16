;; T11 -- GC live = reachable invariant
;;
;; New probe. Build a graph of values, hold roots to some, drop refs
;; to others, force a GC, then verify the held roots are still
;; correct. Drops should not corrupt live values.

(load-file "tests/adv/edge_helpers.clj")
(load-file "tests/adv/invariants.clj")

(defn- probe-live-survives-collection []
  (let [held    (vec (for [i (range 100)]
                       {:idx i :payload (vec (range i))}))
        ;; "throwaway" reachable only here, then dropped
        _       (vec (for [_ (range 1000)] (range 100)))
        _       (gc!)
        same    (vec (for [m held]
                       (= (:payload m) (vec (range (:idx m))))))
        ok      (every? true? same)]
    (emit-verdict "T11.live-survives-collection"
                  (if ok "pass" "fail")
                  :live-count (count held)
                  :all-match  ok)))

(defn- probe-mutation-then-collect []
  ;; Atom round-trip: mutate to many values, GC mid-stream, final
  ;; value preserved.
  (let [a (atom [])]
    (dotimes [i 100] (swap! a conj i))
    (gc!)
    (let [final @a
          ok    (= final (vec (range 100)))]
      (emit-verdict "T11.atom-mutation-survives-gc"
                    (if ok "pass" "fail")
                    :final-len (count final)))))

(defn- probe-deep-graph []
  ;; Build a deeply nested structure, then root it, GC, traverse it.
  (let [g (reduce (fn [acc i] {:i i :next acc})
                  {:bottom true}
                  (range 200))
        _ (gc!)
        depth (loop [n g d 0]
                (if (:bottom n) d
                    (recur (:next n) (inc d))))
        ok    (= depth 200)]
    (emit-verdict "T11.deep-graph-survives-gc"
                  (if ok "pass" "fail")
                  :depth depth)))

(probe-live-survives-collection)
(probe-mutation-then-collect)
(probe-deep-graph)
