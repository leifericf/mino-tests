(require "tests/test")
(require '[clojure.repl :refer [doc doc-string]])

;; :arglists metadata on C-primitive vars, installed at startup from
;; the generated table (ADR 34). Special-form macros carry the census
;; oracle shapes stamped at registration.

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

;; The eight special-form macro vars interned by the registry carry
;; their JVM 1.12.4 oracle arglists (census surface) alongside
;; :macro and :doc.
(deftest arglists-special-forms
  (is (= '([bindings & body]) (:arglists (meta #'clojure.core/binding))))
  (is (= '([& names]) (:arglists (meta #'clojure.core/declare))))
  (is (= '([name doc-string? attr-map? [params*] body]
           [name doc-string? attr-map? ([params*] body) + attr-map?])
         (:arglists (meta #'clojure.core/defmacro))))
  (is (= '([& sigs]) (:arglists (meta #'clojure.core/fn))))
  (is (= '([& body]) (:arglists (meta #'clojure.core/lazy-seq))))
  (is (= '([bindings & body]) (:arglists (meta #'clojure.core/let))))
  (is (= '([bindings & body]) (:arglists (meta #'clojure.core/loop))))
  (is (= '([name docstring? attr-map? references*])
         (:arglists (meta #'clojure.core/ns))))
  (is (:macro (meta #'clojure.core/let)))
  (is (:doc (meta #'clojure.core/let))))

;; The four core.clj def aliases carry :arglists, all oracle-exact:
;; each impl accepts every census-oracle arity (probed at arities
;; 0..9; accepted arities fail only on value shape, never with an
;; arity code). array-map aliases hash-map, whose odd-count rejection
;; is a value error, so the variadic oracle shape is honest.
(deftest arglists-def-aliases
  (is (= '([] [c1] [c1 c2] [c1 c2 & colls])
         (:arglists (meta #'clojure.core/interleave))))
  (is (= '([n coll] [n step coll] [n step pad coll])
         (:arglists (meta #'clojure.core/partition))))
  (is (= '([] [& keyvals])
         (:arglists (meta #'clojure.core/array-map))))
  (is (= '([m ks] [m ks not-found])
         (:arglists (meta #'clojure.core/get-in))))
  ;; Regression: the core.clj override is a defn so its def-with-docstring
  ;; cannot drop the attached :arglists (a def replaces var meta wholesale).
  (is (= '([x]) (:arglists (meta #'clojure.core/realized?))))
  (is (= '(1 :a 2 :b) (interleave [1 2] [:a :b])))
  (is (= '((1 2) (3 4)) (partition 2 [1 2 3 4 5])))
  (is (= {:a 1} (array-map :a 1)))
  (is (= 2 (get-in {:a {:b 2}} [:a :b]))))

;; A dangling key-value or option tail, or an unknown option key, is a
;; value-type error, not an arity error: the call must throw, the error
;; code must not be the arity code MAR001, and the prims carry their
;; oracle arglists.
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
  ;; assoc is the JVM outlier: arity 2 is below the smallest oracle
  ;; signature and stays an arity error (ADR 34 gate), while the
  ;; odd-tail parity rejection alone is a value error.
  (is (thrown? (clojure.core/assoc {} :a :b :c)))
  (is (not= "MAR001"
            (try (clojure.core/assoc {} :a :b :c)
                 (catch Throwable e (get e :mino/code)))))
  (is (= "MAR001"
         (try (clojure.core/assoc {} :a)
              (catch Throwable e (get e :mino/code)))))
  (is (thrown? (clojure.core/restart-agent :ag :state :dispatch)))
  (is (not= "MAR001"
            (try (clojure.core/restart-agent :ag :state :dispatch)
                 (catch Throwable e (get e :mino/code)))))
  ;; ref's option tail is in-signature: unknown keys and dangling
  ;; tails are value errors, and a well-formed tail constructs a ref.
  (is (thrown? (clojure.core/ref :s :bogus 1)))
  (is (not= "MAR001"
            (try (clojure.core/ref :s :bogus 1)
                 (catch Throwable e (get e :mino/code)))))
  (is (thrown? (clojure.core/ref :s :max-history)))
  (is (not= "MAR001"
            (try (clojure.core/ref :s :max-history)
                 (catch Throwable e (get e :mino/code)))))
  (is (ref? (clojure.core/ref :s :max-history 10)))
  ;; spit's dangling option tail is a value error naming the key
  (is (thrown? (clojure.core/spit "/tmp/mino-spit-tail-pin" "x" :append)))
  (is (not= "MAR001"
            (try (clojure.core/spit "/tmp/mino-spit-tail-pin" "x" :append)
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

;; The trivial missing arities the JVM oracle accepts: (require) and
;; (use) take the zero arity but reject it with a value error like
;; the JVM, and (disj! s) returns the set unchanged. Two-arity
;; removal on a real transient, and the nonexistent-ns throw, still
;; behave as before. The three vars carry their census-oracle
;; arglists.
(deftest arglists-trivial-arities
  (is (thrown? (clojure.core/require)))
  (is (not= "MAR001"
            (try (clojure.core/require)
                 (catch Throwable e (get e :mino/code)))))
  (is (thrown? (clojure.core/use)))
  (is (not= "MAR001"
            (try (clojure.core/use)
                 (catch Throwable e (get e :mino/code)))))
  (is (= #{:a} (clojure.core/disj! #{:a})))
  (is (= '([& args]) (:arglists (meta #'clojure.core/require))))
  (is (= '([& args]) (:arglists (meta #'clojure.core/use))))
  (is (= '([set] [set key] [set key & ks])
         (:arglists (meta #'clojure.core/disj!))))
  (is (= #{} (persistent! (disj! (transient #{:a}) :a))))
  (is (thrown? (clojure.core/require :nonexistent-ns-xyz))))

(deftest arglists-defn-baseline
  (is (:arglists (meta #'clojure.core/map))))

;; Lax prims accept every oracle arity plus tolerated extras, so the
;; oracle arglists attach verbatim; the extra arities stay unclaimed.
;; Real gaps reject oracle-claimed arities, so their arglists record
;; the arity set the prim actually accepts (mino-true shapes derived
;; from the prim sources, oracle param names kept where the shape
;; coincides). spit supports its option tail (:append, :encoding) and
;; rides the oracle shape with the lax class.
(deftest arglists-lax-and-gap
  (is (= '([x] [x y] [x y & more]) (:arglists (meta #'clojure.core/=))))
  (is (= '([x y]) (:arglists (meta #'clojure.core/identical?))))
  (is (= '([] [coll] [coll x]) (:arglists (meta #'clojure.core/conj!))))
  (is (= '([f]) (:arglists (meta #'clojure.core/slurp))))
  (is (= '([sym]) (:arglists (meta #'clojure.core/resolve))))
  (is (= '([x] [x & options]) (:arglists (meta #'clojure.core/ref))))
  (is (= '([array idx]) (:arglists (meta #'clojure.core/aget))))
  (is (= '([array idx val]) (:arglists (meta #'clojure.core/aset))))
  (is (= '([binding-map f]) (:arglists (meta #'clojure.core/with-bindings*))))
  (is (= '([f content & options]) (:arglists (meta #'clojure.core/spit))))
  ;; Honesty: slurp's variadic oracle arity stays unclaimed, so no
  ;; two-element signature may appear.
  (is (not-any? #(= 2 (count %))
                (:arglists (meta #'clojure.core/slurp)))))

;; doc renders the JVM signature shape: name line, arglists line,
;; docstring, for every var whose meta carries :arglists, whether the
;; arglists came from the prim table, the special-form registry, or a
;; defn. Vars without :arglists keep their bare docstrings.
(deftest doc-signatures
  (is (= "first\n([coll])\nReturns the first item in a collection, or nil if empty."
         (doc-string 'first)))
  (let [s (with-out-str (doc first))]
    (is (clojure.string/starts-with? s "first\n"))
    (is (clojure.string/includes? s "\n([coll])\n")))
  (is (clojure.string/includes? (with-out-str (doc +))
                                "\n([] [x] [x y] [x y & more])\n"))
  (is (clojure.string/includes? (with-out-str (doc let))
                                "\n([bindings & body])\n"))
  ;; map's arglists come from its core.clj defn form, whose signature
  ;; is ([f] [f & colls]), not the census oracle's five-arity shape.
  (is (clojure.string/includes? (with-out-str (doc map))
                                "\n([f] [f & colls])\n"))
  (is (clojure.string/starts-with?
       (doc-string 'clojure.string/join)
       "join\n([coll] [separator coll])\n"))
  ;; A mino-only prim with no :arglists keeps its bare docstring: no
  ;; line of the output opens an arglists form.
  (let [s (doc-string 'mino-capability)]
    (is (clojure.string/starts-with?
         s "Return the install-group capability label"))
    (is (not-any? #(clojure.string/starts-with? % "(")
                  (clojure.string/split-lines s)))))

(run-tests-and-exit)
