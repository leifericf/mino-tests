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

;; Bound the per-test future fan-out to the host's thread grant.
;; mino's `(future ...)` throws MTH001 thread-limit-exceeded when
;; thread_count >= thread_limit (spawn-per-future, not a pool), and
;; standalone mino sets the limit to cpu_count. GHA's free-tier
;; runners (macos-14: 3 CPUs, ubuntu-24.04: 4 CPUs) trip this for
;; any probe that spawns >= cpu_count concurrent futures. Cap at
;; thread_limit - 1 so the main thread keeps its slot; never go
;; below 2 (the probe needs concurrency to test).
(def ^:private fanout-cap
  (let [lim (try (mino-thread-limit) (catch e 8))
        n   (- lim 1)]
    (cond
      (< n 2) 2
      (> n 10) 10
      :else n)))

(defn- probe-promise-dotimes-fanout []
  ;; The canonical anchor shape: N promises, N futures delivering by
  ;; closed-over `i`. Pre-v0.252.3, every future captured i=N and
  ;; nth threw, so no promise got delivered. N adapts to fanout-cap
  ;; so the probe runs on resource-constrained CI without trading
  ;; the closure-capture coverage.
  (let [n  fanout-cap
        ps (vec (repeatedly n promise))]
    (dotimes [i n]
      (future (deliver (nth ps i) (* i i))))
    (let [r        (mapv deref ps)
          expected (mapv (fn [i] (* i i)) (range n))
          ok       (= r expected)]
      (emit-verdict "T9.promise-dotimes-fanout"
                    (if ok "pass" "fail")
                    :n n
                    :result (pr-str r)))))

(defn- probe-stm-contention []
  ;; N writers transacting +1, N readers transacting -1 -- final sum
  ;; should equal zero. Tests STM under real contention without
  ;; deadlocking. workers x 2 (inc + dec) must fit in fanout-cap.
  (let [workers (max 1 (quot fanout-cap 2))
        r (ref 0)
        n 20
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
                    :workers workers
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
