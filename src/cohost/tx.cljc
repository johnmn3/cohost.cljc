(ns cohost.tx
  "STM + the STM×agent seam for cohost (doc/eve-cohost/STM-AGENTS-REPORT.md):
   stock Clojure's software transactional memory -- ref, dosync/sync,
   alter, commute, ref-set, ensure, ref history, validators and watches --
   implemented in COHOST's layer, above the shim, on Eve's facilities
   (the unit-5 atom shape for state; the unit-1/2 thread layer for agents).
   It does not ship with Eve and puts nothing in Eve's tree: STM needs
   interruptibility, which is cohost's thread layer's to give, not an
   Eve host guarantee.

   The consistency construction (STATUS 0 of the lane report): Eve's
   snapshots are per-atom, so all of a domain's ref cells live in ONE
   atom -- the world atom. A transaction attempt derefs the world once
   (its read point), serves reads from that snapshot overlaid with its
   own writes, and commits by a single CAS on the world atom after
   validating that every written/ensured ref still stands at the
   version the attempt first touched. One swap is the commit point.
   Commutes are not validated; at commit they fold over the CURRENT
   committed value, in call order. Retry is signalled the JVM's way:
   a dedicated exception thrown up through the body and caught only by
   the runner (STATUS 1 of the lane report). Sends made inside a
   transaction are held in the attempt record and dispatched by the
   successful attempt only, through cohost.thread's send-hold hook.

   A ref is a plain map tagged :cohost/kind ::ref (the layer value
   shape): {:id :world :state}, :state an atom holding the ref's
   non-transactional facts (validator, watches, meta, history bounds).
   The committed cell {:value :version :history} lives under the id
   in the world atom's map. Ref creation mints the cell immediately;
   creation is not transactional, as stock's is not. A transaction
   binds to the world of the first ref it touches; touching another
   world's ref is an IllegalStateException, by name."
  (:refer-clojure :exclude [ref alter commute ref-set ensure sync dosync
                            ref-history-count ref-min-history ref-max-history])
  (:require [cohost.adapter :as host]
            [cohost.thread :as thread]))

;; ---------------------------------------------------------------------------
;; the fill switch (the cohost.atom pattern): the shim's Ref surface and
;; LockingTransaction statics serve the layer only when the fill is in
;; force (boot opt :eve-stm, or a ref-less host whose core names the
;; boot filled from here).

(defonce ^:private fill-flag (atom false))

(defn fill-active? [] (boolean @fill-flag))

(defn set-fill!
  "Turn the STM fill on (the boot opt / the layer's own probe) or off.
   When off, nothing in this namespace is called by the shim."
  [on]
  (reset! fill-flag (boolean on))
  nil)

;; ---------------------------------------------------------------------------
;; the retry signal: ONE shared exception instance, thrown by a failed
;; validation, caught only by run-in-transaction (by its ex-data mark).
;; User code catching ExceptionInfo/Throwable around a dosync body can
;; swallow it -- the JVM's RetryEx has exactly the same exposure, named
;; in the design (STATUS 1).

(defonce ^:private retry-signal (atom nil))

(defn- retry-ex []
  (or @retry-signal
      (let [e (host/ex-info* "cohost.tx: transaction retry" {:cohost.tx/retry true})]
        (reset! retry-signal e)
        e)))

(defn retry-ex? [e]
  (boolean (and (map? (ex-data e)) (:cohost.tx/retry (ex-data e)))))

