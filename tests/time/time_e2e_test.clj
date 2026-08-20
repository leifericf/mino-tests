(require "tests/test")
(require '[clojure.string :as str])

;; Time/date E2E: cross-checks the time prims against EXTERNAL tool
;; oracles, which the mino repo forbids in its own suite (C + mino
;; only there). This suite runs inside the mino binary under test,
;; so the prims are called directly and only the ORACLE shells out:
;; the host `date` command for output formats, and python3's
;; datetime / email.utils for random-instant parse/format/offset
;; agreement. Tools missing from PATH skip their lane loudly.

(def ^:private date-available?
  (try (sh "date" "-u" "+%Y") true (catch e false)))

(def ^:private python-available?
  (try (sh "python3" "-c" "import datetime") true (catch e false)))

(def sample-epoch-secs
  "Boundary-ish UTC seconds: epoch, negatives, leap days, century
  edges, far future."
  [0 -1 1 86399 86400 -86400
   951782400           ; 2000-02-29
   4107542400          ; 2100-03-01
   -2208988800         ; 1900-01-01
   784111777           ; the RFC 1123 classic
   1787279462])        ; 2026-08-21T02:31:02Z

(defn- date-out [secs fmt]
  (str/trim (sh! "date" "-u" "-r" (str secs) fmt)))

(deftest rfc1123-matches-host-date
  (if date-available?
    (doseq [s sample-epoch-secs]
      (is (= (date-out s "+%a, %d %b %Y %H:%M:%S GMT")
             (format-time (* 1000 s) :rfc1123))
          (str "epoch " s)))
    (is true "date(1) unavailable; lane skipped")))

(deftest iso8601-matches-host-date
  (if date-available?
    (doseq [s sample-epoch-secs]
      (is (= (date-out s "+%Y-%m-%dT%H:%M:%SZ")
             (format-time (* 1000 s)))
          (str "epoch " s)))
    (is true "date(1) unavailable; lane skipped")))

(deftest host-date-output-parses-back
  (if date-available?
    (doseq [s sample-epoch-secs]
      (let [hdr (date-out s "+%a, %d %b %Y %H:%M:%S GMT")]
        (is (= (* 1000 s) (:epoch-ms (parse-time hdr)))
            (str "Date header " hdr))))
    (is true "date(1) unavailable; lane skipped")))

;;; python3 oracle: random instants through datetime + email.utils

(def ^:private oracle-cases
  "Seeded deterministic (secs, offset-min) pairs spanning 1900..2100."
  (let [seed 20260820
        step 7919          ; prime stride keeps samples unaligned
        start -2208988800  ; 1900-01-01
        span (- 4102444800 start)]
    (map (fn [i]
           (let [s (+ start (* step (mod (* i (+ 1 (mod seed 7))) 157)))
                 off (if (zero? (mod i 3)) 0
                         (- (mod (* i 13) 1439) 719))]
             [s off]))
         (range 40))))

(defn- python-tuple-literal [cases]
  (str "["
       (str/join ", "
                 (map (fn [[s off]]
                        (let [a (if (neg? off) (- off) off)]
                          (str "(" s ", " (quot a 60) ", " (rem a 60)
                               ", " (if (neg? off) -1 1) ")")))
                      cases))
       "]"))

(defn- python-oracle [cases]
  (let [code (str
              "import sys, datetime, email.utils\n"
              "cases = " (python-tuple-literal cases) "\n"
              "for s, oh, om, sign in cases:\n"
              "    dt = datetime.datetime.fromtimestamp(s, datetime.timezone.utc)\n"
              "    delta = datetime.timedelta(hours=oh, minutes=om) * sign\n"
              "    iso = dt.strftime('%Y-%m-%dT%H:%M:%SZ')\n"
              "    iso_date = (dt + delta).strftime('%Y-%m-%d')\n"
              "    rfc1123 = email.utils.format_datetime(dt, usegmt=True)\n"
              "    rfc2822 = email.utils.format_datetime((dt + delta).replace(tzinfo=datetime.timezone(delta)))\n"
              "    print('|'.join([iso, iso_date, rfc1123, rfc2822]))\n")]
    (map #(str/split % #"\|")
         (str/split-lines (sh! "python3" "-c" code)))))

(deftest python-datetime-agrees-on-random-instants
  (if python-available?
    (let [rows (python-oracle oracle-cases)]
      (is (= (count oracle-cases) (count rows)))
      (doseq [[[s off] [iso iso-date rfc1123 rfc2822]] (map vector
                                                             oracle-cases
                                                             rows)]
        ;; UTC ISO agrees
        (is (= iso (format-time (* 1000 s))) (str "iso " s))
        ;; HTTP Date agrees
        (is (= rfc1123 (format-time (* 1000 s) :rfc1123))
            (str "rfc1123 " s))
        ;; python's offset form (e.g. -0500) parses back exactly
        (is (= (* 1000 s) (:epoch-ms (parse-time rfc2822)))
            (str "2822 roundtrip " rfc2822))
        ;; the local date at this offset matches our date-only render
        (is (= iso-date (format-time (* 1000 s) :iso8601-date off))
            (str "local date " s " " off))))
    (is true "python3 unavailable; lane skipped")))

(run-tests-and-exit)
