# `vec` of a string is quadratic under GC

Status: REPRODUCED

Host: clojurust CLI `cljrs 0.2.32` (`cljrs --version`). `CLJRS_IR_THRESHOLD=0` and `CLJRS_NO_JIT=1`.

## Repro

Without a tight heap, `/tmp/upstream/cljrs/12b-vec.cljrs` (`(vec s)` once per size, `s` built with `(apply str (repeat n "a"))`, then `(slurp .../clojure/core.clj)`):

```
CLJRS_IR_THRESHOLD=0 CLJRS_NO_JIT=1 cljrs run --gc-stats /tmp/upstream/cljrs/12b-gc.txt /tmp/upstream/cljrs/12b-vec.cljrs
```

stdout (exit 0):

```
n 8000 count 8000 ms 6
n 16000 count 16000 ms 14
core
core-chars 276144 vec 276144 ms 302
done
```

That GC file says `GC collections: 0` and `Total GC pause time: 0.000ns`.

The same `vec` of `core.clj` with a 32 MB soft limit, `/tmp/upstream/cljrs/12c-gc.cljrs`:

```
CLJRS_IR_THRESHOLD=0 CLJRS_NO_JIT=1 cljrs run --gc-soft-limit-mb 32 --gc-stats /tmp/upstream/cljrs/12c-gc.txt /tmp/upstream/cljrs/12c-gc.cljrs
```

stdout:

```
start
chars 276144 vec 276144 ms 121117
done
```

The process wall time was 364816 ms. The GC file says `GC collections: 3` and `Total GC pause time: 364.402s`.

Shorter strings under the same 32 MB limit, one process each (`(vec (apply str (repeat n "a")))`):

| n | printed ms | wall s | GC collections | Total GC pause time | GC allocations bytes |
|---:|---:|---:|---:|---|---:|
| 10000 | 7 | 1.809 | 28 | 714.258ms | 424930319 |
| 20000 | 15 | 3.815 | 58 | 1.586s | 847176703 |
| 40000 | 33 | 8.927 | 128 | 4.078s | 1691669471 |

## Expected

`(vec s)` for a string is linear in `(count s)`. A collection during it traces the vector being built, not every intermediate suffix.

## Actual

With the collector held off (0 collections), the times stay small (6 ms, 14 ms, 302 ms). With `--gc-soft-limit-mb 32`, the pause grows faster than the length: 4× the characters (10_000 to 40_000) is 5.7× the pause (714.258ms to 4.078s), and `core.clj` at 276144 characters pauses 364.402s. Bytes allocated from 10_000 to 40_000 roughly double when the length doubles (424930319, 847176703, 1691669471).

## Cohost today

`cohost.hosts.cljrs/string-vec` builds the vector 512 characters at a time with `subs` and `into`, and the reader uses that instead of `(vec s)` on a whole source file.
