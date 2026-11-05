# U3 diagnosis — why an unresolved symbol loads silently through the seam
# (rows #4, #12; checks #3, #13) — banked 2026-11-04, lane muse/debug-seam-analysis

## Verdict

The deferral is NOT in jank and NOT in the codemod. It is the seam's
library loader: `cohost.boot/load-source!` evaluates each top-level
form of a loaded file inside `host/try-all`, records a failure as an
`:error` entry in the library's load report, and goes on — a tolerance
designed for stock libraries on imperfect hosts, applied unchanged to
the user's own project namespaces. Jank's analyzer interns the failed
defn's var before the body fails, so later forms analyze against an
unbound var; calling it throws a native C++ exception, which no catch
the stock libraries can write will hold (the catch hole, part (b)).

## Depth 1 — plain jank, no cohost (probed 2026-11-04, jank 0.1)

Probe files: ~/workspace/cohost-install/analysis-probes/u3-depth1*.jank
(run with `jank run <file>`; outputs alongside as .out).

- File that only DEFINES `(defn boom [] (this-var-does-not-exist))`:
  `:before-defn` prints, then **analyze/unresolved-symbol** — "The
  symbol `this-var-does-not-exist` could not be resolved", caret at the
  symbol, "^ Expanded from this macro." (the defn), book URL — exit 1.
  Plain jank refuses at load.
- `(eval '(defn boom [] (this-var-does-not-exist)))` from inside a jank
  program: the same analyze error escapes a
  `(catch cpp/jank.runtime.object_ref ...)` clause — jank's analyze
  errors are native C++ exceptions, not runtime objects.
- The same eval with the form built at runtime (no quote), caught by
  `(catch :default e ...)`: **caught** — `:eval-caught-default`, exit
  0. So plain jank's eval also analyzes eagerly, and the analyze error
  is catchable only by jank's catch-everything spelling (which binds
  the binding to nil), never by a class catch.

