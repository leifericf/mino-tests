(require "tests/test")

;; :arglists metadata on C-primitive vars, installed at startup from
;; the generated table (ADR 34). Divergent-arity prims (slurp,
;; let, ...) carry no :arglists until their arity classes are
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
  (is (nil? (:arglists (meta #'clojure.core/let))))
  (is (nil? (:arglists (meta #'clojure.core/interleave)))))

;; A dangling key-value or option tail is a value-type error, not an
;; arity error: the call must throw, the error code must not be the
;; arity code MAR001, and the prims carry their oracle arglists.
(deftest arglists-dangling-kv
  (is (thrown? (clojure.core/hash-map :a)))
  (is (not= "MAR001"
            (try (clojure.core/hash-map :a)
                 (catch Throwable e (get e :mino/code)))))
  (is (thrown? (clojure.core/sorted-map :a)))
  (is (not= "MAR001"
            (try (clojure.core/sorted-map :a)
                 (catch Throwable e (get e :mino/code)))))
  (is (thrown? (clojure.core/sorted-map-by :cmp :a)))
  (is (not= "MAR001"
            (try (clojure.core/sorted-map-by :cmp :a)
                 (catch Throwable e (get e :mino/code)))))
  (is (thrown? (clojure.core/atom :x :meta)))
  (is (not= "MAR001"
            (try (clojure.core/atom :x :meta)
                 (catch Throwable e (get e :mino/code)))))
  (is (thrown? (clojure.core/agent :f :error-mode)))
  (is (not= "MAR001"
            (try (clojure.core/agent :f :error-mode)
                 (catch Throwable e (get e :mino/code)))))
  (is (thrown? (clojure.core/assoc {} :a)))
  (is (not= "MAR001"
            (try (clojure.core/assoc {} :a)
                 (catch Throwable e (get e :mino/code)))))
  (is (thrown? (clojure.core/restart-agent :ag :state :dispatch)))
  (is (not= "MAR001"
            (try (clojure.core/restart-agent :ag :state :dispatch)
                 (catch Throwable e (get e :mino/code)))))
  (is (= '([] [& keyvals]) (:arglists (meta #'clojure.core/hash-map))))
  (is (= '([& keyvals]) (:arglists (meta #'clojure.core/sorted-map))))
  (is (= '([comparator & keyvals])
         (:arglists (meta #'clojure.core/sorted-map-by))))
  (is (= '([x] [x & options]) (:arglists (meta #'clojure.core/atom))))
  (is (= '([state & options]) (:arglists (meta #'clojure.core/agent))))
  (is (= '([map key val] [map key val & kvs])
         (:arglists (meta #'clojure.core/assoc))))
  (is (= '([a new-state & options])
         (:arglists (meta #'clojure.core/restart-agent)))))

(deftest arglists-defn-baseline
  (is (:arglists (meta #'clojure.core/map))))

(run-tests-and-exit)
