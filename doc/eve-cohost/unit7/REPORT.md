# Eve-in-cohost, unit 7 — REPORT: tiered atoms (mem → disk → net)

Lane branch `agent1/eve-unit7-tiered-atoms`, off `muse/eve-cohost-impl` @ 0fa021f, with
unit 5 (`muse/eve-cohost-impl-u5` @ b142ffe) merged into the lane (unit 5 is complete but
was not in the mainline; see "Base" below). Spec: MEMORY-PLAN.md §4 unit 7 + §7 Q5 ruling
(kind = metadata). All times ET, 2026-11-05.

## Gate — GREEN: `GATE-U7: 9 rows, 0 failures`

The two-process probe (`dev/u7/gate.sh`, run 2, 13:25:01–13:32:01 ET; logs in
`gate-logs/`): a **JVM process** (a cohost layer atom whose kind is chosen by metadata,
`{:eve/kind :disk :eve/path <db> :eve/name "u7/shared"}`, over Eve's JVM hosting) and a
**jank process** (Eve on jank as-is: the vendored eve-jank line's `:jank` roots — completion,
region/jank `MAP_SHARED` + `__atomic` + futex, native slab-io) over **one disk atom**:

```
OK   race: both sides printed
OK   race: final value exact (jvm side) {:jvm 200, :ready 2, :done 2, :jank 200, :marks [], :count 400}
OK   race: value equal from both sides
OK   race: seal identical from both processes [{:ptr 808, :name-hash -1780362285, :blocks 4, :digest -2079092502} {:ptr 808, :name-hash -1780362285, :blocks 4, :digest -2079092502}]
OK   race: sha256 content digest identical ["sha256:24c28ddd3f6ea34da8b1b8616400fd29f89e3dea1f42e54532041052716743d2 over 147 bytes" (same on both)]
OK   restart: jank rejoin sees the killed jvm's mark ["jvm-killed"]
OK   restart: jvm rejoin sees both killed processes' marks ["jvm-killed" "jank-killed"]
OK   restart: final root digests identical jvm vs jank [{:seal {:ptr 819, :name-hash -1780362285, :blocks 6, :digest -264389891}, :sha256 "sha256:fad590ea554b7b9f4ca131c3b118b58808e66a7253d2127a9b5b36b101a2715d over 184 bytes"} (same on both)]
OK   restart: final value equal jvm vs jank
GATE-U7: 9 rows, 0 failures
```

- Race: both processes meet at a barrier kept in the atom itself, then each makes 200 swaps
  (`:count` and its own side) concurrently; Eve's cross-process CAS loses none.
- Two digests, both Eve's own: **seal** = `eve.atom/seal` (root pointer, name hash, block
  count, FNV-1a over every block the value owns, offset and bytes — the stored version, byte
  for byte); **content** = SHA-256 over the root's canonical codec bytes under
  `*shared-write*` (eve.digest's Merkle identity, spelled with its public parts). The JVM's
  digest of the Eve map itself equals the digest of the materialized value (printed as
  `:sha256-eve-map`).
- Restart: the JVM process swaps a mark and is halted (`Runtime.halt`: no close, no slot
  release, no checkpoint); a fresh jank process rejoins and sees it; the jank process swaps a
  mark and `_exit`s; a fresh JVM process rejoins and sees both. Per-atom snapshot rule only —
  no cross-atom instant is claimed.
- Run 1 (13:21) printed the same race value and an identical seal on both sides
  (`{:ptr 808, … :digest -276256802}`); its jank content digest errored (below) and it was
  stopped before its restart legs.

**Which seat hosts what (stated plainly).** The JVM process runs cohost's layer (the kind
expressed as metadata, the unit's mechanism). The jank process runs Eve-on-jank directly:
cohost's own atom layer is not hosted on the jank seat — that is unit 6 (slab residence on
jank, hard-gated on the Eve-on-jank flagship, which has not declared). The jank seat of
cohost compiles the unit-7 layer in its AOT graph (battery below); the fill stays off there,
as in unit 5.

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

