# Intermittent crash in an IR closure (`alloc_closure` / `str`)

Status: NOT REPRODUCED

Host: clojurust CLI `cljrs 0.2.32` (`cljrs --version`). `CLJRS_IR_THRESHOLD=1` and `CLJRS_NO_JIT=1`. gdb 15.1 (`gdb --version` is the Ubuntu package `gdb 15.1-1ubuntu1~24.04.1`).

## Repro

Two small programs, eight runs each. `/tmp/upstream/cljrs/13-closure.cljrs` builds a closure over a map and calls `str` on the capture, 4000 times. `/tmp/upstream/cljrs/13-atom.cljrs` does the same over an atom. Every run exited 0. The closure runs printed `closure-ok`. The atom runs printed `atom-ok 4000`.

Four full cohost boots under gdb:

```
gdb -batch -ex "set pagination off" -ex "set env CLJRS_IR_THRESHOLD 1" -ex "set env CLJRS_NO_JIT 1" -ex run -ex bt --args cljrs run --src-path src --src-path target/cljrs-ns src/cohost/run.cljc
```

Each one printed:

```
cohost.boot on cljrs: 791 top-level forms
  booted as stock: 561   from host: 95   skipped: 135   errors: 0
battery: 95/139 stock behaviors pass
libraries: 0/61 cases pass across 12 clojure.* libraries
```

and gdb then printed:

```
[Inferior 1 (process 21627) exited normally]
No stack.
```

(The process id differs per run. The other three logs end with `exited normally` and `No stack.` as well. gdb's own exit status on those runs was 1, which is gdb reporting that `bt` had no stack after a normal exit, not a signal from `cljrs`.)

## Expected

If the IR still crashes about half of full boots, at least one of these four boots stops with a segfault or an absurd allocation failure, and `bt` shows `ir_interp::alloc_closure` and `dispatch_known_fn` on the way into `str`.

## Actual

Eight closure runs, eight atom runs, and four full boots all finished. There is no faulting backtrace from this session. `bt` after each boot is `No stack.` because the inferior exited normally.

## Cohost today

cohost has no fix for this crash. `cohost.hosts.cljrs`'s `walked` macro keeps some functions tree-walked so they are not lowered, which avoids the IR for those functions only.
