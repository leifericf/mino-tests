;; invariants.clj -- invariant predicates shared across probes.
;;
;; Each invariant takes a context map and returns either:
;;   true                       (invariant holds)
;;   {:violated ... :found ...} (invariant violated, with evidence)

(defn closure-i=
  "For a vec of zero-arity fns built across an iteration, the i'th
   should return the i'th iteration's value. Returns true or a
   violation map listing the (idx, expected, actual) of the first
   mismatch."
  [fs expected]
  (loop [i 0 fs fs expected expected]
    (cond
      (empty? fs)      true
      (empty? expected) true
      :else
      (let [actual (try ((first fs)) (catch Throwable e (str e)))
            want   (first expected)]
        (if (= actual want)
          (recur (inc i) (rest fs) (rest expected))
          {:violated :closure-i=
           :index    i
           :expected want
           :actual   actual})))))

(defn stm-sum-preserved
  "After N threads transact +1 and N threads transact -1 against the
   same ref, its value should be 0. Returns true or a violation."
  [final-val]
  (if (= 0 final-val)
    true
    {:violated :stm-sum-preserved :found final-val}))

(defn jit-quad-byte-id=
  "Run quad of mode outputs should all be byte-identical."
  [quad]
  (let [{:keys [auto on off lean]} quad]
    (if (= auto on off lean)
      true
      {:violated :jit-quad-byte-id=
       :auto auto :on on :off off :lean lean})))

(defn reader-idempotent
  "(read-string (pr-str v)) should round-trip for canonical values.
   Probes pass in fns that produce values to round-trip and compare."
  [v]
  (let [printed (pr-str v)
        round   (try (read-string printed)
                     (catch Throwable e {:read-error (str e)}))]
    (if (= v round)
      true
      {:violated :reader-idempotent
       :original v :printed printed :roundtrip round})))

(defn mode-shape-preservation
  "For an :mino/kind :mino/code :mino/message shape, the same form
   compiled in file-mode vs REPL-mode should preserve all three
   keys. Probes call this with (ex-shape e) maps from each mode."
  [file-shape repl-shape]
  (let [file-keys (when file-shape (set (keys file-shape)))
        repl-keys (when repl-shape (set (keys repl-shape)))]
    (if (and file-keys repl-keys
             (file-keys :mino/kind) (repl-keys :mino/kind)
             (file-keys :mino/code) (repl-keys :mino/code)
             (file-keys :mino/message) (repl-keys :mino/message))
      true
      {:violated :mode-shape-preservation
       :file file-shape :repl repl-shape})))

(defn help-version-distinct
  "mino --help and mino --version outputs should differ (regression
   for the lean-binary bug where they were identical)."
  [help-out version-out]
  (if (= help-out version-out)
    {:violated :help-version-distinct
     :help help-out :version version-out}
    true))

(defn bounded-depth-classified
  "When eval/read goes past a depth limit, the diagnostic should be a
   clean classified error (MRE/MTY/etc), not a SIGSEGV. Probes call
   this with the exception object."
  [e]
  (let [shape (try (ex-data e) (catch Throwable _ nil))
        kind  (when shape (:mino/kind shape))]
    (if kind
      true
      {:violated :bounded-depth-classified
       :exception (str e) :ex-data shape})))

(defn buffer-cap-classified
  "Like bounded-depth-classified but for the GC_SAVE_MAX-class bug:
   exceeding a per-form internal-buffer cap should classify, not
   abort."
  [e]
  (let [shape (try (ex-data e) (catch Throwable _ nil))]
    (if (and shape (:mino/kind shape))
      true
      {:violated :buffer-cap-classified
       :exception (str e) :ex-data shape})))

(defn diag-isolation
  "After a failure in expression E1 the next expression E2 should
   not inherit E1's line/col. Probes compare ex-data line/col."
  [e1-data e2-data]
  (cond
    (nil? e2-data) true
    (and (= (:mino/line e1-data) (:mino/line e2-data))
         (= (:mino/col  e1-data) (:mino/col  e2-data))
         (not= (:mino/code e1-data) (:mino/code e2-data)))
    {:violated :diag-isolation
     :e1 e1-data :e2 e2-data}
    :else true))
