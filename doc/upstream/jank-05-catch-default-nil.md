# `(catch :default e)` binds `e` to nil

Status: REPRODUCED

Host: jank 0.1-noble (`jank compiler jank-0.1-alpha`).

## Repro

`/tmp/upstream/05-catch.jank`:

```clojure
(println "default" (try
                     (throw (ex-info "boom" {:a 1}))
                     (catch :default e
                       {:bound (nil? e) :e e})))
(println "object-ref" (try
                        (throw (ex-info "boom" {:a 1}))
                        (catch cpp/jank.runtime.object_ref e
                          {:msg (ex-message e) :data (ex-data e)})))
```

```
jank run /tmp/upstream/05-catch.jank
```

stdout (exit 0):

```
default {:bound true, :e nil}
object-ref {:msg boom, :data {:a 1}}
```

## Expected

`(catch :default e)` catches the thrown value and binds `e` to that `ex-info`, so the message is `"boom"` and the data is `{:a 1}`.

## Actual

The `:default` clause runs and `e` is nil. The same throw, caught as `cpp/jank.runtime.object_ref`, binds the exception: message `boom`, data `{:a 1}`.

## Cohost today

`cohost.adapter/try-all` on jank catches `cpp/jank.runtime.object_ref` and `cpp/jank.error_ref` (and uses `(catch :default _)` only to notice a native C++ exception, which arrives with `e` nil).
