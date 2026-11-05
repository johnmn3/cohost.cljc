;; Eve-in-cohost unit 9 gate, the ClojureScript seat (Node), against the JVM:
;;
;;   clojure -Sdeps '{:paths ["src" "dev"] :deps {ring/ring-jetty-adapter {:mvn/version "1.12.2"}}}' \
;;     -M dev/eve_http_seats.clj <eve-checkout>
;;
;; builds dev/eve_http_node.cljs (cohost on self-hosted ClojureScript, Eve's
;; Node http / http-server adapters as cohost.http's leaves), then:
;; client side -- every program of eve-http-programs not JVM-only, evaluated
;;   unchanged: REF on plain JVM Clojure against Jetty; the cljs seat against
;;   the same Jetty; the cljs seat against the same handler served by Eve's
;;   Node server on the cljs seat. All three answers equal.
;; server side -- the Ring handler source served by Jetty from plain JVM
;;   Clojure and by Eve's Node http-server from the booted cljs namespace
;;   (cohost.ring), asked the same requests by the JDK's HttpClient.
(ns eve-http-seats
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [ring.adapter.jetty]
            [eve-http-programs :as programs]))

(def eve (or (first *command-line-args*) "../eve"))

(defn- build! []
  (let [paths ["src" "resources" "dev" (str eve "/adapters/http/node/src") (str eve "/adapters/http-server/node/src")]
        ;; ClojureScript 1.12.42's list of Node's built-in modules predates
        ;; worker_threads (eve.http's Node adapter parks on a worker); the
        ;; list is extended for this compile, and Node's own module loads
        r (sh/sh "clojure" "-Sdeps" (pr-str {:paths paths}) "-M:cljs"
                 "-e" "(require 'cljs.js-deps) (alter-var-root #'cljs.js-deps/native-node-modules conj \"worker_threads\")"
                 "-m" "cljs.main" "-co" "dev/eve_http_node.edn" "-c")]
    (when-not (zero? (:exit r))
      (println (:out r) (:err r))
      (throw (ex-info "cljs build failed" {})))))

(def ref-ns (create-ns 'eve-http-seats-ref))
(binding [*ns* ref-ns] (refer-clojure) (require '[clojure.java.io :as io]))
(defn ref-eval [src]
  (try [:ok (binding [*ns* ref-ns] (load-string src))]
       (catch Throwable e [:error (str (.getMessage e))])))

(defn- free-port [] (with-open [s (java.net.ServerSocket. 0)] (.getLocalPort s)))

(def checks (atom []))
(defn verdict! [label pass detail]
  (swap! checks conj pass)
  (println (if pass "PASS" "FAIL") label (if pass (str "=> " (pr-str detail)) detail)))

(defn- node-client [base]
  (let [out (str (java.io.File/createTempFile "eve-http-node" ".edn"))
        r (sh/sh "node" "target/eve-http-node/main.js" "client" base out)]
    (when-not (zero? (:exit r)) (println (:out r) (:err r)) (throw (ex-info "node client failed" {})))
    (into {} (edn/read-string (slurp out)))))

(defn- node-serve! [port]
  (let [p (.start (doto (ProcessBuilder. ["node" "target/eve-http-node/main.js" "serve" (str port)])
                    (.redirectErrorStream true)))
        rdr (io/reader (.getInputStream p))]
    (loop []
      (let [line (.readLine ^java.io.BufferedReader rdr)]
        (cond (nil? line) (throw (ex-info "node server ended before listening" {}))
              (.startsWith line "LISTENING") p
              :else (recur))))))

(def jdk-client (java.net.http.HttpClient/newHttpClient))
(defn ask [base [method path body headers]]
  (let [b (reduce (fn [^java.net.http.HttpRequest$Builder b [k v]] (.header b k v))
                  (java.net.http.HttpRequest/newBuilder (java.net.URI/create (str base path)))
                  headers)
        b (.method ^java.net.http.HttpRequest$Builder b method
                   (if body (java.net.http.HttpRequest$BodyPublishers/ofString body)
                       (java.net.http.HttpRequest$BodyPublishers/noBody)))
        r (.send ^java.net.http.HttpClient jdk-client (.build b) (java.net.http.HttpResponse$BodyHandlers/ofString))
        h (.headers r)]
    [(.statusCode r) (.orElse (.firstValue h "content-type") nil) (.orElse (.firstValue h "x-method") nil) (.body r)]))

(build!)
(def jetty (ring.adapter.jetty/run-jetty (second (ref-eval programs/handler-src)) {:port 0 :join? false :host "127.0.0.1"}))
(def jetty-base (str "http://127.0.0.1:" (.getLocalPort ^org.eclipse.jetty.server.ServerConnector (first (.getConnectors ^org.eclipse.jetty.server.Server jetty)))))
(def node-port (free-port))
(def node-proc (node-serve! node-port))
(def node-base (str "http://127.0.0.1:" node-port))

;; client side
(def cljs-x-jetty (node-client jetty-base))
(def cljs-x-eve (node-client node-base))
(doseq [[label src] programs/client
        :when (not (contains? programs/jvm-only label))]
  (let [r (ref-eval (str "(let [base \"" jetty-base "\"] " src ")"))
        c (get cljs-x-jetty label)
        ce (get cljs-x-eve label)]
    (verdict! (str "client: " label) (and (= :ok (first r)) (= r c ce))
              (if (and (= :ok (first r)) (= r c ce)) (second r)
                  (str "\n      REF " (pr-str r) "\n      CLJS " (pr-str c) "\n      CLJS x Eve node server " (pr-str ce))))))

;; server side
(doseq [[label req] programs/server]
  (let [j (ask jetty-base req)
        e (ask node-base req)]
    (verdict! (str "server: " label) (= j e)
              (if (= j e) j (str "\n      JETTY " (pr-str j) "\n      EVE (node) " (pr-str e))))))

(.stop ^org.eclipse.jetty.server.Server jetty)
(.destroy ^Process node-proc)
(let [cs @checks]
  (println (str "unit 9 gate (cljs seat, Node): " (count (filter true? cs)) "/" (count cs) " PASS"
                "; JVM-only client programs not run on this seat: " (count programs/jvm-only))))
(shutdown-agents)
(System/exit (if (every? true? @checks) 0 1))
