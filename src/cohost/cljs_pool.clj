(ns cohost.cljs-pool
  "The cljs sister for cohost.thread's runtime executors: a pool's
   realm is one booted ClojureScript realm (cohost.boot-cljs), hosted
   per seat --

     :graal  a GraalJS context in this process (the JVM seat)
     :node   the host's own JS engine: a node child process running
             cohost/node_realm.js, a vm context driven over stdio

   The shim (cohost.js-shim) and the boot are identical on both seats:
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
            [cohost.boot-cljs :as bc]
            [cohost.thread :as thread])
  (:import [java.io DataInputStream File OutputStream]
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
    (catch Throwable _ nil)))

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
   boot-cljs realm: see eval-in). node-bin is the node executable."
  [node-bin]
  (let [script-url (io/resource "cohost/node_realm.js")]
    (when-not script-url
      (throw (ex-info "cohost/node_realm.js missing from the classpath" {})))
    (let [tmp (File/createTempFile "cohost-node-realm" ".js")
          errlog (File/createTempFile "cohost-node-realm" ".err")]
      (io/copy (io/input-stream script-url) tmp)
      (let [pb (ProcessBuilder. [node-bin (.getAbsolutePath tmp)])]
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
   \"node\", from PATH)."
  []
  {:boot! (fn [opts]
            (let [realm (make-node-realm (get opts :node-bin "node"))]
              ;; the preamble and Closure's base, as the in-process
              ;; realm gets from make-realm; then the shared boot
              (bc/eval-in realm bc/realm-preamble)
              (doseq [r ["base.js" "deps.js"]]
                (if-let [text (bc/goog-text r)]
                  (bc/eval-in realm text)
                  (throw (ex-info (str "closure library resource missing: goog/" r)
                                  {:resource r}))))
              (bc/boot! realm opts)
              {:realm realm :lock (Object.)}))
   :eval-form (fn [state ns-sym form]
                (locking (:lock state)
                  (form->data (:realm state) ns-sym form)))
   :close! (fn [state]
             (try (.close (:out (:realm state))) (catch Throwable _ nil))
             (try (.destroy ^Process (:proc (:realm state)))
                  (catch Throwable _ nil)))})

;; ---------------------------------------------------------------------------

(defn executor
  "A runtime executor of `size` workers over a cljs realm:
   (executor 1 {:seat :graal}) -- a GraalJS realm on this seat;
   (executor 1 {:seat :node}) -- the host's own JS engine in a node
   process. Boot options pass through to the sister (boot-cljs's
   :wrap-macros; the node seat's :node-bin). Submit with
   cohost.thread/submit-call and submit-form; read results with
   cohost.thread/deref-future. Size 1 is the honest configuration:
   a realm serves serially whatever the pool's size."
  [size & {:keys [seat] :as opts}]
  (thread/runtime-executor
   (case (or seat :graal)
     :graal (graal-sister)
     :node (node-sister))
   size opts))
