# Landing — unit 9 jank AOT fix (`-getDefaultPort` by lookup, not `case`)

Landed 2026-11-05 ~5:56 PM ET by Lane M (Toby's lane), on assignment from
cohost Agent 1: merge of `agent3/unit9-jank-aot-fix` (cohost Agent 3's fix,
one commit) into `muse/eve-cohost-impl`.

## What landed

- Merge commit over the impl tip (unit 4 remainder verdict). Net diff vs the
  pre-merge tip: exactly one file, `src/cohost/shim.cljc`, 7 insertions /
  1 deletion — Agent 3's change, unaltered (verified by reading the diff;
  the fixed shim is pure ASCII, 0 non-ASCII bytes).
- The change: `-getDefaultPort` answers from a map lookup,
  `(get default-ports (:scheme u) -1)` over `{"http" 80 "https" 443 "ftp" 21}`,
  instead of a `case` on string constants with integer-literal branches and
  default. Same answers as the `case` (JVM check by Agent 3:
  http/https/ftp/gopher/nil → 80/443/21/-1/-1).

## Why

The Eve-in-cohost mainline's jank AOT build has been red since unit 9 landed:
the `case` form hits jank upstream defect 19 — each branch assigns a C++ int
to the case's oref result ("no viable overloaded '='" at jank
core.jank:4527, 4 clang errors) — and the AOT build ended without
`cohost/shim.o`. Agent 3 found the red on the untouched base, confirmed the
producer is unit 9's `-getDefaultPort` line, and wrote the fix on its own
branch off the mainline tip so it could land apart from unit 6.

## Gates on the merged tree (this box, 2026-11-05, as printed)

- `bb boot:jvm` — booted as stock: 465, from host: 202, natives: 66,
  skipped: 58, **errors: 0** · **battery: 221/221** · **libraries: 62/62**.
- `bb boot:jank` — AOT-RECOMPILE completed and `cohost/shim.o` is present in
  `target/jank-aot/_cache/.../cohost/shim.o` (the artifact the defect
  denied); booted as stock: 544, from host: 141, natives: 28, skipped: 108,
  **errors: 0** · **battery: 219/221** (the standing jank pair) ·
  **libraries: 62/62**. No clang errors in the log.
- These match Agent 3's fix-branch gates (5:13–5:20 PM ET, its box) exactly,
  and the jank row is back at the unit-8 baseline.
- Conflict-marker sweep on the committed merged tree: line-anchored
  `<<<<<<<` / `>>>>>>>` / `=======` — **0**. (Unanchored grep hits are prose
  mentions of the markers inside backticks in pre-existing landing docs.)

## Declaration

DONE: the fix is merged into `muse/eve-cohost-impl` and pushed (verified on
the fetched remote ref, not push output). This closes the mainline's jank
AOT red found by cohost Agent 3. No other files changed; unit 6's step-2
decision and the pr-str fault are untouched by this landing and remain open
on their own tracks.
