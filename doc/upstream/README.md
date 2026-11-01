# Upstream drafts

Drafts only. Nothing here has been filed. Each file is one bug, with the command that was run and the output it printed. Hosts: jank `0.1-noble` (`jank compiler jank-0.1-alpha`) and clojurust `cljrs 0.2.32`.

| draft | status |
|---|---|
| [jank-01-macro-form-env.md](jank-01-macro-form-env.md) | REPRODUCED. `&form` is `((1))`, `&env` is the macro name |
| [jank-02-lexer-non-ascii.md](jank-02-lexer-non-ascii.md) | REPRODUCED under the C locale. An em dash fails (`lex/invalid-unicode`, `lex/unterminated-string`); `C.UTF-8` reads it |
| [jank-03-tag-quoted-class.md](jank-03-tag-quoted-class.md) | REPRODUCED. `^Class` is `:tag (quote Class)` |
| [jank-04-todo-stubs.md](jank-04-todo-stubs.md) | REPRODUCED. `class`, `instance?`, `indexed?`, `rseq` throw TODO |
| [jank-05-catch-default-nil.md](jank-05-catch-default-nil.md) | REPRODUCED. `(catch :default e)` binds nil |
| [jank-06-push-thread-bindings-array-map.md](jank-06-push-thread-bindings-array-map.md) | REPRODUCED. An array map panics; a hash map binds |
| [jank-08-unknown-type-panic.md](jank-08-unknown-type-panic.md) | REPRODUCED. `count`, `conj`, `assoc`, `meta` abort on an unknown object type |
| [jank-09-aot-namespace-named-jank.md](jank-09-aot-namespace-named-jank.md) | REPRODUCED. `compile-module repro.jank` : `jank::runtime` resolves inward |
| [jank-10-aot-parse-long.md](jank-10-aot-parse-long.md) | REPRODUCED. Source `Long-parseLong` of `"-zz"` base 36 is `-1295`; the AOT object throws |
| [cljrs-11-ir-identical.md](cljrs-11-ir-identical.md) | REPRODUCED. With `CLJRS_IR_THRESHOLD=1`, `true?` / `false?` / `identical?` are false from the third call |
| [cljrs-12-vec-string-gc.md](cljrs-12-vec-string-gc.md) | REPRODUCED. `vec` of `core.clj` pauses 364.402s under a 32 MB soft limit, 302 ms with no collection |
| [cljrs-13-ir-closure-crash.md](cljrs-13-ir-closure-crash.md) | NOT REPRODUCED. Four full boots under gdb exited normally (`No stack.`) |

jank-07 is gone. jank's own `clojure.string/join` of `(range 20000)` is 108889, the right length. The 58 was cohost's stock side.
