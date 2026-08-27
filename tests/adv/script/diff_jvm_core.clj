;; diff_jvm_core.clj -- mino-vs-JVM-Clojure diff over the curated
;; core probe corpus.
;;
;; Ground truth is tests/adv/fixtures/jvm-core-jvm.edn, captured by
;; tests/adv/jvm_core_capture.clj in ONE real `clojure -M` process.
;; Never babashka: bb preloads namespaces and interns symbols that
;; plain Clojure does not, which once produced a false divergence
;; claim. The JVM fixture is committed, so this probe needs no JVM at
;; run time.
;;
;; The mino side evaluates probes in chunks of ~150 per process. Each
;; chunk script prints one pr-str'd result map per line; the driver
;; parses lines, marks keys it never saw as :mino-timeout (the chunk
;; was killed or crashed). Chunks run under `timeout` with stdin
;; redirected from /dev/null: mino's `sh` is popen-based and the
;; child inherits our stdin, and a probe like (line-seq 1) reads
;; stdin instead of throwing until the whole chunk hangs.
;;
;; Comparison: :ok probes must print byte-identical pr-str output;
;; :error probes must error on both sides (message text is a
;; documented census exclusion). The allowlist (same two-tier
;; exact-then-prefix lookup as diff-clojuredocs) holds probes whose
;; divergence is known; each mino fix shrinks it.
;;
;; Smoke: 60 probes. Soak: all.

(load-file "tests/adv/edge_helpers.clj")
(require '[clojure.string :as s])

(def mino-bin (or (getenv "MINO_BIN") "mino/mino"))

(def corpus-path   "tests/adv/fixtures/jvm-core-probes.edn")
(def jvm-fx-path   "tests/adv/fixtures/jvm-core-jvm.edn")
(def allowlist-path "tests/adv/fixtures/jvm_core_allowlist.edn")

(def n-probes
  (case (:mode cli-opts)
    "soak" nil
    "smoke" 60
    60))

(def chunk-size 150)
(def chunk-timeout-s "60")

(defn- load-edn [path]
  (try
    (read-string (slurp path))
    (catch e
      (println "ERROR: failed to read" path ":" (str e))
      (exit 2))))

(def timeout-bin
  (let [probe (fn [bin]
                (zero? (:exit (sh "sh" "-c" (str "command -v " bin " >/dev/null")))))]
    (cond (probe "timeout")  "timeout"
          (probe "gtimeout") "gtimeout"
          :else nil)))

(when-not timeout-bin
  (println "[diff-jvm-core] WARN: no `timeout` binary; a hung chunk"
           "will block this probe."))

(defn- chunk-script
  "Render the mino script for one chunk: each probe wrapped in
   try/catch printing one result line, then exit 0."
  [probes]
  (apply str
         (map (fn [{:keys [key form]}]
                (str "(try (println (pr-str {:key " (pr-str key)
                     " :status :ok"
                     " :out (pr-str (eval (read-string " (pr-str form) ")))}))"
                     " (catch e"
                     " (println (pr-str {:key " (pr-str key)
                     " :status :error"
                     " :err (str e)}))))\n"))
              probes)))

(defn- run-chunk
  "Run one chunk through a fresh mino process. Returns
   {key {:status ... :out/:err ...}} for keys the chunk reported."
  [probes]
  (let [tmp (str "/tmp/mino-jc-" (now-ms) "-" (rand-int 100000) ".clj")]
    (spit tmp (str (chunk-script probes) "(exit 0)\n"))
    (let [cmd (str "\"" mino-bin "\" " tmp " </dev/null")
          r (if timeout-bin
              (sh timeout-bin chunk-timeout-s "sh" "-c" cmd)
              (sh "sh" "-c" cmd))
          lines (s/split-lines (or (:out r) ""))]
      (sh "rm" "-f" tmp)
      (into {}
            (keep #(try
                     (let [m (read-string %)]
                       (when (and (map? m) (contains? m :key))
                         [(:key m) m]))
                     (catch Throwable e nil))
                  lines)))))

(defn- chunk-groups
  "Split probes into chunk groups. :isolated probes get a process of
   their own; the rest pack chunk-size to a process."
  [probes]
  (let [solo (filter :isolated probes)
        packed (remove :isolated probes)
        groups (when (seq packed)
                 (mapv vec (partition-all chunk-size packed)))]
    (concat groups (mapv vector solo))))

(defn- run-mino-side
  "Run the whole corpus through chunked mino processes; timed-out or
   crashed probes get explicit :mino-timeout status."
  [probes]
  (let [results (into {} (mapcat run-chunk (chunk-groups probes)))]
    (mapv (fn [p]
            (let [k (:key p)]
              [k (or (get results k) {:status :mino-timeout})]))
          probes)))

(defn- allow-reason
  "Two-tier lookup: exact probe key, then a prefix key covering every
   probe beneath it (namespace-level)."
  [allow key]
  (or (get allow key)
      (some (fn [[prefix reason]]
              (when (and (not= prefix key)
                         (s/starts-with? key prefix))
                reason))
            allow)))

