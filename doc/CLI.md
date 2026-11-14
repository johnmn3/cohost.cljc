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

Wired: `:cljs :jolt :lpy :cljw :phel :lg :jank`. `:jvm` is the `:clj` leg: the stock
`clojure` CLI when that command is on PATH, otherwise jolt plus a champaca bundle
of Clojure 1.12. `:cljrs` is wired when its boot lands (doc/hosts/PLAN.md).

## The target

`cohost :<target> ...` names the runtime for one command. Without it, the target is
`:cohost/target` in `./deps.edn` (a keyword, e.g. `:lpy`), and without that `:jank`. `:jvm` is the
`:clj` leg. `cohost :x` needs x's runtime installed; this leg's runtime is the `clojure`
command. When `clojure` is on PATH the real CLI runs the command whole, as with `clj`.
When it is not, and `jolt` is on PATH, the leg is jolt's champaca `:clj` handler.
`COHOST_CLJ_BODY=clojure|jolt` forces one of those. tools.deps ignores the key.
`cohost` with no arguments is the REPL.

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
`:target` in the task's arguments, else `:cohost/target`, else `:jank`:

Every artifact is a directory `target/<name>/` whose `run args...` calls the main's `-main` from any
directory; the task prints `build: target/<name>/run`. Measured 2026-11-02 on `cohost/app` projects
(`run you` from `/tmp` -> `Hello, you!`):

