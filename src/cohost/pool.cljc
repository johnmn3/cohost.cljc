(ns cohost.pool
    "Shared pools: cohost's cross-process work surface.

   A pool is a named queue of work descriptors held in a shared atom's
   coordination state. Processes that open the same domain and attach to
   the same rendezvous atom share the pools: any of them submits work,
   the members whose caps cover a descriptor's :needs claim it, run it,
   and complete it, and leases with heartbeats make a dead member's work
   adoptable by the survivors. The coordination state itself is private
   machinery -- it lives beside the rendezvous atom, and no program
   names it, reads it whole, or swaps it directly. What a program sees
   is this surface: domains and their atoms (ordinary atoms, read with
   deref and changed with swap!), attach/detach, pool registration,
   submission, descriptors and the registry as data, claim/complete for
   members that pull by hand, and tasks -- named, registered work that
   crosses a process as data (a name and its arguments), never as a
   closure.

   Underneath, the machinery is Eve's (its thread domains and task
   registry), hosted here the way cohost hosts Eve everywhere: the Eve
   names stay on this side of the wall, and the program's side is
   clojure-shaped throughout."
    (:refer-clojure :exclude [swap!])
    (:require [clojure.core :as core]
              [eve.atom :as ea]
              [eve.deftype-proto.data :as ed]
              [eve.process :as proc]
              [eve.thread :as et]
              [eve.thread.domain :as dom]
              [eve.thread.meta :as mt]
              [eve.thread.task :as task]
              [eve.thread.rung :as rung]
              [eve.thread.fold :as tf]
              #?@(:cljs [[eve.thread.net :as tn]
                         [eve.net.platform :as platform]])))

;; ---------------------------------------------------------------------------
;; time (Eve's process clock, the one the leases read)

(defn now-ms
      "The wall clock in milliseconds -- the same clock pool leases and
   descriptor timestamps read."
      [] (proc/now-ms))

(defn sleep-ms!
      "Sleep ms milliseconds."
      [ms] (proc/sleep-ms! ms))

(defn start-timer!
      "Call f every period-ms until the returned handle is stopped: a
   member's own ticker (a folder watcher, a heartbeat on a seat with
   no pool ticker of its own). The handle is the host's."
      [period-ms f] (proc/start-timer! period-ms f))

;; ---------------------------------------------------------------------------
;; domains and their atoms: shared state, opened by every member alike.
;; An atom here is an ordinary atom to its program: deref reads it,
;; swap! changes it, atomically against every other process holding it.

(defn open-domain
      "The shared domain at file base (created when absent): the place a
   set of named atoms lives, opened by the launcher and by every member
   that joins the run."
      [base]
      (ea/persistent-atom-domain base))

(defn domain-atom
      "The atom named name in domain, created holding init when absent --
   create-or-join: a process that opens a name another process already
   holds joins that atom, and init is ignored."
      [domain name init]
      (ea/lookup-or-create-mmap-atom! domain name init))

(defn open-rungs
      "The named atoms of a domain reached over the network instead of the
   disk (a member that must not write the domain itself): open-rung
   names an atom, and the result is {key rung} for [key name] pairs.
   A rung is read and swapped through read-atom/swap-atom!, as an atom
   is, and attach takes one as its rendezvous."
      [open-rung pairs]
      (into {} (map (fn [[k nm]] [k (open-rung nm)]) pairs)))

(defn- rung? [a] (satisfies? rung/IRung a))

(defn read-atom
      "a's value: an atom's by deref, a network rung's by its read."
      [a]
      (if (rung? a) (rung/value a) @a))

(defn swap-atom!
      "a's value replaced by (apply f value args), atomically against every
   other writer of it, in this process or another. Answers nil."
      [a f & args]
      (if (rung? a)
          (rung/-swap! a (fn [v] (apply f v args)))
          (core/swap! a (fn [v] (apply f v args))))
      nil)

(defn root-version
      "An atom's root version word: a cheap change mark for pollers (two
   loads, no materialization of the value). A rung has none (its read
   is the poll); nil for one."
      [a]
      (when-not (rung? a) (second (ea/version a))))

