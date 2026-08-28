(require "tests/test")

;; Arity enforcement for defns that run on the tree-walk tier. The bc
;; tier rejects wrong arities at dispatch (vm.c's clause match, pinned
;; by mino's tests/arity_strict_test.clj), and multi-arity dispatch
;; rejects them too (fn.c's dispatch_multi_arity). The fns pinned here
;; decline bc compilation (cond / case bodies, a map-destructured
;; rest param), so every wrong-arity call lands in the tree-walk
;; apply path, whose bind layer used to misreport fixed-arity misses
;; as syntax errors and nil-fill variadic sub-minimum calls. Both
;; must reject exactly like the other tiers: kind :eval/arity, code
;; MAR002, and a message naming the callee.
;;
;; The image defns are probed with literal calls so the diagnostic's
;; name attribution (head of the in-progress form) is the callee
;; itself. One apply-routed call pins the kind and code only: apply
;; attribution is a separate wart shared by every tier.

(defn arity-error
  "Eval form and return the thrown diagnostic, or ::accepted when the
  call returns without throwing."
  [form]
  (try (eval form)
       ::accepted
       (catch Throwable e e)))

(defn assert-arity-rejected
  "Wrong-arity call on name-str must throw the structured arity
  diagnostic: kind, code, and the callee named in the message."
  [form name-str]
  (let [e (arity-error form)]
    (is (map? e) (str name-str ": call was accepted"))
    (when (map? e)
      (is (= :eval/arity (:mino/kind e))
          (str name-str ": kind " (pr-str (:mino/kind e))))
      (is (= "MAR002" (:mino/code e))
          (str name-str ": code " (pr-str (:mino/code e))))
      (is (and (string? (:mino/message e))
               (clojure.string/includes? (:mino/message e) name-str))
          (str name-str ": message " (pr-str (:mino/message e)))))))

(deftest image-single-arity-defns-reject-with-arity-kind
  ;; clojure.core defns whose bodies decline bc compilation; each
  ;; wrong arity must reject as :eval/arity MAR002 naming the fn.
  (assert-arity-rejected '(abs) "abs")
  (assert-arity-rejected '(abs 1 2) "abs")
  (assert-arity-rejected '(vec) "vec")
  (assert-arity-rejected '(vec 1 2) "vec")
  (assert-arity-rejected '(rand-nth) "rand-nth")
  (assert-arity-rejected '(rand-nth [1] 2) "rand-nth")
  (assert-arity-rejected '(cycle) "cycle")
  (assert-arity-rejected '(cycle 1 2) "cycle")
  (assert-arity-rejected '(num) "num")
  (assert-arity-rejected '(num 1 2) "num")
  (assert-arity-rejected '(nthnext) "nthnext")
  (assert-arity-rejected '(nthnext [1]) "nthnext")
  (assert-arity-rejected '(nthnext [1] 2 3) "nthnext")
  (assert-arity-rejected '(seq-to-map-for-destructuring) "seq-to-map-for-destructuring")
  (assert-arity-rejected '(seq-to-map-for-destructuring 1 2) "seq-to-map-for-destructuring")
  (assert-arity-rejected '(walk) "walk")
  (assert-arity-rejected '(walk 1) "walk")
  (assert-arity-rejected '(walk 1 2 3 4) "walk")
  (assert-arity-rejected '(re-seq) "re-seq")
  (assert-arity-rejected '(re-seq #"a" "a" 3) "re-seq")
  (assert-arity-rejected '(tagged-literal?) "tagged-literal?")
  (assert-arity-rejected '(tagged-literal? 1 2) "tagged-literal?")
  (assert-arity-rejected '(reader-conditional?) "reader-conditional?")
  (assert-arity-rejected '(reader-conditional? 1 2) "reader-conditional?")
  (assert-arity-rejected '(parse-boolean) "parse-boolean")
  (assert-arity-rejected '(parse-boolean "a" 2) "parse-boolean")
  (assert-arity-rejected '(ex-cause) "ex-cause")
  (assert-arity-rejected '(ex-cause (ex-info "x" {}) 2) "ex-cause"))

(deftest image-string-lib-defns-reject-with-arity-kind
  (assert-arity-rejected '(clojure.string/replace) "clojure.string/replace")
  (assert-arity-rejected '(clojure.string/replace "a" #"a" "b" 4) "clojure.string/replace")
  (assert-arity-rejected '(clojure.string/split-lines) "clojure.string/split-lines")
  (assert-arity-rejected '(clojure.string/split-lines "a" 2) "clojure.string/split-lines"))

(deftest image-variadic-minimum-rejects-at-dispatch
  ;; iteration declares [step & {...}]: minimum 1. The 0-arity call
  ;; used to nil-fill step and return an unrealized lazy seq whose
  ;; realization crashed inside the body at <core>:594 instead of
  ;; rejecting the call.
  (assert-arity-rejected '(iteration) "iteration")
  ;; The reject must fire at the call, before any lazy realization:
  ;; realizing the rejected call's would-be result may not happen.
  (let [e (arity-error '(iteration))]
    (is (map? e))))

(deftest already-correct-image-defns-stay-arity-strict
  ;; These were fixed before this suite landed (multi-arity dispatch
  ;; or bc-compiled bodies); pin them so no tier change regresses.
  (assert-arity-rejected '(update) "update")
  (assert-arity-rejected '(update {}) "update")
  (assert-arity-rejected '(update {} :k) "update")
  (assert-arity-rejected '(update-in) "update-in")
  (assert-arity-rejected '(update-in {}) "update-in")
  (assert-arity-rejected '(update-in {} []) "update-in")
  (assert-arity-rejected '(trampoline) "trampoline")
  (assert-arity-rejected '(protocol-dispatch) "protocol-dispatch")
  (assert-arity-rejected '(protocol-dispatch :s) "protocol-dispatch")
  (assert-arity-rejected '(extend) "extend"))

(deftest tree-walk-tier-matches-bc-tier-arity-diagnostic
  ;; The defect is tier-shaped, not image-shaped: any fn that
  ;; declines bc compilation runs the tree-walk apply path. A
  ;; cond-bodied runtime defn and a map-rest variadic must reject
  ;; identically to a bc-compiled defn.
  (eval '(do
           (defn arity-cond-fn [x] (cond :else x))
           (defn arity-case-fn [x] (case x 1 :one :other))
           (defn arity-map-rest-fn [step & {:keys [k]}] [step k])
           nil))
  (assert-arity-rejected '(arity-cond-fn) "arity-cond-fn")
  (assert-arity-rejected '(arity-cond-fn 1 2) "arity-cond-fn")
  (assert-arity-rejected '(arity-case-fn) "arity-case-fn")
  (assert-arity-rejected '(arity-case-fn 1 2) "arity-case-fn")
  (assert-arity-rejected '(arity-map-rest-fn) "arity-map-rest-fn")
  ;; Declared arities still work on the same fns: the guard rejects
  ;; only the undeclared counts.
  (is (= 1 (eval '(arity-cond-fn 1))))
  (is (= :other (eval '(arity-case-fn 9))))
  (is (= [1 nil] (eval '(arity-map-rest-fn 1))))
  (is (= [1 2] (eval '(arity-map-rest-fn 1 :k 2))))
  ;; A bc-compiled defn keeps rejecting with the same class (the
  ;; reference behavior the tree-walk tier must match).
  (eval '(defn arity-bc-fn [x y] [x y]))
  (assert-arity-rejected '(arity-bc-fn 1) "arity-bc-fn")
  (assert-arity-rejected '(arity-bc-fn 1 2 3) "arity-bc-fn"))

(deftest apply-routed-wrong-arity-still-arity-kind
  ;; The name attribution through apply names the head of the
  ;; in-progress form (a known wart on every tier); the kind and code
  ;; must still classify as arity on the tree-walk tier.
  (let [e (arity-error '(apply vec []))]
    (is (map? e))
    (when (map? e)
      (is (= :eval/arity (:mino/kind e)))
      (is (= "MAR002" (:mino/code e))))))

(deftest vec-one-arg-sentinel-is-a-value-error
  ;; Adjudication pin: (vec :s) accepts the declared arity 1 and the
  ;; body rejects the non-seqable value with a user-class throw, per
  ;; vec's docstring. The arity axis is satisfied; this documents the
  ;; wart so a future change to vec's value diagnostics is visible.
  (let [e (arity-error '(vec :arglists-gate-sentinel))]
    (is (map? e))
    (when (map? e)
      (is (= :user (:mino/kind e)))
      (is (= "MUS001" (:mino/code e)))
      (is (clojure.string/includes? (:mino/message e) "vec")))))

(run-tests-and-exit)
