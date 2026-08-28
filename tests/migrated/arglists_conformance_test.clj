(require "tests/test")
(require '[clojure.repl :refer [doc]])
(require '[clojure.walk] '[clojure.datafy] '[clojure.core.protocols])

;; The permanent arity-conformance gate over :arglists metadata (ADR
;; 34, decision 6). Arglists can never claim an arity the callee
;; rejects, and every undeclared arity 0..9 must stay rejected except
;; the documented lax set. The probe calls each var with sentinel
;; keywords and reads the structured error: a callee-side arity
;; rejection is the :eval/arity kind, which covers both the prim code
;; MAR001 and the compiled-fn code MAR002 (the pair is pinned by
;; mino's tests/arity_strict_test.clj). Value and type errors mean
;; the arity itself was accepted and the callee choked on the
;; sentinel, which is a pass on the arity axis. So does the
;; arity-kind keyword-call diagnostic: the arity under test was
;; accepted, then the sentinel failed in fn position inside the
;; callee (keywords as functions take 1 or 2 arguments).
;;
;; The sweeps run at load time and the deftests assert the collected
;; results: a few image-fn bodies recurse hundreds of eval frames on
;; sentinel input, and computing inside the runner's deeper call
;; context tips mino's stack and aborts with an empty diagnostic.
;; Load-time depth is the same depth the 2026-08-27 sweep ran at, so
;; the gate stays stable no matter which runner drives the file.

(def gate-sentinel :arglists-gate-sentinel)
(def arity-probe-max 9)

(defn arity-accepted?
  "True when calling v with k sentinel args does not end in an
  arity-classified rejection of that arity."
  [v k]
  (try (apply @v (repeat k gate-sentinel))
       true
       (catch Throwable e
         (or (not= :eval/arity (:mino/kind e))
             ;; An arity-kind error whose message is the keyword call
             ;; diagnostic counts as accepted: the arity under test was
             ;; accepted, then the sentinel reached fn position inside
             ;; the callee (reduce, trampoline, transduce, ...) and the
             ;; inner keyword call threw the JVM-correct arity error,
             ;; since keywords as functions take 1 or 2 arguments.
             (let [msg (:mino/message e)]
               (and (string? msg)
                    (clojure.string/includes?
                     msg "keyword as function")))))))

(defn sig-shape
  "One arglist vector reduced to {:min n :variadic bool}; :min counts
  the params before &."
  [sig]
  (loop [s sig n 0]
    (cond (empty? s) {:min n :variadic false}
          (= '& (first s)) {:min n :variadic true}
          :else (recur (rest s) (inc n)))))

(defn declared-min-arities
  "The probe set for the accepted side: every fixed arity plus every
  variadic minimum."
  [arglists]
  (set (map (fn [sig] (:min (sig-shape sig))) arglists)))

(defn claimed-arities
  "Every arity 0..arity-probe-max the arglists value claims: fixed
  counts exactly, variadic entries from their minimum upward."
  [arglists]
  (set (mapcat (fn [sig]
                 (let [shape (sig-shape sig)]
                   (if (:variadic shape)
                     (range (:min shape) (inc arity-probe-max))
                     [(:min shape)])))
               arglists)))

;; Probing is withheld from the prims whose application mutates
;; process-global state: spit writes files, and shutdown-agents
;; quiesces the agent pool the surrounding suite still needs (a
;; load-time probe kills every later agent-based test with MST008).
;; The walk demo fns join them: probing prints a Walked: line per
;; visited sentinel form to *out*, polluting the suite log (both are
;; fixed 2-arity defns; neither has an arity divergence to hide).
;; Macros are not apply-probed either; the apply contract differs for
;; them, so only their arglists presence is checked.
(def skip-vars #{"clojure.core/spit" "clojure.core/shutdown-agents"
                 "clojure.walk/postwalk-demo" "clojure.walk/prewalk-demo"})

;; Mirrored from lax-prims in tools/gen_arglists.clj in the mino repo
;; (the 2026-08-27 sweep): mino accepts every oracle arity plus
;; tolerated extras, so the extra arities stay unclaimed by design.
;; spit rides this class in the generator and the skip set here
;; removes it before the lax set is consulted. interleave needs no
;; entry: its defn claims every arity 0..9 through its variadic tail,
;; so no arity 0..9 is undeclared for it.
(def lax-vars
  #{"clojure.core/<"
    "clojure.core/<="
    "clojure.core/="
    "clojure.core/>"
    "clojure.core/>="
    "clojure.core/all-ns"
    "clojure.core/bit-and"
    "clojure.core/bit-or"
    "clojure.core/bit-xor"
    "clojure.core/byte-array"
    "clojure.core/conj!"
    "clojure.core/distinct?"
    "clojure.core/get-thread-bindings"
    "clojure.core/identical?"
    "clojure.core/loaded-libs"
    "clojure.core/object-array"
    "clojure.core/realized?"
    "clojure.core/ref-history-count"
    "clojure.core/ref-max-history"
    "clojure.core/ref-min-history"
    "clojure.core/release-pending-sends"
    "clojure.core/send-via"
    "clojure.core/spit"
    "clojure.core/symbol"
    "clojure.core/to-array"
    "clojure.core/with-meta"})

(def gate-nss '[clojure.core clojure.string clojure.repl
                clojure.walk clojure.datafy clojure.core.protocols])

(defn gate-targets
  "[id var] pairs for every public of ns-sym whose meta carries
  :arglists."
  [ns-sym]
  (let [acc (atom [])]
    (doseq [e (ns-publics ns-sym)]
      (let [v (val e)]
        (when (some? (:arglists (meta v)))
          (swap! acc conj [(str ns-sym "/" (key e)) v]))))
    @acc))

(defn violation-key [r]
  (str (:var r) "/" (:arity r) "/" (:direction r)))

(defn declared-violations-in
  "Every target whose declared minimum arities the callee rejects.
  Macros contribute a record when their arglists value is empty."
  [targets]
  (let [acc (atom [])]
    (doseq [target targets]
      (let [id (nth target 0) v (nth target 1) m (meta v)]
        (cond (skip-vars id) nil

              (:macro m)
              (when-not (seq (:arglists m))
                (swap! acc conj {:var id :direction :macro-arglists-empty}))

               :else
               (doseq [k (sort (declared-min-arities (:arglists m)))]
                 (when-not (arity-accepted? v k)
                   (swap! acc conj
                          {:var id :arity k
                           :direction :declared-rejected}))))))
    @acc))

(defn undeclared-violations-in
  "Every target that accepts an arity 0..9 its arglists do not
  claim, lax vars excepted."
  [targets]
  (let [acc (atom [])]
    (doseq [target targets]
      (let [id (nth target 0) v (nth target 1) m (meta v)]
        (when-not (or (skip-vars id)
                      (lax-vars id)
                      (:macro m))
          (let [claimed (claimed-arities (:arglists m))]
            (doseq [k (range 0 (inc arity-probe-max))]
              (when (and (not (contains? claimed k))
                         (arity-accepted? v k))
                (swap! acc conj
                       {:var id :arity k :direction :undeclared-accepted})))))))
    @acc))

(defn declared-violations [ns-sym]
  (declared-violations-in (gate-targets ns-sym)))

(defn undeclared-violations [ns-sym]
  (undeclared-violations-in (gate-targets ns-sym)))

;; The sweeps. Each is a deterministic function of the loaded image.

(def declared-sweep
  (into {}
        (map (fn [ns-sym]
               [(str ns-sym)
                (vec (sort-by violation-key (declared-violations ns-sym)))]))
        gate-nss))

(def undeclared-sweep
  (into {}
        (map (fn [ns-sym]
               [(str ns-sym)
                (vec (sort-by violation-key (undeclared-violations ns-sym)))]))
        gate-nss))

(deftest gate-declared-arities-accepted
  (doseq [ns-sym gate-nss]
    (let [vs (get declared-sweep (str ns-sym))]
      (is (empty? vs)
          (str ns-sym ": arglists claim arities the callee rejects "
               "(var, arity): " (pr-str vs))))))

(deftest gate-undeclared-arities-rejected
  (doseq [ns-sym gate-nss]
    (let [vs (get undeclared-sweep (str ns-sym))]
      (is (empty? vs)
          (str ns-sym ": callee accepts undeclared arities "
               "(var, arity): " (pr-str vs))))))

;; A deterministic spread sample instead of a random fuzz: all
;; probe-eligible pairs ordered by a char-sum hash of their key, first
;; 200 probed with the same two-sided invariant. A red run points at
;; the same 200 pairs every time, so there is no flake to reproduce.
(defn pair-hash [id k]
  (reduce (fn [acc c] (mod (+ (* acc 31) (int c)) 9973))
          7
          (str id "/" k)))

(defn eligible-targets []
  (let [acc (atom [])]
    (doseq [ns-sym gate-nss
            e (ns-publics ns-sym)]
      (let [v (val e) m (meta v)
            id (str ns-sym "/" (key e))]
        (when (and (some? (:arglists m))
                   (not (skip-vars id))
                   (not (:macro m)))
          (swap! acc conj [id v]))))
    @acc))

(def fuzz-probes (atom 0))

(defn fuzz-violations [sample-size]
  (reset! fuzz-probes 0)
  (let [pairs (atom [])]
    (doseq [t (eligible-targets)
            k (range 0 (inc arity-probe-max))]
      (swap! pairs conj [(nth t 0) (nth t 1) k]))
    (let [ordered (sort-by (fn [p] (str (pair-hash (nth p 0) (nth p 2))
                                        "|" (nth p 0) "|" (nth p 2)))
                           @pairs)
          acc (atom [])]
      (doseq [p (take sample-size ordered)]
        (let [id (nth p 0) v (nth p 1) k (nth p 2)
              claimed (contains? (claimed-arities (:arglists (meta v))) k)]
          (swap! fuzz-probes inc)
          (cond (and claimed
                     (not (arity-accepted? v k)))
                (swap! acc conj
                       {:var id :arity k :direction :declared-rejected})

                (and (not claimed)
                     (not (lax-vars id))
                     (arity-accepted? v k))
                (swap! acc conj
                       {:var id :arity k :direction :undeclared-accepted})

                :else nil)))
      @acc)))

(def fuzz-sample (vec (sort-by violation-key (fuzz-violations 200))))
(def fuzz-sample-size @fuzz-probes)

(deftest gate-fuzz-deterministic-sample
  (is (= 200 fuzz-sample-size)
      "the sample must probe exactly 200 pairs, else it went vacuous")
  (is (empty? fuzz-sample)
      (str "fuzz sample arity divergences (var, arity): "
           (pr-str fuzz-sample))))

;; Two poisoned vars prove the gate has teeth in both directions: the
;; metadata claims arities the bodies do not honor, and the same
;; checkers used on the real namespaces must report exactly those
;; lies and nothing else.
(def ^{:arglists (quote ([x y] [x]))} gate-poison-narrow
  (fn [x] x))

(def ^{:arglists (quote ([x]))} gate-poison-wide
  (fn ([x] x) ([x y] [x y])))

(deftest gate-sensitivity
  (is (= [{:var "gate-poison-narrow" :arity 2
           :direction :declared-rejected}]
         (vec (sort-by violation-key
                       (declared-violations-in [["gate-poison-narrow"
                                                #'gate-poison-narrow]])))))
  (is (= [{:var "gate-poison-wide" :arity 2
           :direction :undeclared-accepted}]
         (vec (sort-by violation-key
                       (undeclared-violations-in [["gate-poison-wide"
                                                  #'gate-poison-wide]]))))))

(run-tests-and-exit)
