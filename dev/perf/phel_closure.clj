;; bb dev/perf/phel_closure.clj <entry-ns> <src-dir>... : the namespaces <entry-ns> requires, transitively,
;; read with the :phel reader feature, one "<ns> <file>" per line (the files phel build should compile).
(require '[babashka.fs :as fs] '[clojure.string :as str])
(def entry (symbol (first *command-line-args*)))
(def dirs (rest *command-line-args*))
(defn file-of [ns]
  (let [base (str/replace (str/replace (str ns) "-" "_") "." "/")]
    (some (fn [d] (some #(let [f (str d "/" base %)] (when (fs/exists? f) f)) [".phel" ".cljc"])) dirs)))
(defn ns-form [f]
  (let [r (java.io.PushbackReader. (java.io.StringReader. (slurp f)))]
    (loop []
      (let [x (read {:eof ::eof :read-cond :allow :features #{:phel}} r)]
        (cond (= x ::eof) nil
              (and (seq? x) (= 'ns (first x))) x
              :else (recur))))))
(defn requires [form]
  (for [clause (rest form) :when (and (seq? clause) (= :require (first clause)))
        spec (rest clause)]
    (if (sequential? spec) (first spec) spec)))
(loop [todo [entry] seen #{}]
  (if-let [[n & more] (seq todo)]
    (if (seen n)
      (recur more seen)
      (let [f (file-of n)]
        (if-not f
          (recur more (conj seen n)) ; a Phel namespace (phel.*), not in the source dirs
          (do (println n f)
              (recur (concat more (requires (ns-form f))) (conj seen n))))))
    nil))
