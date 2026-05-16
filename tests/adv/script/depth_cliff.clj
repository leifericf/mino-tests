;; T6 -- bounded recursion / nesting depth
;;
;; Anchor: the reader SIGSEGV (v0.252.2) where deeply nested input
;; could crash before the new MRE011 / MINO_READER_MAX_DEPTH=1024 cap
;; landed. Probe: build progressively deeper nested forms and confirm
;; we hit a classified error (MRE011 or similar) and not a SIGSEGV.

(load-file "tests/adv/edge_helpers.clj")
(load-file "tests/adv/invariants.clj")

(defn- nested-form
  "Construct a string '((((...))))' of depth N."
  [n]
  (str (apply str (repeat n "(")) (apply str (repeat n ")"))))

;; mino's catch binding receives the diagnostic envelope map directly
;; (:mino/kind / :mino/code / ...); use it as-is, not ex-data.

(defn- probe-reader-deep-classifies []
  ;; Beyond MINO_READER_MAX_DEPTH (1024), the reader should produce a
  ;; classified error. We probe a few depths around the boundary.
  (doseq [n [1000 1024 1500 2000]]
    (let [src (nested-form n)
          e   (try (read-string src) nil
                   (catch e e))
          kind (when (map? e) (:mino/kind e))]
      (emit-verdict (str "T6.reader-deep-" n)
                    (cond
                      (nil? e)              "pass"    ;; under cap, no error
                      (some? kind)          "pass"    ;; classified error
                      :else                 "fail")
                    :kind (str kind)
                    :code (str (when (map? e) (:mino/code e)))))))

(defn- probe-eval-deep-classifies []
  ;; eval-side: deeply nested form should also classify, not segfault.
  (let [depth 600
        src (nested-form depth)
        e   (try (eval (read-string src))
                 nil
                 (catch e e))
        kind (when (map? e) (:mino/kind e))
        ok  (or (nil? e) (some? kind))]
    (emit-verdict (str "T6.eval-nested-" depth)
                  (if ok "pass" "fail")
                  :kind (str kind))))

(probe-reader-deep-classifies)
(probe-eval-deep-classifies)
