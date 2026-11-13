(ns cohost.squint-main
  "The squint body's entry: install the adapter, mirror the
   compiled cohost.shim / cohost.boot vars into the registry,
   then dispatch:
     run <args...>  cohost.run/-main (boot + battery / --eval)
     cli <args...>  cohost.cli/-main (the body CLI)
   The generated main.mjs calls -main with the process argv."
  (:require [cohost.hosts.squint :as squint-host]
            [cohost.squint-mirror :as mirror]
            [cohost.run :as run]
            [cohost.cli :as cli]))

(defn -main [& args]
  (squint-host/install!)
  (mirror/mirror!)
  (let [[mode & more] args]
    (case mode
      "run" (apply run/-main more)
      "cli" (apply cli/-main more)
      (do (println "usage: squint body: run|cli <args...>")
          (set! (.-exitCode js/process) 2)))))
