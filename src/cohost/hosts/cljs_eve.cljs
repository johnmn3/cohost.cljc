(ns cohost.hosts.cljs-eve
  "Eve-in-cohost, unit 8 (doc/eve-cohost/MEMORY-PLAN.md): the shared-heap
   illusion on the ClojureScript seat, on Node.

   A JavaScript isolate has one heap; Node's workers are isolates. What
   they can share is SharedArrayBuffers -- and Eve's atoms live in
   SharedArrayBuffers: the module slabs every Eve value is allocated in,
   and each in-process domain's root (the CAS words, the registry) and
   reader map. So the program's heap here is Eve's, by construction: the
   seat's atoms (cohost.atom, the shim's Atom) are Eve atoms in domains
   every realm of the process maps, and a value one realm publishes into
   an atom is read by any other realm straight out of the shared slabs.

   The pieces, all over Eve's own facilities:

   - realms: the core realm (the program's main thread, stood up first,
     controlling the rest -- the cljs-thread / eve.thread mesh pattern)
     and worker realms started through Eve's Node thread adapter
     (eve.thread-host/start-threads!: fat-kernel workers, a port mesh, a
     SharedArrayBuffer doorbell). A worker realm runs the same compiled
     image and boots cohost the way the core did.
   - the heap: Eve in-process domains (eve.atom/atom-domain). A worker
     joins the process slabs from the SharedArrayBuffers it is handed
     (eve.process-slab/init-worker-slabs!, Eve's worker path) and each
     domain from its root and reader-map buffers. A realm that mints a
     domain announces its buffers on a BroadcastChannel; the core also
     hands its directory to each worker at start.
   - mailboxes: two Eve atoms in the core's mail domain -- the inbox (a
     vector of tasks every worker claims from by CAS) and the outbox
     ({task-id outcome}). Tasks and outcomes cross THROUGH the atoms; the
     mesh only rings doorbells ({:cohost/task}, {:cohost/done id}).
   - crossing: a task is data (eve.thread's rule -- a plain closure is
     refused): the form, the namespace it was written in, the values of
     the locals it names, the binding frame. A value crosses as itself
     when Eve can hold it; a layer atom crosses as its Eve handle (domain
     key + slot) and the receiving realm wraps the same Eve atom; a fn
     that is a var's root crosses as the var's name. Anything else (a
     closure, a host object, an atom whose root sits in one realm's host
     seat) does not cross: the future runs as a closure in the core
     realm, as it did before this unit, and the count says so.

   Named distances: a worker realm boots the image itself (its boot wall
   is paid once, at the first crossing future); namespaces the core loads
   after a worker booted are required by the worker from the same source
   roots on first use (a namespace that cannot load there is made a bare
   namespace seeing core); watches, validators and meta are each realm's
   own wrapper state (unit 7's process-local rule, at realm scale); a
   binding frame entry whose value cannot cross is not conveyed (counted);
   blocks over 1 KB allocated by a worker go through Eve's class-6
   overflow, which init-worker-slabs! registers but does not wire for
   allocation (Eve-side gap, named in the unit report). The :disk and
   :net kinds are not hosted on this seat (Eve's Node disk domains need
   its N-API addon; unit 7 hosts them on the JVM and jank seats): they
   fail loudly, never fall back to :mem."
  (:require ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            ["node:worker_threads" :as wt]
            [clojure.string :as string]
            [cohost.adapter :as adapter]
            [cohost.atom :as atom-layer]
            [cohost.hosts.cljs :as cljs-host]
            [cohost.thread :as thread]
            [eve.atom :as ea]
            [eve.deftype-proto.alloc :as alloc]
            [eve.deftype-proto.data :as eve-data]
            [eve.deftype-proto.wasm :as wasm]
            [eve.process-slab :as ps]
            [eve.region :as region]
            [eve.thread-host :as th]))

