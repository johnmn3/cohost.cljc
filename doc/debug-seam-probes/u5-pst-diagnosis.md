# U5 diagnosis: the pst residue -- measured only on the broken
# fallback path; the fix's measurement does not exist yet

Unit U5: on jank, (require 'clojure.repl) returns nil and
cohost.repl/pst is unresolved (lane R's direct run); the
coordinator's read is a loader/refer problem under the cohost.<lib>
rename. Plus: own check #8 (*err* separation) -- done, the runner
now probes it (an in-program *err* write, an *out* write, and an
uncaught throw, judged stream by stream).

## What the record actually shows about pst

Lane R's finding (REPORTS-R, gate 1 cont.) was measured "with
cohost.cli run directly (jank -O3 --module-path target/jank-aot:src
run-main cohost.cli -- <cfg>, the CLI's fallback path)". Gate 2's
attribution table then established what that path is: the AOT/JIT
fallback path has a pre-existing defect cluster, present on
release7 (82c9b77) too, in which a user's ex-info is not caught as
an object_ref at all and its message is lost ("a native C++
exception", try-all's :default arm) -- while the launcher path
(fresh snapshot) catches it and keeps the message (:caught "r",
"kaboom" printed). "cohost.repl/pst could not be resolved" comes
from the same fallback-path session, alongside the uncatchable
ex-info. No launcher-path measurement of pst resolution exists in
either lane's reports.

## What the loader/refer layer looks like from the source

The rename machinery is complete on both load paths:

- boot/load-lib! registers (str lib) -> (str target) in the ctx
  :renames on the source path (boot.cljc, load-lib!), and
  snap-lib-end! registers the same on the snapshot path, so the
  rewrite renames clojure.repl -> cohost.repl in user code either
  way (rewrite.cljc's *ns-renames* lookup).
- require! loads via load-lib! and refers/aliases the target ns
  through the host's refer machinery; refer-lib! special-cases only
  the core.
- (require ...) returning nil is stock behavior, not a symptom.

On the JVM host the same code resolves cohost.repl/pst in the
runner's REPL session (check #7 measures pst's own output there).
So a rename-layer defect that fires only on jank's fallback path,
in a session where even (catch Throwable e ...) cannot catch a
user ex-info, is more parsimoniously read as part of that path's
undiagnosed defect (R's candidates: the -O3 JIT of cohost.cli, or
a C++ type other than object_ref thrown from JIT code) than as a
loader/refer bug. Changing the loader/refer layer blind, without a
launcher-path measurement showing the gap there, would be fixing
at a guessed layer.

## What would settle it (at the j gate, or on any jank that launches)

1. Runner check #7 on the launcher path (bin/cohost :jank in
   dev/cli-demo, fresh snapshot): pst's own output shapes are now
   the criterion (F5), so a PASS there closes the question; a FAIL
   with "could not be resolved" re-opens it on the right path.
2. If it fails there: in that session, print
   (cohost.boot/lib-report) after (require 'clojure.repl) -- the
   per-form :error entries for clojure.repl and clojure.stacktrace
   name the exact forms whose evaluation failed (stock loads
   tolerate and record form errors), which pins the layer (a form
   that errors at load vs a var that never interns vs a refer that
   drops it).

Local status: neither can run here -- bin/cohost :jank does not
launch on the collated tree at all (snapshot-key analyze fails on
shim.cljc's U3(b) wrap-jank-error; see the F2 status line), and
this box only ever took the fallback path even before that.
