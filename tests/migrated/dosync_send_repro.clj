;; dosync_send_repro.clj -- minimal reproducer for the
;; send-from-dosync hang seen only on the GHA ubuntu-24.04-arm runner.
;;
;; Run this file directly (NOT through run_migrated.clj) so test
;; ordering doesn't influence the result:
;;
;;   ./mino/mino tests/migrated/dosync_send_repro.clj
;;
;; The hang manifests as no stdout output past "[repro] before await".
;; If the run completes cleanly, the issue is order-dependent.

(defn- log [tag s]
  (binding [*out* *err*]
    (println (str "[" tag "] " s))
    (flush)))

(log "repro" (str "thread-limit=" (mino-thread-limit)))

(dotimes [iter 5]
  (log "repro" (str "iter " iter " start"))
  (let [a (agent 0)
        r (ref 0)
        action-runs (atom 0)]
    (log "repro" (str "iter " iter " before dosync"))
    (dosync
      (alter r inc)
      (send a (fn [v] (swap! action-runs inc) (inc v))))
    (log "repro" (str "iter " iter " before await"))
    (await a)
    (log "repro" (str "iter " iter " after await @a=" @a " @runs=" @action-runs))
    (when-not (and (= 1 @a) (= 1 @action-runs))
      (log "repro" (str "FAIL iter " iter)))))

(log "repro" "all iterations completed")
