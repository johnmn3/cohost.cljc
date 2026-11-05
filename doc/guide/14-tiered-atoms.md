# Tiered atoms: memory, disk, and network

*2026-11-05. The implementation record is `doc/eve-cohost/unit7/REPORT.md`
(gate `GATE-U7: 9 rows, 0 failures`; layer probe 56/0; booted check 17/0) and
`MEMORY-PLAN.md` §4 unit 7 with the §7 Q5 ruling. Status per seat:
[Eve coverage and status](15-eve-coverage-and-status.md).*

An Eve-backed atom has a **kind**: where its root lives. The kind is chosen
by the program, expressed as metadata on the atom value — no new function
surface, nothing but stock `clojure.core`:

```clojure
(atom 1)   ; :mem, the default — unit 5's runtime-domain atom

(def counter
  (atom {:n 0} :meta {:eve/kind :disk
                      :eve/path "/var/app/db"
                      :eve/name "app/counter"}))

(def remote
  (atom {:n 0} :meta {:eve/kind :net
                      :eve/endpoint "http://gw:8080"
                      :eve/domain-id "d1"
                      :eve/name "app/counter"
                      :eve/api-key "k"}))

(alter-meta! a assoc :eve/kind :disk :eve/path p :eve/name n)
; promotion — upward only
```

The metadata keys are `:eve/kind` (`:mem`, `:disk` or `:net`),
`:eve/path` and `:eve/name` for disk, `:eve/endpoint`, `:eve/domain-id`,
`:eve/name` and `:eve/api-key` for network. Everything else about the atom
is the ordinary surface of [Memory with Eve atoms](13-memory-with-eve-atoms.md):
`swap!`, `reset!`, `compare-and-set!`, watches and validators all work, and
publishes ride Eve's own CAS for the tier — the disk CAS word in the mapped
root file, the broker's CAS for network atoms.

The tiers are levels, each backing the one above it, and the bottom level
owns the root pointer:

- **`:mem`** — the runtime atom domain of the process. The default.
- **`:disk`** — a file-backed (memory-mapped) Eve domain at `:eve/path`;
  the atom is registered there as `:eve/name`.
- **`:net`** — Eve's network atom, joined through a gateway/broker. Its
  disk level is the gateway member's own disk domain (see *Network atoms*,
  below, for exactly what that means today).

A kind key arriving on an atom that has never been published is
**create-or-join**: if the place already holds the atom, the existing root
wins and the initial value is ignored. On an atom that has been published,
a kind key is a **promotion**, and promotion is upward only: `:mem` →
`:disk` → `:net`.

The tiered kinds are hosted on the **JVM seat**. On ClojureScript,
`:disk` and `:net` throw, loudly (Eve's Node disk domains need its N-API
addon, which is not built in this line). On the jank seat, cohost's own
atom layer is not hosted yet — that is unit 6, [not yet
done](15-eve-coverage-and-status.md) — although Eve on jank itself speaks
the same disk format, as the gate below shows.

## Disk atoms: shared between processes

A disk atom's root lives in a file, so a second process that names the same
place joins the same atom. Joining is resolved by the operating system:
Eve creates `<path>.root` exclusively, so exactly one process is the
creator and every other process joins — there is no coordinator thread and
nothing to configure.

What the unit's gate exercised, end to end (two processes over **one** disk
atom, interleaved swaps from both sides):

- a JVM process running cohost's layer, and a jank process running Eve on
  jank directly, each made 200 swaps against the atom concurrently; the
  final value was exact, and equal read from both sides;
- Eve's seal of the root, and a SHA-256 digest of its content, were
  **identical from both processes**;
- **kill and rejoin, both ways**: the JVM process published a mark and was
  halted without closing anything; a fresh jank process rejoined and saw
  the mark. The jank process published its mark and exited; a fresh JVM
  process rejoined and saw both.

A killed process leaves its last published root. A rejoining process sees
that root — per-atom, always: a disk atom promises *its own* last published
state, never a cross-atom instant spanning several atoms.

Watches are per-process: a watch fires for the publishing process's own
swaps. Another process's swaps are visible on `deref`; Eve has no
cross-process notification, and neither does this layer.

Only Eve-representable data can live in a shared tier: Eve's codec data and
Eve values — never a function, a host object, or another atom. A memory
atom holding such a root cannot be promoted (see the rules below).

## Network atoms

A network atom is joined through a gateway: the `:eve/endpoint` names it,
`:eve/domain-id` names the domain, `:eve/name` the atom. Joining is
resolved by the broker — Eve's signaling and blob-store machinery — where
a disk atom's joining is resolved by the OS.

What has been exercised, on the JVM seat, in separate processes: a network
client and a disk member of the gateway's own domain each made 50 swaps
into one atom; a fresh network client then read back `{:disk 50, :net 50,
:count 100}` — every publish landed. (In that run the disk member's swaps
landed before the client's began; the run proves the shared root and its
exactness, not an interleaving.) A network atom's value must be a map.

Two things about the tier model are stated here exactly as they stand:

- The model says a network atom *contains* its disk level — the tiers are
  each a cache for the level below, and the bottom level owns the root.
  In Eve as cohost consumes it today, that disk level is **the gateway
  member's disk domain**; the network client's own block cache is kept in
  memory, not on the client's disk. Programs should not rely on a
  client-side disk cache existing.
- On the jank seat a network atom is **not yet** available (Eve's jank row
  has no HTTP root); a jank process can take part as a *disk member* of a
  gateway's domain, which is how the exercised run above was built.

## Bindings across processes

Binding conveyance — the thread layer's capture of a dynamic binding frame
— rides a shared atom too, across processes, on disk atoms. It is opted
into by metadata, again with no new functions:

```clojure
(def shared
  (atom {} :meta {:eve/kind :disk :eve/path p :eve/name "app/s"
                  :eve/conveys true}))

(def ^{:dynamic true :eve/convey shared} *tenant* nil)

;; instance A:  (binding [*tenant* "acme"] @(future ...))
;; instance B:  @(future *tenant*)   ;=> "acme"
```

A future (or agent send) on instance B sees the frame instance A published
for any var marked `:eve/convey`, unless B has bound the var itself — B's
own binding wins. A var not marked stays process-local. The frames live in
the shared atom's Eve meta-atom — coordination state kept beside the atom —
so the atom's own value, version and equality are untouched. Conveyance
rides `:disk` atoms only: Eve's network atom has no meta-atom.

## The rules

Kinds are chosen, never drifted into. The layer enforces, and unit 7's
probe exercised, each of these (the error key is the `:cohost.atom/error`
of the thrown `ex-info`):

| Rule | Behavior | Error key |
|---|---|---|
| No automatic upgrade or fallback | a kind whose seat hosting is absent throws; nothing silently becomes `:mem` | `:kind-unavailable` |
| Unrepresentable root | fails loudly at creation, at every publish, and at promotion, naming the value; it is never silently kept on the host | `:unrepresentable` |
| Downgrade | refused — including network → disk, which is **not supported** | `:downgrade` |
| Re-pointing a tiered atom at another place | refused; make a new atom | `:re-point` |
| Promotion into an occupied target | refused (a published atom cannot promote over an existing root) | `:promotion-target-occupied` |
| Missing place keys, unknown kind | refused | `:missing-place` / `:unknown-kind` |

Metadata on a tiered atom is per-process wrapper state, except the kind
and place keys themselves — those are the agreement all processes sharing
the atom must hold in common.
