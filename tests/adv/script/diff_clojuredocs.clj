;; diff_clojuredocs.clj -- mino-vs-JVM-Clojure diff over user-written
;; examples from clojuredocs.org.
;;
;; Loads the vendored fixture (see tests/adv/clojuredocs_build.clj for
;; how it's produced) and, for each example whose ground-truth bb run
;; succeeded, runs the same form through mino and asserts byte-identical
;; printed output.
;;
;; Why this exists: random-program differential testing (diff_random)
;; covers the syntactic interior of the language well; this probe
;; covers the SEMANTIC surface a Clojure user actually reaches for --
;; the cookbook-style transforms (map / filter / reduce / interpose /
;; assoc-in / sort-by-juxt / ...). When mino diverges here, a user
;; coming from Clojure sees it on day one.
;;
;; Ground truth is the bb output recorded at fixture-build time, not
;; the documented `;;=>` string. ClojureDocs prose can drop outer
;; string quotes, lag behind a Clojure release, or simply contain
;; typos -- bb's actual print is what a user pasting the example into
;; the JVM REPL sees today, so that's what mino has to match.
;;
;; Allowlist at tests/adv/fixtures/clojuredocs_allowlist.edn covers
;; examples whose mino output intentionally differs (a named opt-in
;; or a designed divergence). Allowlisted misses are skip, not fail.
;;
;; Smoke: 50 examples, fixed seed, <30s budget.
;; Soak : full ~1100 examples, random seed, several minutes.

(load-file "tests/adv/edge_helpers.clj")
(require '[clojure.string :as s])

(def mino-bin (or (getenv "MINO_BIN") "mino/mino"))

(def fixture-path "tests/adv/fixtures/clojuredocs-tuples.edn")
(def allowlist-path "tests/adv/fixtures/clojuredocs_allowlist.edn")

(def n-examples
  (case (:mode cli-opts)
    "soak"  nil ;; nil = all
    "smoke" 50
    50))

;; A separate seed offset so this probe's example ordering doesn't
;; shift when other probes load ahead of us. Same trick as diff_random.
(def diff-cd-seed-tag 0xCDF1E)
(seed! (bit-xor effective-seed diff-cd-seed-tag))

(defn- safe-name
  "Replace characters that would confuse the filesystem (/, :, space)
   with underscores. Done char-by-char so we don't depend on regex
   replacement, which mino's clojure.string/replace doesn't yet
   accept (see mino/.local/BUGS.md)."
  [s]
  (apply str
         (map (fn [c]
                (let [s (str c)]
                  (cond
                    (or (and (>= (compare s "a") 0) (<= (compare s "z") 0))
                        (and (>= (compare s "A") 0) (<= (compare s "Z") 0))
                        (and (>= (compare s "0") 0) (<= (compare s "9") 0))
                        (= s ".") (= s "-") (= s "_"))
                    s
                    :else "_")))
              s)))

(defn- regression-path [seed key]
  (str "tests/adv/regressions/clojuredocs-" seed "-" (safe-name key) ".clj"))

;; --- fixture loading ---

(defn- load-fixture []
  (try
    (read-string (slurp fixture-path))
    (catch e
      (println "ERROR: failed to read fixture at" fixture-path ":" (str e))
      (println "Did you run `./mino/mino task clojuredocs-refresh` to build it?")
      (exit 2))))

(defn- load-allowlist []
  (try
    (read-string (slurp allowlist-path))
    (catch e {})))

;; --- script rendering ---

;; Prelude prepended to every rendered script. bb (and the JVM REPL) pre-
;; alias these namespaces; mino doesn't, so cookbook forms like
;; `(str/replace ...)` would otherwise fail at the alias resolver before
;; mino's stdlib path even ran. Keeping the prelude identical on both
;; the bb ground-truth side (clojuredocs_build.clj) and the mino probe
;; side guarantees the two halves of the diff see the same namespace
;; surface.
(def script-prelude
  (str "(require '[clojure.string :as str]"
       " '[clojure.set :as set]"
       " '[clojure.walk :as walk]"
       " '[clojure.pprint :as pp])\n"))

(defn- render-script
  "Render a mino script that prints (pr-str <form>) with print bounds
   so an infinite seq doesn't hang the subprocess. Matches the bb
   render at fixture-build time so the comparison is apples-to-apples."
  [tuple]
  (str script-prelude
       (:preamble-source tuple)
       "\n(binding [*print-length* 200 *print-level* 20]"
       " (println (pr-str " (:form-source tuple) ")))\n"))

;; Probe the host for a timeout binary so a single hung example
;; (e.g. infinite recursion through with-redefs) doesn't block the
;; whole soak. Linux ships `timeout` natively; macOS via brew's
;; coreutils ships both `timeout` and `gtimeout`. If neither is
;; present we fall through to no timeout (with a one-time warning).
(def timeout-bin
  (let [probe (fn [bin]
                (zero? (:exit (sh "sh" "-c" (str "command -v " bin " >/dev/null")))))]
    (cond (probe "timeout")  "timeout"
          (probe "gtimeout") "gtimeout"
          :else nil)))

(when-not timeout-bin
  (println "[diff-clojuredocs] WARN: no `timeout` binary found;"
           "a hung example will block the whole soak."))

(def per-example-timeout-s "5")

(defn- run-mino [tuple]
  (let [script (render-script tuple)
        tmp (str "/tmp/mino-cd-" (now-ms) "-" (rand-int 100000) ".clj")]
    (spit tmp script)
    (let [r (if timeout-bin
              (sh timeout-bin per-example-timeout-s mino-bin tmp)
              (sh mino-bin tmp))]
      (sh "rm" "-f" tmp)
      r)))

;; --- comparison ---

(defn- norm [x]
  (when x (s/trim x)))

(defn- allow-reason
  "Two-tier lookup: exact key first ('ns/var:idx'), then the var-only
   key ('ns/var') which covers all examples of that var. Keeps the
   allowlist compact for the common case (entire var is expected to
   diverge, e.g. all `unchecked-*`)."
  [allow tuple]
  (let [vk (str (:ns tuple) "/" (:var-name tuple))
        ek (str vk ":" (:idx tuple))]
    (or (get allow ek) (get allow vk))))

(defn- compare-one
  "Run one tuple through mino, compare to recorded bb output.
   Returns {:status :pass|:fail|:mino-fail|:allowlisted, ...}."
  [allow key tuple]
  (let [expected (:bb-out (:gt tuple))
        reason (allow-reason allow tuple)
        {:keys [exit out err]} (when-not reason (run-mino tuple))
        actual out]
    (cond
      reason
      {:status :allowlisted :reason reason}

      (not (zero? exit))
      {:status :mino-fail :exit exit :err err :expected expected
       :tuple tuple}

      (= (norm actual) (norm expected))
      {:status :pass}

      :else
      {:status :fail :expected expected :actual actual
       :tuple tuple})))

;; --- main loop ---

(defn- key-of [t]
  (str (:ns t) "/" (:var-name t) ":" (:idx t)))

(defn- assign-indices
  "Number tuples per (ns, var) so the allowlist can pin a specific
   example without depending on the order of the source corpus."
  [tuples]
  (let [counters (atom {})]
    (mapv (fn [t]
            (let [k [(:ns t) (:var-name t)]
                  i (get @counters k 0)]
              (swap! counters update k (fnil inc 0))
              (assoc t :idx i)))
          tuples)))

(defn- capture-failure [seed key result]
  (let [rfile (regression-path seed key)
        tuple (:tuple result)]
    (try
      (spit rfile
            (str ";; Auto-captured clojuredocs divergence at " (now-ms) ".\n"
                 ";; seed=" seed " key=" key "\n"
                 ";; ns=" (:ns tuple) " var=" (:var-name tuple)
                 " idx=" (:idx tuple) "\n"
                 ";;\n"
                 ";; Expected (bb / JVM Clojure):\n"
                 ";;   " (pr-str (:expected result)) "\n"
                 ";; Actual (mino):\n"
                 ";;   " (pr-str (:actual result)) "\n"
                 ";;\n"
                 ";; Preamble (run for side effects):\n"
                 (apply str (map #(str ";;   " % "\n")
                                 (s/split-lines (:preamble-source tuple))))
                 ";; Form:\n"
                 ";;   " (:form-source tuple) "\n"
                 ";;\n"
                 ";; Re-run via load-file on this regression file --\n"
                 ";; the form below reproduces the divergence:\n"
                 "(let [script "
                 (pr-str (str (:preamble-source tuple)
                              "\n(println (pr-str "
                              (:form-source tuple) "))"))
                 "\n      tmp \"/tmp/clojuredocs-replay.clj\"]\n"
                 "  (spit tmp script)\n"
                 "  (let [r (sh (or (getenv \"MINO_BIN\") \"mino/mino\") tmp)]\n"
                 "    (println :mino-out (:out r))\n"
                 "    (println :expected " (pr-str (:expected result)) ")))\n"))
      (catch e (println "WARN: failed to write regression file:" (str e))))
    rfile))

(let [start (now-ms)
      data (load-fixture)
      allow (load-allowlist)
      all-ok (->> (:tuples data)
                  (filter #(= :ok (:status (:gt %))))
                  assign-indices)
      selected (cond
                 (nil? n-examples) all-ok
                 :else (vec (take n-examples (shuffle all-ok))))
      n (count selected)
      _ (println "[diff-clojuredocs] corpus:" (count all-ok)
                 "examples, running" n
                 (if (nil? n-examples) "(soak: all)" "(smoke)"))
      results (atom {:pass 0 :fail 0 :mino-fail 0 :allowlisted 0
                     :failures []})]
  (doseq [t selected]
    (let [k (key-of t)
          r (compare-one allow k t)]
      (case (:status r)
        :pass        (swap! results update :pass inc)
        :allowlisted (swap! results update :allowlisted inc)
        :fail        (do (swap! results #(-> %
                                              (update :fail inc)
                                              (update :failures conj
                                                      {:key k :result r})))
                         (emit-verdict "diff-clojuredocs.divergence"
                                       "fail"
                                       :key k
                                       :expected (:expected r)
                                       :actual (:actual r)))
        :mino-fail   (do (swap! results #(-> %
                                              (update :mino-fail inc)
                                              (update :failures conj
                                                      {:key k :result r})))
                         (emit-verdict "diff-clojuredocs.mino-error"
                                       "fail"
                                       :key k
                                       :exit (:exit r)
                                       :err (when (:err r)
                                              (subs (:err r)
                                                    0 (min 200 (count (:err r)))))
                                       :expected (:expected r))))))
  (let [r @results
        n-bad (+ (:fail r) (:mino-fail r))]
    ;; capture regressions for the first 20 failures so we don't write
    ;; thousands of files when the corpus is newly mis-tuned
    (doseq [{:keys [key result]} (take 20 (:failures r))]
      (capture-failure effective-seed key result))
    (emit-verdict "diff-clojuredocs.summary"
                  (if (zero? n-bad) "pass" "fail")
                  :tested n
                  :pass (:pass r)
                  :fail (:fail r)
                  :mino-fail (:mino-fail r)
                  :allowlisted (:allowlisted r)
                  :seed effective-seed
                  :elapsed (- (now-ms) start))
    (when (pos? n-bad)
      (throw (ex-info (str "diff-clojuredocs: " n-bad " of " n " examples diverged")
                      {:probe "diff-clojuredocs"
                       :fail (:fail r)
                       :mino-fail (:mino-fail r)
                       :n n})))))
