# In the IR, `identical?`, `true?`, and `false?` are always false

Status: REPRODUCED

Host: clojurust CLI `cljrs 0.2.32` (`cljrs --version`), installed with `rustup run stable cargo install cljrs --version 0.2.32` (Rust 1.99.1; the crate pulls `cljrs-runtime` 0.2.33). `CLJRS_NO_JIT=1` on both runs.

## Repro

`/tmp/upstream/cljrs/11-pred.cljrs`:

```clojure
(defn t [x] (true? x))
(defn f [x] (false? x))
(defn i [a b] (identical? a b))
(defn same-kw [a b] (identical? a b))
(println "start")
(dotimes [n 30]
  (println "n" n "true?" (t true) "false?" (f false) "ident" (i true true) "kw" (same-kw :a :a)))
(println "done")
```

Tree-walked (`CLJRS_IR_THRESHOLD=0`):

```
CLJRS_IR_THRESHOLD=0 CLJRS_NO_JIT=1 cljrs run /tmp/upstream/cljrs/11-pred.cljrs
```

exit 0. Every line `n 0` through `n 29` is:

```
n 0 true? true false? true ident true kw true
```

(the same values for each `n`), then `done`.

Lowered (`CLJRS_IR_THRESHOLD=1`):

```
CLJRS_IR_THRESHOLD=1 CLJRS_NO_JIT=1 cljrs run /tmp/upstream/cljrs/11-pred.cljrs
```

exit 0. `n 0` and `n 1` match the tree-walked line. From `n 2` through `n 29` every line is:

```
n 2 true? false false? false ident false kw false
```

## Expected

`(true? true)` is true, `(false? false)` is true, `(identical? true true)` is true, and `(identical? :a :a)` is true, in a lowered fn as well as a tree-walked one.

## Actual

With `CLJRS_IR_THRESHOLD=1`, from the third call onward (`n 2` .. `n 29`) all four are false. `cljrs-runtime` 0.2.33 `tiered/ir_interp.rs` implements `KnownFn::Identical` as `std::ptr::eq` of the two argument slots (`&args[0]` and `&args[1]`), which are different addresses, and the lowerer emits `true?` / `false?` as that call against the boolean constant.

## Cohost today

Before loading each cohost source namespace, `cohost.hosts.cljrs` interns that namespace's own `identical?`, `true?`, and `false?` holding the host builtins, so the lowerer does not treat those names as `clojure.core`'s vars.
