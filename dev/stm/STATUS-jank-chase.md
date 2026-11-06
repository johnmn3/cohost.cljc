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

## Next
Probe attempt 5: from repo root,
`jank --module-path src:. run dev/stm/probe_tx_jank.jank`
(loads cohost.hosts.jank-host, then load-file "probe_tx.clj" — the same
23 semantics checks the JVM seat runs; refs seat in host atoms on this
seat, Eve hosting joint unbuilt — STATUS 10 of the prior lane).
Interrupt sections called out explicitly in the run report.
