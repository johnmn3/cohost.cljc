# #13 diagnosis: macro attribution -- the wrapper lands, the JVM
# headline is blocked on the host-type tables

Unit 5 (coordinator addendum 645d12a): catch a throw from the macro
fn at the expansion wrapper (boot's wrap-macro) and rethrow an
ex-info carrying {:clojure.error/phase :macroexpansion
:clojure.error/symbol <macro sym> :clojure.error/source <file>
:clojure.error/line <line>}, the original error as cause.

## What landed

boot.cljc: wrap-macro's call of the macro fn runs under host/try-all;
a throw is rethrown as macro-expansion-error's ex-info (nil message;
phase/symbol always, source/line/column from the call form's meta
when present). The runner's #13 gained the unit's probe (a macro
whose body throws, via -e) beside the seam.badmacro fixture, with
per-probe needles.

## Measured (JVM host, probes n13-probe.clj, before/after)

Body-throw `(defmacro m [x] (throw (ex-info "bad" {}))) (m 1)`:

- BEFORE: report :via = [compiler wrapper "Syntax error
  macroexpanding m at (0:0).", the macro's ex-info]; triage phase
  :execution; headline "Execution error (ExceptionInfo) at user/m
  (REPL:0)." / "bad".
- AFTER: :via = [compiler wrapper, **{:type ExceptionInfo :message
  nil :data {:clojure.error/phase :macroexpansion :clojure.error/
  symbol m}}** (the wrapper's attributed ex-info, at
  cohost.boot$macro_expansion_error), the macro's ex-info]. The
  attribution is in the chain, in the named layer, on the JVM host
  too. (No :source/:line: the -e call form's meta carries neither;
  file-loaded forms carry them.)
- Headline UNCHANGED: phase :execution. throwable->map takes the
  phase from the FIRST :via entry's ex-data -- the host compiler's
  CompilerException wrapper -- and through the seam that data is
  unreadable (below), so the headline cannot use either the
  compiler's own attribution or the wrapper's.

Stock Clojure 1.12 ground truth (same programs, real clojure):
- body-throw: "Syntax error macroexpanding m at (REPL:1:45)." /
  "bad"; the CompilerException's ex-data is #:clojure.error{:phase
  :macro-syntax-check :line 1 :column 74 :source "NO_SOURCE_PATH"
  :symbol m} -- readable there.
- the fixture shape ((defn use-it [] (bad)) with bad expanding to an
  unresolved symbol): "Syntax error compiling at (REPL:1:25)." --
  NO macro attribution in stock either. The fixture's attribution
  ("Expanded from this macro") is jank's analyzer diagnostic; on
  jank the runner accepts that needle from the fixture's run.

## The residual (why the JVM headline still cannot attribute)

The seam cannot read a host CompilerException's ex-data, for two
compounding table reasons, both in host-type knowledge, neither in
the expansion wrapper nor the report layer:

1. src/cohost/hosts/java.clj's exception-class (the JVM host's
   nearest-class table) has no CompilerException entry, so
   shim/class-of flattens it to java.lang.RuntimeException (measured
   :type in every report above).
2. src/cohost/shim.cljc's token-ancestors entry for
   clojure.lang.Compiler$CompilerException (line ~234) lists
   [RuntimeException Exception Throwable Object] -- no
   clojure.lang.IExceptionInfo, which Clojure's CompilerException
   has implemented since 1.10. Token-based instance? tests against
   the flattened class therefore fail, and the booted core's ex-data
   answers nil for the one exception type that carries
   :clojure.error/phase data from the host compiler itself.

The fix, when routed: add the CompilerException arm to java.clj's
exception-class and IExceptionInfo to its shim ancestors entry.
Then booted-ex-data reads {:phase :macro-syntax-check :symbol m
...}, the triage's macro branch prints "Syntax error macroexpanding
m at (...)", and runner #13 passes on the JVM through the
body-throw probe. Both edits are one line each; they are outside
this unit's named layer, so they are NOT made here -- coordinator
to route.

## Runner state

#13 on jvm: FAIL, honestly -- bad-macro exit 1 (load refuses);
body-throw exit 1; attribution: none in the printed message. The
needle for the body-throw probe deliberately searches stderr (the
printed message), not the whole report file: the compiler wrapper's
own message ("Syntax error macroexpanding m at (0:0).") rides in
:via and would satisfy a file-wide needle without the seam
attributing anything.
