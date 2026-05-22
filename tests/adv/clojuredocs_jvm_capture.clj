;; clojuredocs_jvm_capture.clj -- capture JVM-Clojure ground truth for
;; each example in the ClojureDocs fixture so the diff probe can compare
;; mino against canonical Clojure output (not just babashka).
;;
;; The bb fixture (tests/adv/fixtures/clojuredocs-tuples.edn) is the
;; primary; this script writes a companion EDN keyed by the same
;; ns/var/index that records what JVM Clojure prints for the same form.
;; The probe accepts a mino match against EITHER bb-out or jvm-out --
;; in practice they agree on the canonical printer, but the two
;; runtimes do diverge occasionally and JVM output is the true canon.
;;
;; Runs in a single `clojure -M` process: ~3s startup amortised across
;; ~1500 tuples, then each eval is sub-millisecond. Total wall time
;; on a dev host is a few minutes versus several hours if we spawned a
;; subprocess per tuple.
;;
;; Usage from mino-tests root:
;;
;;   clojure -M tests/adv/clojuredocs_jvm_capture.clj
;;
;; Writes tests/adv/fixtures/clojuredocs-jvm-tuples.edn.

(require '[clojure.string :as str]
         '[clojure.edn :as edn]
         '[clojure.set :as set]
         '[clojure.walk :as walk]
         '[clojure.pprint :as pp]
         '[clojure.math]
         '[clojure.spec.alpha]
         '[clojure.template]
         '[clojure.zip]
         '[clojure.java.io :as io])

(def in-path  "tests/adv/fixtures/clojuredocs-tuples.edn")
(def out-path "tests/adv/fixtures/clojuredocs-jvm-tuples.edn")

(defn- tuple-key
  "Same key format the probe uses: ns/var:index, where index is the
   position of this tuple within its (ns, var) group."
  [tuple idx]
  (str (:ns tuple) "/" (:var-name tuple) ":" idx))

(defn- index-tuples
  "Walk tuples in source order, numbering each per (ns, var) by its
   position. The probe (diff_clojuredocs.clj :: assign-indices) numbers
   the same way -- group-by would NOT preserve order and produces a
   different key set, which silently misaligns the two fixtures."
  [tuples]
  (let [counters (atom {})]
    (mapv (fn [t]
            (let [k [(:ns t) (:var-name t)]
                  i (get @counters k 0)]
              (swap! counters update k (fnil inc 0))
              (assoc t ::idx i)))
          tuples)))

(def ^:private per-tuple-ms 3000)

(defn- eval-with-timeout
  "Run thunk on a worker thread, force-stop after timeout-ms. Returns
   :timeout if the thread didn't finish in time, or the thunk's value
   wrapped in {:value ...}. A few real-world examples (recursive
   defaults, lazy seq blow-ups) loop forever in eval; without this
   guard the capture run hangs indefinitely."
  [timeout-ms thunk]
  (let [p (promise)
        ^Thread t (Thread. ^Runnable (fn []
                                       (deliver p (try {:value (thunk)}
                                                       (catch Throwable e
                                                         {:throw e})))))]
    (.setDaemon t true)
    (.start t)
    (let [r (deref p timeout-ms :timeout)]
      (when (= r :timeout)
        (try (.stop t) (catch Throwable _)))
      r)))

(defn- eval-tuple
  "Run preamble + form in a fresh sandbox namespace so prior tuples
   can't pollute later ones. Returns the same shape the bb path emits:
     {:status :ok :jvm-out \"...\"} on success
     {:status :jvm-fail :err \"...\"} on throw
     {:status :jvm-timeout} on per-tuple timeout
     {:status :jvm-empty} when the form printed nothing."
  [{:keys [preamble-source form-source]}]
  (let [ns-sym (gensym 'clojuredocs-jvm-sandbox-)
        r (eval-with-timeout
            per-tuple-ms
            (fn []
              (binding [*ns* (create-ns ns-sym)]
                (refer 'clojure.core)
                ;; Match the bb fixture-build prelude exactly so the two
                ;; ground truths see the same namespace surface.
                (alias 'str 'clojure.string)
                (alias 'set 'clojure.set)
                (alias 'walk 'clojure.walk)
                (alias 'pp 'clojure.pprint)
                (alias 'math 'clojure.math)
                (alias 'spec 'clojure.spec.alpha)
                (alias 'edn 'clojure.edn)
                (let [reads (fn [src]
                              (read-string (str "(do " src "\n nil)")))
                      buf  (java.io.StringWriter.)]
                  (when (seq preamble-source)
                    (binding [*print-length* 200 *print-level* 20]
                      (eval (reads preamble-source))))
                  (binding [*out*          buf
                            *print-length* 200
                            *print-level*  20]
                    (println (pr-str (eval (read-string form-source)))))
                  (str/trim-newline (str buf))))))]
    (remove-ns ns-sym)
    (cond
      (= r :timeout)        {:status :jvm-timeout}
      (contains? r :throw)  {:status :jvm-fail
                             :err   (let [m (.getMessage ^Throwable (:throw r))]
                                      (when m (first (str/split-lines m))))}
      (str/blank? (:value r)) {:status :jvm-empty}
      :else                   {:status :ok :jvm-out (:value r)})))

(defn- run-capture! []
  (println "Reading" in-path)
  (let [in   (edn/read-string {:default tagged-literal} (slurp in-path))
        ;; Match the probe's pipeline: filter to bb-ok tuples FIRST so
        ;; the per-(ns, var) indices line up with the probe's
        ;; assign-indices. If we indexed all tuples, a non-ok earlier
        ;; entry would shift every later index and the probe would look
        ;; up the wrong jvm-out.
        all  (index-tuples
              (filter #(= :ok (:status (:gt %))) (:tuples in)))
        n    (count all)
        t0   (System/currentTimeMillis)]
    (println "Evaluating" n "tuples through JVM Clojure...")
    (let [acc (loop [acc (transient {})
                     i 0
                     remaining all]
                (if-let [t (first remaining)]
                  (do
                    (when (zero? (mod (inc i) 100))
                      (println " " (inc i) "/" n
                               (str "("
                                    (long (/ (- (System/currentTimeMillis)
                                                t0)
                                             1000))
                                    "s elapsed)")))
                    (recur (assoc! acc (tuple-key t (::idx t))
                                   (eval-tuple t))
                           (inc i)
                           (rest remaining)))
                  acc))]
      (let [m       (into (sorted-map) (persistent! acc))
            n-ok    (count (filter #(= :ok (:status (val %))) m))
            n-fail  (count (filter #(= :jvm-fail (:status (val %))) m))
            n-empty (count (filter #(= :jvm-empty (:status (val %))) m))
            n-to    (count (filter #(= :jvm-timeout (:status (val %))) m))
            sec     (long (/ (- (System/currentTimeMillis) t0) 1000))]
        (println "Done in" sec "s:" n-ok "ok," n-fail "fail,"
                 n-empty "empty," n-to "timeout")
        (println "Writing" out-path)
        (io/make-parents out-path)
        (spit out-path
              (binding [*print-length* nil *print-level* nil]
                (pr-str
                 {:corpus {:source        in-path
                           :captured-at   (str (java.time.Instant/now))
                           :runtime       "JVM Clojure (clojure-clj)"
                           :clojure-vers  (clojure-version)
                           :total-tuples  n
                           :n-ok          n-ok
                           :n-fail        n-fail
                           :n-empty       n-empty
                           :n-timeout     n-to}
                  :outputs m})))))))

(run-capture!)
