;; Eve-in-cohost unit 9 gate, the seats beside the JVM's (eve_http_check.clj),
;; each against the JVM:
;;
;;   clojure -Sdeps '{:paths ["src" "dev"] :deps {ring/ring-jetty-adapter {:mvn/version "1.12.2"}}}' \
;;     -M dev/eve_http_seats.clj <eve-checkout> [node|jank]
;;
;; seat node (the default): the ClojureScript seat (Node). Builds
;;   dev/eve_http_node.cljs (cohost on self-hosted ClojureScript, Eve's Node
;;   http / http-server adapters as cohost.http's leaves).
;; seat jank: cohost on jank (dev/eve_http_jank.jank), Eve's jank http /
;;   http-server adapters as cohost.http's leaves (src/cohost/http_eve.jank).
;;   <eve-checkout> is an Eve tree holding adapters/http/jank and
;;   adapters/http-server/jank. Its build is cohost's AOT objects
;;   (dev/perf/jank_aot_ensure.clj, through bb: recompiled when stale) and
;;   Eve's :jank roots (`bb classpath jank` in the checkout); jank runs as
;;   bb.edn's jank tasks run it: JANK_BIN (default jank), COHOST_JANK_O
;;   (default -O3), dev/perf/jank_gc_env.clj's collector environment,
;;   LC_ALL=en_US.UTF-8.
;; Then, on either seat:
;; client side -- every program of eve-http-programs not JVM-only, evaluated
;;   unchanged: REF on plain JVM Clojure against Jetty; the seat against
;;   the same Jetty; the seat against the same handler served by Eve's
;;   server on the seat. All three answers equal.
;; server side -- the Ring handler source served by Jetty from plain JVM
;;   Clojure and by Eve's http-server from the seat's booted namespace
;;   (cohost.ring), asked the same requests by the JDK's HttpClient.
;; A jank process that ends before its work is done is reported in one line,
;; never as a pass: when it ended loading a namespace (dev/eve_http_jank.jank
;; prints LOADING / LOADED around each), the line names that namespace and
;; the exit code -- BLOCKED when it is Eve's (an eve.* namespace: Eve's http
;; layer does not load on this seat), FAIL otherwise. The checks are then not
;; run, the summary counts them as not run, and the gate exits 2.
(ns eve-http-seats
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [ring.adapter.jetty]
            [eve-http-programs :as programs]))

(def eve (or (first *command-line-args*) "../eve"))
(def seat-name (or (second *command-line-args*) "node"))

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
    [(.statusCode r) (eve-http-programs/media-type (.orElse (.firstValue h "content-type") nil)) (.orElse (.firstValue h "x-method") nil) (.body r)]))

;; --- the cljs seat (Node) ------------------------------------------------

