;; T7 -- per-form internal-buffer caps
;;
;; Anchor: GC_SAVE_MAX=64 bug where exceeding the pin stack inside one
;; eval frame triggered an abort/assert in sanitizer builds. v0.252.2
;; gates the assert on sanitizer builds; the underlying cap should
;; cause a classified diagnostic, not an abort.

(load-file "tests/adv/edge_helpers.clj")
(load-file "tests/adv/invariants.clj")

(defn- probe-many-pinned-values []
  ;; A form that creates many transient values within a single
  ;; evaluation context. Older GC_SAVE_MAX-bound code would saturate
  ;; the pin stack; the runtime should now either grow the stack or
  ;; classify cleanly.
  (let [e (try
            ;; Build a deeply chained vec of cons calls; each cons
            ;; pins its head value while the tail evaluates.
            (let [v (loop [n 0 acc '()]
                      (if (>= n 200) acc
                          (recur (inc n) (cons n acc))))]
              (count v))
            (catch e e))
        ok (or (integer? e) (some? (try (ex-data e) (catch _ nil))))]
    (emit-verdict "T7.large-cons-chain-survives"
                  (if (integer? e) "pass"
                      (if ok "pass" "fail"))
                  :result (str e))))

(defn- probe-deep-let-chain []
  ;; Many sibling lets that each bind a temporary. The eval frame
  ;; must accommodate the bindings without hitting an abort.
  (let [src "(let [a 1 b 2 c 3 d 4 e 5 f 6 g 7 h 8 i 9 j 10
                   k 11 l 12 m 13 n 14 o 15 p 16 q 17 r 18 s 19 t 20
                   u 21 v 22 w 23 x 24 y 25 z 26]
                  (+ a b c d e f g h i j k l m n o p q r s t u v w x y z))"
        result (try (eval (read-string src))
                    (catch e (str e)))]
    (emit-verdict "T7.deep-let-chain"
                  (if (= result 351) "pass" "fail")
                  :result (str result))))

(probe-many-pinned-values)
(probe-deep-let-chain)
