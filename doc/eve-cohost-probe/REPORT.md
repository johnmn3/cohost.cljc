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

Probe-design notes (measured): the first JVM run never exited — after
the agent/future cases, the JVM's non-daemon executor threads keep the
process alive. The program now ends with `shutdown-agents` + a guarded
`System/exit`, after printing its done line.

