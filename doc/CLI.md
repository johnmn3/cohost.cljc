# The `cohost` command: tools.deps on every host

`bin/cohost` puts the Clojure CLI's face on any host: `deps.edn`, aliases,
`-e`/`-i`/`-m`/scripts/`-X` — while the code runs as stock Clojure 1.12 on the
host named first:

```bash
cohost :cljs -e "(+ 1 2)"            # 3: stock Clojure on ClojureScript
cohost :jolt -M -m demo.main arg1    # deps.edn resolved, demo.main booted on jolt
cohost :lg -X:run :n 2               # an alias's :exec-fn, its :exec-args merged
cohost :cljs -Spath                  # the source roots, in tools.deps order
```

Wired: `:cljs :jolt :lpy :cljw :phel :lg :jank`. `:jvm` runs the real Clojure CLI.
`:cljrs` is wired when its boot lands (doc/hosts/PLAN.md).

## The target

`cohost :<target> ...` names the runtime for one command. Without it, the target is
`:cohost/target` in `./deps.edn` (a keyword, e.g. `:jank`), and without that `:jvm`: the
real Clojure CLI runs the command whole, so a project with no key behaves as with `clj`.
tools.deps ignores the key. `cohost` with no arguments is the REPL.

## New projects

deps-new, as a tool: `cohost -Tnew create :template cohost/app :name acme/myapp
[:target jank]`. `-T` runs the real CLI on every target, and `bin/cohost` merges cohost's
template library (`templates/cohost-app`, deps-new's `cohost/app`) into the `-Sdeps` of
every `-T` call, so the template is found without naming it. The project: `deps.edn`
(`:cohost/target`, Clojure 1.12.0, aliases `:main`, `:run`, `:test`, `:build`),
`src/acme/myapp.cljc`, `test/acme/myapp_test.cljc` (clojure.test; `-X:test` exits 1 on a
failure), `build.clj`, `README.md`. The template reads `COHOST_HOME`, which `bin/cohost`
exports, for the `:build` alias's `:local/root` on the cohost checkout.

## Building

tools.build, as for `clojure`: `cohost -T:build build` runs the project's `build.clj`,
which calls `cohost.build/build` (`src/cohost/build.clj`, JVM-side). The target is
`:target` in the task's arguments, else `:cohost/target`, else `:jvm`:

Every artifact is a directory `target/<name>/` whose `run args...` calls the main's `-main` from any
directory; the task prints `build: target/<name>/run`. Measured 2026-11-02 on `cohost/app` projects
(`run you` from `/tmp` -> `Hello, you!`):

| target | `target/<name>/` holds | needs at run time | measured (bb readme:check, 2026-11-02 2:06-2:27 PM ET) |
|---|---|---|---|
| `:jvm` | `<name>.jar` (tools.build uberjar, `clojure.main` as its main; no `:gen-class` in code every target reads), `run` (`java -jar ... -m <main>`) | java | yes |
| `:cljs` | cohost's ClojureScript host build (`target/cljs-cli`), the stock sources, the source roots, `run.js`, `run` | node | yes |
| `:jolt` | `bin/cohost-jolt` (`jolt build -m cohost.cli`, 36 MB, 36 s), the stock sources, the source roots, `run` | nothing: ran with jolt off `PATH` | yes |
| `:cljw` | a script bundle: cohost's sources, the stock sources, the source roots, `run` (`cljw -cp ... -m cohost.cli`) | cljw | yes (an argument with spaces and quotes too) |
| `:lg` | a script bundle, `run` (`lg -source-paths ... lg_cli.lg`) | lg | yes (the same argument) |
| `:lpy` | a script bundle, `run` (`basilisp run -p ... -c ...`) | basilisp | yes (`bb readme:check lpy`, 2026-11-02) |
| `:phel` | a script bundle with `phel-config.php`, `run` (`phel run dev/phel_cli.phel` in the bundle's `cohost/`) | php + phel | yes (`bb readme:check phel`, 2026-11-02) |
| `:jank` | `run` itself: one native executable (`cohost.build-jank`): the booted core's snapshot and the project's namespaces compiled `-O3`, jank's static runtime; rebuilt when stale (state in `cohost-build/`) | nothing: it links only libc, libstdc++, libgcc_s, libm | yes (`Hello, you!` in 0.926 s, median of 3, from another directory) |
| `:cljrs` | not yet: cohost's clojurust boot is parked and the CLI does not run it (`bin/cohost`: not wired) | | |

Why cljw and lg ship a script bundle and not their own native packaging (measured on cohost.cli): `cljw build -m
cohost.cli -o out -cp src` stops with `build failed: UnsupportedValueTag`; `lg -source-paths src -b out entry.lg`
stops with `unsupported value type for serialization: *vm.Ratio` (and with `-entry-frame-entry`: `-entry-frame-entry
requires -c and a namespace/main or namespace/-main value`).

`bb readme:check [target ...]` runs README.md's command blocks, as written, against a fresh
`cohost/app` project per target.

## Shape

Two halves, one contract between them (an EDN file):

- **`bin/cohost`** (Babashka) is the launcher. It takes the host key off the
  front, reads the rest the way the `clojure` script does, resolves `deps.edn`,
  and starts the host process with a config file it deletes after.
- **`src/cohost/cli.cljc`** is the host-side runner. It reads the config — stock
  root, source roots, the init options and the main option — boots the stock
  core, registers the roots with `boot/add-source-root!`, and runs as
  `clojure.main` does (`clojure.run.exec` for `-X`). A dependency's namespace is
  found by `boot/load-lib!` through the ordinary loader and rewritten like every
  other form: dependencies get stock core, the shim and the host's interop.

## Resolution is tools.deps, not a reimplementation

The launcher runs `clojure -Sverbose -Spath` with the user's `-Sdeps`,
`-Srepro`, `-Sforce`, `-Sthreads` and alias tokens (`-A`, `-M`, `-X`) in the
project directory. The Clojure CLI *is* tools.deps' front end, so the
root/user/project config chain, alias merging, `:local/root`, git deps and
Maven coordinates behave as they do for `clj`. The classpath is its last line;
`-Sverbose` names the cache file it came from, and beside it the CLI writes the
basis, whose `:argmap` holds the aliases' `:main-opts`, `:exec-fn`,
`:exec-args`, `:ns-default` and `:ns-aliases`. Each classpath entry becomes a
source root:

- a **directory** (project `:paths`, a `:local/root` dep's paths, a git dep's
  checkout) as it is;
- a **jar** has its `.clj`/`.cljc` entries extracted once to
  `~/.cache/cohost/deps/<artifact>-<hash of path|size|mtime>/`;
- **`org.clojure/clojure`'s jar is left out**: the host runtime is the language,
  booted from cohost's pinned 1.12.0 sources whatever version the deps name.
  `clojure.*` libraries (`clojure.string`, …) load from those sources too: the
  stock root comes first.

## What runs, as `clojure.main` runs it

- **Init options** `-e`/`--eval` and `-i`/`--init`, in the order given; each
  `-e` form's value is printed (the stock `prn`) when it is not nil.
- **One main option**: `-m ns args` calls `ns/-main`; a script path or `-`
  (standard input) runs the file, printing nothing; `-r`, or no main option and
  no init option, is the REPL. `*command-line-args*` is the arguments after the
  main option, set before the init options run.
- **`-M:alias`** puts the alias's `:main-opts` before the command line's.
- **`-X`** (`clojure.run.exec`): the fn from the command line or `:exec-fn`
  (qualified through `:ns-aliases`, or by `:ns-default`); its argument map is
  `:exec-args`, then the command line's `key value` pairs (a vector key is a
  path) and an optional trailing map. Its value is not printed.
- **Namespace**: `user`, seeing the stock core.
- **Reader conditionals** in your code and your dependencies' (`.cljc` files,
  `-e` text, scripts) are chosen by cohost, the same way on every host: each
  target ranks the platform keys, and the branch whose key ranks highest is
  taken, wherever it stands in the `#?` (not the first match in source order).
  Today every target ranks `:clj` first, then `:default`. A branch whose key is
  not ranked is never taken; a `#?` with no ranked branch reads as nothing.
  `#?@` splices the chosen branch the same way. The same ranking picks the file
  when a namespace has several: `foo.clj` before `foo.cljc`.
