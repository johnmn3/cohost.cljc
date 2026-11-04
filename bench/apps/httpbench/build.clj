(ns build
  "cohost -T:build build [:target :jank]: tools.build, with cohost.build's
   per-target artifact (doc/CLI.md, \"Building\")."
  (:require [cohost.build :as cb]))

(defn build [opts]
  (cb/build (merge {:main 'httpbench.core} opts)))
