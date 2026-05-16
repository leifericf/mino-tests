;; T10 -- memory pressure x JIT compile interaction
;;
;; New probe. JIT compiles hot functions during runtime; if GC runs
;; while compile is in progress, or if the JIT's region allocator
;; competes with the mutator's nursery, we want to make sure the
;; program still terminates with the right result.

(load-file "tests/adv/edge_helpers.clj")
(load-file "tests/adv/invariants.clj")

(defn- probe-hot-fn-survives-gc-pressure []
  ;; A tight loop that allocates lots of intermediate vectors should
  ;; (a) get JIT-compiled if the threshold is low enough, and (b) keep
  ;; returning the same answer regardless of when GC fires.
  (defn churn [n]
    (loop [i 0 acc 0]
      (if (>= i n) acc
          (recur (inc i) (+ acc (count (vec (range 10))))))))
  (let [r1 (churn 1000)
        ;; trigger minor GC, then re-run
        _  (gc!) ;; mino has gc! that requests a collection
        r2 (churn 1000)
        ok (= r1 r2 10000)]
    (emit-verdict "T10.hot-fn-stable-under-gc"
                  (if ok "pass" "fail")
                  :r1 r1 :r2 r2)))

(defn- probe-many-hot-paths []
  ;; Several distinct hot functions; each compiles independently.
  ;; Their results should be unaffected.
  (defn sum-evens [n]
    (loop [i 0 s 0]
      (if (>= i n) s
          (recur (inc i) (if (even? i) (+ s i) s)))))
  (defn product [xs]
    (reduce * 1 xs))
  (defn distinct-count [xs]
    (count (distinct xs)))
  (let [a (sum-evens 1000)
        b (product (range 1 11))
        c (distinct-count (cycle-take 100 [1 2 3 4 5]))
        ok (and (= a 249500) (= b 3628800) (= c 5))]
    (emit-verdict "T10.many-hot-paths"
                  (if ok "pass" "fail")
                  :a a :b b :c c)))

(defn cycle-take [n xs]
  (take n (cycle xs)))

(probe-hot-fn-survives-gc-pressure)
(probe-many-hot-paths)
