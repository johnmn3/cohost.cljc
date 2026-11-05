# Eve-in-cohost — HEAP SPIKE: the heap Clojure sees, on an Eve atom

> DECLARATION — spike in progress, banked in slices on branch
> `muse/eve-cohost-impl`. Skeleton + problem list banked
> 2026-11-05; verdicts and the supported shape fill in below.
> Exploration only — no implementation code. Every problem is
> stated with its evidence (probe output, file/line, or VS
> precedent) and a verdict: **real blocker** / **priced cost** /
> **non-problem**.

## 0. The direction under test

John (2026-11-05 ~09:45 ET; MEMORY-PLAN §8):

> The host heap that Clojure sees on cohost should be
> implemented on top of an Eve atom so that this discrepancy
> between types isn't there.

…followed by: "You're going to have to explore that and see
if there's any problems with that idea."

Under the ruling, the heap a cohosted program sees — its
collections, its runtime objects, its locals/analyzer/compiler
state (the Q5 ruling, MEMORY-PLAN §7: these live in the
**default runtime atom domain**, as in VS) — is not the host's
heap with Eve-backed exceptions behind the shim. The heap
itself is an Eve atom: one value world, Eve structures by
construction, no Zone H / Zone E discrepancy to bridge.

This spike's job is to find where that idea breaks, where it
costs, and where it is free — before any unit is designed
against it. It is the problem-list companion to MEMORY-PLAN
§8, not a re-plot of the plan.

## 1. Candidate problems (verdicts fill in as evidence lands)

### P1. Bootstrap — what creates the runtime atom domain?

Cohost's boot builds the shim/runtime (codemod, companions,
core fill) before any Eve atom could exist. Questions:
- What creates the runtime atom domain, and in what order
  relative to boot's existing steps?
- What lives outside the domain during boot (the boot code's
  own working state, the host core being read in)?
- Is there a chicken-and-egg that forces a **pre-domain
  stratum**, and if so, how big is it really — a handful of
  boot-internal values, or the whole boot?

Evidence: _pending — cohost boot order (src/cohost/boot*),
unit 1's registry precedent, VS boot precedent (§7 below)._

Verdict: _pending._

### P2. Identity & GC — Eve residents vs host objects in Eve clothing

The host runtime still allocates and GCs its own objects
(jank's host GC; the JVM seat's JVM). If the heap Clojure
sees is Eve-atom-backed:
- Which objects are true Eve residents, and which are host
  objects (closures, host interop objects) merely *held by*
  the domain?
- What happens to host-GC pressure (does the domain pin host
  objects forever? does Eve residence relieve or worsen it)?
- Host identity (`identical?`) — where does it survive, and
  where does the unit-1 tagged-map distance (equality ≠
  identity) generalize?
- Host interop that expects real host objects (a Java method
  wanting a `java.util.List`, jank C++ interop wanting a host
  value): where does the seam answer, and what does it cost?

Evidence: _pending._

Verdict: _pending._

### P3. Hot paths — where does the per-op cost land?

The crossing bench (muse/eve-cohost-xbench) priced the
shim-level swap!/deref hop at +3.1/+3.5 ns/op. But plain
collection ops (`conj`, `assoc`, `get`, seq steps) and
locals are far hotter than atom ops:
- Do collection ops on an Eve-atom heap pay a per-op domain
  cost, or only atom ops (swap!/deref on domain-crossing
  atoms) pay?
- Locals: in VS they live in the runtime atom domain — what
  does a local read/write actually compile to there (a deref
  per access, or a cached frame)?
- Is there a shape that makes the common case free — e.g.
  the domain as the *container* of immutable roots, with
  intra-domain reads being plain block reads?

Evidence: _pending — xbench numbers, VS runtime precedent,
Eve atom/collection shapes._

Verdict: _pending._

### P4. Per-host reality — which seats can host the heap today?

Eve is present as a hosted library on 6 of 8 hosts per the
research; PHP and Wasm have no row (the probe: phel never
reached cohost code, cljrs has no CLI row, basilisp's boot
fails in the CLI namespace before any surface).
- On which seats can the heap actually be Eve-backed today?
- What does the design do on a seat where Eve cannot be
  resident — host heap as today (a named distance), a
  counterpart in-memory domain, or the seat waits?

Evidence: _pending — probe REPORT.md host table, Eve host
state (eve/doc/hosts/*)._

Verdict: _pending._

### P5. Failure modes — the runtime mutating its own heap-atom

- Domain death/restart: what is lost, what is recoverable,
  and who notices?
- Contention: analyzer/compiler state and runtime registries
  swap inside the same domain programs read — does runtime
  traffic (compilation bursts) convoy program reads?
- Snapshot semantics: per-atom snapshots (model law) mean a
  program can observe the runtime mid-swap; where does that
  bite (a var root changing under a running fn is stock
  Clojure behavior — what is genuinely new here)?

Evidence: _pending._

Verdict: _pending._

### P6. The VS precedent — what does VS already do?

John's cited precedent (Q5 ruling): in VS, default atoms run
within the existing default **runtime atom domain**; locals,
analyzer state, and compiler state live in that domain,
visible across threads within it.
- How does VS's runtime actually structure this (program
  runtime, frames, the var-registry atom's history)?
- Does cohost's shape differ anywhere that matters (cohost
  boots *stock core.clj* — VS compiles its own core; cohost's
  analyzer is the host's, not cohost's)?

Evidence: _pending — vs/docs/memory-model.md, VS program
runtime sources._

Verdict: _pending._

## 2. The shape the exploration supports

_pending — filled in last, from the verdicts above._

## 3. Questions that are genuinely John's

_pending — only forks the evidence cannot settle._
