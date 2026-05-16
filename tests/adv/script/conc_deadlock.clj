;; T9 -- concurrency primitive deadlock detection
;;
;; Anchor: the promise + dotimes + future deadlock (v0.252.3) where
;; closures captured wrong i, then derefed an out-of-bounds promise
;; that never got delivered. The probe sets a generous timeout and
;; reports the deadlock as a clean failure instead of hanging.

(load-file "tests/adv/edge_helpers.clj")
(load-file "tests/adv/invariants.clj")

;; mino has no deref-with-timeout and realized? has a cross-thread
;; visibility quirk (see ../mino/.local/BUGS.md). For T9 we rely on
;; the runner's per-probe wall-clock budget instead: if a probe hangs,
;; the OS-level timeout in the smoke driver kills it and the runner
;; reports the failure. Probe bodies stay simple: spawn the work,
;; deref the result, assert the value.

(defn- probe-promise-dotimes-fanout []
  ;; The canonical anchor shape: 10 promises, 10 futures delivering by
  ;; closed-over `i`. Pre-v0.252.3, every future captured i=10 and
  ;; nth threw, so no promise got delivered.
  (let [ps (vec (repeatedly 10 promise))]
    (dotimes [i 10]
      (future (deliver (nth ps i) (* i i))))
    (let [r  (mapv deref ps)
          ok (= r [0 1 4 9 16 25 36 49 64 81])]
      (emit-verdict "T9.promise-dotimes-fanout"
                    (if ok "pass" "fail")
                    :result (pr-str r)))))

(defn- probe-stm-contention []
  ;; N writers transacting +1, N readers transacting -1 -- final sum
  ;; should equal zero. Tests STM under real contention without
  ;; deadlocking.
  (let [r (ref 0)
        n 20
        workers 4
        inc-futs (doall (for [_ (range workers)]
                          (future
                            (dotimes [_ n]
                              (dosync (alter r inc))))))
        dec-futs (doall (for [_ (range workers)]
                          (future
                            (dotimes [_ n]
                              (dosync (alter r dec))))))]
    (doseq [f inc-futs] @f)
    (doseq [f dec-futs] @f)
    (let [final @r
          verdict (stm-sum-preserved final)]
      (emit-verdict "T9.stm-zero-sum"
                    (if (= verdict true) "pass" "fail")
                    :final (pr-str final)))))

(defn- probe-no-mutual-deadlock []
  ;; Two refs, two writers swapping order. Should not deadlock.
  (let [a (ref 0) b (ref 0)
        f1 (future
             (dotimes [_ 100]
               (dosync (alter a inc) (alter b inc))))
        f2 (future
             (dotimes [_ 100]
               (dosync (alter b inc) (alter a inc))))]
    @f1 @f2
    (let [r [@a @b]]
      (emit-verdict "T9.no-mutual-deadlock"
                    (if (= r [200 200]) "pass" "fail")
                    :final (pr-str r)))))

(probe-promise-dotimes-fanout)
(probe-stm-contention)
(probe-no-mutual-deadlock)
