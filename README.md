# cohost

**A Clojure you develop in: `clojure.core`, `clojure.string`, `clojure.test` and the
rest are Clojure 1.12's own source, unchanged, on the runtime your project picks.**

You write ordinary Clojure, use `deps.edn`, Maven and git dependencies, a REPL, `-M`,
`-X`, `-T` and tools.build exactly as with `clj`. The command is `cohost` instead of
`clojure`; where your code runs is one key in `deps.edn`.

## Install

You need [Babashka](https://babashka.org) and the
[Clojure CLI](https://clojure.org/guides/install_clojure). Then:

```bash
# not checked: installs
git clone https://github.com/johnmn3/cohost.cljc ~/cohost
export PATH="$HOME/cohost/bin:$PATH"
clojure -Ttools install-latest :lib io.github.seancorfield/deps-new :as new
```

## A new project

Projects come from [deps-new](https://github.com/seancorfield/deps-new), with cohost's
template `cohost/app`, published from this repository. Plain `clj` makes one; no cohost
checkout is needed for this step:

```bash
clojure -Sdeps '{:deps {io.github.johnmn3/cohost.cljc {:git/sha "c6b8eaa9983d6cf7f14fa1c6d6e56a4c93ef89bf" :deps/root "templates/cohost-app"}}}' -Tnew create :template cohost/app :name acme/myapp
cd myapp
```

That gives `deps.edn`, `src/acme/myapp.cljc`, `test/acme/myapp_test.cljc` and
`build.clj`; `:target jank` (or any runtime below) at the end of the command writes that
runtime into `deps.edn`. From a cohost checkout, `cohost -Tnew create :template cohost/app
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

On a terminal it reads a line at a time; wrap it in `rlwrap` for editing and history.

## Dependencies

`deps.edn` is resolved by tools.deps itself (through the Clojure CLI), so Maven
coordinates, git deps, `:local/root`, aliases and `-Sdeps` work as they do for `clj`:

```bash
cohost -Sdeps '{:deps {org.clojure/tools.cli {:mvn/version "1.2.245"}}}' -e "(require '[clojure.tools.cli :as cli])" -e '(:options (cli/parse-opts ["-v"] [["-v" "--verbose"]]))'
# => {:verbose true}
```

A library's Clojure sources load through cohost, so they run on stock `clojure.core` on
any runtime. `cohost -Spath` prints the source roots.

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

A project without the key runs on the JVM (the real `clojure`). `cohost :<target> ...`
overrides the key for one command:

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

What differs from one runtime to the next, and startup and speed for each:
[Choosing a runtime](doc/guide/07-choosing-a-runtime.md). The per-host performance switches:
[How it works](doc/guide/08-how-it-works.md#performance-keys).

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

Also: [the `cohost` command's reference](doc/CLI.md), [embedding cohost in a host
program](doc/EMBEDDING.md), [performance](doc/PERF.md), [the measurements and host
facts](doc/REPORT.md).

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
`cohost.battery` — 167 stock behaviors evaluated in a namespace that sees only
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
