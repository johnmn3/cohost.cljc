# Getting started

*2026-11-02. The commands on this page run, as written, in `bb docs:check`.*

cohost runs Clojure 1.12: `clojure.core`, `clojure.string`, `clojure.test` and the other
`clojure.*` libraries are Clojure's own source files, read and evaluated on the runtime your
project names. You use it the way you use `clj`: a `deps.edn`, the REPL, `-M`, `-X`, `-T`,
tools.build. The command is `cohost`.

## Install

You need one Clojure-like to run the installer — [Jank](https://jank-lang.org),
Babashka, the Clojure CLI, or [Jolt](https://github.com/jolt-lang/jolt)
— plus Jank itself, which compiles the
`cohost` tool: on the Jank seat that is the same binary; on the
other seats the installer uses the Jank on your PATH, or installs
it from the checkout when there is none. (ClojureCLR is not an
installer seat: the snapshot build the installer drives has no
ClojureCLR-loadable form — see the note in the README's Install
section.)

```bash
# not checked: installs
curl -fsSL https://raw.githubusercontent.com/johnmn3/cohost.cljc/main/install.cljc -o install.cljc
jank run install.cljc          # or: bb install.cljc
                                # or: clojure -M install.cljc
                                # or: jolt run install.cljc
cohost -Ttools install-latest :lib io.github.seancorfield/deps-new :as new
```

`install.cljc` fetches the checkout if you don't have one, builds
cohost's compiled core snapshot — driven by the seat you ran,
compiled by Jank on every seat (minutes, the first time) — compiles
the `cohost` command into `~/.local/bin` and records the checkout
and the installing seat in `~/.config/cohost/install.edn`. Add
`~/.local/bin` to your PATH if it isn't there (the installer says so
when it isn't). From an existing checkout, `jank run install.cljc
-- --repo ~/cohost` installs from it instead (the other seats take
`--repo` the same way).

The default target (Jank) — `-Ttools install`, `-M` (with `-e`, `-m`
and script files), `-X` and `-Spath` — is native to this install, and
project deps resolve through its built-in tools.deps subset (config
chain, aliases, `:local/root`, git deps, Maven coordinates; directory
roots fully supported, jar-only deps the named limit). Other `-T` aliases,
`-Sdescribe` and the `:jvm` target delegate to the real Clojure CLI
when one is installed, and stop and say so when there isn't. Each
runtime other than the JVM is its own install, served by `bin/cohost`
from a checkout (that route still uses Babashka): `dev/install/<runtime>.sh`,
on Linux and macOS (and Windows under Git Bash where the runtime ships
for it), or `bash dev/install/all.sh` for every one (see
[Choosing a runtime](07-choosing-a-runtime.md)). On Windows,
`bin\cohost.cmd` runs `bin/cohost` with `bb`.

## A project

The README's "A new project" makes `acme/myapp` from the `cohost/app` template. In it:

```bash
cohost -M -m acme.myapp you       # => Hello, you!
cohost -X:test                    # => Testing acme.myapp-test
# => Ran 1 tests containing 2 assertions.
# => 0 failures, 0 errors.
```

`deps.edn` carries `:cohost/target`, the runtime `cohost` uses for this project; the next
pages go through the command, dependencies, the REPL, tests and builds.
