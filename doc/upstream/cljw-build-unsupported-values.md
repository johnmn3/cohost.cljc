# ClojureWasm: `cljw build` fails with `UnsupportedValueTag` on two kinds of value

Found by cohost (doc/AOT-PLAN.md item 5), ClojureWasm v1.15.4, Linux x86_64, 2026-11-03.

`cljw build <file> -o <out>` evaluates the program, then writes its values into the binary.
Two kinds of value that the same program holds without trouble when run as a script stop
the build with `build failed: UnsupportedValueTag` (exit 1).

## 1. A PersistentQueue

```clojure
(ns probe)
(def v clojure.lang.PersistentQueue/EMPTY)
(println :ok)
```

`cljw probe.clj` prints `:ok`. `cljw build probe.clj -o probe` prints `:ok`, then
`build failed: UnsupportedValueTag`. Any reference to `PersistentQueue/EMPTY` in compiled
code does the same: `(fn [] clojure.lang.PersistentQueue/EMPTY)`,
`(fn [] (into clojure.lang.PersistentQueue/EMPTY []))`. Looking it up at run time builds:
`(fn [] (eval 'clojure.lang.PersistentQueue/EMPTY))` builds and works in the binary.

## 2. An integer outside the signed 48-bit range

```clojure
(ns probe)
(def v 140737488355328)   ; 2^47
(println :ok)
```

Fails the same way. Measured boundaries: `140737488355327` (2^47 - 1) and
`-140737488355328` (-2^47) build; `140737488355328` and `-140737488355329` fail, as do
`Long/MIN_VALUE`, `Long/MAX_VALUE`, `9007199254740992` and `(- -9223372036854775807 1)`.
All are `Long` (`(type Long/MIN_VALUE)` -> `Long`), so the boxed (out-of-immediate-range)
integer seems to have no tag in the build's value writer. `(bigint 5)` builds.

## Values checked that build

`(volatile! nil)`, `(atom {})`, `(atom #{})`, a regex, `(delay 1)`, fns and maps of fns,
`java.lang.String` (a class), `(find-ns 'clojure.core)`, a var, `(agent 1)`, `*out*`,
`*in*`, an `ArraySeq`, a multimethod, a protocol, `(Object.)`, a deftype instance, a
`StringWriter`.

## Effect on cohost

cohost's shim holds both: `(def PersistentQueue-EMPTY (host/empty-queue))` and
`(def long-min-value (- -9223372036854775807 1))` (src/cohost/shim.cljc), shared by every
runtime, so `cljw build` of anything that loads `cohost.shim` fails. The ClojureWasm
artifact ships the recorded boot as cljw modules instead, run by `cljw` (0.9 s for the
demo app).