;; ---------------------------------------------------------------------------
;; realm state (the leaf's own bookkeeping: plain host atoms, as the
;; cooperative leaf's are -- none of it is program state)

(defonce ^:private R
  (atom {:realm 0          ;; this realm's address on the mesh (0: the core)
         :core? true
         :on? false        ;; Eve hosting installed in this realm
         :entry nil        ;; the worker entry (a qualified symbol)
         :boot nil         ;; what a worker needs to boot as the core did
         :realms 1         ;; worker realms the core starts at the first crossing
         :workers []
         :pending {}       ;; task id -> completion fn (core)
         :task-seq 0
         :mail nil         ;; {:key k :inbox h :outbox h}
         :hosting {:current nil :count 0 :counter 0 :dseq 0}
         :stats {:crossed 0 :closure 0 :completed 0 :ran-here 0 :frame-dropped 0}}))

(defn- stat! [k] (swap! R update-in [:stats k] (fnil inc 0)) nil)

(defn stats
  "This realm's crossing counts (probes, the gate's evidence)."
  []
  (assoc (:stats @R) :realm (:realm @R) :workers (count (:workers @R))))

(defn- trace? [] (some? (.. js/process -env -COHOST_REALM_TRACE)))

(defn- trace [& xs]
  (when (trace?)
    ;; synchronous: a worker's process.stderr drains through the core's
    ;; event loop, which an exiting core never runs again
    (fs/writeSync 2 (str "cohost-realm[" (:realm @R) "]: " (string/join " " xs) "\n"))))

(defn available?
  "Eve hosting is in force in this realm."
  []
  (:on? @R))

;; ---------------------------------------------------------------------------
;; the domain directory

(defonce ^:private dirs (atom {}))        ;; key -> {:sabs js-obj :domain d}
(defonce ^:private ds-keys (js/Map.))     ;; domain-state -> key
(defonce ^:private chan* (atom nil))

(defn- chan ^js []
  (or @chan*
      (let [c (wt/BroadcastChannel. "cohost.eve.domains")]
        (.unref c)
        (reset! chan* c)
        c)))

(defn- sabs-of [k ^js d]
  (let [ds (.-domain-state d)]
    #js {:key k :droot (.-sab ^js (:root-r ds)) :rmap (.-sab ^js (:rmap-r ds))}))

(defn- note-sabs! [^js m]
  (let [k (.-key m)]
    (when-not (contains? @dirs k)
      (swap! dirs assoc k {:sabs m}))))

(defn- drain-chan! []
  (loop []
    (when-let [r (wt/receiveMessageOnPort (chan))]
      (note-sabs! (.-message r))
      (recur))))

(defn- register-domain! [k d]
  (.set ds-keys (.-domain-state d) k)
  (swap! dirs assoc k {:sabs (sabs-of k d) :domain d})
  d)

(defn- fresh-domain
  "A fresh in-process Eve domain over the process slabs. The core realm
   uses eve.atom/atom-domain. A worker realm cannot: atom-domain's
   init-domain-views! asks each slab instance for its wasm memory, and a
   worker's instances, registered from the handed-over buffers by
   init-worker-slabs!, have none (Eve-side gap, named) -- their views
   exist already, so the worker opens the domain with Eve's own opener
   step (open-heap-domain!) alone."
  []
  (if (:core? @R)
    (ea/atom-domain {})
    (ea/MmapAtomDomain. (#'ea/open-heap-domain! {}) (atom {}))))

(defn- mint-domain!
  "A fresh Eve in-process domain, announced to every realm."
  []
  (let [n (get-in (swap! R update-in [:hosting :dseq] inc) [:hosting :dseq])
        k (str "r" (:realm @R) "-d" n)
        d (fresh-domain)]
    (register-domain! k d)
    (.postMessage (chan) (:sabs (get @dirs k)))
    d))

(defn- join-domain
  "This realm's handle on another realm's domain, from its buffers: the
   domain state Eve's own opener builds (root region, reader map, the
   process slabs' slab I/O, a participant slot of its own). Eve has no
   public worker-side opener for an in-process domain (named gap); the
   two calls below are Eve's own constructor steps."
  [^js m]
  (let [root-r (region/js-sab-region (.-droot m))
        rmap-r (region/js-sab-region (.-rmap m))
        sio (alloc/heap-domain-sio! {})
        slot (#'ea/mmap-claim-slot! root-r)
        ds (#'ea/make-domain-state root-r rmap-r sio nil slot nil)]
    (ea/MmapAtomDomain. ds (atom {}))))

(defn- domain-of
  "The domain keyed k in this realm, joining it on first use."
  [k]
  (let [deadline (+ (js/Date.now) 30000)]
    (loop [asked? false]
      (let [e (get @dirs k)]
        (cond
          (:domain e) (:domain e)
          (:sabs e) (let [d (join-domain (:sabs e))]
                      (.set ds-keys (.-domain-state d) k)
                      (swap! dirs assoc-in [k :domain] d)
                      d)
          :else
          (do (drain-chan!)
              (if (contains? @dirs k)
                (recur asked?)
                (do (when (> (js/Date.now) deadline)
                      (throw (ex-info (str "cohost: Eve domain " k " was never announced to realm " (:realm @R))
                                      {:cohost.realm/error :unknown-domain :key k})))
                    ;; announced before this realm's channel opened: ask the core
                    (when (and (not asked?) (not (:core? @R)))
                      (th/send! 0 {:cohost/need-domain k}))
                    (js/Atomics.wait (js/Int32Array. (js/SharedArrayBuffer. 4)) 0 0 2)
                    (recur true)))))))))

;; ---------------------------------------------------------------------------
;; the atom factory (cohost.atom's seat hosting): program atoms in this
;; realm's domains, sharded at Eve's slot limit as the JVM seat shards

(def ^:private slots-per-domain 255)

(defn- make-eve-atom [slab-init]
  (let [h (:hosting @R)]
    (when (or (nil? (:current h)) (>= (:count h) slots-per-domain))
      (let [d (mint-domain!)]
        (swap! R update :hosting assoc :current d :count 0))))
  (let [s (swap! R (fn [r] (-> r
                               (update-in [:hosting :count] inc)
                               (update-in [:hosting :counter] inc))))]
    (ea/lookup-or-create-mmap-atom! (get-in s [:hosting :current])
                                    (str "cohost.atom/r" (:realm s) "-a" (get-in s [:hosting :counter]))
                                    slab-init)))

(defonce ^:private registry-handle (atom nil))

(defn- registry-eve-atom []
  (or @registry-handle
      (let [d (fresh-domain)]
        (reset! registry-handle (ea/lookup-or-create-mmap-atom! d "cohost.registry/table" {})))))

(defn- unhosted-kind [what]
  (fn [& _]
    (throw (ex-info (str "cohost.atom: " what " is not hosted on the ClojureScript seat (Eve's Node disk "
                         "domains need its N-API addon; unit 7 hosts the :disk and :net kinds on the JVM "
                         "and jank seats)")
                    {:cohost.atom/error :kind-unhosted :seat :cljs}))))

(defn- make-domain-atom
  "The STM lane's per-ref cell handle (STATUS 10/11): a FRESH Eve
   domain holding exactly one atom -- the ref's own contention
   domain, as cohost.atom-eve's :make-domain-atom mints on the JVM
   seat. Realm-local by design (STATUS 10: refs do not cross realms
   as live objects; each realm runs its own STM), so the domain is
   opened with fresh-domain and not announced to other realms.
   Nothing else ever joins this domain."
  [slab-init]
  (let [d (fresh-domain)
        s (swap! R update-in [:hosting :counter] inc)]
    (ea/lookup-or-create-mmap-atom! d
                                    (str "cohost.tx/ref" (get-in s [:hosting :counter]))
                                    slab-init)))

(defn- install-factory! []
  (atom-layer/install-factory! {:make-atom make-eve-atom
                                :make-domain-atom make-domain-atom
                                :make-tier-atom (unhosted-kind "a tiered atom kind")
                                :frame-atom (unhosted-kind "binding conveyance through a disk atom")
                                :registry-atom registry-eve-atom
                                :eve-value? (fn [v] (satisfies? eve-data/IEveRoot v))}))

;; ---------------------------------------------------------------------------
;; crossing: values as Eve holds them, atoms by handle, var-root fns by name

(defonce ^:private atom-cache (atom {}))   ;; [domain-key slot] -> layer atom

(defn- refuse! [why]
  (throw (ex-info "cohost: value cannot cross a realm" {::uncrossable why})))

(def ^:private seq-limit 100000)

(defn- encode [x]
  (cond
    (or (nil? x) (boolean? x) (number? x) (string? x) (keyword? x) (symbol? x)
        (uuid? x) (inst? x))
    x

    (atom-layer/atom-value? x)
    (if-let [h (atom-layer/crossing-handle x)]
      (if-let [k (.get ds-keys (.-domain-state h))]
        (let [slot (.-atom-slot-idx h)]
          (swap! atom-cache (fn [m] (if (contains? m [k slot]) m (assoc m [k slot] x))))
          {::atom [k slot]})
        (refuse! :atom-outside-program-domains))
      (refuse! :atom-root-in-host-seat))

    (satisfies? eve-data/IEveRoot x) x

    (fn? x) (if-let [q (cljs-host/fn-var-name x)] {::var q} (refuse! :closure))
    (record? x) (refuse! :record)
    (map? x) (into {} (map (fn [[k v]] [(encode k) (encode v)])) x)
    (vector? x) (mapv encode x)
    (set? x) (into #{} (map encode) x)
    (seq? x) (let [v (vec (take (inc seq-limit) x))]
               (when (> (count v) seq-limit) (refuse! :unbounded-seq))
               ;; tagged: a list published into an Eve atom read back nil
               ;; (measured: (future (list 1 2)) -> nil outcome), so a seq
               ;; crosses as a vector and is rebuilt as a list
               {::seq (mapv encode v)})
    :else (refuse! :host-object)))

(defn- atom-for [k slot]
  (or (get @atom-cache [k slot])
      (let [a (atom-layer/atom-over-handle (ea/MmapAtom. (.-domain-state (domain-of k)) slot))]
        (swap! atom-cache assoc [k slot] a)
        a)))

(defn- decode [x]
  (cond
    (map? x) (cond (contains? x ::atom) (let [[k slot] (::atom x)] (atom-for k slot))
                   (contains? x ::seq) (apply list (map decode (::seq x)))
                   (contains? x ::var) (let [q (::var x)]
                                         (or (cljs-host/var-name-root q)
                                             (throw (ex-info (str "cohost: " q " has no root in realm " (:realm @R))
                                                             {:cohost.realm/error :unresolved-var :var q}))))
                   :else (into {} (map (fn [[k v]] [(decode k) (decode v)])) x))
    (vector? x) (mapv decode x)
    (set? x) (into #{} (map decode) x)
    (seq? x) (apply list (map decode x))
    :else x))

(defn- encode-frame
  "{var value} -> {qualified-symbol encoded}; an entry whose value cannot
   cross is not conveyed (counted, traced)."
  [b]
  (reduce (fn [acc [v x]]
            (let [q (cljs-host/var-qsym v)]
              (try (assoc acc q (encode x))
                   (catch :default e
                     (if (::uncrossable (ex-data e))
                       (do (stat! :frame-dropped) (trace "frame entry not conveyed:" q) acc)
                       (throw e))))))
          {} b))

(defn- decode-frame [f]
  (into {} (map (fn [[q x]] [(cljs-host/var-handle q) (decode x)])) f))

(defn- error-outcome [e]
  {:error {:message (or (ex-message e) (str e))
           :class (cond (instance? ExceptionInfo e) "clojure.lang.ExceptionInfo"
                        (instance? js/Error e) (str "js/" (.-name e))
                        :else "unknown")
           :data (try (encode (ex-data e)) (catch :default _ nil))}})

(defn- outcome->layer [o]
  (if (contains? o :ok)
    {:status :done :value (decode (:ok o))}
    (let [{:keys [message class data]} (:error o)]
      {:status :error
       :error (ex-info message (merge {:cohost.realm/class class} (when (map? data) (decode data))))})))

;; ---------------------------------------------------------------------------
;; the mailboxes

(defn- outbox-key
  "The outbox's key for task id: a string. An Eve map's small integer key
   whose value holds a nested collection reads back incremented on this
   seat (measured: {1 {:z [1 2]}} -> {2 {:z [1 2]}}; Eve-side defect,
   named in the unit report), so the transport never keys by integer."
  [id]
  (str "t" id))

(defn- mail []
  (or (:mail @R)
      (let [d (mint-domain!)
            k (.get ds-keys (.-domain-state d))
            m {:key k
               :inbox (ea/lookup-or-create-mmap-atom! d "cohost.realm/inbox" [])
               :outbox (ea/lookup-or-create-mmap-atom! d "cohost.realm/outbox" {})}]
        (swap! R assoc :mail m)
        m)))

(defn- join-mail [k]
  (let [d (domain-of k)
        m {:key k
           :inbox (ea/lookup-or-create-mmap-atom! d "cohost.realm/inbox" [])
           :outbox (ea/lookup-or-create-mmap-atom! d "cohost.realm/outbox" {})}]
    (swap! R assoc :mail m)
    m))

;; ---------------------------------------------------------------------------
;; the core realm: starting workers, submitting, collecting

(defn- kernel-path
  "The compiled image as a worker kernel. eve.thread-host's fat-kernel
   route evaluates the program file (process.argv[1]); a :none build's
   bootstrap finds its output from the working directory, which the CLI
   moves to the project, so the kernel is the same file with that one
   lookup pinned to the build's root."
  []
  (let [script (fs/realpathSync (aget js/process.argv 1))
        src (fs/readFileSync script "utf8")
        root (-> script path/dirname path/dirname path/dirname)
        out (path/join (os/tmpdir) (str "cohost-realm-kernel-" js/process.pid ".js"))]
    (fs/writeFileSync out (string/replace src "path.resolve(\".\")" (js/JSON.stringify root)))
    out))

(defn- env-data []
  #js {:slabs (ps/get-all-slab-sabs)
       :root (ps/get-root-sab)
       :overflow (some-> (wasm/get-slab-instance 6) :u8 .-buffer)
       :dirs (into-array (map :sabs (vals @dirs)))})

(defn- ensure-workers! []
  (when (empty? (:workers @R))
    (let [m (mail)
          t0 (js/Date.now)
          kernel (kernel-path)
          argv1 (aget js/process.argv 1)]
      (wt/setEnvironmentData "cohost.eve" (env-data))
      (aset js/process.argv 1 kernel)
      (let [ids (try (th/start-threads! (:realms @R) (:entry @R)
                                        {:mail (:key m) :boot (:boot @R)} {})
                     (finally (aset js/process.argv 1 argv1)))]
        (swap! R assoc :workers ids)
        (trace "started worker realms" (pr-str ids) "in" (- (js/Date.now) t0) "ms")))))

(defn- submit!
  "The seat's :realm-submit (cohost.thread/future-form-call)."
  [{:keys [ns form args frame complete]}]
  (try
    (let [eargs (encode (vec args))
          eframe (encode-frame frame)
          id (:task-seq (swap! R update :task-seq inc))
          task {:id id :ns ns :form form :args eargs :frame eframe :from (:realm @R)}]
      (ensure-workers!)
      (swap! (:inbox (mail)) conj task)
      (swap! R assoc-in [:pending id] complete)
      (stat! :crossed)
      (trace "task" id "handed over:" (pr-str form))
      (doseq [w (:workers @R)] (th/send! w {:cohost/task id}))
      true)
    (catch :default e
      (stat! :closure)
      (trace "task stays a closure here:" (pr-str (or (::uncrossable (ex-data e)) (ex-message e))))
      false)))

(defn- fail-pending! [why]
  (let [p (:pending @R)]
    (swap! R assoc :pending {})
    (doseq [[_ complete] p]
      (complete {:status :error :error (ex-info (str "cohost: worker realm ended: " why)
                                                {:cohost.realm/error :realm-ended})}))))

(defn- handle! [m]
  (cond
    (and (map? m) (contains? m :cohost/done))
    (let [id (:cohost/done m)
          ob (:outbox (mail))
          o (get @ob (outbox-key id))
          complete (get-in @R [:pending id])]
      (swap! ob dissoc (outbox-key id))
      (swap! R update :pending dissoc id)
      (stat! :completed)
      (trace "task" id "completed in realm" (:realm m) (when (trace?) (pr-str o)))
      (when complete (complete (outcome->layer o))))

    (and (map? m) (contains? m :cohost/need-domain))
    (when-let [e (get @dirs (:cohost/need-domain m))]
      (.postMessage (chan) (:sabs e)))

    (and (map? m) (contains? m :eve.thread/exit))
    (do (swap! R update :workers (fn [ws] (vec (remove #{(:eve.thread/exit m)} ws))))
        (trace "worker realm" (:eve.thread/exit m) "ended:" (pr-str (:reason m)))
        (when (empty? (:workers @R))
          (fail-pending! (pr-str (:reason m)))))

    :else nil))

(defn- poll!
  "The realm hook (cohost.hosts.cljs/set-realm-poll!): drain the mesh;
   ms > 0 may first block up to ms on this realm's doorbell."
  [ms]
  (when (seq (:workers @R))
    (let [m (th/receive! ms)]
      (when-not (= m :eve.thread/timeout)
        (handle! m)
        (loop []
          (let [m (th/receive! 0)]
            (when-not (= m :eve.thread/timeout)
              (handle! m)
              (recur))))))))

(defn install-core!
  "In the core realm, before boot: the process slabs, the atom factory,
   the realm hook and the seat's :realm-submit. opts: :entry -- the
   worker entry (a qualified symbol naming a fn of the boot map); :boot
   -- data a worker boots from; COHOST_CLJS_REALMS sets the worker count
   (default 1). Returns true."
  [{:keys [entry boot]}]
  (ps/init!)
  (chan)
  (install-factory!)
  (swap! R assoc :on? true :core? true :realm 0 :entry entry :boot boot
         :realms (let [n (js/parseInt (or (.. js/process -env -COHOST_CLJS_REALMS) "1") 10)]
                   (if (pos? n) n 1)))
  (cljs-host/set-realm-poll! poll!)
  (adapter/install! (assoc (adapter/current) :realm-submit submit!))
  true)

(defn shutdown!
  "Ask the worker realms to end (their loops honour :eve.thread/stop)."
  []
  (when (seq (:workers @R))
    (th/stop-threads! (:workers @R))
    (swap! R assoc :workers [])))

;; ---------------------------------------------------------------------------
;; a worker realm

(defn run-thread!
  "eve.thread-host/run-thread!: in a worker realm this adapter started,
   run its entry (the realm's whole life) and answer true; else false.
   The program's main calls it first."
  []
  (th/run-thread!))

(defn install-realm!
  "In a worker realm, before its boot: join the process slabs and every
   domain the core announced, the mailboxes, the atom factory. Returns
   the boot data."
  [b]
  (let [^js e (wt/getEnvironmentData "cohost.eve")]
    (ps/init-worker-slabs! (.-slabs e) (.-root e) (.-overflow e))
    (chan)
    (doseq [m (.-dirs e)] (note-sabs! m))
    (swap! R assoc :on? true :core? false :realm (:self b))
    (join-mail (:mail b))
    (install-factory!)
    (:boot b)))

(defn- claim! [inbox]
  (let [got (volatile! nil)]
    (swap! inbox (fn [q]
                   (if (seq q)
                     (do (vreset! got (first q)) (vec (rest q)))
                     (do (vreset! got nil) q))))
    @got))

(defn- run-task [{:keys [ns form args frame]} {:keys [eval-in ensure-ns!]}]
  (ensure-ns! ns)
  (let [f (eval-in ns form)
        argv (decode args)
        b (decode-frame frame)]
    ((thread/conveyed b (fn [] (apply f argv))))))

(defn- run-and-publish! [t opts]
  (let [{:keys [inbox outbox]} (:mail @R)
        id (:id t)
        o (try {:ok (encode (run-task t opts))}
               (catch :default e (error-outcome e)))
        _ (trace "task" id "outcome" (pr-str o))]
    (try (swap! outbox assoc (outbox-key id) o)
         (catch :default e
           (swap! outbox assoc (outbox-key id) (error-outcome
                                   (ex-info (str "cohost: the result cannot cross back to the core realm: "
                                                 (ex-message e))
                                            {})))))
    (stat! :ran-here)
    (th/send! (:from t) {:cohost/done id :realm (:realm @R)})))

(defn serve!
  "A worker realm's loop: claim tasks from the inbox, run them, publish
   their outcomes to the outbox, ring the submitter; until asked to stop.
   opts: :eval-in (fn [ns-sym form] value), :ensure-ns! (fn [ns-sym])."
  [opts]
  (trace "serving")
  (loop []
    (let [m (th/receive! -1)]
      (when-not (= m :eve.thread/stop)
        (loop []
          (when-let [t (claim! (:inbox (:mail @R)))]
            (run-and-publish! t opts)
            (recur)))
        (recur)))))
