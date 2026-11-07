# cohost

> **Pre-alpha. Do not use in production.** Commands, options, build output and the host
> adapter contract can change without notice or a migration path, and each runtime has
> known gaps ([Limitations and FAQ](doc/guide/10-limitations-and-faq.md)).

**A Clojure you develop in: `clojure.core`, `clojure.string`, `clojure.test` and the
rest are Clojure 1.12's own source, unchanged, on the runtime your project picks.**

You write ordinary Clojure, use `deps.edn`, Maven and git dependencies, a REPL, `-M`,
`-X`, `-T` and tools.build exactly as with `clj`. The command is `cohost` instead of
`clojure`; where your code runs is one key in `deps.edn`.

## Install

You need one Clojure-like to run the installer — [Jank](https://jank-lang.org),
Babashka, the Clojure CLI, or [Jolt](https://github.com/jolt-lang/jolt)
— plus Jank itself, which compiles the
`cohost` tool: run the installer on Jank and that is the same binary;
run it on one of the others and the installer uses the Jank on your
PATH, or installs it from the checkout when there is none. (Getting
Jank itself: Homebrew on macOS, jank's apt package on Debian/Ubuntu —
`dev/install/jank.sh` in a checkout runs the right one and says so if
it needs root.)

```bash
curl -fsSL https://raw.githubusercontent.com/johnmn3/cohost.cljc/main/install.cljc -o install.cljc
jank run install.cljc          # or: bb install.cljc
                                # or: clojure -M install.cljc
                                # or: jolt run install.cljc
```

or, from a checkout, `jank run install.cljc -- --repo ~/cohost` (the
other seats take `--repo` the same way). The installer fetches the
checkout if you don't have one
(`~/.local/share/cohost/cohost.cljc`), builds cohost's compiled core
snapshot — driven by the seat you ran, compiled by Jank on every
seat (the first build takes minutes; later runs rebuild only when
the sources changed) — compiles the `cohost` command
into `~/.local/bin` (`--bin DIR` to choose), and records the checkout
in `~/.config/cohost/install.edn`, which is how the command finds it
afterwards. Add `~/.local/bin` to your PATH if it isn't there.

One Clojure-like that is not an installer seat: ClojureCLR. The
snapshot build the installer drives has no ClojureCLR-loadable form
— its driver libraries (babashka.fs, babashka.process) ship no
ClojureCLR reader arms in any release, and the jank-native build's
foundation library is C++ — so `install.cljc` has no ClojureCLR arm.
If those libraries gain ClojureCLR arms, the seat is a
straightforward addition; until then it is named here rather than
discovered mid-install.

Then install the project tool the way the Clojure CLI would — through
`cohost` itself:

```bash
cohost -Ttools install-latest :lib io.github.seancorfield/deps-new :as new
```

That command is native to this install: it keeps the same gitlibs
layout and tool records the Clojure CLI writes, so the two can share a
machine. What runs where, honestly: on the default target (Jank),
`-Ttools install`, `-M` (with `-e`, `-m` and script files), `-X` and
`-Spath` never involve another tool — proven end to end with no
Clojure CLI and no Babashka on the PATH. Project dependencies are
resolved by a built-in subset of tools.deps: the config chain,
aliases (`:extra-paths`, `:extra-deps`, `:main-opts`, `:exec-fn`),
`:local/root` directories, git deps through the shared gitlibs, and
Maven coordinates fetched to the local repository. Directory roots
are fully supported; loading namespaces from inside a jar is the
subset's named limit. Other `-T` aliases, `-Sdescribe` and the
`:jvm` target delegate to the real Clojure CLI when one is installed,
and stop and say so when there isn't. The other runtimes in the table
below are served by `bin/cohost` from a checkout (that route still
uses Babashka), as is Windows (`bin\cohost.cmd`).

## A new project

Projects come from [deps-new](https://github.com/seancorfield/deps-new), with cohost's
template `cohost/app`, published from this repository. Plain `clj` makes one; no cohost
checkout is needed for this step:

```bash
clojure -Sdeps '{:deps {io.github.johnmn3/cohost.cljc {:git/sha "2563eee3627fac0adf75a3b1358af48cd55cc64e" :deps/root "templates/cohost-app"}}}' -Tnew create :template cohost/app :name acme/myapp
cd myapp
```

That gives `deps.edn`, `src/acme/myapp.cljc`, `test/acme/myapp_test.cljc` and
`build.clj`. The project runs on jank: its `deps.edn` names `:cohost/target :jank`.
`:target <host>` at the end of the command picks another runtime (any of the table below);
`:target jvm` is the reference, the real Clojure CLI that cohost is checked against. From a cohost checkout, `cohost -Tnew create :template cohost/app
:name acme/myapp` is the same template at the checkout's commit. The project's `:build`
alias names cohost by that commit too, so `-T:build` works with or without a checkout.

## Run it

```bash
cohost -M -m acme.myapp           # => Hello, world!
cohost -M:main you                # => Hello, you!
cohost -X:run :who '"cohost"'     # => Hello, cohost!
cohost -e '(+ 1 2)'               # => 3
```

`-M -m ns args` calls `ns/-main`, `-M:alias` adds the alias's `:main-opts`, `-X` calls a
function with a map (an alias's `:exec-fn` and `:exec-args`, then `key value` pairs from
the command line), and `-e`/`-i`/a script path run as `clojure.main` runs them.

## The REPL

`cohost` with no main option is the REPL, as `clj` is:

```bash
printf '(require (quote [clojure.string :as str]))\n(str/upper-case "hi")\n' | cohost
# => Clojure 1.12.0
# => user=> nil
# => user=> "HI"
# => user=>
```

On a terminal it reads a line at a time; wrap it in `rlwrap` for editing and history (`brew install rlwrap`, `sudo apt install rlwrap`).

## Dependencies

`deps.edn` is resolved by tools.deps itself (through the Clojure CLI), so Maven
coordinates, git deps, `:local/root`, aliases and `-Sdeps` work as they do for `clj`:

```bash
cohost -Sdeps '{:deps {org.clojure/tools.cli {:mvn/version "1.2.245"}}}' -e "(require '[clojure.tools.cli :as cli])" -e '(:options (cli/parse-opts ["-v"] [["-v" "--verbose"]]))'
# => {:verbose true}
```

A library's Clojure sources load through cohost, so they run on stock `clojure.core` on
any runtime. `cohost :<target> -Spath` prints the source roots it loads from (on `:jvm` it is
the Clojure CLI's own `-Spath`, the classpath).

## Tests

`clojure.test`, through the template's `:test` alias:

```bash
cohost -X:test
# => Testing acme.myapp-test
# => Ran 1 tests containing 2 assertions.
# => 0 failures, 0 errors.
```

## Building

Builds are [tools.build](https://clojure.org/guides/tools_build): `cohost -T:build <task>`
runs the project's `build.clj`, as `clojure -T:build` does. cohost adds `cohost.build`,
whose `build` makes the artifact for the project's runtime: a directory `target/<name>/`
whose `run` starts your `-main`, from any directory:

```bash
# targets: jvm jolt jank cljs cljw lg lpy phel
cohost -T:build build                               # => build: target/myapp/run
(cd /tmp && "$OLDPWD"/target/myapp/run you)         # => Hello, you!
```

What is inside for each runtime (an uberjar, a native binary, a bundle) is in
`doc/CLI.md` ("Building").

## Choosing a runtime

The runtime is a key in `deps.edn`:

```clojure
{:cohost/target :jank
 :paths ["src"]}
```

A project without the key runs on jank. `:jvm` is the reference: the real `clojure`, which every
check compares the other runtimes with. `cohost :<target> ...` overrides the key for one command:

```bash
cohost :jvm -M -m acme.myapp      # => Hello, world!
```

`cohost -Tnew create :template cohost/app :name acme/myapp :target jank` writes the key
for you.

| target | runtime | `cohost` command | install |
|---|---|---|---|
| `:jvm` | JVM Clojure (the real Clojure CLI) | yes | the Clojure CLI |
| `:jolt` | jolt (Chez Scheme) | yes | `dev/install/jolt.sh` |
| `:jank` | jank (C++/LLVM) | yes | `dev/install/jank.sh` |
| `:cljs` | ClojureScript, self-hosted on Node | yes | node |
| `:lpy` | basilisp (Python) | yes | `dev/install/basilisp.sh` |
| `:cljw` | ClojureWasm | yes | `dev/install/cljw.sh` |
| `:phel` | Phel (PHP) | yes | `dev/install/phel.sh` |
| `:lg` | let-go (Go) | yes | `dev/install/lg.sh` |
| `:cljrs` | clojurust (Rust) | not yet | `dev/install/cljrs.sh` |

Every `dev/install/<host>.sh` runs on Linux x86_64 and macOS (arm64; x86_64 where the
host publishes a build), and under Git Bash on Windows where the host ships for Windows
(jolt; let-go through `go install`; basilisp). Each installs its pinned version, checks the
release checksum, skips the work when that version is already on PATH (`COHOST_FORCE=1`
reinstalls), and puts binaries in `/usr/local/bin` when it is writable, else `~/.local/bin`
(`COHOST_BIN` picks another). Missing toolchains come with it: uv for basilisp, PHP 8.5 for
Phel (Homebrew on macOS, ppa:ondrej/php on Ubuntu), Rust for clojurust (rustup). jank comes
from jank's apt repository on Ubuntu and from jank's Homebrew tap on macOS, which Homebrew
asks you to trust once (`brew trust --formula jank-lang/jank/jank`; or run the script with
`COHOST_TRUST_TAPS=1`). `bash dev/install/all.sh` installs every runtime and reports any it
could not.

What differs from one runtime to the next, and startup and speed for each:
[Choosing a runtime](doc/guide/07-choosing-a-runtime.md). The per-host performance switches:
[How it works](doc/guide/08-how-it-works.md#performance-keys).

jank, as its apt package and Homebrew formula ship today, has known gaps cohost cannot hide:
integers wrap at 32 bits (a fix is in progress upstream), `coll?` is false on lazy seqs, and
two character rows of the battery fail under the C locale (`LANG` unset). The details, and each
runtime's other gaps: [Limitations and FAQ](doc/guide/10-limitations-and-faq.md) and
`doc/REPORT.md`.

## The guide

The guide covers each part of using cohost; every command in it runs, as written, in
`bb docs:check`.

1. [Getting started](doc/guide/01-getting-started.md)
2. [The cohost command](doc/guide/02-the-cohost-command.md): every option, exit codes, errors
3. [deps.edn and libraries](doc/guide/03-deps-and-libraries.md): Maven, git, `:local/root`, how a dependency's `.cljc` reads
4. [The REPL](doc/guide/04-the-repl.md)
5. [Testing](doc/guide/05-testing.md)
6. [Building and deploying](doc/guide/06-building.md): the artifact per runtime
7. [Choosing a runtime](doc/guide/07-choosing-a-runtime.md): what differs per runtime, startup and speed
8. [How cohost works](doc/guide/08-how-it-works.md): the codemod, the shim, the adapter, the boot, snapshots
9. [Adding a host](doc/guide/09-adding-a-host.md)
10. [Limitations and FAQ](doc/guide/10-limitations-and-faq.md)
11. [Debugging](doc/guide/11-debugging.md): the error report, traces as data, `pst`, REPL vars, exit codes — and the jank pathway's own facilities (codegen/IR printing, profiling, gdb break-on-throw)
12. [Threading with Eve](doc/guide/12-threading-with-eve.md): futures, promises, agents and pmap where the host has none — one layer over Eve, worker realms on ClojureScript
13. [Memory with Eve atoms](doc/guide/13-memory-with-eve-atoms.md): cohost's Atom on Eve atoms — what changes, what it costs, and the shared heap across ClojureScript's worker realms
14. [Tiered atoms](doc/guide/14-tiered-atoms.md): choosing an atom's kind by metadata — memory, disk shared between processes, and network atoms
15. [Eve coverage and status](doc/guide/15-eve-coverage-and-status.md): per facility and runtime — what is landed, what is proven, what is not yet

Also: [the `cohost` command's reference](doc/CLI.md), [embedding cohost in a host
program](doc/EMBEDDING.md), [performance](doc/PERF.md), [the measurements and host
facts](doc/REPORT.md), [an out-of-the-box run on macOS and its paper cuts](doc/OOTB-MACOS.md),
[httpbench, the C++ interop capstone on jank](doc/httpbench.md).

## Developing cohost

```bash
# not checked: cohost's own tasks, run in the cohost checkout
bb boot:jolt      # jolt on PATH
bb boot:lpy       # basilisp on PATH  (uv tool install basilisp)
bb boot:cljs      # ClojureScript, self-hosted on Node (needs the clojure CLI + node)
bb boot:browser   # ClojureScript, self-hosted in Chromium (Playwright for Python)
bb boot:jvm       # JVM Clojure as the host: the reference adapter
bb corpus:jolt path/to/corpus.edn         # a conformance corpus (jolt's test/chez/corpus.edn): stock core vs jolt's own
bb corpus:jvm                             # then: which rows the stock core fails JVM Clojure answers as expected
bb eval:jolt '(require (quote [clojure.string :as str]))' '(str/split "a,b" #",")'
bb lint:reader-order                      # cohost's #? arms: :jolt and :cljw ahead of :clj, nothing after :default
```

Two `bb boot:cljs` (or `bench:cljs`, or the CLI's cljs build) at once share
`~/.cljs/.aot_cache/<cljs version>` and can corrupt it: a concurrent compile left
`cljs/spec/alpha.js` truncated, and every later cljs boot failed with
`SyntaxError: missing ) after argument list` until that entry was deleted. Run cljs compiles one
at a time, or give each its own cache.

The hosts of `doc/hosts/PLAN.md` have the same tasks: `bb boot:cljw`, `boot:phel`, `boot:lg`,
`boot:cljrs` and their `bench:` twins. jank's are `bb boot:jank` (from the snapshot or the AOT
objects), `bb boot:jank-jit`, `bb bench:jank`, `bb bench:programs jank` and `bb build:jank`.
Each one's install is `dev/install/<host>.sh`.

Each boots core.clj, prints the boot report (booted / from host / skipped /
errors, every error's root cause, the interop the shim lacks), runs
`cohost.battery` — 216 stock behaviors evaluated in a namespace that sees only
the booted core — and then `cohost.lib-battery`: twelve `clojure.*`
libraries required through the loader, each case checked against the value
JVM Clojure 1.12.0 gives. `cohost.run/-main` takes `--verbose`, `--no-libs`
and `--eval src ...` (evaluate instead of the batteries) on every host, e.g.
`node target/cljs-node/main.js --eval "(binding [*print-length* 2] (pr-str (range)))"`.

## License and provenance

cohost's own work is given away freely; the code it loads keeps its original
copyright and license, untouched, as in
[clinj](https://github.com/johnmn3/clinj):

- **cohost's own code** (the codemod, the shim, the boot loop and loader, the
  adapters, the batteries, the docs) → **0BSD, no copyright claimed**
  (`LICENSE`).
- **The vendored Clojure sources** (`resources/cohost/clojure-1.12.0/`) →
  **EPL-1.0, unchanged** (`LICENSE-EPL-v10.html`): Clojure 1.12.0's
  `core.clj` and its companions, and `walk`, `set`, `string`, `zip`, `data`,
  `edn`, `template`, `test`, `math`, `stacktrace`, `repl`, from the release
  jar (commit `d4bb93f0d1ab2004f89c6ead1b32449fd7ed1a6d`), © Rich Hickey
  (`repl.clj` © Chris Houser); `clojure/spec/alpha.clj` and
  `clojure/spec/gen/alpha.clj` from clojure.spec.alpha 0.5.238 (the version
  Clojure 1.12.0 depends on), © Rich Hickey. cohost's 0BSD never touches them.

The vendored files are byte for byte those of the release jars. Every
transformation is mechanical and happens at load time; nothing in the vendored
tree is edited. Full provenance in [`NOTICE.md`](NOTICE.md).
