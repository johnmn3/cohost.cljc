(ns seam.throwmain
  "Gate probe #9's fixture: a -main that throws uncaught, so the AOT
   binary's exit code and error report can be measured against the
   same throw through the launcher.")

(defn -main [& args]
  (throw (ex-info "kaboom from seam.throwmain" {:fixture :aot-throw})))
