;; Eve-in-cohost unit 9 gate, JVM seat: the java.net HTTP surface and Ring,
;; behind cohost's shim, answered by Eve's HTTP layer.
;;
;;   clojure -Sdeps '{:paths ["src" "dev" "<eve>/adapters/http/java/src" "<eve>/adapters/http-server/jdk/src"]
;;                    :deps {ring/ring-jetty-adapter {:mvn/version "1.12.2"}}}' -M eve_http_check.clj
;;
;; (<eve>: a checkout of github.com/SeniorCareMarket/eve.) cohost.http-eve
;; installs Eve's eve.http / eve.http-server as cohost.http's leaves; the
;; stock core is booted on the JVM seat; every program below is one source
;; string, evaluated twice with the program unchanged:
;;   REF    -- plain JVM Clojure (the JDK's own java.net classes), in a
;;             namespace cohost never touched;
;;   COHOST -- the booted user namespace: the codemod sends java.net's
;;             constructors, statics and members to the shim, the shim to
;;             cohost.http, cohost.http to Eve's leaf.
;; Client side: each program runs REF and COHOST against the same Jetty
;; server (ring-jetty-adapter, the JVM's reference Ring host) and COHOST
;; again against the same handler served by Eve (cohost.ring) -- three
;; answers, all equal. Server side: one Ring handler source, served by
;; Jetty from plain JVM Clojure and by Eve's HTTP server from the booted
;; namespace, asked the same requests by the JDK's own HttpClient; the
;; answers compared.
;; The harness has its own namespace: the booted `user` namespace refers
;; cohost.core's macros, which would send this file's own interop to the shim.
(ns eve-http-check
  (:require [cohost.hosts.jvm]
            [cohost.boot :as boot]
            [cohost.policy]
            [cohost.adapter :as host]
            [cohost.http-eve]
            [cohost.ring]
            [ring.adapter.jetty]
            [eve-http-programs]))

(println "Eve http leaves installed:" (cohost.http-eve/install!))
(def report (boot/boot! {:host-lacks cohost.policy/thread-names}))
(boot/print-report report)
(boot/make-user-ns!)
(in-ns 'eve-http-check)

(defn cohost-eval [src]
  (host/try-all (fn [] [:ok (boot/eval-str src {:session? false})])
                (fn [e] [:error (str (host/error-message e))])))

(def ref-ns (create-ns 'eve-http-ref))
(binding [*ns* ref-ns] (refer-clojure) (require '[clojure.java.io :as io]))
(defn ref-eval [src]
  (try [:ok (binding [*ns* ref-ns] (load-string src))]
       (catch Throwable e [:error (str (.getMessage e))])))

;; --- the Ring handler, one source for both servers -----------------------
(def ref-handler (second (ref-eval eve-http-programs/handler-src)))
(def cohost-handler (let [[k v] (cohost-eval eve-http-programs/handler-src)] (if (= :ok k) v (throw (ex-info (str "handler: " v) {})))))

(def jetty (ring.adapter.jetty/run-jetty ref-handler {:port 0 :join? false :host "127.0.0.1"}))
(def jetty-port (.getLocalPort ^org.eclipse.jetty.server.ServerConnector (first (.getConnectors ^org.eclipse.jetty.server.Server jetty))))
(def eve-srv (cohost.ring/run-server cohost-handler {:port 0}))
(def jetty-base (str "http://127.0.0.1:" jetty-port))
(def eve-base (str "http://127.0.0.1:" (:port eve-srv)))

(def checks (atom []))
(defn verdict! [label pass detail]
  (swap! checks conj pass)
  (println (if pass "PASS" "FAIL") label (if pass (str "=> " (pr-str detail)) detail)))

;; --- client side -------------------------------------------------------
(ref-eval eve-http-programs/setup)
(cohost-eval eve-http-programs/setup)

(defn client-check [label src]
  (let [with-base (fn [base] (str "(let [base \"" base "\"] " src ")"))
        r (ref-eval (with-base jetty-base))
        c (cohost-eval (with-base jetty-base))
        ce (cohost-eval (with-base eve-base))]
    (verdict! (str "client: " label) (and (= :ok (first r)) (= r c ce))
              (if (= :ok (first r) (first c) (first ce)) (second r)
                  (str " REF " (pr-str r) "\n      COHOST " (pr-str c) "\n      COHOST x Eve server " (pr-str ce))))
    (when (and (= :ok (first r)) (not= r c ce))
      (println "      REF" (pr-str r) "\n      COHOST" (pr-str c) "\n      COHOST x Eve server" (pr-str ce)))))

(doseq [[label src] eve-http-programs/client] (client-check label src))

;; --- server side -------------------------------------------------------
(def jdk-client (java.net.http.HttpClient/newHttpClient))
(defn ask [base [method path body headers]]
  (let [b (reduce (fn [b [k v]] (.header b k v))
                  (java.net.http.HttpRequest/newBuilder (java.net.URI/create (str base path)))
                  headers)
        b (.method b method (if body (java.net.http.HttpRequest$BodyPublishers/ofString body)
                                (java.net.http.HttpRequest$BodyPublishers/noBody)))
        r (.send jdk-client (.build b) (java.net.http.HttpResponse$BodyHandlers/ofString))
        h (.headers r)]
    [(.statusCode r) (.orElse (.firstValue h "content-type") nil) (.orElse (.firstValue h "x-method") nil) (.body r)]))

(doseq [[label req] eve-http-programs/server]
  (let [j (ask jetty-base req)
        e (ask eve-base req)]
    (verdict! (str "server: " label) (= j e)
              (if (= j e) j (str "\n      JETTY " (pr-str j) "\n      EVE   " (pr-str e))))))

(.stop ^org.eclipse.jetty.server.Server jetty)
((:stop eve-srv))
(let [cs @checks]
  (println (str "unit 9 gate (JVM seat): " (count (filter true? cs)) "/" (count cs) " PASS")))
(shutdown-agents)
(System/exit (if (every? true? @checks) 0 1))