- **An error** prints `Execution error (Class).` and its message to stderr and
  ends the run with exit code 1; `-X` of a missing fn prints tools.deps'
  `Namespace x loaded but function not found: f`. Exit codes propagate.
- `-J` options are dropped; `-Sdescribe` and `-T` (tools, JVM programs) run the
  real CLI; `-Spath` prints the source roots; `-P` resolves and extracts only.

Per-host launches: `:jolt`, `:cljw` take `-m cohost.cli <config>`; `:lpy` runs it
through `basilisp run -c`; `:lg` runs `dev/lg_cli.lg` (it has no `-m`); `:phel`
runs `dev/phel_cli.phel` from the repo (its `phel-config.php` names the source
directories) and `:cljs` the build in `cljs-cli.edn` (`:main cohost.cli`, rebuilt
when `src/` or the edn changes) from the repo (the nodejs bootstrap finds its
files from the process's directory): `cohost.cli` moves to the project directory
the config names. `:jank` runs `jank -O3 --module-path <cohost's -O3 AOT objects>:src
run-main cohost.cli -- <config>`; the objects are `dev/perf/jank_aot_ensure.clj`'s, rebuilt
when `src/cohost` or the jank binary changed (the first run after a change compiles them).

## jank: where `cohost :jank` spends its time

Measured 2026-11-02 ET in `dev/cli-demo` with a fresh snapshot, `bash dev/perf/jank_snapshot_cli_parts.sh`: each part is its own command, three runs, seconds (the jank parts run the launch's own `jank -O3 ... run-main cohost.cli` with a config that stops at that point).

| part | before (s) | after (s) |
|---|---|---|
| `bb` start | 0.032 / 0.027 / 0.023 | 0.024 / 0.025 / 0.023 |
| `bin/cohost :jank -Spath` (bb, `clojure -Spath`, the roots) | 0.049 / 0.051 / 0.050 | 0.055 / 0.052 / 0.052 |
| `clojure -Spath`, `.cpcache` hit / miss | 0.017 / 0.015 / 0.015; 1.301 / 1.266 / 1.305 | 0.014 / 0.014 / 0.013; 1.264 / 1.205 / 1.045 |
| the launch helper (snapshot key, a second bb) | 0.099 / 0.107 / 0.102 | 0.097 / 0.116 / 0.107 |
| a jank process, nothing loaded | 0.714 / 0.680 / 0.616 | 0.641 / 0.624 / 0.614 |
| + cohost's AOT objects | 1.085 / 1.047 / 1.134 | 1.113 / 1.110 / 1.069 |
| + cohost.cli, snapshot boot, user ns, exit | 3.277 / 3.289 / 3.301 | 1.685 / 1.750 / 1.807 |
| + loading demo.main (tools.cli, greeter) | 3.612 / 3.090 / 3.420 | 1.858 / 2.086 / 1.834 (project snapshot) |
| + `-main` | 6.006 / 5.690 / 5.783 | 1.906 / 1.967 / 2.096 (project snapshot) |
| **`cohost :jank -M -m demo.main jank-user`** | 5.481 / 5.986 / 5.851 (median 5.851) | **2.075 / 2.070 / 1.976 (median 2.070)** |
| `clojure -M -m demo.main jank-user` (JVM, same box) | | 1.485 / 1.486 / 1.236 (median 1.485) |

What moved:

- `-main` cost 2.36 s and loading the app 0.13 s: jank's lazy JIT compiled the app's functions at `-O3` on their first call, every run. A **project snapshot** compiles them once: `bin/cohost`'s jank launch records the main namespace's load on top of a snapshot boot, emits the project's library modules, `compile-module`s them `-O3` into `<project>/target/cohost-jank/` (keyed by the core snapshot's stamp, every file under the project's roots and the main namespace), and passes the module to `cohost.cli`, which registers it before running. When it is stale, the run builds it first (`cohost: jank: compiling the project's namespaces ahead (once per change)`; 37.2 s for the first run of the demo, the build included); `COHOST_JANK_PROJECT=none` turns it off. With it, loading and `-main` together are within the spread of a run that stops before them.
- `cohost.cli` and its start cost 1.54 s more than the snapshot boot: `cohost.cli` was JIT-compiled from source every run. The snapshot's core module now requires it, so the snapshot's compile makes its object.
- Not moved: the launcher (about 0.1 s with `.cpcache` hit), a jank process (0.62 s), cohost's objects (0.49 s; loading only `cohost.hosts.auto`, `cohost.boot` and `cohost.cli` instead of `cohost.run` measured the same, 1.197 / 1.208 / 1.062 against 1.204 / 1.118 / 1.218), the snapshot's replay (0.64 s). Under 1.485 s is jank's static executable (0.911 s, "A native executable on jank" below).
- The stale case: "jank: a stale snapshot" below.

The fix this needed (any snapshot): a library module loaded inside another library's load now restores what was loading when it began (`snap-lib-begin!` pushes `:loading`, `snap-lib-end!` pops it) instead of the parent recorded at snapshot time.

## jank: a stale snapshot

After any change to the stock sources or `src/cohost`, the core snapshot is stale and is never loaded: `cohost :jank` boots core.clj on the AOT path instead (about 26 s for the demo, against about 2 s from the snapshot). Measured 2026-11-02 ET on this box.

**What jank 0.1 lets us skip.** `compile-module` recompiles a module only when its source file is newer than its object, and never because a module it depends on changed. On a copy of a fresh snapshot: nothing changed, 2.3 s and no object written; a touched `cohost/zip.jank`, 8.1 s and `zip.o` alone; a touched `cohost/core.jank`, 44.7 s and `core.o` alone; a touched `src/cohost/shim.cljc`, 52.2 s and `shim.o` alone. (Its cache key, the directory name under `_cache/`, is the binary and its flags; it covers no header or source contents, so staleness has to be decided by us.) So the build decides what is stale and says so with the file's mtime:

- the emitter keeps a module file as it is (bytes and mtime) when its text is unchanged and so is the text of every module loaded before it ended (cohost.core, the libraries before it, the ones it loads): those are the namespaces whose macros it can expand. Anything else is written again, so jank recompiles it. cohost's own sources are not dependencies of a module: they define no macros, and a module calls their fns through vars (jank 0.1 does not implement `-Odirect-call`), so a change there reaches a module only by changing its recorded text;
- the build keeps the last build's objects (unless another jank binary made them; `COHOST_JANK_SNAPSHOT=full` deletes them first), compiles `cohost.run` into the snapshot's own cache (only the changed files recompile), records a boot from those objects, emits, and compiles.

`bb boot:jank-snapshot` after a one-line docstring change, each applied and reverted twice (median of the first three); the full rebuild with each of the three changes, once:

| one-line change in | full rebuild (before) | incremental (after) | modules recompiled | where the time goes |
|---|---:|---:|---|---|
| clojure.zip (`zip.clj`, a library loaded fifth) | 331.7 s | **99.1 s** (99.0 / 101.2 / 99.1) | 10 of 15 (zip and the 9 loaded after it) | compile 78 s, record 20 s |
| cohost.core (`core.clj`) | 331.7 s | **155.0 s** (162.5 / 154.1 / 155.0) | 14 of 15 | compile 134 s, record 20 s |
| the shim (`src/cohost/shim.cljc`) | 331.7 s | **62.3 s** (61.9 / 62.3 / 62.8) | 0 of 15 (`shim.o` alone) | `shim.o` 41 s, record 19 s, compile 2 s |

The full rebuilds: 364.4 s (zip), 331.7 s (core), 329.1 s (shim); median 331.7 s. Before this change a rebuild was a record of 1 m 49 s plus emit and compile of 4 m 56 s plus, after a `src/cohost` change, the AOT objects' full recompile. The record now takes about 20 s: it boots from the snapshot cache's `-O3` objects. The battery was unchanged after every build (144/146, libraries 62/62; 152/154 on the release branch).

**What `bin/cohost :jank` does with a stale snapshot.** It rebuilds it in the background and boots this run without it:

    cohost: jank: rebuilding the snapshot in the background (pid 15750, .../target/jank-snapshot-rebuild.log); this run boots without it

A run while the rebuild is going says so (`the snapshot is being rebuilt`) and starts no second one (a lock holds the pid). The rebuild runs `nice`d and writes its stamp last, so a half-built snapshot is never loaded. `COHOST_JANK_REBUILD=0` turns it off and prints the one command instead (`(cd <repo> && bb boot:jank-snapshot)`).

The numbers that chose it, `cohost :jank -M -m demo.main jank-user` after the shim change:

| | run (s) |
|---|---|
| fresh snapshot | 1.897 / 2.007 |
| stale, during the background rebuild | 25.868 / 26.004 |
| the rebuild (78.9 s, incremental), then | 34.257 (the project's own snapshot rebuilds once, keyed on the core's), 1.922 / 1.944 |
| stale, `COHOST_JANK_REBUILD=0` | 68.299 (includes `shim.o`'s recompile for the AOT path), and about 26 s on every run after it until the command runs |

The rebuild costs a run beside it nothing measurable (26.0 s against 26.0 s) and makes the CLI fast again a minute or two after a change; printing the command leaves every run about 24 s slower until someone runs a 62-155 s command. The AOT path's own objects (`target/jank-aot`) now recompile incrementally too (only the changed files); the first stale run after a large merge still recompiled most of them (189.6 s).

## A native executable on jank

`cohost.build-jank/build!` (src/cohost/build_jank.clj) makes one native executable of a project's app on jank: the booted core's snapshot plus the app's namespaces, compiled `-O3` and linked by `jank compile --runtime static`, with the app's `-main` as its entry. `cohost.build` (tools.build: `cohost -T:build jank-exe`) calls it; in this repo, `bb build:jank <project-dir> <main-ns> [name]` does (the roots are what `bin/cohost :jank -Spath` prints).

1. cohost's own namespaces as `-O3` AOT objects;
2. a boot with record mode on: the project's source roots added, the twelve libraries and the app's main namespace loaded through cohost's loader (so the app, its `:local/root` libraries and its Maven jars are rewritten as every other namespace is);
3. one module per booted namespace, and `cohost.app-main`, whose `-main` boots from the snapshot and calls the app's;
4. `jank -O3 compile cohost.app-main --runtime static --name <name>`: `<project>/target/jank/<name>`.

Every input (the stock sources, `src/cohost`, the snapshot scripts, jank's binary version, every file under the app's roots, the main and the name) goes into one sha256 stamped beside the executable: a fresh one is not built again (`JANK-EXE-FRESH`), a stale one always is.

Measured 2026-11-02 ET on `dev/cli-demo` (jank `0.1-noble`):

```
bb build:jank dev/cli-demo demo.main demo
JANK-EXE-BUILD .../dev/cli-demo/target/jank/demo (new)
SNAPSHOT-EVENTS 1508 bytes 805029
MODULE cohost.app-main .../cohost/app_main.jank main demo.main/-main
JANK-EXE-BUILT .../dev/cli-demo/target/jank/demo in 428694 ms
bb build:jank dev/cli-demo demo.main demo       # again
JANK-EXE-FRESH .../dev/cli-demo/target/jank/demo   (0.161 s)
```

The executable is 38,921,656 bytes and links only the system's libc, libstdc++, libgcc_s and libm. A copy run from `/tmp` prints `hello, jank-USER!`; under `strace` it opens `/etc/ld.so.cache`, those four libraries and `/proc` files, nothing in the checkout, the project or jank's install. It cannot `eval` or load a namespace that was not in the build: the static runtime has no JIT.

Startup, wall seconds to the greeting, three runs each, interleaved, in `dev/cli-demo`:

| | runs | median |
|---|---|---:|
| `target/jank/demo jank-user` | 0.930 / 0.876 / 0.911 | 0.911 |
| `cohost :jank -M -m demo.main jank-user` (fresh snapshot) | 6.797 / 5.925 / 5.870 | 5.925 |
| `clojure -M -m demo.main jank-user` (JVM) | 1.400 / 1.259 / 1.363 | 1.363 |

## Verified (2026-11-02 ET)

The demo is committed: `dev/cli-demo/` (`deps.edn` with `org.clojure/tools.cli 1.2.245` from Maven, the
`:local/root` library `greeter/`, stock `clojure.string`; aliases `:main` (`:main-opts`) and `:run`
(`:exec-fn`/`:exec-args`); `script.clj`). `bb cli:check [target ...]` runs six cases in it with `clojure` and with
`bin/cohost :<target>` and prints one line per target x case: SAME when stdout and the exit code match (`-Spath`:
when cohost's roots are clojure's classpath with each jar replaced by its extraction and the clojure jar left out).

```
cohost :jank -M -m demo.main jank-user        # hello, jank-USER!
cohost :cljw -M:main -g hi -n 2 cljw-user     # hi, ClojureWasm-USER! (twice)
cohost :lg -X:run :user '"lg-user"'           # hello, let-go-USER!
cohost :cljs -e "(require 'greeter.core)" -e '(greeter.core/name-of "cljs-user")'   # "ClojureScript-USER"
cohost :jolt script.clj jolt-user jvm-x       # jolt-USER JVM-X
cohost :jank -Spath                           # the source roots
```

`bb cli:check`, 2026-11-02 12:07-12:18 PM ET, cohost `c/cli-every-target`:

| target | -M -m | -M:main | -X:run | -e | script | -Spath |
|---|---|---|---|---|---|---|
| jvm | SAME | SAME | SAME | SAME | SAME | SAME |
| jolt | SAME | SAME | SAME | SAME | SAME | SAME |
| cljs | SAME | SAME | SAME | SAME | SAME | SAME |
| cljw | SAME | SAME | SAME | SAME | SAME | SAME |
| lg | SAME | SAME | SAME | SAME | SAME | SAME |
| jank | SAME | SAME | SAME | SAME | SAME | SAME |
| lpy, phel, cljrs | not on the box that ran this | | | | | |

jank before this change: `cohost: :jank is not wired into the CLI yet` on every case. Wiring it took the launch
above and one host arm in `cohost.cli/exit!`: jank 0.1's `flush` throws `TODO: port flesh`, so jank exits through
`std::exit`, which flushes standard output. `:cljrs` is not wired: cohost's clojurust boot is parked (doc/PERF.md).

## Verified (2026-10-31 ET)

Demo project: `deps.edn` with `org.clojure/tools.cli 1.2.245` (Maven jar), a
`:local/root` library `greeter`, aliases `:run` (`:exec-fn`/`:exec-args`) and
`:main` (`:main-opts`); every output compared line by line with `clojure` itself.

| host | cases | differs from `clojure` only in |
|---|---|---|
| jolt | 16 (`-e` multi-form/errors/printing, `-i` order, script args, `-m`, `-M:alias`, `-X`, `-X:alias`, missing fn, `-J`, missing ns, `-`, REPL) | error location; `#'cohost.core/map`; missing-ns message; REPL prompt per form |
| cljs, cljw, lg, lpy, phel | 5 (multi-form `-e` + error, `-m` with jar + local deps and `*command-line-args*`, `-X:alias`, `-M:alias`, `-`) | error location |

Phel's five were re-run after the fixes (2026-10-31): its `(/ 1 0)` said PHP's "Division by
zero", now the JVM's "Divide by zero" (the shim's `Numbers/divide` throws it for an exact zero
divisor on every host). The cross-host regression (every battery and the jolt corpus) was
re-run on these fixes and matched the earlier numbers.

## Where it differs from `clojure`

- **The error report** names the class and message, not where it was thrown,
  and writes no full report file. A namespace that is nowhere on the roots gives
  the host's own message ("Could not locate nope/ns.jolt …").
- **Names print under `cohost.*`**: `#'clojure.core/map` prints
  `#'cohost.core/map`, as everywhere in cohost (doc/REPORT.md).
- **The REPL** (`cohost` with no main option, as `clj`) reads a line at a time, on a
  terminal or a pipe: a prompt, lines until they hold whole forms, each value printed
  (the forms of one line under one prompt), an error reported and the REPL goes on, as
  `clojure` does. The banner says `Clojure 1.12.0` (the booted core); there is no
  line editing or history (wrap it in `rlwrap`, as `clj` wraps `clojure`). Piped
  input, 2026-11-02 (7 lines: a multi-line `def`, two forms on one line, `(/ 1 0)`, a `;`
  in a string): the same output as `clojure` on jolt, cljw and jank but for the banner
  and the error location; cljs the same but `(/ 1 0)` prints `##Inf` (as `-e` does there);
  lg checked on a shorter input.
- **Jars contribute sources only.** Resources in a jar (data files,
  `data_readers.clj`) are not extracted; a source-less (AOT-only) jar
  contributes nothing. `.cljs`-only libraries do not load (the loader reads
  `.clj`/`.cljc`).
- The cljs build compile honors `COHOST_CLI_SDEPS`, an extra `-Sdeps` string for
  the build step only (for environments whose pinned ClojureScript is not
  fetchable); `COHOST_VERBOSE` set prints the boot report first.

## Found by the CLI, fixed in cohost

Running dependencies on every host found gaps in cohost itself: reader
conditionals read with each host's own feature, jolt reading nothing after a
top-level `#?` with no branch for it, ClojureWasm's `let*` taking no empty body,
Phel compiling a fn not-found value of a keyword call to a string, and let-go's
`*ns*` read as a copy. Each is fixed where it arose; doc/REPORT.md ("The `cohost`
command, and what it found") has the details.
