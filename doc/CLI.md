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

Wired: `:cljs :jolt :lpy :cljw :phel :lg`. `:jvm` runs the real Clojure CLI.
`:cljrs` and `:jank` are wired when their boots land (doc/hosts/PLAN.md).

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
the config names.

## Verified (2026-10-31 ET)

Demo project: `deps.edn` with `org.clojure/tools.cli 1.2.245` (Maven jar), a
`:local/root` library `greeter`, aliases `:run` (`:exec-fn`/`:exec-args`) and
`:main` (`:main-opts`); every output compared line by line with `clojure` itself.

| host | cases | differs from `clojure` only in |
|---|---|---|
| jolt | 16 (`-e` multi-form/errors/printing, `-i` order, script args, `-m`, `-M:alias`, `-X`, `-X:alias`, missing fn, `-J`, missing ns, `-`, REPL) | error location; `#'cohost.core/map`; missing-ns message; REPL prompt per form |
| cljs, cljw, lg, lpy | 5 (multi-form `-e` + error, `-m` with jar + local deps and `*command-line-args*`, `-X:alias`, `-M:alias`, `-`) | error location |
| phel | `(:k m f)` fix and the 5 not yet re-run after the fixes | — |

Not re-run after the last fixes: the cross-host regression (batteries + corpus).

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
