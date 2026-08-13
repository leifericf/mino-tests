#!/usr/bin/env bb
;;
;; Extract llvm-cov line/region/function coverage summary from a
;; merged .profdata file and emit a stable EDN artifact.
;;
;; Usage:
;;   bb scripts/extract_coverage.bb <binary> <profdata> [output.edn]
;;
;; Requires: llvm-cov on PATH (absent on macOS Apple clang; runs in CI).
;; Output shape:
;;   {:lines-covered N :lines-total N :lines-percent 0.0
;;    :regions-covered N :regions-total N :regions-percent 0.0
;;    :functions-covered N :functions-total N :functions-percent 0.0}
;;
;; Verification deferred to a host with LLVM tools.

(require '[babashka.process :refer [shell]])
(require '[babashka.fs :as fs])
(require '[cheshire.core :as json])

(when (< (count *command-line-args*) 2)
  (binding [*out* *err*]
    (println "Usage: bb scripts/extract_coverage.bb <binary> <profdata> [output.edn]"))
  (System/exit 1))

(def binary  (nth *command-line-args* 0))
(def profdata (nth *command-line-args* 1))
(def out-path (or (nth *command-line-args* 2 nil)
                  "output/coverage-summary.edn"))

(def json-str
  (:out (shell {:out :string}
               (str "llvm-cov export -summary-only -format=json "
                    "-instr-profile=" profdata " " binary))))

(def data (json/parse-string json-str true))

(def totals (get-in data [:data 0 :totals]))

(def summary
  {:lines-covered    (get-in totals [:lines :covered] 0)
   :lines-total      (get-in totals [:lines :count] 0)
   :lines-percent    (get-in totals [:lines :percent] 0.0)
   :regions-covered  (get-in totals [:regions :covered] 0)
   :regions-total    (get-in totals [:regions :count] 0)
   :regions-percent  (get-in totals [:regions :percent] 0.0)
   :functions-covered (get-in totals [:functions :covered] 0)
   :functions-total   (get-in totals [:functions :count] 0)
   :functions-percent (get-in totals [:functions :percent] 0.0)})

(fs/create-dirs (fs/parent (fs/file out-path)))
(spit out-path (pr-str summary))
(println "coverage summary:" out-path)
(println (pr-str summary))
