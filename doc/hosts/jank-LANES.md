# jank: two lanes over doc/hosts/jank-FAST.md (2026-11-01 ET)

Two implementers work the plan in parallel; a third session reviews and merges. The split
follows file ownership, so the lanes do not edit the same code.

## Shared rules

- The base is `origin/claude/youthful-euler-gquikf`. Rebase onto it before every push.
- Push every step when it is done, and push work in progress at least once per session. Work
  that exists only on one machine can be neither reviewed nor built on.
- Each commit message names the step (jank-FAST step number) and its scoreboard numbers before
  and after, from the commands the plan names.
- A change to a shared file (`src/cohost/*.cljc`, `bb.edn`) states in its commit message
  that it touches the shared layer. The reviewer runs the all-host regression before merging;
  a lane whose machine has only jank installed is not expected to run it.
- New host facts go to doc/REPORT.md with a battery row; no battery row is weakened.

## Lane M (muse): correctness, then runtime speed, in the adapter and the shim

Owns `src/cohost/hosts/jank.jank` and the `:jank` branches of `src/cohost/shim.cljc` and
`src/cohost/adapter.cljc`.
1. **Push Step 1 now**, as it stands (`jj git push` of the change to its own branch, e.g.
   `muse/jank-step1`, if it is not ready for the main branch). Then finish it: exception class
   names, reduce over an eduction, the reader for `read-string`/edn, a clean libraries run, the
   two Character host facts. Done-bar: jank-FAST Step 1's.
2. Step 2: the full bench baseline and the runtime profile (the boot's split and profile moved to
   lane G, 2026-11-01, as G-8: it feeds Step 6 stage 2). Push the numbers
   (doc/PERF.md, doc/perf/jank.md) before starting Step 3: lane G reads them.
3. Step 3: the cheap adapter/shim wins (type tests by tag, the C++ instance guard, capability
   lookups, the syntax-quote metadata pass).
4. Step 4: natives for jank.

## Lane G (grok): type hints, startup, upstream

Owns new files and the shared-layer pieces named below. Touches `jank.jank` only by adding a
key to the adapter map; announce it in the commit message so lane M can rebase.
1. **Step 5 H0 now** (raw jank, no cohost code, no dependency on lane M): the hint microbench
   and spellings. Result: a section in doc/perf/jank.md (create it if lane M has not). If no
   case gains, stop Step 5 there.
2. **Step 5 H1/H2** (only if H0 pays): a contract capability `:native-hints` (a map from a JVM
   primitive hint to the host's type; default nil, so every other host is byte-identical), its
   use in `rewrite.cljc`'s `strip-meta`, the battery rows for mismatched arguments with
   JVM-computed expected values (add them to `battery.cljc` first, verified on `bb boot:jvm`),
   then the one key in `jank.jank`.
3. **Step 6 stage 1 now** (independent of lane M): ahead-of-time compile cohost's own
   namespaces (`jank compile-module`, `-O3`) and measure cohost's load time before and after.
   Wire it as a bb task or a documented command; no change to the boot.
4. **Step 6 stage 2** (after lane M's Step 1 lands, since a snapshot must reproduce a correct
   boot): the record mode as a capability-level hook in `boot.cljc` (default off on every
   host), the snapshot modules, their compile and load, and stale detection. Probe first, as
   the plan says.
5. **Step 7** at any time: the upstream reports with minimal repros, as markdown drafts under
   `doc/upstream/` (jank) and the three clojurust bugs from doc/HANDOFF.md. Drafts only; the
   user files them.

Lane G's machine needs babashka, the Clojure CLI (for `bb boot:jvm`) and jank:
`dev/install/jank.sh` plus babashka 1.13.229 and the Clojure CLI 1.12.6.1673 from their
GitHub releases. Installs need the user's approval.

## Review (this repo's third session)

On each push: re-run the commands the commit names on the reviewer's machine (all hosts are
installed there), compare the numbers, run the all-host regression for shared-layer changes,
and merge to `claude/youthful-euler-gquikf`. Findings go back to the lane as a short list.
