;; conformance_edge_capture.clj -- build the curated edge corpus from
;; the authored forms file and record bb ground truth for each form.
;;
;; Input:  tests/adv/fixtures/conformance-edge-forms.edn
;;         {:forms [{:var "rationalize" :form "(rationalize 0.1)"}
;;                  {:var "count" :apply-palette 1}
;;                  {:var "reductions" :form "..." :pending-bug "..."} ...]}
;;   :form entries are hand-reasoned edge cases (the judgment tier).
;;   :apply-palette N entries expand mechanically: the var applied to
;;   every N-tuple drawn from the fixed palette below (the free tier
;;   that catches bulk arity/type edges without any judgment). Palette
;;   applications that throw in canon are recorded as non-ok ground
;;   truth and filtered by the differ, so a throwing combination costs
;;   a corpus entry, never a false failure.
;;   :pending-bug carries through to the tuple: the differ skips and
;;   counts it (open bug, tracked in mino/.local/BUGS.md).
;;
;; Output: tests/adv/fixtures/conformance-edge-tuples.edn, same tuple
;; shape as the clojuredocs fixture. Eval semantics mirror
;; clojuredocs_jvm_capture.clj exactly (fresh sandbox ns, same aliases,
;; *print-length* 200 / *print-level* 20, pr-str, trim-newline) so bb,
;; JVM, and mino all see the same namespace surface and print bounds.
;;
;; Usage from mino-tests root:  bb tests/adv/conformance_edge_capture.clj

(require '[clojure.string :as str]
         '[clojure.set]
         '[clojure.walk]
         '[clojure.pprint]
         '[clojure.math]
         '[clojure.edn :as edn]
         '[clojure.java.io :as io])

(def in-path  "tests/adv/fixtures/conformance-edge-forms.edn")
(def out-path "tests/adv/fixtures/conformance-edge-tuples.edn")

;; Fixed palette of edge values, as source text. Order matters: it is
;; part of the corpus identity (tuple indices are positional).
(def palette
  ["nil" "[]" "{}" "\"\"" "()" "#{}" "0" "-1" "1" "1.5" "-0.0"
   "9223372036854775807" "-9223372036854775808" "1/3" "10N" "0.1M"
   "\"abc\"" ":kw" "'sym" "[1 2 3]" "{:a 1}" "#{1 2}" "(range 5)"
   "(range)" "\\a"])

(defn- cartesian [n]
  (if (= n 1)
    (map vector palette)
    (for [head palette, tail (cartesian (dec n))]
      (into [head] tail))))

(defn- expand-entry [{:keys [var form apply-palette pending-bug]}]
  (cond
    form
    [{:var var :form form :pending-bug pending-bug}]

    apply-palette
    (for [args (cartesian apply-palette)]
      {:var var
       :form (str "(" var " " (str/join " " args) ")")})

    :else
    (throw (ex-info "forms entry needs :form or :apply-palette"
                    {:entry {:var var}}))))

(defn- eval-with-timeout [timeout-ms thunk]
  (let [fut (future (try {:value (thunk)}
                         (catch Throwable e {:throw e})))
        r   (deref fut timeout-ms :timeout)]
    (when (= r :timeout) (future-cancel fut))
    r))

(defn- eval-one [form-src]
  (let [ns-sym (gensym 'conformance-edge-sandbox-)
        r (eval-with-timeout
           3000
           (fn []
             (binding [*ns* (create-ns ns-sym)]
               (refer 'clojure.core)
               (alias 'str 'clojure.string)
               (alias 'set 'clojure.set)
               (alias 'walk 'clojure.walk)
               (alias 'pp 'clojure.pprint)
               (alias 'math 'clojure.math)
               (alias 'edn 'clojure.edn)
               (let [buf (java.io.StringWriter.)]
                 (binding [*out* buf *print-length* 200 *print-level* 20]
                   (println (pr-str (eval (read-string form-src)))))
                 (str/trim-newline (str buf))))))]
    (remove-ns ns-sym)
    (cond
      (= r :timeout)       {:status :bb-timeout}
      (contains? r :throw) {:status :bb-fail
                            :err (some-> (.getMessage ^Throwable (:throw r))
                                         str/split-lines first)}
      :else                {:status :ok :bb-out (:value r)})))

(let [in (edn/read-string (slurp in-path))
      expanded (vec (mapcat expand-entry (:forms in)))
      _ (println "Capturing bb ground truth for" (count expanded) "forms...")
      tuples (mapv (fn [{:keys [var form pending-bug]}]
                     (let [gt (eval-one form)]
                       (cond-> {:preamble-source ""
                                :form-source form
                                :expected (:bb-out gt)
                                :ns "clojure.core"
                                :var-name var
                                :gt gt}
                         pending-bug (assoc :pending-bug pending-bug))))
                   expanded)
      n-ok (count (filter #(= :ok (:status (:gt %))) tuples))
      n-pending (count (filter :pending-bug tuples))]
  (io/make-parents out-path)
  (spit out-path
        (binding [*print-length* nil *print-level* nil]
          (pr-str {:corpus {:source in-path
                            :total-tuples (count tuples)
                            :runnable n-ok
                            :with-ground-truth n-ok}
                   :tuples tuples})))
  (println "Wrote" out-path ":" (count tuples) "tuples,"
           n-ok "with bb ground truth," n-pending "pending-bug")
  (doseq [t tuples
          :when (and (not= :ok (:status (:gt t)))
                     (not= :bb-fail (:status (:gt t))))]
    (println "  note:" (:form-source t) "->" (:status (:gt t)))))
