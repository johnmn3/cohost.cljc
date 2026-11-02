# Upstream drafts

Drafts only. Nothing here has been filed. Each file is one bug, with the command that was run and the output it printed. Hosts: jank `0.1-noble` (`jank compiler jank-0.1-alpha`) and clojurust `cljrs 0.2.32`. jank-11 to jank-21 (lane R, order R-2, 2026-11-02) are the performance and correctness items of doc/research/jank-faster-than-jvm.md; their repro scripts are under `dev/research/upstream/`.

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
| [jank-10-aot-long-min-literal.md](jank-10-aot-long-min-literal.md) | REPRODUCED. The literal `-9223372036854775808` is a different garbage double on every AOT run; `(- -9223372036854775807 1)` stays the long |
| [jank-11-sorted-walk.md](jank-11-sorted-walk.md) | REPRODUCED. Walking `sort`'s result is O(n^2): 1e5 sorts in 11 ms, reducing the result takes 27 s |
| [jank-12-i32-add.md](jank-12-i32-add.md) | REPRODUCED. `(+ 2147483647 1)` is `-2147483648`; `+ - *` on two small integers compute in i32 |
| [jank-13-eq-inline.md](jank-13-eq-inline.md) | REPRODUCED. `=` is not inlined: 13 ns more per test than `==` |
| [jank-14-keyword-hash.md](jank-14-keyword-hash.md) | REPRODUCED. A keyword's hash is recomputed per lookup: `get` is 2.4x slower with a 62-char keyword |
| [jank-15-reduce-walk.md](jank-15-reduce-walk.md) | REPRODUCED. `reduce` walks with an allocating `next()`: 2.5-6x an in-place or indexed loop |
| [jank-16-lazy-mutex.md](jank-16-lazy-mutex.md) | REPRODUCED. `first` on a realized lazy seq is 5x `first` on a list (a mutex per call) |
| [jank-17-range-chunks.md](jank-17-range-chunks.md) | REPRODUCED. `(range n)` and vector seqs are not chunked; the lazy map/filter pipeline is 185x the loop |
| [jank-18-small-vectors.md](jank-18-small-vectors.md) | REPRODUCED. A `[i i]` costs 245 ns (three allocations, a 32-slot leaf); a bare GC allocation 24 ns |
| [jank-19-gc-defaults.md](jank-19-gc-defaults.md) | REPRODUCED (settings only). 110 collections per run; incremental mode 3-6x slower; heap knobs move both ways |
| [jank-20-native-returns.md](jank-20-native-returns.md) | REPRODUCED. A `^long` leaf fn is 1.6x slower than the unhinted one; a return hint changes nothing |
| [jank-21-int32-loop-local.md](jank-21-int32-loop-local.md) | REPRODUCED. A loop local from an integer literal is a 32-bit `int`: ten `inc`s from 2147483640 give `-2147483646` |
| [cljrs-11-ir-identical.md](cljrs-11-ir-identical.md) | REPRODUCED. With `CLJRS_IR_THRESHOLD=1`, `true?` / `false?` / `identical?` are false from the third call |
| [cljrs-12-vec-string-gc.md](cljrs-12-vec-string-gc.md) | REPRODUCED. `vec` of `core.clj` pauses 364.402s under a 32 MB soft limit, 302 ms with no collection |
| [cljrs-13-ir-closure-crash.md](cljrs-13-ir-closure-crash.md) | NOT REPRODUCED. Four full boots under gdb exited normally (`No stack.`) |

jank-07 is gone. jank's own `clojure.string/join` of `(range 20000)` is 108889, the right length. The 58 was cohost's stock side.