| target | `target/<name>/` holds | needs at run time | measured (`run you` from `/tmp`; bb readme:check, 2026-11-02 2:06-2:27 PM ET, unless the row says) |
|---|---|---|---|
| `:jvm` | `<name>.jar` (tools.build uberjar, `clojure.main` as its main; no `:gen-class` in code every target reads), `run` (`java -jar ... -m <main>`) | java | yes |
| `:cljs` | cohost's ClojureScript host build (`target/cljs-cli`), the stock sources, the source roots, `run.js`, `run` | node | yes |
| `:jolt` | `run` itself: one native binary (`jolt build`, release mode: direct-linked, whole-program inference) holding the booted core, the libraries and the app's namespaces, recorded on jolt and compiled ahead of time (54 MB) | nothing: no jolt, no sources | yes: `run you` 0.37 s; batteries 167/167 and 62/62 from the modules (macOS arm64 M5, 2026-11-03, aot/native-builds) |
| `:cljw` | the recorded boot as ClojureWasm modules (`snapshot/`: the core, the libraries, the app's namespaces, `cohost.app-main`), cohost's sources, `run` (`cljw -cp snapshot:cohost/src -m cohost.app-main`) | cljw | yes: boot 0.83 s against about 17 s from source, `run` 0.9 s; batteries 165/167 and 62/62, as from source (macOS arm64 M5, 2026-11-03, aot/native-builds) |
| `:lg` | a script bundle, `run` (`lg -source-paths ... lg_cli.lg`) | lg | yes (the same argument) |
| `:lpy` | a script bundle plus basilisp's compiled boot (the booted core as `.lpy` modules with their `.lpyc` bytecode, mtimes kept), `run` (`basilisp run -p snapshot -p cohost/src ...`) | basilisp | yes: `run` 1.9 s against 19-25 s from source (macOS arm64 M5, 2026-11-03, aot/native-builds) |
| `:phel` | the app compiled to PHP by `phel build` from its recorded boot (the core, the libraries, the app's namespaces; no `.phel` sources), `phel.phar`, `run.php`, `run` (PHP with a 64 MB stack, OPcache file cache and the tracing JIT) | php | yes: from another directory, 1.7-2.0 s, 1.3-1.4 s with OPcache warm; build 150-170 s; batteries 195/205 and 60/62 from the compiled build (Box 1, Linux, 2026-11-03, c/aot-phel @ 43ad1ee) |
| `:jank` | `run` itself: one native executable (`cohost.build-jank`): the booted core's snapshot and the project's namespaces compiled `-O3`, jank's static runtime; rebuilt when stale (state in `cohost-build/`) | nothing: it links only libc, libstdc++, libgcc_s, libm | yes (`Hello, you!` in 0.926 s, median of 3, from another directory) |
| `:cljrs` | not yet: cohost's clojurust boot is parked and the CLI does not run it (`bin/cohost`: not wired) | | |

What is not native yet ([doc/AOT-PLAN.md](AOT-PLAN.md)): `cljw build` of the emitted modules stops with `build failed:
UnsupportedValueTag`, so `:cljw` ships the modules for cljw to run; `lg -source-paths src -b out entry.lg`
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

## The :clj body

`:jvm` is the `:clj` leg. cohost does not inspect a JVM. `bin/cohost_clj_body.clj`
looks only at PATH:

- `clojure` resolves: the existing Clojure CLI launch, same arguments as before.
  Resolving dependencies through that CLI is how cohost works.
- `clojure` does not resolve, and `jolt` does: jolt resolves the project
  classpath and `$BUNDLE/lib/champaca` runs `clojure.main`. The classpath
  command is `jolt -Srepro -Spath`, plus any `-Sdeps`, `-A`, and `-M:`/`-X:`
  tokens from the command (`jolt -Srepro -Spath -M:main`). jolt leaves
  `org.clojure/clojure` off that path on purpose. The bundle's `app/*` already
  holds Clojure 1.12, spec.alpha, and core.specs.alpha, and that directory is
  the front of `-cp`, so the project classpath does not need the Clojure jar.
  `-M:alias` prepends the selected aliases' `:main-opts` (aliases combined
  in order, `:main-opts` the last alias's that sets them — the CLI replaces
  rather than concatenates — from `./deps.edn` and `-Sdeps`; user
  deps.edn is skipped because the resolve is `-Srepro`) and then the user's
  arguments. `-X:alias` is `clojure.main -e` of a `requiring-resolve` call:
  Clojure 1.12's jar has no `clojure.run.exec`. The argument map is the
  alias's `:exec-args` merged with the command line, the same merge the other
  hosts use.
- neither resolves: a diagnostic, exit 1.

`COHOST_CLJ_BODY=clojure` or `=jolt` forces that choice on a lone `:jvm`
(and on `:cohost/target :jvm`). Unset follows PATH. A host string that names
several hosts, or names `:clj`, does not consult the override.
`-T` and `-Sdescribe` stay on the `clojure` CLI on every host string. Without
`clojure` on PATH they say so and exit 1.

### Host strings

`cohost :jolt:lpy ...` lists hosts in order. The string used to be one host
name, so `:jolt:lpy` was unknown. It is now a list. `:clj` is not a runtime
host (the CLI's name for that runtime is `:jvm`). In a host string `:clj`
means the official `clojure` CLI only, with no champaca fallback. `:jvm`
keeps the PATH rule above.

Who owns the `:clj` leg (`resolve-clj-leg!`, stored in the `clj-leg` atom):

1. `:clj` anywhere in the string: the official `clojure` CLI.
2. Otherwise the first listed host that has a `:clj` provider. The table is
   `jolt -> champaca`, `jvm ->` the PATH selector. Hosts not in the table
   have none. Another host registers with `swap!` on `clj-providers`.
3. Otherwise the first host keeps the launch it has today.

The first host is always the process. `:jolt` and `:jolt:lpy` boot the native
jolt adapter (`jolt -Scp src:champaca-src -m cohost.cli`). Their `:clj` leg is
champaca inside that process. `champaca-src` is the bundle directory the build
records in `manifest.edn` (`:champaca-src`), or `COHOST_CHAMPACA_SRC` when that
is set. It holds champaca's Clojure sources and the Scheme files that checkout
loads. Those names are read from `cachedir.clj` (`runtime-files`,
`translator-sources`) and from `(io/resource "...")` in the sources.
`44ad7c4` has no `archive.ss`; a later checkout that adds the file names
it itself.
Without it the launch stops: `(require 'champaca.api)` only searches jolt's
source roots. The launcher also sets `JAVA_HOME` to the bundle runtime and
`CHAMPACA_CACHE_DIR`, and `cohost.cli` routes a `:clj` reader arm to
`champaca.api/init!` on first use, then `champaca.api/save-cache!` (so the
JIT keeps a generation), then `clojure.lang.RT/init` through
`champaca.api/invoke-static`, then `save-cache!` again, before any
`Compiler` call. Each arm calls `save-cache!` after `Compiler/load`.
A call that finds nothing new does not write. A `:clj` provider that is not champaca
(`:jolt:clj`) does not add that directory. `:lpy:jolt` and `:jank:jolt` boot basilisp
and jank. The `:clj` leg is still jolt's champaca, recorded in `clj-leg` as a
sibling body. This process does not start that sibling. A cross-process router
would have to launch the jolt/champaca body separately and ship the arm source
and a return value back. That protocol is not here.

`:clj` as the first token is the official `clojure` CLI, which is that token's
process. `:clj` later in the string (`:jolt:clj`) still names the official CLI
as the `:clj` leg, and the first host stays the process.

A jolt host with no `clojure` on PATH resolves the classpath with
`jolt -Srepro -Spath` and the same alias flags. With `clojure` on PATH it
keeps the Clojure CLI's basis, which is the argmap this adapter has used.
Every other host still resolves with `clojure`. A missing binary is
`cohost: \`clojure\` failed to start`, not an uncaught `IOException`.

The bundle is `COHOST_CLJ_BUNDLE`, or `$HOME/.cache/cohost/cohost-clj`. It is built
with JDK 25 and jolt 0.8.26, and run with neither:

```bash
CHAMPACA_SRC=/path/to/patched/champaca bash dev/build-clj-bundle.sh
```

`CHAMPACA_SRC` is a champaca checkout with the per-loader class identity patch
applied (johnmn3/atom.broker `lane-m/jolt-clj-body` @ ee947a3fdd,
`docs/clojure-runtime/multi-bodied/jolt-spike/per-loader-identity.experimental.diff`).
Upstream champaca does not have that fix yet. The script pins Clojure 1.12.0,
spec.alpha 0.5.238 and core.specs.alpha 0.4.74, and names the launcher
`cohost-clj`. The bundle's own `bin/` launcher cannot take a runtime classpath,
so cohost calls `lib/champaca` directly:

```bash
JAVA_HOME=$BUNDLE/runtime \
CHAMPACA_BUNDLE=$BUNDLE \
CHAMPACA_CACHE_DIR=$HOME/.cache/cohost/champaca \
  $BUNDLE/lib/champaca -cp "$BUNDLE/app/*:$CP" clojure.main <main-opts then user args>
```

`$CP` is the `jolt -Srepro -Spath` output (with the alias flag when there is
one). `app/*` supplies Clojure, so `$CP` omitting it is what this leg expects.
The build script downloads `clojure-1.12.0.jar` into the Maven repo jolt
names (`mvn_local_repo`, or `~/.m2/repository`) because `jolt -Spath` omits
that jar. The same build copies champaca's `src` and `resources` into
`$BUNDLE/champaca-src` (one jolt source root: `champaca/api.clj` beside
`champaca/runtime.ss`) and writes that relative path into `$BUNDLE/manifest.edn`.
The native `:jolt` adapter (`launch!`'s `"jolt"` branch) puts that directory
on `jolt -Scp` when this process's `:clj` provider is champaca. The `:jvm`
whole-process path above does not: it still execs `lib/champaca`.

Champaca's guest-private JVM/Chez internals are outside this launcher (memory law
waived there, John 00:50 ET). This cohost side retains nothing and adds no
scanner, header counter, journal, or arena.

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
- **An error** is reported as clojure.main reports it: the triaged message
  (`Execution error (ExceptionInfo) at user/boom (REPL:1).` and the cause) to
  stderr, and a full report at `<tmpdir>/cohost-<millis>-<n>.edn` holding
  `{:clojure.main/message :clojure.main/triage :clojure.main/trace}`;
  `COHOST_MAIN_REPORT=stderr|none` chooses the target instead. The run ends
  with exit code 1; `-X` of a missing fn prints tools.deps'
  `Namespace x loaded but function not found: f`. Exit codes propagate.
- `-J` options are dropped; `-Sdescribe` and `-T` run the Clojure CLI only
  (a missing `clojure` is a diagnostic, including on the champaca leg);
  `-Spath` prints the source roots on a native host (on `:jvm` it is the CLI's
  `-Spath`, or `clojure.main`'s arguments when that leg is champaca); `-P`
  resolves and extracts only.

Per-host launches: `:jolt`, `:cljw` take `-m cohost.cli <config>`; `:lpy` runs it
through `basilisp run -c`; `:lg` runs `dev/lg_cli.lg` (it has no `-m`); `:phel`
runs `dev/phel_cli.phel` from the repo (its `phel-config.php` names the source
directories) and `:cljs` the build in `cljs-cli.edn` (`:main cohost.cli`, rebuilt
when `src/` or the edn changes) from the repo (the nodejs bootstrap finds its
files from the process's directory): `cohost.cli` moves to the project directory
the config names. `:jank` runs the snapshot's launcher, `target/jank-snapshot/aot/cohost-jank
<config>`: `cohost.cli` compiled with the booted core and the ten libraries into one jank program
(`--runtime dynamic`, so `-e` and a project's namespaces still evaluate), built with the snapshot
(`dev/perf/jank_snapshot_key.clj`, once per cohost update; the first `cohost` command builds it).
Without a fresh snapshot it runs `jank -O3 --module-path <cohost's -O3 AOT objects>:src
run-main cohost.cli -- <config>`; the objects are `dev/perf/jank_aot_ensure.clj`'s, rebuilt
when `src/cohost` or the jank binary changed (the first run after a change compiles them).

Two environment variables reach every jank command cohost runs (`bin/cohost :jank`, `bb boot:jank`,
`bb bench:jank`, `bb bench:programs jank|jank-jit|jank-aot`): `JANK_BIN` names the jank binary
(default `jank` on PATH; a source build gets its own AOT objects under
`target/jank-aot-<12 hex of sha256(realpath)>` and its own bench records, and `bb boot:jank-snapshot`
refuses it, since the snapshot's key reads `jank print-binary-version`, which a patch does not
move), and `COHOST_JANK_O` is the JIT optimization flag (default `-O3`; `none` passes no `-O`;
any other value, e.g. `-O0`, replaces it).

Jank's own introspection reaches the run the same way: the launcher passes its environment to
the jank process unchanged, so `JANK_PRINT_IR=1` and `JANK_PRINT_CODEGEN=1` print jank's
intermediate representation and the generated C++ for the forms as cohost rewrote them (shim
and `cohost.core` names included -- what the host compiler actually receives). A compiled
snapshot compiles nothing at run time, so an introspection request takes the source boot for
that run (cohost's AOT objects, core from source -- the slower path): the only shape in which
the rewritten forms compile in the run and print. For a profile,
`COHOST_JANK_PROFILE=1 bin/cohost :jank ...` runs the program under jank's `--profile` and
jank writes its trace to `jank.profile` in the project directory (a full enter/exit trace --
expect tens of megabytes for a booting run). A compiled snapshot launcher cannot take the
flag, so a profile request takes the JIT `run-main` path for that run.

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
- The stale case (26.26 s: the AOT boot of core.clj) lasts until `bb boot:jank-snapshot` rebuilds the core snapshot. That build records a boot (1 m 49 s, measured 2026-11-02), emits and compiles every module (4 m 56 s, measured 2026-11-02), and first recompiles cohost's AOT objects when `src/cohost` changed. Rebuilding only the modules whose source changed needs the emitter to keep unchanged files and jank's `compile-module` to skip them; whether it does is not measured, and a codemod change can change every recorded form. Not done yet.

The fix this needed (any snapshot): a library module loaded inside another library's load now restores what was loading when it began (`snap-lib-begin!` pushes `:loading`, `snap-lib-end!` pops it) instead of the parent recorded at snapshot time.

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
`std::exit`, which flushes standard output. `:cljrs` is not wired: cohost's clojurust boot is parked (doc/perf/HISTORY.md).

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

- **The error report** is clojure.main's (message, triage, report file);
  two pieces are thinner: on a host with no stack trace the report's
  `:trace` is empty, and a spec error prints its cause rather than
  spec's explain text (the explain-data is in the report file). A
  namespace that is nowhere on the roots gives
  the host's own message ("Could not locate nope/ns.jolt …").
- **Names print under `cohost.*`**: `#'clojure.core/map` prints
  `#'cohost.core/map`, as everywhere in cohost (doc/REPORT.md).
- **On `:jank`, integer arithmetic on two small integers wraps at 32 bits** (jank 0.1's own
  `+`, `-`, `*`; doc/REPORT.md's host facts). A sum past 2^31 is wrong until A5 lands (in flight,
  jank-MAX A5; not in this tree).
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
