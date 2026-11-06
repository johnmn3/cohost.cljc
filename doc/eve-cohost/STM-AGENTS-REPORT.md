# Eve-in-cohost — STM + agents (Lane M subagent lane)

Branch `muse/eve-cohost-stm-agents`, off `muse/eve-cohost-impl`
@ f78853a. Operator's direct order 2026-11-05 ~7:16 PM ET:
"spin up a local subagent on implementing an STM and agents —
for cohost." This supersedes the morning Q2 ruling ("no STM
for now"). Layering ruling (John, same evening): **STM over
cohost over Eve over Jank** — the STM lives in COHOST's layer,
built on Eve's facilities; it does not ship with Eve, because
STM needs interruptibility, which not every Eve host can
guarantee. JVM is the semantic reference seat; jank is the
named target stack.

Prior state, measured (not recalled):

- Agents: unit 1's `cohost.thread` already implements the
  agent machinery behind the shim (state/queue/ctl atoms,
  error modes, handlers, restart, nested-send holding,
  await/await-for, shutdown) and fills the core names on
  hosts that lack them. On the JVM seat a default boot's
  agents are the host's own `clojure.lang.Agent`.
- STM: three different answers by seat. JVM seat (default
  boot): the host's own `clojure.lang.Ref` + host
  LockingTransaction (scratch probe: `(type (ref 1))` =
  `class clojure.lang.Ref`). Jank: the host core's `ref`
  is the shim's atom fallback (`adapter/atom*`), `sync`/
  `dosync` expand to the shim's `LockingTransaction`
  statics, `alter`/`commute`/`ref-set`/`ensure` route to
  the shim's vars — i.e. the shim's undo-log hack IS jank's
  STM today. Ref-less hosts (cljs, basilisp, ...): the
  same shim surface — `new-Ref` falls back to a host atom,
  transactions run the body once, writes apply immediately
  (no isolation, no snapshot, commute = alter, watches
  fire per write, abort undoes via saved values). Unit 5's
  census: the whole gated surface is one battery row
  ("refs: a transaction, and none") + core/test bookkeeping
  shapes (single ref, alter/commute, one dosync).

## STATUS 0 — 2026-11-05 ~7:30 PM ET — design

### The consistency construction (the brief's required paragraph)

