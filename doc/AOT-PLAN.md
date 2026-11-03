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
| Phel | `phel build -O 2` (inlining; the default is level 0) to PHP; PHP OPcache with the tracing JIT (`opcache.enable_cli=1 opcache.jit=tracing`) | spike: see below |
| let-go | `.lgb` bytecode (`lg -c`); standalone binaries (bytecode appended to `lg`); AOT that lowers IR to native Go (`ir.passes.pipeline/lower-ns-to-go`, tagged `lg` build; fib(35) 2.42 s → 0.11 s in their benchmark) | spike: see below |
| ClojureWasm | `cljw build`, a native binary; the runtime itself as a ReleaseFast Zig build (`-Doptimize=ReleaseFast`; it ships ReleaseSafe) | spike: see below |
| ClojureScript | Closure `:advanced` | plan only: see below |

## Items, in order

1. **jolt** — done.
2. **basilisp** — done. Next: measure `--inline-functions` and `--generate-auto-inlines`
   on the cached boot.
3. **Phel**
   - Emit `--host phel` modules. cohost's own `.cljc` already reads on Phel; check that the
     emitter's printed forms read back the same.
   - Compile them with `phel build -O 2` and ship the PHP.
   - Run under OPcache with the tracing JIT and a file cache (measured: fib(25) inside
     cohost on Phel 26 ms → 21 ms; startup unchanged at about 23 s, all of it the boot
     from source, which the build removes).
4. **let-go**
   - First, bytecode: `lg -c` of the emitted modules. cohost.cli itself fails today with
     `unsupported value type for serialization: *vm.Ratio`. Find the Ratio constant;
     emit it as `(/ n d)` or report it upstream.
   - Then the Go lowering. It needs let-go's source and Go; their build script handles
     `ns` + `defn` fixtures only.
5. **ClojureWasm**
   - `cljw build` of the emitted modules. cohost.cli itself fails with
     `build failed: UnsupportedValueTag`. Find the value; work around it or report upstream.
   - ReleaseFast is a property of the cljw binary, not of cohost: a separate measurement.
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
   run time.

## Gates for each runtime

- The built app runs the README's `run you` from another directory, with the runtime's
  sources not on disk.
- `dev/perf/snapshot_run.cljc` on the emitted modules passes the same core and library
  batteries as the boot from source.
- `bb docs:check <runtime>`; `bb bench:programs <runtime>-built` gives the JVM's answers.
