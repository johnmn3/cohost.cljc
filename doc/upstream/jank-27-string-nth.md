# String `nth` boxes a fresh character and re-checks its index on every call

Status: REPRODUCED (patch: `upstream/jank/patches/27-string-nth-str.patch`)

Host: jank built from source at jank-lang/jank `f3d82d65` with the series of `upstream/jank/patches/series` up to `23-chunk-branch` (the base) and with `27` on top, Ubuntu noble, 4 CPUs shared with other lanes. Source citations are to `f3d82d65`.

## Repro

`dev/research/upstream/27-string-nth.jank` in johnmn3/cohost.cljc, run as `bash dev/research/upstream/run.sh 27-string-nth` (each binary with its own `XDG_CACHE_HOME`). It times P5's `string-nth-1e6` shape (k-nucleotide's inner loop: `(= (nth s i) \t)` over a 1e6-character string) against the same loop without `nth`, and `apply str` over 1e4 short strings (json's printer). The JVM twin (`dev/research/p5_seqs.clj`): `string-nth-1e6` 8406315 ns, `apply-str-1e4` 425450 ns.

## Expected

String `nth` costs about what the JVM's `String.charAt` plus `Character.valueOf` costs: no allocation for a byte-sized character, one type check, one bounds check.

## Actual

Base (the series to 23) then patched (+27), three interleaved rounds, each binary with its own `XDG_CACHE_HOME`, box shared with other lanes:

```
string nth 1e6, (= (nth s i) \t)   61605819 / 49796178 / 51231615  ->  21253650 / 21448242 / 20510852 ns
string count 1e6 loop, no nth       8912671 /  7405674 /  8307830  ->   7291667 /  7370437 /  7325568
apply str over 1e4 strings           147983 /   133700 /   123856  ->    186074 /   180710 /   178094
identical? (nth s 3) (nth s 7)        false                         ->   true
```

On the unpatched f3d82d65 (`jank-dev`) the same nth loop takes 1770725751 ns (the `=` against a character literal is jank-13's). String `nth` with 27 is 2.5x the JVM twin's 8406315. `apply str` over 1e4 strings already beats the JVM twin's 425450 ns on the series with 15a (in-place walks), so `str` needed no change; its base/patched spread is noise (four more interleaved rounds of a str-only probe: base 190806 / 251490 / 179650 / 349449, patched 360342 / 198962 / 175914 / 198788).

## Where it is in the source

`runtime::nth` (`runtime/core/seq.cpp`) unboxes the index, then `visit_object`s the collection, then calls the virtual `nth(object_ref)`, which checks `is_integral` and unboxes again (`obj/persistent_string.cpp:107`); the string's `nth` then allocates a new `character` with a `jtl::immutable_string` holding one byte (`persistent_string.cpp:118`) on every call.

## What would fix it

What the patch does: `runtime::nth` (both arities, and a new `nth_i64(object_ref, i64)`: a separate name, since an `nth` overload on `i64` makes core.jank's `:inline` `cpp/jank.runtime.nth` call ambiguous under jank's automatic trait conversions) reads a string or a vector directly when the index is an in-range integer, and falls to the general path otherwise (bounds errors, other types, not-found unchanged); a string's `nth` returns one of 256 shared character boxes (made once in uncollectable memory; characters are immutable, so sharing them is safe, as the JVM's `Character.valueOf` caches). Byte-indexed semantics (`count` and `nth` on bytes) are unchanged: that is a separate host fact.
