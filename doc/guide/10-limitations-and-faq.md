# Limitations and FAQ

*2026-11-02. The details and the measurements behind each are in `doc/REPORT.md`.*

## What cohost does not do

- **The JVM itself.** Proxies, `gen-class`, agents' thread pools, the class loader and
  `java.io` streams are the JVM's; their forms in `core.clj` are skipped off the JVM. Java
  libraries (classes, not Clojure source) exist only on `:jvm`.
- **Type hints** are JVM compiler directives: elsewhere a `^long` does not saturate or coerce
  as on the JVM (jank uses some of them to pick native types).
- **Printing** is the host's: names print under `cohost.*` (`#'cohost.core/map`), class tokens
  are symbols (`(str (class ""))` is `"java.lang.String"`).
- **Chunked seqs** are the host's own seqs; results are the same, `chunked-seq?` differs.
- **Jars** contribute their Clojure sources only: no resources, no `data_readers.clj`.
- **nREPL** is not part of `cohost` yet; the REPL has no line editing (use `rlwrap`).
- **clojurust** is not wired into the `cohost` command.

## FAQ

**Is this a new Clojure dialect?** No: the language is Clojure 1.12's own `core.clj` and
libraries, unchanged. cohost rewrites their JVM interop at load time and maps it onto the
runtime underneath. Where a runtime differs at the bottom (numbers, characters, regex), the
difference is listed in [Choosing a runtime](07-choosing-a-runtime.md).

**Do my dependencies need to be written for cohost?** No. Libraries in Clojure source load
through cohost on every runtime; their reader conditionals read as on the JVM (`:clj`, else
`:default`). A library whose JVM interop goes beyond what the shim covers fails on the other
runtimes with the missing class or method named.

**Why is startup slower than the runtime's own?** cohost boots `clojure.core` from source
on each run; jank loads a compiled snapshot instead (see
[How it works](08-how-it-works.md)).

**Where do the numbers come from?** Every number in these pages names the commit it was
measured on; `doc/PERF.md` and `doc/REPORT.md` have the commands to reproduce them.
