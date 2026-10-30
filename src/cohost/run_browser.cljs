(ns cohost.run-browser
  "The browser entry: boot the stock core.clj in the page (self-hosted
   ClojureScript), print the report and the battery to the console and to
   the page. core.clj is fetched from resources/ relative to the page."
  (:require [cohost.hosts.cljs]
            [cohost.boot :as boot]
            [cohost.battery :as battery]
            [cohost.lib-battery :as lib-battery]))

(enable-console-print!)

(defn- append! [s]
  (when-let [el (.getElementById js/document "out")]
    (set! (.-textContent el) (str (.-textContent el) s "\n"))))

(defn ^:export run []
  (let [t0 (.now js/performance)
        lines (atom [])]
    (set! *print-fn* (fn [s] (swap! lines conj s) (.log js/console s)))
    (let [report (boot/boot! {:root "resources/cohost/clojure-1.12.0"
                              :verbose (boolean (re-find #"verbose" (str (.-search js/location))))})]
      (boot/print-report report)
      (boot/make-user-ns!)
      (battery/run-battery! boot/eval-str)
      (lib-battery/run-lib-battery! boot/eval-str)
      (println (str "elapsed: " (js/Math.round (- (.now js/performance) t0)) " ms"))
      (append! (apply str (interpose "\n" @lines)))
      (set! (.-clojureHostDone js/window) true))))

(set! (.-onload js/window) run)
