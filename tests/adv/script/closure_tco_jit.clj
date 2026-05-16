;; T8 -- closure capture under TCO + JIT
;;
;; Anchor: the v0.252.3 closure capture across self-tail-call bug. Self-
;; tail-call reused the local env_child and bind_params mutated param
;; slots in place, so a closure built in iteration N silently observed
;; iteration N+1's param values once the next recur landed. Fix
;; allocates a fresh env_child per iteration unconditionally.

(load-file "tests/adv/edge_helpers.clj")
(load-file "tests/adv/invariants.clj")

(defn- probe-loop-recur-closures []
  (let [fs (loop [i 0 acc []]
             (if (>= i 5) acc
                 (recur (inc i) (conj acc (fn [] i)))))
        verdict (closure-i= fs [0 1 2 3 4])]
    (emit-verdict "T8.loop-recur-captures-per-iter"
                  (if (= verdict true) "pass" "fail")
                  :verdict (pr-str verdict))))

(defn- probe-dotimes-closures []
  (let [fs (atom [])]
    (dotimes [i 5] (swap! fs conj (fn [] i)))
    (let [verdict (closure-i= @fs [0 1 2 3 4])]
      (emit-verdict "T8.dotimes-captures-per-iter"
                    (if (= verdict true) "pass" "fail")
                    :verdict (pr-str verdict)))))

(defn- probe-self-recursion-closures []
  (let [cls (atom [])]
    (defn G [i]
      (swap! cls conj (fn [] i))
      (when (< i 4) (G (inc i))))
    (G 0)
    (let [verdict (closure-i= @cls [0 1 2 3 4])]
      (emit-verdict "T8.self-recursion-captures-per-iter"
                    (if (= verdict true) "pass" "fail")
                    :verdict (pr-str verdict)))))

(defn- probe-macro-introduced-closures []
  ;; `for` expands to (fn [] ...) implicitly; older source-level
  ;; "has-closures" probes would miss this.
  (let [fs (for [i (range 5)] (fn [] i))
        verdict (closure-i= fs [0 1 2 3 4])]
    (emit-verdict "T8.for-expanded-closures"
                  (if (= verdict true) "pass" "fail")
                  :verdict (pr-str verdict))))

(probe-loop-recur-closures)
(probe-dotimes-closures)
(probe-self-recursion-closures)
(probe-macro-introduced-closures)
