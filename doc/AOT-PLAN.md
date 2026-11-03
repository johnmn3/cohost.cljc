# Native AOT builds per runtime: the plan

**Goal:** `cohost -T:build build` should produce each runtime's own fastest ahead-of-time
form: Clojure's `clojure.core`, the libraries and the app compiled the way that runtime
compiles its own programs. Today only jank does this; the other runtimes boot
`clojure.core` from Clojure's source on every run of the artifact.

**Branch:** `aot/native-builds`, from `coord/release3` @ ab3cd28.

## The mechanism, the same for every runtime

jank's snapshot (lane D) is three stages, and nothing in them is specific to jank:

1. **Record.** Boot cohost on the runtime with record mode on, load the twelve libraries
   and the app's main namespace, and write every boot event to `events.edn`.
   Script: `dev/perf/snapshot_record.cljc`, the runtime-neutral form of
   `jank_snapshot_record.jank`.
2. **Emit.** Turn the events into one source module per namespace, in the runtime's
   dialect: `dev/perf/jank_snapshot_emit.clj --host <h>`. `--main <ns>` also writes
   `cohost.app-main`, whose `-main` boots from the modules and calls the app's.
3. **Compile.** Hand the modules to the runtime's own compiler, and ship what it makes.

`dev/perf/snapshot_run.cljc` boots from the modules (or from source), times the boot, and
runs both batteries. This is the correctness gate for each runtime's modules.

## Each runtime's native AOT mode (from its own docs) and the status here

| runtime | its fastest AOT/native mode | status on this branch |
|---|---|---|
| jank | `compile-module -O3`, statically linked | already done (`cohost.build-jank`) |
| jolt | `jolt build`: one binary. Release mode direct-links and inlines over a direct-linked `clojure.core`, with whole-program inference (unboxed numerics). `--closed-world` also tree-shakes | **done:** record, emit `--host jolt`, `jolt build` in release mode. Batteries 167/167 and 62/62 from the modules; app `run` 0.37 s |
| basilisp | compiled namespaces cached as `.lpyc` bytecode; `--inline-functions` / `--generate-auto-inlines` | **done:** ships the cached boot (`.lpy` + `.lpyc`); `run` 1.9 s, down from 19–25 s booting from source. Inline flags: to try |
| Phel | `phel build -O 2` (inlining; the default is level 0) to PHP; PHP OPcache with the tracing JIT (`opcache.enable_cli=1 opcache.jit=tracing`) | **spike, partway:** records (29 s), emits, and the core boots from the modules in 6 s against about 23 s from source (core battery 162/167; 166 from source). Libraries load in order up to `clojure.spec.gen.alpha`, which stops: see item 3 |
| let-go | `.lgb` bytecode (`lg -c`); standalone binaries (bytecode appended to `lg`); AOT that lowers IR to native Go (`ir.passes.pipeline/lower-ns-to-go`, tagged `lg` build; fib(35) 2.42 s → 0.11 s in their benchmark) | **spike blocked:** recording works (7 s, 1,323 events), but the emitted modules don't load. The recorded forms still hold special forms (`letfn*`) that cohost's lg adapter lowers only at eval time. See item 4 |
| ClojureWasm | `cljw build`, a native binary; the runtime itself as a ReleaseFast Zig build (`-Doptimize=ReleaseFast`; it ships ReleaseSafe) | **done as modules:** the artifact ships the recorded boot as cljw modules and runs `cohost.app-main` from them. Batteries 165/167 (as from source) and 62/62; boot 0.83 s against about 17 s; app `run` 0.9 s. `cljw build` of those modules: still `UnsupportedValueTag` |
| ClojureScript | Closure `:advanced` | plan only: see below |

## Items, in order

1. **jolt** — done.
2. **basilisp** — done. Next: measure `--inline-functions` and `--generate-auto-inlines`
   on the cached boot.
3. **Phel**
   - Done in the spike:
     - `snap-encode` tags regexes as `{:cohost.snap/regex src}` on every host, because
       Phel prints a regex as bare text.
     - The emitter reads Phel's `\$` string escape.
     - Phel modules get a plain `(ns …)` first in the file, the core's requires inside
       it, and each library module requires every library that finished loading before
       it (Phel has no `require` at run time).
     - `require-host!` on Phel accepts a namespace that's already loaded.
     - `snap-lib-end!` uses `last`/`butlast`, not `peek`/`pop`: Phel's `pop` is another fn.
   - **Next:** `clojure.spec.gen.alpha`'s `(defn- dynaload …)` never reaches the recording
     on Phel, so the module's later forms can't resolve `dynaload`. Phel resolves symbols
     at compile time and compiles a whole file at once, so a missing def stops the module.
     Find how the source boot treats that form on Phel, and record what it does.
   - **Next:** the core battery's 4 regressions against the source boot (162 against 166).
   - Emit `--host phel` modules. cohost's own `.cljc` already reads on Phel; check that the
     emitter's printed forms read back the same.
   - Compile them with `phel build -O 2` and ship the PHP.
   - Run under OPcache with the tracing JIT and a file cache (measured: fib(25) inside
     cohost on Phel 26 ms → 21 ms; startup unchanged at about 23 s, all of it the boot
     from source, which the build removes).
