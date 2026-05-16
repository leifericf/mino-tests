;; gen_program.clj -- seeded random mino program generator.
;;
;; Each emitted program:
;;   * is pure (no I/O except a single trailing println)
;;   * is deterministic (no rand, time, file read, no concurrency)
;;   * terminates (loops use a bounded literal counter; no unbounded
;;     recursion)
;;   * exercises shapes the BC compile path and JIT have diverged on
;;     historically: let, loop+recur, closures, conditionals, arith
;;
;; The goal is differential testing: each program is run through
;; tree-walker / bc / bc+jit / mino-lean and the four stdouts must
;; be byte-identical. The generator therefore avoids any construct
;; whose printed output could legitimately differ across runtimes
;; (float arithmetic, lazy-seq realization timing, map iteration
;; order on non-printed values, hash-randomized seeds).
;;
;; Depends on tests/adv/gen.clj for the seeded RNG (xorshift64*).

;; --- generator parameters ---

(def ^:private gen-max-defns 3)
(def ^:private gen-max-arity 3)
(def ^:private gen-max-depth 4)
(def ^:private gen-max-let-bindings 3)
(def ^:private gen-max-loop-iters 8)
(def ^:private gen-int-lo -64)
(def ^:private gen-int-hi 64)

;; A program is generated under a CONTEXT that names previously-
;; defined fns and currently-in-scope params/locals. The context is
;; threaded through every gen-* call via the atom *gen-ctx*. We use
;; an atom (not a dynamic var) because we mutate it on let / loop
;; entry and reset on exit; dynamic binding would be cleaner but
;; the generator is single-threaded by construction.

(def ^:private *gen-ctx*
  (atom {:defns []           ;; vec of [sym arity]
         :locals []          ;; vec of symbols currently in scope
         :next-local 0}))    ;; counter for fresh local names

(defn- ctx-defns []  (:defns @*gen-ctx*))
(defn- ctx-locals [] (:locals @*gen-ctx*))

(defn- push-locals [syms]
  (swap! *gen-ctx* update :locals into syms))

(defn- pop-locals [n]
  (swap! *gen-ctx* update :locals
         (fn [xs] (vec (drop-last n xs)))))

(defn- fresh-local []
  (let [n (:next-local @*gen-ctx*)
        sym (symbol (str "v" n))]
    (swap! *gen-ctx* update :next-local inc)
    sym))

(defn- add-defn [sym arity]
  (swap! *gen-ctx* update :defns conj [sym arity]))

(defn- reset-ctx! []
  (reset! *gen-ctx* {:defns [] :locals [] :next-local 0}))

;; --- low-level shape builders ---

(defn- gen-int-near-boundary []
  ;; Mix small ints with bounded boundary values. We avoid INT_MIN /
  ;; INT_MAX because mod / quot / negation produce defined but
  ;; runtime-divergent results there; bitwise probes pin those
  ;; separately in Phase 4.
  (case (mod (abs (next-u32)) 6)
    0 0
    1 1
    2 -1
    3 (gen-int gen-int-lo gen-int-hi)
    4 (gen-int gen-int-lo gen-int-hi)
    5 (gen-int -10 10)))

(defn- pick-local []
  (let [locals (ctx-locals)]
    (when (seq locals)
      (nth locals (mod (abs (next-u32)) (count locals))))))

(defn- pick-defn []
  (let [defns (ctx-defns)]
    (when (seq defns)
      (nth defns (mod (abs (next-u32)) (count defns))))))

;; --- expression generation ---

(declare gen-body-expr)

(defn- gen-leaf
  "Depth-0 expression: a literal or an in-scope reference."
  []
  (let [locals (ctx-locals)]
    (if (and (seq locals)
             (zero? (mod (abs (next-u32)) 2)))
      (pick-local)
      (gen-int-near-boundary))))

