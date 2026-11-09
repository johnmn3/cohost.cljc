;; O5 — :inline survival audit (cohost-jvm-speed lane, ATTRIBUTION §6/O5).
;;
;; T3 proved a lost :inline costs x16-27 on booted loops. This probe
;; enumerates every name stock core.clj defines with inlining
;; (definline, or defn carrying :inline in its attr map), boots the
;; stock core through cohost, and checks the booted var's meta still
;; carries :inline / :inline-arities. Output: one EDN line per name
;; plus a summary line; a name in :missing is a pure-loss bug.
;;
;;   clojure -Sdeps '{:paths ["src" "resources" "dev"]}' -M -m perf.o5-inline-audit
(ns perf.o5-inline-audit
  (:require [clojure.java.io :as io]))

(defn- source-inline-names
  "-> {name {:src :definline|:defn}} for clojure/core.clj at path."
  [path]
  (let [r (java.io.PushbackReader. (io/reader path))]
    (binding [*ns* (create-ns 'perf.o5-scratch)
              *read-eval* false]
      (loop [out {}]
        (let [form (read {:eof ::eof} r)]
          (if (= ::eof form)
            out
            (recur
              (if (and (seq? form) (symbol? (first form)))
                (let [head (first form) nm (second form)]
                  (cond
                    (= 'definline head)
                    (assoc out nm {:src :definline})

                    (#{'defn 'defn-} head)
                    (if (some (fn [x] (and (map? x) (contains? x :inline))) form)
                      (assoc out nm {:src :defn})
                      out)

                    :else out))
                out))))))))

(defn -main [& _]
  (let [src (source-inline-names "resources/cohost/clojure-1.12.0/clojure/core.clj")
        _ (require 'cohost.hosts.auto 'cohost.boot 'cohost.bench)
        boot! @(resolve 'cohost.boot/boot!)
        make-user-ns! @(resolve 'cohost.boot/make-user-ns!)
        eval-str @(resolve 'cohost.boot/eval-str)
        load-libs! @(resolve 'cohost.bench/load-libs!)]
    (boot! {:root "resources/cohost/clojure-1.12.0"})
    (make-user-ns!)
    (load-libs!)
    (let [rows (mapv (fn [[nm info]]
                       (let [q (str "(let [v (resolve 'clojure.core/" nm ")]"
                                    " (when v {:inline (boolean (:inline (meta v)))"
                                    "           :inline-arities (boolean (:inline-arities (meta v)))}))")
                             r (try (eval-str q) (catch Throwable e (str "ERR " (.getMessage e))))]
                         {:name nm :src (:src info) :booted r}))
                     (sort-by (comp str key) src))
          missing (filterv (fn [{:keys [booted]}]
                             (not (and (map? booted) (:inline booted))))
                           rows)]
      (doseq [row rows] (prn row))
      (prn {:summary true :checked (count rows) :missing (mapv :name missing)})
      (println "O5-AUDIT-DONE")))
  (System/exit 0))