| Run | Printed | Where |
|---|---|---|
| Unit-7 layer probe, JVM, one process (`probe_tier.clj`) | **PROBE-TIER: 56 checks, 0 failures** | kinds, disk create/join/CAS/vals/watch/validator, every loud failure, promotion mem→disk and disk→net, fresh create-or-join, net over Eve's gateway in-process, digests |
| Booted core, JVM (`eve_cohost_check_u7.clj`; boot 501 stock / 172 host / 61 natives / 88 skipped / errors 0) | **CHECK-U7: 17 checks, 0 failures** | `(atom x :meta {...})`, swap!/reset!/compare-and-set!/swap-vals!/reset-vals!, watches, validators, join, loud failures, `alter-meta!` promotion, conveyance through core's `future` |
| Conveyance, two JVM processes (`dev/u7/convey.clj`) | A `{:future-saw ["acme" :a-side]}`; B `{:future-saw ["acme" :plain], :own-binding-wins "b-own"}`; refusal `:unrepresentable` | B's future sees A's conveyed binding; a non-conveyed var stays local; B's own binding wins |
| Net kind, separate JVM processes (`dev/u7/net_proc.clj`; store + gateway on `eve.http-server` sockets, gateway a member of its disk domain) | net client (50 swaps) + disk member of the gateway's domain (50 swaps) → `{:disk 50, :net 50, :count 100}`, read back by a fresh net client; gateway `{:requests 273, :commits 50, :conflicts 1, :by-atom {"app/shared" 108}}` | the disk member's swaps landed before the client's started (its own view `{:disk 50, :net 0, :count 50}`): exact, not interleaved in this run |
| **The gate** (above) | **GATE-U7: 9 rows, 0 failures** | JVM + jank, one disk atom |
| JVM battery (`bb boot:jvm`, default config) | booted as stock 465 / from host 202 / natives 66 / skipped 58 / errors 0; **battery: 221/221; libraries: 62/62** | the shim/thread changes are invisible with the fill off |
| Unit 1 `eve_cohost_check.clj` | **CHECKS: 21 / 21 passed** | |
| Unit 3 `probe_memory.clj` (this Eve line) | **111/111 PASS** | |
| Unit 5 `eve_cohost_check_u5.clj` (this Eve line) | **CHECK-U5: 31 checks, 0 failures** | |
| Unit 5 `probe_atom.clj` (this Eve line) | PROBE-ATOM: 66 checks, 3 failures — identical to the pre-change baseline on this line | the three Eve-line rows ("Base", below) |
| Jank AOT battery (`bb boot:jank`) | __JANK__ | |

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

- **cohost's layer on the jank seat** is unit 6's ground (flagship-gated): the gate's jank
  process is Eve-on-jank as-is, not cohost-on-jank. The JVM half preceded unit 6, as the plan
  allows ("the unit report must say which"): it does.
- **Eve-on-jank gaps met** (Eve-side, consumed as-is, not filled from cohost):
  (a) Eve-on-jank's codec takes its own `eve.map/EveHashMap` as a reference ("a value with
  no eve bytes … is held by reference"), so the content digest of an Eve map cannot be taken
  on jank directly; the gate hashes the materialized value on both seats (equal to the JVM
  Eve map's own digest). (b) `(eve.core/into {} eve-map)` on jank self-deadlocks inside jank's
  runtime (`object::seq` → `meta` → `lazy_meta::get` exclusive lock; Eve jank.md defect 4);
  the probe reads through eve.core's protocols. (c) jank's `flush` is a "TODO: port" stub.
  (d) First join on jank costs ~1.5–3 min of JIT per process (the race leg's barrier absorbs
  it).
- **Net kind**: Eve's network-atom client keeps its block cache in memory; the disk tier "a
  network atom contains" is the gateway member's disk domain (Eve's model), not a client-side
  disk LRU. Eve's net value must be a map (`:not-a-map`). jank has no `http` root in its
  `:jank` row, so a jank net atom is NOT-YET (Eve ADAPTERS.md: "none until jank runs the
  shared code"); a jank process can be a disk member of a gateway's domain. The net
  two-process run did not interleave (stated above).
- **Watches** fire for this process's publishes only; another process's swaps are seen on
  deref. Eve has no cross-process notification.
- **Conveyance** rides only `:disk` atoms (Eve's net atom has no meta-atom); frames are
  published at every thread-boundary capture while any conveying atom exists in the process
  (one meta-atom read per capture; a swap when a conveyed var is bound). Frame entries are
  last-writer-wins per var across processes. The expression (`:eve/conveys` on the atom,
  `:eve/convey` on the var) is this lane's design choice under the Q5 metadata ruling — the
  operator's to confirm.
- **Meta on a tiered atom** is per-process wrapper meta (the kind/place keys are what all
  processes must agree on; other keys stay local).
- `alter-meta!`/`reset-meta!` on a layer atom return the atom, not the new meta (inherited
  from unit 5, unchanged here).
- **Representability** of a shared tier: Eve codec data and Eve values; never a unit-3
  stand-in, a fn, or a host object. A mem atom holding such a root cannot be promoted.
- Timings were not a gate item and are not claimed. Unit 5's numbers (Eve JVM atom ops
  ~300–1000× stock) stand; disk adds the mapped-file costs.
