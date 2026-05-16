;; gen.clj -- seeded shape generators for adversarial probes.
;;
;; The RNG is a linear congruential generator parameterised in u32
;; space (MMIX / Knuth constants). xorshift64* would be the natural
;; choice, but mino's BC compiler currently promotes a 64-bit
;; bit-shift-left to bigint inside compiled fns (see .local/BUGS.md
;; in the main repo), which trips bit-xor's int-only check. A u32 LCG
;; sidesteps that and is plenty random for shape-fuzzing.

(def *rng* (atom 1))

(defn seed!
  "Reset the RNG seed. Stored as a positive i32; zero is OK for
   an LCG (the increment carries it out of zero)."
  [s]
  (reset! *rng* (bit-and 0xFFFFFFFF s)))

(defn next-u32
  "LCG step: x' = (a*x + c) mod 2^32 with MMIX constants."
  []
  (let [x  @*rng*
        n  (bit-and 0xFFFFFFFF
                    (+ (unchecked-multiply x 1103515245)
                       12345))]
    (reset! *rng* n)
    n))

(defn next-u64
  "Compose a 64-bit value from two LCG steps. Used by callers that
   want a wider range; in this codebase nearly everyone uses
   next-u32 directly."
  []
  (let [hi (next-u32)
        lo (next-u32)]
    (+ (bit-shift-left hi 32) lo)))

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
