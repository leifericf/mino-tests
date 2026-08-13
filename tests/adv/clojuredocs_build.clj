;; clojuredocs_build.clj -- build the ClojureDocs differential-test
;; fixture.
;;
;; Pulls the ClojureDocs example export, splits each example body into
;; (preamble + final-form + ;;=> expected) tuples, filters out tuples
;; that exercise Java interop / side effects / REPL state, then runs
;; each survivor through mino to record a ground-truth output. The
;; result is written as EDN to tests/adv/fixtures/clojuredocs-tuples.edn.
;;
;; The probe (tests/adv/script/diff_clojuredocs.clj) loads that EDN and
;; runs each form through mino, comparing to the recorded output.
;;
;; Run this on the dev host whenever the fixture should be refreshed:
;;
;;   ./mino/mino task clojuredocs-refresh
;;
;; The script requires a network connection to clojuredocs.org.

(require '[clojure.string :as str]
         '[clojure.data.json :as json])

;; ---- Configuration ----

(def export-url "https://clojuredocs.org/clojuredocs-export.json")
(def cache-path "tests/adv/fixtures/clojuredocs-export.json")
(def out-path "tests/adv/fixtures/clojuredocs-tuples.edn")

(def stdlib-nss
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
     class type instance? cast
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
    (let [wrapped (read-string (str "(do " s "\n)"))]
      (if (and (seq? wrapped) (= (first wrapped) 'do))
        (vec (rest wrapped))
        [wrapped]))
    (catch e nil)))

(defn split-segments
  "Split a body into [{:code [lines] :expected '...'}] segments."
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
       (or (re-matches #"^[A-Z][A-Za-z0-9]*(?:\.[A-Za-z][A-Za-z0-9]*)*\.?$" s)
           (re-matches #"^\.[A-Za-z].*$" s)
           (and ns (re-matches #"^[A-Z].*" ns))
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
  [carry-pre {:keys [code expected]}]
  (when (and expected (seq code))
    (let [code-str (strip-repl-prompts (str/join "\n" code))
          forms (read-all code-str)]
      (when (and forms (seq forms))
        (let [pre        (butlast forms)
              form       (last forms)
              pre-text   (str/join "\n" (map pr-str pre))
              merged-pre (->> [carry-pre pre-text]
                              (remove str/blank?)
                              (str/join "\n"))]
          {:preamble-source merged-pre
           :form-source     (pr-str form)
           :expected        (str/trim expected)
           :skip-reason     (triage-reason forms)
           :segment-text    (str/join "\n" (map pr-str forms))})))))

(defn parse-body
  [body]
  (loop [segs (split-segments body), carry "", out []]
    (if (empty? segs)
      (mapv #(dissoc % :segment-text) out)
      (let [t (segment->tuple carry (first segs))]
        (if t
          (recur (rest segs)
                 (->> [carry (:segment-text t)]
                      (remove str/blank?)
                      (str/join "\n"))
                 (conj out t))
          (recur (rest segs) carry out))))))

;; ---- Corpus extraction ----

(defn- corpus-tuples [export]
  (->> (:vars export)
       (filter #(contains? stdlib-nss (:ns %)))
       (mapcat (fn [v]
                 (mapcat (fn [ex]
                           (map #(assoc % :ns (:ns v) :var-name (:name v))
                                (parse-body (:body ex))))
                         (:examples v))))))

;; ---- Ground truth ----

(def script-prelude
  (str "(require '[clojure.string :as str]"
       " '[clojure.set :as set]"
       " '[clojure.walk :as walk]"
       " '[clojure.pprint :as pp]"
       " '[clojure.math])\n"))

(defn- render-script
  [{:keys [preamble-source form-source]}]
  (str script-prelude
       preamble-source
       "\n(binding [*print-length* 200 *print-level* 20]"
       " (println (pr-str " form-source ")))\n"))

(defn- run-evaluator
  "Run the mino binary with the given script. Returns {:out :exit}
   from sh. Uses the timeout command to kill runaway evaluations."
  [script]
  (let [result (sh "timeout" "3" "./mino/mino" "-e" script)]
    {:out (str/trim-newline (or (:out result) ""))
     :exit (:exit result)}))

(defn- ground-truth [tuple]
  (try
    (let [{:keys [out exit]} (run-evaluator (render-script tuple))]
      (cond
        (not (zero? exit)) {:status :eval-fail}
        (str/blank? out)   {:status :eval-empty}
        :else              {:status :ok :eval-out out}))
    (catch e
      {:status :eval-throw})))

;; ---- Pipeline ----

(defn- ensure-corpus! []
  (when-not (file-exists? cache-path)
    (println "Downloading" export-url "->" cache-path)
    (mkdir-p "tests/adv/fixtures")
    (spit cache-path (sh! "curl" "-s" export-url))))

(defn- iso-timestamp []
  (str/trim (sh! "date" "-u" "+%Y-%m-%dT%H:%M:%SZ")))

(defn- build! []
  (ensure-corpus!)
  (println "Parsing" cache-path)
  (let [export (json/read-str (slurp cache-path) :key-fn keyword)
        tuples (corpus-tuples export)
        runnable (filter #(nil? (:skip-reason %)) tuples)
        n-total (count tuples)
        n-run (count runnable)
        n-skip (- n-total n-run)]
    (println "Parsed" n-total "tuples;" n-run "runnable," n-skip "triaged")
    (println "Running mino on" n-run "tuples (this takes a few minutes)...")
    (let [t0 (time-ms)
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
          elapsed-s (long (/ (- (time-ms) t0) 1000))]
      (println "mino ran in" elapsed-s "s;" n-ok "produced ground truth," n-fail "couldn't")
      (println "Writing" out-path)
      (mkdir-p "tests/adv/fixtures")
      (spit out-path
            (binding [*print-length* nil *print-level* nil]
              (pr-str
               {:corpus {:source export-url
                         :captured-at (iso-timestamp)
                         :total-examples (->> (:vars export) (mapcat :examples) count)
                         :total-tuples n-total
                         :runnable n-run
                         :with-ground-truth n-ok}
                :tuples enriched})))
      (println "Done."))))

(build!)
