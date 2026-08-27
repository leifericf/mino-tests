(require "tests/test")
(require '[clojure.string :as str])

;; Path/glob E2E: cross-checks the path prims against EXTERNAL tool
;; oracles, which the mino repo forbids in its own suite (C + mino
;; only there). Oracles: find(1) for discovery sets (piped through
;; LC_ALL=C sort for byte order; hidden entries excluded where
;; mino's default-hidden policy demands it) and python3's glob
;; module for ** recursion and the dotfile policy (python hides
;; dotfiles from wildcards exactly like mino does). Tools missing
;; from PATH skip their lane loudly.

(def ^:private find-available?
  (try (sh "find" "/tmp" "-maxdepth" "0") true (catch Throwable e false)))

(def ^:private python-available?
  (try (sh "python3" "-c" "import glob") true (catch Throwable e false)))

(def root "/tmp/mino-path-e2e")

(defn- build-tree []
  (rm-rf root)
  (mkdir-p (str root "/src/deep"))
  (mkdir-p (str root "/vendor"))
  (mkdir-p (str root "/.hidden-dir"))
  (spit (str root "/core.clj") "1")
  (spit (str root "/util.clj") "2")
  (spit (str root "/notes.md") "3")
  (spit (str root "/.dotfile") "4")
  (spit (str root "/src/inner.clj") "5")
  (spit (str root "/src/deep/leaf.clj") "6")
  (spit (str root "/vendor/lib.js") "7")
  (spit (str root "/.hidden-dir/secret.clj") "8"))

(def ^:private empty-sentinel "<EMPTY>")

(defn- python-list [xs]
  "Renders a python list literal: pr-str of a mino vector is
  space-separated, and python concatenates adjacent string
  literals, so the commas are explicit."
  (str "[" (str/join ", " (map pr-str xs)) "]"))

(defn- oracle-lines [raw]
  "Splits oracle stdout on newlines; every oracle line is non-empty
  (empty answers print the sentinel) so the split is unambiguous."
  (mapv #(if (= empty-sentinel %) "" %) (str/split (str/trim raw) #"\n")))

(defn- find-sorted [& args]
  "Runs find with args, pipes through LC_ALL=C sort, answers the
  non-empty lines as a vector. Hidden entries stay find's business:
  callers exclude them where mino's default-hidden policy applies."
  (let [quoted (str/join " " (map (fn [a] (str "'" a "'")) args))
        raw (sh! "sh" "-c" (str "find " quoted " | LC_ALL=C sort"))]
    (vec (filter #(not= "" %) (str/split (str/trim raw) #"\n")))))

(defn- rel [paths]
  "Strips the root prefix and the following separator."
  (mapv (fn [p] (subs p (inc (count root)))) paths))

(defn- no-hidden-find-args []
  ["-not" "-path" (str root "/.hidden-dir")
   "-not" "-path" (str root "/.hidden-dir/*")
   "-not" "-name" ".*"])

(deftest star-agrees-with-find
  (if find-available?
    (do
      (build-tree)
      (is (= (apply find-sorted
                    (concat [(str root) "-maxdepth" "1" "-name" "*.clj"]
                            (no-hidden-find-args)))
             (glob "*.clj" root)))
      (is (= (apply find-sorted
                    (concat [(str root) "-name" "*.clj"]
                            (no-hidden-find-args)))
             (glob "**/*.clj" root)))
      (is (= (apply find-sorted
                    (concat [(str root) "-name" "*.md"]
                            (no-hidden-find-args)))
             (glob "**/*.md" root)))
      (rm-rf root))
    (is true "find(1) unavailable; lane skipped")))

(deftest sortedness-agrees-with-byte-order-sort
  (if find-available?
    (do
      (build-tree)
      ;; every top-level entry (hidden included) in byte order
      (is (= (find-sorted (str root) "-mindepth" "1" "-maxdepth" "1")
             (glob "*" root {:match-dot true})))
      (rm-rf root))
    (is true "find(1) unavailable; lane skipped")))

(deftest trailing-doublestar-agrees-with-find
  (if find-available?
    (do
      (build-tree)
      ;; a trailing ** is every visible descendant: files and dirs
      (is (= (apply find-sorted (concat [(str root) "-mindepth" "1"]
                                        (no-hidden-find-args)))
             (glob "**" root)))
      (rm-rf root))
    (is true "find(1) unavailable; lane skipped")))

;;; python3 glob oracle: ** recursion and the dotfile policy

(defn- python-glob [pattern]
  (let [code (str "import glob\n"
                  "for p in sorted(glob.glob("
                  (pr-str pattern)
                  ", root_dir="
                  (pr-str root)
                  ", recursive=True)):\n"
                  "    print(p)\n")
        raw (sh! "python3" "-c" code)]
    (vec (filter #(not= "" %) (str/split (str/trim raw) #"\n")))))

(deftest doublestar-agrees-with-python-glob
  (if python-available?
    (do
      (build-tree)
      (is (= (python-glob "**/*.clj")
             (rel (glob "**/*.clj" root))))
      (is (= (python-glob "*.clj")
             (rel (glob "*.clj" root))))
      ;; python hides dotfiles from wildcards, exactly like mino
      (is (= [] (glob "*.dotfile" root))
          "hidden file invisible to a wildcard by default")
      ;; and both reveal dot entries through a literal-dot segment
      (is (= [".dotfile" ".hidden-dir"] (python-glob ".*")))
      (is (= [".dotfile" ".hidden-dir"] (rel (glob ".*" root))))
      (rm-rf root))
    (is true "python3 unavailable; lane skipped")))

;;; python3 os.path oracle: the pure algebra

(deftest split-ext-agrees-with-os-path
  (if python-available?
    (let [cases ["core.clj" "a.tar.gz" ".bashrc" "README"
                 "src/inner.clj" "index." "" "a.b/c"]
          code (str "import os\n"
                    "for c in "
                    (python-list cases)
                    ":\n"
                    "    st, ext = os.path.splitext(c)\n"
                    "    print(st or '<EMPTY>')\n"
                    "    print(ext or '<EMPTY>')\n")
          raw (sh! "python3" "-c" code)
          lines (oracle-lines raw)]
      (doseq [[c [st ext]] (mapv vector cases (partition 2 lines))]
        (is (= [st (if (= "" ext) nil ext)]
               (path-split-ext c))
            (str "case " c))))
    (is true "python3 unavailable; lane skipped")))

(deftest basename-dirname-agree-with-python
  (if python-available?
    (let [cases ["/a/b/c.txt" "/" "/a"]
          code (str "import os\n"
                    "for c in " (python-list cases) ":\n"
                    "    print(os.path.basename(c) or '<EMPTY>')\n"
                    "    print(os.path.dirname(c) or '<EMPTY>')\n")
          raw (sh! "python3" "-c" code)
          lines (oracle-lines raw)]
      (doseq [[c [b d]] (mapv vector cases (partition 2 lines))]
        (is (= b (path-basename c)) (str "basename " c))
        (is (= d (path-dirname c)) (str "dirname " c))))
    (is true "python3 unavailable; lane skipped")))

(deftest basename-dirname-divergences-pinned
  ;; Deliberate, documented (ADR 22): where python answers the
  ;; empty string, mino answers the normalized form ("." rather
  ;; than ""), and basename takes the raw last non-empty segment
  ;; (node parity) where python's basename of "a/b/" is "".
  (are [expected c which] (= expected (which c))
    "c.txt" "c.txt"   path-basename
    "."     "c.txt"   path-dirname
    "."     ""        path-basename
    "."     ""        path-dirname
    "b"     "a/b/"    path-basename
    "a"     "a/b/"    path-dirname))

(run-tests-and-exit)
