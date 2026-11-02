# Embedding cohost

The `cohost` command (README.md) is how you develop in cohost. This page is the other use:
cohost as a library inside a host program, a stock-Clojure sub-runtime you boot and hand source strings to.

## The API

```clojure
(require 'cohost.hosts.auto)                ; installs this host's adapter
(require '[cohost.boot :as boot])

(boot/boot!)                                ; -> the report map
(boot/make-user-ns!)                        ; cohost.user sees only stock
(boot/eval-str "(->> (range 10) (map inc) (filter even?) (reduce +))")   ; 30
(boot/eval-str "(require '[clojure.set :as set])")                      ; the stock clojure.set
(boot/eval-str "(set/union #{1} #{2})")                                 ; #{1 2}
(boot/eval-str "(ns my.app (:require [clojure.string :as str])) (str/upper-case \"hi\")")
```

`cohost.core/map`, `cohost.core/let` and the rest are the stock definitions;
`(meta #'cohost.core/map)` carries core.clj's docstring and arglists.
`eval-str` reads each form of its source after the one before it ran, in the
namespace that one left current, as a REPL session does (a `::kw` after an
`ns` form is that namespace's), and keeps the namespace for the next call:
after the `ns` form above, `(boot/eval-str "*ns*")` is `my.app`. A
`require` (or an `ns` form's `:require`) of a library whose source is on the
source roots boots it stock — `clojure.X` into `cohost.X`, any other library
under its own name — and falls back to the host's own `require` otherwise.
`boot/add-source-root!` adds a directory to search; `boot/lib-report` says
what each library's load did.

