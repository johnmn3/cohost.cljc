# #13 lane STATUS — muse/debug-seam-n13 (base: muse/debug-seam-native @ eef1ab4)

## 2026-11-05 00:31 ET — lane start, cold pickup

- Worktree ~/workspace/cohost-n13 created from eef1ab4 (doors IN this tip:
  a185cd5 rw-try native doors + eef1ab4 name discipline).
- Read: doc/debug-seam-probes/n13-diagnosis.md (fchain unit: wrapper landed
  in boot.cljc wrap-macro/expand-error; JVM headline residual = two
  host-type table gaps), dev/debug_seam_check.clj #13 probes
  (seam.badmacro load + seam.throwmacro body-throw; needle "macroexpanding"
  or "Expanded from this macro" over stdout+stderr+report files), the
  J gate line @ 358048e (impl-plan Status): #13 PASS jvm / FAIL jank,
  "macro attribution blocked by the native escape"; harness line @ 29cfac1:
  "on jank the fixture's macro throw crosses as a native C++ exception".

## Mechanism read (code, this tree)

- Expansion path on jank: rewrite calls ctx :expand-macro =
  jank_host.jank's expand-macro, which applies (var-get v). User macros
  are wrapped by boot's scan-macros! (macro-wrap-style :root, the adapter
  default; jank does not override) -> var root is wrap-macro's fn.
- wrap-macro calls the macro fn under host/try-all (adapter.cljc). The
  jank arm of try-all catches cpp/jank.runtime.object_ref (a thrown
  ex-info IS one) and hands it to the handler -> expand-error attaches
  {:clojure.error/phase :macroexpansion :clojure.error/symbol ...}.
  On paper the attribution should now survive; the doors do not touch
  this path (they are in rw-try's lowering of user try forms).
- OPEN QUESTION the probe must answer: where the throw actually goes
  native on jank -- candidate seams: (a) the fixture macro's throw is
  never under wrap-macro (jank's own analyzer expands (m 1) instead of
  the codemod), (b) try-all's compiled form in the snapshot loses the
  object_ref catch, (c) attribution attached but the report/triage on
  jank drops it before the needle text is produced.

## Probe vehicle

- ~/workspace/cohost-native (same tip eef1ab4) has a snapshot build
  RUNNING NOW (bb boot:jank-snapshot, compile-module cohost.snapshot
  -O3 in flight at lane start). Not mine; do not disturb. When it
  lands, run seam.throwmacro + seam.badmacro through that launcher --
  REPL-scale verdict on the first question.
- J box: takeover lane active there (introspect + traces); my full
  jank runner run queues behind it.

## Next

1. JVM ground truth: bb debug-seam:check jvm (expect #13 PASS; no regress).
2. Probe on the native snapshot when built.
3. Fix in cohost layers only, if a fix is needed.

## 2026-11-05 00:41 ET — probe vehicle reassessed; J queue position known

- The cohost-native snapshot build: its LAUNCHER compile was OOM-KILLED
  on this box (native-snapshot3.log: "clang frontend command failed
  with exit code -2"; the script fell back to compiling the modules
  alone). Same wall the introspection lane (x4) and gateprobes lane
  hit locally. Local snapshot = dead end; J is the probe vehicle.
- J box (TAKEOVER-STATUS.md, read 00:41 ET): Remainder 1 DONE --
  introspect snapshot built on J in 524,475 ms, #11 PASS; full runner
  7/14 PASS / 4 FAIL / 3 SKIP, "only #11 moved vs the 358048e gate"
  (so #13 still FAILs on jank on the introspect tree, which has NO
  doors -- consistent with the wrapper alone not fixing jank).
  Takeover has switched to the traces tree (Remainder 2, snapshot
  building). My jank run queues behind Remainder 2.
- Code read since pickup (all in-tree): load-source! keeps the FIRST
  failure's original exception object and load-lib! rethrows it
  untouched -> the attributed ex-info, if produced, reaches the CLI
  report layer intact. cli throwable->map takes :phase from the FIRST
  :via entry's booted-ex-data. So the jank break is at or before
  wrap-macro's handler: (a) jank's analyzer expands (m 1) instead of
  the codemod, (b) try-all's object_ref catch misses the macro throw
  in the snapshot's compiled boot (falls to the :default native
  sentinel), or (c) booted-ex-data reads nil for the attributed
  ex-info on jank.
- JVM check running detached (log cohost-install/n13-jvm-check.log).
