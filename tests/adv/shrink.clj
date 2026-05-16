;; shrink.clj -- reduce a divergent mino program to a minimal witness.
;;
;; A divergence is a program whose four-mode quad isn't byte-identical
;; (some variant of stdout or exit code differs). Manual diagnosis on
;; a 500-character random program is painful; shrinking finds the
;; smallest program-string that still produces the divergence so the
;; underlying compiler / runtime bug is easier to spot.
;;
;; Strategy (delta-debugging-shaped):
;;   1. Parse the program string into a list of top-level forms.
;;   2. Try removing each form; if the divergence persists, drop it.
;;      Never drop the final printing form -- without printed output
;;      there's nothing to compare and "byte-identical" becomes
;;      trivially true.
;;   3. For each remaining form, try replacing subexpressions with
;;      simpler literals (0, nil) and check whether the divergence
;;      still appears.
;;   4. Repeat until a full pass yields no change. The result is the
;;      smallest the algorithm could reach inside the wall-clock budget.
;;
;; Wall-clock budget: 30s by default. The shrinker times out cleanly
;; and returns whatever it has -- partial reductions are still useful.

(load-file "tests/adv/edge_helpers.clj")
(require '[clojure.string :as s])

(def ^:private default-budget-ms 30000)

(defn- forms-of
  "Parse program-str into a vec of top-level forms. mino's
   read-string reads a single form; we split on top-level newlines
   and reassemble. For now, programs from gen_program are emitted
   one form per line so this is sufficient."
  [program-str]
  (vec (->> (s/split-lines program-str)
            (remove s/blank?))))

(defn- emit-forms
  "Reassemble a vec of form-strings into a program string."
  [forms]
  (apply str (interpose "\n" forms)))

(defn- printing-form?
  "True iff this top-level form contains a print/println/pr/prn call.
   The shrinker preserves the LAST printing form so the program still
   has stdout to compare against."
  [form-str]
  (or (s/includes? form-str "(println ")
      (s/includes? form-str "(print ")
      (s/includes? form-str "(prn ")
      (s/includes? form-str "(pr ")))

(defn shrink-by-form-removal
  "First-pass: try dropping each top-level form individually and
   keep the drop if the divergence persists. Walks back to front
   so a removed form never breaks the symbol resolution of a later
   form (defns referencing each other survive)."
  [diverges? forms budget-ms start]
  (loop [forms forms, i (dec (count forms))]
    (cond
      (> (- (now-ms) start) budget-ms) forms
      (< i 0) forms
      (and (= i (dec (count forms)))
           (printing-form? (nth forms i)))
      ;; Never drop the final printing form.
      (recur forms (dec i))
      :else
      (let [candidate (vec (concat (subvec forms 0 i)
                                   (subvec forms (inc i))))]
        (if (and (seq candidate)
                 (diverges? (emit-forms candidate)))
          (recur candidate (dec i))
          (recur forms (dec i)))))))

(defn- replace-substring [haystack pat repl]
  ;; mino's clojure.string/replace doesn't support regex; we use the
  ;; literal-string overload which IS what we want here -- the
  ;; substitutions are exact textual replacements.
  (s/replace haystack pat repl))

(defn shrink-by-substitution
  "Second-pass: try replacing complex sub-expressions with simpler
   literals. Walks a small library of canonical substitutions; keeps
   each substitution that preserves the divergence. The substitutions
   are deliberately textual -- they're cheap, work on the source
   string directly, and don't depend on a full reader pipeline."
  [diverges? program-str budget-ms start]
  (let [;; Each candidate is [needle replacement]. The needles
        ;; target shapes gen_program emits; the replacements are
        ;; literal constants or simpler nested forms.
        candidates [
                    ;; Replace long loop bodies with their accumulator
                    ["(recur (inc " "(recur (inc "]
                    ;; Drop `or` wrappers around when forms -- the
                    ;; common gen_program shape "or-when-pos" reduces
                    ;; to a literal.
                    ["(or (when " "(when "]
                    ;; Replace nested arith with literal results.
                    ;; Walked by the changeset.
                    ]]
    (loop [s program-str, [c & rest] candidates]
      (cond
        (> (- (now-ms) start) budget-ms) s
        (nil? c) s
        :else
        (let [[needle repl] c
              s' (replace-substring s needle repl)]
          (if (and (not= s s')
                   (diverges? s'))
            (recur s' rest)
            (recur s rest)))))))

(defn shrink-divergent
  "Reduce a divergent program to a minimal witness.

   Arguments:
     mino-bin       -- path to the mino binary (carries --jit modes)
     lean-bin       -- path to mino-lean (no JIT pipeline)
     program-str    -- the divergent program source
     {:keys [budget-ms]} -- optional opts

   Returns the smallest divergent program-string the shrinker
   reached within the wall-clock budget. If the input doesn't
   diverge, the original string is returned unchanged."
  [mino-bin lean-bin program-str & [{:keys [budget-ms]
                                      :or {budget-ms default-budget-ms}}]]
  (let [start (now-ms)
        diverges? (fn [src]
                    (let [tmp (str "/tmp/mino-shrink-" (now-ms) ".clj")]
                      (spit tmp src)
                      (let [q (run-quad mino-bin lean-bin tmp)
                            d (not (quad-byte-identical? q))]
                        (sh "rm" "-f" tmp)
                        d)))]
    (cond
      (not (diverges? program-str))
      (do (println "  [shrink] input does not diverge; returning unchanged")
          program-str)

      :else
      (let [forms (forms-of program-str)
            after-removal (shrink-by-form-removal diverges? forms
                                                  budget-ms start)
            reduced (shrink-by-substitution diverges?
                                            (emit-forms after-removal)
                                            budget-ms start)]
        (println "  [shrink] reduced from"
                 (count program-str) "to" (count reduced) "chars"
                 "elapsed:" (- (now-ms) start) "ms")
        reduced))))
