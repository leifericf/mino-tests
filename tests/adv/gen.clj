;; gen.clj -- seeded shape generators for adversarial probes.
;;
;; xorshift64* with the standard Numerical Recipes finalizer. The
;; multiplication step uses unchecked-multiply so the i64 wrap is
;; well-defined; the shift steps use the BC fast-path which (since
;; mino v0.255.5) routes bitwise ops through mino_int_wrap and
;; doesn't promote to bigint.

(def *rng* (atom 1))

(defn seed!
  "Reset the RNG seed. xorshift64* has an all-zero fixed point; a
   zero seed is replaced with the splitmix64 constant."
  [s]
  (reset! *rng* (if (zero? s) 0x9E3779B97F4A7C15 s)))

(defn next-u64
  "xorshift64* step. Uses unchecked-multiply for the finalizer so
   the i64 wrap is well-defined."
  []
  (let [x  @*rng*
        x1 (bit-xor x (unsigned-bit-shift-right x 12))
        x2 (bit-xor x1 (bit-shift-left x1 25))
        x3 (bit-xor x2 (unsigned-bit-shift-right x2 27))]
    (reset! *rng* x3)
    (unchecked-multiply x3 0x2545F4914F6CDD1D)))

(defn next-u32 []
  (bit-and 0xFFFFFFFF (unsigned-bit-shift-right (next-u64) 32)))

(defn gen-int
  ([] (gen-int -1000 1000))
  ([lo hi]
   (let [span (- hi lo -1)
         u    (next-u32)
         u-pos (if (neg? u) (- u) u)]
     (+ lo (mod u-pos span)))))

(defn gen-bool [] (zero? (mod (abs (next-u32)) 2)))

;; mino has no int->char primitive, so we index a fixed alphabet
;; string instead. Same shape works for both gen-char (printable) and
;; gen-sym (letters-only).
(def ^:private printable-alphabet
  " !\"#$%&'()*+,-./0123456789:;<=>?@ABCDEFGHIJKLMNOPQRSTUVWXYZ[\\]^_`abcdefghijklmnopqrstuvwxyz{|}~")
(def ^:private printable-len (count printable-alphabet))
(def ^:private letter-alphabet "abcdefghijklmnopqrstuvwxyz")

(defn gen-char []
  (let [idx (mod (abs (next-u32)) printable-len)]
    (subs printable-alphabet idx (inc idx))))

(defn gen-str
  ([] (gen-str 0 40))
  ([min-len max-len]
   (let [len (+ min-len (mod (abs (next-u32)) (- max-len min-len -1)))]
     (apply str (repeatedly len gen-char)))))

(defn- gen-letter []
  (let [idx (mod (abs (next-u32)) 26)]
    (subs letter-alphabet idx (inc idx))))

(defn gen-sym
  ([] (gen-sym 1 16))
  ([min-len max-len]
   (let [first-c (gen-letter)
         rest-n  (+ min-len (mod (abs (next-u32)) (- max-len min-len -1)))
         rest-cs (repeatedly rest-n gen-letter)]
     (symbol (apply str (cons first-c rest-cs))))))

(defn gen-kw [] (keyword (str (gen-sym))))

(defn gen-prim
  "One of: int, bool, str, kw, sym, nil."
  []
  (case (mod (abs (next-u32)) 6)
    0 (gen-int)
    1 (gen-bool)
    2 (gen-str)
    3 (gen-kw)
    4 (gen-sym)
    5 nil))

(defn gen-vec
  ([] (gen-vec 0 8))
  ([min-n max-n]
   (let [n (+ min-n (mod (abs (next-u32)) (- max-n min-n -1)))]
     (vec (repeatedly n gen-prim)))))

(defn gen-map
  ([] (gen-map 0 8))
  ([min-n max-n]
   (let [n (+ min-n (mod (abs (next-u32)) (- max-n min-n -1)))]
     (into {} (repeatedly n #(vector (gen-kw) (gen-prim)))))))

(defn gen-nested
  "Recursively nested form: a vec/map/set whose leaves are gen-prim.
   DEPTH caps recursion."
  ([] (gen-nested 3))
  ([depth]
   (if (or (zero? depth) (zero? (mod (abs (next-u32)) 3)))
     (gen-prim)
     (case (mod (abs (next-u32)) 3)
       0 (vec (repeatedly (mod (abs (next-u32)) 5) #(gen-nested (dec depth))))
       1 (into {} (repeatedly (mod (abs (next-u32)) 5)
                              #(vector (gen-kw) (gen-nested (dec depth)))))
       2 (set (repeatedly (mod (abs (next-u32)) 5) gen-prim))))))
