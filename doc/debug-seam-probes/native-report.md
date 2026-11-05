# Native-escape lane report (U3(b) reopened: the catch doors, second design)

Branch muse/debug-seam-native. Base muse/debug-seam @ 030cc0a.
Commits: a185cd5 (doors), eef1ab4 (name discipline), 4f40abe + 005e159
(doors-once dedup + its paren repair), 6169ed7 (try-all std arm).

## The mechanism, pinned (plain-jank probes, jank 0.1)

Probe scripts and outputs: ~/workspace/cohost-install/native-probes/
(p1..p6, run with `jank run`; the cohost-side shapes were re-measured
through the runner and the snapshot battery below).

1. A user ex-info thrown at RUNTIME crosses as an object_ref and is
   caught by the folded catch-all: not the problem (p3:
   `:p3-oref kaboom`, exit 0; the j gate's launcher probe agreed:
   `:caught kaboom`, exit 0).
2. A genuine std::exception crosses as itself. `(catch
   cpp/std.exception e ...)` with `(cpp/.what e)` works in plain jank
   (p1: `:p1-std-caught boom`, exit 0).
3. An ex-info thrown by a MACRO FN during compilation does NOT cross
   as a std::exception and NOT as an object_ref. Jank's compiler wraps
   it at the macroexpand boundary into a `jank::error` (error_ref)
   whose message is the thrown object's printed `#error {...}` form
   (p4: caught by the error_ref clause; the message contains the full
   via-trace, `jank::runtime::context::macroexpand1` frames included).
   This is the crossing that defeated check #13 and the one the first
   door design chased as a "native escape".
4. What adapter try-all's :default arm receives: for a jank error, the
   error_ref translation already existed there (in-clause, via the
   cohost_err cpp/raw namespace); for a std::exception, an ex-info "a
   native C++ exception" with no what(). Neither helps a USER try: the
   folded catch clause catches object_ref only, so both native shapes
   pass a user try by.

## The doors (src/cohost/rewrite.cljc, rw-try)