4. **let-go**
   - **Recording must capture the forms after the adapter's lowering** — what the
     adapter hands let-go's compiler — not the rewritten forms before it. Without that,
     the emitted module fails: `Can't resolve letfn* in this context` (core.clj:95,
     `cons`). jank, jolt, basilisp and cljw have these special forms natively, which is
     why their modules load as recorded.
   - First, bytecode: `lg -c` of the emitted modules. cohost.cli itself fails today with
     `unsupported value type for serialization: *vm.Ratio`. Find the Ratio constant;
     emit it as `(/ n d)` or report it upstream.
   - Then the Go lowering. It needs let-go's source and Go; their build script handles
     `ns` + `defn` fixtures only.
5. **ClojureWasm**
   - `cljw build` of the emitted modules. cohost.cli itself fails with
     `build failed: UnsupportedValueTag`. Find the value; work around it or report upstream.
   - ReleaseFast is a property of the cljw binary, not of cohost: a separate measurement.
   - **Upstream bug, found here:** when stdout is a file, cljw writes at offset 0 and
     ignores append mode. `run x >> log` overwrites the start of `log`, and three runs
     appended to one file leave one line. This hits `cohost :cljw` and anything that
     logs cljw output to a file. Report it to ClojureWasm.
6. **ClojureScript.** Emit `.cljs` modules and compile them with the JVM ClojureScript
   compiler under `:advanced`. The obstacle: cohost's macros are defined at boot, inside
   the self-hosted runtime, so the JVM compiler doesn't have them. Two ways in:
   1. emit the modules with macros already expanded (the recorded forms are post-rewrite;
      check whether they're also post-macroexpansion);
   2. compile with self-hosted `cljs.js` at build time and ship its JS output (`:none`-like,
      no Closure).

   The first gives `:advanced`; the second is simpler.
7. **No `eval` in a built app (`--closed-world`, and Closure's dead-code removal)**
   - JVM Clojure always ships `eval`; ClojureScript `:advanced` doesn't. cohost needn't
     either: an app that only calls `-main`, with every namespace recorded at build time,
     needs no compiler.
   - What keeps it today, from jolt's `--closed-world`:
     `cohost.adapter/defaults -> clojure.core/eval, resolve, intern, ns-publics, ns-interns, jolt.host/load-namespace`.
     One capability map holds all of them, so reaching the map reaches every one.
   - Two changes:
     1. Split the adapter, so the snapshot boot path reaches only what it uses.
     2. Make the replay emit direct `def`s, where it now calls `intern`/`ns-publics` at
        run time.
   - Vouching for the map instead (`:jolt/tree-shake {:allow-dynamic ...}`) would be
     wrong: the replay does call `intern` and `ns-publics`, and jolt says a wrong vouch
     fails silently, with `resolve` answering nil.
8. **The bench.** The `<runtime>-built` columns measure these artifacts. Re-run them for
   jolt and lpy (this branch) and add the results to doc/PERF.md's "Compiled builds".
9. **Docs.** Update doc/CLI.md "Building": what each artifact holds and what it needs at
   run time. **Done** for jolt, basilisp and ClojureWasm (2026-11-03); each later runtime updates its row.

## Shared changes, checked on every runtime that snapshots

The branch changes code every snapshot path uses: `snap-encode` (regexes), `snap-lib-end!`
(`last`/`butlast`), the emitter (dialects, regex decoding), and the jolt branch of
`:set-current-ns!`. After them, each runtime's snapshot was rebuilt from a new recording
and booted, with both batteries (macOS arm64, 2026-11-03):

| runtime | core battery | libraries | the same as |
|---|---:|---:|---|
| jank (`bb boot:jank-snapshot`) | 165/167 | 62/62 | before the branch |
| jolt | 167/167 | 62/62 | its source boot |
| ClojureWasm | 165/167 | 62/62 | its source boot |
| basilisp (`bb boot:lpy-snapshot`) | 165/167 | 62/62 | its source boot, on this branch and on its base ab3cd28 (`numeric tower` and `hash = identical` fail on all three) |

Guide 07 listed basilisp at 166/167; on ab3cd28 its source boot gives 165/167, and the row now says so.

## Gates for each runtime

- The built app runs the README's `run you` from another directory, with the runtime's
  sources not on disk.
- `dev/perf/snapshot_run.cljc` on the emitted modules passes the same core and library
  batteries as the boot from source.
- `bb docs:check <runtime>`; `bb bench:programs <runtime>-built` gives the JVM's answers.
