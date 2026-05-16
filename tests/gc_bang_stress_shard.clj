;; gc_bang_stress_shard.clj -- the (gc!)-using shapes from mino's
;; transient_test.clj and gc_test.clj, sized for MINO_GC_STRESS=1.
;;
;; Why a shard: stress mode forces a full STW major on every alloc,
;; so a 2000-iter loop turns into millions of GC cycles and runs for
;; tens of minutes. The interesting shapes -- transients across gc!,
;; gc! mid-incremental-major -- need only a handful of allocations
;; to exercise the dangerous path. This file pins those shapes at
;; iteration counts that finish in seconds, even under stress.
;;
;; Run via `mino task gc-stress-subset` -- the wrapper sets
;; MINO_GC_STRESS=1 and points mino at this file.

(require "tests/test")

;; The shape that hid the v0.255.6/.7/.8 CI hang. The bug pattern is
;; (gc!) called between transient mutations while the major is mid-
;; mark. Stress mode forces a full major on every alloc, so it
;; triggers on every `conj!` allocation -- exercising the gc!
;; interaction with maximum stress.
(deftest gc-bang-transient-vec-survives
  (let [t (transient [])]
    (conj! t 1)
    (gc!)
    (conj! t 2)
    (gc!)
    (conj! t 3)
    (is (= [1 2 3] (persistent! t)))))

(deftest gc-bang-transient-map-survives
  (let [t (transient {})]
    (assoc! t :a 1)
    (gc!)
    (assoc! t :b 2)
    (gc!)
    (assoc! t :c 3)
    (is (= {:a 1 :b 2 :c 3} (persistent! t)))))

(deftest gc-bang-transient-set-survives
  (let [t (transient #{})]
    (conj! t :x)
    (gc!)
    (conj! t :y)
    (gc!)
    (conj! t :z)
    (is (= #{:x :y :z} (persistent! t)))))

;; Bare gc! between persistent ops -- the simplest shape that
;; exercises mino_gc_collect(MINO_GC_FULL).
(deftest gc-bang-between-persistent-ops
  (let [v1 (vec (range 4))
        _  (gc!)
        v2 (conj v1 4)
        _  (gc!)
        v3 (conj v2 5)]
    (is (= [0 1 2 3 4 5] v3))))

;; gc! immediately after a major-triggering allocation surge. This
;; reproduces the in-flight-major scenario: enough allocations to
;; start an incremental major, then gc! while it's still in
;; MAJOR_MARK phase.
(deftest gc-bang-during-major
  (let [surge (loop [i 0 acc []]
                (if (= i 100)
                  acc
                  (recur (inc i) (conj acc i))))]
    (is (= 100 (count surge)))
    (gc!)
    (let [t (transient [])]
      (conj! t 1)
      (gc!)
      (conj! t 2)
      (is (= [1 2] (persistent! t))))))

(run-tests-and-exit)
