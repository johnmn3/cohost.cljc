;; A script: cohost :<target> script.clj a b
(require '[greeter.core :as greeter] '[clojure.string :as str])
(println (str/join " " (map greeter/name-of *command-line-args*)))
