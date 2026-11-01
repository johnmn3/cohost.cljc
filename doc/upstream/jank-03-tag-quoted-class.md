# `^Class` is read as `:tag (quote Class)`

Status: REPRODUCED

Host: jank 0.1-noble (`jank compiler jank-0.1-alpha`).

## Repro

`/tmp/upstream/03-tag.jank`:

```clojure
(let [form (read-string "(defn f [^String x ^Class y] x)")
      params (nth form 2)]
  (println "string" (pr-str (meta (nth params 0))))
  (println "class" (pr-str (meta (nth params 1)))))
(println "double" (pr-str (meta (nth (nth (read-string "(defn f [^double x] x)") 2) 0))))
```

```
jank run /tmp/upstream/03-tag.jank
```

stdout (exit 0):

```
string {:jank/source {:module "user", :file "nil", :start {:offset 17, :line 1, :col 18}, :end {:offset 18, :line 1, :col 19}}, :tag (quote String)}
class {:jank/source {:module "user", :file "nil", :start {:offset 26, :line 1, :col 27}, :end {:offset 27, :line 1, :col 28}}, :tag (quote Class)}
double {:jank/source {:module "user", :file "nil", :start {:offset 17, :line 1, :col 18}, :end {:offset 18, :line 1, :col 19}}, :tag (quote double)}
```

## Expected

The reader stores a hint as a symbol tag, `:tag String` and `:tag Class`, the way Clojure's reader does.

## Actual

The tag is the list `(quote String)`, `(quote Class)`, and `(quote double)`.

## Cohost today

`cohost.rewrite/strip-meta` drops a `:tag` that is a symbol, a string, or a `(quote SomeClass)` list, so a JVM class hint is removed before jank's compiler tries to resolve it as a C++ type.
