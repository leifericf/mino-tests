;; gen_bc_control_shapes.clj -- control-flow BC compile path probes.
;;
;; Programs target try / catch / cond / nested let-loop-fn shapes.
;; The catch binding receives mino's diagnostic envelope; programs
;; only read fields the envelope is guaranteed to have (`:mino/kind`,
;; `:mino/message`) so the printed witness is deterministic across
;; runtimes.

(def ^:private control-templates
  [
   ;; Shape 1: try/catch with string throw
   (fn []
     (str "(println (try (throw (ex-info \"boom\" {:kind :user})) (catch e (:mino/kind (ex-data e)))))"))

   ;; Shape 2: cond chain
   (fn []
     (let [n (- (mod (abs (next-u32)) 20) 10)]
       (str "(defn classify [x]\n"
            "  (cond (neg? x) :neg\n"
            "        (zero? x) :zero\n"
            "        (< x 10) :small\n"
            "        :else :big))\n"
            "(println (classify " n "))")))

   ;; Shape 3: nested let with shadowed names
   (fn []
     (let [v (mod (abs (next-u32)) 100)]
       (str "(let [a " v "]\n"
            "  (let [a (* a 2)]\n"
            "    (let [a (+ a 10)]\n"
            "      (println a))))")))

   ;; Shape 4: nested loop with shadowed names
   (fn []
     (let [outer (+ 2 (mod (abs (next-u32)) 5))
           inner (+ 2 (mod (abs (next-u32)) 5))]
       (str "(let [total\n"
            "      (loop [i 0 acc 0]\n"
            "        (if (= i " outer ")\n"
            "          acc\n"
            "          (recur (inc i)\n"
            "                 (+ acc (loop [j 0 s 0]\n"
            "                          (if (= j " inner ") s (recur (inc j) (+ s j))))))))]\n"
            "  (println total))")))

   ;; Shape 5: try/catch around arithmetic that may overflow
   (fn []
     (str "(defn safe-div [a b]\n"
          "  (try (quot a b) (catch e :div-error)))\n"
          "(println (safe-div 10 2) (safe-div 10 0))"))

   ;; Shape 6: when chain
   (fn []
     (let [a (mod (abs (next-u32)) 100)
           b (mod (abs (next-u32)) 100)]
       (str "(defn maybe-add [x y]\n"
            "  (when (and (pos? x) (pos? y))\n"
            "    (+ x y)))\n"
            "(println (maybe-add " a " " b ") (maybe-add 0 0))")))

   ;; Shape 7: do-block with multiple side effects + final value
   (fn []
     (let [n (+ 1 (mod (abs (next-u32)) 10))]
       (str "(let [a (atom 0)]\n"
            "  (dotimes [_ " n "] (swap! a inc))\n"
            "  (println @a))")))

   ;; Shape 8: if with try-catch in each branch
   (fn []
     (let [n (- (mod (abs (next-u32)) 20) 10)]
       (str "(defn divide-safely [x]\n"
            "  (if (zero? x)\n"
            "    :zero\n"
            "    (try (quot 100 x) (catch e :err))))\n"
            "(println (divide-safely " n "))")))
   ])

(defn gen-control-shape []
  (let [t (nth control-templates (mod (abs (next-u32)) (count control-templates)))]
    (t)))

(defn gen-control-shapes [n]
  (vec (repeatedly n gen-control-shape)))
