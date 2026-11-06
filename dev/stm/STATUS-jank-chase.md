# STATUS — STM jank chase lane (Lane M)

Operator directive (John Newman, 2026-11-06): the STM multi-target lane
declared jank "NOT RUN (not failed)" behind an apt/dpkg lock. Operator:
"Those don't seem like real blockers" — chase it down.

Branch: `muse/stm-jank-probe` off `origin/muse/eve-cohost-stm-agents`
(tip 228e0c1 at branch creation). Worktree: `~/workspace/wt/stm-jank`.

## Milestone 1 — 2026-11-06 ~9:13 AM ET — lock state + toolchain restore

**Lock state, firsthand:** NO apt/dpkg process exists on this box and
`fuser` shows no holder on /var/lib/dpkg/lock or lock-frontend (the lock
FILES exist, dated today 12:50–12:53 UTC, held by nobody — stale files,
not a held lock). Whatever held the lock during the prior lane's run is
gone. No apt was needed for the restore regardless.

**What was actually missing:** only jank itself. A VM cycle wiped
/usr/local again; everything else the prior lane named was already back:
gcc-14-base, libgcc-14-dev, libstdc++-14-dev are dpkg-registered
(os replay restored them), /usr/include/c++/14 headers present, system
libstdc++ is .so.6.0.33 (gcc-14 runtime, GLIBCXX up to 3.4.33), and all of
jank.deb's declared dev deps (libbz2-dev, libzstd-dev, libxml2-dev,
zlib1g-dev, libboost-all-dev) are installed. The three cached gcc-14 debs
were ALSO extracted with `dpkg-deb -x` as belt-and-braces (idempotent,
same versions).

**Restore:** `dpkg-deb -x ~/workspace/cohost-install/jank.deb /`
(no dpkg lock involved). jank → /usr/local/bin/jank, resources →
/usr/local/lib/jank/0.1 (bundled clang-23). `ldd` clean, no missing libs.

**Smoke, as printed:**
- `jank check-health`: all green — runtime initialized, resource dir
  found, runtime clang found, **"jank can jit compile c++" ✅**.
- Hello run: `jank run hello.jank` → `hello from jank: 3` (1.3 s, warm
  PCH cache in ~/.cache/jank).
- C++ JIT spot check: bundled clang-23 demonstrably parses the
  libstdc++-14 headers (a deliberately malformed probe got a semantic
  error citing /usr/include/c++/14/ratio). NOTE: raw `#include` inside
  `(cpp/raw ...)` at top level does NOT take effect for later forms —
  the cpp-include mechanics documented in
  ~/workspace/cohost-install/cpp-include-problem.md; cohost's boot
  handles includes its own way. Probe-authoring detail, not a toolchain
  defect.

## Milestone 2 — 2026-11-06 ~9:20 AM ET — probe attempt 1: mixed-install PCH (diagnosed)

Probe attempt 1 (log ~/workspace/stm-jank-chase/probe-run-1.log) died in
the C++ JIT compile of cohost/adapter.cljc's interop: mass
"redefinition of 'platform' / 'is_trivially_movable' / …" errors, with
the "previous definition" notes pointing into a SECOND jank tree at
~/opt/jank-root/usr/local/lib/jank/0.1 while the current definitions
came from /usr/local/lib/jank/0.1.

Diagnosis (verified): the box carries TWO extractions of the same jank
build (both binaries print binary-version cf627abb…): /usr/local
(restored today) and ~/opt/jank-root (Nov 5 08:42, another lane's
extraction). jank's user cache keys on binary version, so both share
~/.cache/jank/…-cf627abb…, which held an 88 MB incremental.pch built
Nov 5 08:42 against the OPT tree's headers. Today's /usr/local binary
loaded that PCH, then re-included the same jtl headers from its own
tree → every definition twice. Pure-Clojure runs (hello) never touch
the C++ headers, which is why the smoke passed.

