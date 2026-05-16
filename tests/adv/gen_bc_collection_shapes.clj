;; gen_bc_collection_shapes.clj -- collection ops BC fast-path probes.
;;
;; Programs target vec / map / set shapes where the BC compile path
;; specializes:
;;   * conj on tagged vs boxed vectors
;;   * assoc with duplicate keys (HAMT branching)
;;   * dissoc with missing keys
;;   * transient promotion via conj!
;;   * get / nth on various coll types
;;
;; Maps are read-back through keyword-keyed access (deterministic);
;; sets are read-back via `count` and `contains?` so iteration order
;; never reaches stdout.

(def ^:private coll-templates
  [
   ;; Shape 1: vec built via conj, summed via reduce
   (fn []
     (let [n (+ 1 (mod (abs (next-u32)) 50))]
       (str "(let [v (loop [i 0 acc []]\n"
            "          (if (= i " n ") acc (recur (inc i) (conj acc i))))]\n"
            "  (println (reduce + 0 v)))")))

   ;; Shape 2: vec slice via subvec
   (fn []
     (let [n (+ 4 (mod (abs (next-u32)) 20))
           lo (mod (abs (next-u32)) 3)
           hi (- n (mod (abs (next-u32)) 3))]
       (str "(let [v (vec (range " n "))]\n"
            "  (println (subvec v " lo " " hi ")))")))

   ;; Shape 3: assoc + dissoc round-trip (key membership is deterministic)
   (fn []
     (let [n (+ 1 (mod (abs (next-u32)) 10))]
       (str "(let [m (loop [i 0 acc {}]\n"
            "          (if (= i " n ")\n"
            "            acc\n"
            "            (recur (inc i) (assoc acc (keyword (str \"k\" i)) i))))]\n"
            "  (println (count m) (get m :k0) (get m :k" (dec n) ")))")))

   ;; Shape 4: get with default
   (fn []
     (str "(let [m {:a 1 :b 2 :c 3}]\n"
          "  (println (get m :a) (get m :missing 99)))"))

   ;; Shape 5: transient conj on vec
   (fn []
     (let [n (+ 1 (mod (abs (next-u32)) 30))]
       (str "(let [v (persistent!\n"
            "          (loop [i 0 t (transient [])]\n"
            "            (if (= i " n ") t (recur (inc i) (conj! t i)))))]\n"
            "  (println (count v) (first v) (last v)))")))

   ;; Shape 6: nth + count on vec
   (fn []
     (let [n (+ 5 (mod (abs (next-u32)) 20))
           idx (mod (abs (next-u32)) n)]
       (str "(let [v (vec (range " n "))]\n"
            "  (println (count v) (nth v " idx ")))")))

   ;; Shape 7: reduce-kv on map (keys come out in iteration order;
   ;; we sum values, which is order-invariant, so the comparison
   ;; stays deterministic)
   (fn []
     (let [n (+ 1 (mod (abs (next-u32)) 8))]
       (str "(let [m (loop [i 0 acc {}]\n"
            "          (if (= i " n ")\n"
            "            acc\n"
            "            (recur (inc i) (assoc acc (keyword (str \"k\" i)) (* i i)))))]\n"
            "  (println (reduce-kv (fn [acc _ v] (+ acc v)) 0 m)))")))

   ;; Shape 8: contains? on set
   (fn []
     (let [n (+ 1 (mod (abs (next-u32)) 10))]
       (str "(let [s (loop [i 0 acc #{}]\n"
            "          (if (= i " n ") acc (recur (inc i) (conj acc i))))]\n"
            "  (println (count s) (contains? s 0) (contains? s 999)))")))
   ])

(defn gen-collection-shape []
  (let [t (nth coll-templates (mod (abs (next-u32)) (count coll-templates)))]
    (t)))

(defn gen-collection-shapes [n]
  (vec (repeatedly n gen-collection-shape)))
