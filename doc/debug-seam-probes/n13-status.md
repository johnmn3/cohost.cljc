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
