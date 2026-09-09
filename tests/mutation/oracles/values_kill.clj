;; Value-core kill-signal for the mutation lane. Loads the mino
;; submodule's own value-core test subset (collection / sorted /
;; hash-compare / values-safety / data / records / arithmetic) and runs
;; the registry, exiting non-zero on any failure. Mull reads that exit
;; code as the per-mutant kill oracle: a val.c mutant that flips a tag
;; check, an eq_seq length bound, a tagged-int overflow guard, or a
;; boxing bound trips one of these assertions and dies.
;;
;; collection_test carries the cross-type-equality assertions round 4
;; proved kill the RB-depth mutant; they are the POSITIVE CONTROL that
;; the harness reports KILLED. If a cross-type equality mutant survives,
;; the harness is miswired, not the suite -- investigate before trusting
;; any score.
;;
;; Runs with the mino submodule as CWD (the `mutation` task cd's there)
;; so the files' own `(require "tests/test")` relative loads resolve.
(require "tests/test")
(load-file "tests/collection_test.clj")
(load-file "tests/sorted_test.clj")
(load-file "tests/hash_compare_test.clj")
(load-file "tests/values_safety_test.clj")
(load-file "tests/data_test.clj")
(load-file "tests/records_test.clj")
(load-file "tests/arithmetic_test.clj")
(run-tests-and-exit)
