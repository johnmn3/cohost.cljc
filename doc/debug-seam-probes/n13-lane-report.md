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