;; ---------------------------------------------------------------------------
;; the network door: an atom reached through a gateway, for the members
;; (a browser's workers above all) that have no disk to open.

#?(:cljs
   (defn net-platform
         "This realm's network platform value, for net-atom's third arity."
         [] (platform/platform)))

;; net-atom lives on the arms whose seats load Eve's network rung (the
;; browser's and the JVM's); a seat without it has no net-atom to call.
#?(:cljs
   (defn net-atom
         "The atom registered as name at the gateway cfg names
      ({:endpoint :domain-id :api-key}), reached over the network:
      read-atom/swap-atom! take it, and attach takes it as a rendezvous."
         ([cfg name] (tn/rung cfg name))
         ([cfg name platform] (tn/rung cfg name platform)))
   :clj
   (defn net-atom
         "The atom registered as name at the gateway cfg names
      ({:endpoint :domain-id :api-key}), reached over the network:
      read-atom/swap-atom! take it, and attach takes it as a rendezvous.
      The network rung is resolved at call time, not required: this
      namespace is also a macro namespace (deftask) for ClojureScript
      builds whose JVM pass has no HTTP adapter on its classpath, and
      a load-time require of the network stack would take the macros
      down with it (measured in the page build, 2026-11-17)."
         ([cfg name] ((requiring-resolve 'eve.thread.net/rung) cfg name))
         ([cfg name platform] ((requiring-resolve 'eve.thread.net/rung) cfg name platform))))

;; ---------------------------------------------------------------------------
;; attach: this process joins a rendezvous atom's pools

(defn attach
      "Join the pools held at rendezvous atom a (a domain atom, or a network
   rung of one). opts: :rid (this runtime's name in the registry),
   :ttl (its lease horizon, ms; heartbeats renew at a quarter of it),
   :caps (the work it is suited for: a descriptor's :needs must be
   covered by its claimer's caps), :requires (namespaces its worker
   threads load: the tasks they run), :threads false (no workers of its
   own: it renews on its own swaps and pulls by hand with claim).
   The handle: every other call in this section takes it."
      ([a] (dom/attach! a))
      ([a opts] (dom/attach! a opts)))

(defn detach
      "Leave the pools: this runtime's leases end and what it held is
   adoptable by the survivors at once."
      [h] (dom/detach! h))

(defn serve-pool
      "Pool name of the domain, registered unless it is there (the first
   registration's descriptor stands), and served by :threads workers of
   this runtime (0, the default: this runtime only submits to it).
   opts: :threads, :retry (the pool's default restart policy)."
      ([h name] (dom/pool h name))
      ([h name opts] (dom/pool h name opts)))

(defn registry
      "The runtimes joined to the pools, as data: {rid {:caps [...]
   :pools [...] :host ...}} -- each runtime's descriptor, leases aside
   (a runtime's liveness is runtime-alive?'s question)."
      [h] (:runtimes (dom/registry h)))

(defn runtimes
      "The registry's runtimes with their leases: {rid {:caps #{...} :ttl ms
   :hb last-beat-ms ...}} -- the coverage a planner routes by, read with
   runtime-alive?."
      [h] (dom/read-meta h [:runtimes]))

(defn runtime-alive?
      "Is runtime rid lease-alive at now (ms)? A runtime whose lease lapsed
   covers nothing until it beats again."
      [h rid now]
      (mt/runtime-alive? {:runtimes (runtimes h)} rid now))

(defn descriptors
      "Every descriptor in flight, as data: {id {:pool :state :needs :scope
   :after :attempts :ran-on ...}} -- states :queued :running :done
   :failed :cancelled. Settled descriptors are forgotten past a horizon;
   a settled job's record is what it wrote, not its descriptor."
      [h] (dom/read-meta h [:descs]))

(defn- meta-path-of
       "The coordination state's value at path, read at rendezvous atom a
   without attaching (a launcher's or a check's read: it joins nothing,
   renews nothing)."
       [a path]
       (let [r (if (rung? a) a (rung/eve-rung a))]
            (rung/-read (rung/-meta-rung r {})
                        (fn [v] (ed/eve->clj (get-in v path))))))

(defn descriptors-of
      "The descriptors in flight at rendezvous atom a, as descriptors
   answers them, for a reader that is not a runtime of the pools (the
   launcher's checks, a progress report)."
      [a] (meta-path-of a [:descs]))

(defn runtimes-of
      "The runtimes joined at rendezvous atom a, as runtimes answers them,
   for a reader that is not one of them."
      [a] (meta-path-of a [:runtimes]))

;; ---------------------------------------------------------------------------
;; submission: work is data -- {:task qualified-sym :args [value ...]} --
;; submitted under this runtime's name, in one swap of the coordination
;; state per call, however many descriptors the call submits.

(defn submit-one
      "One descriptor for work on pool, submitted by this runtime: its id.
   opts: :needs (caps a claimer must have), :after (ids it waits on),
   :scope, :retry, :priority."
      [h pool work opts]
      (dom/swap-meta! h (fn [m]
                            (let [[m ids] (mt/submit m pool [work] (:rid h) (or opts {}) (now-ms))]
                                 [m (first ids)]))))

(defn submit-batch
      "Many descriptors in ONE swap -- a plan submitted atomically, so a
   crash leaves none of it half-wired. entries: [{:key k :pool p :work w
   :opts {...}}]; an entry's :after may name ids, or the :key of an
   earlier entry of the same batch (resolved to that entry's id as the
   batch is laid down). Answers {key id} for the entries submitted; the
   batch stops at the first entry a pool refuses (its key is absent)."
      [h entries]
      (dom/swap-meta!
       h
       (fn [m]
           (let [[m ids]
                 (reduce (fn [[m ids] {:keys [key pool work opts]}]
                             (let [opts (update (or opts {}) :after
                                                (fn [after] (mapv (fn [a] (if (and (keyword? a) (contains? ids a))
                                                                              (get ids a) a))
                                                                  after)))
                                   [m got] (mt/submit m pool [work] (:rid h) opts (now-ms))]
                                  (if got
                                      [m (assoc ids key (first got))]
                                      (reduced [m ids]))))
                         [m {}]
                         entries)]
                [m ids]))))

(defn forget
      "Settled descriptors ids leave the coordination state: their record
   is what the jobs wrote; the state stays the size of the work in
   flight."
      [h ids]
      (dom/swap-meta! h (fn [m] [(mt/forget m ids) nil]))
      nil)

;; ---------------------------------------------------------------------------
;; pulling by hand: a member with no workers of its own (an event loop's)
;; claims, runs and completes descriptors itself, and beats its lease.

(defn claim
      "Up to k of pool's queued descriptors this runtime's caps cover and
   its tasks can run, claimed for thread tid: {:claimed [descriptor]}.
   A descriptor's :work is its data ({:task :args}); task-fn resolves
   the task in this runtime."
      [h pool tid k]
      (dom/claim! h pool tid k))

(defn complete
      "The outcome of descriptor id, claimed with claim-token: ok? and the
   value (or the error's data). True when it settled it."
      [h id claim-token ok? v]
      (dom/complete! h id claim-token ok? v))

(defn beat
      "Renew this runtime's lease and its threads tids' (a member with no
   ticker beats from its own loop, at a quarter of its ttl)."
      [h tids]
      (dom/beat! h tids {} 0))

(defn thread-up
      "Thread tid of this runtime has started pulling: recorded, so a
   pool's start is observable to its last thread."
      [h tid]
      (dom/swap-meta! h (fn [m] [(mt/thread-up m (:rid h) tid) nil]))
      nil)

;; ---------------------------------------------------------------------------
;; tasks and folds: the named work, and work over the pools in bulk

(defmacro deftask
          "defn, and the fn registered under its qualified name: a task that
   may cross a process by name, its arguments as data. Every runtime
   that loads the namespace knows the task; which tasks a member runs
   is its pool config's caps, never which file it loaded."
          [name & fdecl]
          `(et/deftask ~name ~@fdecl))

(defn task-fn
      "The fn registered as the task named sym, in this runtime."
      [sym]
      (task/task-fn sym))

(defn fold
      "A fold over coll on this runtime's pools, in chunks sized from
   measurement: (fold combinef reducef coll) -- combinef merges two
   partial answers (and, called with none, seeds one), reducef folds
   each element into a partial. The answer is the combined value."
      ([reducef coll] (tf/fold reducef coll))
      ([combinef reducef coll] (tf/fold combinef reducef coll)))
