(ns cohost.cljs-pool
  "The cljs sister for cohost.thread's runtime executors: a pool's
   realm is one booted ClojureScript realm (cohost.boot-cljs), hosted
   per seat --

     :graal   a GraalJS context in this process (the JVM seat)
     :node    the host's own JS engine: a node child process running
              cohost/node_realm.js, a vm context driven over stdio
     :browser the host's own JS engine in a browser page: starting
              the seat starts a web server (the bootstream, the app
              assets, the interop channel) and a browser pointed at
              it; the page runs cohost/browser_realm.js and speaks
              the node seat's protocol over HTTP

   The shim (cohost.js-shim) and the boot are identical on all seats:
   the shim is written against realm globals, not an engine, and the
   analyzer/compiler run on the JVM either way -- only the realm's
   eval transport differs (cohost.boot-cljs/eval-in).

   Work crosses as forms + data, per the layer's realm contract: the
   item's form is analyzed in its namespace against the realm's own
   compiler env and evaluated in the realm; the result crosses back
   as data -- the realm pr-str's it, the driver reads the EDN. A
   result that is not EDN data (a function, a JS object) does not
   cross: its printed form comes back as a string, and that is a
   contract breach by the submitter, exactly as a non-crossing value
   is for the SCI sister. Arguments cross as literals in the form
   (submit-form quotes them), so they must be data too.

   A JS realm is single-threaded: eval-form serializes on the realm's
   lock, so a pool of any size serves its realm serially -- the :solo
   shape with a realm behind it. Size 1 is the honest configuration.

   The host adapter must be installed before an executor is built
   (require cohost.hosts.auto, as everywhere in cohost)."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [cohost.boot-cljs :as bc]
            [cohost.thread :as thread])
  (:import [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]
           [java.io ByteArrayOutputStream DataInputStream File InputStream OutputStream]
           [java.net InetSocketAddress URLDecoder]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [java.util Base64]
           [java.util.concurrent Executors LinkedBlockingQueue ThreadFactory TimeUnit]
           [org.graalvm.polyglot Context]))

;; ---------------------------------------------------------------------------
;; data crossing

