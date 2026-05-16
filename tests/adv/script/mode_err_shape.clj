;; T2 -- cross-mode error-shape preservation
;;
;; Anchor: the file-mode ex-info loss bug (v0.252.1). mino_eval_string_inner
;; didn't run normalize_exception, so ex-info maps lost :mino/kind /code/
;; message when surfaced as the top-level error. Fix routes through
;; normalize_exception in both file and REPL modes.

(load-file "tests/adv/edge_helpers.clj")
(load-file "tests/adv/invariants.clj")
(require '[clojure.string])

;; In mino, the catch binding receives the normalized diagnostic map
;; directly (:mino/kind / :mino/code / :mino/message / :mino/data),
;; not the raw ex-info data. ex-data returns the original user data
;; under the diagnostic envelope. The probes assert the envelope keys
;; are present.

(defn- probe-ex-info-shape-preserved []
  (let [e  (try (eval (read-string "(throw (ex-info \"boom\" {:k :v}))"))
                (catch e e))
        ok (and (map? e)
                (contains? e :mino/kind)
                (contains? e :mino/code)
                (contains? e :mino/message))]
    (emit-verdict "T2.ex-info-shape-preserved"
                  (if ok "pass" "fail")
                  :keys (pr-str (set (keys (or e {})))))))

(defn- probe-keyword-throw-shape []
  ;; Throwing a non-map (a keyword) should still produce a classified
  ;; diagnostic envelope.
  (let [e  (try (eval (read-string "(throw :keyword-payload)"))
                (catch e e))
        ok (and (map? e) (contains? e :mino/kind))]
    (emit-verdict "T2.keyword-throw-classified"
                  (if ok "pass" "fail")
                  :envelope (pr-str (select-keys (or e {})
                                                 [:mino/kind :mino/code])))))

(defn- probe-string-throw-shape []
  (let [e   (try (eval (read-string "(throw \"plain string error\")"))
                 (catch e e))
        msg (when (map? e) (:mino/message e))
        ok  (and (string? msg)
                 (or (= msg "plain string error")
                     (clojure.string/includes? msg "plain string error")))]
    (emit-verdict "T2.string-throw-classified"
                  (if ok "pass" "fail")
                  :msg (pr-str msg))))

(probe-ex-info-shape-preserved)
(probe-keyword-throw-shape)
(probe-string-throw-shape)
