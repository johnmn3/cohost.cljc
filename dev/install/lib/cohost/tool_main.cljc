;; cohost.tool-main — the entry namespace the installer AOT-compiles
;; into the `cohost` command (jank compile cohost.tool-main -O3
;; --runtime dynamic). All of the work is cohost.tool's.
(ns cohost.tool-main
  (:require [cohost.tool]))

(defn -main [& args]
  (apply cohost.tool/-main args))
