# `class`, `instance?`, `indexed?`, and `rseq` throw TODO stubs

Status: REPRODUCED

Host: jank 0.1-noble (`jank compiler jank-0.1-alpha`).

## Repro

`/tmp/upstream/04c.jank`:

```clojure
(defn attempt [label f]
  (try
    (println label "VALUE" (pr-str (f)))
    (catch cpp/jank.runtime.object_ref e
      (println label "CAUGHT" (pr-str e)))))
(attempt "class" #(class 1))
(attempt "instance?" #(instance? :whatever "a"))
(attempt "indexed?" #(indexed? [1]))
(attempt "rseq" #(rseq [1 2 3]))
```

```
jank run /tmp/upstream/04c.jank
```

exited 0. Each call was caught. The `:cause` of each `#error` (the `pr-str` also prints a `:via` and a `:trace`):

```
class CAUGHT #error {
 :cause "TODO: port class"
instance? CAUGHT #error {
 :cause "TODO: port instance?"
indexed? CAUGHT #error {
 :cause "TODO: port indexed?"
rseq CAUGHT #error {
 :cause "TODO: port rseq"
```

## Expected

`(class 1)` returns the class of the value, `(instance? c x)` tests it, `(indexed? [1])` is true, and `(rseq [1 2 3])` is `(3 2 1)`.

## Actual

All four throw `ex-info` whose message is `TODO: port class`, `TODO: port instance?`, `TODO: port indexed?`, and `TODO: port rseq`.

## Cohost today

The jank adapter answers `:indexed?` with `vector?` (or a cohost type's `:nth`), the shim's `-rseq` is `(seq (reverse coll))`, and cohost's jank code uses `type`'s name string where the JVM would use `class`.