On a cpp host only, two sibling clauses are emitted ONCE, after all of
a try's folded clauses:

    (catch cpp/jank.error_ref n
      (let* [e (cohost.shim/new-ExceptionInfo
                 (cpp/.-message (cpp/.get n))
                 {:cohost/class 'clojure.lang.Compiler$CompilerException})]
        <the try's own dispatch chain>))
    (catch cpp/std.exception n
      (let* [e (cohost.shim/new-ExceptionInfo
                 (cpp/.what n)
                 {:cohost/class 'java.lang.Exception})]
        <the try's own dispatch chain>))

Design constraints, each one a measured failure of the removed doors:

- Member access in the clause ((cpp/.-message (cpp/.get n)),
  (cpp/.what n)): compiles against jank/std types alone. No seam cpp
  namespace is named (the f03e517 break: cohost_shim_err is visible in
  no other module, so runtime compilation in the snapshot launcher
  died), and no caught value crosses a fn boundary (the a01bc15 break:
  analyze/invalid-cpp-call -- a caught C++ value passed as an argument
  boxes to object_ref and matches no typed overload). The naive
  (cpp/.-message n) fails analysis ("There is no `message` member
  within `jtl::ref<jank::error::base>`"); error.hpp's base HAS the
  field -- through the ref's .get() it reads (p2a, p6: translated,
  message intact).
- The constructor is the shim's qualified new-ExceptionInfo: plain
  ex-info does not resolve in every emitted context (snapshot record,
  measured: deref-future analyzes before ex-info is interned; the shim
  module is below stock core). The class symbol rides in the data map
  as a quote form: a bare symbol there is analyzed as a class
  reference (record errors on parse-long/parse-double, measured).
  First door shape's record: 3 boot errors; fixed shape: errors 0.
- Doors are emitted ONCE, last, and skip any type the user already
  catches natively: a duplicate catch type is a jank analyze error
  ("Each `catch` form must specify a unique exception type"). First
  shape emitted doors after every folded run: battery rows 271/272
  (a cpp catch standing as a sibling clause; a stock catch folding
  beside one) failed with exactly that error -- battery 217/221 with
  the doors vs the standing 219/221 (the 2 Character rows) without.
  The end-position chain folds ALL the try's JVM clauses together, so
  a clause in a later folded run still matches a translated native.

## The top-level path: try-all's std::exception arm (adapter.cljc)

No user try exists around a -main invocation, so the doors cannot
help there: cohost's own catch tower is adapter/try-all. Its jank
branch caught object_ref and error_ref and let everything else fall
to a :default arm that reported "a native C++ exception" with the
message discarded. Measured (the -m seam.throwmacro report trace,
before the arm): the macro fn's throw during the CLI's AHEAD COMPILE
of the project's namespaces crosses as a std::exception-family value
-- not an error_ref -- into try-all's :default arm
({:cohost/native true}).

6169ed7 adds one arm between the error_ref arm and :default:

    (catch cpp/std.exception e
      (handler (ex-info (cpp/.what e)
                        {:cohost/class 'clojure.lang.Compiler$CompilerException})))

After the arm, the same run's headline is "Execution error
(Compiler$CompilerException)" and the report's :via head carries the
true message: "An object of type `persistent_string` was expected
here, but a `nil` was provided instead." That is the attributed
error's own nil message throwing inside the attribution -- the
wrap-macro layer's defect (the macro lane's nil-safety fix is on
their branch, not in this lane's base). The arm's work is exactly
that it makes this visible: what was masked as "a native C++
exception" is now the real error, in the real class. Check #13's
runner verdict on this tree still fails on that nil-message defect;
the native escape itself no longer exists at any layer: user tries
(the doors), the top level (this arm), the REPL/eval path (error_ref
door + wrap-macro).

## Verification

- Plain-jank probes p1-p6: all exit 0, outputs as quoted above.
- JVM (door tree): debug-seam:check jvm 10/14 PASS, 0 FAIL;
  cli:check jvm 7/7 SAME -- identical to the collated tree (door
  emission is gated on (host/cpp?); off-jank output is unchanged).
- Snapshot record (door tree): booted as stock 514, from host 171,
  natives 28, skipped 78, errors: 0.
- Snapshot AOT: every module (core, shim, boot, adapter, cli, hosts,
  spec, ...) compiles with the doors in the sources -- no
  analyze/invalid-cpp-call anywhere; the a01bc15 failure mode does not
  recur.
- Seam checks on the door tree (bb debug-seam:check jank, snapshot
  key 2269cfb0): 7/14 PASS, 5 FAIL, 2 SKIP. #3 PASS -- the native
  catch prints :caught "boom" (pre-doors it reported SKIP with the
  hole named). Remaining FAILs: #1/#6 (host frames lead; traces
  lane), #7 (pst needle shape; traces lane), #11 (introspection
  lane), #13 (the escape is closed by the try-all arm; the runner
  still fails #13 on the attributed error's nil message -- the macro
  lane's layer, see above). SKIPs: #9/#12 (U4c-owned).
- Final tree (6169ed7) battery and libraries (bb boot:jank):
  battery 219/221 (the two standing jank Character rows),
  libraries 62/62, record errors: 0.
- Final tree JVM gates: debug-seam:check jvm 10/14 PASS 0 FAIL;
  cli:check jvm 7/7 SAME (identical to the door tree's run; later
  commits touch only jank-gated branches).

## The launcher binary on this box (environment, not code)

This box (7 GB RAM, no swap) plus a mid-lane VM replacement made the
snapshot's launcher step the most expensive part of the verification:

- `jank -O3 compile cohost.app-main` was OOM-killed five times
  ("error: unable to execute command: Killed", clang frontend
  exit -2), always late (core.o or the final link); its driver alone
  holds ~1.3-2.3 GB with several lanes' compiles resident.
- After the VM replacement, cached jank state pinned the old image's
  GCC 13 include paths and every compile failed with
  "'concepts'/'stdlib.h' file not found" although the headers exist;
  jank's bundled clang standalone picks GCC 14 on the new image.
  Clearing ~/.cache/jank (fresh PCH) restored compilation -- the
  same stale-cache trap the positions lane found.
- Two completion shapes were used. Door tree: the build's fallback
  (compile-module) produced the full module cache; the two-cache
  invariant was restored by hand (the -O3 cache moved aside) and the
  stamp written with the computed key (2269cfb0): SNAPSHOT-FRESH,
  boot 1407 ms, battery 219/221.
- Final tree: the -O3 compile finished ALL 29 module objects +
  app_main.o and died at the link; the fallback then wrote 166 KB
  one-symbol skeleton objects (it considered every module already
  compiled), so the snapshot cache was completed from the -O3 cache
  itself (key 077aa71f, stamp verified). Its spec/alpha.o is missing,
  so fresh-path reports "objects missing" and bb boot:jank takes the
  AOT/JIT path -- which is how the final battery above ran. The
  launcher binary remains buildable wherever the box has the memory
  (the same build completes on j: SNAPSHOT_EXIT=0 in ~9 min); nothing
  in the door or arm design depends on it.