(defn- compare-one
  "Compare mino result against JVM ground truth. Pass requires equal
   status and, for :ok probes, byte-identical printed output."
  [allow jvm-rec key mino-rec]
  (let [reason (allow-reason allow key)]
    (cond
      reason
      {:status :allowlisted :reason reason}

      (nil? jvm-rec)
      {:status :fixture-missing}

      (= :timeout (:status jvm-rec))
      {:status :jvm-timeout}

      ;; both sides erroring is parity; message text is excluded
      (= :error (:status jvm-rec))
      (if (= :error (:status mino-rec))
        {:status :pass}
        {:status :fail :actual (:out mino-rec) :jvm jvm-rec})

      (not= :ok (:status mino-rec))
      {:status :mino-fail :mino (:status mino-rec)
       :err (:err mino-rec) :jvm jvm-rec}

      (= (:out mino-rec) (:out jvm-rec))
      {:status :pass}

      :else
      {:status :fail :actual (:out mino-rec) :jvm (:out jvm-rec)})))

(defn- safe-key
  "Filesystem-safe form of a probe key. Char-by-char because mino's
   clojure.string/replace does not accept regex patterns."
  [k]
  (apply str
         (map (fn [c]
                (let [ch (str c)]
                  (if (or (and (>= (compare ch "a") 0) (<= (compare ch "z") 0))
                          (and (>= (compare ch "A") 0) (<= (compare ch "Z") 0))
                          (and (>= (compare ch "0") 0) (<= (compare ch "9") 0))
                          (= ch ".") (= ch "-") (= ch "_"))
                    ch "_")))
              k)))

(defn- capture-failure
  [key result corpus]
  (let [probe (some #(when (= (:key %) key) %) corpus)
        rfile (str "tests/adv/regressions/jvm-core-" (now-ms) "-"
                   (safe-key key) ".clj")]
    (try
      (spit rfile
            (str ";; Auto-captured jvm-core divergence at " (now-ms) ".\n"
                 ";; key=" key "\n"
                 ";; form=" (:form probe) "\n"
                 ";; jvm=" (pr-str (:jvm result)) "\n"
                 ";; mino=" (pr-str (:actual result))
                 (when (:err result) (str "\n;; mino-err=" (pr-str (:err result))))
                 "\n"))
      (catch e (println "WARN: failed to write regression file:" (str e))))
    rfile))

(let [start (now-ms)
      corpus (load-edn corpus-path)
      jvm-fx (:outputs (load-edn jvm-fx-path))
      allow (try (read-string (slurp allowlist-path)) (catch e {}))
      selected (if (or (nil? n-probes) (>= n-probes (count corpus)))
                 corpus
                 (vec (take n-probes corpus)))
      _ (println "[diff-jvm-core] corpus:" (count corpus)
                 "probes, running" (count selected)
                 (if (nil? n-probes) "(soak: all)" "(smoke)"))
      mino-side (run-mino-side selected)
      results (atom {:pass 0 :fail 0 :mino-fail 0 :allowlisted 0
                     :failures []})]
  (doseq [[k mrec] mino-side]
    (let [r (compare-one allow (get jvm-fx k) k mrec)]
      (case (:status r)
        :pass        (swap! results update :pass inc)
        :allowlisted (swap! results update :allowlisted inc)
        :fail        (do (swap! results #(-> % (update :fail inc)
                                             (update :failures conj
                                                     {:key k :result r})))
                         (emit-verdict "diff-jvm-core.divergence" "fail"
                                       :key k
                                       :jvm (pr-str (:jvm r))
                                       :actual (:actual r)))
        :mino-fail   (do (swap! results #(-> % (update :mino-fail inc)
                                             (update :failures conj
                                                     {:key k :result r})))
                         (emit-verdict "diff-jvm-core.mino-error" "fail"
                                       :key k
                                       :mino-status (:mino r)
                                       :err (when (:err r)
                                              (subs (:err r) 0
                                                    (min 200 (count (:err r)))))))
        ;; :fixture-missing / :jvm-timeout are corpus bugs, loud by default
        (do (swap! results #(-> % (update :fail inc)
                                (update :failures conj {:key k :result r})))
            (emit-verdict "diff-jvm-core.corpus-bug" "fail"
                          :key k :status (:status r))))))
  (let [r @results
        n-bad (+ (:fail r) (:mino-fail r))]
    (doseq [{:keys [key result]} (take 20 (:failures r))]
      (capture-failure key result corpus))
    (emit-verdict "diff-jvm-core.summary"
                  (if (zero? n-bad) "pass" "fail")
                  :tested (count selected)
                  :pass (:pass r)
                  :fail (:fail r)
                  :mino-fail (:mino-fail r)
                  :allowlisted (:allowlisted r)
                  :elapsed (- (now-ms) start))
    (when (pos? n-bad)
      (throw (ex-info (str "diff-jvm-core: " n-bad " of "
                           (count selected) " probes diverged")
                      {:probe "diff-jvm-core"
                       :fail (:fail r)
                       :mino-fail (:mino-fail r)})))))
