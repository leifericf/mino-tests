(require "tests/test")
(require '[clojure.string :as str])

;; Zip/gzip binary-level E2E (compression-zip campaign p6t2, ADR 29):
;; the UTC-timestamp decision's real proof. A child process rebuilds
;; the campaign's frozen write-golden archive (the entries vector is
;; mino's own, frozen at the p4t2 impl commit; the expectation lives
;; in the submodule at tests/fixtures/zip/write_golden.edn) and a
;; fixed gzip member round trip, printing key=value verdicts. The
;; parent runs that child through the REAL pinned binary -- the
;; plain-Makefile bootstrap build of the submodule, the release
;; checkout path -- once per timezone (unset, UTC, New York, Tokyo,
;; Kiritimati +14), and asserts every run exits 0 with byte-
;; IDENTICAL stdout: the archive bytes, member reads, and gzip
;; recompression cannot vary with the runner's timezone (D5; a
;; vendor localtime path would shift the DOS minute field and flip
;; the archive sha across TZs).
;;
;; Byte-identity to the dev build is pinned two ways: the child
;; asserts the produced archive sha EQUALS the golden sha frozen by
;; the dev build at p4t2 (a pinned-build writer that diverges fails
;; here, not just in mino's own suite), and when a second binary is
;; locatable the same child runs under it and its stdout must match
;; the released build's byte for byte: mino/mino-lean (the
;; distributable build CI produces) and ../mino/mino (an adjacent
;; dev checkout; local dev flow). Absent binaries skip LOUDLY (the
;; R4 self-skipping rule), never silently.

(def ^:private ze-script-path "/tmp/mino_zip_e2e_child.clj")

(def ^:private ze-child-script
  "The child's whole run: rebuild the frozen archive (default and
  forced-zip64 shapes), round-trip every member, decode and
  recompress the fixed gzip member. Every print is derived from
  fixed inputs -- no wall clock, no randomness -- so identical
  behavior means identical stdout."
  (str "(require '[clojure.edn :as edn])\n"
       "(let [entries [{:name \"hello.txt\" :data (byte-array (map int \"hello zip write side\\n\"))}\n"
       "              {:name \"dir/nested.bin\" :data (byte-array (range 256)) :level 9}\n"
       "              {:name \"stored.txt\" :data (byte-array (repeat 300 65)) :method :store}\n"
       "              {:name \"empty.txt\" :data (byte-array 0)}\n"
       "              {:name \"caf\\u00e9.txt\" :data (byte-array (map int \"unicode name\"))\n"
       "               :mtime 1718454896 :comment \" Caf\\u00e9 comment\"}\n"
       "              {:name \"dir/\" :data (byte-array 0)}\n"
       "              {:name \"canary.txt\" :data (byte-array (map int \"mtime one\")) :mtime 1}]\n"
       "      golden (edn/read-string (slurp \"tests/fixtures/zip/write_golden.edn\"))\n"
       "      ar (zip-write entries)\n"
       "      ar64 (zip-write entries {:zip64 true})\n"
       "      names (mapv :name entries)\n"
       "      roundtrip (fn [a]\n"
       "                  (and (= names (mapv :name (zip-entries a)))\n"
       "                       (loop [i 0]\n"
       "                         (or (= i (count entries))\n"
       "                             (and (= (:data (nth entries i))\n"
       "                                     (zip-read a (:name (nth entries i))))\n"
       "                                  (recur (inc i)))))))\n"
       "      gz (base64-decode (base64-encode (slurp \"tests/fixtures/gzip/hello.gz\")))\n"
       "      payload (gzip-decompress gz)\n"
       "      re (gzip-compress payload {:mtime 315532800 :name \"hello.gz\"})]\n"
       "  (println \"archive-sha-match\"\n"
       "           (= (:sha256 golden) (hex-encode (sha256 ar))))\n"
       "  (println \"archive64-sha-match\"\n"
       "           (= (:zip64-sha256 golden) (hex-encode (sha256 ar64))))\n"
       "  (println \"roundtrip-ok\" (and (roundtrip ar) (roundtrip ar64)))\n"
       "  (println \"gzip-decode-bytes\" (count payload))\n"
       "  (println \"gzip-recompress-sha\" (hex-encode (sha256 re)))\n"
       "  (println \"gzip-roundtrip-ok\" (= payload (gzip-decompress re)))\n"
       "  (println \"done\"))\n"))

(def ^:private ze-tz-prefixes
  "One child run per timezone: unset (env -u), UTC, two DST-shifting
  western/eastern zones, and the +14 extreme. Every zoneinfo name
  ships on the CI hosts (ubuntu/macos) and the dev mac."
  ["env -u TZ" "TZ=UTC" "TZ=America/New_York" "TZ=Asia/Tokyo"
   "TZ=Pacific/Kiritimati"])

(defn- ze-bins
  "The binaries under test: the pinned submodule's bootstrap build
  (the released shape; MINO_BIN override honored exactly like the
  html e2e), plus mino-lean and the adjacent dev checkout when
  present -- each announced, each skipped loudly when absent."
  []
  (into [["released" (or (getenv "MINO_BIN") "./mino")]]
        (filterv #(nth % 1)
                 [["lean" (if (file-exists? "mino/mino-lean")
                            "./mino-lean"
                            (do (println "zip-e2e: mino/mino-lean absent"
                                         "-- lean comparison skipped")
                                nil))]
                  ["dev" (if (file-exists? "../mino/mino")
                           "../mino/mino"
                           (do (println "zip-e2e: ../mino/mino absent"
                                        "-- adjacent-dev comparison skipped")
                               nil))]])))

(defn- ze-run
  "One child run: binary under one TZ prefix, cwd mino/ so the
  submodule fixtures resolve. Returns {:exit :out}."
  [bin tz]
  (sh "sh" "-c"
      (str "cd mino && " tz " " bin " " ze-script-path " 2>&1")))

(deftest zip-e2e-round-trip-is-tz-and-build-invariant
  ;; The pinned binary must rebuild the dev-frozen archive bytes in
  ;; every timezone, and every build of the same pin must agree byte
  ;; for byte. The :mtime 1 canary entry means a NOW-stamping writer
  ;; flips the archive sha; a localtime-path writer flips it across
  ;; the TZ matrix.
  (spit ze-script-path ze-child-script)
  (let [bins (ze-bins)
        pairs (vec (reduce
                     (fn [acc [label bin]]
                       (into acc
                             (mapv (fn [tz] [label tz (ze-run bin tz)])
                                   ze-tz-prefixes)))
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
    (is (str/includes? out "archive-sha-match true")
        "the released build reproduced the dev-frozen archive sha")
    (is (str/includes? out "archive64-sha-match true")
        "the forced-zip64 sha reproduced too")
    (is (str/includes? out "roundtrip-ok true")
        "every member of both archives round-trips")
    (is (str/includes? out "gzip-roundtrip-ok true")
        "the fixed gzip member decodes and recompresses cleanly")
    (is (str/includes? out "\ndone") "the child completed its run")))

(run-tests-and-exit)
