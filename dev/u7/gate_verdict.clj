;; Unit 7 GATE verdict: reads the RESULT lines dev/u7/gate.sh's processes printed
;; and states each comparison as printed. bb dev/u7/gate_verdict.clj <dir> <N>
(require '[clojure.edn :as edn] '[clojure.string :as str])
(let [[dir n-s] *command-line-args*
      n (parse-long n-s)
      res (fn [f] (some->> (str dir "/" f) slurp str/split-lines
                           (filter #(str/starts-with? % "RESULT "))
                           first (#(subs % 7)) edn/read-string))
      rj (res "race-jvm.log") rk (res "race-jank.log")
      r1 (res "read-jank-1.log") r2 (res "read-jvm-2.log") r3 (res "read-jank-2.log")
      digest (fn [r] (select-keys r [:seal :sha256]))
      row (fn [label ok detail] (println (if ok "OK  " "FAIL") label detail) (boolean ok))
      oks [(row "race: both sides printed" (and rj rk) "")
           (row "race: final value exact (jvm side)"
                (= [(* 2 n) n n] ((juxt :count :jvm :jank) (:value rj))) (pr-str (:value rj)))
           (row "race: value equal from both sides" (= (:value rj) (:value rk)) "")
           (row "race: seal identical from both processes" (= (:seal rj) (:seal rk))
                (pr-str [(:seal rj) (:seal rk)]))
           (row "race: sha256 content digest identical" (= (:sha256 rj) (:sha256 rk))
                (pr-str [(:sha256 rj) (:sha256 rk)]))
           (row "restart: jank rejoin sees the killed jvm's mark"
                (= ["jvm-killed"] (:marks (:value r1))) (pr-str (:marks (:value r1))))
           (row "restart: jvm rejoin sees both killed processes' marks"
                (= ["jvm-killed" "jank-killed"] (:marks (:value r2))) (pr-str (:marks (:value r2))))
           (row "restart: final root digests identical jvm vs jank" (= (digest r2) (digest r3))
                (pr-str [(digest r2) (digest r3)]))
           (row "restart: final value equal jvm vs jank" (= (:value r2) (:value r3)) "")]]
  (println (str "GATE-U7: " (count oks) " rows, " (count (remove true? oks)) " failures")))
