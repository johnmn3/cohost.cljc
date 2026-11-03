# The cohost command

*2026-11-02. Blocks marked otherwise run in `bb docs:check`.*

```text
cohost [:<target>] <clojure CLI arguments...>
```

## The target

The first argument may name the runtime: `cohost :cljs -e '(+ 1 2)'`. Without it, the
runtime is `:cohost/target` in `./deps.edn`, and without that `:jank`. `:jvm` runs the real
Clojure CLI unchanged: the reference cohost is checked against (`cohost :jvm ...`, or
`:cohost/target :jvm`).

## Arguments, as the Clojure CLI reads them

| argument | what it does |
|---|---|
| `-e expr`, `--eval` | evaluate; print the value unless it is nil |
| `-i file`, `--init` | load a file (init options run in the order given) |
| `-m ns args...` | call `ns/-main` with the arguments |
| `path args...` | run a script (nothing printed); `-` reads it from standard input |
| `-r`, or no main option | the REPL ([page](04-the-repl.md)) |
| `-M:alias` | the alias's `:main-opts` first, then the command line |
| `-X[:alias] [fn] [k v ...] [map]` | call a function with a map: `:exec-fn`, `:exec-args`, `:ns-default`, `:ns-aliases`, then `key value` pairs (a vector key is a path) |
| `-T...` | a tool: the real Clojure CLI runs it, on every target (deps-new, tools.build) |
| `-A:alias` | aliases for resolution (`:extra-paths`, `:extra-deps`) |
| `-Sdeps edn`, `-Srepro`, `-Sforce`, `-Sthreads n` | passed to tools.deps |
| `-Spath` | the source roots, in classpath order |
| `-P` | resolve and extract dependencies only |
| `-Sdescribe` | the real CLI's |
| `-J...` | dropped off the JVM |

`*command-line-args*` is the arguments after the main option, set before the init options run.

```bash
cohost -e '(+ 1 2)' -e '(str "a" "b")'            # => 3
# => "ab"
cohost -e '(println (count *command-line-args*))' -m acme.myapp you     # => 1
# => Hello, you!
```

## Exit codes and errors

An uncaught error prints `Execution error (ClassName).` and its message to standard error and
exits 1; `(System/exit n)` exits n; a bad option or an unknown target exits 2.

```bash
cohost -e '(throw (ex-info "boom" {}))' 2>/dev/null; echo "exit $?"     # => exit 1
cohost -e '(println "before")' -e '(System/exit 3)'; echo "exit $?"   # => before
# => exit 3
```

`-X` of a function that is not there prints tools.deps' `Namespace x loaded but function not
found: f` and exits 1.

## Environment

- `COHOST_VERBOSE` set: the boot report first (what booted stock, what the host gives).
- `COHOST_CLI_SDEPS`: an extra `-Sdeps` for the ClojureScript build step only.
- Every process `cohost` starts sees `COHOST_HOME`, the checkout it runs from.

Where `cohost` differs from `clojure` in output: an error names its class and message, not
where it was thrown, and writes no report file; names print under `cohost.*`
(`#'cohost.core/map`). The per-runtime launch details are in [`doc/CLI.md`](../CLI.md).
