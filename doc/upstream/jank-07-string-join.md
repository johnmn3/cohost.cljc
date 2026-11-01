# `clojure.string/join` returns a wrong count

Status: NOT REPRODUCED

Host: jank 0.1-noble (`jank compiler jank-0.1-alpha`).

An earlier cohost bench note said jank's own `clojure.string/join` of a range disagreed with the stock join (108,889 vs 58). On this binary the join matches a hand-built string, and the count for `(range 20000)` is 108,889, which is the right length (digits of 0..19999 plus 19,999 commas).

## Repro

`/tmp/upstream/07-join.jank` compares `(clojure.string/join "," (range 20))` to a loop of `str`. `/tmp/upstream/07b-join.jank` counts larger joins.

```
jank run /tmp/upstream/07-join.jank
```

stdout (exit 0):

```
small "1,2,3"
count-got 49
count-expected 49
equal true
got 0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19
expected 0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19
```

```
jank run /tmp/upstream/07b-join.jank
```

stdout (exit 0):

```
no-sep "0123456789"
n100 289
n20000 108889
```

## Expected

`(str/join "," [1 2 3])` is `"1,2,3"`. `(count (str/join "," (range 20)))` is 49. `(count (str/join "," (range 100)))` is 289. `(count (str/join "," (range 20000)))` is 108889. `(str/join (range 10))` is `"0123456789"`.

## Actual

Each of those is what jank printed.

## Cohost today

This tree does not replace jank's `clojure.string/join`. The fast-path notes say not to install it as a native because an earlier run disagreed; this binary's answers match the lengths above.
