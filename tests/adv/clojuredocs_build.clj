#!/usr/bin/env bb
;; clojuredocs_build.clj  --  build the ClojureDocs differential-test fixture.
;;
;; Pulls the ClojureDocs example export, splits each example body into
;; (preamble + final-form + ;;=> expected) tuples, filters out tuples
;; that exercise Java interop / side effects / REPL state, then runs
;; each survivor through bb to record a ground-truth output. The result
;; is written as EDN to tests/adv/fixtures/clojuredocs-tuples.edn.
;;
;; The probe (tests/adv/script/diff_clojuredocs.clj) loads that EDN and
;; only needs to run each form through mino, comparing to the recorded
;; bb output. No bb at test time -> nightly CI doesn't need it.
;;
;; Run this on the dev host whenever the fixture should be refreshed:
;;
;;   ./mino/mino task clojuredocs-refresh
;;
;; (or directly: bb tests/adv/clojuredocs_build.clj)
;;
;; The script requires bb plus a network connection to clojuredocs.org.
;; It writes/overwrites the EDN fixture; nothing else.

(require '[clojure.string :as str]
         '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[cheshire.core :as json]
         '[babashka.process :as p])

;; ---- Configuration ----

(def export-url "https://clojuredocs.org/clojuredocs-export.json")
(def cache-path "tests/adv/fixtures/clojuredocs-export.json")
(def out-path "tests/adv/fixtures/clojuredocs-tuples.edn")

(def stdlib-nss
  "Examples for vars in these namespaces are considered in-scope. Other
   namespaces (core.async, core.logic, contrib libs) exercise dispatch
   that bb and mino don't both ship, so they aren't a useful diff."
  #{"clojure.core" "clojure.string" "clojure.set" "clojure.walk"
    "clojure.zip" "clojure.template" "clojure.edn"
    "clojure.spec.alpha"})

;; ---- Triage symbols: presence anywhere in the form -> skip ----

(def skip-symbols
  '#{println print pr prn printf newline flush
     slurp spit read-line line-seq with-open file file-seq
     time eval load load-file load-string require use import
     in-ns ns create-ns remove-ns the-ns ns-publics ns-interns
     ns-aliases ns-refers ns-resolve ns-map ns-name find-ns all-ns
     agent send send-off await await-for shutdown-agents
     future future-call promise deliver pmap pcalls pvalues
     atom ref ref-set commute alter ensure dosync
     swap! reset! compare-and-set! add-watch remove-watch
     gensym
     read read-string *read-eval*
     *1 *2 *3 *e *ns* *file* *agent* *out* *in* *err* *command-line-args*
     System Thread Runtime Math String Integer Long Double Boolean Character
     Class Object File URL URI Date Calendar Locale Pattern Matcher
     ArrayList HashMap HashSet LinkedList StringBuilder StringBuffer
     bigint biginteger bigdec rationalize
     locking sync io!
     declare definterface defmulti defprotocol defrecord deftype reify proxy
     send-via set-error-handler! restart-agent
     meta with-meta vary-meta alter-meta! reset-meta!
     ;; Java-class-returning fns: bb returns java.lang.Long etc., mino's
     ;; type system is different so these always look like divergence.
     class type instance? cast
     ;; dot special forms for Java interop
     . .. set!})

;; ---- Body parsing ----

