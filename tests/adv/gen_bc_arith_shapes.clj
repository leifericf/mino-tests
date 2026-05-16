;; gen_bc_arith_shapes.clj -- arithmetic/bitwise BC fast-path probes.
;;
;; Programs target shapes where the BC fast-path has diverged
;; historically:
;;   * bit-shift-left with shift amounts at 25/30/40/50/63 (v0.255.5)
;;   * bit-and / bit-or / bit-xor chains against large operands
;;   * mixed signed-overflow at LLONG_MAX / LLONG_MIN
;;   * inc / dec on boxed-int values
;;   * arithmetic on mixed int / bigint promotions
;;
;; All shapes stay deterministic and pure; the printed result is
;; the witness compared across the quad.

(def ^:private LARGE-INTS [1 0xFF 0xFFFF 0xFFFFFF 0xFFFFFFFF
                            0xFFFFFFFFFF 0x7FFFFFFFFFFFFFFE])
(def ^:private SHIFT-AMTS [0 1 7 8 15 16 24 25 30 31 32 40 50 62])

(def ^:private arith-templates
  [
   ;; Shape 1: bit-shift-left chain (the v0.255.5 anchor)
   (fn []
     (let [base (nth LARGE-INTS (mod (abs (next-u32)) (count LARGE-INTS)))
           amt  (nth SHIFT-AMTS  (mod (abs (next-u32)) (count SHIFT-AMTS)))]
       (str "(defn shl [x] (bit-shift-left x " amt "))\n"
            "(println (shl " base "))")))

   ;; Shape 2: bit-xor chain (compresses if BC fast-path miscomputes)
   (fn []
     (let [a (nth LARGE-INTS (mod (abs (next-u32)) (count LARGE-INTS)))
           b (nth LARGE-INTS (mod (abs (next-u32)) (count LARGE-INTS)))
           c (nth LARGE-INTS (mod (abs (next-u32)) (count LARGE-INTS)))]
       (str "(defn xor3 [x y z] (bit-xor x (bit-xor y z)))\n"
            "(println (xor3 " a " " b " " c "))")))

   ;; Shape 3: bit-and chain
   (fn []
     (let [a (nth LARGE-INTS (mod (abs (next-u32)) (count LARGE-INTS)))
           b (nth LARGE-INTS (mod (abs (next-u32)) (count LARGE-INTS)))]
       (str "(defn and-of [x y] (bit-and x y))\n"
            "(println (and-of " a " " b "))")))

   ;; Shape 4: bit-or chain
   (fn []
     (let [a (nth LARGE-INTS (mod (abs (next-u32)) (count LARGE-INTS)))
           b (nth LARGE-INTS (mod (abs (next-u32)) (count LARGE-INTS)))]
       (str "(defn or-of [x y] (bit-or x y))\n"
            "(println (or-of " a " " b "))")))

   ;; Shape 5: unsigned-bit-shift-right
   (fn []
     (let [a (nth LARGE-INTS (mod (abs (next-u32)) (count LARGE-INTS)))
           amt (nth SHIFT-AMTS (mod (abs (next-u32)) (count SHIFT-AMTS)))]
       (str "(defn ushr [x] (unsigned-bit-shift-right x " amt "))\n"
            "(println (ushr " a "))")))

   ;; Shape 6: inc/dec loop on boxed ints
   (fn []
     (let [start (- (mod (abs (next-u32)) 1000) 500)
           n     (+ 1 (mod (abs (next-u32)) 20))]
       (str "(defn count-up [v n]\n"
            "  (loop [i 0 x v] (if (= i n) x (recur (inc i) (inc x)))))\n"
            "(println (count-up " start " " n "))")))

   ;; Shape 7: arith inside an if-cond (the v0.255.5 BC bug shape)
   (fn []
     (let [shift (nth SHIFT-AMTS (mod (abs (next-u32)) (count SHIFT-AMTS)))]
       (str "(defn maybe-shift [x flag]\n"
            "  (if flag (bit-shift-left x " shift ") x))\n"
            "(println (maybe-shift 3 true))\n"
            "(println (maybe-shift 5 false))")))

   ;; Shape 8: rem / mod with negative operands
   (fn []
     (let [a (- (mod (abs (next-u32)) 200) 100)
           b (+ 1 (mod (abs (next-u32)) 20))]
       (str "(defn r [x y] (rem x y))\n"
            "(defn m [x y] (mod x y))\n"
            "(println (r " a " " b ") (m " a " " b "))")))
   ])

(defn gen-arith-shape []
  (let [t (nth arith-templates (mod (abs (next-u32)) (count arith-templates)))]
    (t)))

(defn gen-arith-shapes [n]
  (vec (repeatedly n gen-arith-shape)))
