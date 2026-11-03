(def vals1 [0 1 -1 2 2147483647 -2147483648 2147483648 9223372036854775807 -9223372036854775808
            1.5 2.0 -0.0 0.0 ##NaN ##Inf ##-Inf (/ 1 2) (/ -3 2) "a" nil :k])
(def fns1 [["inc" inc] ["dec" dec] ["even?" even?] ["odd?" odd?] ["pos?" pos?] ["neg?" neg?] ["zero?" zero?]])
(def pairs [[1 2] [2 1] [1 1] [1 1.0] [1.5 1] [##NaN 1] [1 ##NaN] [##NaN ##NaN] [(/ 1 2) 0.5] [(/ 1 2) (/ 1 3)]
            [9223372036854775807 -9223372036854775808] [-0.0 0.0] [2147483648 2147483647] [1 "a"] [nil 1] [:a :a] ["a" "a"]])
(def fns2 [["<" <] [">" >] ["<=" <=] [">=" >=] ["==" ==] ["=" =] ["not=" not=]])
(defn run-edges []
  (doseq [[nm f] fns1 v vals1]
    (println nm (pr-str v) "->" (T (f v))))
  (doseq [[nm f] fns2 [a b] pairs]
    (println nm (pr-str a) (pr-str b) "->" (T (f a b))))
  (doseq [[nm f] fns2]
    (println nm "1 2 3 ->" (T (f 1 2 3)) "3 2 1 ->" (T (f 3 2 1)) "1 ->" (T (f 1)))))
(run-edges)
