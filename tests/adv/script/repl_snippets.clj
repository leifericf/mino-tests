;; T3 -- REPL multi-form snippet rendering
;;
;; Anchor: the v0.252.1 REPL bug where source_cache_store was called
;; with the parse buffer (truncated per form), so when an error in form
;; N tried to render the surrounding source snippet, the snippet was
;; blank. Fix added a hist_buf in main.c that accumulates the whole
;; REPL session text.
;;
;; This probe spawns mino-bin in -e mode (which mimics the REPL parse
;; path) with multiple forms separated by newlines, then asserts the
;; error report mentions the line / form context.

(load-file "tests/adv/edge_helpers.clj")
(require '[clojure.string :as s])

(def mino-bin (or (getenv "MINO_BIN") "mino/mino"))

;; mino's `sh` returns {:exit N :out S} -- :out is the combined
;; stdout+stderr stream (mino's CLI prints errors to stdout in -e
;; mode). Probes check :out.

(defn- probe-snippet-line-cited []
  ;; Three forms: two clean, one that errors on line 3. The error
  ;; report should cite line 3 (or column 1 of line 3 -- mino emits
  ;; `<string>:3:1`).
  (let [src "(println :one)\n(println :two)\n(/ 1 0)\n(println :four)\n"
        out (sh mino-bin "-e" src)
        body (or (:out out) "")
        ok (and (not= 0 (:exit out))
                (or (s/includes? body ":3")
                    (s/includes? body "line 3")))]
    (emit-verdict "T3.repl-snippet-cites-line"
                  (if ok "pass" "fail")
                  :exit (:exit out)
                  :body-len (count body))))

(defn- probe-snippet-spans-forms []
  ;; If form 5 errors, the snippet should still resolve. We don't
  ;; assert exact wording; just that the error has some context.
  (let [src (apply str (concat
                        (repeat 4 "(println :ok)\n")
                        ["(undefined-symbol)\n"]))
        out (sh mino-bin "-e" src)
        body (or (:out out) "")]
    (emit-verdict "T3.repl-snippet-non-blank"
                  (if (and (not= 0 (:exit out))
                           (s/includes? body "undefined-symbol"))
                    "pass" "fail")
                  :body-bytes (count body))))

(probe-snippet-line-cited)
(probe-snippet-spans-forms)
