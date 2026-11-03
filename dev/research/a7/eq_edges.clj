(defmacro T [form] `(try (pr-str ~form) (catch IllegalArgumentException e# :IAE) (catch ClassCastException e# :CCE) (catch Exception e# :EX)))
["setup" (T (do (defrecord EqR [a]) (deftype EqT [a]) :ok))]
["numbers" (T [(= 1 1) (= 1 2) (= 1 1.0) (= 1.0 1.0) (= 1 (identity 1)) (= 2147483648 2147483648) (= (/ 1 2) (/ 2 4)) (= (/ 1 2) 0.5) (= ##NaN ##NaN) (= -0.0 0.0) (= 1N 1)])]
["nil bool" (T [(= nil nil) (= nil false) (= false false) (= true (= 1 1)) (= nil 0)])]
["chars strings" (T [(= \a \a) (= \a "a") (= "ab" "ab") (= "ab" (str "a" "b")) (= (nth "xy" 0) \x) (= \x (nth "xy" 0))])]
["keywords symbols" (T [(= :a :a) (= :a (keyword "a")) (= 'a 'a) (= :a 'a) (= :a/b :a/b)])]
["collections" (T [(= [1 2] [1 2]) (= [1 2] '(1 2)) (= [1 2] (seq [1 2])) (= {} {}) (= {:a 1} {:a 1}) (= #{1} #{1}) (= [] '()) (= [1] [1.0]) (= (range 3) [0 1 2]) (= {:a [1]} {:a '(1)})])]
["records types" (T [(= (->EqR 1) (->EqR 1)) (= (->EqR 1) (->EqR 2)) (= (->EqR 1) {:a 1}) (let [t (->EqT 1)] [(= t t) (= t (->EqT 1))])])]
["arities and values" (T [(= 1) (= 1 1 1) (= 1 1 2) (apply = [1 1]) (map = [1 2] [1 3]) (filter #(= % 2) [1 2 3]) (let [f =] (f :a :a))])]
