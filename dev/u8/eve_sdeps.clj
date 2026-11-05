;; bb dev/u8/eve_sdeps.clj [extra-path ...] -> the -Sdeps EDN putting Eve's hosts.edn :cljs row
;; (plus any extra source paths) on the cljs compile classpath (dev/eve-cljs/eve_paths.clj).
(load-file "dev/eve-cljs/eve_paths.clj")
(let [m (clojure.edn/read-string (eve-paths/eve-cljs-sdeps "."))]
  (println (pr-str (update m :paths into *command-line-args*))))
