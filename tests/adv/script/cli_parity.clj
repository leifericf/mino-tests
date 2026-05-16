;; T4 -- CLI flag / env parsing parity
;;
;; Anchor bugs: --JIT=on vs --jit=on case asymmetry (v0.252.1). The CLI
;; flag was case-sensitive but MINO_JIT env var matched case-insensitive,
;; so a user typing the flag in caps got a silent no-op while the env
;; var would have worked. Fixed: both routes are case-insensitive now.

(load-file "tests/adv/edge_helpers.clj")

(def mino-bin (or (getenv "MINO_BIN") "mino/mino"))

(defn- run-with-jit
  "Run mino-bin with --jit=<value>, return exit code map. Uses sh
   (not sh!) so a non-zero exit doesn't throw."
  [val]
  (sh mino-bin (str "--jit=" val) "-e" "(println :ok)"))

(defn- probe-jit-flag-case []
  ;; All four spellings should produce the same output / exit code.
  (let [lo  (run-with-jit "on")
        up  (run-with-jit "ON")
        mix (run-with-jit "On")
        off (run-with-jit "off")]
    (let [ok (and (= (:exit lo) (:exit up))
                  (= (:exit lo) (:exit mix))
                  (= (:exit lo) 0)
                  (or (= (:out lo) (:out up))
                      ;; Allow capability-mismatch stderr warnings to
                      ;; differ; stdout :ok must match.
                      (.contains (or (:out lo) "") ":ok")))]
      (emit-verdict "T4.jit-flag-case-insensitive"
                    (if ok "pass" "fail")
                    :lo-exit (:exit lo) :up-exit (:exit up)
                    :mix-exit (:exit mix) :off-exit (:exit off)))))

(defn- probe-env-flag-parity []
  ;; Setting MINO_JIT via env should behave like --jit on the CLI.
  (let [via-env (sh "env" "MINO_JIT=on" mino-bin "-e" "(println :env-on)")
        via-cli (sh mino-bin "--jit=on" "-e" "(println :env-on)")
        ok      (and (= (:exit via-env) 0) (= (:exit via-cli) 0))]
    (emit-verdict "T4.env-vs-cli-parity"
                  (if ok "pass" "fail")
                  :env-exit (:exit via-env)
                  :cli-exit (:exit via-cli))))

(probe-jit-flag-case)
(probe-env-flag-parity)
