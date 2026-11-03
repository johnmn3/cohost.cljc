(ns greeter.core
  "A :local/root library: a user name like \"cljw-user\" -> \"ClojureWasm-USER\"."
  (:require [clojure.string :as str]))

(def host-names
  {"jvm" "JVM" "jolt" "jolt" "lpy" "Basilisp" "cljs" "ClojureScript" "cljw" "ClojureWasm"
   "phel" "Phel" "lg" "let-go" "jank" "jank" "cljrs" "clojurust"})

(defn name-of
  "The host part of user spelled out, the rest upper-cased."
  [user]
  (let [[host more] (str/split user #"-" 2)]
    (str (get host-names host host)
         (when more (str "-" (str/upper-case more))))))