(defn- node-build! []
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

;; --- the jank seat -------------------------------------------------------

;; defines jank-gc-env: the collector environment bb.edn's jank tasks give jank
(load-file "dev/perf/jank_gc_env.clj")

(def ^:private jank-bin (let [b (System/getenv "JANK_BIN")] (if (str/blank? b) "jank" b)))
(defn- jank-o []
  (let [o (System/getenv "COHOST_JANK_O")]
    (cond (nil? o) ["-O3"] (= "none" o) [] :else [o])))
(defn- jank-env [] (merge {"LC_ALL" "en_US.UTF-8"} (jank-gc-env)))
(def ^:private jank-path (atom nil))
;; minutes a jank process may take to finish (client) or to listen (serve)
(def ^:private jank-minutes 20)

(defn- jank-build! []
  (let [eve-dir (.getCanonicalPath (io/file eve))
        env (merge (into {} (System/getenv)) {"LC_ALL" "en_US.UTF-8"})
        _ (doseq [d ["adapters/http/jank/src" "adapters/http-server/jank/src"]]
            (when-not (.isDirectory (io/file eve-dir d))
              (throw (ex-info (str eve-dir " has no " d ": not an Eve tree with the jank http adapters") {}))))
        _ (println "jank seat: cohost's AOT objects (dev/perf/jank_aot_ensure.clj; a stale cache recompiles)")
        aot (sh/sh "bb" "-e" "(load-file \"dev/perf/jank_aot_ensure.clj\")" :env env)
        _ (when-not (zero? (:exit aot))
            (println (:out aot) (:err aot))
            (throw (ex-info "cohost's jank AOT build failed" {})))
        aot-dir (some (fn [l] (when (str/starts-with? l "AOT-DIR ") (str/trim (subs l 8))))
                      (reverse (str/split-lines (:out aot))))
        roots (sh/sh "bb" "classpath" "jank" :dir eve-dir :env env)
        roots-path (last (remove str/blank? (str/split-lines (:out roots))))]
    (when-not aot-dir
      (println (:out aot) (:err aot))
      (throw (ex-info "dev/perf/jank_aot_ensure.clj printed no AOT-DIR" {})))
    (when-not (and (zero? (:exit roots)) roots-path)
      (println (:out roots) (:err roots))
      (throw (ex-info (str "`bb classpath jank` failed in " eve-dir) {})))
    (println "jank seat: AOT-DIR" aot-dir)
    (reset! jank-path (str/join ":" [aot-dir "src" "dev" (str/trim roots-path)
                                     (str eve-dir "/adapters/http/jank/src")
                                     (str eve-dir "/adapters/http-server/jank/src")]))))

(defn- jank-process [mode & args]
  (let [pb (doto (ProcessBuilder. ^java.util.List
                                  (vec (concat [jank-bin] (jank-o)
                                               ["--module-path" @jank-path "run-main" "eve-http-jank" "--" mode]
                                               args)))
             (.redirectErrorStream true))]
    (.putAll (.environment pb) (jank-env))
    (.start pb)))

(defn- ended-loading
  "the namespace a jank process was loading when it ended: its last LOADING
   line without the LOADED line after it, or nil"
  [out]
  (let [lines (str/split-lines (or out ""))
        loaded (set (keep #(when (str/starts-with? % "LOADED ") (subs % 7)) lines))]
    (last (remove loaded (keep #(when (str/starts-with? % "LOADING ") (subs % 8)) lines)))))

(defn- exit-text [exit]
  (if (and (integer? exit) (> exit 128))
    (str exit " (" (get {4 "SIGILL" 6 "SIGABRT" 7 "SIGBUS" 9 "SIGKILL" 11 "SIGSEGV" 15 "SIGTERM"} (- exit 128)
                         (str "signal " (- exit 128))) ")")
    (str exit)))

(defn- jank-ended!
  "a jank process ended (or timed out) before its work was done"
  [mode exit out]
  (throw (ex-info (str "jank " mode " process ended")
                  {:jank-ended {:mode mode :exit exit :out out :ns (ended-loading out)}})))

(defn- jank-client [base]
  (let [out (str (java.io.File/createTempFile "eve-http-jank" ".edn"))
        ^Process p (jank-process "client" base out)
        text (future (slurp (.getInputStream p)))
        done (.waitFor p jank-minutes java.util.concurrent.TimeUnit/MINUTES)]
    (when-not done (.destroyForcibly p) (.waitFor p))
    (let [exit (if done (.exitValue p) :timeout)]
      (when-not (and (= 0 exit) (str/includes? @text "client programs run: "))
        (jank-ended! "client" exit @text))
      (into {} (edn/read-string (slurp out))))))

(defn- jank-serve! [port]
  (let [^Process p (jank-process "serve" (str port))
        rdr (io/reader (.getInputStream p))
        seen (StringBuffer.)
        deadline (+ (System/currentTimeMillis) (* jank-minutes 60 1000))]
    (loop []
      (let [line (deref (future (.readLine ^java.io.BufferedReader rdr))
                        (max 1 (- deadline (System/currentTimeMillis))) ::timeout)]
        (cond (= ::timeout line) (do (.destroyForcibly p) (.waitFor p)
                                     (jank-ended! "serve" :timeout (str seen)))
              (nil? line) (do (.waitFor p) (jank-ended! "serve" (.exitValue p) (str seen)))
              (.startsWith ^String line "LISTENING")
              ;; the rest of its output is read as it comes, so the pipe never fills
              (do (future (loop [] (when-let [l (.readLine ^java.io.BufferedReader rdr)]
                                     (.append seen l) (.append seen "\n") (recur))))
                  p)
              :else (do (.append seen line) (.append seen "\n") (recur)))))))

(defn- jank-stop! [^Process p]
  ;; its standard input ends: the server stops and the process exits
  (.close (.getOutputStream p))
  (when-not (.waitFor p 30 java.util.concurrent.TimeUnit/SECONDS)
    (.destroyForcibly p)))

;; --- the gate ------------------------------------------------------------

(def seats
  {"node" {:title "cljs seat, Node" :build! node-build! :client node-client :serve! node-serve!
           :stop! (fn [^Process p] (.destroy p))
           :client-label "CLJS" :eve-client-label "CLJS x Eve node server" :server-label "EVE (node)"}
   "jank" {:title "jank seat" :build! jank-build! :client jank-client :serve! jank-serve! :stop! jank-stop!
           :client-label "JANK" :eve-client-label "JANK x Eve jank server" :server-label "EVE (jank)"}})

(def seat (or (get seats seat-name)
              (do (println (str "unknown seat " (pr-str seat-name) "; the seats: " (str/join ", " (keys seats))))
                  (System/exit 64))))

(def run-checks
  "the checks of a seat run: the client programs not JVM-only, the server requests"
  (+ (count (remove (fn [[label _]] (contains? programs/jvm-only label)) programs/client))
     (count programs/server)))

(defn- summary [total extra]
  (println (str "unit 9 gate (" (:title seat) "): " (count (filter true? @checks)) "/" total " PASS" extra
                "; JVM-only client programs not run on this seat: " (count programs/jvm-only))))

(defn- ended-report!
  "the one line for a seat process that ended before its work was done, the
   tail of its output, and the summary with the checks not run"
  [{:keys [mode exit out ns]}]
  (let [eve-ns? (and ns (str/starts-with? (str ns) "eve."))
        what (if ns (str "loading " ns) (str "the " mode " run"))]
    (println (cond eve-ns?
                   (str "BLOCKED unit 9 gate (" (:title seat) "): Eve's http layer does not load on this seat -- the "
                        mode " process ended loading " ns ", exit " (exit-text exit))
                   ns
                   (str "FAIL unit 9 gate (" (:title seat) "): the " mode " process ended loading " ns
                        ", exit " (exit-text exit))
                   :else
                   (str "FAIL unit 9 gate (" (:title seat) "): the " mode " process ended before its work was done, exit "
                        (exit-text exit))))
    (println (str "  last lines of the " mode " process:"))
    (doseq [l (take-last 15 (str/split-lines (or out "")))] (println (str "    " l)))
    (summary run-checks (str ", " (- run-checks (count @checks)) " not run (" (if eve-ns? "BLOCKED" "FAIL") ": " what
                  ", exit " (exit-text exit) ")"))))

((:build! seat))
(def jetty (ring.adapter.jetty/run-jetty (second (ref-eval programs/handler-src)) {:port 0 :join? false :host "127.0.0.1"}))
(def jetty-base (str "http://127.0.0.1:" (.getLocalPort ^org.eclipse.jetty.server.ServerConnector (first (.getConnectors ^org.eclipse.jetty.server.Server jetty)))))

(def ended
  "nil, or what a seat process that ended before its work was done left"
  (try
    (let [seat-port (free-port)
          seat-proc ((:serve! seat) seat-port)
          seat-base (str "http://127.0.0.1:" seat-port)]
      (try
        ;; client side
        (let [x-jetty ((:client seat) jetty-base)
              x-eve ((:client seat) seat-base)]
          (doseq [[label src] programs/client
                  :when (not (contains? programs/jvm-only label))]
            (let [r (ref-eval (str "(let [base \"" jetty-base "\"] " src ")"))
                  c (get x-jetty label)
                  ce (get x-eve label)]
              (verdict! (str "client: " label) (and (= :ok (first r)) (= r c ce))
                        (if (and (= :ok (first r)) (= r c ce)) (second r)
                            (str "\n      REF " (pr-str r) "\n      " (:client-label seat) " " (pr-str c)
                                 "\n      " (:eve-client-label seat) " " (pr-str ce)))))))
        ;; server side
        (doseq [[label req] programs/server]
          (let [j (ask jetty-base req)
                e (ask seat-base req)]
            (verdict! (str "server: " label) (= j e)
                      (if (= j e) j (str "\n      JETTY " (pr-str j) "\n      " (:server-label seat) " " (pr-str e))))))
        nil
        (finally ((:stop! seat) seat-proc))))
    (catch clojure.lang.ExceptionInfo e
      (or (:jank-ended (ex-data e)) (throw e)))))

(.stop ^org.eclipse.jetty.server.Server jetty)
(if ended
  (ended-report! ended)
  (summary (count @checks) ""))
(shutdown-agents)
(System/exit (cond ended 2 (every? true? @checks) 0 :else 1))
