(require "tests/test")
(in-ns 'doc-examples-test)
(require '[clojure.test :refer :all])
(require '[clojure.string :refer [blank? starts-with? ends-with? includes?]])

;; Documentation examples: clean, self-contained assertions designed
;; to appear in the Language Reference on mino-lang.org. Each test
;; name matches the function it documents.

;; mino resolves symbols in fn bodies at call time, not compile time.
;; A (doc-test string? ...) creates a var string? that shadows
;; clojure.core/string? when the test body runs. doc-test appends
;; -doc to the registered name so the test var can't clobber the
;; very function its body exercises.
(defmacro doc-test [name & body]
  `(deftest ~(symbol (str (name '~name) "-doc")) ~@body))

;; --- Special forms ---

(doc-test def
  (def x 42)
  (is (= 42 x)))

(doc-test defmacro
  (defmacro unless [cond then else]
    (list 'if cond else then))
  (is (= "no" (unless true "yes" "no")))
  (is (= "yes" (unless false "yes" "no"))))

(doc-test quasiquote
  (let [x 1 y 2]
    (is (= '(1 2 3) `(~x ~y 3)))))

(doc-test unquote
  (let [x 10]
    ;; Bare `+` in a syntax-quoted template auto-qualifies to clojure.core/+
    ;; because backquote namespace-resolves any non-locally-bound symbol.
    (is (= '(clojure.core/+ 10 1) `(+ ~x 1)))))

(doc-test unquote-splicing
  (def xs '(1 2 3))
  (is (= '(0 1 2 3 4) `(0 ~@xs 4))))

(doc-test defn
  (defn square [x] (* x x))
  (is (= 25 (square 5)))
  (is (= 1 (square 1))))

;; --- Predicates ---

(doc-test not=
  (is (= true (not= 1 2)))
  (is (= false (not= 1 1))))

(doc-test empty?
  (is (= true (empty? [])))
  (is (= false (empty? [1 2 3])))
  (is (= true (empty? nil))))

(doc-test >=
  (is (= true (>= 3 2)))
  (is (= true (>= 3 3)))
  (is (= false (>= 2 3))))

(doc-test nil?
  (is (= true (nil? nil)))
  (is (= false (nil? 0)))
  (is (= false (nil? false))))

(doc-test string?
  (is (= true (string? "hello")))
  (is (= false (string? 42))))

(doc-test number?
  (is (= true (number? 42)))
  (is (= true (number? 3.14)))
  (is (= false (number? "5"))))

(doc-test keyword?
  (is (= true (keyword? :foo)))
  (is (= false (keyword? "foo"))))

(doc-test symbol?
  (is (= true (symbol? 'x)))
  (is (= false (symbol? :x))))

(doc-test vector?
  (is (= true (vector? [1 2 3])))
  (is (= false (vector? '(1 2 3)))))

(doc-test map?
  (is (= true (map? {:a 1})))
  (is (= false (map? [1 2]))))

(doc-test fn?
  (is (= true (fn? +)))
  (is (= true (fn? (fn [x] x))))
  (is (= false (fn? 42))))

(doc-test set?
  (is (= true (set? #{1 2 3})))
  (is (= false (set? [1 2 3]))))

(doc-test seq?
  (is (= true (seq? '(1 2 3))))
  (is (= false (seq? [1 2 3]))))

(doc-test cons?
  (is (= true (cons? (cons 1 nil))))
  (is (= false (cons? [1 2]))))

(doc-test ifn?
  (is (= true (ifn? +)))
  (is (= true (ifn? :foo)))
  (is (= true (ifn? {:a 1})))
  (is (= false (ifn? 42))))

(doc-test true?
  (is (= true (true? true)))
  (is (= false (true? 1))))

(doc-test false?
  (is (= true (false? false)))
  (is (= false (false? nil))))

(doc-test boolean?
  (is (= true (boolean? true)))
  (is (= true (boolean? false)))
  (is (= false (boolean? nil))))

(doc-test int?
  (is (= true (int? 42)))
  (is (= false (int? 3.14))))

(doc-test integer?
  (is (= true (integer? 42)))
  (is (= false (integer? 3.14))))

(doc-test float?
  (is (= true (float? 3.14)))
  (is (= false (float? 42))))

(doc-test double?
  (is (= true (double? 3.14)))
  (is (= false (double? 42))))

(doc-test coll?
  (is (= true (coll? [1 2])))
  (is (= true (coll? {:a 1})))
  (is (= false (coll? 42))))

(doc-test list?
  (is (= true (list? '(1 2 3))))
  (is (= false (list? [1 2 3]))))

(doc-test some?
  (is (= true (some? 0)))
  (is (= true (some? false)))
  (is (= false (some? nil))))

(doc-test any?
  (is (= true (any? nil)))
  (is (= true (any? 42))))

(doc-test sequential?
  (is (= true (sequential? [1 2])))
  (is (= true (sequential? '(1 2))))
  (is (= false (sequential? {:a 1}))))

(doc-test associative?
  (is (= true (associative? {:a 1})))
  (is (= true (associative? [1 2])))
  (is (= false (associative? '(1 2)))))

(doc-test seqable?
  (is (= true (seqable? [1 2])))
  (is (= true (seqable? "hi")))
  (is (= false (seqable? 42))))

(doc-test indexed?
  (is (= true (indexed? [1 2])))
  (is (= false (indexed? '(1 2)))))

(doc-test counted?
  (is (= true (counted? [1 2])))
  (is (= true (counted? {:a 1})))
  (is (= false (counted? (range)))))

(doc-test reversible?
  (is (= true (reversible? [1 2])))
  (is (= false (reversible? '(1 2)))))

(doc-test sorted?
  (is (= true (sorted? (sorted-set 1 2 3))))
  (is (= false (sorted? #{1 2 3}))))

(doc-test distinct?
  (is (= true (distinct? 1 2 3)))
  (is (= false (distinct? 1 2 1))))

(doc-test qualified-symbol?
  (is (= true (qualified-symbol? 'foo/bar)))
  (is (= false (qualified-symbol? 'foo))))

(doc-test simple-symbol?
  (is (= true (simple-symbol? 'foo)))
  (is (= false (simple-symbol? 'foo/bar))))

(doc-test qualified-keyword?
  (is (= true (qualified-keyword? :foo/bar)))
  (is (= false (qualified-keyword? :foo))))

(doc-test simple-keyword?
  (is (= true (simple-keyword? :foo)))
  (is (= false (simple-keyword? :foo/bar))))

;; --- Numeric predicates ---

(doc-test zero?
  (is (= true (zero? 0)))
  (is (= false (zero? 1))))

(doc-test pos?
  (is (= true (pos? 1)))
  (is (= false (pos? -1))))

(doc-test neg?
  (is (= true (neg? -1)))
  (is (= false (neg? 1))))

(doc-test pos-int?
  (is (= true (pos-int? 1)))
  (is (= false (pos-int? -1)))
  (is (= false (pos-int? 1.0))))

(doc-test neg-int?
  (is (= true (neg-int? -1)))
  (is (= false (neg-int? 1))))

(doc-test nat-int?
  (is (= true (nat-int? 0)))
  (is (= true (nat-int? 5)))
  (is (= false (nat-int? -1))))

(doc-test ==
  (is (= true (== 1 1.0)))
  (is (= false (== 1 2))))

;; --- Arithmetic ---

(doc-test dec
  (is (= 4 (dec 5)))
  (is (= -1 (dec 0))))

;; --- Sequences ---

(doc-test take-nth
  (is (= '(0 3 6 9) (take-nth 3 (range 10)))))

(doc-test lazy-cat
  (is (= '(1 2 3 4) (lazy-cat [1 2] [3 4]))))

(doc-test dorun
  (is (= nil (dorun (map identity [1 2 3])))))

(doc-test run!
  (is (= nil (run! identity [1 2 3]))))

;; --- Collections ---

(doc-test array-map
  (is (= {:a 1 :b 2} (array-map :a 1 :b 2))))

;; --- Threading ---

(doc-test ->>
  (is (= [2 3 4] (->> [1 2 3] (map inc) vec))))

;; --- String ---

(doc-test blank?
  (is (= true (blank? "")))
  (is (= true (blank? "   ")))
  (is (= false (blank? "hi"))))

;; --- Bitwise ---

(doc-test bit-test
  (is (= true (bit-test 5 0)))
  (is (= false (bit-test 5 1)))
  (is (= true (bit-test 5 2))))

;; --- Functional ---

(doc-test some-fn
  (let [f (some-fn :a :b)]
    (is (= 1 (f {:a 1})))
    (is (= 2 (f {:b 2})))))

;; --- Stateful ---

(doc-test volatile!
  (let [v (volatile! 0)]
    (vswap! v inc)
    (is (= 1 @v))))

(doc-test volatile?
  (is (= true (volatile? (volatile! 0))))
  (is (= false (volatile? 42))))

(doc-test vswap!
  (let [v (volatile! 10)]
    (is (= 11 (vswap! v inc)))))

(doc-test vreset!
  (let [v (volatile! 0)]
    (vreset! v 42)
    (is (= 42 @v))))

;; --- Macros and reflection ---

(doc-test gensym
  (is (= true (symbol? (gensym))))
  (is (= false (= (gensym) (gensym)))))

(doc-test macroexpand-1
  (is (= '(if true (do 1 2)) (macroexpand-1 '(when true 1 2)))))

(doc-test macroexpand
  (is (= '(if true (do 1 2)) (macroexpand '(when true 1 2)))))

(doc-test quote
  (is (= 'foo 'foo))
  (is (= '(1 2 3) '(1 2 3))))

(doc-test doc
  (require '[clojure.repl :refer [doc-string]])
  (is (= true (string? (doc-string 'map)))))

(doc-test source
  (require '[clojure.repl :refer [source-form]])
  (is (= true (cons? (source-form 'when)))))

;; --- Strings ---

(doc-test starts-with?
  (is (= true (starts-with? "hello" "hel")))
  (is (= false (starts-with? "hello" "world"))))

(doc-test ends-with?
  (is (= true (ends-with? "hello" "llo")))
  (is (= false (ends-with? "hello" "hel"))))

(doc-test includes?
  (is (= true (includes? "hello world" "world")))
  (is (= false (includes? "hello" "xyz"))))

;; --- Delay ---

(doc-test delay
  (let [d (delay (+ 1 2))]
    (is (= 3 (force d)))))

(doc-test delay?
  (is (= true (delay? (delay 1))))
  (is (= false (delay? 42))))

(doc-test force
  (is (= 42 (force (delay 42))))
  (is (= 42 (force 42))))

(doc-test deref-delay
  (let [d (delay (+ 1 2))]
    (is (= 3 (deref-delay d)))))

;; --- Higher-order ---

(doc-test memoize
  (let [f (memoize (fn [x] (* x x)))]
    (is (= 25 (f 5)))
    (is (= 25 (f 5)))))

(doc-test doto
  (is (= [1 2 3] (doto [1 2 3] count))))

;; --- Iteration ---

(doc-test shuffle
  (let [s (shuffle [1 2 3 4 5])]
    (is (= 5 (count s)))
    (is (= #{1 2 3 4 5} (set s)))))

;; --- Tree walking ---

(doc-test walk
  (is (= [2 3 4] (walk inc vec [1 2 3]))))

(doc-test prewalk
  (is (= [2 [3 [4]]] (prewalk #(if (number? %) (inc %) %) [1 [2 [3]]]))))

;; --- Transducers ---

(doc-test cat
  (is (= [1 2 3 4] (into [] cat [[1 2] [3 4]]))))

(doc-test unreduced
  (is (= 42 (unreduced (reduced 42))))
  (is (= 42 (unreduced 42))))

;; --- Type predicates ---

(doc-test char?
  (is (= false (char? "a")))
  (is (= false (char? 65))))

(doc-test ratio?
  (is (= false (ratio? 1)))
  (is (= false (ratio? 1.5))))

(doc-test decimal?
  (is (= false (decimal? 1.0)))
  (is (= false (decimal? 1))))

(doc-test rational?
  (is (= true (rational? 42)))
  (is (= false (rational? 3.14))))

;; --- Random ---

(doc-test rand-int
  (let [n (rand-int 10)]
    (is (= true (and (>= n 0) (< n 10))))))

(doc-test rand-nth
  (let [x (rand-nth [1 2 3])]
    (is (= true (contains? #{1 2 3} x)))))

(in-ns 'user)
