;; CLJS parity stage 2, app side: the cohost-media frontend files as
;; .cljc. Checks, for each file named on the command line:
;;
;;   clj-reads    every form reads with the JVM reader at :clj
;;                features (the file is a well-formed cljc)
;;   cljs-reads   every form reads with the cljs reader at :cljs
;;   ns-shape     the :cljs-resolved ns form requires exactly the
;;                libraries the original .cljs required, and the
;;                :clj-resolved ns form requires none (the frontend's
;;                libraries are cljs-side, required via #?(:cljs ...))
;;
;;   dev/cljs_parity/run.sh app_cljc_check.clj <page.cljc> <tasks.cljc>
(require '[clojure.java.io :as io]
         '[cljs.vendor.clojure.tools.reader :as cljs-reader])

(defn read-all [read-one file]
  (with-open [r (java.io.PushbackReader. (io/reader file))]
    (loop [acc []]
      (let [f (read-one r)]
        (if (= ::eof f) acc (recur (conj acc f)))))))

(defn read-all-clj [file]
  (read-all (fn [r] (read {:eof ::eof :read-cond :allow :features #{:clj}} r)) file))

(defn read-all-cljs [file]
  ;; the vendored reader's defaults carry no js tag reader (the
  ;; compiler's env registers one); for a read-validity check a
  ;; stand-in is the honest binding
  (binding [cljs-reader/*data-readers*
            (assoc cljs-reader/default-data-readers
                   'js (fn [v] (list 'js-literal v)))]
    (let [opts {:read-cond :allow :features #{:cljs} :eof ::eof}]
      (read-all (fn [r] (cljs-reader/read opts r)) file))))

(defn required-libs [ns-form]
  ;; the lib specs of every :require clause in a resolved ns form
  (into #{}
        (comp (filter seq?)
              (filter #(= :require (first %)))
              (mapcat rest)
              (map (fn [spec] (if (sequential? spec) (first spec) spec))))
        ns-form))

;; per-file expectations: the :cljs-resolved and :clj-resolved require
;; sets of the ns form. "page" is the converted page.cljc (cljs-side
;; libs only, clj side none). "tasks" is the capstone's unified
;; cohost-media.tasks (src/), which the page's browser tasks moved
;; into: a real two-sided cljc whose arms must resolve as listed.
(def expected
  {"tasks" {:cljs '#{clojure.string eve.thread cohost-media.av
                     cohost-media.core cohost-media.media
                     cohost-media.state cohost-media.work
                     eve.net.platform eve.thread.net eve.thread.rung}
            :clj  '#{clojure.string eve.thread cohost-media.av
                     cohost-media.core cohost-media.media
                     cohost-media.state cohost-media.work
                     cohost-media.phash cohost-media.r128 eve.mem
                     eve.region}}
   "page"  {:cljs '#{clojure.string eve.process eve.thread eve.thread-host
                     eve.thread.domain eve.thread.meta eve.thread.net
                     eve.thread.rung cohost-media.core cohost-media.tasks
                     reagami.core}
            :clj  '#{}}})

(def results (atom []))
(defn check [label pass detail]
  (swap! results conj pass)
  (println (if pass "PASS" "FAIL") label (when-not pass (str " -- " detail))))

(doseq [file *command-line-args*]
  (let [base (last (clojure.string/split file #"/"))
        kind (first (clojure.string/split base #"\."))
        clj-forms (try (read-all-clj file) (catch Throwable t (str "THREW: " (ex-message t))))
        cljs-forms (try (read-all-cljs file) (catch Throwable t (str "THREW: " (ex-message t))))]
    (check (str base " clj-reads") (vector? clj-forms) (str clj-forms))
    (check (str base " cljs-reads") (vector? cljs-forms) (str cljs-forms))
    (when (vector? clj-forms)
      (check (str base " clj-side requires as expected")
             (= (get-in expected [kind :clj]) (required-libs (first clj-forms)))
             (str "got " (required-libs (first clj-forms)))))
    (when (vector? cljs-forms)
      (check (str base " cljs-side requires as expected")
             (= (get-in expected [kind :cljs]) (required-libs (first cljs-forms)))
             (str "got " (required-libs (first cljs-forms)))))))

(let [rs @results]
  (println "APP-CLJC-CHECK:" (count (filter identity rs)) "/" (count rs) "passed")
  (System/exit (if (every? identity rs) 0 1)))
