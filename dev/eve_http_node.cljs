;; Unit 9, the ClojureScript seat (Node): cohost booted on self-hosted
;; ClojureScript with Eve's Node HTTP adapters as cohost.http's leaves.
;; Driven by dev/eve_http_seats.clj; two modes:
;;   node target/eve-http-node/main.js client <base> <out.edn>
;;     every client program of eve-http-programs not JVM-only, evaluated
;;     unchanged in the booted user namespace with `base` bound; the
;;     answers written to out.edn as [[label [:ok value] | [:error msg]] ...]
;;   node target/eve-http-node/main.js serve <port>
;;     the Ring handler source evaluated in the booted namespace and served
;;     by Eve's Node http-server through cohost.ring; prints LISTENING <port>
(ns eve-http-node
  (:require [cohost.hosts.auto]
            [cohost.adapter :as host]
            [cohost.boot :as boot]
            [cohost.http-eve]
            [cohost.ring]
            [eve-http-programs]
            ["fs" :as fs]))

(defn- boot! []
  (let [installed (cohost.http-eve/install!)
        report (boot/boot! {})]
    (boot/make-user-ns!)
    (println "Eve http leaves installed:" installed)
    (boot/print-report report)))

(defn- cohost-eval [src]
  (host/try-all (fn [] [:ok (boot/eval-str src {:session? false})])
                (fn [e] [:error (str (host/error-message e))])))

(defn -main [mode & args]
  (boot!)
  (case mode
    "client"
    (let [[base out] args
          answers (vec (for [[label src] eve-http-programs/client
                             :when (not (contains? eve-http-programs/jvm-only label))]
                         [label (cohost-eval (str "(let [base \"" base "\"] " src ")"))]))]
      (fs/writeFileSync out (pr-str answers))
      (println "client programs run:" (count answers)))
    "serve"
    (let [port (js/parseInt (first args))
          [k h] (cohost-eval eve-http-programs/handler-src)]
      (when-not (= :ok k) (throw (ex-info (str "handler: " h) {})))
      (let [srv (cohost.ring/run-server h {:port port})]
        (.then (:bound srv) (fn [p] (println "LISTENING" p)))))))

(set! *main-cli-fn* -main)
