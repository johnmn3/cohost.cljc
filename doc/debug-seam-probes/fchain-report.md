# F-chain + #13 + U5 -- lane M declaration

Branch muse/debug-seam-fchain, base muse/debug-seam @ a127a19.
All units committed and pushed; every push verified on the fetched
remote ref. Gates are JVM-host numbers from this box unless said
otherwise; the chain moved the runner from 8/14 PASS, 1 FAIL to
9/14 PASS, 1 FAIL, cli:check jvm 7/7 SAME throughout, and the
chain-end bb boot:jvm stands at battery 221/221, libraries 62/62.

## F8 -- report fallback -- DONE @ a197c02 (Status b1b4288)

cli.cljc: report-fallback! prints the exception's class (through
display-class-name) and message as two lines, each fetched under
its own try-all so it cannot throw. It guards BOTH report paths:
report-error!'s terminal handler catches the whole body with it,
and repl-report! is wrapped the same way. Probes (exceptions whose
report build is forced to fail): a Throwable whose getStackTrace
throws -- before, report-error! printed nothing and repl-report!
escaped with the inner failure; after, both print class+message.
A circular ex-data through the real CLI -- before, exit 1 in total
silence with no report file; after, exit 1 with
clojure.lang.ExceptionInfo / kaboom on stderr.
Gates: check 8/14 (1 FAIL #13, standing), cli:check 7/7 SAME.

## F2 -- construction-head elision at the data layer -- DONE
## @ 55f12dd, amendment @ aaa6b95 (Status 9b8710a)

jank_host.jank: ctor-frame? over the adapter's frame strings and a
prefix table covering both naming universes (jank::, clojure_core$,
clojure.core$, cohost_core$, cohost.core$, cohost.shim$,
cohost_shim$, cohost.adapter$, cohost_adapter$) plus
object_ref:: -- jank's apply trampoline, the third head frame lane
R measured ([jank::runtime::ex_info] [clojure_core$ex_info_...]
[object_ref::call]) and the one frame the original table missed.
The jank :ex-trace now drops that head; cli.cljc carries no
elision rule; the JVM adapter is untouched. The elision logic was
verified frame-for-frame on the JVM host over lane R's measured
trace and three other cases (paren check + logic probe).
Gates: check 8/14, cli:check 7/7 SAME.
UNVERIFIED: the jank end-to-end -- bin/cohost :jank does not
launch on the collated tree (see the blocker below). Pending the
j gate.

## F9 -- REPL display names -- NO CODE, diagnosis @ 5cfc1fa
## (Status a8c8681)

doc/debug-seam-probes/f9-diagnosis.md. The unit's premise -- a
shim print-method for errors in the *e path, routeable through
display-class-name -- does not exist on any host measured. JVM:
*e prints the full #error with the TRUE trace (construction head
included), i.e. the host's own Throwable print-method, exactly
real Clojure's behavior; no cohost layer is in the path. cljw: *e
prints #error{:message ... :data ...} with no :trace at all --
ClojureWasm's native ex-info rendering; the booted print-method
does not claim host exception values (a print-dispatch gap, a
different mechanism). jank: not launchable locally; by code
inspection the trace is :ex-trace's (F2-elided) and frame names
already pass shim's -getClassName, display-mapped since U8.
The only remaining route (intercepting cli.cljc's repl! value
printing) is a layer the coordinator did not name -- not taken.
Coordinator to re-scope if wanted.

## F4-F7 -- runner hardening -- DONE @ 6d881da (Status 278c954)

