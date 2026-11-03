# deps.edn and libraries

*2026-11-02.*

`deps.edn` is resolved by tools.deps itself, through the Clojure CLI, so the
root/user/project configuration chain, aliases, `-Sdeps`, Maven coordinates, git deps and
`:local/root` all behave as for `clj`. Each classpath entry becomes a source root that
cohost's loader searches when your code `require`s a namespace:

- a directory (your `:paths`, a `:local/root` library, a git dep's checkout) as it is;
- a jar: its `.clj` and `.cljc` files, extracted once to `~/.cache/cohost/deps/`;
- `org.clojure/clojure`'s jar is left out: the language is the Clojure 1.12.0 source cohost
  boots, whatever version `deps.edn` names. `clojure.string` and the other `clojure.*`
  libraries load from those sources too.

```bash
cohost -Sdeps '{:deps {org.clojure/tools.cli {:mvn/version "1.2.245"}}}' -e "(require '[clojure.tools.cli :as cli])" -e '(:options (cli/parse-opts ["-v"] [["-v" "--verbose"]]))'
# => {:verbose true}
```

## How a dependency's code reads

A dependency's namespaces are read and rewritten by cohost like your own code, so they run on
the stock `clojure.core` on every runtime. Reader conditionals read as the JVM reads them, on
every runtime: the first `:clj` branch, else `:default`. A `.cljc` library written for Clojure
therefore takes its Clojure branch everywhere, and its JVM interop goes through cohost's shim
of `clojure.lang`/`java.*` (what the shim covers is in `doc/REPORT.md`).

## What does not load

- Resources inside a jar (data files, `data_readers.clj`) are not extracted.
- A jar with no sources (AOT-only) contributes nothing.
- `.cljs`-only libraries do not load: the loader reads `.clj` and `.cljc`.
- Java libraries (classes, not Clojure sources) exist only on `:jvm`.
