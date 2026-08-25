(require "tests/test")
(require '[clojure.string :as str])

;; HTML/XML E2E (html-xml campaign p6t3, design A-5): binary-level
;; integration. Unlike the path/time/digest batteries (which run
;; inside the binary under test and shell out only for ORACLES),
;; this suite shells out to the REAL ./mino binary as a CHILD and
;; checks what a script actually sees: parse the 1MB fixture,
;; select through it, serialize it, convert it to hiccup, and read
;; the strict-XML mix -- through the binary's own process boundary,
;; exit code and stdout. The fixture generators come from the
;; pinned mino submodule (tests.html-fixture / tests.xml-fixture),
;; so the child runs with cwd mino/ where those namespaces resolve.

(def ^:private e2e-child-script
  "The script the child binary runs: the full read/select/write
  surface over both campaign fixtures, printing one key=value line
  per measurement plus a final ok."
  (str "(require '[mino.html :as html])\n"
       "(require '[mino.html.select :as sel])\n"
       "(require '[tests.html-fixture :as hfix])\n"
       "(require '[clojure.xml :as xml])\n"
       "(require '[tests.xml-fixture :as xfix])\n"
       "(let [doc (hfix/html-fixture-doc)\n"
       "      tree (html/parse doc)\n"
       "      ps (count (sel/select (sel/tag :p) tree))\n"
       "      links (count (sel/select (sel/tag :a) tree))\n"
       "      out (html/to-html tree)\n"
       "      hic (html/as-hiccup tree)\n"
       "      xdoc (xfix/xml-fixture-doc)\n"
       "      xtree (xml/parse xdoc)\n"
       "      deps (count (sel/select (sel/tag :dependency) xtree))]\n"
       "  (println \"html-bytes\" (count doc))\n"
       "  (println \"p-elements\" ps)\n"
       "  (println \"a-elements\" links)\n"
       "  (println \"serialized-bytes\" (count out))\n"
       "  (println \"hiccup-forms\" (count hic))\n"
       "  (println \"xml-bytes\" (count xdoc))\n"
       "  (println \"xml-root\" (name (:tag xtree)))\n"
       "  (println \"xml-children\" (count (:content xtree)))\n"
       "  (println \"xml-deps\" deps)\n"
       "  (println \"ok\"))\n"))

(def ^:private e2e-error-script
  "A second child: the strict-XML error contract through the binary.
  An undefined entity must throw a positioned ex-info; the child
  catches it, prints the shape, and still exits 0 (the throw shape
  is the assertion, not the exit)."
  (str "(require '[clojure.xml :as xml])\n"
       "(try (xml/parse \"<a>&nosuch;</a>\")\n"
       "  (println \"no-throw\")\n"
       "  (catch e\n"
       "    (let [d (ex-data e)]\n"
       "      (println \"xml-error-kind\" (:kind d))\n"
       "      (println \"xml-error-code\" (:code d))\n"
       "      (println \"xml-error-line\" (:line (:location d)))\n"
       "      (println \"xml-error-col\" (:col (:location d))))))\n"
       "(println \"done\")\n"))

(def ^:private e2e-script-path "/tmp/mino_html_xml_e2e_child.clj")
(def ^:private e2e-err-script-path "/tmp/mino_html_xml_e2e_err_child.clj")

(defn- e2e-run-child
  "Runs the child binary on the script with cwd mino/ (where
  tests.html-fixture and tests.xml-fixture resolve). Honors the
  MINO_BIN override the CI lanes set (an absolute path, valid from
  any cwd); defaults to the submodule's own build, relative to the
  mino/ cwd the child gets. Returns the {:exit :out} map."
  [script-path]
  (let [bin (or (getenv "MINO_BIN") "./mino")]
    (sh "sh" "-c"
        (str "cd mino && " bin " " script-path " 2>&1"))))

(defn- e2e-lines
  "Child stdout as a map of key -> value string."
  [out]
  (reduce
    (fn [m line]
      (let [kv (str/split line #" ")]
        (if (= 2 (count kv))
          (assoc m (nth kv 0) (nth kv 1))
          m)))
    {}
    (str/split-lines out)))

(deftest html-xml-e2e-full-pipeline-through-the-binary
  ;; The real binary, one child process, the whole campaign surface:
  ;; tolerant parse, select, serialize, as-hiccup over the 1MB page
  ;; mix; strict parse and select over the 1MB pom/rss/svg mix.
  ;; Deterministic values are pinned exactly (the generators are
  ;; seeded arithmetic and the submodule pin fixes the reader).
  (spit e2e-script-path e2e-child-script)
  (let [r (e2e-run-child e2e-script-path)
        m (e2e-lines (str (:out r)))]
    (is (zero? (:exit r))
        (str "child exited " (:exit r) "; output:\n" (:out r)))
    (is (= "1026904" (get m "html-bytes"))
        "fixture must be the pinned megabyte page mix")
    (is (= "1555" (get m "p-elements")) "p-element count")
    (is (= "1295" (get m "a-elements")) "a-element count")
    (is (= "1018872" (get m "serialized-bytes")) "serialized size")
    (is (= "2" (get m "hiccup-forms"))
        "document converts to doctype string plus html form")
    (is (= "1019865" (get m "xml-bytes")) "xml fixture size")
    (is (= "catalog" (get m "xml-root")) "xml root tag")
    (is (= "2905" (get m "xml-children")) "catalog child count")
    (is (= "968" (get m "xml-deps")) "dependency elements")
    (is (str/includes? (str (:out r)) "\nok") "child completed its run")))

(deftest html-xml-e2e-error-contract-through-the-binary
  ;; Strict XML's positioned error crosses the process boundary
  ;; intact: kind, code, and 1-based line/col.
  (spit e2e-err-script-path e2e-error-script)
  (let [r (e2e-run-child e2e-err-script-path)
        m (e2e-lines (str (:out r)))]
    (is (zero? (:exit r))
        (str "child exited " (:exit r) "; output:\n" (:out r)))
    (is (= ":xml/parse" (get m "xml-error-kind")))
    (is (= ":undefined-entity" (get m "xml-error-code")))
    (is (= "1" (get m "xml-error-line")))
    (is (= "4" (get m "xml-error-col"))
        "position at the reference's ampersand (1-based)")
    (is (str/includes? (str (:out r)) "\ndone"))))

(run-tests-and-exit)
