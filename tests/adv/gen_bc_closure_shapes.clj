;; gen_bc_closure_shapes.clj -- closure-capture pattern templates.
;;
;; Programs are deterministic, pure, and exercise closure capture
;; shapes the BC compile path has historically diverged on:
;;   * fn-returning-fn (outer captures, inner doesn't)
;;   * let-binding then fn (closure over let local)
;;   * loop+recur with fn captured per-iteration
;;   * dotimes with side-effect-recording capture
;;   * self-recursion (fn body references its own name)
;;   * nested closures inside defn body
;;
;; The bug v0.255.0 fixed (closures inside loop+recur inside defn all
;; captured iteration-0's value) lives in shape 3.

(def ^:private closure-templates
  [
   ;; Shape 1: fn returning fn (closure over outer-fn's param)
   (fn [x n]
     (str "(defn outer [v] (fn [] v))\n"
          "(println (mapv (fn [i] ((outer i))) [" (apply str (interpose " " (range x x (+ x n)))) "]))"))

   ;; Shape 2: let then fn (closure over let-local)
   (fn [x _]
     (str "(let [a " x " b (* " x " 2)]\n"
          "  (println ((fn [] (+ a b)))))"))

   ;; Shape 3: loop+recur with per-iteration fn capture
   (fn [_ n]
     (str "(defn build-closures [n]\n"
          "  (loop [i 0 acc []]\n"
          "    (if (= i n) acc (recur (inc i) (conj acc (fn [] i))))))\n"
          "(println (mapv (fn [f] (f)) (build-closures " n ")))"))

   ;; Shape 4: dotimes-style fan-out via atom collector
   (fn [_ n]
     (str "(let [results (atom [])]\n"
          "  (dotimes [i " n "] (swap! results conj i))\n"
          "  (println @results))"))

   ;; Shape 5: self-recursive fn captures its own name
   (fn [x _]
     (str "(defn self-rec [n acc]\n"
          "  (if (zero? n) acc (recur (dec n) (conj acc n))))\n"
          "(println (self-rec " x " []))"))

   ;; Shape 6: nested fn inside defn body, closure over param
   (fn [x _]
     (str "(defn outer [v]\n"
          "  (let [inner (fn [k] (+ v k))]\n"
          "    (mapv inner [1 2 3])))\n"
          "(println (outer " x "))"))

   ;; Shape 7: closure inside loop, used after loop ends
   (fn [_ n]
     (str "(let [fns (loop [i 0 acc []]\n"
          "            (if (= i " n ")\n"
          "              acc\n"
          "              (recur (inc i)\n"
          "                     (conj acc (fn [k] (* i k))))))]\n"
          "  (println (mapv (fn [f] (f 10)) fns)))"))

   ;; Shape 8: doubly-nested closure (outer captures, inner captures both)
   (fn [x _]
     (str "(defn outer [a]\n"
          "  (fn [b]\n"
          "    (fn [c] (+ a b c))))\n"
          "(println (((outer " x ") " (* x 2) ") " (* x 3) "))"))
   ])

(defn gen-closure-shape
  "Pick one template at random and parameterise it via the seeded RNG."
  []
  (let [t (nth closure-templates (mod (abs (next-u32))
                                      (count closure-templates)))
        x (+ 1 (mod (abs (next-u32)) 16))
        n (+ 2 (mod (abs (next-u32)) 6))]
    (t x n)))

(defn gen-closure-shapes
  "Return N seeded closure-shape programs."
  [n]
  (vec (repeatedly n gen-closure-shape)))