Eve's snapshots are per-atom; there is no cross-atom instant
in the model, and this design does not pretend otherwise.
It makes the per-atom snapshot be the transaction's snapshot
by putting **all of a domain's ref cells in ONE atom — the
domain's world atom**. A ref is a handle value
(`{:cohost/kind ::ref :id :world :state}`): its committed
state is the cell `{:value :version :history}` under its id
in the world atom's value (an immutable map); per-ref
non-transactional state (validator, watches, meta, history
bounds) lives in the ref's own state atom — the unit-5
wrapper shape. A transaction attempt (a) derefs the world
atom ONCE — a per-atom snapshot, exactly the model's
primitive — and keeps that immutable map as its read point;
(b) serves every read from the snapshot overlaid with its
own writes, so an in-flight transaction's view cannot move;
(c) buffers writes in the attempt record (a per-scope local
atom, dynamically bound — transaction state lives in an
atom, temporaries die with the scope), recording per ref
the snapshot version it first touched, an optional set
value, and the ordered commute list; (d) commits by
arbitration on the world atom alone: a compare-and-set
loop whose pure step validates that every written
(non-commute) and every ensured ref still stands at its
recorded version in the current world, computes the new
world (set values in; commutes folded over the CURRENT
committed values in call order; versions bumped; replaced
values prepended to bounded per-ref histories), and swaps
it in with one CAS. One swap = the atomic commit point:
before it, none of the transaction's writes exist anywhere;
after it, all of them do. A failed validation discards the
attempt and re-runs the body against a fresh snapshot
(lock-free progress: some transaction's CAS wins every
round). Plain reads are snapshot-stable by construction;
write skew is prevented the stock way — `ensure` enrolls a
ref in the validated set. Validators run at commit against
final values (core's own docstring for `ref`); watches fire
once per written ref, after the winning swap, with the
pre-commit and final committed values, before the
transaction's held agent sends are released. Sends made
inside a transaction are held in the attempt record and
dispatched only by the successful attempt — a retried or
aborted attempt's sends die with it, so a send fires
exactly once, only on commit (the STM×agent seam:
`cohost.thread`'s dispatch consults a hold hook the tx
layer installs). Ref creation mints a cell into the world
immediately (creation is not transactional, as stock's is
not). A transaction binds to the world of the first ref it
touches; touching another world's ref is a named
IllegalStateException. The world atom itself: where the
seat's Eve hosting is installed it is a unit-5 layer atom
(the refs ride on an Eve atom — the runtime domain's world
atom; a world holding unrepresentable values seats in the
wrapper exactly as unit 5's atoms do); where no Eve hosting
exists on the seat it is a plain host atom, the layer's
universal state shape (every layer value's state atom is
one today). Retry honors thread interruption (checked per
attempt; STM is the interruptible layer — that is why it
lives in cohost, per the layering ruling).

### Shape of the work

- NEW `src/cohost/tx.cljc`: the STM layer (world, refs,
  transaction runner, alter/commute/ref-set/ensure/deref,
  history, validators/watches plumbing, the send-hold hook
  into cohost.thread, fill switch mirroring cohost.atom's).
- `cohost.thread`: one hook — dispatch consults the tx
  layer's hold before enqueueing (agents themselves are
  unit 1's, unchanged except this seam).
- `cohost.shim`: the Ref surface dispatches on layer refs
  (`-deref`, `-set`, `-alter`, `-commute`, `-touch`,
  history vars, `-addWatch`/`-removeWatch`/`-setValidator`/
  `-getValidator`/`-getWatches`, meta entries); `new-Ref`
  prefers the layer when the fill is in force, else the
  host's own ref where the host has one, else the layer
  (a ref-less host's STM is the layer — the atom fallback
  and the undo hack retire there); `LockingTransaction-
  runInTransaction`/`isRunning` route to the layer where
  the layer serves.
- `cohost.boot` + `cohost.policy`: boot opt `:eve-stm`
  (the :eve-atoms pattern: ref-surface names boot stock
  where a host native would bypass the shim) and
  `install-stm-layer!`; hosts whose core has no STM names
  at all get the core fill (core-impls for ref/dosync-side
  names the stock boot leaves uninterned — measured per
  seat, not assumed).
- Probe: `probe_tx.clj` (host-side layer probe, the
  probe_atom shape) + `eve_cohost_check_stm.clj` (booted-
  context check with the fill in force) + the brief's
  concurrency cases (two-ref transfer invariant under
  concurrent dosync load, commute ordering, abort leaves
  no partial writes, send-from-dosync exactly-once,
  agent error/recovery, await draining).
- Gates at the end, final tree: JVM battery 221/221 +
  libraries 62/62 (default boot AND fill boot), cljs
  219/221 + 62/62, jank boot:jank 219/221 + 62/62 with the
  new shim code in the AOT compile.

Seats plan: JVM first (reference), then cljs, then jank —
each as far as the leaves honestly go; remainders named
with evidence, never stubbed over.

## STATUS 1 — 2026-11-05 ~7:34 PM ET — interruptibility design (operator input banked)

Restart of the lane (first session died in a daemon restart;
picked up from this file + the scratch probes in dev/stm/).
Operator input, this evening, verbatim in substance: he is
curious how this lane solves interruptibility, and his
recollection of the JVM reference is that Clojure hacked
interruptibility in through the EXCEPTION system — "which
you're not supposed to do, but he did anyway." The
recollection is correct, and this design follows the same
precedent, in cohost's layer only. Nothing in this section
puts machinery into Eve's tree.

### How the JVM reference actually does it (measured + source)

- **Retry is an exception.** `clojure.lang.LockingTransaction`
  signals a failed attempt by throwing a dedicated singleton
  exception (`RetryEx`) up through the dosync body; only the
  transaction runner catches it, and it loops the body
  against a fresh snapshot. User code never sees it (unless
  it catches Throwable broadly — a documented stock hazard
  this design inherits and names, see below). Retry is not
  a return code and not a status flag: it unwinds arbitrary
  user frames, which is exactly why it has to be an
  exception.
- **Interruption is an exception too.** Blocking points
  (future deref, agent `await` on its latch, `Thread/sleep`)
  surface cancellation as `InterruptedException` when
  `Thread.interrupt` lands; the interrupt itself is
  cooperative — it stops nothing that isn't blocked or
  polling the flag. Stock STM's retry loop is bounded by
  contention, not by a timeout; interruption is how a
  thread parked inside STM-cooperating code gets out.

### 1. The retry signal on jank: a cohost-layer exception type

This layer does the same thing, with cohost's own type:

- `cohost.tx` defines ONE dedicated retry exception value
  type (a layer exception, in the `cohost.thread` layer
  exception family), with a single shared instance. A
  failed commit validation throws it; ONLY the transaction
  runner in `cohost.tx` catches it (by identity/type check
  in the runner's catch, rethrowing everything else). It
  is never exposed as a var user code can catch by name;
  a user `catch Throwable`/`catch Exception` around a
  dosync body can swallow it exactly as on the JVM — named
  here as inherited stock behavior, not a new defect.
- **Viability on jank is probed, not assumed.** Open
  question: does a throw of a layer exception cross
  jank-compiled frames (nested fn calls, `letfn` cycles,
  calls through the shim's filled core fns) and land in
  the runner's catch, in both JIT (boot context) and AOT
  (shim compile) modes? The probe (`probe_tx.clj` jank
  leg) throws the retry type from depth ≥3 of compiled
  frames and from inside a callback invoked by layer code,
  and times the throw/catch path.
- **Cost, named in advance:** jank's throw allocates an
  error object and unwinds via the host exception path —
  not free. The cost lands ONLY on contended retries: an
  uncontended commit never throws (validation passes, CAS
  wins, done). Retry frequency is contention frequency;
  the probe reports throws/sec so the number is on record.
  If the measured cost is pathological, the mitigation is
  fewer retries (bounded spin/backoff in the runner before
  the throw-driven re-run), NOT a different signal —
  unwinding user frames has no second mechanism.
- **Fallback if a throw cannot cross some frame class:**
  if the probe finds a frame class the exception cannot
  cross (the candidates: host/C++ callback frames, Eve
  thread-task boundaries), the runner degrades for bodies
  that pass through such frames to a **flag-checked
  cooperative retry**: a per-attempt retry-request flag on
  the attempt record, set by the ref ops, checked at every
  ref-op boundary and at body exit; a set flag discards
  the attempt's writes and re-runs the body instead of
  unwinding it. Named semantic difference: the doomed
  attempt runs to its end rather than unwinding at the
  conflict point, so body side effects after the conflict
  point execute in the doomed attempt too (stock dosync
  already re-runs bodies; this widens one attempt's
  execution, it does not add executions). Any seat/frame
  class on this fallback is named in STATUS with probe
  evidence — never silently.

### 2. Interrupts proper — closing unit 1's remainder, cooperatively

Unit 1 banked the remainder: on Eve's thread layer behind
the shim, `future-cancel` and `Thread.interrupt` don't
interrupt yet. This lane's agent story depends on the
answer, so it is stated plainly:

- `await` / `await-for` do NOT need target-thread
  interruption: they are timed waits on the WAITER's side
  (latch with timeout). `await-for` returning false on
  timeout, with the agent action still queued/running, is
  stock semantics and works on every seat as-is.
- Cancelling or interrupting RUNNING work does need it.
  This lane closes the unit-1 remainder **in cohost's
  thread layer, cooperatively** — which is also all the
  JVM ever does: a per-task cancel flag on layer tasks;
  `future-cancel` / agent shutdown set the flag and wake
  the task's waiters; every blocking leaf in the layer
  (latch await, queue take, sleep, agent await) waits in
  bounded slices and, on seeing its task's flag, throws
  the layer's interrupt exception. Pure compute loops are
  not interrupted — identical to the JVM, where interrupt
  surfaces only at blocking points or explicit polls. The
  tx runner checks the flag once per attempt: an
  interrupted thread abandons the transaction with the
  interrupt exception instead of retrying forever.
- What is NOT claimed: preemption. No seat gets the power
  to stop a thread mid-computation, and no doc in this
  lane will say "cancel stops the action" without the
  words "at its next blocking point."

### 3. Seats where interruptibility cannot be provided

- If a seat's thread layer cannot carry even the
  cooperative flag (no tasks, no blocking leaves — the
  single-realm case), STM still installs, with the seat's
  STATUS entry stating: retry is uninterruptible on this
  seat (a contended transaction retries until contention
  resolves; `await-for` timeouts still bound agent waits,
  since those are waiter-side). The capability is a named
  per-seat fact in the probe output (`:stm-interruptible?`),
  checked by the booted-context gate — a seat flipping
  either way changes a measured row, not a doc claim.
- Layering, restated: the retry exception type, the cancel
  flags, and the interrupt exception all live in
  `cohost.tx` / `cohost.thread`. Eve gets nothing; Eve
  hosts that cannot interrupt lose nothing, because STM
  was never theirs to carry.

## STATUS 2 — 2026-11-05 ~8:2x PM ET — REDESIGN PICKUP (replacement lane)

The previous agent banked the world-atom implementation +
probe (23/23 on JVM) and then went silent ~27 min after the
operator's redesign ruling landed; the coordinator closed
it as dead and re-dispatched. This lane picks up cold from
git. Everything banked survives; nothing is redone that the
ruling did not order redone.

My reading of the state, from source (not from summaries):

- `src/cohost/tx.cljc` (404 lines) implements the REJECTED
  construction: all ref cells in one map inside one atom
  per "world"; commit = validate + one CAS on that atom.
  The probe suite (`probe_tx.clj`, 23 checks) pins the
  SEMANTICS (snapshot stability, skew bare vs ensure,
  commute folding, transfer invariant, validators, watches,
  history, exactly-once sends, retry cost, cooperative
  interrupt) and stays the bar for the rework.
- One gap the earlier statuses did not name: the design
  text (STATUS 0) says the world atom "is a unit-5 layer
  atom where the seat's Eve hosting is installed", but the
  code holds cells in a plain host atom and the probe never
  installs the Eve hosting (`cohost.atom-eve/install!` is
  nowhere in the probe or the booted STM check) — so "refs
  ride on an Eve atom" was never exercised by this lane's
  own evidence. The redesign closes this too: the new
  probes install the hosting and count domains.
- Eve's domain facts (source: eve.atom, cohost.atom-eve):
  a domain is Eve's contention unit — own slab, root,
  epoch, arbitration. `eve.atom/atom-domain {}` mints a
  heap domain on the JVM seat. Cohost's atom hosting
  deliberately SHARDS program atoms across shared domains
  (255 slots each) — the exact sharing the ruling forbids
  for refs. Domain-per-ref therefore needs a new factory
  capability in cohost.atom, not a reuse of `:make-atom`.

The ruling this lane works under (John, tonight):

1. The world-atom construction is rejected: real refs,
   separate contention domains — per-ref versioned
   history, per-ref read points, per-ref commit
   validation; disjoint transactions never contend.
2. Separate domains means separate ATOM DOMAINS: per-ref
   atoms inside a shared Eve domain do not count. A ref
   is an atom in its OWN domain. Build domain-per-ref, or
   measure its cost and report with evidence. No silent
   shared-domain fallback.
3. The Eve-level STM question is live and gets scoped in
   the design (portable parts / minimal host contract /
   per-target table). Cohost-on-jank still goes first, in
   the portable shape. JVM is the semantic reference seat.

Plan, in the brief's order: design rewrite (next STATUS);
domain-per-ref cost measurement on the JVM seat with
evidence; Eve-level scoping section; tx rework
(per-ref/per-domain cells, Clojure's mechanism — read
point + per-ref validation + per-ref commit locks, the
LockingTransaction shape, not a container snapshot);
verification = the 23-check probe unchanged in its checks
plus the two new probes (disjoint transactions commit
with zero retries; a contended pair retries scoped to the
conflicting ref only).

## STATUS 3 — 2026-11-05 ~8:5x PM ET — design rewrite: per-ref, per-domain STM

This section REPLACES the STATUS 0 consistency construction.
The world atom is gone. What follows is Clojure's own
mechanism, re-based on Eve's facilities: MVCC over per-ref
versioned history, a transaction read point, per-ref commit
validation, and per-ref commit locks — the LockingTransaction
shape, with no container snapshot anywhere.

### Where a ref lives

A ref is a layer value `{:cohost/kind ::ref :cell … :state …}`:

- `:cell` — the ref's committed state, an atom holding ONE
  immutable cell map `{:value v :point p :version n
  :history [{:point :value} …]}` (history newest-first,
  bounded by the ref's max-history, never below
  min-history). The cell atom is the ref's alone:
  - where the seat's Eve hosting is installed, the cell is
    a cohost.atom layer atom minted by a NEW factory
    capability, `:make-domain-atom`, whose Eve handle is
    the sole atom of a FRESH Eve atom domain
    (`eve.atom/atom-domain {}` + one registered atom). The
    domain is the ref's contention domain: its own slab,
    root, epoch and arbitration point, shared with no
    other ref. If the hosting is installed but cannot mint
    a domain atom, creation throws — there is no path from
    make-ref to a shared-domain atom (the no-silent-
    fallback guarantee, enforced in cohost.atom, not by
    convention).
  - where no Eve hosting exists on the seat, the cell is
    a plain host atom — which is already a contention
    domain of one (a host atom arbitrates nothing but
    itself), so the construction's guarantee is the same
    statement in the seat's own terms. Which residence a
    ref got is a measured fact the probes print
    (`:cell-residence`), never a doc claim.
  A cell whose value Eve cannot represent seats in the
  wrapper's host seat by cohost.atom's existing gate
  machinery (one-way, named in the state) — semantics
  preserved, residence degraded per-ref, exactly as
  unit 5's atoms behave.
- `:state` — a host atom with the ref's non-transactional
  facts (validator, watches, meta, history bounds) AND
  its commit lock (`:lock`, nil or the owning attempt's
  token). The lock lives here, not in the cell, because
  stock keeps its locks in the Ref object, not in the
  value: lock traffic never publishes to Eve.

There is no world, no domain registry of refs, and no
map of cells anywhere in the layer. Refs of one program
share exactly one thing: the commit-point counter below.

### The transaction mechanism (stock's, step for step)

- **Commit points.** One layer atom holds a monotonic
  long: the last commit point. It carries no ref data;
  it is the point clock stock keeps as LockingTransaction's
  global lastPoint. A commit claims its point by one
  increment, after validation, before installing. Points
  are only an ordering; aborted attempts consume none.
- **Read point.** An attempt fixes its read point `rp`
  lazily at its first ref touch: `rp` = the current commit
  point. Every value the attempt reads is the value as of
  `rp`: for each ref, the newest cell entry with
  `point ≤ rp` (the live value if the cell's point ≤ rp,
  else a history entry; if history no longer reaches `rp`,
  the attempt faults and retries with a fresh point —
  stock's "history too short" answer). An in-flight
  attempt's view cannot move, because committed history is
  immutable and trimming never removes the newest entry.
- **Reads and writes in the attempt.** The attempt record
  (a per-scope local atom, dynamically bound — unchanged
  from the previous design) records per touched ref: the
  base point (the point of the value it read), the ordered
  action list (:set / :commute), and the ensured flag.
  Reads serve from the attempt's view (base value folded
  with its own actions), as before.
- **Commit.** (1) Lock every written ref, in ref order
  (creation sequence — deterministic, so no lock-order
  cycles), by CAS on the ref's own `:state`. A lock held
  by another attempt: release all, throw the retry signal
  (try-lock + retry, not blocking locks; the named
  difference from stock is no barging — liveness rests on
  the retry loop and the small contention windows, and
  the concurrency probes are the evidence it suffices).
  (2) Validate: every ensured or written ref's base must
  still be its value at `rp`, and every READ-only ref must
  show no commit with point > `rp` (its cell point must
  still be ≤ `rp` with the same base) — a ref that moved
  after `rp` retries the attempt, stock's read validation.
  Commute-only refs are not validated. (3) Run validators
  against final values. (4) Claim the commit point.
  (5) Install each written ref with one CAS on its own
  cell atom: written (alter/ref-set) refs fold their
  actions over the value at `rp`; commute-only refs fold
  over the cell's CURRENT value, in call order, under
  their lock. The new cell prepends the replaced state to
  history and bumps the ref's version. (6) Unlock, fire
  watches once per written ref with the pre-commit and
  final values, release held agent sends.
- **Why disjoint transactions cannot contend.** Two
  attempts touching disjoint ref sets lock disjoint
  `:state` atoms, validate against disjoint cells, and CAS
  disjoint cell atoms. The only shared write is the
  commit-point increment — one atomic increment per
  commit, stock's own global, which carries no ref's
  data and validates nothing. It cannot make an attempt
  retry: retries come only from a touched ref's lock or
  validation. The new probe (STATUS 6) measures exactly
  this: attempts counted, retries = 0 across concurrent
  disjoint transactions.
- **Atomicity and visibility.** Installation is per-ref
  under per-ref locks, as stock's is; a concurrent reader
  with read point < the commit point reads pre-commit
  history (immutable), a reader with a later point reads
  the new cells. The commit point is claimed before
  installation begins — the same order stock assigns its
  commit point before setting tvals — so the construction
  inherits the reference mechanism's visibility edges
  rather than inventing new ones.
- **Retry and interruption** are unchanged from STATUS 1:
  the layer's shared retry exception, caught only by the
  runner; the thread layer's cooperative interrupt checked
  once per attempt; held sends released only by the
  successful attempt (exactly-once).

### What changed relative to the rejected design

Snapshot-by-container → snapshot-by-read-point over
per-ref histories. One CAS on one atom → per-ref locks +
per-ref CAS on per-ref cell atoms in per-ref domains.
Cross-ref consistency no longer comes from a single deref;
it comes from the read point + validation, which is where
stock gets it. The price, named: a transaction's reads are
one cell deref per ref (not one per world), commits are
O(written refs) Eve publishes (not O(1)), and consistent
reads of moved refs depend on bounded per-ref history —
the STM cost model every MVCC has, now honestly per-ref.

## STATUS 4 — 2026-11-05 ~9:0x PM ET — domain-per-ref cost, measured (JVM seat)

Question (ruling 2): what does an atom in its OWN Eve atom
domain cost per ref? Measured against the sibling Eve
checkout this worktree's deps name (`../eve`, JVM heap
domains, `eve.atom/atom-domain {}`), scratch probes in
dev/stm/scratch-domains*.clj; the box was loaded (load
~4–6, 4 cores, other lanes active) — creation TIME below
carries that taint and is reported as an upper bound; the
memory figure is structural and load-independent.

- **Memory: ≈ 6.8 MB of eager heap per domain.** 120 fresh
  domains grew used heap by 812 MB (GC'd, steady). Source
  agrees: a JVM heap domain eagerly allocates 6 bitmap
  slab regions at 1 MiB default capacity, a 256 KiB reader
  map, a 64 KiB coalescing region and the root region —
  before any atom exists. Corroborated from the other
  side: minting ~220 domains OOM'd a default-heap JVM at
  eve.mem/make-heap-region. On this seat a heap domain's
  regions are heap byte arrays, so a domain's memory is
  reclaimable when the ref dies with it; the cost is per
  LIVE ref.
- **Creation time: ~110–370 ms per domain** on the loaded
  box (median ~160 ms; a host ref is ~µs). Even discounting
  the load generously this is a 10⁴–10⁵× ref-creation
  cost. A program minting refs casually (a ref per entity,
  10³–10⁴ refs) pays seconds-to-minutes and gigabytes.
- **Per-op cost: unchanged by geometry.** In the same
  runs, deref/swap! on a sole-domain atom and on an atom
  in a shared domain were indistinguishable (both ride the
  same eve.atom mmap path; tonight both were slow in
  absolute terms — ~0.3–0.9 ms deref under load vs 0.38 µs
  for a host atom in the same JVM — a property of Eve's
  JVM path on this checkout + box, NOT of domain geometry;
  it taxes shared-domain atoms identically).
- **The capacities lever, named and NOT taken:** Eve's
  private domain opener accepts per-class slab capacities,
  but the public `atom-domain` discards its opts on the
  JVM seat, and bitmap slab classes are fixed at creation
  (only the coalescing class grows) — a shrunk domain
  risks allocation failure under a large ref value.
  Threading capacities through Eve's public API is
  Eve-side work, outside cohost's layer and this lane's
  mandate. Estimated floor if it were done: ~0.7–1 MB per
  domain (reader map + root + minimal slabs) — still
  10³–10⁴× a host ref.

**Finding, for the operator:** domain-per-ref is built
(this lane, STATUS 5) and the semantics verify against it,
but at Eve's current domain geometry it costs ≈6.8 MB and
≈10² ms per ref on the JVM seat. That is a sound price for
the coarse-grained refs STM is idiomatically used with
(dozens), and prohibitive as a universal default (10³+
refs). The construction in cohost does NOT silently shard
refs into shared domains to dodge this: where Eve hosting
serves, a ref is a domain; the cost is reported here, and
the geometry fix, if wanted, is an Eve-side decision
(small-domain capacities), not a cohost fallback.

## STATUS 5 — 2026-11-05 ~9:0x PM ET — the Eve-level question, scoped

Ruling 3: IF refs are separate-domain atoms AND cooperative
interruption can be built between arbitrary threads of an
arbitrary host, STM could live IN EVE across its targets.
Scoped against Eve's actual surface (this checkout):

### What is already portable (would move into Eve unchanged)

- The MVCC core as now designed: per-ref cell maps
  ({:value :point :version :history}), the read-point /
  validation / commit-point protocol, commute folding,
  ensure, bounded history. It is written against three
  cell operations — deref, CAS, and "an atom in its own
  domain" — and Eve has all three on every seat Eve runs
  on: Eve atoms ARE the substrate (eve.atom on JVM/CLJS;
  the same domain/atom model is Eve's own cross-host
  design). Nothing in the protocol names a host type.
- The send-hold seam shape: "actions dispatched inside a
  transaction fire only on commit" needs only an
  enqueue-with-hold callback from the host's task system.
- Ref metadata state (validators/watches/meta) as data
  beside the cell — pure data, portable. (Validators and
  watches are host FUNCTIONS: they stay host-resident
  values held beside the cell, as cohost.atom's wrapper
  already holds unrepresentables; the protocol only calls
  them.)

### The minimal host contract (what Eve would have to require)

1. **Cooperative interrupt points.** A per-task cancel
   flag the host's blocking leaves ( waits, sleeps, queue
   takes) poll in bounded slices, surfacing cancellation
   as a throwable the transaction runner can distinguish.
   Plus one poll per transaction attempt by the runner
   itself. This is the ONLY hard requirement, and it is
   the layering ruling's original reason STM sits in
   cohost: not every Eve host can promise it.
2. **A retry signal that unwinds user frames** — any
   host exception/throw mechanism (universal in Eve's
   targets: JVM/CLJS/jank all throw). A flag-checked
   degradation (STATUS 1) covers frame classes a throw
   cannot cross; an Eve-level STM would carry the same
   named fallback.
3. **Thread/task identity + a dynamic-scope binding** for
   the attempt record (thread-local or equivalent). Eve's
   mesh tasks already carry task identity; CLJS's single
   realm carries it trivially (and serializes attempts by
   construction).
4. **Domain-per-ref atoms at viable geometry** — the
   STATUS 4 finding is the real gate: an Eve-level STM
   inherits the ~6.8 MB/ref geometry unless Eve grows a
   small-domain profile. Portable protocol, heavyweight
   substrate: this, not interruptibility, is today's
   binding constraint.

### Per-target table (Eve's hosts, as this project knows them)

| target | own-domain atoms | interrupt points | attempt scope | history reclamation (STATUS 9) | verdict |
|---|---|---|---|---|---|
| JVM (Eve on JVM) | yes (heap domains) | yes (thread interrupt + cohost thread layer) | thread-local | domain retire-diff + epoch-gated flush on the write path; materialized copies GC'd; subject to R-RECLAIM | ready modulo geometry |
| jank (Eve on jank) | yes (Eve's domain model) | cooperative via cohost thread layer (this lane's STATUS 1) | thread-local | the same :clj-family retire machinery, compiled native; subject to R-RECLAIM | ready modulo geometry + the throw-crossing probe (STATUS 1) |
| CLJS / Node | yes (SAB domains) | cooperative only within the realm's task system; no preemption | realm/task-local | CLJS retire path (retire queue + epoch-gated flush in the SAB domain) | protocol fits; interrupt degrades to poll-at-attempt-boundary |
| CLJS / browser | yes (SAB domains) | same, weaker (worker messaging) | task-local | same, in the worker's SAB domain | same, named weaker |
| WASM / VS seat | Eve domains in the seat's model | isolates cannot be interrupted (operator's standing fact; hard stop = restart the isolate) | task-local | Eve's slab IS the memory — no host GC beneath to catch a miss; the domain retire path is the ONLY free; exposure is exactly R-RECLAIM, compounded by the seat's restart-only remedy | retry works; interruption is isolate-restart granularity — an Eve-level STM must name this seat :stm-interruptible? false |
| basilisk/basilisp, phel, other shim hosts | n/a (no Eve hosting) | host-dependent | host thread-local | host cells only: the host's GC where it has one; a non-GC host without Eve hosting has NO reclamation story in this layer (no domain, no retire path) — stated, not assumed | out of Eve's reach by definition — cohost's layer remains their STM |

### The scoping conclusion

An Eve-level STM is a protocol relocation, not a rewrite:
the MVCC core + cell contract move; the host contract is
four items, of which interrupt points are the only one
that can fail per-target, and geometry (STATUS 4) is the
only one that fails TODAY on every target. Cohost's build
therefore goes first in exactly the shape Eve would need —
per-domain ref atoms behind a three-op cell contract,
interruption as a named per-seat capability
(`:stm-interruptible?`) — so the relocation, if ordered,
is a move of cohost.tx's core into Eve plus a per-target
capability table, not a second design.

## STATUS 6 — 2026-11-05 ~9:2x PM ET — verification in flight

- probe_tx (the 23-check semantics suite, host-atom cells):
  **ALL 23 CHECKS PASS** over the reworked construction
  (log dev/stm/logs/probe-tx-redesign.log). Transfer bench
  took 2658 attempts for 2000 transactions (retries real,
  scoped, and the invariant held); the forced-retry bench's
  phantom-commit conflict retries via the exception as
  designed. The ONE adaptation, named in STATUS 2's plan
  and in the probe's own comment: the forced-retry bench
  manufactures its conflict with a public-API phantom
  commit instead of poking the deleted world map. Every
  check line is unchanged.
- eve_cohost_check_stm (booted-context check): re-running
  detached; the boot itself (full stock core through the
  shim) is slow on this loaded box (load ~4–6) and a first
  foreground attempt hit the runner's timeout before the
  boot finished printing. Not a failure signal; the result
  lands in STATUS 7 with its log.
- probe_tx_domains (new; residence + zero-retry disjoint +
  scoped-retry over real Eve domains) runs next, then
  probe_tx again with the Eve hosting installed (the 23
  checks over domain cells).

## STATUS 7 — 2026-11-05 ~9:5x PM ET — the ruling's probes PASS over Eve domain cells; booted check 12/22 → 20/22

- **probe_tx_domains: ALL 15 CHECKS PASS** (log
  `dev/stm/logs/probe-tx-domains.log`). The probe installs an Eve
  factory first, so refs are real domain cells, and verifies with
  evidence read off the handles themselves: 10 refs → the factory
  minted exactly 10 domains and the refs' Eve handles sit in 10
  pairwise-distinct domain states; a two-ref transaction commits
  across domains; a fn-valued ref seats its cell value in the
  wrapper's host seat (gate `:host`) and transacts. **Disjoint
  transactions: 800 attempts, 800 commits, ZERO retries** (8 refs ×
  100 alter-inc on 8 threads). **Scoped retries:** with ref P hammered
  by an aggressor (400 transactions) and a victim doing 20 P+Q
  transactions, the victim retried 171 times while a bystander doing
  400 Q-only transactions retried **2** times — bounded by Q's own
  conflicts with the victim's 20 commits, against 800+ total commits.
  Under the rejected world-atom construction the bystander would have
  retried on commits anywhere. make-ref over Eve domains measured
  407 ms/ref in this run (box loaded; consistent with STATUS 4).
- **The factory is installed probe-locally** — the same two Eve calls
  `cohost.atom-eve`'s `:make-domain-atom` makes — because
  **cohost.atom-eve does not compile against the current Eve
  checkout**: its unit-7 `root-digests` references `eve.atom/seal` and
  resolves `eve.digest/compute-digest`, and neither exists in Eve at
  the sibling checkout's current state (no `seal`, no `eve.digest`
  namespace). That drift predates this lane (the compile error sits
  in unit-7 code this lane never touched) and is filed here as a
  **remainder for the mainline**: until it is repaired, no probe on
  this box can load `cohost.atom-eve`. This lane's `atom_eve.clj`
  `:make-domain-atom` implementation stands as written (it uses only
  `atom-domain` + `lookup-or-create-mmap-atom!`, both present) but is
  itself unverified-by-load for exactly this reason; the path under
  test here — `cohost.atom/make-domain-atom` → the seat's installed
  `:make-domain-atom` — is exercised fully.
- **Booted-context check: 12/22 → 20/22** (logs
  `dev/stm/logs/check-stm-redesign.log` before,
  `check-stm-redesign2.log` after). The 10 failures were all one
  mechanism, **pre-existing and construction-independent**: the dead
  lane's own scratch log (`dev/stm/logs/check-stm-1.log`, against the
  world-atom build) shows the identical 12/22 with the identical
  failure set — the dead lane's "23/23" headline never mentioned this
  check. Diagnosis from the shim source: every Ref METHOD fn
  (`-deref`, `-set`, `-alter`, `-commute`, …) already dispatched on
  `tx-layer/ref-value?`, but the shim's *classification* layer did
  not — `class-of` had no layer-ref case (a layer ref fell through to
  the map branch and was named `clojure.lang.PersistentArrayMap`,
  literally the class in every cast error), `token-ancestors` had no
  `clojure.lang.Ref` entry, and the `IDeref`/`IRef`/`IReference`
  class-token predicates omitted refs (so booted `deref` routed a ref
  down the future path). **Fix** (shim only, mirroring the unit-5
  layer-atom precedents at the same sites): a `class-of` ref branch,
  a `token-ancestors` Ref entry, and refs added to the three
  predicates. After it, `ref + deref`, `dosync alter`, `ref-set`,
  `commute`, `sync runs`, abort-rollback, snapshot, and
  validator-left-the-value all pass.
- **The remaining 2 booted-check rows are a different, second
  pre-existing mechanism**, filed as a remainder: `ref-history-count`
  and `ref meta` fail with genuine JVM checkcasts
  (`PersistentArrayMap cannot be cast to clojure.lang.Ref` /
  `…IReference`) — in the pre-redesign log these two rows already
  failed with these same cast targets while the other eight wore the
  Future signature. They are the two core fns whose bodies make
  *hinted interop method calls* (`.getHistoryCount` on a `^Ref`,
  `.getMeta` on an `^IReference`): somewhere in the rewrite/interop
  path those calls reach a host checkcast instead of the shim's Ref
  method vars (`-getHistoryCount` exists and is correct; meta has a
  shim path via `meta-of`). Fixing that dispatch is rewrite-layer work
  with battery implications beyond this lane's seam; the trail above
  is the diagnosis a follow-up needs. Everything else in the check —
  including all agent rows — passes.
- **Version reclamation (operator note, Nov 5 ~8:55 PM, relayed
  mid-lane): accepted as a design gap in this lane's construction.**
  On hosts without a GC (VS and the other non-GC Eve targets), merely
  unlinking a trimmed history version would leak slab storage under
  steady writes — and this implementation trims by dropping entries
  from an immutable cell map, i.e. exactly the unlinking shape.
  What exists toward it in Eve today, from the substrate source: a
  domain's slab retires freed blocks through its epoch gate — blocks
  return only when no reader can still see them, which is the
  substrate-side half an MVCC trim needs (a version's block may be
  retired once no live read point can reach it). What does NOT exist:
  (a) any path in this lane that hands a trimmed version's storage
  back (the JVM seat's GC makes the omission invisible here — the
  cell map's dropped entries are ordinary garbage); (b) a
  read-point watermark in the transaction layer: commit already knows
  the oldest live read point in principle (an attempt's read point is
  fixed at first touch and attempts are short), but nothing tracks
  the minimum over running attempts, which is the input a trim would
  need before retiring anything past `max-history`. Design for the
  Eve-level build, recorded in STATUS 5's terms: trim keeps
  `max-history` entries AND every version at or after the oldest live
  read point; retirement is an explicit per-version release at trim
  time (on an Eve seat: the version's root goes to the domain's retire
  queue; on a host seat: deref-and-drop). On the JVM seat this is a
  no-op by construction. Not implemented in this lane: the lane's
  build targets cohost-on-jank semantics with the JVM as reference,
  and the mechanism only bites on seats this lane does not run — but
  it is now a named requirement of the construction, not a silent
  property of the GC. (Taken up in full as STATUS 9: the design
  section, the per-target reclamation column in STATUS 5, the
  layer-retention audit, and a checked invariant.)
- probe_tx over Eve domain cells (the 23 semantics checks with the
  factory installed) is running detached; its result lands in the
  final STATUS.

## STATUS 8 — 2026-11-05 ~10:3x PM ET — FINAL: the Eve-cells run caught a real bug; fixed; the substrate's write ceiling measured

Running the semantics suite over real Eve domain cells earned its
keep twice over:

- **A real bug in this lane's construction, caught only by the slow
  seat.** First Eve-cells run: `ensure prevents the skew` FAILED
  (both transactions committed). Diagnosis: stock's `ensure`
  *write-locks* the ref for the rest of the transaction
  (`LockingTransaction.ensure`) — that lock is what closes the
  validate/install race for a ref the attempt does not write. This
  lane's commit locked only the refs it installed into. Over host
  cells the race window is nanoseconds and the check passed by
  timing; over Eve cells the window is milliseconds and it failed
  deterministically. **Fixed**: commit's lock set is installs ∪
  ensured, still in creation order, still per-ref (disjoint
  transactions unaffected). After the fix, `ensure` passes on both
  seats by mechanism.
- **A measured substrate ceiling, not a semantics failure.** The
  high-volume benches over Eve cells lost writes at *identical*
  counts in both runs (commutes 1248/1600; transfers net 752) while
  the transfer invariant held in every run — atomicity never broke;
  worker threads were dying on `coalesc-alloc!: out of memory` thrown
  from Eve's slab writer. A dedicated scratch
  (`dev/stm/scratch-ceiling.clj`, raw Eve handle, no cohost code)
  measures it exactly: **a fresh domain sustains precisely 1,248
  installs of a cell-shaped map at default geometry — the commute
  bench's landed count to the digit — and after a 20 s quiescent
  pause it is still full**: no reclamation arrives in place under
  this usage. This is STATUS 4's geometry finding in operational
  form, and the measured evidence for the operator's reclamation
  note (STATUS 7): per-domain capacity + explicit version retirement
  are Eve-side work, and they gate domain-per-ref STM at production
  write volumes on every seat. Below the ceiling, everything passes.

### Final verification state (all post-fix, logs in `dev/stm/logs/`)

- probe_tx, host cells: **ALL 23 CHECKS PASS**
  (`probe-tx-final.log`) — the semantics suite, check lines
  unchanged.
- probe_tx_domains, Eve domain cells: **ALL 15 CHECKS PASS**
  (`probe-tx-domains-final.log`) — residence proven off the handles
  (10 refs, 10 distinct domain states); disjoint transactions
  800 commits / **0 retries**; scoped contention: victim 198 retries,
  bystander on an untouched ref **1** retry.
- probe_tx over Eve domain cells (`probe-tx-eve-cells2.log`):
  every semantic check passes, including ensure post-fix and the
  transfer invariant; the two volume benches stop at the measured
  1,248-install domain ceiling (writes throw past it; no wrong value
  is ever produced), and a later bench's main-thread write hit the
  same ceiling and ended that run early.
- Booted-context check: **20/22** (`check-stm-redesign2.log`), from
  12/22 — the classification fix is this lane's; the last 2 rows are
  the pre-existing hinted-interop gap named in STATUS 7.

### Remainders, named for their owners

1. **Eve-side (gates STM at volume, all seats):** domain geometry +
   version reclamation — a default-geometry domain costs ~6.8 MB and
   ~0.1–0.4 s to create and sustains ~1.2×10³ cell writes before
   refusing more, with no in-place recovery observed. Levers exist
   inside Eve (capacities threaded through the public opener;
   epoch-gated retirement driven by an oldest-live-read-point
   watermark, STATUS 7); both are Eve changes, outside this lane.
2. **Mainline (cohost):** `cohost.atom-eve` does not compile against
   the current Eve checkout (`eve.atom/seal`, `eve.digest` gone) —
   pre-existing drift in unit-7 code; until repaired, Eve hosting
   cannot be loaded on this box, and this lane's `atom_eve.clj`
   `:make-domain-atom` (same two Eve calls the probes exercised
   probe-locally) is unverified-by-load.
3. **Rewrite/interop (cohost):** hinted method calls on layer refs
   (`.getHistoryCount`, `.getMeta`) reach a host checkcast — the
   booted check's last 2 rows; diagnosis trail in STATUS 7.
4. **Jank seat:** the whole construction is portable-shape but was
   run here only on the JVM seat (the semantic reference); the
   throw-crossing probe from STATUS 1 and a jank run of both probes
   remain for the seat that owns jank time.

### Declaration

The redesign the operator ordered is built and verified as far as
this box and this lane's seams reach: real refs, one Eve atom domain
per ref, stock's MVCC mechanism (read point, per-ref validation,
per-ref locks — including ensure's), no world atom, no shared-domain
fallback (creation throws instead). The probe suite the semantics
were pinned by passes unchanged on host cells and — the two measured
substrate ceilings and the two named pre-existing gaps aside —
over real Eve domains. What blocks domain-per-ref STM in production
is no longer design or semantics; it is Eve's per-domain geometry
and version reclamation, measured above and filed to the Eve side.

## STATUS 9 — 2026-11-05 ~9:4x PM ET — history reclamation, designed (operator's addition; completes STATUS 7's note)

The operator's rule, stated as a design requirement: **a history
trim must RELEASE the trimmed versions, not merely unlist them.**
On the JVM the difference is invisible (unreachable = collected);
on a host without a GC — the VS seat above all — unlisted-but-
retained is a leak that runs forever. This section is the design:
who frees a trimmed history entry, and when, per host class; what
this layer does and deliberately does not do; and the one Eve-side
primitive the mechanism still needs.

### The two levels of "version" (do not conflate them)

1. **History entries inside a cell.** A commit prepends the
   superseded value to the cell's `:history` and bounds the vector
   to max-history (never below min-history). Trimming drops entries
   from the NEW cell.
2. **Superseded cells inside the domain.** Every install replaces
   the domain atom's root (the whole cell map). The old cell —
   including its longer history — becomes a superseded VALUE in the
   ref's own domain.

Each level has exactly one owner of its freeing, and neither owner
is a collector this layer builds.

### GC hosts (host cells: JVM, CLJS, any GC host without Eve hosting)

Freeing is reachability: a trimmed entry is freed when nothing
references it, by the host GC, at its own pace. Stated, not
belabored — but reachability is a property the LAYER must actually
produce, and the first version of this construction did not:

- **Caught by probe, fixed:** `trim-history` bounded the vector
  with `subvec`. A subvec shares the untrimmed vector's backing
  storage, so the "trimmed" entries stayed reachable through the
  cell itself — exactly one trimmed version pinned per ref at any
  time (the probe's alive-set was cellₙ ∪ cellₙ₋₁ to the entry).
  On the JVM: a bounded over-retention. On a non-GC seat: a slab
  tree pinned forever, per ref. The trim now rebuilds the kept
  prefix as a fresh vector (`vec`/`take`), which is also the
  correct SHAPE for the Eve level below: the new cell references
  only what it lists.
- **The layer-retention audit** (this layer's source, grepped for
  any second home for a version value): the cell is the ONLY
  version store. Attempt records hold `:base-value` per touched
  ref but are runner locals — created per attempt, dropped on
  retry (a fresh attempt replaces them) and on completion; they
  are in-flight state, not retention. Watch notifications are
  `[ref old new]` triples fired synchronously by the runner after
  the winning installs and dropped — never stored (a watch FN may
  retain what it is handed; that is the program's choice, as in
  stock). Held sends hold ACTIONS (agent, fn, args — user data),
  released on commit, dropped with the attempt on abort; never
  versions. The ref's `:state` holds validator/watches/meta/
  history bounds/a lock id — no values. The cohost.atom wrapper's
  state carries exactly one root — the current cell — at rest.
  `probe_tx_reclaim.clj` checks the consequence: nine sentinel
  values installed in sequence (max-history 3, a non-retaining
  watch attached so the watch path runs too), only WeakReferences
  kept — **the five trimmed sentinels clear under GC; the three
  retained history values and the current value survive. ALL 4
  CHECKS PASS** (log `dev/stm/logs/probe-tx-reclaim.log`; before
  the trim fix this probe failed on exactly the subvec retention).
  probe_tx is unchanged and still 23/23.

### Eve seats (any host language): the domain's retire path is the free

On an Eve seat there are two memories: the host's (materialized
copies — the host's GC story, above) and the domain's slab, which
NO host GC manages. Slab freeing is Eve's own machinery, and it
already has the right shape for STM — read from `eve.atom`'s
source at the sibling checkout:

- On every successful root transition, the seat collects the
  superseded value's **retire-diff** — the slab offsets of the
  nodes that changed between old and new value (per-type
  collectors in eve.map/vec/set/list; an alloc-log for values
  written from non-Eve types) — tags them with the new epoch, and
  enqueues them on the DOMAIN's retire queue.
- A flush on the write path frees queued offsets whose epoch is
  older than the minimum epoch any reader slot in the domain
  currently pins; reads pin their epoch for the read's duration,
  so a value being read cannot be freed under its reader. The
  flush is time-throttled (a scan at most every 50 ms, or when
  the queue passes 64 entries) — in the source's own words, "we
  only delay freeing, never free too early."
- Applied to STM: a commit's install IS a root transition, so the
  superseded cell — history and all — is retired by the same diff
  as any superseded atom value. **The layer therefore does
  nothing per trim beyond dropping the reference, and must not
  invent a per-entry free:** Eve values are persistent slab
  trees; there is no freeing a subtree out of the middle of one,
  and a cohost-side collector (side tables of dead versions, a
  journal of trims, a liveness scan) is exactly what the memory
  model forbids. Trimming is domain-local retirement because the
  history lives in the ref's own domain — the ruling's geometry
  is what makes the reclamation story this simple.
- STM attempts never pin slab storage: a transaction's read point
  is a logical commit point, served from the CURRENT cell's
  history vector — no old root is epoch-pinned by an attempt, so
  transactions never hold the epoch gate shut. (A value a
  transaction is actively folding over is a materialized host
  copy, under the host's story.)

### The gap that remains is Eve-side, and it is named, not worked around

The flush runs only AFTER a successful transition — the incoming
value is allocated first. Once a domain's slab is saturated, the
incoming allocation throws before any flush can run, no idle
driver exists, and the domain is write-bricked even though its
retire queue holds epoch-safe blocks: STATUS 8's ceiling,
measured — 1,248 cell installs at default geometry, still full
after a 20 s quiescent pause. Under a sustained write stream the
throttled, post-allocation flush cannot keep up with an STM ref's
commit rate even before saturation; at saturation it can never
run again at all.

**Eve-side requirement R-RECLAIM:** allocation in a domain must
be able to force an unthrottled flush of that domain's retire
queue before failing — flush-before-alloc, or flush-on-alloc-
failure with one retry — so epoch-safe storage is reclaimable
under write pressure and at the wall, not only in the aftermath
of successful writes. This joins STATUS 4's geometry item
(capacities through the public domain opener) as the second
Eve-side gate on an Eve-level STM; STATUS 5's table now carries
the per-target reclamation column, with the VS/WASM row stated
plainly: there the slab is the only memory, retirement is the
only free, and a wedged domain's remedy is the seat's remedy for
everything — restart the isolate. No cohost-side mechanism is
improvised for any of this; per the operator's instruction, the
requirement is filed to Eve.

### Declaration for this addition

The reclamation design is now explicit end to end: GC hosts free
by reachability, and this layer is now PROVEN (probe, not
inspection) to make trimmed versions unreachable — one real
retention defect (subvec sharing) found by that probe and fixed;
Eve seats free by the domain's epoch-gated retire path, which
the construction feeds by shape and obstructs in no way; and the
substrate's one missing primitive is named as R-RECLAIM with its
measurement, filed to the Eve side alongside the geometry
finding.
