(require "tests/test")

;; :arglists metadata on C-primitive vars, installed at startup from
;; the generated table (ADR 34). Divergent-arity prims (slurp,
;; hash-map, ...) carry no :arglists until their arity classes are
;; fixed, so they must still read as nil here.

(deftest arglists-prim-present
  (is (= '([coll]) (:arglists (meta #'clojure.core/first))))
  (is (= '([x]) (:arglists (meta #'clojure.core/inc))))
  (is (= '([] [x] [x y] [x y & more]) (:arglists (meta #'clojure.core/+))))
  (is (= '([] [coll] [coll x] [coll x & xs])
         (:arglists (meta #'clojure.core/conj))))
  (is (= '([f args] [f x args] [f x y args] [f x y z args] [f a b c d & args])
         (:arglists (meta #'clojure.core/apply))))
  (is (= '([f coll] [f val coll]) (:arglists (meta #'clojure.core/reduce))))
  (is (= '([coll] [separator coll]) (:arglists (meta #'clojure.string/join))))
  (is (= '([s re] [s re limit]) (:arglists (meta #'clojure.string/split))))
  (is (= '([s]) (:arglists (meta #'clojure.string/upper-case)))))

;; Divergent-arity vars read nil until their arity classes are fixed
;; (ADR 34).
(deftest arglists-divergent-still-absent
  (is (nil? (:arglists (meta #'clojure.core/slurp))))
  (is (nil? (:arglists (meta #'clojure.core/hash-map))))
  (is (nil? (:arglists (meta #'clojure.core/let))))
  (is (nil? (:arglists (meta #'clojure.core/interleave)))))

(deftest arglists-defn-baseline
  (is (:arglists (meta #'clojure.core/map))))

(run-tests-and-exit)
