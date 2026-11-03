(ns demo.main
  "cohost :<target> -M -m demo.main <name>-user -> hello, <Host>-USER!"
  (:require [clojure.tools.cli :as cli]
            [clojure.string :as str]
            [greeter.core :as greeter]))

(def options
  [["-g" "--greeting WORD" "the greeting" :default "hello"]
   ["-n" "--times N" "how many lines" :default 1 :parse-fn parse-long]])

(defn greeting [word user]
  (str word ", " (greeter/name-of user) "!"))

(defn -main [& args]
  (let [{:keys [options arguments errors]} (cli/parse-opts args options)]
    (if (seq errors)
      (do (println (str/join "\n" errors)) (System/exit 2))
      (dotimes [_ (:times options)]
        (println (greeting (:greeting options) (or (first arguments) "jvm-user")))))))

(defn run
  "-X:run: the alias's :exec-args, then the command line's key value pairs."
  [{:keys [user] word :greeting}]
  (println (greeting word user)))
