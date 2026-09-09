;; Reader kill-signal for the mutation lane. Loads the mino submodule's
;; own reader test subset (literal / char / numeric / reader-macro /
;; reader-conditional / metadata / error-path) and runs the registry,
;; exiting non-zero on any failure. Mull reads that exit code as the
;; per-mutant kill oracle: a reader mutant that changes a parsed value,
;; a boundary, or an error path flips one of these assertions and dies.
;;
;; Runs with the mino submodule as CWD (the `mutation` task cd's there)
;; so the files' own `(require "tests/test")` relative loads resolve.
(require "tests/test")
(load-file "tests/literal_test.clj")
(load-file "tests/char_test.clj")
(load-file "tests/numeric_edges_test.clj")
(load-file "tests/numeric_tower_test.clj")
(load-file "tests/reader_macros_test.clj")
(load-file "tests/reader_cond_test.clj")
(load-file "tests/metadata_test.clj")
(load-file "tests/error_path_test.clj")
(run-tests-and-exit)
