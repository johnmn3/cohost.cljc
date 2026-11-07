# Memory with Eve atoms

*2026-11-05. The implementation record is `doc/eve-cohost/` — `UNIT5-REPORT.md`
for the atom layer, `unit8/REPORT.md` for the ClojureScript shared heap,
`MEMORY-PLAN.md` §§7–8 for the rulings this chapter explains. Status per seat:
[Eve coverage and status](15-eve-coverage-and-status.md).*

With the Eve atom layer in force, `clojure.lang.Atom` — the class behind
`atom`, `swap!`, `reset!` and the rest — is answered by an **Eve atom**: the
atom's root is an immutable Eve value, and every change is a
compare-and-swap of that root inside Eve. Nothing in the API changes. What
changes is what an atom *is*: state that lives in Eve's heap, under Eve's
rules, where threads — and, on the seats that support it, worker realms and
other processes — can share it.

The layer is in force:

- on the **ClojureScript seat (Node)**, by default at the entry points
  (`COHOST_CLJS_EVE=0` turns the seat back to host atoms and one realm);
- on the **JVM seat**, when the boot opts in (`:eve-atoms true` in
  `cohost.boot/boot!`); the gates below were run that way;
- not yet on the **jank seat**: the layer compiles there; the seat's Eve
  hosting is [unit 6's ground](15-eve-coverage-and-status.md) — unit 6 has
  landed on the release branch — and the fill stays off, so jank programs
  use jank's atoms exactly as before.

## The ordinary surface, unchanged

`atom`, `deref` (and `@`), `swap!`, `reset!`, `compare-and-set!`,
`swap-vals!`, `reset-vals!`, `add-watch`, `remove-watch`, `set-validator!`,
`get-validator`, and the metadata operations all behave as stock's do, and
the layer's atoms compose with the thread layer: futures capture them,
agents act on them, `pmap` maps over them. (Unit 5's booted check runs 31
stock-shaped programs over the layer — atom inside a future, atom inside an
agent action, `pmap` over atoms — 31/31.) Watches fire with the exact
`(old, new)` pair of the swap that committed; a validator rejects a swap
before anything is published.

Under contention the semantics are exact: unit 5's probe ran 4 threads ×
2,500 `swap! inc` on one atom, three rounds, and the final value was 10,000
on every arm — no lost updates.

## What is different, on the record

Unit 5's report names the places where an Eve-backed atom is observably not
a host atom. They are decisions and substrate facts, not bugs:

- **`compare-and-set!` compares by equality, not identity.** The comparison
  is the seam's equality over canonical forms: two distinct but equal roots
  CAS as equal. Stock compares by identity.
- **A swap function can run more than once.** `swap!` delegates to Eve's own
  CAS loop, which re-runs the function per attempt; on a watched swap the
  layer additionally runs it once under an exclusive claim to learn the
  exact pair the watches must fire with. Stock permits multiple runs too —
  keep swap functions pure, as stock already requires.
- **Some roots cannot live in Eve.** Eve's codec refuses host values —
  functions, host objects. A root like that seats in the atom's wrapper on
  the host side instead, the atom keeps working, and the seating is one-way:
  once an atom has gone host-held it does not move back. A root that is
  itself an Eve value nested inside host data is likewise read as host data,
  not as Eve.
- **`deref` reads the whole root.** There is no partial read of a large
  root value.
- **Printed form (JVM seat).** A layer atom prints as its stand-in map, not
  as `#atom[...]`; metadata is served normally, so the printed form is the
  one surface gap.
- **Timed `deref` of an atom returns the value** rather than throwing —
  cohost's standing host-atom behavior, inherited by the layer, not
  introduced by it.

## Domains: the heap is an atom

Eve's unit of shared state is a **domain**: a heap of Eve atoms that a set
of threads shares. The ruling the memory work is built on is that *the heap
a Clojure program sees on cohost is itself an Eve atom* — the default
runtime atom domain. A program's atoms are minted across that domain's Eve
heap domains (Eve caps a domain's slots, and the layer shards at the cap);
namespace and var containers are residents of the same world: on the JVM
seat, var roots live in a registry that is itself one Eve atom in the
startup domain, so a `def` is, underneath, a publish into shared Eve state.

In plain terms, for a program:

- Threads in the same domain see the same atoms. An atom is how anything is
  shared; there is no second, hidden heap beside it.
- A value crosses a domain wall only by `deref` or `swap!` — a thread never
  reaches into another domain's storage directly.
- Contention is per atom and per domain: two threads swapping *different*
  atoms in a domain do not contend; two threads swapping the *same* atom
  serialize on its CAS, as with stock atoms.

Atoms that must outlive the process, or be shared with another process,
choose a different kind — disk or network. That is [Tiered
atoms](14-tiered-atoms.md).

## The shared heap on ClojureScript

A ClojureScript process has several heaps: the core realm's and each worker
realm's, with no shared objects between them. Unit 8 puts Eve underneath
all of them. The program's atoms are Eve atoms living in in-process Eve
domains over `SharedArrayBuffer`s, and **every realm of the process maps the
same domains** — so an atom is one atom, whichever realm touches it.

That is what makes the threading chapter's worker realms honest:

- A value created in a worker realm's heap, published into an atom the core
  created, is read back by the core — and extended by a second future in
  the worker. (The crossing program's `made-elsewhere` case.)
- An atom *created* in a worker realm is returned to the core by its Eve
  handle and swapped there. (`atom-born-elsewhere`.)
- Core and worker each swapping one atom 500 times through Eve's CAS land
  on the exact final value. (`contended-swaps`: `[:done 1000]`.)
- A future's captured locals and binding frame cross through the same
  atoms; a function crosses by its qualified name. A plain closure does
  not cross — work that cannot travel runs in the core realm instead, and
  the layer counts it (0 in the gate runs).

Realm-local state stays realm-local: watches, validators and metadata
belong to each realm's own wrapper, and a realm's watches fire for that
realm's swaps.

One Eve-side defect on this seat is named in unit 8's report and is visible
to programs today: **an atom holding a map with small integer keys whose
values are collections can read a key back incremented** — `(swap! a assoc
1 {:z [1 2]})` has been measured reading back as `{2 {:z [1 2]}}`. Scalar
values under integer keys, and keyword or string keys, are unaffected. The
defect is Eve's, it is recorded as Eve-side work, and cohost does not paper
over it.

## Cost today

Eve's JVM atom operations are priced nothing like the host's. Unit 5's
report prints, uncontended and in one session: stock `swap!` ≈ 0.7 µs/op;
the Eve-backed `swap!` ≈ 0.8 ms/op; `deref` ≈ 145 µs against stock's
≈ 0.2 µs. The wrapper's own share of that is a state read and a claim
check per operation; the rest is Eve's JVM atom price. Heap-grade speed on
this seat is named Eve-side work in progress — no substitute
implementation was built in the meantime — and those numbers are the
pre-work state, dated 2026-11-05.
