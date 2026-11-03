(ns cohost.template
  "deps-new :data-fn of the cohost/app template.")

(defn data
  "The cohost checkout bin/cohost runs from (it exports COHOST_HOME): the
   generated build alias depends on it for cohost.build."
  [_]
  {:cohost-root (or (System/getenv "COHOST_HOME") "/path/to/cohost.cljc")})
