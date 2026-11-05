# Docs lane report — guide ch. 11 "Debugging" (2026-11-05)

Branch `muse/debug-seam-docs` from `muse/debug-seam` @ 030cc0a
(tip verified by `git ls-remote` before branching: 030cc0a6…).

## Files written

- `doc/guide/11-debugging.md` — new guide chapter.
  - Part 1, the Clojure-side facilities as a Clojure user knows
    them: the clojure.main error report + report file
    (`<tmpdir>/cohost-<millis>-<n>.edn`, `:report`
    file/stderr/none via `COHOST_MAIN_REPORT`), root-cause
    trace/data (F3), the report-build fallback (F8), traces as
    data (`:ex-trace`, `.getStackTrace`, stock
    `clojure.stacktrace`), `pst`, REPL `*1`/`*2`/`*3`/`*e`,
    `*err*` separation, load-time unresolved-symbol failures,
    reader errors with phase + position, real source positions
    (read end + AOT emitter placement), macro-expansion errors,
    divide by zero, exit codes.
  - Part 2, the jank pathway: `JANK_PRINT_CODEGEN` /
    `JANK_PRINT_IR`, `COHOST_JANK_PROFILE=1` → `jank.profile`,
    AOT-binary vs snapshot-launcher reporting, gdb
    break-on-throw with the gate-probes lane's exact commands
    and measured output.
  - Closing table: all 14 seam checks × JVM/jank with dated
    states.
- `README.md` — guide index gains entry 11 (the pointer).
- This report.

## Sources (the verified record; nothing written from general knowledge)

- `doc/debug-seam-impl-plan.md` Status section (per-unit sha +
  gate lines, incl. the j gate @ 358048e: jank 6 PASS / 5 FAIL /
  3 SKIP — PASS #2 #4 #5 #8 #10 #14; FAIL #1 #6 #7 #11 #13;
  SKIP #3-native, #9, #12).
- `dev/debug_seam_check.clj` — the 14 checks as the capability
  list, incl. each check's PASS needles (quoted shapes in the
  chapter are the runner's: pst header `ExceptionInfo r {:x 2}`,
  #6 first frame `[user$inner invokeStatic "NO_SOURCE_FILE" 0]`
  via the traces lane's JVM run).
- `doc/debug-seam-probes/gateprobes-report.md` on
  `muse/debug-seam-gateprobes` @ f3aafc7 — #9 GAP and #12 PASS,
  measured twice, identical.
- Lane trees read firsthand at their tips (rechecked by
  `git ls-remote` while writing): native eef1ab4 (doors +
  name discipline; J verification per its gate record:
  snapshot clean, cli:check 7/7, runner #3 PASS on that tree),
  traces 68d806b (`traces-report.md`: mechanism + JVM gates
  10/14 PASS 0 FAIL, battery 221/221; jank run queued),
  introspect 37d340c (`introspect-report.md`: shape A/B
  measurements — 0 cohost lines on the launcher shape, 1,571
  on source boot; fix = source-boot routing in bin/cohost;
  end-to-end gate verification pending).
- `doc/CLI.md` (report contract), `doc/debug-seam-research.md`
  §(b)/(c), `doc/debug-seam-probes/u7-arithmetic.md` (the
  divide-by-zero layers + the cpp/ UB remainder).

## Verification

- `bb docs:check jvm` on this branch: **does not complete on
  this box — environmental, proven not to be this change.**
  It fails at the README's own first block
  (`clojure -Sdeps … -Tnew create …` → "Error building
  classpath. Unknown tool: new" — deps-new is not installed in
  this box's environment) and the checker then crashes on the
  never-created project directory. Control run on the pristine
  seam branch (`~/workspace/cohost-debug` @ 030cc0a, no docs
  changes) fails **identically at the same command**
  (logs: `~/workspace/cohost-install/docs-check-docs-lane.log`,
  `docs-check-base-control.log`). The new chapter contributes
  zero ` ```bash ` blocks (all examples are plain/clojure
  fences), so docs:check has nothing new to execute from it;
  the README edit is one index line, not a command block.
  docs:check remains owed on a box with deps-new installed
  (it passed on the build server in earlier gates).
- A supplementary own-run of `bb debug-seam:check jvm` on this
  branch was started and killed after ~9 min with no output:
  the box was saturated by another lane's snapshot build
  (load ~6–8; the runner's first JVM session had accumulated
  9 s CPU). The chapter's JVM numbers therefore rest on the
  traces lane's banked run, and the chapter header says so
  exactly.
- Final re-read of the chapter against the impl-plan Status
  lines done claim by claim (2026-11-05); two sentences were
  corrected during it (macro `:via` detail softened to the
  shipped wrapper's measured evidence; the native tree's
  runner #3 PASS added).

## Gaps and unverified claims, as marked in the chapter

1. **#9 AOT uncaught throw — GAP** (chapter: "AOT binaries vs
   the launcher" + table): static binary exits 0, raw `#error`
   only, no headline/report file. Measured 2026-11-05.
2. **#13 macro attribution on jank — FAIL/open**: macro throws
   cross as native C++ exceptions; attribution lost.
3. **#1/#6/#7 on jank — FAIL at the 2026-11-04 gate; fix landed
   on `muse/debug-seam-traces`, JVM-verified, jank run QUEUED**
   — the chapter never states these pass on jank.
4. **#3 native half on `muse/debug-seam` — hole OPEN (SKIP)**;
   closed only on `muse/debug-seam-native` (verified there on
   the build server), not yet collated.
5. **#11 on the launcher shape — FAIL at gate** (no codegen
   dump; nothing compiles at run time); fix on
   `muse/debug-seam-introspect`, gate verification pending.
   The 1,571-line figure is a local source-boot measurement,
   labeled as such.
6. **Divide by zero via direct `cpp/` arithmetic — still
   native SIGFPE**, stated plainly (cohost cannot guard code
   it does not rewrite).
7. Per-host depth caveat stated: on a host with no stack
   trace the report's `:trace` is empty; no facility is
   rounded up to cross-host parity anywhere in the chapter.
