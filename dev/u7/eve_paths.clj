;; Unit 7: print the source roots for one seat from ONE Eve tree.
;;   bb dev/u7/eve_paths.clj <cohost-dir> <eve-root> jvm   -> quoted EDN strings, space-separated
;;   bb dev/u7/eve_paths.clj <cohost-dir> <eve-root> jank  -> a ':'-joined module path
(require '[clojure.edn :as edn] '[clojure.string :as str])
(let [[here eve seat] *command-line-args*
      hosts (edn/read-string (slurp (str eve "/hosts.edn")))
      roots (concat [(str eve "/src")] (map #(str eve "/" %) (get hosts (keyword seat))))]
  (case seat
    "jvm" (println (str/join " " (map pr-str (cons (str here "/src") roots))))
    "jank" (println (str/join ":" roots))))
