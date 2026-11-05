# F9 diagnosis: REPL *e display names -- no shim print seam in the *e path

Unit F9 (coordinator addendum 645d12a, re-scoped to display-names only):
route the host's #error printer / the shim's print-method for errors
through shim/display-class-name so `cohost.core` reads `clojure.core`
in REPL *e printing; the trace printed is :ex-trace's (F2). Lane R's
finding (REPORTS-R, gate 1, finding 8, verified on jvm): *e's :trace
keeps `cohost.adapter$ex_info_STAR_` and `cohost.core$ex_info` frames;
neither U1's elision nor U8's names reach a printed exception.

Measured on this branch (2026-11-04), three hosts, three printers --
and in none of them does a cohost-owned print-method for errors sit
in the *e path:

1. **JVM host** (probe: f9-probe.sh, REPL `(inner 3)` then `*e`):
   *e prints the full #error map; :via :at is
   `[clojure.lang.AFn applyToHelper "AFn.java" 156]` and :trace keeps
   the construction head (`clojure.core$apply`,
   `cohost.adapter$fn__501`, `cohost.adapter$ex_info_STAR_`,
   `cohost.core$ex_info`). The head is NOT elided, which proves the
   print did not pass through the adapter's :ex-trace (the JVM impl
   elides exactly those frames): the printer is the host JVM's own
   print-method Throwable over real getStackTrace data. The names it
   prints are the classes' true JVM names; real Clojure prints its
   own construction frames the same way (F9's "full raw trace, no
   further elision"). There is no cohost layer in this path to route
   through display-class-name.

2. **cljw host** (probe: f9-probe-cljw.sh, same REPL input): *e
   prints `#error{:message "r" :data {:x 3}}` -- ClojureWasm's native
   ex-info rendering, no :cause/:via/:trace at all. The booted
   core_print's print-method Throwable does not claim the host's
   exception value. That is a print-dispatch gap (does the booted
   core's print-method for Throwable dispatch over host exception
   types), a different mechanism from display-name routing.

3. **jank host**: unmeasurable locally -- bin/cohost :jank does not
   launch on the collated tree (snapshot-key analyze fails on
   shim.cljc's U3(b) wrap-jank-error; see the F2 status line). What
   can be said from the code: where the booted core_print DOES print
   *e over shim statics, frame class names already pass through
   shim's -getClassName, which has display-mapped them since U8, and
   the trace is :ex-trace's, whose head F2 now elides. Lane R's
   gate-2 sandbox run had runner #5 PASS on jank (the *e #error print
   with :cause), consistent with that path already carrying display
   names.

Disposition: no code change. The named layer (a shim print-method
for errors in the *e path) does not exist on any measured host; the
JVM instance is the host's own printer printing true class names,
and the cljw instance is a print-dispatch question for the
coordinator to scope as its own unit if wanted. If the coordinator
wants the JVM REPL's printed *e display-mapped regardless, the only
route is intercepting value printing in cli.cljc's repl! -- a layer
the addendum did not name, so it is not taken here.

Side observation (not this unit): on cljw, repl-report! prints
"Execution error (ExceptionInfo) at (REPL:1)." -- no user/inner
symbol, where the JVM prints "at user/inner (REPL:0)". The triage's
:execution symbol comes from the first non-core :ex-trace frame;
cljw's frames evidently name it differently.
