(ns cohost.clj-champaca
  "In-process :clj leg for a jolt host. Champaca runs inside this jolt
   process; it is not a second process.

   The handle lives in `state`. Scan routes a :clj arm to a marker form,
   and boot calls `eval-arm!` instead of the booted core.

   What champaca's API does not do, so this does not pretend it does
   (b12n-oss/champaca src/champaca/api.clj):

   - init! boots once. A later call's :classpath is ignored
     (api.clj:86-87, \"Only the first call boots, so a later call's
     options are ignored.\"). The classpath is the bundle's app jars
     plus the project roots known at the first :clj arm.
   - There is no eval that returns a jolt value. invoke-static
     (api.clj:170-173) returns boxed numbers, booleans and chars as
     jolt values; anything else stays a Java object. ->clj
     (api.clj:200-226) copies arrays and java.util collections only.
     A Clojure value from Compiler/load is not spliced back into a
     jolt expression. eval-arm! returns nil. The arm's own printing
     goes to this process's stdout.

   A sibling host (basilisp or jank) that records jolt's champaca as
   its :clj leg does not call this. Starting that body in another
   process and shipping forms across is not built."
  (:require [cohost.scan :as scan]))

(def state
  "The champaca body handle: :cold, :booted, or :missing with :error."
  (atom {:status :cold :cfg nil :error nil}))

(defn- classpath [cfg]
  (vec (concat (or (:champaca-classpath cfg) [])
               (or (:roots cfg) []))))

(defn- save-cache!
  "champaca.api/save-cache! (api.clj:130). The first call arms generation
   saves (saves-generation!, api.clj:137) so later JDK methods compile
   straight to object code (cachedir.clj compile-to-cache?, line 281).
   save-generation! writes nothing when the recording is empty
   (libcache.clj:423), so a later call that finds no new code does no IO.
   Two processes saving one cache dir are not serialized: extra-<millis>.so
   and state.img are rename-over writes (libcache.clj:425-437), and the
   gil (api.clj:28) is per process. This does not add a lock."
  []
  ((requiring-resolve 'champaca.api/save-cache!)))

(defn- ensure!
  []
  (let [{:keys [status cfg]} @state]
    (when (= :missing status)
      (throw (ex-info (:error @state) {:cohost/clj-body :champaca})))
    (when-not (= :booted status)
      (if-not (try (require 'champaca.api) true
                   (catch Throwable e
                     (swap! state assoc :status :missing
                            :error (str "cohost: this jolt process's :clj leg needs champaca.api, and it did not load ("
                                        (.getMessage e) "). "
                                        "champaca.api/init! is src/champaca/api.clj:79; "
                                        "a classpath passed after the first init! is ignored (api.clj:86)."))
                     false))
        (throw (ex-info (:error @state) {:cohost/clj-body :champaca}))
        (let [init! (requiring-resolve 'champaca.api/init!)
              call (requiring-resolve 'champaca.api/invoke-static)
              cp (classpath cfg)]
          (init! {:classpath cp})
          ;; Arm saving before RT.init. init! does not (api.clj:79); without
          ;; saves-generation!, clojure.core is compiled as text and the next
          ;; process pays it again. The call after RT.init writes that
          ;; generation (extra-*.so and the library images).
          (save-cache!)
          ;; Compiler.load is not a legal first touch. Compiler.<clinit>
          ;; starts RT.<clinit>, and RT.baseLoader NPEs while Compiler/LOADER
          ;; is still null (assigned later in Compiler.<clinit>). clojure.main
          ;; calls RT.init first. This is champaca's invoke-static, so the RT
          ;; is champaca's, not jolt's. RT/init is idempotent.
          (call "clojure.lang.RT" "init")
          (save-cache!)
          (swap! state assoc :status :booted :error nil))))))

(defn eval-arm!
  "Load one :clj arm's source inside champaca. Returns nil. See the ns
   docstring for the value that does not cross back to jolt."
  [src]
  (ensure!)
  (let [ctor (requiring-resolve 'champaca.api/construct)
        call (requiring-resolve 'champaca.api/invoke-static)
        reader (ctor "java.io.StringReader" src)
        pushback (ctor "clojure.lang.LineNumberingPushbackReader" reader)]
    ;; Compiler/load(Reader) reads every form. There is no champaca.api
    ;; eval; this is invoke-static (api.clj:170). The result is discarded.
    (call "clojure.lang.Compiler" "load" pushback)
    ;; An arm that compiled nothing leaves the recording empty, and
    ;; save-cache! then returns without writing (libcache.clj:423).
    (save-cache!)
    nil))

(defn- arm-form [src]
  (pr-str (list 'cohost.clj-champaca/eval-arm! src)))

(defn install!
  "Point the scanner at this body and remember the launcher's config."
  [cfg]
  (swap! state assoc :cfg cfg :status :cold :error nil)
  (reset! scan/clj-arm-eval eval-arm!)
  (reset! scan/clj-arm-route arm-form)
  nil)
