# Unit 9 Node-seat HttpHeaders red — fix report (Lane M)

**Branch:** `muse/unit9-node-headers-fix`, forked from `muse/eve-cohost-impl`
@ 5bcbd10 (the impl mainline tip: unit 4 landed + unit 9 jank AOT fix merged).

**The defect (owner: unit 4, per cohost Agent 3's diagnosis, accepted by
Lane M in REPORTS 2026-11-05 7:05 PM ET):** on the impl mainline, unit 9's
cljs (Node) gate reads 35/36. The failing row is the client program
"HttpHeaders: firstValue absent, allValues, map lookup"
(`dev/eve_http_programs.cljc`), failing with
`Cannot compare :cohost/kind to date`.

**Source-read mechanism (verified in this tree before any run):**

- `cohost.http/header-map` builds the headers map as a **sorted map** of
  lower-cased string keys -> value vectors; `(.map h)` in the program
  returns exactly that sorted map.
- The program's `(get (.map h) "content-type")` routes through the shim's
  `RT-get` (`src/cohost/shim.cljc`), whose `:default` branch first asks
  `(memory/transient-value? m)`.
- `memory/transient-value?` (`src/cohost/memory.cljc`) answers with a
  keyword lookup: `(= ::transient (:cohost/kind x))`. A keyword lookup
  inside a sorted map is a tree search: the comparator is asked to
  compare the probe key `:cohost/kind` against the stored string keys
  ("date", "content-type", ...) and throws — on cljs,
  `Cannot compare :cohost/kind to date`.