So: jank analyzes eagerly on both its run and eval paths, and its
diagnostic machinery (row #12's "Expanded from this macro" included)
works whenever analysis runs. The seam's silence is the seam's.

## The seam's pipeline (from source)

1. `boot/eval-str` → `eval-top` → `eval-in*`: codemod
   (`rewrite/rewrite`), then `host/eval-form` → adapter `:eval`
   (adapter.cljc:319, plain `(eval form)`; the jank adapter does not
   override `:eval`).
2. The codemod hands the form over untouched. Measured on the JVM
   host (boot + rewrite of the probe form, probe
   u3-rewrite-probe.clj): `(defn boom [] (this-var-does-not-exist))`
   rewrites to itself; the def-expansion shape rewrites to itself.
   And through the seam on the JVM host the same eval-str **threw**
   "Syntax error compiling at (...)" — JVM load-time parity, same
   pipeline. The difference is purely the load path below.
3. Namespace loads (`require`, and the CLI's `-m`/`-X` main namespace)
   go `load-lib!` → `load-source!` (boot.cljc): "Every top-level form
   of a library, read and evaluated one at a time … **a form that
   fails is recorded and the load goes on, as the core boot does**"
   (its own docstring). The recorded entry keeps only
   `(error-message e)` — a flattened string — and nothing on the CLI
   path prints the library report. Hence exit 0, silent.
4. `snap-declare!`'s docstring records the companion fact: "jank's
   analyzer interns a def's var before the body fails: later forms
   refer to that var". So the failed defn leaves an interned, unbound
   var; a `-main` whose body calls it analyzes cleanly, loads, and at
   call time jank throws a native C++ exception from the unbound var.
5. Scripts, `-e`, `-i`, stdin and the REPL do NOT take this path —
   they go through `eval-str`, whose per-form route has no swallow.
   Prediction (to confirm when B1 lands): seam eval of the bad defn
   refuses at eval time, like depth 1; only namespace loads defer.

## The catch hole (part b, from source)

- User `try`: `rewrite/rw-try` folds every JVM-class catch clause into
  ONE clause catching the adapter's `:catch-all` — on jank,
  `cpp/jank.runtime.object_ref` (jank_host.jank:2338) — with an in-body
  dispatch (`catches?` / `instance?`, else rethrow). A native C++
  exception is not an object_ref, so it never enters the clause.
- Cohost's OWN code already has the translation, in
  `adapter/try-all`'s jank branch: catch object_ref; catch
  `cpp/jank.error_ref` → ex-info with the message extracted by the
  `cohost_err::message` cpp/raw helper, `:cohost/class
  clojure.lang.Compiler$CompilerException`; catch `:default` (jank's
  C++ catch-all) → ex-info "a native C++ exception" `{:cohost/native
  true}` (message lost — std::exception's `what()` is not extracted).
- Fix shape: in `rw-try`'s fold, on `:cpp?` hosts, append sibling
  clauses after the folded catch-all clause that run the same dispatch
  chain with the exception translated first:
  `(catch cpp/jank.error_ref e (let [e (shim-translate-error e)] chain))`
  and `(catch cpp/std.exception e (let [e (shim-translate-std e)]
  chain))`, where the translators live in the shim (the layer emitted
  code may name) with jank implementations in the adapter's cpp/raw
  idiom — error_ref via `cohost_err::message`; std::exception via
  `what()`, wrapped as an ex-info whose `:cohost/class` is
  `java.lang.Exception` (error_ref keeps Compiler$CompilerException,
  as try-all has it). A `(catch Throwable ...)` clause's dispatch test
  is literally `true` (rewrite's catch-all-classes), so a translated
  exception lands in the user's Throwable clause with its message
  intact; a specific-class clause that doesn't match rethrows the
  translated (now ordinary) exception instead of the native one.

## Fix shape for part (a) — at the layer named above

In `load-lib!`/`load-source!`: a form failure in a NON-stock library
(one whose source path is not under boot's `:root`, the stock root —
project roots are registered separately via `add-source-root!`) is
captured as the exception object (first one wins), and rethrown when
the load completes, so the load fails with jank's original diagnostic
object — its caret box renders at the CLI's top level, not the
flattened `(error-message e)` string the report records. Stock
libraries keep the record-and-continue tolerance exactly as today.
Nested loads: an inner user lib's rethrow surfaces as the outer form's
failure and rethrows at the outer level too; the object stays the
original.

## Depths 2/3 — seam confirmations pending B1

Blocked, precisely: on this branch jank_host.jank still carries U1's
`dynamic_object_cast` (B1 is lane M's uncommitted edit; do not touch),
and locally the run dies even earlier — `bin/cohost :jank -e '(+ 1 2)'`
in dev/cli-demo (2026-11-04, this branch @ 5039590) exits 1 in
`dev/perf/jank_snapshot_key.clj`: "jank: no module path from
dev/perf/jank_snapshot_key.clj: … jtl/primitive.hpp:45:14: error:
redefinition of 'platform'" — jank's headers are reached under two
path identities (`/home/hatch/opt/jank-root/usr/local/lib/jank/0.1/…`
and the baked `/usr/local/lib/jank/0.1/…`), so the snapshot-key compile
fails before any cohost source compiles. JVM-side gates all stand
(see the step-0 commit). When B1 lands on muse/debug-seam, merge it
in and run: depth 2 = `bin/cohost :jank -e` with the defn (predict:
refuses at eval); depth 3 = the sp2 shape as a project (predict:
silent 0 today, load failure after the fix), plus the call-in-try
half (predict: catch fires after part (b), with the message).

## Addendum (2026-11-04, post-B1): U3(b) outcome -- doors measured, removed; hole OPEN

U3(a) is verified on jank: `-m seam.bad` exits 1 at load with "The
symbol `this-var-does-not-exist` could not be resolved." (the report
path, not a deferred call-time throw).

U3(b)'s catch doors shipped in two shapes; both were measured
against real jank compilation and removed:

1. **Translation behind a fn boundary** (a01bc15: the clauses called
   shim fns taking the caught C++ value). The AOT build dies in the
   shim itself: `analyze/invalid-cpp-call` -- a caught value passed
   as a fn argument arrives boxed as an `object_ref` and matches no
   typed overload (`error_message(jank::error_ref)`). try-all never
   crosses that boundary; it calls its helper inside the clause.
2. **Message read in the clause** (7cb29ec + recording gate
   f03e517). Compiles in a source-boot (JIT) session, and the
   snapshot record compiles clean with the doors gated out of
   recordings (record errors: 3 -> 0). But in the snapshot
   LAUNCHER's process, a runtime-compiled stock try fails: "Unable
   to find `cohost_shim_err` within the global namespace" -- the
   shim arrives AOT-compiled, its cpp/raw was never evaluated in
   that JIT session, and the namespace is visible only where a
   cpp/raw declaration has been evaluated in-session. The doors
   thus broke the default path's runtime compilation: worse than
   the hole. Removed (the removal commit); rw-try is the plain
   fold everywhere again.

The lead for whoever closes it: read the message by MEMBER ACCESS
on the caught value -- `(cpp/.-message e)` for an error_ref,
`(cpp/.what e)` for a std::exception -- which compiles against
jank/std types that are always visible, not against a
seam-declared namespace. Unprobed in this codebase's idiom.

Lane R's sharpening, attributed: the "a native C++ exception"
message is adapter/try-all's `:default` arm (adapter.cljc), code
this lane never touched and present on release7. Reproduced live
on this branch's tree, 2026-11-04: `-m seam.throwmacro` on jank
exits 1 reporting exactly "a native C++ exception" -- an ex-info
thrown by a macro fn during JIT compilation crosses as a native
C++ exception, not an object_ref, so no Clojure-level catch
(try-all's, wrap-macro's, or a user's) ever holds it. The cause
between R's two candidates stays undiagnosed; the phenomenon is
release7's, not U1's and not this branch's.
