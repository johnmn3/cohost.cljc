# Eve coverage and status

*2026-11-05. This page collects, per facility and per seat, the state of the
Eve work described in [Threading with Eve](12-threading-with-eve.md),
[Memory with Eve atoms](13-memory-with-eve-atoms.md) and [Tiered
atoms](14-tiered-atoms.md). Every cell rests on the report or gate line in
its row's Sources column; the record lives in `doc/eve-cohost/`.*

The states mean exactly this:

- **LANDED** — the work is on `muse/eve-cohost-impl`, the Eve-in-cohost
  mainline, with its gates green there or on the merged tree.
- **GATE-GREEN** — proven on its own branch by its unit's gate, landing in
  flight; not yet on the mainline.
- **NOT-YET** — not available on that seat. Where a seat's cell is NOT-YET,
  the program gets the seat's previous behavior (or a loud refusal, where
  the chapters say so) — never a substitute implementation.
- **—** — the facility does not apply to the seat.

## Threading

| Facility | JVM | jank | ClojureScript (Node) | Sources |
|---|---|---|---|---|
| Thread layer: futures, promises, `pmap`/`pcalls`/`pvalues` | LANDED — fires where the host lacks the names; exercised via the boot's `:host-lacks` opt | LANDED — the seat's threading; its core has the family only as stubs | LANDED — cooperative tasks on the core realm (unit 2's leaf) | UNIT1-REPORT (probe 29/29, booted check 21/21); UNIT2-REPORT (AOT battery 219/221); CLJS-DEP-REPORT (probe row ok=22 after the macro-fill fix) |
| Agents | LANDED | LANDED | LANDED — actions run in the core realm; they do not cross to a worker realm | UNIT1-REPORT; UNIT2-REPORT (probe agent case answers 1); unit8/REPORT remainders |
| Worker realms: futures crossing realms, shared heap | — (real host threads) | — (real host threads) | GATE-GREEN — futures whose work can travel run in worker realms; captures and frames cross through the shared atom | unit8/REPORT gate: probe rows JVM ok=23 / cljs ok=23; crossing program same 5/5, handed-over 5, closures 0 |
| `seque` | NOT-YET | NOT-YET | NOT-YET | UNIT1-REPORT named distances: `seque` remains host-only and unfilled on every seat |

The probe row behind the ClojureScript cells matches the JVM's values on 22
of 23 cases. The exception is `test-stacktrace`, which counts a JVM stack's
frames; the cljs seat answers an empty trace, and the case awaits the
operator's ruling (unit8/REPORT, *The gate* §1).

## Memory

| Facility | JVM | jank | ClojureScript (Node) | Sources |
|---|---|---|---|---|
| Eve-backed `Atom` (unit 5's layer) | LANDED — opt-in per boot (`:eve-atoms true`) | NOT-YET — the layer compiles in the seat's AOT graph; the fill stays off (unit 6's ground) | GATE-GREEN — on by default at the Node entry points; `COHOST_CLJS_EVE=0` opts out | UNIT5-REPORT (probe 66/0, booted check 31/0, jank AOT gate 219/221); unit8/REPORT (entry points) |
| Var roots in a registry Eve atom | LANDED | NOT-YET | NOT-YET — var roots cross *realms* by name under unit 8; the unit-5 registry itself is JVM hosting | UNIT5-REPORT (registry: `cohost.registry/table`) |

One Eve-side defect is live on the ClojureScript seat and named in
unit8/REPORT (*Named Eve-side findings* §1): an atom holding a map with
small integer keys whose values are collections can read a key back
incremented. It is Eve-side work; cohost does not hide it.

## Tiered atoms

| Facility | JVM | jank | ClojureScript (Node) | Sources |
|---|---|---|---|---|
| Disk atoms (`:eve/kind :disk`) | LANDED | NOT-YET through cohost's layer — the gate's jank side ran Eve on jank directly over the same atom and format | NOT-YET — `:disk` throws loudly on this seat | unit7/REPORT (GATE-U7 9/9: race, digests identical, kill/rejoin both ways); LANDING-u7-u9-DECLARATION (landed, merged-tree gates green) |
| Network atoms (`:eve/kind :net`) | LANDED — exercised between separate processes through a gateway; the atom's value must be a map; the disk level is the gateway member's disk domain | NOT-YET — Eve's jank row has no HTTP root; a jank process can be a disk member of a gateway's domain | NOT-YET — `:net` throws loudly on this seat | unit7/REPORT (net two-process run; named remainders) |
| Binding conveyance across processes | LANDED — disk atoms only | NOT-YET | NOT-YET — conveyance across *realms* is the worker-realms row above | unit7/REPORT (two-process conveyance run) |

## Not yet, anywhere

- **Eve-backed collections (unit 4)** — NOT-YET on every seat: built behind
  the seam on its own lane; not landed, and not a seat default.
- **Slab residence on the jank seat (unit 6)** — NOT-YET: design and probe
  scaffolding banked, and a JVM reference leg passing; the implementation
  waits on the Eve-on-jank flagship gate. This is the work that turns the
  jank seat's Eve-backed atoms and tiered kinds on.
- **The browser seat** — no Eve in the browser build; unchanged.
- **Sorted collections** — Eve has them (`eve.deftype.rb-tree`); bringing
  them behind cohost's seam rides Eve's line and the seam-identity
  contract, and is not part of the units above (MEMORY-PLAN §7, Q4).

Standing battery context for every LANDED cell above: on the merged
mainline, `bb boot:jvm` prints battery 221/221 and libraries 62/62; the
jank seat's battery is 219/221, its two failures the standing named pair
(`subs count nth first last butlast`; `Character outside ASCII`).