- A layer guarded transient is never a sorted map: `memory/as-transient`
  builds a plain tagged map. The shim already states this rule at the
  transient-class predicates ("sorted collections have no transient
  form", guarded by `h-sorted?`); the RT dispatch paths that call
  `memory/transient-value?` were left unguarded by unit 4.

Agent 3's bisection: 2a551e9 36/36 -> 37dbcf4 36/36 -> f912e87 35/36
(the third state = mainline + unit-9 AOT fix merge).

---

## STATUS 0 — 2026-11-05 ~7:10 PM ET — reproduction launched

Worktree `~/workspace/wt/unit9-node-headers-fix` created from the impl
tip; nothing in `~/src/cohost` touched. Running unit 9's cljs harness
(`dev/eve_http_seats.clj`, Eve checkout = atom.broker's vendored tree)
detached, log at `~/workspace/wt/unit9-node-headers-logs/repro-mainline.log`.
Expectation: 35/36 with the HttpHeaders row red on the exact error above.

## STATUS 1 — 2026-11-05 ~7:35 PM ET — defect reproduced exactly; harness REF arm environment-blocked on this box

Setup note: the harness's ring-jetty classpath was absent from the local
Maven repo and direct Central TLS is intercepted on this box; seeded
`/home/hatch/m2repo` with curl (Central, Clojars fallback), 33 artifacts,
until `clojure -Spath` converged (seeder + logs in
`~/workspace/wt/unit9-node-headers-logs/`).

Reproduction (`repro-mainline2.log`; the first attempt's JVM was
OOM-killed silently under box load, no verdicts printed):

- **The HttpHeaders row fails on its CLJS arms with the exact defect
  error**, both arms: `[:error "Cannot compare :cohost/kind to date
  (outer: ERROR)"]` (vs Jetty) and `[:error "Cannot compare
  :cohost/kind to content-length (outer: ERROR)"]` (vs Eve's Node
  server). Every other client program's CLJS arms answer correctly.
  The row is red for the diagnosed reason, on this tree. Reproduced.
- The harness's other two arms cannot run from this sandbox: the plain
  JVM REF evals and the JDK `ask` calls need loopback TCP, and this
  assistant's sandbox intercepts **all** direct TCP (Muse "Other TCP
  connections" permission = Deny). Evidence (`net-probe.clj`): a raw
  JVM socket to a live 127.0.0.1 server receives the denial text as its
  first line; a connect to a dead port "succeeds". Node processes are
  unaffected (they route via the env proxy), which is why the CLJS arms
  run. The calibrated 35/36 was read in Agent 3's environment; the
  three-way verdict for the fixed row must be re-read there or wherever
  JVM loopback is not intercepted. What this box can and will prove:
  the row's CLJS arms go from the defect error to correct, mutually
  equal answers, and a same-mechanism probe on cohost's JVM seat
  (below) goes from throw to value.

Mechanism probes on cohost's JVM seat, pre-fix (`clojure -M:eve -m
cohost.run --eval`, logs `jvm-probe-prefix*.log`; stock-Clojure control
in-line): `memory/transient-value?` compiled by the host throws on a
string-keyed sorted map (ClassCastException, String cannot be cast to
Keyword), and in the booted world `contains?` and `find` on such a map
throw the same through RT-contains/RT-find, while booted `get` happens
not to reach the check on this seat (it throws through `get` on the
Node seat, per the row itself). The check is the defect, whichever op
reaches it first on a given seat.

## STATUS 2 — 2026-11-05 ~7:50 PM ET — fix applied in the shim

`src/cohost/shim.cljc` only (12 lines added, 37 call sites re-pointed):

- A private `transient-value?` in the shim: `(and (not (h-sorted? x))
  (memory/transient-value? x))`, with the comment naming the rule and
  the defect. `h-sorted?` is the adapter capability the shim already
  binds; on hosts without one it answers false and the check is
  memory's own, unchanged.
- Every shim dispatch path that asked `memory/transient-value?` now
  asks the guarded one: RT-get, RT-assoc, RT-dissoc, RT-contains,
  RT-find, RT-nth, RT-count, RT-seq, RT-peek, RT-pop, the ITransient*
  class-token predicates, and the `-seq`/`-count`/`-assoc`/`-valAt`/
  `-entryAt`/`-containsKey`/... method surface. A guarded transient is
  a plain map, never sorted, so unit 4's transient cases take the same
  branch as before; a sorted map now falls through to the host op
  instead of dying in the tag lookup.
- Full diff audited line-by-line against the pre-fix file: nothing but
  the alias block and the substitutions. Pure ASCII. Reader check
  (paren.clj equivalent with `:read-cond :allow`, the stock checker
  cannot read `#?`): 975 forms before and after, no read errors.

Adjacent same-shape site, NOT changed (out of this defect's scope, the
row does not reach it): `memory/eve-value?` also keyword-lookups its
argument; it is asked by the shim's equiv/hash dispatch, not by the
collection ops. Named here so it is a recorded remainder, not a
surprise.

## STATUS 3 — 2026-11-05 ~8:05 PM ET — post-fix probes + JVM gate

JVM-seat probe, post-fix (`jvm-probe-postfix.log`): `contains?` ->
true, `find` -> ["a" 1], `get` on the headers-shaped sorted map ->
["application/json"], `assoc`/`dissoc`/`count` correct on sorted maps;
guarded transients unchanged (`persistent!`/`conj!` -> [1 2 3],
`get` on a transient -> 1).

JVM gate (`bb boot:jvm`): **battery 221/221, libraries 62/62**
(`gate-jvm2.log`). First run read 220/221 with the sole FAIL being the
eduction row and its exact cast message — the canary
UNIT4-REMAINDER-REPORT.md documents as stock coll-reduce dispatch
fragility in that JVM state, not a seam regression. Control run on the
pristine impl tip (detached worktree, `gate-jvm-control.log`):
221/221 + 62/62; re-run of the fixed tree: 221/221 + 62/62. The red
was run state, not the change.

Unit 9 harness, post-fix (`harness-postfix.log`): the HttpHeaders
row's CLJS arms now read `[:ok [false :none ["application/json"]
["application/json"]]]` on **both** arms (vs Jetty, vs Eve's Node
server) — firstValue absent, orElse :none, allValues and the
`(.map h)` lookup agreeing. The row still prints FAIL in this sandbox
solely because the REF arm is environment-blocked (STATUS 1); the
value both CLJS arms produce is the value the program's semantics fix
for the reference. Pre/post diff of the whole run: the FAIL label set
is identical (the 17 REF-blocked client rows), and among all CLJS
answers exactly one line changed — this row's, from the defect error
to the correct value. No collateral movement on the Node seat.

## STATUS 4 — 2026-11-05 ~8:25 PM ET — cljs gate + unit 4 probe

- cljs gate (`bb boot:cljs`, `gate-cljs.log`): **battery 219/221,
  libraries 62/62**. The 2 FAILs are "numeric tower" and
  "defmacro &form &env" — confirmed standing: the pristine-tip
  control (`gate-cljs-control.log`) reads the identical 219/221 +
  62/62 with the identical pair. Neither touches the transient
  dispatch.
- Unit 4 collections probe (`probe_collections.clj`, JVM seat,
  `gate-u4probe.log`): **PROBE 76 checks, 0 FAIL** — the guarded
  check takes unit 4's transient cases down the same branch as
  before, as designed.

## STATUS 5 — 2026-11-05 ~8:55 PM ET — jank gate: toolchain restored, shim AOT-compiled, run in flight

First `bb boot:jank` attempt died in the AOT compile before reaching
any cohost code: jank's clang found libstdc++-13's headers but not
libc's (`fatal error: 'stdlib.h' file not found` on a trivial module
too). Diagnosis: this box cycled (uptime <1 h) and the gcc-14 dev
stack the toolchain detection needs was gone; only gcc-13's remained.
Restored from the cached debs in `~/workspace/cohost-install/`
(gcc-14-base, libstdc++6 14.2, libgcc-14-dev, libstdc++-14-dev — the
recorded cycle-recovery set); the trivial module then compiled.
Attempt 2: full AOT recompile ran; **`cohost/shim.o` produced** — the
new shim code compiles under jank's AOT. Battery run in flight at
this writing (box under multi-lane load).

## STATUS 6 — 2026-11-05 ~9:15 PM ET — all gates green; a landing happened mid-lane

Jank gate: attempt 2's run phase was OOM-killed (exit 137) at ~15 min
under multi-lane memory pressure — contention, not code. Attempt 3,
launched when the box drained, AOT cache fresh (no recompile):
**battery 219/221, libraries 62/62, exit 0** (`gate-jank3.log`). Its
2 FAILs are "Character/isWhitespace is Java's" ("ERR Unfinished
character: 8195" — a reader error in the row's own program text) and
"Character outside ASCII". Both are character/reader rows, red in
banked jank runs on pristine trees (the u9-aot-landing records carry
the isWhitespace row; UNIT5-REPORT names the outside-ASCII row as
standing); neither routes anywhere near the transient check, and the
row that differs from UNIT5's named pair ("subs count nth first last
butlast") **passed** in this run. No same-day pristine-tip jank
control was completed (see below), so this pair is labeled: standing
family, run-variable membership, mechanism-independent of the change.

**The landing.** At 8:22 PM ET, while this lane's jank gate was still
running, the fix was committed and landed by another hand: commit
5c50680 ("unit 9 Node-seat HttpHeaders fix: sorted-guarded transient
check in the shim") carries the fixed shim (verified byte-identical
to this lane's file), this report as of that hour, and copies of the
gate logs under `doc/eve-cohost/node-headers-logs/`. It sits on both
`muse/unit9-node-headers-fix` and `muse/eve-cohost-impl`, pushed to
origin (both refs verified at 5c50680). This lane did not make that
commit and did not merge anything; the coordinator's brief said the
coordinator lands it, and it is landed — earlier than this lane's
declaration, and with the report still mid-flight. Every gate below
was completed by this lane on content byte-identical to the landed
commit (the jank gate finished after the landing, on the same shim
bytes), so the landing stands on complete evidence; this final
report is committed on this lane's branch on top of it.

## Gate table (all on the fixed tree; logs in ~/workspace/wt/unit9-node-headers-logs/)

| Gate | Result |
|---|---|
| Unit 9 harness, Node seat — HttpHeaders row | CLJS arms `[:ok [false :none ["application/json"] ["application/json"]]]`, both arms, agreeing (was: the defect error, both arms). Row's three-way verdict needs a host without this sandbox's JVM-loopback block (STATUS 1) |
| JVM battery / libraries (`bb boot:jvm`) | **221/221, 62/62** (one 220/221 canary run — eduction cast, documented stock fragility; control + re-run both 221/221) |
| cljs battery / libraries (`bb boot:cljs`) | **219/221, 62/62** (pair: numeric tower, defmacro &form &env — identical on pristine-tip control) |
| Jank battery / libraries (`bb boot:jank`) | **219/221, 62/62** (pair: Character/isWhitespace, Character outside ASCII — standing reader family); shim AOT-compiled (`cohost/shim.o`) |
| Unit 4 collections probe (JVM) | **76 checks, 0 FAIL** |
| JVM-seat sorted-map probes | get/contains?/find/assoc/dissoc/count answer on sorted maps (contains?/find threw pre-fix); transients unchanged |

## DECLARATION — Lane M, unit 9 Node-seat HttpHeaders fix

DONE, with one named environmental remainder. The defect is fixed in
cohost's shim: `memory/transient-value?` is no longer asked bare by
any shim dispatch path — a sorted-guarded `transient-value?` in
`cohost.shim` answers first, so a sorted map (cohost.http's headers
map is the instance that surfaced it) falls through to the host op
instead of dying in the tag lookup's comparator. Unit 4's transient
behavior is unchanged (probe 76/76; transient ops verified in the
seat probes). The change is landed on `muse/eve-cohost-impl` (by the
coordinator's hand, STATUS 6) and this report is its record.

Remainders, named: (1) the unit 9 harness's three-way verdict for the
HttpHeaders row (REF = CLJS = CLJS×Eve) must be re-read in an
environment whose JVM loopback is not sandbox-intercepted — Agent 3's
environment read the 35/36 and can read the 36/36; both CLJS arms
here already produce the reference value. (2) `memory/eve-value?`
carries the same lookup shape on the equiv/hash path (STATUS 2) —
not this defect, not touched. (3) The gate logs committed under
`doc/eve-cohost/node-headers-logs/` by the landing hand duplicate the
durable logs in `~/workspace/wt/unit9-node-headers-logs/`; tidying
them is the coordinator's call.

## STATUS 5 — 2026-11-05 ~8:20 PM ET — independent verification + banking (Toby's follow-on lane)

John's word ~8:16 PM ET was to have this knocked out; his follow-up was to
double-check it was not already fixed. It was: the work above (STATUS 0–4)
sat **uncommitted** in this worktree — shim diff + this report, branch
never pushed. This lane verified it firsthand rather than trusting the
writeup, then banked it.

- Diff audited firsthand: `src/cohost/shim.cljc` only, 49 insertions /
  37 deletions — the sorted-guarded `transient-value?` alias block plus
  mechanical re-pointing of every `memory/transient-value?` call site.
  Nothing else in the tree differs from the impl tip.
- Unit 9 harness re-run firsthand in this sandbox with the fix in place
  (`harness-verify.log`, banked in `node-headers-logs/` beside this
  file): the HttpHeaders row's CLJS arms both answer
  `[:ok [false :none ["application/json"] ["application/json"]]]`.
  A per-row diff of every CLJS answer, pre-fix repro vs this run,
  changes **exactly one row** — this one, from
  `Cannot compare :cohost/kind to date` / `... to content-length` to the
  correct value. Zero collateral movement on the Node seat.
- The gate logs were re-read, not re-trusted: JVM gate fixed tree
  221/221 + 62/62 with pristine-tip control identical; cljs gate
  219/221 + 62/62 with the standing pair (numeric tower, defmacro
  &form &env) identical in the control; unit 4 probe 76 checks, 0 FAIL.
- What remains unprovable **in this sandbox family** (unchanged from
  STATUS 1/3): the harness's REF arm errors and its JDK-ask server
  section dies on the sandbox's "Other TCP connections is turned off"
  interception (reproduced again in this run — the JVM HttpClient
  receives the denial text as a status line), so the formal three-way
  36/36 cannot be re-read here. The calibrated 35/36 was Agent 3's
  environment; the closing 36/36 re-read is owed there or wherever JVM
  loopback is not intercepted. On the Node seat itself — the seat that
  was red — the row is correct on both arms and nothing else moved.
- Banked: fix + this report + the verdict logs committed and pushed on
  `muse/unit9-node-headers-fix`.
