# AOT-compiled `(Long/parseLong "-zz" 36)` throws; the same source returns -1295

Status: REPRODUCED

Host: jank 0.1-noble (`jank compiler jank-0.1-alpha`). The function is cohost's `cohost.shim/Long-parseLong` (`parse-radix` in `src/cohost/shim.cljc`), compiled by `jank compile-module cohost.run -O3` into `shim.o`.

## Repro

`/tmp/upstream/10-parse.jank`:

```clojure
(require 'cohost.shim)
(println "parse" (try
                   (cohost.shim/Long-parseLong "-zz" 36)
                   (catch cpp/jank.runtime.object_ref e
                     (str "CAUGHT " (pr-str e)))))
```

Source only:

```
jank --module-path /workspace/src run /tmp/upstream/10-parse.jank
```

stdout (exit 0):

```
parse -1295
```

The same file with the object directory ahead of `src` (jank loads `shim.o` because the object is newer and the source is still on the path):

```
jank --module-path /workspace/target/jank-aot/_cache/x86_64-unknown-linux-gnu-d41ef4a07a9d4dd9963044192227fbfde2da40c9f817907bab326588f8e7ef94:/workspace/src run /tmp/upstream/10-parse.jank
```

stdout (exit 0), cause line:

```
parse CAUGHT #error {
 :cause "For input string: "-zz" under radix 36"
```

The `:data` is `{:cohost/class java.lang.NumberFormatException}` and the trace includes `cohost.shim/parse-radix (arity 4)`.

## Expected

Both runs return `-1295`. In base 36, `z` is 35, so `zz` is `35 * 36 + 35 = 1295`, and the leading minus makes it `-1295`. That value is inside a long's range.

## Actual

The source run prints `parse -1295`. The AOT run throws `NumberFormatException` with message `For input string: "-zz" under radix 36`.

## Cohost today

This tree evaluates `cohost.shim` from source, where the call returns -1295. Nothing rewrites `parse-radix` for AOT.