(defn- gen-arith-expr [depth]
  ;; Two-arity arithmetic. Division-by-zero is guarded.
  (let [op (case (mod (abs (next-u32)) 7)
             0 '+
             1 '-
             2 '*
             3 'quot
             4 'rem
             5 'mod
             6 'bit-and)
        a (gen-body-expr (dec depth))
        b (gen-body-expr (dec depth))]
    (cond
      ;; quot / rem / mod guard divisor
      (#{'quot 'rem 'mod} op)
      (list 'let ['d b]
            (list 'if (list 'zero? 'd)
                  a
                  (list op a 'd)))
      :else
      (list op a b))))

(defn- gen-if-expr [depth]
  (let [test (case (mod (abs (next-u32)) 4)
               0 (list 'zero? (gen-body-expr (dec depth)))
               1 (list 'pos?  (gen-body-expr (dec depth)))
               2 (list 'neg?  (gen-body-expr (dec depth)))
               3 (list '< (gen-body-expr (dec depth)) (gen-body-expr (dec depth))))
        then (gen-body-expr (dec depth))
        else (gen-body-expr (dec depth))]
    (list 'if test then else)))

(defn- gen-let-form [depth]
  (let [n (inc (mod (abs (next-u32)) gen-max-let-bindings))
        ;; collect fresh syms then walk; intermediate values may
        ;; depend on previously-bound locals in the same let.
        binds (loop [i 0, syms [], rhs []]
                (if (= i n)
                  [syms rhs]
                  (let [s (fresh-local)
                        r (gen-body-expr (dec depth))]
                    ;; push so the next rhs can reference this sym
                    (push-locals [s])
                    (recur (inc i) (conj syms s) (conj rhs r)))))
        [syms rhs] binds
        body (gen-body-expr (dec depth))
        _    (pop-locals n)
        bindings-vec (vec (mapcat list syms rhs))]
    (list 'let bindings-vec body)))

(defn- gen-loop-form [depth]
  ;; (loop [i 0 acc init] (if (>= i N) acc (recur (inc i) (op acc ...))))
  ;; bounded counter ensures convergence on every mode
  (let [iters (inc (mod (abs (next-u32)) gen-max-loop-iters))
        i-sym (fresh-local)
        a-sym (fresh-local)
        _     (push-locals [i-sym a-sym])
        init  (gen-int-near-boundary)
        step  (gen-body-expr (dec depth))
        _     (pop-locals 2)
        body  (list 'if (list '>= i-sym iters)
                    a-sym
                    (list 'recur (list 'inc i-sym)
                          (list '+ a-sym step)))]
    (list 'loop [i-sym 0 a-sym init] body)))

(defn- gen-call-expr [depth]
  ;; Call one of the previously-defined defns with random args.
  (let [d (pick-defn)]
    (if d
      (let [[sym arity] d
            args (vec (repeatedly arity #(gen-body-expr (dec depth))))]
        (cons sym args))
      (gen-leaf))))

(defn- gen-bool-expr [depth]
  (case (mod (abs (next-u32)) 4)
    0 (list '< (gen-body-expr (dec depth)) (gen-body-expr (dec depth)))
    1 (list '= (gen-body-expr (dec depth)) (gen-body-expr (dec depth)))
    2 (list 'zero? (gen-body-expr (dec depth)))
    3 (list 'pos?  (gen-body-expr (dec depth)))))

(defn- gen-when-expr [depth]
  ;; (when test body) returns body or nil; we wrap with (or ... 0) so
  ;; the result remains numeric and the program prints deterministically.
  (list 'or
        (list 'when (gen-bool-expr (dec depth))
              (gen-body-expr (dec depth)))
        0))

(defn gen-body-expr
  "Return one expression. At depth 0 falls back to gen-leaf so the
   tree is bounded. Choice of constructor is uniform over what fits
   in the remaining depth budget."
  [depth]
  (if (<= depth 0)
    (gen-leaf)
    (case (mod (abs (next-u32)) 7)
      0 (gen-arith-expr depth)
      1 (gen-if-expr depth)
      2 (gen-let-form depth)
      3 (gen-loop-form depth)
      4 (gen-call-expr depth)
      5 (gen-when-expr depth)
      6 (gen-leaf))))

;; --- top-level program ---

(defn- gen-fn-name [i]
  (symbol (str "f" i)))

(defn- gen-defn [i]
  (let [name  (gen-fn-name i)
        arity (inc (mod (abs (next-u32)) gen-max-arity))
        params (vec (map #(symbol (str "p" %)) (range arity)))
        _ (push-locals params)
        body (gen-body-expr gen-max-depth)
        _ (pop-locals arity)
        _ (add-defn name arity)]
    (list 'defn name params body)))

(defn gen-program
  "Emit one random valid mino program as a source string.

   The program defines 1..MAX-DEFNS defns followed by a final
   (println EXPR) so the four-mode quad has a deterministic stdout
   to compare.

   Caller must (seed! n) before invoking; identical seeds yield
   identical programs."
  []
  (reset-ctx!)
  (let [n-defns (inc (mod (abs (next-u32)) gen-max-defns))
        defns   (vec (for [i (range n-defns)] (gen-defn i)))
        final   (gen-body-expr gen-max-depth)
        forms   (conj defns (list 'println final))]
    (apply str (interpose "\n" (map pr-str forms)))))

(defn gen-programs
  "Return a vector of N distinct seeded programs starting from the
   current RNG state."
  [n]
  (vec (repeatedly n gen-program)))
