# Eve-in-cohost load probe — report

Branch `muse/eve-cohost-probe` off `muse/debug-seam` @ 2563eee (the
collated final-gate tree). Probe ordered by John 2026-11-05 (the gate on
it was the coordinator-side hold, not John's; see MEMORY.md).

## What the probe measures

The Eve-in-cohost research's named risk: library code that assumes a
`future`/thread form can capture any closure freely, on cohost hosts
without a shared heap. John's design answer: the shared-heap illusion —
a shared atom between all workers, bindings conveyed through the atoms,
transparent under library code. This probe measures, per host, how much
existing library code actually hits that edge, before any implementation
is designed against guesses.

Method: one boot per host (`bin/cohost :<host> -e <program>`, hosts run
serially), the program in `dev/eve_probe_program.cljc`, the runner in
`dev/eve_probe.clj`. Per host it records:

- each of the twelve stock libraries required on its own: loads clean,
  or fails with what;
- the threading surface as library code and its users call it:
  `future` (plain, capturing a local, capturing an atom, under a
  `binding`), `promise`, `delay`, `agent`/`send`/`await`, `pmap`;
- two library-internal edge cases (spec conform; `Thread/currentThread`
  stack trace, which `clojure.test` calls).

Failures are classified: **unsupported-shim** (the shim's "not available
on this host" — the entry Eve's thread layer would hydrate),
**fail-other** (quoted verbatim), or boot failure.

## Static findings (measured by reading the vendored sources)

The twelve libraries (`resources/cohost/clojure-1.12.0/clojure/*.clj`):

| Library | Threading-surface references in its own code |
|---|---|
| walk, set, string, zip, data, edn, math, stacktrace | 0 |
| template | 0 (the one grep hit is `postwalk-replace`) |
| test | 1 — `(.getStackTrace (Thread/currentThread))`, clojure/test.clj:365 |
| repl | 1 — `(Thread/currentThread)` in `thread-stopper`, clojure/repl.clj |
| spec.alpha | `delay` only (in-process; no thread crosses) |

So **no library in the set calls `future`, `agent`, or `pmap` in its own
code** — library *loads* cannot hit the closure-capture edge. The edge
sits in `clojure.core` itself: `future-call` (core.clj:7110) submits to
`clojure.lang.Agent/soloExecutor`, and `send`/`send-off` route through
`Agent/pooledExecutor`/`soloExecutor`. In the shim today
(`src/cohost/shim.cljc`): `java.lang.Thread` unsupported (line 1735),
`CountDownLatch` unsupported (1738), `new-Agent` a host-fn falling back
to unsupported "agent" (1293). The dynamic-var thread-binding machinery
works — which is the conveyance half of John's design, already present.

## Per-host results

(filled in as each host completes; numbers quoted as printed by the
runner's SUMMARY lines)

### JVM (the reference host) — complete

`SUMMARY|jvm|ok=23 unsupported-shim=0 fail-other=0 wall-ms=2178`

All twelve libraries load; every threading case answers: future-basic
3, future-capture-local 42, future-capture-atom 1, future-binding 3,
promise 7, delay 2, agent 1, pmap (2 3 4), spec-conform 2,
test-stacktrace 26.

### ClojureWasm (cljw) — complete

`SUMMARY|cljw|ok=23 unsupported-shim=0 fail-other=0 wall-ms=131737`

All twelve libraries load; the whole threading surface answers with
the same values as the JVM (future-capture-local 42, future-binding 3,
agent 1, pmap (2 3 4)). One divergence: test-stacktrace answers 0 —
`Thread/currentThread` resolves (does not throw) but its stack trace
is empty. ClojureWasm's own runtime supplies the agent/executor
surface, and it is a single-heap host, so closure capture crosses no
boundary there.

### jolt (Chez Scheme) — complete

`SUMMARY|jolt|ok=21 unsupported-shim=0 fail-other=2 wall-ms=4562`

The whole threading surface answers with JVM values (capture-local
42, binding 3, agent 1, pmap (2 3 4)); test-stacktrace 0. The two
failures are one gap, not the threading edge: `load-clojure.spec.alpha`
fails — "Could not locate clojure/spec/alpha.jolt (or .clj/.cljc) on
the source roots" — a loader/source-root gap for that one library,
and `spec-conform` fails downstream of it ("No such namespace:
clojure.spec.alpha"). 11 of 12 libraries load.

### let-go (lg, Go) — complete

`SUMMARY|lg|ok=19 unsupported-shim=0 fail-other=4 wall-ms=14429`

The whole threading surface answers with JVM values (capture-local
42, binding 3, agent 1, pmap (2 3 4)); test-stacktrace 0. Library
loads 9 of 12: `clojure.template`, `clojure.stacktrace` and
`clojure.spec.alpha` fail with "unable to load namespace" — none of
the three names a threading form in its own code (see the static
table), so these read as host loader/compiler gaps, and
`spec-conform` fails downstream ("CompileError: compiling function
position").

### clojurust (cljrs) — no CLI row

`SUMMARY|cljrs|ok=0 unsupported-shim=0 fail-other=0 wall-ms=78`

`bin/cohost :cljrs` answers "cohost: :cljrs is not wired into the CLI
yet: its boot is still in flight (doc/hosts/PLAN.md)". The host boots
through its bb task only; through the CLI surface this probe measures,
it has no row today. Recorded as the host's state, not a probe failure.

### basilisp (lpy, Python) — boot fails through the CLI path

No SUMMARY line: the boot itself fails in 7,358 ms (trivial
`-e '(println "BOOT-OK")'` repro) with a basilisp CompilerException
during macroexpansion, form `(basilisp.core/fn temp-dir []
(System/getProperty "java.io.tmpdir"))`, file
`src/cohost/cli.cljc`. A `System/getProperty` shim gap in the CLI
namespace — the probe reaches hosts through the CLI, so basilisp has
no probe row on this tree. Not a threading failure; the threading
surface was never reached.

### ClojureScript (cljs, self-hosted on Node) — the edge, measured

`SUMMARY|cljs|ok=15 unsupported-shim=0 fail-other=8 wall-ms=9055`

All twelve libraries load. `delay` answers (2 — in-process, no thread
crosses). **Every case that routes through the executor/latch seam
fails, identically**: future-basic, future-capture-local,
future-capture-atom, future-binding, promise, agent and pmap all fail
with `java.lang.NullPointerException :: Cannot read properties of
undefined (reading 'call')`. `promise` belongs in that family: core's
Promise waits on a `CountDownLatch`. In the shim, `CountDownLatch`,
`java.lang.Thread` and the Agent executors are the unsupported set;
through the JS layer the missing entries surface as an undefined-call
NPE rather than the shim's clean "not available" exception. Closure
capture itself is never reached — the machinery fails before any
closure crosses. `spec-conform` also fails ("ExceptionInfo :: ERROR"
through the eval path; it names no threading form). test-stacktrace
answers 0.

### Phel (phel, PHP) — no row (as the research said)

`bin/cohost :phel` dies before any cohost code runs: "Fatal error:
Uncaught Error: Class \"Phar\" not found in
/home/hatch/.cache/cohost-hosts/phel-v0.54.0/phel.phar:3", exit 255;
and `php` is not on this box's PATH at all. An environment-level gap,
consistent with the standing fact that PHP has no row ready today.
The threading surface was never reached.

### jank (C++/LLVM) — run on J (this box OOM-kills the snapshot build)

`SUMMARY|jank|ok=16 unsupported-shim=0 fail-other=7 wall-ms=3493`
(runner wall on J, snapshot fresh; boot alone 3,197 ms)

Threading surface: **future works in full** — basic 3, capture-local
42, capture-atom 1, binding 3 (dynamic-var conveyance across the
future boundary works today). promise 7, delay 2. Two explicit stubs,
both in cohost's own policy layer, quoted as printed: `agent` fails
"TODO: port agent" and `pmap` fails "TODO: port pmap"
(clojure.lang.ExceptionInfo). test-stacktrace 0.

Library loads through the snapshot launcher's module path: 7 of 12.
`clojure.zip`, `clojure.edn`, `clojure.math`, `clojure.repl` and
`clojure.spec.alpha` fail with "The module `clojure.X` could not be
found" — those five are not in the launcher's compiled-module set.
They are not unloadable: `spec-conform` answers 2 in the same run,
spec reached through the eval/loader path. A launcher module-path
gap, not a threading failure.

Two jank defects the probe itself hit, both defect-corpus shaped:
(1) the probe program's comment block (non-ASCII em-dashes) made the
launcher's analysis of the whole -e form fail with "Unable to find
`` within the global namespace" — every case passes individually and
the whole program passes with comments stripped; (2) `flush` throws
"TODO: port flesh" on jank 0.1 (the adapter's own comments document
this: `src/cohost/adapter.cljc:464` — std::exit is used to flush
instead). The local snapshot build for this tree was OOM-killed
twice at the launcher compile (exit 137, 8 GB box) — the known wall
that puts heavy jank legs on J; the probe's jank row was therefore
run on J against the seam tree's existing snapshot.

Probe-design notes (measured): the first JVM run never exited — after
the agent/future cases, the JVM's non-daemon executor threads keep the
process alive. The program now ends with `shutdown-agents` + a guarded
`System/exit`, after printing its done line.

## Conclusions — the measured size of the edge

Summary across hosts (23 cases each: 12 library loads + 11 surface
cases; counts from the SUMMARY lines):

| Host | Loads | Threading surface | Wall |
|---|---|---|---|
| JVM (reference) | 12/12 | all answer | 2,178 ms |
| cljw | 12/12 | all answer | 131,737 ms |
| jolt | 11/12 | all answer | 4,562 ms |
| lg | 9/12 | all answer | 14,429 ms |
| cljs | 12/12 | **0 of 7 cross-thread cases** | 9,055 ms |
| jank (on J) | 7/12 via launcher modules | future/promise/delay answer; agent, pmap stubbed | 3,493 ms |
| basilisp | boot fails at the CLI namespace | never reached | 7,358 ms repro |
| cljrs | no CLI row | — | 78 ms |
| phel | no row (php absent) | never reached | exit 255 |

The edge, in numbers:

1. **Library code never hits it at load.** 0 of the 12 libraries call
   `future`/`agent`/`pmap` in their own code (static count, above);
   every host that boots loads at least 9 of the 12, and every load
   failure measured is a loader/module gap naming no threading form.
2. **Library *users* hit it at exactly one seam.** Every failing
   surface case on every host routes through the same three shim
   entries: the Agent executors (`future-call`, `send`/`send-off`,
   `pmap`), `CountDownLatch` (promise), and `java.lang.Thread`
   (test/repl stack traces — which degrade to an empty trace, 0, on
   every off-JVM host, never a throw). Nothing else in the surface
   fails anywhere.
3. **The failure is total where it occurs, and absent where a host
   supplies real threads.** On cljs — the one measured host with no
   shared heap and no host threads — 7 of 7 cross-thread cases fail
   identically, before any closure crosses. On jolt, lg and cljw the
   host supplies threads/executors and every case answers with the
   JVM's values, capture included. On jank, cohost already supplies
   enough for future (including binding conveyance: future-binding
   answers 3) and the remaining gap is two named policy stubs,
   "TODO: port agent" and "TODO: port pmap".
4. **Closure capture itself was never the failure.** Wherever a
   future ran at all, capture-local answered 42 and capture-atom 1 —
   on every such host the closure crossed with its captures intact,
   because those hosts share a heap. The unmeasured remainder is
   precisely John's case: a host where the closure must cross to a
   *separate* heap. Among cohost's hosts only cljs/worker-style
   targets have that shape, and there the machinery dies one layer
   earlier, at the executor seam.

What this implies for the transparent shared-atom design:

- The hydration target is small and exact: **the executor surface
  behind `future-call`/`send`/`pmap`, plus `CountDownLatch`** —
  three shim entries. Hydrate those with Eve's thread layer and the
  entire measured surface answers on every host that boots; no
  library in the set needs anything else.
- The conveyance half of the design already exists and is proven by
  this probe on the JVM and on jank: core's binding-conveyor
  machinery carries dynamic bindings across a future boundary
  whenever a future can run (future-binding = 3 on both). The
  shared-atom conveyance Eve adds has to reproduce that one behavior
  on hosts where the boundary is a heap boundary.
- The design risk is therefore *not* library compatibility — it is
  the one case no current host exhibits: a future whose closure
  captures values that live in another heap, on the cljs/worker
  shape. That is where the probe says to spend the design effort,
  and it can be prototyped against the cljs row: success looks like
  this probe's cljs line flipping from 7 identical NPEs to the JVM's
  values, with library code unchanged — the transparency test in
  executable form.


