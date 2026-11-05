# #13 lane report — check #13 (macro attribution) on jank: FIXED

Lane muse/debug-seam-n13, base muse/debug-seam-native @ eef1ab4,
fix @ 2c578c1. Verdicts as printed by the existing runner, both hosts,
on the fixed tree.

## Verdicts

Jank (fresh snapshot on j, built 523,068 ms, stamp 00dd8851;
snapshot boot report: 791 forms, errors: 0):

    #13 PASS load exit 1, throw exit 1; report names the macroexpansion
    debug-seam:check jank: 8/14 PASS, 4 FAIL

JVM (local, source-booted):

    #13 PASS load exit 1, throw exit 1; report names the macroexpansion
    debug-seam:check jvm: 10/14 PASS, 0 FAIL

The JVM tally is identical to the pre-fix run on this base (10/14,
0 FAIL) -- no regression. The fixture's own stderr on jank, fixed:

    Unexpected error (ExceptionInfo) macroexpanding seam.throwmacro/m at (REPL:1).
    bad

("Unexpected error" is the port's standing wording for the phase,
cli.cljc:210, on every host -- not a jank delta.) The report EDN
carries :clojure.error/phase :macroexpansion on the outermost entry,
the cause chain down to :message "bad", and the outermost entry's
data carries :cohost/nil-message true -- the mark the jank :ex-info
cap leaves, i.e. the attributed ex-info was built by the nil-safe
construction.

Baseline accounting (j gate @ 358048e: 6 PASS / 5 FAIL / 3 SKIP):
this branch reads 8 / 4 / 2. The two moves are #13 FAIL->PASS (this
fix) and #3 SKIP->PASS (the native doors, already on this base).
The four remaining FAILs are other lanes' un-collated work, absent
from this branch by construction, unchanged in kind: #1/#6/#7
(traces fixes live on muse/debug-seam-traces) and #11 (introspect
fix on muse/debug-seam-introspect). At collation they compose.

## The mechanism (probe-bisected on the base snapshot, J)

The wrapper was never bypassed, and the native doors are not in
this path. The attribution died inside the wrapper's own handler:

