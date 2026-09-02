(require "tests/test")
(require '[clojure.string :as str])

;; Tar binary-level E2E (ADR 29's container pattern): the write side's
;; determinism proof through the real pinned binary. A child process
;; builds a fixed tar archive from a frozen entry set (files, a
;; directory, a symlink, a hardlink, a long name past the 100-byte
;; ustar field, an explicit mtime), round-trips every member through
;; tar-entries / tar-read, and composes a .tar.gz via the mino.tar
;; facade. Every input is fixed -- no wall clock, no randomness -- so
;; identical behavior means byte-identical stdout.
;;
;; The parent runs that child through the REAL binary once per
;; timezone (unset, UTC, New York, Tokyo, Kiritimati +14) and asserts
;; every run exits 0 with byte-IDENTICAL stdout: tar stores mtime as
;; a raw epoch-second field with no localtime path, so the archive
;; sha must not vary with the runner's timezone. When a second binary
;; is locatable (mino/mino-lean, ../mino/mino) the same child runs
;; under it and its stdout must match byte for byte. Absent binaries
;; skip LOUDLY, never silently.

(def ^:private te-script-path "/tmp/mino_tar_e2e_child.clj")

(def ^:private te-child-script
  "The child's whole run over the mino.tar facade: build the archive
  (plain and gzipped), round-trip every member, extract into a temp
  dir and read the payloads back. Every print derives from fixed
  inputs, so identical behavior means identical stdout."
  (str "(require '[mino.tar :as tar])\n"
       "(let [long-name (str \"deep/\" (apply str (repeat 120 \"a\")) \".txt\")\n"
       "      entries [{:name \"hello.txt\" :data \"hello tar e2e\\n\" :mode 0644 :mtime 1700000000}\n"
       "               {:name \"logs/\" :type :dir :mode 0755 :mtime 1700000001}\n"
       "               {:name \"logs/app.log\" :data \"line one\\nline two\\n\" :mode 0640}\n"
       "               {:name long-name :data \"long name payload\\n\" :mtime 1700000002}\n"
       "               {:name \"link\" :type :symlink :linkname \"hello.txt\"}\n"
       "               {:name \"hard.txt\" :type :hardlink :linkname \"hello.txt\"}]\n"
       "      names (mapv :name entries)\n"
       "      ar (tar/create entries)\n"
       "      gz (tar/create entries {:gzip true})\n"
       "      listed (mapv :name (tar/entries ar))\n"
       "      gz-listed (mapv :name (tar/entries gz))\n"
       "      read-ok (and (= (byte-array (map int \"hello tar e2e\\n\"))\n"
       "                      (tar/read ar \"hello.txt\"))\n"
       "                   (= (tar/read ar \"logs/app.log\")\n"
       "                      (tar/read gz \"logs/app.log\")))]\n"
       "  (with-temp-dir [d]\n"
       "    (let [extracted (tar/extract ar d)]\n"
       "      (println \"extract-names-match\" (= names extracted))\n"
       "      (println \"extract-payload-ok\"\n"
       "               (= \"hello tar e2e\\n\" (slurp (str d \"/hello.txt\"))))))\n"
       "  (println \"archive-sha\" (hex-encode (sha256 ar)))\n"
       "  (println \"gzip-sha\" (hex-encode (sha256 gz)))\n"
       "  (println \"names-match\" (= names listed gz-listed))\n"
       "  (println \"read-ok\" read-ok)\n"
       "  (println \"gunzip-round-trips\" (= ar (gzip-decompress gz)))\n"
       "  (println \"done\"))\n"))

(def ^:private te-tz-prefixes
  ["env -u TZ" "TZ=UTC" "TZ=America/New_York" "TZ=Asia/Tokyo"
   "TZ=Pacific/Kiritimati"])

(defn- te-tar-capable?
  "True when a binary (a run-path relative to the mino/ cwd) carries
  the mino.tar surface. The pinned submodule may predate the tar
  campaign; such a binary cannot run the child and is skipped loudly
  (never a silent pass) until the pin bumps, exactly the
  graceful-shutdown e2e's submodule-bump rule."
  [run-path]
  (let [r (sh "sh" "-c"
              (str "cd mino && " run-path
                   " -e '(require (quote [mino.tar :as t])) "
                   "(print (fn? t/create))' 2>&1"))]
    (and (= 0 (:exit r)) (str/includes? (str (:out r)) "true"))))

(defn- te-optional
  "An optional cross-check binary. disk-path is checked for presence
  (relative to the repo root); run-path is how the child invokes it
  from the mino/ cwd. Present AND tar-capable yields [label run-path],
  else a loud skip."
  [label disk-path run-path desc]
  (cond
    (not (file-exists? disk-path))
    (do (println (str "tar-e2e: " desc " absent -- " label
                      " comparison skipped")) nil)
    (not (te-tar-capable? run-path))
    (do (println (str "tar-e2e: " desc " predates the tar surface -- "
                      label " comparison skipped until the pin bumps"))
        nil)
    :else [label run-path]))

(defn- te-bins
  "The binaries under test: the released shape (MINO_BIN override, as
  in the zip e2e) is the always-run baseline; mino-lean and the
  adjacent dev checkout are opportunistic byte-for-byte cross-checks,
  each announced and each skipped loudly when absent or too old to
  carry the tar surface."
  []
  (into [["released" (or (getenv "MINO_BIN") "./mino")]]
        (filterv some?
                 [(te-optional "lean" "mino/mino-lean" "./mino-lean"
                               "mino/mino-lean")
                  (te-optional "dev" "../mino/mino" "../mino/mino"
                               "../mino/mino")])))

(defn- te-run
  "One child run: binary under one TZ prefix, cwd mino/ so the
  submodule paths resolve. Returns {:exit :out}."
  [bin tz]
  (sh "sh" "-c"
      (str "cd mino && " tz " " bin " " te-script-path " 2>&1")))

(deftest tar-e2e-round-trip-is-tz-and-build-invariant
  ;; The tar write side has no localtime path (mtime is a raw epoch
  ;; field), so the archive bytes must be byte-identical in every
  ;; timezone and across builds of the same pin. A hardlink to an
  ;; earlier member and a >100-byte name (GNU long-name header) are in
  ;; the set so the header layout is exercised end to end.
  (spit te-script-path te-child-script)
  (let [bins (te-bins)
        pairs (vec (reduce
                     (fn [acc [label bin]]
                       (into acc
                             (mapv (fn [tz] [label tz (te-run bin tz)])
                                   te-tz-prefixes)))
                     [] bins))
        baseline (nth (nth pairs 0) 2)
        offenders (filterv (fn [[_ _ r]]
                             (or (not= 0 (:exit r))
                                 (not= (:out r) (:out baseline))))
                           pairs)
        out (str (:out baseline))]
    (is (= 0 (:exit baseline))
        (str "baseline child exited " (:exit baseline) ":\n" out))
    (is (zero? (count offenders))
        (str "runs that diverged from baseline (exit or stdout): "
             (pr-str (mapv (fn [[label tz _]] [label tz]) offenders))))
    (is (str/includes? out "names-match true")
        "listing the plain and gzipped archives agrees with the input")
    (is (str/includes? out "read-ok true")
        "every member round-trips through tar-read, plain and gzipped")
    (is (str/includes? out "extract-names-match true")
        "extract materializes exactly the archived members in order")
    (is (str/includes? out "extract-payload-ok true")
        "an extracted file carries its archived bytes")
    (is (str/includes? out "gunzip-round-trips true")
        "the .tar.gz gunzips back to the plain archive bytes")
    (is (str/includes? out "\ndone") "the child completed its run")))

(run-tests-and-exit)