(defn- strip-repl-prompts [body]
  (-> body
      (str/replace #"(?m)^\s*[A-Za-z0-9_.\-]+=>\s?" "")
      (str/replace #"(?m)^\s*=>\s?" "")))

(defn- read-all
  "Read all top-level forms from `s` without evaluation. Returns nil on
   any reader error -- malformed examples are skipped, not flagged."
  [s]
  (try
    (let [rdr (java.io.PushbackReader. (java.io.StringReader. s))
          eof ::eof]
      (binding [*read-eval* false]
        (loop [acc []]
          (let [v (read {:eof eof :read-cond :allow :features #{:clj}} rdr)]
            (if (= v eof) acc (recur (conj acc v)))))))
    (catch Exception _ nil)))

(defn split-segments
  "Split a body into [{:code [lines] :expected '...'}] segments. Each
   segment is the code that came before its `;;=>` line, plus the
   continuation `;;` lines after."
  [body]
  (let [lines (str/split-lines body)]
    (loop [acc [] code [] lines lines]
      (cond
        (empty? lines)
        (cond-> acc (some #(not (str/blank? %)) code)
                (conj {:code code :expected nil}))
        :else
        (let [line (first lines)]
          (if-let [[_ initial] (re-matches #"^\s*;;?\s*=>\s?(.*)$" line)]
            (let [[cont rest-lines]
                  (split-with #(re-matches #"^\s*;;.+$" %) (rest lines))
                  expected (str/join "\n"
                                     (cons (str/trim initial)
                                           (map #(str/replace % #"^\s*;;\s?" "") cont)))]
              (recur (conj acc {:code code :expected expected}) [] rest-lines))
            (recur acc (conj code line) (rest lines))))))))

;; ---- Triage ----

(defn- contains-symbol? [form sym]
  (cond
    (= form sym) true
    (sequential? form) (some #(contains-symbol? % sym) form)
    (map? form) (or (some #(contains-symbol? % sym) (keys form))
                    (some #(contains-symbol? % sym) (vals form)))
    (set? form) (some #(contains-symbol? % sym) form)
    :else false))

(defn- has-java-interop? [form]
  (cond
    (symbol? form)
    (let [s (name form) ns (namespace form)]
      (boolean
       (or ;; Class. or Class or pkg.Class or pkg.Class. constructor calls
           (re-matches #"^[A-Z][A-Za-z0-9]*(?:\.[A-Za-z][A-Za-z0-9]*)*\.?$" s)
           ;; .methodName access form
           (re-matches #"^\.[A-Za-z].*$" s)
           ;; Class/staticMethod -- detect by uppercase first letter of namespace
           (and ns (re-matches #"^[A-Z].*" ns))
           ;; Java-package prefixes used as a function call: java.util.Date.
           (re-find #"^(?:java|javax|jakarta|org\.[a-z]+|com\.[a-z]+|sun\.|clojure\.lang)\." s)
           (= s "new"))))
    (sequential? form) (some has-java-interop? form)
    (map? form) (or (some has-java-interop? (keys form))
                    (some has-java-interop? (vals form)))
    (set? form) (some has-java-interop? form)
    :else false))

(defn- triage-reason [forms]
  (let [all (apply list forms)]
    (cond
      (has-java-interop? all) :java-interop
      (first (filter #(contains-symbol? all %) skip-symbols)) :side-effect-or-state
      :else nil)))

(defn- segment->tuple
  "Build a tuple for one segment of an example body.

   The stored shape is source-only -- preamble and form are kept as
   pr-str text rather than as parsed forms -- so the EDN stays
   readable by clojure.edn (no reader-macro literals). The text
   round-trips cleanly into mino's full reader at probe time."
  [{:keys [code expected]}]
  (when (and expected (seq code))
    (let [code-str (strip-repl-prompts (str/join "\n" code))
          forms (read-all code-str)]
      (when (and forms (seq forms))
        (let [pre (butlast forms)
              form (last forms)]
          {:preamble-source (str/join "\n" (map pr-str pre))
           :form-source (pr-str form)
           :expected (str/trim expected)
           :skip-reason (triage-reason forms)})))))

(defn parse-body [body]
  (->> (split-segments body)
       (keep segment->tuple)
       vec))

;; ---- Corpus extraction ----

(defn- corpus-tuples [export]
  (->> (:vars export)
       (filter #(contains? stdlib-nss (:ns %)))
       (mapcat (fn [v]
                 (mapcat (fn [ex]
                           (map #(assoc % :ns (:ns v) :var-name (:name v))
                                (parse-body (:body ex))))
                         (:examples v))))))

;; ---- bb ground truth ----

(defn- render-script
  "Render a self-contained script that prints (pr-str <form>) with
   *print-length* and *print-level* bound. The bounds keep an infinite
   lazy seq from hanging the subprocess; an over-long print still gets
   truncated cleanly with `...` at the end."
  [{:keys [preamble-source form-source]}]
  (str preamble-source
       "\n(binding [*print-length* 200 *print-level* 20]"
       " (println (pr-str " form-source ")))\n"))

(defn- run-bb
  "Run bb with the given script. Hard kill after 3s -- any example that
   takes that long is either looping or producing too much output, and
   neither is useful as a ground truth."
  [script]
  (let [proc (p/process ["bb" "-e" script] {:out :string :err :string})
        result (deref proc 3000 ::timeout)]
    (if (= result ::timeout)
      (do (.destroyForcibly ^java.lang.Process (:proc proc))
          {:out "" :exit -1 :err "timeout" :timed-out? true})
      {:out (str/trim-newline (or (:out result) ""))
       :exit (:exit result)
       :err (when (seq (:err result)) (first (str/split-lines (:err result))))
       :timed-out? false})))

(defn- ground-truth [tuple]
  (try
    (let [{:keys [out exit err timed-out?]} (run-bb (render-script tuple))]
      (cond
        timed-out?         {:status :bb-timeout}
        (not (zero? exit)) {:status :bb-fail :err err}
        (str/blank? out)   {:status :bb-empty}
        :else              {:status :ok :bb-out out}))
    (catch Exception e
      {:status :bb-throw :err (.getMessage e)})))

;; ---- Pipeline ----

(defn- ensure-corpus! []
  (when-not (.exists (io/file cache-path))
    (println "Downloading" export-url "->" cache-path)
    (io/make-parents cache-path)
    (spit cache-path (slurp export-url))))

(defn- build! []
  (ensure-corpus!)
  (println "Parsing" cache-path)
  (let [export (json/parse-string (slurp cache-path) true)
        tuples (corpus-tuples export)
        runnable (filter #(nil? (:skip-reason %)) tuples)
        n-total (count tuples)
        n-run (count runnable)
        n-skip (- n-total n-run)]
    (println "Parsed" n-total "tuples;" n-run "runnable," n-skip "triaged")
    (println "Running bb on" n-run "tuples (this takes a few minutes)...")
    (let [t0 (System/currentTimeMillis)
          enriched (vec
                    (for [[i t] (map-indexed vector runnable)]
                      (let [gt (ground-truth t)]
                        (when (zero? (mod (inc i) 100))
                          (println " " (inc i) "/" n-run))
                        (-> t
                            (dissoc :source :skip-reason)
                            (assoc :gt gt)))))
          n-ok (count (filter #(= :ok (:status (:gt %))) enriched))
          n-fail (- n-run n-ok)
          elapsed-s (long (/ (- (System/currentTimeMillis) t0) 1000))]
      (println "bb ran in" elapsed-s "s;" n-ok "produced ground truth," n-fail "couldn't")
      (println "Writing" out-path)
      (io/make-parents out-path)
      (spit out-path
            (binding [*print-length* nil *print-level* nil]
              (pr-str
               {:corpus {:source export-url
                         :captured-at (str (java.time.Instant/now))
                         :total-examples (->> (:vars export) (mapcat :examples) count)
                         :total-tuples n-total
                         :runnable n-run
                         :with-ground-truth n-ok}
                :tuples enriched})))
      (println "Done."))))

(build!)
