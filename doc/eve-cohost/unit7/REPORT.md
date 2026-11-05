# Eve-in-cohost, unit 7 — REPORT: tiered atoms (mem → disk → net)

Lane branch `agent1/eve-unit7-tiered-atoms`, off `muse/eve-cohost-impl` @ 0fa021f, with
unit 5 (`muse/eve-cohost-impl-u5` @ b142ffe) merged into the lane (unit 5 is complete but
was not in the mainline; see "Base" below). Spec: MEMORY-PLAN.md §4 unit 7 + §7 Q5 ruling
(kind = metadata). All times ET, 2026-11-05.

__GATE__

## What shipped

**Kinds as metadata on the atom value** (`src/cohost/atom.cljc`). The program writes stock
Clojure only:

```clojure
(def counter (atom {:n 0} :meta {:eve/kind :disk :eve/path "/var/app/db" :eve/name "app/counter"}))
(def remote  (atom {:n 0} :meta {:eve/kind :net :eve/endpoint "http://gw:8080" :eve/domain-id "d1"
                                 :eve/name "app/counter" :eve/api-key "k"}))
(atom 1)                                      ; :mem, the default, unit 5's runtime-domain atom
(alter-meta! a assoc :eve/kind :disk :eve/path p :eve/name n)   ; promotion, upward only
```

- `:mem` (no kind key) — unchanged unit-5 atoms in the runtime atom domain.
- `:disk` — Eve's file-backed (mmap) domain at `:eve/path`, the atom registered as `:eve/name`.
  A second process naming the same place joins it OS-resolved: Eve's exclusive create of
  `<path>.root` picks the one creating process, every other joins; no coordinator.
- `:net` — Eve's network atom (`eve.net.atom/remote-atom`), gateway/broker-resolved; its
  bottom tier is the gateway member's disk domain (Eve's model).
- Stock core's `(atom x :meta m)` is `(new Atom x)` then `reset-meta!`; a kind arriving on a
  never-published atom is therefore **create-or-join** (the target's existing root wins, as
  Eve's `lookup-or-create` does); on a published atom it is a promotion.

**Rules, enforced and probed:**

| Rule | Behavior | Error key |
|---|---|---|
| No automatic upgrade/fallback | a kind whose seat hosting is absent throws; nothing silently becomes `:mem` | `:kind-unavailable` |
| Unrepresentable root | fails loudly at creation, at every publish, at promotion, naming the value; never the host seat | `:unrepresentable` |
| Downgrade (net → disk, disk → mem, …) | refused | `:downgrade` |
| Re-pointing a tiered atom | refused (make a new atom) | `:re-point` |
| Promotion into an occupied target (published atom) | refused | `:promotion-target-occupied` |
| Missing place keys / unknown kind | refused | `:missing-place` / `:unknown-kind` |

Eve independently refuses a reference into a shared domain ("eve: a value with no eve bytes
(a fn, a regex, an atom, a host object, ...) is held by reference, and a reference never
reaches a shared domain") — two loud layers.

**Cross-process publishes ride Eve's CAS.** A tiered atom's swap/reset/CAS go through the
Eve handle's own CAS loop (the disk CAS word in the mapped root file; the broker CAS for net)
— a process-local claim cannot make a shared publish exclusive. Watches fire with the
committing attempt's exact `(old, new)` pair (every attempt overwrites the pair; the last is
the commit). CAS compares by seam equality (unit 3), as unit 5's does.

**Bindings conveyance across processes rides the shared atom** (metadata again, no new
function surface):

```clojure
(def shared (atom {} :meta {:eve/kind :disk :eve/path p :eve/name "app/s" :eve/conveys true}))
(def ^{:dynamic true :eve/convey shared} *tenant* nil)
;; instance A:  (binding [*tenant* "acme"] @(future ...))
;; instance B:  @(future *tenant*)  ;=> "acme"
```

A thread-boundary capture — core's `binding-conveyor-fn` via the shim's
`Var/getThreadBindings`, and the thread layer's futures and agent sends (one hook,
`cohost.thread/set-capture-hook!`) — publishes the frame it captured for vars conveyed
through a shared atom, and conveys frames other processes published there for vars the
thread has not bound itself. The frame lives in the shared atom's **Eve meta-atom**
(`eve.atom/meta-atom`: coordination state kept beside the atom, same domain): the atom's
own value, version and equality are untouched.

**JVM seat hosting** (`src/cohost/atom_eve.clj`): `:make-tier-atom` (disk/net),
`:frame-atom` (the meta-atom), `root-digests`, `close-disk-domain!`.

## Results, as printed

__RESULTS__

## Base, and the Eve line

- Unit 5 was not in the mainline at 0fa021f (units 2+3 only). It is merged into this lane
  only (bb68557); conflicts were doc-strings, the shim require, and boot's `:host-lacks`
  (unit 2's `core-lacks` ∪ unit 5's atom-surface names). No muse/* branch was touched.
- Units 3–5 were built against the standalone Eve line (`/home/hatch/src/eve` @ c7dc390, not
  reachable here). Unit 7 runs **both seats from one Eve tree** — the vendored eve-jank line,
  `johnmn3/atom.broker` @ `claude/eve-jank` 7ae05d49 (its `:jvm` and `:jank` roots) — so the
  file format is one format. One reference moved between lines
  (`eve.mem/value+sio->eve-bytes` → `eve.codec`); resolved from whichever is present.
- On this line unit 5's `probe_atom.clj` prints 66 checks, 3 failures: the three
  "transition to host seat" rows, because this Eve line holds a fn root in-process by
  reference (realm-scoped) instead of refusing it, so the host-seat transition never fires.
  Deref identity stays exact. An Eve-line delta, recorded; unit 7 does not depend on Eve's
  refusal.

## Named remainders

__REMAINDERS__
