# AOT turns the literal `-9223372036854775808` into a garbage double

Status: REPRODUCED

Host: jank 0.1-noble (`jank compiler jank-0.1-alpha`). `jank print-binary-version` printed `x86_64-unknown-linux-gnu-cf627abb22b776c8ebbb6caee09f4f22fe25a16e33827b2ccc2eb155085e5e68`. No cohost code.

The literal is `Long/MIN_VALUE`. In `cohost.shim/parse-radix` it is the lower bound of `(Long/parseLong "-zz" 36)`. The parsed value `-1295` is in range, but `(< -1295 lmin)` is true when `lmin` has become a tiny double, so the function throws `NumberFormatException` (`For input string: "-zz" under radix 36`). That is the same message as a bad digit. The digit loop is not what changes.

## Repro

`/tmp/upstream/jank10/repro/parse.jank`:

```clojure
;; No cohost. jank AOT turns the literal Long/MIN_VALUE into a garbage double.
(ns repro.parse)

(def lmin -9223372036854775808)
(def via (- -9223372036854775807 1))

(defn -main [& _]
  (println "LMIN" lmin)
  (println "VIA" via)
  (println "BARE" (< -1295 -9223372036854775808))
  (println "VIA-LT" (< -1295 via)))
```

Source:

```
jank --module-path /tmp/upstream/jank10 run-main repro.parse
```

stdout (exit 0):

```
LMIN -9223372036854775808
VIA -9223372036854775808
BARE false
VIA-LT false
```

Each opt level was `jank --module-path /tmp/upstream/jank10 compile-module repro.parse -O<n> --target-dir /tmp/upstream/jank10/o<n>` (exit 0), then `jank --module-path <cache>:/tmp/upstream/jank10 run-main repro.parse`, twice, without recompiling between the two runs.

`-O0`, cache `.../x86_64-unknown-linux-gnu-cf627abb22b776c8ebbb6caee09f4f22fe25a16e33827b2ccc2eb155085e5e68`. First run (exit 0):

```
LMIN 6.9248298180677e-310
VIA -9223372036854775808
BARE true
VIA-LT false
```

Second run of that same object: `LMIN 6.9061162928391e-310`. `VIA`, `BARE`, and `VIA-LT` were unchanged.

`-O1`, cache `.../x86_64-unknown-linux-gnu-4dcf63e79614bd1a26fdd873ef34f036e3ed76b2de9be3401ac4c1a9f24475c7`. First `LMIN 6.93475453251875e-310`, second `LMIN 6.9250968742946e-310`. `VIA` was `-9223372036854775808`, `BARE` was `true`, `VIA-LT` was `false` both times.

`-O2`, cache `.../x86_64-unknown-linux-gnu-57ac479be5103e0ee49fa60cfb16fe16ba136595e6a5dc20ee1c04ee05ba3518`. First `LMIN 6.9032138833617e-310`, second `LMIN 6.94139320643687e-310`. `VIA` was `-9223372036854775808`, `BARE` was `true`, `VIA-LT` was `false` both times.

`-O3`, cache `.../x86_64-unknown-linux-gnu-d41ef4a07a9d4dd9963044192227fbfde2da40c9f817907bab326588f8e7ef94`. First run (exit 0):

```
LMIN 6.9331815824161e-310
VIA -9223372036854775808
BARE true
VIA-LT false
```

Second run of that same object: `LMIN 6.9081950949291e-310`. `VIA`, `BARE`, and `VIA-LT` were unchanged.

`9223372036854775807` (`Long/MAX_VALUE`) and `-2147483648` (`Integer/MIN_VALUE`) printed unchanged from an earlier draft of this file at `-O0`. The value that moves is the minimum long literal, and it is a different double on every run of one object.

## Expected

`LMIN` is `-9223372036854775808` and `BARE` is `false`, as in the source run. `-1295` is greater than `Long/MIN_VALUE`.

## Actual

The source run prints that. Every AOT run prints a denormal double for the literal (a different one each process) and `BARE` is `true`. `(- -9223372036854775807 1)` stays `-9223372036854775808`, and `(< -1295 via)` stays `false`.

## Cohost today

`long-min-value` in `src/cohost/shim.cljc` is `(- -9223372036854775807 1)`. `Long-MIN_VALUE`, `lmin`, `long-min`, the lower bound passed to `d2l`, and `Math/round` of an infinity use that var instead of the literal, so `shim.o` can be loaded. The boot that checks the battery is in `doc/perf/jank.md`.