dev/debug_seam_check.clj (+ new fixture dev/cli-demo/src/seam/
ok.cljc): #1 judges streams separately (stdout empty; the boom
program is one do form so -e value printing stays silent); #5
probes *1 by a value only *1 can produce (:star1 3 after (+ 1 2));
#7 judges pst's own output shapes (ExceptionInfo r {:x 2} header +
tab-indented demunged frame lines), which U1's report line matches
neither of; #14 runs all three exit cases (-e throw: 1; -X missing
fn in seam.ok: function not found + nonzero; (System/exit 3)
script: 3); every PASS line quotes its matched text, and evidence
strings claim only matches actually made (one draft of #14's
evidence printed "matched" unconditionally -- caught and fixed).
Gates: first hardened run 7/14, 2 FAIL (#13 + #14's wrong first
fixture); final 8/14, 1 FAIL (#13) -- #14 flipped, no PASS
regressed; cli:check 7/7 SAME.

## #13 -- STOPPED by the coordinator; variant reverted, diagnosis
## retained as an exhibit

This lane built an expansion-wrapper variant (landed @ 170a1ef:
boot.cljc wrap-macro rethrowing an ex-info with
:clojure.error/phase :macroexpansion, the macro symbol, and the
call form's source/line/column, cause chained -- measured in the
report's :via on the body-throw probe -- plus a runner probe with
the needle on the printed message). The coordinator then stopped
the line: the analysis lane banked its own #13 (7b3e7e3, its
runner reads #13 PASS, 8/14, 0 FAIL), and two implementations of
the unit must not reach collation. The stop arrived after the
variant was pushed; the banking commit that carries this report
reverts its code exactly -- boot.cljc is byte-identical to the
base a127a19, and the runner differs from the F4-F7 commit only
by U5's #8 hunks. The analysis lane's #13 is the implementation
of record.

Retained verbatim as an exhibit:
doc/debug-seam-probes/n13-diagnosis.md -- the variant's residual:
on the JVM host the printed headline takes its phase from the
host CompilerException's ex-data, which the seam cannot read
(hosts/java.clj's exception-class table has no CompilerException
arm; shim.cljc's token-ancestors for it omits
clojure.lang.IExceptionInfo, implemented since Clojure 1.10).
A possible follow-up refinement; the coordinator notes it does
not block the banked #13, which meets the probe definition.

## U5 -- check #8 done; pst diagnosed, no code -- @ 8c4a3a9
## (Status 4d5e0d6)

Check #8 (*err* separation) is a real probe: one session prints
to *out*, prints to *err* under a binding, and throws -- exit 1,
stdout carries only the *out* write, stderr carries the *err*
write and the report. PASS on the JVM host: runner 9/14, 1 FAIL
(#13 residual); cli:check 7/7 SAME.

The pst half is no code, with a banked diagnosis
(doc/debug-seam-probes/u5-pst-diagnosis.md): lane R's
"cohost.repl/pst could not be resolved" was measured only on the
CLI's fallback path, whose defect cluster R's gate 2 attributed
separately (release7-era: a user ex-info uncatchable there, its
message lost). The rename/refer machinery is complete on both
load paths in the source (renames registered by load-lib! and by
snap-lib-end!; require! refers/aliases the target), and pst
resolves and prints in the runner's JVM REPL session (check #7).
The measurement that would pin a launcher-path gap -- runner #7's
own-output criterion on a fresh snapshot, and the lib-report's
per-form errors for clojure.repl/clojure.stacktrace if it fails
-- cannot run locally and is named in the diagnosis for the
j gate. No blind loader change.

## Blocker that bounds every jank-side claim above

bin/cohost :jank does not launch on the collated tree at all: the
snapshot-key step dies analyzing shim.cljc's wrap-jank-error
(U3(b)), whose (cpp/cohost_shim_err.error_message e) passes an
untyped (object_ref) argument to a C++ function declared on
error_ref -- "No matching overload of the 'error_message'
function". Reproduced identically on the base tree (a127a19), so
it predates this chain; U3(b)'s own status line already flagged
its jank compile as unproven. This is outside every unit's named
layer here and was not routed around. Consequences: F2's and
#13's jank confirmations and U5's launcher-path measurement are
pending the j gate, and the same analyze error is expected to
fail the j gate's boot:jank-snapshot step on f1d87db until
U3(b)'s call is retyped.
