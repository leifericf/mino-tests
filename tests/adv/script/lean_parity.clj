;; T5 -- mino vs mino-lean parity
;;
;; Anchor bugs: --help / --version under mino-lean were identical (v0.252.1).
;; Fix: --version distinguishes the lean variant. Also: a script's stdout
;; should be identical between the JIT-enabled binary and mino-lean for
;; any program that doesn't introspect its own runtime mode.

(load-file "tests/adv/edge_helpers.clj")
(load-file "tests/adv/invariants.clj")
(require '[clojure.string :as s])

(def mino-bin (or (getenv "MINO_BIN") "mino/mino"))
(def lean-bin (or (getenv "MINO_LEAN_BIN") "mino/mino-lean"))

(defn- probe-help-version-distinct []
  (when (file-exists? lean-bin)
    (let [help-lean    (sh lean-bin "--help")
          version-lean (sh lean-bin "--version")
          ok (and (= 0 (:exit help-lean))
                  (= 0 (:exit version-lean))
                  (not= (:out help-lean) (:out version-lean)))]
      (emit-verdict "T5.lean-help-version-distinct"
                    (if ok "pass" "fail")
                    :help-len    (count (or (:out help-lean) ""))
                    :version-len (count (or (:out version-lean) ""))))))

(defn- probe-version-includes-lean []
  (when (file-exists? lean-bin)
    (let [v  (sh lean-bin "--version")
          ok (and (= 0 (:exit v))
                  (or (s/includes? (or (:out v) "") "lean")
                      (s/includes? (or (:out v) "") "Lean")
                      (s/includes? (or (:out v) "") "no-jit")
                      (s/includes? (or (:out v) "") "without")))]
      (emit-verdict "T5.lean-version-mentions-lean"
                    (if ok "pass" "fail")
                    :version (:out v)))))

(defn- probe-stdout-quad []
  ;; A deterministic non-JIT-introspective program should produce
  ;; byte-identical stdout across mino auto/on/off + mino-lean.
  (when (file-exists? lean-bin)
    (let [tmp "/tmp/mino-tests-stdout-quad.clj"
          _   (spit tmp "(println (reduce + (range 10)))\n(println :done)\n")
          a   (sh mino-bin "--jit=auto" tmp)
          o   (sh mino-bin "--jit=on"   tmp)
          f   (sh mino-bin "--jit=off"  tmp)
          l   (sh lean-bin tmp)
          ok  (and (= (:out a) (:out o))
                   (= (:out a) (:out f))
                   (= (:out a) (:out l)))]
      (sh "rm" "-f" tmp)
      (emit-verdict "T5.stdout-quad-byte-identical"
                    (if ok "pass" "fail")
                    :out-len (count (or (:out a) ""))))))

(probe-help-version-distinct)
(probe-version-includes-lean)
(probe-stdout-quad)
