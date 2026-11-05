(ns cohost.hosts.cljs-realm
  "Unit 8: the Node entry points' half of the shared-heap seat
   (cohost.hosts.cljs-eve): the core realm's install before its boot,
   and a worker realm's whole life -- join the heap, boot the image as
   the core booted it, serve tasks."
  (:require [cohost.boot :as boot]
            [cohost.hosts.cljs :as cljs-host]
            [cohost.hosts.cljs-eve :as cljs-eve]))

(defn enabled?
  "The seat's Eve hosting is on unless COHOST_CLJS_EVE=0."
  []
  (not= "0" (.. js/process -env -COHOST_CLJS_EVE)))

(defn core-boot-opts
  "In the core realm: install Eve hosting (when enabled) and answer the
   boot opts that put the program's atoms in it. boot-data is what a
   worker realm boots from: {:root stock-root :roots [source-root ...]}."
  [boot-data]
  (if (enabled?)
    (do (cljs-eve/install-core! {:entry 'cohost.hosts.cljs-realm/realm-main :boot boot-data})
        {:eve-atoms true})
    {}))

(defn- known-ns? [ns-sym]
  (contains? (:cljs.analyzer/namespaces @cljs-host/st) ns-sym))

(defn- ensure-ns!
  "A task's namespace in this realm: as booted; else required from the
   source roots; else a bare namespace seeing core."
  [ns-sym]
  (when-not (known-ns? ns-sym)
    (try (boot/eval-in 'user (list 'require (list 'quote ns-sym)))
         (catch :default _ nil))
    (when-not (known-ns? ns-sym)
      (boot/make-user-ns! ns-sym))))

(defn realm-main
  "A worker realm's entry (eve.thread-host runs it with the boot map)."
  [b]
  (try
    (let [{:keys [root roots]} (cljs-eve/install-realm! b)]
      (boot/boot! {:root root :eve-atoms true})
      (doseq [r roots] (boot/add-source-root! r))
      (boot/make-user-ns!)
      (boot/make-user-ns! 'user)
      (cljs-eve/serve! {:eval-in boot/eval-in :ensure-ns! ensure-ns!}))
    (catch :default e
      ;; a realm's end is never silent: the stack to stderr, then the
      ;; exit notice (eve.thread-host) carries the message to the core
      ;; (the worker's stderr may not drain before the core exits: the
      ;; stack rides the exit notice's message)
      (throw (js/Error. (str "realm " (:self b) " died: " (.-stack e)))))))
