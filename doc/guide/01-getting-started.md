# Getting started

*2026-11-02. The commands on this page run, as written, in `bb docs:check`.*

cohost runs Clojure 1.12: `clojure.core`, `clojure.string`, `clojure.test` and the other
`clojure.*` libraries are Clojure's own source files, read and evaluated on the runtime your
project names. You use it the way you use `clj`: a `deps.edn`, the REPL, `-M`, `-X`, `-T`,
tools.build. The command is `cohost`.

## Install

You need [Jank](https://jank-lang.org). That is the only prerequisite:
the installer is a Clojure program that runs on Jank, and everything it
builds is built by Jank — no Babashka, no Clojure CLI.

```bash
# not checked: installs
curl -fsSL https://raw.githubusercontent.com/johnmn3/cohost.cljc/main/install.cljc -o install.cljc
jank run install.cljc
cohost -Ttools install-latest :lib io.github.seancorfield/deps-new :as new
```

`jank run install.cljc` fetches the checkout if you don't have one,
builds cohost's compiled core snapshot with Jank alone (minutes, the
first time), compiles the `cohost` command into `~/.local/bin` and
records the checkout in `~/.config/cohost/install.edn`. Add
`~/.local/bin` to your PATH if it isn't there (the installer says so
when it isn't). From an existing checkout, `jank run install.cljc
--repo ~/cohost` installs from it instead.

The default target (Jank) — `-M`, `-X`, `-e`, the REPL — and
`-Ttools install` are native to this install. Other `-T` aliases,
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