(defn- illegal-state [msg]
  (host/ex* 'java.lang.IllegalStateException msg nil))

;; ---------------------------------------------------------------------------
;; worlds and refs

(defn- new-world []
  {:cohost/kind ::world
   :cells (atom {})
   :next-id (atom 0)})

(defonce ^:private default-world (atom nil))

(defn world
  "The default world: the domain's one world atom, created on first use.
   Every ref names its world; the default is where refs go when their
   creation names none."
  []
  (or @default-world
      (let [w (new-world)]
        (reset! default-world w)
        w)))

(defn ref-value? [x] (and (map? x) (= ::ref (:cohost/kind x))))

(defn make-ref
  "clojure.lang.Ref's constructor, the layer's shape. opts: :meta,
   :validator, :min-history, :max-history, :world (default: the default
   world). The cell is minted into the world immediately."
  ([x] (make-ref x {}))
  ([x opts]
   (let [w (or (:world opts) (world))
         id (:next-id (swap! (:next-id w) inc))
         ;; swap! returns the new value of next-id atom: (dec) because
         ;; the atom holds the count, ids start at 0
         id (dec id)]
     (swap! (:cells w) assoc id {:value x :version 0 :history []})
     {:cohost/kind ::ref
      :id id
      :world w
      :state (atom {:validator (:validator opts)
                    :watches {}
                    :meta (:meta opts)
                    :min-history (or (:min-history opts) 0)
                    :max-history (or (:max-history opts) 10)})})))

(defn- cell-of [r] (get @(:cells (:world r)) (:id r)))

;; ---------------------------------------------------------------------------
;; the attempt record: a per-scope local atom, dynamically bound.
;; {:world (atom nil-or-world) :snapshot (atom map) :touched
;;  (atom {id {:ref :version :ensured :actions []}}) :held-sends (atom [])}

(def ^:dynamic *tx*
  "The running attempt's record atom on this thread, or nil."
  nil)

(defn in-transaction? [] (some? *tx*))

(defn- attempt-world! [att r]
  (let [w @(:world att)]
    (cond (nil? w) (do (reset! (:world att) (:world r))
                       (reset! (:snapshot att) @(:cells (:world r)))
                       (:world r))
          (identical? w (:world r)) w
          :else (throw (illegal-state
                        "cohost.tx: a transaction cannot touch refs of two worlds")))))

(defn- touch!
  "Enroll ref r in the attempt: record the snapshot version it first
   stands at. The attempt's entry for r."
  [att r]
  (attempt-world! att r)
  (let [id (:id r)
        t @(:touched att)]
    (or (get t id)
        (let [cell (get @(:snapshot att) id)
              e {:ref r :version (:version cell) :ensured false :actions []}]
          (swap! (:touched att) assoc id e)
          e))))

(defn- view-of
  "The attempt's current value of an entry: its snapshot value folded
   with its own actions, in order."
  [att e]
  (let [base (:value (get @(:snapshot att) (:id (:ref e))))]
    (reduce (fn [v [op a b]]
              (case op
                :set a
                :commute (apply a v b)))
            base
            (:actions e))))

(defn- record-action! [att r action]
  (let [e (touch! att r)]
    (swap! (:touched att) update (:id r)
           (fn [e] (update e :actions conj action)))
    (view-of att (get @(:touched att) (:id r)))))

(defn- require-tx! []
  (when-not *tx*
    (throw (illegal-state "No transaction running")))
  *tx*)

;; ---------------------------------------------------------------------------
;; ref operations

(defn deref-ref
  "deref of a layer ref: inside a transaction, the attempt's view
   (snapshot-stable); outside, the latest committed value."
  [r]
  (if *tx*
    (view-of *tx* (touch! *tx* r))
    (:value (cell-of r))))

(defn ref-set*
  "ref-set: the attempt's value of r becomes v; the attempt's view of it."
  [r v]
  (record-action! (require-tx!) r [:set v]))

(defn alter*
  "alter: apply f to the attempt's view of r, record the result as a set.
   A ref altered in an attempt is validated at commit."
  [r f args]
  (let [att (require-tx!)
        e (touch! att r)
        v (apply f (view-of att e) args)]
    (swap! (:touched att) update (:id r)
           (fn [e] (update e :actions conj [:set v])))
    v))

(defn commute*
  "commute: record f to fold over the CURRENT committed value at commit
   time, in call order; the attempt's view applies it now, over its view.
   A commute-only ref is not validated at commit."
  [r f args]
  (record-action! (require-tx!) r [:commute f args]))

(defn ensure*
  "ensure (Ref.touch): enroll r in the attempt's validated set, so a
   concurrent write to it retries this attempt -- the write-skew guard."
  [r]
  (let [att (require-tx!)
        e (touch! att r)]
    (swap! (:touched att) assoc-in [(:id r) :ensured] true)
    (view-of att e)))

(defn ref-history-count [r] (count (:history (cell-of r))))
(defn ref-min-history [r] (:min-history @(:state r)))
(defn ref-max-history [r] (:max-history @(:state r)))
(defn set-min-history! [r n] (swap! (:state r) assoc :min-history n) r)
(defn set-max-history! [r n] (swap! (:state r) assoc :max-history n) r)

;; watches and validators: the ref's own state atom (non-transactional,
;; as stock's are)

(defn add-watch* [r k f] (swap! (:state r) assoc-in [:watches k] f) r)
(defn remove-watch* [r k] (swap! (:state r) update :watches dissoc k) r)
(defn get-watches [r] (:watches @(:state r)))
(defn set-validator* [r f]
  (when f
    (let [v (deref-ref r)]
      (when-not (f v)
        (throw (illegal-state "Invalid reference state")))))
  (swap! (:state r) assoc :validator f)
  nil)
(defn get-validator [r] (:validator @(:state r)))

(defn meta-of [r] (:meta @(:state r)))

(defn with-meta-ref [r m]
  (assoc r :state (atom (assoc @(:state r) :meta m))))

(defn alter-meta-ref [r f args]
  (let [s (swap! (:state r) (fn [s] (assoc s :meta (apply f (:meta s) args))))]
    (:meta s)))

;; ---------------------------------------------------------------------------
;; commit: validate + one CAS on the world atom

(defn- written? [e] (boolean (some #(= :set (first %)) (:actions e))))

(defn- fold-actions
  "Fold an entry's actions over base: :set replaces, :commute applies."
  [base actions]
  (reduce (fn [v [op a b]]
            (case op
              :set a
              :commute (apply a v b)))
          base
          actions))

(defn- commit!
  "Validate the attempt against the world's current cells and swap the
   new world in with one CAS. Throws the retry signal on a validation
   failure; a lost CAS under unrelated contention revalidates locally
   (bounded), then also escalates to the retry signal. Returns the
   watch list [[ref old new] ...] of the winning swap."
  [att]
  (let [w @(:world att)
        cells (:cells w)
        touched @(:touched att)
        snapshot @(:snapshot att)]
    (loop [n 0]
      (let [cur @cells
            bad (some (fn [[id e]]
                        (and (or (:ensured e) (written? e))
                             (not= (:version e) (:version (get cur id)))))
                      touched)]
        (if bad
          (throw (retry-ex))
          (let [watches (atom [])
                news (reduce
                      (fn [m [id e]]
                        (if (empty? (:actions e))
                          m
                          (let [cell (get cur id)
                                final (if (written? e)
                                        (fold-actions (:value (get snapshot id))
                                                      (:actions e))
                                        (fold-actions (:value cell)
                                                      (:actions e)))
                                validator (:validator @(:state (:ref e)))]
                            (when (and validator (not (validator final)))
                              (throw (illegal-state "Invalid reference state")))
                            (swap! watches conj [(:ref e) (:value cell) final])
                            (assoc m id
                                   {:value final
                                    :version (inc (:version cell))
                                    :history (let [h (into [(:value cell)]
                                                           (:history cell))
                                                   mx (ref-max-history (:ref e))]
                                               (if (> (count h) mx)
                                                 (subvec h 0 mx)
                                                 h))}))))
                      cur
                      touched)]
            (if (compare-and-set! cells cur news)
              @watches
              (if (< n 100)
                (recur (inc n))
                (throw (retry-ex))))))))))

(defn- release-sends! [att]
  (doseq [action @(:held-sends att)]
    (thread/enqueue-or-hold! action))
  nil)

(defn run-in-transaction
  "clojure.lang.LockingTransaction.runInTransaction, the layer's shape:
   run f in an attempt; on the retry signal, re-run against a fresh
   snapshot; on the thread's interrupt flag, abandon with the interrupt
   exception (checked once per attempt -- STM is the interruptible
   layer). A nested call joins the running attempt, as stock's does.
   Validators run at commit; watches fire once per written ref after
   the winning swap, before the attempt's held sends are released."
  [f]
  (if *tx*
    (f)
    (loop []
      (when (thread/interrupted?)
        (throw (thread/interrupted-ex)))
      (let [att {:world (atom nil)
                 :snapshot (atom {})
                 :touched (atom {})
                 :held-sends (atom [])}
            outcome (binding [*tx* att]
                      (host/try-all
                       (fn []
                         (let [v (f)
                               watches (commit! att)]
                           {:value v :watches watches}))
                       (fn [e] {:error e})))]
        (if-let [e (:error outcome)]
          (cond (retry-ex? e) (recur)
                (thread/interrupted-ex? e) (throw e)
                :else (throw e))
          (do (doseq [[r old new] (:watches outcome)]
                (doseq [[k wf] (:watches @(:state r))]
                  (wf k r old new)))
              (release-sends! att)
              (:value outcome)))))))

;; ---------------------------------------------------------------------------
;; the send-hold hook: installed at load. A send dispatched while an
;; attempt runs on this thread is held in the attempt record; commit
;; releases it (exactly once -- a retried attempt's record dies with
;; it, and the re-run's sends are recorded afresh).

(defn- hold-send [action]
  (if *tx*
    (do (swap! (:held-sends *tx*) conj action)
        true)
    false))

(thread/set-send-hold-hook! hold-send)

;; ---------------------------------------------------------------------------
;; the clojure.core fill for hosts whose core has no STM names: intered
;; by cohost.boot alongside the thread layer's fill. Hosts whose core
;; boots stock's names (JVM, jank) reach the layer through the shim's
;; statics and Ref surface instead, under the :eve-stm fill.

(defn- dosync-macro [_form _env & body]
  (list 'cohost.tx/run-in-transaction (list* 'fn [] body)))

(defn- sync-macro [_form _env _flags & body]
  ;; the flags (:release-pending-sends) are not modeled: pending sends
  ;; here are the agent machinery's, released at action end; named in
  ;; the lane report as a distance.
  (list 'cohost.tx/run-in-transaction (list* 'fn [] body)))

(def core-impls
  {'ref (fn [x & {:keys [meta validator min-history max-history]}]
          (make-ref x {:meta meta :validator validator
                       :min-history min-history :max-history max-history}))
   'alter (fn [r f & args] (alter* r f args))
   'commute (fn [r f & args] (commute* r f args))
   'ref-set (fn [r v] (ref-set* r v))
   'ensure (fn [r] (ensure* r))
   'ref-history-count ref-history-count
   'ref-min-history ref-min-history
   'ref-max-history ref-max-history})

(def core-macros
  "The fill's macros, name -> macro fn; boot marks them after interning."
  {'dosync dosync-macro
   'sync sync-macro})
