# The `cohost` command: tools.deps on every host (spike)

**Status: spike, 2026-10-31, branch `claude/cohost-cli-spike`.** A working
proof that cohost can present the Clojure CLI's face — `deps.edn`,
aliases, `-e`/`-m`/`-X` — while the code runs as stock Clojure 1.12 on
the host named first:

```bash
cohost :cljs -e "(+ 1 2)"            # => 3, stock Clojure on ClojureScript
cohost :cljs -M -m demo.main arg1    # deps.edn resolved, demo.main booted on cljs
cohost :jolt -X demo.main/run :n 2
cohost :cljs -Spath                  # the source roots, tools.deps order
```

## Shape

Two halves, one contract between them (an EDN file):

- **`bin/cohost`** (Babashka) is the launcher. It takes the host key off
  the front, parses the rest the way the Clojure CLI does, resolves
  `deps.edn`, and spawns the host process.
- **`src/cohost/cli.cljc`** is the host-side runner. It reads the
  launcher's config — stock root, source roots, one action
  (`:eval`/`:main`/`:exec`/`:stdin`) — boots the stock core, registers
  the roots with `boot/add-source-root!`, and performs the action.
  Nothing in it is CLI-specific beyond that: a dependency namespace is
  found by `boot/load-lib!` through the ordinary loader and rewritten
  like every other form, so dependencies get the full treatment — stock
  core, the shim, and the host's own interop — for free.

## Resolution is tools.deps, not a reimplementation

The launcher runs `clojure -Spath` (plus the user's `-Sdeps` pairs and
`-M:`/`-X:` alias token) in the project directory. The Clojure CLI *is*
tools.deps' front end, so the root/user/project config chain, alias
merging, `:local/root`, git deps and Maven coordinates all behave
exactly as they do for `clj` — because it is the same resolver. Each
classpath entry then becomes a source root:

- a **directory** (project `:paths`, a `:local/root` dep's paths, a git
  dep's checkout) is used as it is;
- a **jar** has its `.clj`/`.cljc` entries extracted to
  `~/.cache/cohost/deps/<artifact>-<hash of path|size|mtime>/`, once;
- the **`org.clojure/clojure` jar is skipped**: the host runtime is the
  language, booted from cohost's pinned 1.12.0 stock sources, whatever
  version the project's deps resolve.

Exit codes propagate from the host process; an action that errors
prints `cohost: ERR ...` and exits 1.

Per-host launches: `:cljs` runs the dedicated build in `cljs-cli.edn`
(`:main cohost.cli`), rebuilt when `src/` or the edn changes; the
nodejs bootstrap joins its output dir onto the process cwd, so node
starts in the repo and `cohost.cli` immediately `chdir`s to the project
directory carried in the config. `:jolt`/`:cljw` run
`<host> -cp src -m cohost.cli <config>`; `:lpy` runs the same through
`basilisp run -c`. `:jvm` is not a launch at all: the whole argument
list delegates to the real Clojure CLI. `-T` (tools) delegates the same
way on every host — tools are JVM programs.

## Verified (this branch, 2026-10-31)

Demo project: `deps.edn` with `org.clojure/math.combinatorics 0.2.0`
(Maven), a `:local/root` library `greeter`, and `demo.main` using both.

| command | result |
|---|---|
| `cohost :cljs -e "(+ 1 2)"` (no deps.edn) | `3` |
| `cohost :cljs -Spath` (demo) | `src : greeter/src : ~/.cache/cohost/deps/math.combinatorics-… : spec.alpha-… : core.specs.alpha-…` |
| `cohost :cljs -e "(require '[clojure.math.combinatorics :as c])" -e "(c/permutations [1 2 3])"` | `([1 2 3] [1 3 2] [2 1 3] [2 3 1] [3 1 2] [3 2 1])` |
| `cohost :cljs -M -m demo.main cljs-user` | `hello, CLJS-USER!` + the permutations |
| `cohost :cljs -X demo.main/run :n 2` | `{:perms ([0 1] [1 0]), :count 2}` |
| `echo "(+ 40 2)" \| cohost :cljs` | `42` |
| `cohost :jolt -e "(+ 1 2)"`, `cohost :cljw -e "(+ 1 2)"`, `cohost :lpy -e "(+ 1 2)"` | `3` |
| `cohost :jolt -M -m demo.main jolt-user` | greeter half prints; combinatorics does not load on jolt (below) |
| `cohost :jvm -e "(println (+ 1 2))"` | `3` (delegated) |

Regression after adding `cli.cljc`: `bb boot:jvm` 465/202/66/58/errors 0,
139/139, 61/61; `bb boot:jolt` 466/199/63/63/0, 139/139, 61/61;
`bb lint:reader-order` 0 problems — identical to the branch base.

## Deviations and gaps (the spike's honest edges)

- **`-e` prints its values.** `clojure.main -e` is silent; a spike you
  can't see is useless, so each `-e` result is printed. Revisit if the
  CLI grows up.
- **No interactive REPL yet.** With no main options (or `-r`/`-`), the
  runner reads piped stdin as a script and prints the last value. A
  line-at-a-time TTY REPL needs a per-host stdin story.
- **combinatorics does not load on `:jolt`** through the ordinary
  loader: its forms error during load (recorded per-form, as the loader
  does), leaving its vars absent — `No such var: c/permutations` at the
  call site. It loads and runs on `:cljs`. This is a loader/host gap
  the CLI exposes, not a CLI defect; the local `:local/root` library
  loads fine on jolt in the same run.
- **Jars contribute sources only.** Resources in a jar (data files,
  `data_readers.clj`) are not extracted; a source-less (AOT-only) jar
  contributes nothing, silently — the field-wide behavior for
  source-only loaders, but it should become a resolve-time error.
- **`.cljs`-only libraries** are not loadable (the loader reads
  `.clj`/`.cljc`); native/JNI dependencies are meaningless off the JVM.
- **Not wired:** `:phel`, `:lg`, `:cljrs`, `:jank` (their boots are
  still in flight); `-J` JVM options are ignored off-JVM; a script
  file argument is evaluated but its trailing args are not yet exposed
  as `*command-line-args*`.
- The cljs build compile honors `COHOST_CLI_SDEPS`, an extra `-Sdeps`
  string applied to the build step only (for environments whose pinned
  ClojureScript is not fetchable); unset in normal use.