Fix applied: both stale caches moved aside (recoverable):
~/.cache/jank-stale-mixed-cf627abb (the 88 MB PCH) and
~/.cache/jank-stale-opt-6187d5ff (an older build's cache). Attempt 2
rebuilds the PCH from the single /usr/local install. This is an
environment defect, not a jank-seat or STM defect.

## Milestone 3 — 2026-11-06 ~9:27 AM ET — attempt 2: PCH fixed; runner's load-file unresolved (fixed)

Attempt 2 (log probe-run-2.log): the PCH rebuilt cleanly against the
single /usr/local install ("Building pre-compiled header… done!") and
compilation got past the C++ header stage — the mixed-install defect is
closed. New, distinct failure at analysis of the runner itself:
`load-file` could not be resolved (dev/stm/probe_tx_jank.jank:12).
jank's clojure.core (installed core.jank) defines load / load-reader /
load-string / slurp but NO load-file; cohost's booted path fills
load-file via core-extras, but this raw runner doesn't boot, so the
symbol isn't there. The prior lane wrote the runner but never ran it —
this is exactly the probe-infrastructure class the chase brief covers.

Fix (committed on this branch): the runner now uses
`(load-string (slurp "probe_tx.clj"))`, the raw-jank equivalent.
Attempt 3 proceeds on this change (not a blind retry: new failure, named
mechanism, named fix).

## Milestone 4 — 2026-11-06 ~9:33 AM ET — attempt 3: load-string is a TODO stub; route = `load`

Attempt 3 (log probe-run-3.log) died immediately: jank's `load-string`
throws ex-info "TODO: port load-string". Reading the installed
core.jank settled the whole family: `load-reader` and `load-string`
are both unimplemented TODO stubs; `load` IS implemented (runtime
load_module over the module path). Each attempt has failed on a
distinct, diagnosed mechanism (PCH mix → unresolved load-file →
load-string stub), banked before the next — not blind retries.

Runner now uses `(load "/probe_tx")` and the run command gains `.` on
the module path (`--module-path src:.`) so the repo-root probe_tx.clj
resolves as a module. If jank's module loader rejects a no-ns .clj
script, the fallback is a read/eval loop over the file's forms.

## Milestone 5 — 2026-11-06 ~9:40 AM ET — attempt 4: .clj invisible to jank's module loader (measured); runner self-generates a .jank copy

Attempt 4 (log probe-run-4.log): `(load "/probe_tx")` →
runtime/module-not-found. Isolated in a scratch dir (modtest, warm PCH,
seconds per run): `(load "probe_tx")`, `(load "/probe_tx")`,
`(load "probe-tx")` and `(require 'probe-tx)` ALL fail to find
probe_tx.clj at relative or absolute module paths — while the identical
content named probe_tx.jank loads immediately. **jank's runtime module
search resolves .jank/.cljc only; .clj files are invisible to it.**
(That's also why the seat's own sources are .jank/.cljc.)

Runner restructured (committed): it now spits the shared script to
dev/stm/probe_tx_jank_generated.jank (gitignored; probe_tx.clj stays
the single source) and `(load "/dev/stm/probe_tx_jank_generated")`.
slurp/spit verified as real implementations in jank core (C++ bodies,
not stubs). The no-ns script body executing under module load was
proven by the modtest run.

## Milestone 6 — 2026-11-06 ~9:45 AM ET — attempt 5: spit works, absolute load path doesn't; relative-to-module it is

Attempt 5 (log probe-run-5.log): the self-generation worked (the
10,474-byte generated module was written) but
`(load "/dev/stm/probe_tx_jank_generated")` → module-not-found.
Combined with the modtest evidence (relative `(load "probe_tx")` from
a same-directory module WORKED; every absolute form failed), jank's
`load` resolves relative paths against the loading module's own
directory, not Clojure's ns-root rule. Runner now uses the relative
`(load "probe_tx_jank_generated")` — the generated file sits beside
the runner in dev/stm/.

## Milestone 7 — 2026-11-06 ~9:50 AM ET — attempts 5/6: `load` can't reach subdirectory modules at all; require-by-ns-name can (measured)

Attempt 5: absolute `(load "/dev/stm/probe_tx_jank_generated")` →
module-not-found. Attempt 6: relative `(load
"probe_tx_jank_generated")` → module-not-found (my
relative-to-loading-module theory was WRONG). Decisive modtest with a
subdirectory module: `(load "/sub/gen")` ✗, `(load "sub/gen")` ✗,
`(require 'sub.gen)` ✓ LOADED. So in this jank build, `load`'s string
forms reach only flat same-root modules, and the general mechanism is
require's ns-name → munged-path resolution.

Runner now: the generated copy carries an
`(ns dev.stm.probe-tx-jank-generated)` header (its forms evaluate in
that ns, as they would under load-file) and the runner requires it by
name.

## Milestone 8 — 2026-11-06 ~9:55 AM ET — attempt 7: require route WORKS; jank's lexer rejects ALL non-ASCII source (isolated); one-char seat fix in tx.cljc

Attempt 7 (log probe-run-7.log): the generated module loaded and its
requires began resolving — then jank's lexer failed with
"Unterminated string" on src/cohost/tx.cljc line 2, the ns docstring.
Isolated in modtest (seconds each): a `×` inside a string →
Unterminated string; a `×` inside a COMMENT → "Unicode character
literal is unfinished"; an em dash in a string → Unterminated string.
**This jank build's lexer rejects non-ASCII characters anywhere in a
source file, strings and comments alike** (the reader non-ASCII gap
previously met on unit 9's jank leg).

Blast radius, measured: across ALL of cohost's src only TWO files
contain any non-ASCII at all — hosts/jolt.clj (JVM-side, not in this
load set) and tx.cljc, which has exactly ONE non-ASCII character: the
`×` in its ns docstring. Seat-side fix (committed on this branch):
that one character is now `/` ("STM/agent seam" — docstring only, no
code touched). General finding for the mainline: any future non-ASCII
character landing in a jank-loaded cohost/Eve source file is a hard
load failure on this seat until jank's lexer learns UTF-8.

## Milestone 9 — 2026-11-06 ~10:00 AM ET — attempt 8: PROBE RUNS (4 PASS), then jank's analyzer rejects JVM catch classes; seat-adapted spellings in the generated copy

Attempt 8 (log probe-run-8.log) is the first run to EXECUTE the probe:
ref-value?, deref outside tx, dosync alter, committed — 4/4 PASS as
printed, on jank, through cohost.tx over host atoms. It then stopped
at the first JVM-spelled catch: jank's analyzer cannot resolve
`IllegalStateException` "within the global namespace"
(analyze/invalid-try). Full inventory of JVM spellings in the shared
script (grep): 2× (catch IllegalStateException, 4× (catch Exception,
2× (Exception. "boom"), 9× (Thread/sleep, 4× (System/nanoTime),
1× (System/exit). Each has a measured seat spelling:

- Catches: cohost's own adapter (adapter.cljc try-all, :jank branch)
  catches thrown values as (catch cpp/jank.runtime.object_ref e) —
  every cohost-layer throw on this seat (host/ex* products, ex-infos)
  crosses as an object_ref and binds properly. (:default would bind e
  to nil; the interrupt checks pass e to thread/interrupted-ex?, whose
  contract reads (ex-data e) for :cohost.thread/interrupted — so
  object_ref is the only faithful spelling.)
- (Exception. "boom") → (ex-info "boom" {}).
- (Thread/sleep → (@#'clojure.core/sleep — jank core's sleep is
  private (cpp/jank.runtime.sleep underneath); verified callable.
- (System/nanoTime) → (cpp/cohost_time.nano_time) — the chrono clock
  jank_host.jank itself installs for the seat.
- (System/exit ...) → dropped; the probe's PROBE-TX verdict line is
  the result of record.

All six substitutions are applied ONLY to the generated .jank copy,
spelling-only, documented in the runner header; probe_tx.clj is
byte-untouched and the 23 checks keep their order and expectations.

## Milestone 10 — 2026-11-06 ~10:05 AM ET — attempt 9: PROBE RUNS END-TO-END — 20/23 PASS, interrupt checks PASS

Run under a pty (script -qec; plain redirected stdout is block-buffered
and a late crash eats it — log probe-run-9c.log; the two earlier
attempt-9 runs, 9/9b, segfaulted at process teardown AFTER the probe
completed, losing the buffered output). **All 23 checks executed, as
printed:**

PASS (20): ref-value? · deref outside tx · dosync alter returns new
value · committed · alter outside tx throws · abort rolls back both
refs · snapshot read point stable · outside read sees the commit ·
ensure prevents the skew · 8 threads x 200 commutes, none lost (1600)
· transfer invariant holds (20000) · all transfers landed [8000
12000] (INFO: 2253 attempts for 2000 transactions) · validator
rejects the commit · value unchanged after rejected commit · watch
fired once [[:w 1 3]] · history count · hot ref landed every
increment (200) · **interrupt wakes a blocked latch wait with the
interrupt exception => :interrupted** · **interrupted tx thread
abandons => :interrupted** · **abandoned tx wrote nothing (555)**.

FAIL (3): "write skew happens without ensure (stock answer too)"
expected 80 got 100 · "send from a committing tx fired once" expected
[:committed] got [] · "send from an aborting tx never fired" expected
[:committed] got [] (inherits the previous row's empty agent state).

**INTERRUPT VERDICT (the STATUS 1 throw-crossing question): YES on
jank.** Both interrupt checks pass exactly as designed: the interrupt
exception crosses the transaction body on jank frames, is caught as a
jank object_ref, and thread/interrupted-ex? identifies it via ex-data
(:cohost.thread/interrupted) — the abandoning transaction wrote
nothing. Jank behaves JVM-family here, like Jolt's "throw-crossing
YES", NOT flag-only like the JS seats. Cooperative interruption is
therefore PROVEN on the jank seat, not just designed.

Also as printed: the two bench INFO lines render their format string
UNSUBSTITUTED ("INFO uncontended commute tx: %.0f tx/ms") — jank's
format does not substitute %.0f, so the retry-cost numbers do not
print on this seat (minor; finding F-3 below). The verdict line
"PROBE-TX: ..." never prints because the runner's generation replaced
the System/exit form — the per-check lines + the runner's "script
completed" are the record (the failures atom is listed in the FAIL
lines above).

Teardown: after "INFO probe_tx_jank: script completed" the process
SEGFAULTS on exit (139) — reproducibly, also after both diagnostic
scripts. The probe's work is complete before the crash; it is a
runtime-shutdown crash with live layer threads (finding F-2).

## Milestone 11 — 2026-11-06 ~10:20 AM ET — the 3 FAILs diagnosed: 1 probe-timing artifact, 1 real seat defect BELOW the STM

Diagnostics (~/workspace/stm-jank-chase/diag1.jank, diag2.jank; logs
alongside):

- **Write skew = TIMING ARTIFACT, STM semantics CORRECT.** The
  section's overlap window is an in-transaction (sleep 60). Re-run of
  the identical shape with a 2000 ms window: skew total = **80**, the
  stock answer. On this JIT seat, thread start + first-call JIT of the
  second transaction's body exceeds 60 ms, so the probe's two
  transactions serialize (second reads the committed 100 and declines
  the debit). Not an STM defect; the probe's 60 ms choreography is
  calibrated for a warm JVM. (The ensure row passes under both
  schedules, as it should.)
- **Agent sends = REAL SEAT DEFECT, but NOT in the STM.** Diag part B:
  a transaction-held send never lands (8 s poll, state stays []).
  Diag 2: a PLAIN send outside any transaction ALSO never lands
  (dispatch! accepted, state [] after 8 s). So the tx→agent hold seam
  is exonerated: cohost.thread's agent machinery on the jank seat
  accepts sends and never drains them. The executor is layer code
  (queue atom + control atom + generation doorbell, workers spawned
  through the seat's thread leaves, thread.cljc:266ff) — one-shot
  spawned closures run fine on this seat (every threaded STM section
  passes), so the suspect locus is the long-lived worker/doorbell
  path (doorbell wait/signal on jank), the same family as the known
  jank thread-suite gap around closures sent to worker pools.
  **Finding F-1 (named, with repro): AGENT-DRAIN-JANK** —
  repro = diag2.jank (20 lines, deterministic: dispatch! accepted,
  agent-state [] forever). Owner: the cohost.thread jank seat work
  (unit 1/2 line), not the STM layer and not this lane. The STM's
  send-HOLD semantics are unproven-on-jank only insofar as delivery
  itself is broken beneath them; the hold/release code ran without
  error in both diag parts.

## FINAL — 2026-11-06 ~10:25 AM ET

**Toolchain:** the "blocker" was not real. No process held the dpkg
lock (stale lock files only); the gcc-14 dev stack the prior lane
named was already dpkg-restored; only jank itself was missing (VM
cycle wiped /usr/local). Restored via `dpkg-deb -x` of the cached
jank.deb (+ the three cached gcc-14 debs, idempotent). Smoke:
check-health all green incl. "can jit compile c++", hello runs. One
environment trap found and cleared: two extractions of the same jank
build shared one PCH cache keyed by binary version — the stale
opt-tree PCH poisoned compiles until moved aside (milestone 2).

**Probe:** probe_tx runs END-TO-END on the jank seat —
**20/23 checks PASS as printed**, including both cooperative-
interrupt checks and the full contention battery (8×200 commutes
exact, transfers exact with retries counted). Of the 3 FAILs: one is
a probe-timing artifact (skew proven correct at a 2000 ms window),
two are one real defect BELOW the STM (agent sends never drain on
this seat — F-1, repro banked). Named findings: F-1 AGENT-DRAIN-JANK
(thread-layer seat) · F-2 jank process segfaults at shutdown after
completed runs (exit 139; verdicts print before it) · F-3 jank's
format does not substitute %.0f (bench INFO numbers don't print) ·
plus the seat facts established en route: jank's lexer rejects ALL
non-ASCII source (one-char docstring fix in tx.cljc on this branch),
jank core lacks load-file and stubs load-string/load-reader, and its
module search is .jank/.cljc-only with require-by-ns-name as the
general loader (runner restructured accordingly, spelling-only seat
adaptations documented in its header; probe_tx.clj byte-untouched).

**Jank STM verdict: RUNNING** — semantics + cooperative interruption
proven on the seat over host atoms. (Eve-hosted refs on jank remain
the unbuilt atom-eve jank joint, STATUS 10 — unchanged by this lane.)
The prior lane's "NOT RUN (not failed)" is hereby closed: it ran.
— Lane M, STM jank chase

`jank --module-path src:. run dev/stm/probe_tx_jank.jank`
(loads cohost.hosts.jank-host, then load-file "probe_tx.clj" — the same
23 semantics checks the JVM seat runs; refs seat in host atoms on this
seat, Eve hosting joint unbuilt — STATUS 10 of the prior lane).
Interrupt sections called out explicitly in the run report.
