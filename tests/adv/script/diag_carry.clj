;; T1 -- diag state leak across loads
;;
;; Anchor: the reader_col bug (v0.252.1) where reader_col was preserved
;; across mino_eval_string_inner calls, so a failure at col 87 would
;; report subsequent failures at col 87 too. This probe causes
;; consecutive parse failures and asserts they classify cleanly --
;; same envelope shape, distinct sources.

(load-file "tests/adv/edge_helpers.clj")
(load-file "tests/adv/invariants.clj")

(defn- probe-diag-carry []
  (let [r1 (try (read-string "(foo") nil (catch e e))
        r2 (try (read-string "[bar") nil (catch e e))
        r1-kind (when (map? r1) (:mino/kind r1))
        r2-kind (when (map? r2) (:mino/kind r2))
        ok (and r1-kind r2-kind)]
    (emit-verdict "T1.diag-carry-across-reads"
                  (if ok "pass" "fail")
                  :r1-code (str (when (map? r1) (:mino/code r1)))
                  :r2-code (str (when (map? r2) (:mino/code r2))))))

(defn- probe-diag-eval-isolation []
  ;; Two separate eval failures should each classify with a kind --
  ;; the runtime mustn't carry the first's diag state into the
  ;; second.
  (let [e1 (try (eval (read-string "(undefined-symbol-1)"))
                (catch e e))
        e2 (try (eval (read-string "(undefined-symbol-2)"))
                (catch e e))
        k1 (when (map? e1) (:mino/kind e1))
        k2 (when (map? e2) (:mino/kind e2))
        ok (and k1 k2)]
    (emit-verdict "T1.diag-eval-isolation"
                  (if ok "pass" "fail")
                  :k1 (str k1) :k2 (str k2))))

(probe-diag-carry)
(probe-diag-eval-isolation)
