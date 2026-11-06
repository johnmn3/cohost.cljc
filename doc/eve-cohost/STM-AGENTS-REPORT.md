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