(defn- form->data
  "Evaluate form in realm's namespace ns-sym and answer its value as
   JVM data: the realm evaluates (cljs.core/pr-str form) and the
   driver reads the resulting EDN. A value whose printed form is not
   readable EDN crosses as the raw string instead of throwing."
  [realm ns-sym form]
  (let [raw (bc/eval-form realm ns-sym (list 'cljs.core/pr-str form))
        text (if (instance? org.graalvm.polyglot.Value raw)
               (when raw (.asString ^org.graalvm.polyglot.Value raw))
               raw)]
    (if (string? text)
      (try (edn/read-string {:default (fn [_tag v] v)} text)
           (catch Throwable _ text))
      text)))

;; ---------------------------------------------------------------------------
;; the GraalJS seat (in-process)

(defn graal-sister
  "The GraalJS sister impl map for cohost.thread/runtime-executor.
   Boot options are cohost.boot-cljs/boot!'s (notably :wrap-macros)."
  []
  {:boot! (fn [opts]
            (let [ctx (bc/make-realm)]
              (bc/boot! ctx opts)
              {:realm ctx :lock (Object.)}))
   :eval-form (fn [state ns-sym form]
                (locking (:lock state)
                  (form->data (:realm state) ns-sym form)))
   :close! (fn [state] (.close ^Context (:realm state)))})

;; ---------------------------------------------------------------------------
;; the node seat (the host's own JS engine, in a child process)
;;
;; The realm is a node process running cohost/node_realm.js: a vm
;; context driven over stdio with length-prefixed JSON frames. The
;; driver side below is the whole transport: evals are answered by the
;; reader thread; a goog load mid-eval arrives on the same stream as a
;; file request and is served synchronously from the classpath, the
;; same resolution the in-process realm uses (boot-cljs/goog-text).

(defn- write-frame!
  [^OutputStream out lock ^String payload]
  (let [bytes (.getBytes payload "UTF-8")
        header (.getBytes (str (alength bytes) "\n") "US-ASCII")]
    (locking lock
      (.write out header)
      (.write out bytes)
      (.flush out))))

(defn- read-frame!
  "One frame from the realm process, or nil at EOF."
  [^DataInputStream din]
  (let [hdr (StringBuilder.)]
    (loop []
      (let [b (.read din)]
        (cond
          (== b -1) nil
          (== b (int \newline))
          (let [n (Long/parseLong (str hdr))
                buf (byte-array n)]
            (.readFully din buf)
            (String. buf "UTF-8"))
          :else (do (.append hdr (char b)) (recur)))))))

(defn- node-reader-loop
  "The realm process's stdout: file requests are served in place;
   anything else is the pending eval's answer."
  [state]
  (try
    (loop []
      (when-let [payload (read-frame! (:din state))]
        (let [msg (json/read-str payload :key-fn keyword)]
          (if (= "file" (:op msg))
            (write-frame! (:out state) (:write-lock state)
                          (json/write-str {"op" "file"
                                           "text" (bc/goog-text (:path msg))}))
            (when-let [p @(:pending state)]
              (deliver p msg))))
        (recur)))
    (catch Throwable _ nil)
    (finally
      ;; the stream ended -- the process died or was closed: a pending
      ;; eval must not wait out its timeout for an answer that is
      ;; never coming
      (when-let [p @(:pending state)]
        (deliver p {:ok false
                    :error "cohost: the node realm process closed"})))))

(defn- node-eval!
  [state ^String js]
  (let [p (promise)]
    (reset! (:pending state) p)
    (write-frame! (:out state) (:write-lock state)
                  (json/write-str {"eval" js}))
    (let [resp (deref p 180000 ::timeout)]
      (reset! (:pending state) nil)
      (cond
        (= ::timeout resp)
        (throw (ex-info (str "node realm eval timed out; realm stderr: "
                             (:errlog state))
                        {:js js}))
        (:ok resp) (:value resp)
        :else (throw (ex-info (str "node realm eval failed: " (:error resp))
                              {:js js :error (:error resp)}))))))

(defn- make-node-realm
  "Spawn the node realm process and answer its transport map (a
   boot-cljs realm: see eval-in). node-bin is the node executable.
   require-root, when given, is the realm's node interop anchor: the
   realm script receives it as its argument (its require is created
   over it) and the process starts with it as working directory, as
   a node process of that directory would."
  [node-bin require-root]
  (let [script-url (io/resource "cohost/node_realm.js")]
    (when-not script-url
      (throw (ex-info "cohost/node_realm.js missing from the classpath" {})))
    (let [tmp (File/createTempFile "cohost-node-realm" ".js")
          errlog (File/createTempFile "cohost-node-realm" ".err")]
      (io/copy (io/input-stream script-url) tmp)
      (let [pb (ProcessBuilder. (if require-root
                                  [node-bin (.getAbsolutePath tmp) (str require-root)]
                                  [node-bin (.getAbsolutePath tmp)]))]
        (when require-root
          (.directory pb (File. (str require-root))))
        (.redirectError pb errlog)
        (let [proc (.start pb)
              state {:proc proc
                     :out (.getOutputStream proc)
                     :din (DataInputStream. (.getInputStream proc))
                     :pending (atom nil)
                     :write-lock (Object.)
                     :errlog (.getAbsolutePath errlog)}]
          (doto (Thread. ^Runnable (fn [] (node-reader-loop state))
                         "cohost-node-realm-reader")
            (.setDaemon true)
            (.start))
          (assoc state :eval! (fn [js] (node-eval! state js))))))))

(defn node-sister
  "The node sister impl map for cohost.thread/runtime-executor.
   Boot options: cohost.boot-cljs/boot!'s, plus :node-bin (default
   \"node\", from PATH) and :require-root -- a directory the realm's
   node interop anchors at: the realm gets a require created over
   that directory (its node_modules resolve as a node process
   started there would resolve them), process and Buffer, and the
   realm process's working directory is that directory. Without
   :require-root the realm stays bare (no require, no process)."
  []
  {:boot! (fn [opts]
            (let [realm (make-node-realm (get opts :node-bin "node")
                                         (:require-root opts))]
              ;; the preamble and Closure's base, as the in-process
              ;; realm gets from make-realm; then the shared boot
              (bc/eval-in realm bc/realm-preamble)
              (doseq [r ["base.js" "deps.js"]]
                (if-let [text (bc/goog-text r)]
                  (bc/eval-in realm text)
                  (throw (ex-info (str "closure library resource missing: goog/" r)
                                  {:resource r}))))
              ;; Eve's type registrations are this realm's own boot,
              ;; not the app's: their register! calls run at load in
              ;; the type-defining namespaces, which do not require
              ;; the registrars, so both load before any type in the
              ;; app's graph registers -- an app names only its own
              ;; namespaces in :load-ns.
              (bc/boot! realm (update opts :load-ns
                                      (fn [nss] (into '[eve.type-readers eve.flat.sister]
                                                      (remove #{'eve.type-readers 'eve.flat.sister} nss)))))
              {:realm realm :lock (Object.)}))
   :eval-form (fn [state ns-sym form]
                (locking (:lock state)
                  (form->data (:realm state) ns-sym form)))
   :close! (fn [state]
             (try (.close (:out (:realm state))) (catch Throwable _ nil))
             (try (.destroy ^Process (:proc (:realm state)))
                  (catch Throwable _ nil)))})

;; ---------------------------------------------------------------------------
;; the browser seat (the host's own JS engine, in a browser page)
;;
;; Starting the seat starts two things: a web server and the browser
;; pointed at it. The server serves the initial bootstream HTML (with
;; the realm host script injected), the app's static assets, and the
;; interop channel the page speaks -- the node seat's protocol, over
;; HTTP: the page long-polls /__cohost/poll for evals, posts results
;; to /__cohost/result, and the goog loader's file service is the
;; node seat's, through synchronous requests to /__cohost/file. The
;; page itself is cohost/browser_realm.js; the driver's boot sequence
;; (preamble, goog base.js + deps.js, boot!) is the node sister's
;; verbatim.
;;
;; The server is the JDK's HttpServer: this sister is driver-seat
;; code (as the node sister's ProcessBuilder and stdio are), and the
;; JDK server needs no dependency a cohost driver may not have.
;; cohost.ring + the :http-serve leaf are the other direction: they
;; serve programs running INSIDE a seat. This server is the seat's
;; own machinery, one layer down -- and the browser thread IS
;; basically a server at that point, so it is exposed as one:
;; start-server! runs the web half alone, and :handler lets an
;; application put its own endpoints under the same header pipeline
;; (isolation, CORS, cache policy).
;;
;; Chrome discovery is the capstone's (cohost-media boot's
;; find-tools): EVE_CHROME, else the fixed install paths, a
;; Playwright cache under the user's home, then PATH; the launch
;; flags are the capstone's headless ones, a fresh profile per start
;; (deleted on close). The bootstream the server serves is the one
;; the caller names; the realm script's injection into it is the
;; server's business, not the file's -- the file on disk is untouched.

(def default-server-config
  "The browser seat's web server with no configuration: an ephemeral
   port on 127.0.0.1, cross-origin isolation headers on (the page's
   SharedArrayBuffer dies without them -- the demo's first wall),
   no CORS grants (same-origin only), no caching. :server options in
   the pool config merge over this map; a :cors map merges over
   {:methods [\"GET\" \"POST\" \"OPTIONS\"] :headers nil :credentials
   false :max-age 600} -- :headers nil means a preflight is answered
   with the headers it asked for."
  {:port 0
   :host "127.0.0.1"
   :isolation true
   :cors nil
   :headers {}
   :cache-control "no-cache"})

(def ^:private isolation-headers
  {"Cross-Origin-Opener-Policy" "same-origin"
   "Cross-Origin-Embedder-Policy" "require-corp"})

(defn- merge-server-config [user]
  (let [base (merge default-server-config (dissoc user :cors))
        cors (when (some? (:cors user))
               (merge {:methods ["GET" "POST" "OPTIONS"]
                       :headers nil
                       :credentials false
                       :max-age 600}
                      (:cors user)))]
    (assoc base :cors cors)))

(defn- cors-allowed-origin
  "The Access-Control-Allow-Origin value for origin under cors, or
   nil when the origin is not granted. :origins may be a list of
   exact origins or :any."
  [cors origin]
  (when (and cors origin)
    (let [origins (:origins cors)]
      (when (or (= :any origins) (contains? (set origins) origin))
        origin))))

(defn- cors-headers
  "CORS response headers for a request: origin the Origin header (or
   nil), preflight? whether this is an OPTIONS preflight, req-headers
   the Access-Control-Request-Headers value (preflight only). An
   ungranted origin answers no CORS headers at all, which is how the
   browser denies the request."
  [cors origin preflight? req-headers]
  (if-let [acao (cors-allowed-origin cors origin)]
    (cond-> {"Access-Control-Allow-Origin" acao
             "Vary" "Origin"}
      (:credentials cors) (assoc "Access-Control-Allow-Credentials" "true")
      preflight? (assoc "Access-Control-Allow-Methods" (str/join ", " (:methods cors))
                        "Access-Control-Max-Age" (str (:max-age cors)))
      (and preflight? (seq (or (:headers cors) (when req-headers [req-headers]))))
      (assoc "Access-Control-Allow-Headers"
             (str/join ", " (or (:headers cors) [req-headers]))))
    {}))

(def ^:private default-bootstream-page
  ;; The built-in page: no bootstream named, the server still serves
  ;; a page the realm host runs in (the script tag is injected by
  ;; the server, as for any bootstream).
  "<!DOCTYPE html>\n<html><head><meta charset=\"utf-8\"><title>cohost browser realm</title></head><body></body></html>\n")

(defn- inject-realm-script
  "The served bootstream carries the realm host script as its first
   head script (see browser_realm.js); the file on disk is untouched."
  [^String html]
  (let [tag "<script src=\"/__cohost/realm.js\"></script>"
        lower (str/lower-case html)
        i (or (str/index-of lower "<head") (str/index-of lower "<html"))]
    (if i
      (let [j (str/index-of html ">" i)]
        (if j
          (str (subs html 0 (inc j)) tag (subs html (inc j)))
          (str tag html)))
      (str tag html))))

(defn- bootstream-source
  "Resolve the :bootstream option to {:html string :root File-or-nil
   :name string-or-nil}. A path to an .html file (its directory
   becomes the static root), a path to a directory (its index.html;
   the directory is the root), {:resource \"path\"} for a classpath
   resource (no static root), or nil for the built-in page."
  [bootstream]
  (cond
    (nil? bootstream)
    {:html default-bootstream-page :root nil :name nil}

    (map? bootstream)
    (let [text (some-> (io/resource (:resource bootstream)) slurp)]
      (if text
        {:html text :root nil :name nil}
        (throw (ex-info (str "bootstream resource not found: " (:resource bootstream))
                        {:bootstream bootstream}))))

    (string? bootstream)
    (let [f (io/file bootstream)]
      (cond (.isDirectory f)
            (let [index (io/file f "index.html")]
              (if (.isFile index)
                {:html (slurp index) :root f :name "index.html"}
                (throw (ex-info (str "bootstream directory has no index.html: " bootstream)
                                {:bootstream bootstream}))))
            (.isFile f)
            {:html (slurp f) :root (.getParentFile f) :name (.getName f)}
            :else
            (throw (ex-info (str "bootstream not found: " bootstream)
                            {:bootstream bootstream}))))

    :else
    (throw (ex-info (str "bootstream must be a path, {:resource ...}, or nil, was: " (pr-str bootstream))
                    {:bootstream bootstream}))))

(def ^:private mime-types
  {"html" "text/html; charset=utf-8"
   "htm" "text/html; charset=utf-8"
   "js" "application/javascript"
   "mjs" "application/javascript"
   "css" "text/css"
   "json" "application/json"
   "map" "application/json"
   "png" "image/png"
   "jpg" "image/jpeg"
   "jpeg" "image/jpeg"
   "gif" "image/gif"
   "svg" "image/svg+xml"
   "ico" "image/x-icon"
   "wasm" "application/wasm"
   "txt" "text/plain; charset=utf-8"
   "woff" "font/woff"
   "woff2" "font/woff2"})

(defn- mime-for [path]
  (get mime-types (some-> (re-find #"\.([^./]+)$" path) second str/lower-case)
       "application/octet-stream"))

;; ---------------------------------------------------------------------------
;; the web server's plumbing (JDK HttpServer)

(defn- read-body-bytes [^InputStream in]
  (when in
    (let [out (ByteArrayOutputStream.)]
      (io/copy in out)
      (.toByteArray out))))

(defn- query-params [^String q]
  (if (str/blank? q)
    {}
    (into {}
          (for [pair (str/split q #"&")
                :let [[k v] (str/split pair #"=" 2)]]
            [(URLDecoder/decode k "UTF-8")
             (URLDecoder/decode (or v "") "UTF-8")]))))

(defn- respond-bytes!
  [^HttpExchange ex status headers ^bytes bytes]
  (let [resp-headers (.getResponseHeaders ex)]
    (doseq [[k v] headers] (.set resp-headers k v))
    (if bytes
      (do (.sendResponseHeaders ex status (alength bytes))
          (with-open [os (.getResponseBody ex)] (.write os bytes)))
      (.sendResponseHeaders ex status -1))))

(defn- respond-text!
  [ex status headers ^String body]
  (respond-bytes! ex status headers
                  (when body (.getBytes body "UTF-8"))))

(defn- exchange-request
  "The request as a map: {:method :uri :query :headers :body :body-b64},
   header names lower-cased. :body is the request body as a UTF-8 string;
   :body-b64 is the same bytes base64-encoded (eve.http-server's
   convention), for handlers whose traffic is binary."
  [^HttpExchange ex]
  (let [uri (.getRequestURI ex)
        bs (read-body-bytes (.getRequestBody ex))]
    {:method (keyword (str/lower-case (.getRequestMethod ex)))
     :uri (.getPath uri)
     :query (query-params (.getRawQuery uri))
     :headers (into {} (for [[k vs] (.getRequestHeaders ex)]
                         [(str/lower-case k) (first vs)]))
     :body (when bs (String. bs "UTF-8"))
     :body-b64 (when bs (.encodeToString (Base64/getEncoder) bs))}))

(defn- app-headers
  "The server-config header pipeline every app response passes
   through: isolation (unless off), CORS for the request's origin,
   the config's extra headers, its cache policy. (A preflight's
   Access-Control-Request-Headers is what its Allow-Headers is
   computed from when :cors :headers is unset.)"
  [cfg req]
  (merge (when (:isolation cfg) isolation-headers)
         (cors-headers (:cors cfg) (get (:headers req) "origin")
                       (= :options (:method req))
                       (get (:headers req) "access-control-request-headers"))
         (:headers cfg)
         {"Cache-Control" (:cache-control cfg)}))

(defn- preflight?
  "An OPTIONS with an Origin and an Access-Control-Request-Method:
   a CORS preflight, answered by the pipeline alone."
  [req]
  (and (= :options (:method req))
       (some? (get (:headers req) "origin"))
       (some? (get (:headers req) "access-control-request-method"))))

(defn- static-file
  "The file under root for the request's URI path, or nil. The
   canonical path is confined under the root: a .. cannot climb out."
  [^File root ^String uri-path]
  (when root
    (let [f (io/file root (subs (URLDecoder/decode uri-path "UTF-8") 1))
          canon-root (.getCanonicalPath root)
          canon (.getCanonicalPath f)]
      (when (and (.startsWith canon canon-root) (.isFile f)) f))))

(defn- interop-handler
  "The /__cohost endpoints: the realm host script, the goog file
   service, the eval poll and result post, and a ping. channel is the
   realm's {:queue :pending :page-ready}; with no browser behind it
   the poll idles and the file service still serves."
  [channel]
  (reify HttpHandler
    (handle [_ ex]
      (try
        (let [req (exchange-request ex)
              uri (:uri req)]
          (cond
            (and (= "/__cohost/realm.js" uri) (= :get (:method req)))
            (if-let [text (some-> (io/resource "cohost/browser_realm.js") slurp)]
              (respond-text! ex 200 {"Content-Type" "application/javascript"
                                     "Cache-Control" "no-store"} text)
              (respond-text! ex 500 {"Content-Type" "text/plain"} "cohost/browser_realm.js missing"))

            (and (= "/__cohost/file" uri) (= :get (:method req)))
            (if-let [text (bc/goog-text (get (:query req) "path" ""))]
              (respond-text! ex 200 {"Content-Type" "text/plain; charset=utf-8"
                                     "Cache-Control" "no-store"} text)
              (respond-text! ex 404 {"Content-Type" "text/plain"} "not found"))

            (and (= "/__cohost/poll" uri) (= :get (:method req)))
            (do (when-let [ready (:page-ready channel)]
                  (when-not (realized? ready) (deliver ready true)))
                (if-let [item (.poll ^LinkedBlockingQueue (:queue channel) 30 TimeUnit/SECONDS)]
                  (respond-text! ex 200 {"Content-Type" "application/json"
                                         "Cache-Control" "no-store"}
                                 (json/write-str {"id" (:id item) "js" (:js item)}))
                  (respond-text! ex 204 {"Cache-Control" "no-store"} nil)))

            (and (= "/__cohost/result" uri) (= :post (:method req)))
            (let [msg (json/read-str (or (:body req) "{}") :key-fn keyword)]
              (when-let [p (get @(:pending channel) (:id msg))]
                (deliver p msg))
              (respond-text! ex 204 {"Cache-Control" "no-store"} nil))

            (and (= "/__cohost/ping" uri) (= :get (:method req)))
            (respond-text! ex 200 {"Content-Type" "text/plain"} "ok")

            :else
            (respond-text! ex 404 {"Content-Type" "text/plain"} "not found")))
        (catch Throwable t
          (try (respond-text! ex 500 {"Content-Type" "text/plain"}
                              (str "cohost: " (ex-message t)))
               (catch Throwable _ nil)))
        (finally
          (try (.close ^HttpExchange ex) (catch Throwable _ nil)))))))

(defn- app-handler
  "The app endpoints, under cfg's header pipeline: the bootstream
   (at / and at its own name, with the realm script injected),
   static files under the bootstream's root, :handler's own
   endpoints for anything else (a handler answers {:status :headers
   :body}, or {:body-as :b64 :body <base64>} for binary traffic —
   eve.http-server's convention, mirrored; its headers win over the
   pipeline's where they name the same key)."
  [{:keys [cfg html root boot-name handler]}]
  (reify HttpHandler
    (handle [_ ex]
      (try
        (let [req (exchange-request ex)
              uri (:uri req)
              headers (app-headers cfg req)]
          (cond
            (preflight? req)
            (respond-text! ex 204 headers nil)

            (or (= "/" uri) (and boot-name (= (str "/" boot-name) uri)))
            (respond-text! ex 200 (assoc headers "Content-Type" "text/html; charset=utf-8")
                           (inject-realm-script html))

            :else
            (if-let [f (static-file root uri)]
              (respond-bytes! ex 200 (assoc headers "Content-Type" (mime-for (.getName f)))
                              (Files/readAllBytes (.toPath f)))
              (let [resp (when handler (handler req))]
                (if resp
                  (if (= :b64 (:body-as resp))
                    ;; a handler answers binary traffic as base64 text
                    ;; (eve.http-server's convention: :body-as :b64)
                    (respond-bytes! ex (or (:status resp) 200)
                                    (merge headers (:headers resp))
                                    (when (:body resp)
                                      (.decode (Base64/getDecoder) ^String (:body resp))))
                    (respond-text! ex (or (:status resp) 200)
                                   (merge headers (:headers resp))
                                   (:body resp)))
                  (respond-text! ex 404 headers "not found"))))))
        (catch Throwable t
          (try (respond-text! ex 500 {"Content-Type" "text/plain"}
                              (str "cohost: " (ex-message t)))
               (catch Throwable _ nil)))
        (finally
          (try (.close ^HttpExchange ex) (catch Throwable _ nil)))))))

(defn- daemon-thread-factory
  [name-prefix]
  (let [n (atom 0)]
    (reify ThreadFactory
      (newThread [_ r]
        (doto (Thread. ^Runnable r (str name-prefix "-" (swap! n inc)))
          (.setDaemon true))))))

(defn start-server!
  "Start the browser seat's web server on its own: the bootstream +
   static root + interop endpoints of `config` (:bootstream and
   :server keys, the pool config's shape), with `channel` the realm
   channel ({:queue :pending :page-ready}) or nil for a server with
   no browser behind it. Answers {:port :url :stop!} -- :port the
   port actually bound (the default config's is ephemeral, 0).
   `config` may also carry :handler, a fn of the request map
   answering {:status :headers :body} for paths that are neither
   interop nor a static hit (nil means not mine: the server
   answers 404). Public because the browser thread IS basically a
   server: an application may run the web half alone, and the seat's
   proofs measure CORS against a second origin served by this same
   machinery."
  ([config] (start-server! config nil))
  ([config channel]
   (let [cfg (merge-server-config (:server config))
         src (bootstream-source (:bootstream config))
         channel (or channel {:queue (LinkedBlockingQueue.)
                              :pending (atom {})
                              :page-ready (promise)})
         addr (InetSocketAddress. ^String (:host cfg) (int (:port cfg)))
         server (HttpServer/create addr 0)
         exec (Executors/newCachedThreadPool (daemon-thread-factory "cohost-browser-server"))]
     (.setExecutor server exec)
     (.createContext server "/__cohost" (interop-handler channel))
     (.createContext server "/" (app-handler {:cfg cfg
                                              :html (:html src)
                                              :root (:root src)
                                              :boot-name (:name src)
                                              :handler (:handler config)}))
     (.start server)
     {:server server
      :port (.getPort (.getAddress server))
      :url (str "http://" (:host cfg) ":" (.getPort (.getAddress server)))
      :stop! (fn []
               (try (.stop ^HttpServer server 0) (catch Throwable _ nil))
               (try (.shutdownNow exec) (catch Throwable _ nil)))})))

;; ---------------------------------------------------------------------------
;; the browser itself

(defn- which
  "The first executable named n on PATH, or nil."
  [n]
  (some (fn [dir] (let [f (io/file dir n)]
                    (when (.canExecute f) (.getPath f))))
        (str/split (or (System/getenv "PATH") "") #":")))

(defn- find-chrome
  "The demo's discovery (capstone boot's find-tools): EVE_CHROME,
   else the first Chromium or Chrome found -- the fixed install
   paths (Playwright's tree, macOS's apps), a Playwright cache under
   the user's home, then PATH."
  []
  (or (System/getenv "EVE_CHROME")
      (some (fn [c] (let [f (io/file c)] (when (.canExecute f) c)))
            ["/opt/pw-browsers/chromium"
             "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
             "/Applications/Chromium.app/Contents/MacOS/Chromium"])
      (let [cache (io/file (System/getProperty "user.home") ".cache/ms-playwright")]
        (when (.isDirectory cache)
          (some (fn [^File d]
                  (let [c (io/file d "chrome-linux64/chrome")]
                    (when (.canExecute c) (.getPath c))))
                (sort-by #(.getName ^File %)
                         (filter #(.isDirectory ^File %) (.listFiles cache))))))
      (some which ["chromium" "chromium-browser" "google-chrome" "google-chrome-stable"])
      "chromium"))

(defn- log-tail
  "The last 2 KB of the browser's stderr log, or \"\"."
  [^File errlog]
  (try
    (let [text (slurp errlog)
          n (count text)]
      (subs text (max 0 (- n 2048))))
    (catch Throwable _ "")))

(defn- launch-browser!
  "Start the browser at url: headless unless :headless is false, a
   fresh profile per start (the capstone's launch flags verbatim).
   Answers {:proc :profile :errlog}."
  [chrome-cfg url]
  (let [bin (or (:bin chrome-cfg) (find-chrome))
        profile (Files/createTempDirectory "cohost-browser-chrome-"
                                           (into-array FileAttribute []))
        errlog (File/createTempFile "cohost-browser-chrome" ".log")
        args (vec (concat [bin]
                          (when (get chrome-cfg :headless true)
                            ["--headless=new" "--disable-gpu"])
                          ["--no-sandbox" "--disable-dev-shm-usage"
                           "--no-proxy-server" "--no-first-run"
                           "--no-default-browser-check"
                           (str "--user-data-dir=" (.toString profile))]
                          (:args chrome-cfg)
                          [url]))
        pb (ProcessBuilder. ^java.util.List args)]
    (.redirectErrorStream pb true)
    (.redirectOutput pb errlog)
    {:proc (.start pb) :profile profile :errlog errlog :bin bin}))

(defn- wait-for-page!
  "The page's first poll, or a throw: the browser dying first is a
   startup failure, not a timeout -- its stderr tail is the reason."
  [channel ^Process proc ^File errlog timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (let [v (deref (:page-ready channel) 250 ::waiting)]
        (cond (true? v) true
              (not (.isAlive proc))
              (throw (ex-info "browser exited before its page connected"
                              {:browser-stderr (log-tail errlog)}))
              (> (System/currentTimeMillis) deadline)
              (throw (ex-info "browser page did not connect in time" {}))
              :else (recur))))))

(defn- browser-eval!
  "One eval through the page's poll channel: enqueue {id js} and wait
   for the result post, the node sister's timeout shape."
  [state ^String js]
  (let [channel (:channel state)
        id (swap! (:counter state) inc)
        p (promise)]
    (swap! (:pending channel) assoc id p)
    (.put ^LinkedBlockingQueue (:queue channel) {:id id :js js})
    (let [resp (deref p 180000 ::timeout)]
      (swap! (:pending channel) dissoc id)
      (cond (= ::timeout resp)
            (throw (ex-info (str "browser realm eval timed out; browser stderr: "
                                 (log-tail (:errlog state)))
                            {:js js}))
            (:ok resp) (:value resp)
            :else (throw (ex-info (str "browser realm eval failed: " (:error resp))
                                  {:js js :error (:error resp)}))))))

(defn- delete-tree!
  "Delete a directory tree, best effort."
  [^java.nio.file.Path p]
  (try
    (when (Files/exists p (into-array java.nio.file.LinkOption []))
      (doseq [f (reverse (iterator-seq (.iterator (Files/walk p (into-array java.nio.file.FileVisitOption [])))))]
        (try (Files/delete ^java.nio.file.Path f) (catch Throwable _ nil))))
    (catch Throwable _ nil)))

(defn- close-browser-state!
  "Stop the browser thread's machinery: fail pending evals, stop the
   server, kill the browser, delete its profile."
  [{:keys [channel server browser]}]
  (doseq [[_ p] @(:pending channel)]
    (deliver p {:ok false :error "browser realm closed"}))
  (when server
    (try ((:stop! server)) (catch Throwable _ nil)))
  (when browser
    (let [^Process proc (:proc browser)]
      (try (.destroy proc)
           (when-not (.waitFor proc 5 TimeUnit/SECONDS)
             (.destroyForcibly proc))
           (catch Throwable _ nil)))
    (delete-tree! (:profile browser))))

(defn browser-sister
  "The browser sister impl map for cohost.thread/runtime-executor.
   Boot options are cohost.boot-cljs/boot!'s, plus:
     :bootstream -- where the server finds the initial page: an .html
       file (its directory is served as the static root), a
       directory (its index.html), {:resource \"path\"}, or absent
       for the built-in page.
     :server -- the web server's config, over default-server-config.
     :chrome -- {:bin path :headless bool (default true) :args [..]}."
  []
  {:boot! (fn [opts]
            (let [channel {:queue (LinkedBlockingQueue.)
                           :pending (atom {})
                           :page-ready (promise)}
                  server (start-server! opts channel)
                  browser (try (launch-browser! (:chrome opts) (str (:url server) "/"))
                               (catch Throwable t
                                 ((:stop! server))
                                 (throw t)))]
              (try
                (wait-for-page! channel (:proc browser) (:errlog browser) 120000)
                (let [state {:channel channel :server server :browser browser
                             :errlog (:errlog browser) :counter (atom 0)
                             :lock (Object.)}
                      realm {:eval! (fn [js] (browser-eval! state js))}]
                  (bc/eval-in realm bc/realm-preamble)
                  (doseq [r ["base.js" "deps.js"]]
                    (if-let [text (bc/goog-text r)]
                      (bc/eval-in realm text)
                      (throw (ex-info (str "closure library resource missing: goog/" r)
                                      {:resource r}))))
                  (bc/boot! realm opts)
                  (assoc state :realm realm))
                (catch Throwable t
                  (close-browser-state! {:channel channel :server server :browser browser})
                  (throw t)))))
   :eval-form (fn [state ns-sym form]
                (locking (:lock state)
                  (form->data (:realm state) ns-sym form)))
   :close! (fn [state] (close-browser-state! state))})

;; ---------------------------------------------------------------------------

(defn executor
  "A runtime executor of `size` workers over a cljs realm:
   (executor 1 {:seat :graal}) -- a GraalJS realm on this seat;
   (executor 1 {:seat :node}) -- the host's own JS engine in a node
   process; (executor 1 {:seat :browser :bootstream \"index.html\"})
   -- the host's own JS engine in a browser page, the server and the
   browser started as the thread. Boot options pass through to the
   sister (boot-cljs's :wrap-macros and :load-ns; the node seat's
   :node-bin and :require-root; the browser seat's :bootstream
   :server :chrome). Submit with
   cohost.thread/submit-call and submit-form; read results with
   cohost.thread/deref-future. Size 1 is the honest configuration:
   a realm serves serially whatever the pool's size."
  [size & {:keys [seat] :as opts}]
  (thread/runtime-executor
   (case (or seat :graal)
     :graal (graal-sister)
     :node (node-sister)
     :browser (browser-sister))
   size opts))
