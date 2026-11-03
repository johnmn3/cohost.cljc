;; writes num_edges_cohost.clj: every unary/binary numeric core fn on the
;; battery's edge cases, at a call head and as a value, exceptions by class.
(def vals1 ["0" "1" "-1" "2147483647" "-2147483648" "2147483648" "9223372036854775807" "(dec -9223372036854775807)"
            "1.5" "2.0" "-0.0" "##NaN" "##Inf" "(/ 1 2)" "(/ -3 2)" "1N" "\"a\"" "nil" ":k"])
(def fns1 ["inc" "dec" "even?" "odd?" "pos?" "neg?" "zero?"])
(def pairs [["1" "2"] ["2" "1"] ["1" "1"] ["1" "1.0"] ["1.5" "1"] ["##NaN" "1"] ["(/ 1 2)" "0.5"] ["(/ 1 2)" "(/ 1 3)"]
            ["9223372036854775807" "(dec -9223372036854775807)"] ["-0.0" "0.0"] ["2147483648" "2147483647"] ["1N" "1"] ["1" "\"a\""] ["nil" "1"] [":a" ":a"]])
(def fns2 ["<" ">" "<=" ">=" "==" "=" "not="])
(println "(defmacro T [form] `(try (pr-str ~form) (catch IllegalArgumentException e# :IAE) (catch ClassCastException e# :CCE) (catch ArithmeticException e# :AE) (catch NullPointerException e# :NPE) (catch Exception e# :EX)))")
(println "(defn run [] [")
(doseq [f fns1 v vals1] (println (format "[\"%s %s\" (T (%s %s)) (T (let [g %s] (g %s)))]" f (str/replace v "\"" "\\\"") f v f v)))
(doseq [f fns2 [a b] pairs] (println (format "[\"%s %s %s\" (T (%s %s %s)) (T (let [g %s] (g %s %s)))]" f (str/replace a "\"" "\\\"") (str/replace b "\"" "\\\"") f a b f a b)))
(doseq [f fns2] (println (format "[\"%s 1 2 3\" (T (%s 1 2 3)) (T (%s 3 2 1)) (T (%s 1 1.0 1)) (T (let [g %s] [(g 1 2 3) (g 1)]))]" f f f f f)))
(println "[\"map filter\" (T (count (filter odd? (map inc (range 1000)))))]")
(println "])")