1. seam.throwmacro loads; (m 1) is expanded by the codemod through
   wrap-macro; the macro fn throws (ex-info "bad" {}) -- a clean
   ExceptionInfo (probe: the macro body's own catch sees it whole).
2. wrap-macro's host/try-all catches it (object_ref clause) and the
   handler calls boot's expand-error to rethrow it attributed.
3. expand-error built (ex-info nil {...} e). In cohost's OWN
   compiled code, that plain ex-info reference reaches the host's
   native constructor -- and jank's native ex-info rejects a nil
   message (jank_host.jank's :ex-info cap comment says so in as
   many words) by throwing a std::exception: "An object of type
   `persistent_string` was expected here, but a `nil` was provided
   instead." The construction died before any attributed ex-info
   existed; the macro's error went with it (no cause survived).
4. That std::exception escaped the handler into load-source!'s
   try-all, whose :default clause converts a native exception to
   the (ex-info "a native C++ exception" {:cohost/native true})
   sentinel -- the whole report at the gate and on this base
   pre-fix: one :via entry, the sentinel, phase :execution.

Why user code never hits it: the booted ex-info routes through the
adapter :ex-info cap, which on jank stores "" for a nil message and
marks the data :cohost/nil-message (its :exception-message
translates the mark back to nil). Only cohost's own compiled code
constructs ex-info outside that cap, and expand-error's was the
only (ex-info nil ...) in src/ (grep-verified).

Bisection trail (each a seconds-long -e probe through the built
launcher): try-all catches a direct ex-info throw (P2); the macro
var is :cohost/wrapped (P4); a non-throwing macro expands fine
through the wrapped call (P13); the real wrap-macro with a hand
throwing fn reproduces the escape exactly (P17); a hand fn throwing
an ex-info that ALREADY carries :clojure.error/phase passes
through expand-error untouched and is caught cleanly (P19) --
pinning the break to the construction step; nil-message ex-info
and the attributed shape both construct fine from user code (P8,
P15), through the cap.

## The fix

src/cohost/boot.cljc, expand-error only: build the attributed
ex-info with host/ex-info* (the adapter cap's constructor) instead
of plain ex-info. On the JVM the cap IS plain ex-info, so the nil
message and the JVM report are byte-for-byte the semantics they
were; on jank the cap is the nil-safe construction. A comment at
the site names the hazard. No other file changed; no jank patch
(the operator rule); paren check 133 forms OK.

## Named remainders

- The JVM headline residual from doc/debug-seam-probes/
  n13-diagnosis.md (host CompilerException ex-data unreadable
  through the seam: java.clj exception-class lacks a
  CompilerException arm; shim token-ancestors for it omits
  IExceptionInfo) is NOT needed by check #13 on either host --
  both runners pass -- and remains a coordinator routing item for
  stock-parity of hand-probed -e body-throw headlines.
- General hazard, one line for the next author: any future
  (ex-info nil ...) in cohost's own compiled runtime code takes
  the same native-constructor wall on jank. The one existing site
  is fixed; a grep sweep found no others.
- Full battery/libraries were not re-run on this branch; the
  snapshot build's own boot report (791 forms, errors: 0) and both
  debug-seam runners are the verification. The change touches one
  construction, reached only when a macro fn throws.

---

# Unit 2 — check #9: AOT uncaught throw exits 0 (FIXED, verified on J)

## The gap (gate probes lane, measured twice on the pre-fix tree)

`bb build:jank dev/cli-demo seam.throwmain throwmain` produced a
44,572,208-byte static binary that exited **0** on an uncaught throw:
stdout was only jank's raw `#error` print of the exception; no
headline, no "Full report at:", no report EDN. The AOT entry —
build-jank's generated `cohost.app-main`, whose -main was
`(boot-from-snapshot! {}) (make-user-ns!) (apply <app>/-main args)`
— never passed through cohost.cli's report path; jank's generated
C++ main printed the escape as a value and returned 0. The launcher
path (app-main applies `cohost.cli/-main`, whose run-cli! wraps the
run in try-all → report-error! → exit 1) was already correct.

## The fix (8932728; cohost layers only)

1. **src/cohost/cli.cljc** gains a public AOT entry,
   `run-app-main! [main-ns args]`: resets the report config to the
   default the launcher gets from bin/cohost — `:report "file"`,
   `:report-dir` the host's temp directory — and runs
   `(exit! (run-cli! {:inits [] :main {:type :main :ns ... :args ...}}))`.
   The whole contract is run-cli!/run-main!'s own (the app resolves
   through load-lib!'s snapshot registry); nothing is duplicated.
   The temp dir on jank comes from a `cohost_io.temp_dir` cpp/raw
   helper beside the file's existing cpp block (TMPDIR, else /tmp);
   elsewhere it is `java.io.tmpdir`.
2. **dev/perf/jank_snapshot_emit.clj**: the generated app-main now
   requires `[cohost.cli]` (jank compiles it from repo src at build
   step 4, exactly as the launcher build already does) and ends
   `(cohost.cli/run-app-main! (quote <app>) args)`.
3. build_jank.clj is unchanged; its input key already covers both
   files, so stale executables rebuild. The probe-9 fixture
   (dev/cli-demo/src/seam/throwmain.cljc, the gate probes lane's
   commit) was taken onto this branch @ 841cb76 — without it the
   record step cannot find the app (the local build leg died at
   exactly that, before the move to J).

## Verification (J box, tree d9deefd; JVM re-check there too)

- JVM runner: **10/14 PASS, 0 FAIL** (also on this box) — cli.cljc
  loads in every session; no regression.
- Build: `JANK-EXE-BUILT .../throwmain in 407275 ms`; binary
  44,331,608 bytes.
- Throwing binary from a scratch dir, verbatim:

```
AOT_EXIT=1
-- stdout: (empty)
-- stderr:
Execution error (ExceptionInfo) at object-ref::call/ (oref.hpp:566).
kaboom from seam.throwmain

Full report at:
/tmp/cohost-1793862429787-1.edn
```

  The EDN (1,953 bytes) carries :clojure.main/message with the
  headline, :message "kaboom from seam.throwmain" and the :trace.
  The headline's first frame matches the launcher's own #1 headline
  at the J gate — parity, not a new format.
- Clean control (demo.main AOT, same route, 81,127 ms warm):
  `CLEAN_EXIT=0`, stdout `hello, JVM-USER!`, stderr empty — a
  non-throwing app is unchanged by the new entry.

## Named remainder (unit 2)

- The launcher-comparison leg of probe9-aot-throw.sh was not
  re-run for this unit: the launcher's behavior on this exact app
  is already measured (J gate #1: exit 1, headline, report EDN),
  and the AOT output now matches that shape line for line.

---

# Unit 3 — divide by zero in direct cpp/ arithmetic: MEASURED DISPOSITION

**Disposition: not guardable at cohost's layers. No code change.**
The boundary below is stated precisely, with the measurements that
establish it; a user who writes the division in Clojure gets the
guard, a user who writes it in C++ gets C++'s rules. This matches
jank's own documented stance (book, differences-from-clojure.md:
"Division by integer 0 is undefined behavior") — cohost neither
widens nor narrows it for user C++.

## What the layers emit around user C++

Nothing. The codemod's one deliberate non-rewrite is cpp/raw:
rewrite.cljc passes its text through untouched, and the adapter
compiles it into the module body as written. That is the
passthrough contract — cohost's own host caps and user native code
(httpbench's hot path) are written in it. The seam's division guard
lives in booted core's arithmetic: `/`, `quot`, `rem` check the
divisor before any native division executes (check #10 passes on
both hosts for exactly those paths).

## Measurements (J box, this branch's tree, 2026-11-05)

Probe script: doc/debug-seam-probes/probe-div0-cpp.sh (volatile
operands, so the division is a real runtime idiv, not a folded
constant).

- Plain jank, namespace-scope cpp/raw helper called via cpp/:
  divisor 1 → prints `5`, exit 0. Divisor 0 → **exit 136**
  (bash: "Floating point exception"), no stdout, no diagnostic.
- Cohost session (`bin/cohost :jank -m seam.div0`, the fixture's
  -main calling the same helper from its cpp/raw block): **exit
  136**, stdout empty, stderr only the launcher's project-compile
  notice. No headline, no report EDN, no trace — the process died
  inside the user's C++ before any seam machinery could run.
- Control, same session shape (`bin/cohost :jank -e "(/ 5 0)"`):
  **exit 1**, stderr "Execution error (ArithmeticException) at
  object-ref::call/ (oref.hpp:582)." / "Divide by zero" + report.

## Why no layer can guard it (options examined)

1. **Rewrite the user's divisions** — excluded by the passthrough
   contract, and unsound: raw text holds comments, string
   literals, float division, overloaded operators and macros; a
   textual scan would change correct C++ while missing divisions
   spelled through templates and headers. It would be a second
   C++ compiler, not a guard.
2. **A SIGFPE handler that throws** — a C++ exception cannot be
   thrown from a signal handler (async-signal context; unwinding
   arbitrary user frames is undefined). The faulting frame may
   hold runtime/GC locks; even a siglongjmp to an armed eval
   boundary abandons that state (skipped destructors, held locks)
   and trades a loud, debuggable crash — core dump, and gdb stops
   at the trap (check #12's machinery) — for silent continuation
   of a possibly poisoned process. The JVM draws the same line:
   its own JIT-compiled division traps into ArithmeticException
   because it owns that codegen and its frame maps; a div-by-zero
   in JNI/native code is process-fatal. Cohost owns its
   arithmetic's codegen the same way — hence the #10 guard — and
   does not own user C++.
3. **feenableexcept / FP environment** — governs floating-point
   traps only; integer idiv ignores it. Not applicable.
4. **Sanitizer codegen** (-fsanitize=integer-divide-by-zero) — a
   global build policy, not a layer guard: still process-fatal in
   halt mode (a sanitizer diagnostic, exit ~1), and in recover
   mode it continues past the division with an undefined quotient.
   Noted for completeness; adopting it is a build-policy decision
   for the coordinator, not this seam's.

## The boundary, for the docs

Divisions written in Clojure — `/`, `quot`, `rem`, on any host —
throw ArithmeticException ("Divide by zero"), exit 1, with the
seam's report. Divisions written as C++ (a cpp/raw block, called
through cpp/) execute as the host's native division under C++
rules: integer division by zero is undefined behavior and in
practice the CPU's #DE trap — SIGFPE, exit 136, no report. The
user's remedies are the C++ ones: test the divisor inside the raw
block, or do the division in Clojure.

Probe methodology note: in plain jank the supported raw shape is
namespace-scope (a top-level cpp/raw defining C++ functions,
called via cpp/ns.fn — the shape cli.cljc's cohost_io uses). A
cpp/raw as a defn *body* segfaulted jank's JIT in probing (exit
139, divisor-independent); that wart is jank's, is not the shape
cohost emits for user code, and is recorded here only so the next
prober does not repeat it.
