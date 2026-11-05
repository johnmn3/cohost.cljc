(ns cohost.http-eve
  "JVM-seat hosting for cohost.http (MEMORY-PLAN.md, unit 9): Eve's own
   HTTP layer installed as the layer's leaves.

   This namespace is JVM-only (.clj) and is loaded only where Eve's http
   adapters are on the classpath (eve/adapters/http/java/src and
   eve/adapters/http-server/jdk/src); nothing in cohost's shim or boot
   requires it, so the jank and CLJS builds never see it -- the same shape
   as cohost.memory-eve. install! puts eve.http/request and
   eve.http-server/serve! into the installed host adapter as its
   :http-request / :http-serve leaves; from then on the shim's java.net
   entries answer from cohost.http over Eve, not from the JDK's classes.

   clojure.java.io is the host's own on this seat (cohost loads it from the
   JVM, not through its loader), so its IOFactory protocol is extended here
   to the layer's URL / URI / HttpURLConnection values: slurp, io/reader,
   io/input-stream and line-seq over a URL the program built reach Eve's
   leaf. Any other map keeps the host's answer (the IllegalArgumentException
   io.clj throws for a value it cannot open)."
  (:require [cohost.adapter :as adapter]
            [cohost.http :as http]
            [clojure.java.io :as io]
            [eve.http :as eve-http]
            [eve.http-server :as eve-http-server]))

(defrecord Locator [scheme user-info host port path query fragment raw]
  ;; a URL / URI is its text to str, as java.net's are
  Object
  (toString [_] (str raw)))

(defn install!
  "Install Eve's http leaves over the current host adapter."
  []
  (adapter/install! (assoc (adapter/current)
                           :http-request eve-http/request
                           :http-serve eve-http-server/serve!
                           :http-value map->Locator))
  (http/available?))

(defn- layer-input-stream [x]
  (case (http/kind-of x)
    :cohost.http/url (http/open-stream x)
    :cohost.http/uri (http/open-stream (http/uri->url x))
    :cohost.http/connection (http/conn-input-stream x)
    nil))

(defn- cannot [x what]
  (throw (IllegalArgumentException. (str "Cannot open <" (pr-str x) "> as " what "."))))

(def ^:private io-factory
  {:make-reader (fn [x opts]
                  (io/make-reader (or (layer-input-stream x) (cannot x "an InputStream")) opts))
   :make-writer (fn [x opts]
                  (if (http/is? :cohost.http/connection x)
                    (io/make-writer (http/conn-output-stream x) opts)
                    (cannot x "an OutputStream")))
   :make-input-stream (fn [x _opts] (or (layer-input-stream x) (cannot x "an InputStream")))
   :make-output-stream (fn [x _opts]
                         (if (http/is? :cohost.http/connection x)
                           (http/conn-output-stream x)
                           (cannot x "an OutputStream")))})

;; a URL / URI (the Locator record) and an HttpURLConnection (a tagged map);
;; a map that is no layer value meets cannot, the message io.clj's Object
;; impl gives it
(extend Locator io/IOFactory io-factory)
(extend clojure.lang.APersistentMap io/IOFactory io-factory)
