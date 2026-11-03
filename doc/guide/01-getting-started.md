# Getting started

*2026-11-02. The commands on this page run, as written, in `bb docs:check`.*

cohost runs Clojure 1.12: `clojure.core`, `clojure.string`, `clojure.test` and the other
`clojure.*` libraries are Clojure's own source files, read and evaluated on the runtime your
project names. You use it the way you use `clj`: a `deps.edn`, the REPL, `-M`, `-X`, `-T`,
tools.build. The command is `cohost`.

## Install

You need [Babashka](https://babashka.org) (`bb`), the
[Clojure CLI](https://clojure.org/guides/install_clojure) (`clojure`) and Java (the Clojure
CLI resolves dependencies with it, whatever runtime runs your code).

```bash
# not checked: installs
git clone https://github.com/johnmn3/cohost.cljc ~/cohost
export PATH="$HOME/cohost/bin:$PATH"
clojure -Ttools install-latest :lib io.github.seancorfield/deps-new :as new
```

The `export` lasts for this shell; add it to `~/.zshrc` (or `~/.bashrc`) to keep `cohost` on
PATH. On Windows, `bin\cohost.cmd` runs `bin/cohost` with `bb`.

Each runtime other than the JVM is its own install: `dev/install/<runtime>.sh` in the
checkout, on Linux and macOS (and Windows under Git Bash where the runtime ships for it), or
`bash dev/install/all.sh` for every one (see [Choosing a runtime](07-choosing-a-runtime.md)).
`cohost :<target> ...` with the runtime missing names the script that installs it.

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
