# Building and deploying

*2026-11-02. Measured with `bb readme:check` on jvm, jolt, cljs, cljw and lg.*

Builds are [tools.build](https://clojure.org/guides/tools_build): `cohost -T:build build` runs
the project's `build.clj` as `clojure -T:build build` does. The template's `build.clj` calls
`cohost.build/build`, which makes the artifact for the project's runtime (`:target` in the
task's arguments, else `:cohost/target`, else `:jvm`). Every artifact is a directory
`target/<name>/` whose `run` starts the project's `-main` from any directory:

```bash
# targets: jvm jolt cljs cljw lg lpy phel
cohost -T:build build                               # => build: target/myapp/run
(cd /tmp && "$OLDPWD"/target/myapp/run you)         # => Hello, you!
```

The `:build` alias depends on cohost as a git dependency on this repository at the commit the
template came from, so `-T:build` needs no cohost checkout.

| runtime | `target/<name>/` holds | the machine running it needs |
|---|---|---|
| `:jvm` | `<name>.jar`, an uberjar with `clojure.main` as its main, and `run` (`java -jar <name>.jar -m <main>`) | Java |
| `:cljs` | cohost's ClojureScript build, Clojure's sources, the project's source roots, `run.js`, `run` | Node |
| `:jolt` | `bin/cohost-jolt` (cohost compiled by `jolt build` into one native binary, 36 MB), Clojure's sources, the source roots, `run` | nothing else |
| `:cljw`, `:lg`, `:lpy`, `:phel` | cohost's sources, Clojure's sources, the source roots, `run` (a script bundle) | that runtime installed |
| `:jank` | an executable from jank's AOT compiler (in progress) | |
| `:cljrs` | not yet | |

ClojureWasm and let-go have native packagers of their own; on cohost they stop with
`build failed: UnsupportedValueTag` (`cljw build`) and `unsupported value type for
serialization: *vm.Ratio` (`lg -b`), so those two ship a script bundle. Details and the
command each `run` uses: [`doc/CLI.md`](../CLI.md), "Building".
