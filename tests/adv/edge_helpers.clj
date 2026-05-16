;; edge_helpers.clj -- script-side helpers for adversarial probes.
;;
;; Loaded by probe scripts via:
;;   (load-file "tests/adv/edge_helpers.clj")
;; The helpers stay in the user namespace so probes can use them
;; directly without a require dance.

;; --- timing ---

(defn now-ms [] (time-ms))

(defmacro elapsed-ms [& body]
  `(let [start# (time-ms)
         _#     (do ~@body)
         end#   (time-ms)]
     (- end# start#)))

;; --- exception capture ---

(defn eval-survives
  "Call f with no args; return :ok if it returns, the ex-info data
   map if it throws something normalize-able. Used by probes that
   want to assert 'this should not crash the runtime' without caring
   about the specific outcome."
  [f]
  (try
    (f)
    :ok
    (catch e
      (or (ex-data e)
          {:mino/kind :throwable :mino/message (str e)}))))

(defn ex-shape
  "Return the {:mino/kind :mino/code :mino/message} shape from an
   ex-info-wrapped exception, or nil if the exception isn't shaped
   that way."
  [e]
  (let [d (ex-data e)]
    (when (and d (:mino/kind d))
      (select-keys d [:mino/kind :mino/code :mino/message]))))

;; --- stdout / stderr capture via tmpfile + sh ---

(defn capture-eval
  "Spawn mino-bin in a child process with EXPR as a temp file.
   Returns the {:out S :err S :exit N} map from sh. Uses sh, not sh!,
   so non-zero exit doesn't throw -- probes want failed runs too."
  [mino-bin expr]
  (let [tmp (str "/tmp/mino-tests-cap-" (now-ms) "-" (rand-int 100000) ".clj")]
    (spit tmp expr)
    (let [out (sh mino-bin tmp)]
      (sh "rm" "-f" tmp)
      out)))

;; --- run-quad: parity across auto / on / off / lean ---

(defn run-quad
  "Run the same source against four binary/mode combinations:
     :auto -- mino --jit=auto
     :on   -- mino --jit=on
     :off  -- mino --jit=off
     :lean -- mino-lean (no --jit)
   Returns a map keyed by variant -> stdout string.
   The caller decides whether to assert byte-identity."
  [mino-bin mino-lean-bin src-file]
  {:auto (try (sh! mino-bin "--jit=auto" src-file) (catch e (str e)))
   :on   (try (sh! mino-bin "--jit=on"   src-file) (catch e (str e)))
   :off  (try (sh! mino-bin "--jit=off"  src-file) (catch e (str e)))
   :lean (try (sh! mino-lean-bin          src-file) (catch e (str e)))})

(defn quad-byte-identical?
  "True iff all four variants produced byte-identical stdout."
  [quad]
  (let [{:keys [auto on off lean]} quad]
    (= auto on off lean)))

;; --- probe verdict emit ---

(defn emit-verdict
  "Print one JSON line for the runner to ingest."
  [probe-name verdict & extras]
  (let [m (merge {:probe probe-name :verdict verdict}
                 (apply hash-map extras))]
    (println (pr-str m))))

;; --- with-seed: pin RNG state for one block ---

(def ^:dynamic *seed* 0)

(defmacro with-seed [s & body]
  `(binding [*seed* ~s]
     ~@body))
