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
- **Reader conditionals** in a dependency's `.cljc` (and in `-e` text) read as the
  JVM reads them, on every host: the first `:clj` branch, else `:default`.
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

## A native executable on jank (D-7)

`cohost.build-jank/build!` (src/cohost/build_jank.clj) makes one native executable of a project's app on jank: the booted core's snapshot plus the app's namespaces, compiled `-O3` and linked by `jank compile --runtime static`, with the app's `-main` as its entry. Lane C's `cohost.build` (tools.build: `cohost -T:build jank-exe`) calls it; in this repo, `bb build:jank <project-dir> <main-ns> [name]` does (the roots are what `bin/cohost :jank -Spath` prints).

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
- **The REPL** reads standard input whole: for piped input it prints what
  `clojure` prints, but a prompt per form (`clojure` prints one per line read),
  and it stops at the first error. There is no line-at-a-time terminal REPL.
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
