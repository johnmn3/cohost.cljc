(ns cohost.tx
  "STM + the STM×agent seam for cohost (doc/eve-cohost/STM-AGENTS-REPORT.md):
   stock Clojure's software transactional memory -- ref, dosync/sync,
   alter, commute, ref-set, ensure, ref history, validators and watches --
   implemented in COHOST's layer, above the shim, on Eve's facilities
   (cohost.atom for ref cells; the unit-1/2 thread layer for agents).
   It does not ship with Eve and puts nothing in Eve's tree: STM needs
   interruptibility, which is cohost's thread layer's to give, not an
   Eve host guarantee.

   The consistency construction (STATUS 3 of the lane report, replacing
   the rejected world-atom design): Clojure's own mechanism. Each ref's
   committed state is a cell -- {:value :point :version :history} --
   held by an atom that is the ref's alone: where the seat's Eve
   hosting is installed, a cohost.atom layer atom whose Eve handle is
   the sole atom of a FRESH Eve atom domain (the ref's own contention
   domain: its own slab, root, epoch, arbitration); where no Eve
   hosting exists, a plain host atom, itself a contention domain of
   one. A transaction fixes a read point (the layer's commit-point
   counter, stock's lastPoint) and reads each ref's value as of that
   point from its per-ref versioned history. Commit locks each ref it
   installs into and each ref it ensured (the lock lives in the ref's
   own state atom, where stock keeps its locks -- in the Ref, not the
   value; stock's ensure write-locks the same way), validates that
   every written or ensured ref still stands at the point the attempt
   read, claims the next commit point, and installs each written ref
   with one CAS on its own cell atom. Commutes are not validated; at
   commit they fold over the ref's CURRENT committed value, in call
   order. Plain read-only refs are not validated at commit -- that is
   stock's answer, and it is why ensure exists. A read, in a
   transaction or out, waits while another attempt holds the ref's
   commit lock, as stock's Ref read lock does: commit holds its locks
   from before it claims its point until its last install, so no
   reader sees part of a commit (defect F1). Transactions touching
   disjoint refs lock, validate and CAS disjoint atoms; the only
   shared write is the commit-point increment, which carries no ref's
   data and can make no attempt retry. Retry is signalled the JVM's
   way: a dedicated exception thrown up through the body and caught
   only by the runner (STATUS 1 of the lane report). Sends made inside
   a transaction are held in the attempt record and dispatched by the
   successful attempt only, through cohost.thread's send-hold hook.

   THE SINGLE-WRITER CONTRACT (the lane report's STATUS 14 item;
   dispositioned by the operator, 2026-11-06): a ref's cell is
   written ONLY through this layer -- transactions, and agent sends
   the layer releases at commit. The cell rides an ordinary atom and
   nothing physically stops other code from swapping it, but doing
   so is outside the contract: state that needs uncoordinated
   writers is a plain Eve atom, not a ref -- the choice stock
   Clojure makes at the type level, where a Ref's cell cannot be
   written outside a transaction at all. An out-of-band write can
   move a cell where no legal commit sequence can put it (its point
   beyond the commit-point clock, its retained history not reaching
   an attempt's read point), and an attempt against such a cell can
   never validate: it would retry identically forever. The runner
   therefore counts those impossible-progress failures (basis
   faults, below) and, at basis-fault-limit, faults the transaction
   with an IllegalStateException -- stock's answer, cf.
   LockingTransaction's retry limit -- instead of spinning. The
   bound is scoped to impossible progress: ordinary
   transaction-vs-transaction contention retries are NOT counted
   and stay unbounded, as stock's do.

   A ref is a plain map tagged :cohost/kind ::ref (the layer value
   shape): {:seq :cell :state}; :state is a host atom holding the
   ref's non-transactional facts (validator, watches, meta, history
   bounds, commit lock). Ref creation is not transactional, as
   stock's is not."
  (:refer-clojure :exclude [ref alter commute ref-set ensure sync dosync
                            ref-history-count ref-min-history ref-max-history])
  (:require [cohost.adapter :as host]
            [cohost.atom :as atom-layer]
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

;; ---------------------------------------------------------------------------
;; the basis-fault signal: the retry signal's sibling, thrown instead
;; of it when a failure carries the out-of-band signature (a cell
;; standing beyond the commit-point clock -- see beyond-clock?). The
;; runner counts these per transaction invocation and, at
;; basis-fault-limit, faults the transaction (IllegalStateException)
;; rather than retrying: the attempt's basis is being destroyed by a
;; writer outside the layer, so no retry can land. Ordinary
;; validation failures keep the plain retry signal and are never
;; counted.

(defonce ^:private basis-fault-signal (atom nil))

(defn- basis-fault-ex []
  (or @basis-fault-signal
      (let [e (host/ex-info* "cohost.tx: transaction basis fault (cell moved outside the layer)"
                             {:cohost.tx/basis-fault true})]
        (reset! basis-fault-signal e)
        e)))

(defn basis-fault-ex? [e]
  (boolean (and (map? (ex-data e)) (:cohost.tx/basis-fault (ex-data e)))))

(defn- illegal-state [msg]
  (host/ex* 'java.lang.IllegalStateException msg nil))

;; ---------------------------------------------------------------------------
;; attempt statistics (probes read these; the runner maintains them)

(defonce ^:private stats* (atom {:attempts 0 :retries 0 :commits 0}))

(defn stats [] @stats*)

(defn reset-stats! [] (reset! stats* {:attempts 0 :retries 0 :commits 0}) nil)

;; ---------------------------------------------------------------------------
;; the commit-point clock: one layer atom holding the last commit
;; point. It carries no ref's data; it is the ordering stock keeps as
;; LockingTransaction's global lastPoint, and the only state refs of
;; one program share.

(defonce ^:private commit-point (atom 0))

(def basis-fault-limit
  "How many impossible-progress (basis-fault) failures one
   run-in-transaction invocation tolerates before faulting the
   transaction with an IllegalStateException. The number is stock's:
   LockingTransaction's RETRY_LIMIT is 10000, after which stock
   throws \"Transaction failed after reaching retry limit\". The
   SCOPE is deliberately narrower than a global attempt cap: only
   basis faults count toward it. Contention retries -- validation
   failures against movement the layer's own commit path produced --
   are not counted, are not reset by basis faults, and stay
   unbounded: they are the model working."
  10000)

(defn- beyond-clock?
  "True when a cell's point stands beyond the commit-point clock.
   The layer's commit path cannot produce that state: commit!
   claims the next point from the clock and install! writes only
   claimed points, and the clock never decreases, so every
   layer-installed cell satisfies (:point cell) <= @commit-point at
   every later observation. A cell beyond the clock was moved by a
   writer outside the layer -- the single-writer contract's
   violation signature, and the precise shape of the STATUS 14
   manufacture (a cell bumped past the clock over history that does
   not reach back)."
  [cell]
  (> (:point cell) @commit-point))

;; ---------------------------------------------------------------------------
;; cells: a ref's committed state {:value :point :version :history},
;; history a newest-first vector of {:point :value} the value had at
;; earlier commit points. The cell atom is the ref's alone (see the
;; ns docstring): a cohost.atom layer atom over a fresh Eve domain
;; where Eve hosting is installed, else a host atom.

(defn- new-cell [x]
  (let [init {:value x :point 0 :version 0 :history []}]
    (if (atom-layer/available?)
      (atom-layer/make-domain-atom init {})
      (atom init))))

(defn- layer-cell? [r] (atom-layer/atom-value? (:cell r)))

(defn- cell-deref [r]
  (if (layer-cell? r)
    (atom-layer/deref-atom (:cell r))
    @(:cell r)))

(defn- cell-cas! [r old new]
  (if (layer-cell? r)
    (atom-layer/compare-and-set-atom! (:cell r) old new)
    (compare-and-set! (:cell r) old new)))

(defn cell-residence
  "Where a ref's cell lives: :eve-own-domain (a layer atom, sole atom
   of its own Eve domain) or :host-atom. A measured fact per ref --
   probes print it, docs don't assert it."
  [r]
  (if (layer-cell? r) :eve-own-domain :host-atom))

;; ---------------------------------------------------------------------------
;; refs

(defonce ^:private ref-seq (atom 0))

(defn ref-value? [x] (and (map? x) (= ::ref (:cohost/kind x))))

(defn make-ref
  "clojure.lang.Ref's constructor, the layer's shape. opts: :meta,
   :validator, :min-history, :max-history. The cell is minted
   immediately; creation is not transactional, as stock's is not."
  ([x] (make-ref x {}))
  ([x opts]
   {:cohost/kind ::ref
    :seq (swap! ref-seq inc)
    :cell (new-cell x)
    :state (atom {:validator (:validator opts)
                  :watches {}
                  :meta (:meta opts)
                  :min-history (or (:min-history opts) 0)
                  :max-history (or (:max-history opts) 10)
                  :lock nil})}))

(defn- value-at
  "The cell entry an attempt with read point rp sees: [point value],
   the newest entry with point <= rp -- or ::too-short when the
   ref's retained history no longer reaches rp (the attempt must
   retry with a fresh point; stock faults the same way)."
  [cell rp]
  (if (<= (:point cell) rp)
    [(:point cell) (:value cell)]
    (or (first (map (fn [e] [(:point e) (:value e)])
                    (filter (fn [e] (<= (:point e) rp)) (:history cell))))
        ::too-short)))

;; ---------------------------------------------------------------------------
;; the attempt record: a per-scope local atom, dynamically bound.
;; {:id :rp (atom nil-or-long) :touched (atom {seq entry})
;;  :held-sends (atom []) :locks-held (atom bool)}
;; :locks-held is true while commit! holds (or is taking) the attempt's
;; commit locks -- the one phase whose read waits are bounded.
;; entry: {:ref :base-point :base-value :ensured :actions []}

(def ^:dynamic *tx*
  "The running attempt's record on this thread, or nil."
  nil)

(defn in-transaction? [] (some? *tx*))

(defonce ^:private attempt-seq (atom 0))

(defn- read-point!
  "The attempt's read point, fixed lazily at its first ref touch."
  [att]
  (or @(:rp att)
      (let [p @commit-point]
        (reset! (:rp att) p)
        p)))

(def read-lock-wait-ms
  "How long a read performed WHILE ITS ATTEMPT HOLDS COMMIT LOCKS
   (commit phase: the attempt's :locks-held flag, set by commit!)
   waits on a ref whose commit lock another attempt holds before
   giving its attempt up with the plain retry signal (contention:
   never counted, unbounded). Only there: body-phase reads hold no
   commit locks, so their waits cannot form a cycle and are
   UNBOUNDED, as stock's read lock is (the Q-STM-WAIT rule). Under
   locks the bound turns a wait that cannot end -- two committers
   whose validators each read a ref the other holds locked -- into a
   retry. A commit holds its locks far shorter than this (STM report,
   defect F1)."
  100)

(def ^:private read-lock-spins
  ;; checks of a held lock before the wait backs off to 1 ms timed
  ;; waits: a host-cell commit section passes inside the spin; an
  ;; Eve-cell one (milliseconds) is waited out without starving the
  ;; committer of the CPU it needs to finish (measured, defect F1)
  1000)

(defn- held-by-other
  "The id of the attempt holding r's commit lock when that is not
   att (att nil: any holder), else nil."
  [att r]
  (let [l (:lock @(:state r))]
    (when (and (some? l) (not= l (:id att))) l)))

(defn- await-unlocked!
  "Stock's read lock (Ref.lock.readLock, taken by
   LockingTransaction.doGet and by Ref.currentVal): do not read r's
   cell while another attempt holds r's commit lock. commit! holds its
   locks from before it claims its commit point until after its last
   install, so a read that passes this wait AFTER its read point was
   fixed sees every install of every commit whose point it can read.
   Without it (defect F1), an attempt whose read point equals a
   commit's point read that commit's installed refs new and its
   not-yet-installed refs old: a torn snapshot, which a read-only
   transaction returns (read-only refs are not validated -- stock).
   The wait is UNBOUNDED in body phase and outside a transaction (att
   nil), as Ref.currentVal's read lock is: those reads hold no commit
   locks, so they cannot form a cycle (Q-STM-WAIT). Only a read made
   while its attempt HOLDS its commit locks (the :locks-held flag
   commit! sets -- a validator that derefs) gives up after
   read-lock-wait-ms with the plain retry signal. The wait spins
   read-lock-spins checks, then backs off in 1 ms timed waits on a
   fresh doorbell no one rings (the thread layer's wait primitive used
   as a timed park; a host without doorbells keeps spinning)."
  [att r]
  (when (held-by-other att r)
    (let [deadline (when (and att @(:locks-held att))
                     (+ (host/current-time-millis) read-lock-wait-ms))
          bell (host/doorbell)]
      (loop [n 0]
        (when (held-by-other att r)
          (when (and deadline (> (host/current-time-millis) deadline))
            (throw (retry-ex)))
          (when (and bell (>= n read-lock-spins))
            (host/doorbell-wait bell 1))
          (recur (inc n)))))))

(defn- touch!
  "Enroll ref r in the attempt: read its value as of the attempt's
   read point and record the base it stands at. The attempt's entry
   for r. The read point is fixed FIRST; then the read waits out
   another attempt's commit lock on r (await-unlocked!, defect F1)."
  [att r]
  (let [t @(:touched att)]
    (or (get t (:seq r))
        (let [rp (read-point! att)
              _ (await-unlocked! att r)
              cell (cell-deref r)
              va (value-at cell rp)]
          (when (= ::too-short va)
            ;; ::too-short against a cell within the clock is the
            ;; legal history-trim case: a fresh attempt's read point
            ;; advances to the clock and is served -- plain retry.
            ;; Against a cell beyond the clock the fresh point can
            ;; never be served: a basis fault (STATUS 14's spin).
            (throw (if (beyond-clock? cell) (basis-fault-ex) (retry-ex))))
          (let [e {:ref r :base-point (first va) :base-value (second va)
                   :ensured false :actions []}]
            (swap! (:touched att) assoc (:seq r) e)
            e)))))

(defn- view-of
  "The attempt's current value of an entry: its base value (as of
   the read point) folded with its own actions, in order."
  [att e]
  (reduce (fn [v [op a b]]
            (case op
              :set a
              :commute (apply a v b)))
          (:base-value e)
          (:actions e)))

(defn- record-action! [att r action]
  (let [e (touch! att r)]
    (swap! (:touched att) update (:seq r)
           (fn [e] (update e :actions conj action)))
    (view-of att (get @(:touched att) (:seq r)))))

(defn- require-tx! []
  (when-not *tx*
    (throw (illegal-state "No transaction running")))
  *tx*)

;; ---------------------------------------------------------------------------
;; ref operations

(defn deref-ref
  "deref of a layer ref: inside a transaction, the attempt's view
   (the value as of its read point, folded with its own writes);
   outside, the latest committed value, read once no commit holds the
   ref's lock (Ref.currentVal's read lock: a reader that has seen one
   ref of a commit cannot then see another ref of that commit older --
   defect F1, outside a transaction)."
  [r]
  (if *tx*
    (view-of *tx* (touch! *tx* r))
    (do (await-unlocked! nil r)
        (:value (cell-deref r)))))

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
    (swap! (:touched att) update (:seq r)
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
   concurrent commit to it retries this attempt -- the write-skew guard."
  [r]
  (let [att (require-tx!)
        e (touch! att r)]
    (swap! (:touched att) assoc-in [(:seq r) :ensured] true)
    (view-of att e)))

(defn ref-history-count [r] (count (:history (cell-deref r))))
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
;; commit: per-ref locks + per-ref validation + per-ref CAS installs

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

(defn- try-lock!
  "CAS the ref's commit lock from free to this attempt's id. The lock
   is a field of the ref's own state atom -- host-atom CAS, never an
   Eve publish."
  [att r]
  (let [st (:state r)]
    (loop []
      (let [s @st]
        (cond (nil? (:lock s)) (if (compare-and-set! st s (assoc s :lock (:id att)))
                                 true
                                 (recur))
              (= (:id att) (:lock s)) true
              :else false)))))

(defn- lock! [att r]
  ;; commit sections are tiny; spin briefly before escalating to the
  ;; retry signal (the commute case wants exactly this: serialize at
  ;; commit, never re-run the body for a lock alone, if it can help it)
  (loop [n 0]
    (cond (try-lock! att r) true
          (< n 100000) (recur (inc n))
          :else false)))

(defn- unlock! [att r]
  (let [st (:state r)]
    (loop []
      (let [s @st]
        (when (= (:id att) (:lock s))
          (when-not (compare-and-set! st s (assoc s :lock nil))
            (recur))))))
  nil)

(defn- trim-history
  "Bound a history vector: never more than max-history entries,
   never fewer than min-history (stock's fault window lives between
   the two: a read point older than the oldest retained entry
   retries with a fresh point).

   The kept prefix is rebuilt as a FRESH vector, never a subvec:
   a subvec shares the untrimmed vector's backing storage, so the
   trimmed entries would stay reachable through the cell -- on a
   GC host a small bounded over-retention, on a host without a GC
   (the VS seat) a slab tree pinned forever per ref. A trim must
   RELEASE, not merely unlist (report STATUS 9; probe_tx_reclaim
   caught the subvec shape retaining exactly one trimmed version)."
  [h mn mx]
  (let [keep (max mn mx)]
    (if (> (count h) keep)
      (vec (take keep h))
      h)))

(defn- install!
  "Install one written/commuted entry under its lock: CAS the ref's
   cell from the state validation saw to the new cell at commit
   point cp. Returns [ref old-value final-value] for the watches."
  [att e cp]
  (let [r (:ref e)]
    (loop [n 0]
      (let [cur (cell-deref r)
            final (if (written? e)
                    (fold-actions (:base-value e) (:actions e))
                    (fold-actions (:value cur) (:actions e)))
            new-cell {:value final
                      :point cp
                      :version (inc (:version cur))
                      :history (trim-history
                                (into [{:point (:point cur) :value (:value cur)}]
                                      (:history cur))
                                (ref-min-history r)
                                (ref-max-history r))}]
        (cond (cell-cas! r cur new-cell) [r (:value cur) final]
              (< n 100) (recur (inc n))
              :else (throw (if (beyond-clock? cur) (basis-fault-ex) (retry-ex))))))))

(defn- commit!
  "Validate the attempt against the touched refs' own cells and
   install its writes, one CAS per ref, under the refs' own locks.
   Throws the retry signal on a validation failure or a lock that
   stays held; returns the watch list [[ref old new] ...]."
  [att]
  (let [touched @(:touched att)
        entries (sort-by (fn [e] (:seq (:ref e))) (vals touched))
        installs (filter (fn [e] (seq (:actions e))) entries)
        ;; the lock set is installs UNION ensured: stock's ensure
        ;; write-locks the ref for the rest of the transaction
        ;; (LockingTransaction.ensure), and that lock is what closes
        ;; the validate/install race for a ref this attempt does not
        ;; write. Locking only installs left the race open -- invisible
        ;; over nanosecond host cells, a lost skew check over
        ;; millisecond Eve cells (probe_tx over domain cells,
        ;; 2026-11-05). Validation below then runs under the locks.
        lock-set (filter (fn [e] (or (seq (:actions e)) (:ensured e))) entries)
        locked (atom [])]
    ;; the commit phase holds locks from here to the finally: reads
    ;; made under them (a validator that derefs) are the ONLY bounded
    ;; read waits (Q-STM-WAIT, await-unlocked!)
    (reset! (:locks-held att) true)
    (try
      ;; (1) lock every ref we will install into or ensured, in
      ;; creation order
      (doseq [e lock-set]
        (if (lock! att (:ref e))
          (swap! locked conj (:ref e))
          (throw (retry-ex))))
      ;; (2) validate: every written or ensured ref must still stand
      ;; at the point the attempt read. Read-only refs and
      ;; commute-only refs are not validated (stock's answers).
      (doseq [e entries]
        (when (or (:ensured e) (written? e))
          (let [cur (cell-deref (:ref e))]
            (when (not= (:base-point e) (:point cur))
              ;; a validation failure against a cell the commit path
              ;; moved is contention (plain retry); against a cell
              ;; standing beyond the clock it is a basis fault.
              (throw (if (beyond-clock? cur) (basis-fault-ex) (retry-ex)))))))
      ;; (3) validators against final values
      (doseq [e installs]
        (let [validator (:validator @(:state (:ref e)))]
          (when validator
            (let [final (if (written? e)
                          (fold-actions (:base-value e) (:actions e))
                          (fold-actions (:value (cell-deref (:ref e))) (:actions e)))]
              (when-not (validator final)
                (throw (illegal-state "Invalid reference state")))))))
      ;; (4) claim the commit point, (5) install per ref
      (let [cp (swap! commit-point inc)]
        (mapv (fn [e] (install! att e cp)) installs))
      (finally
        (doseq [r @locked]
          (unlock! att r))
        (reset! (:locks-held att) false)))))

(defn- release-sends! [att]
  (doseq [action @(:held-sends att)]
    (thread/enqueue-or-hold! action))
  nil)

(defn run-in-transaction
  "clojure.lang.LockingTransaction.runInTransaction, the layer's shape:
   run f in an attempt; on the retry signal, re-run against a fresh
   read point; on the thread's interrupt flag, abandon with the
   interrupt exception (checked once per attempt -- STM is the
   interruptible layer). A nested call joins the running attempt, as
   stock's does. Validators run at commit; watches fire once per
   written ref after the winning installs, before the attempt's held
   sends are released.

   Retries are unbounded for contention. Basis faults -- failures
   carrying the out-of-band signature (the single-writer contract,
   see the ns docstring) -- are counted per invocation; at
   basis-fault-limit the invocation throws an IllegalStateException
   instead of retrying, as stock's runner faults after its retry
   limit."
  [f]
  (if *tx*
    (f)
    (loop [basis-faults 0]
      (when (thread/interrupted?)
        (throw (thread/interrupted-ex)))
      (swap! stats* update :attempts inc)
      (let [att {:id (swap! attempt-seq inc)
                 :rp (atom nil)
                 :touched (atom {})
                 :held-sends (atom [])
                 :locks-held (atom false)}
            outcome (binding [*tx* att]
                      (host/try-all
                       (fn []
                         (let [v (f)
                               watches (commit! att)]
                           {:value v :watches watches}))
                       (fn [e] {:error e})))]
        (if-let [e (:error outcome)]
          (cond (basis-fault-ex? e)
                (if (>= basis-faults basis-fault-limit)
                  (throw (illegal-state "Transaction failed after reaching retry limit"))
                  (do (swap! stats* update :retries inc)
                      (recur (inc basis-faults))))
                (retry-ex? e) (do (swap! stats* update :retries inc)
                                  (recur basis-faults))
                (thread/interrupted-ex? e) (throw e)
                :else (throw e))
          (do (swap! stats* update :commits inc)
              (doseq [[r old new] (:watches outcome)]
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
