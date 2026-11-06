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
