# let-go 1.13.0: two bugs found by cohost's cached boot (D-10, 2026-11-03 ET)

## 1. `map` over an empty list that carries metadata yields `(nil)`

    lg -e '(let [w (with-meta (list) {:line 1})] (println (pr-str (seq w)) (pr-str (map identity w)) (count w)))'
    ;; nil (nil) 0          -- expected: nil () 0

`seq`, `first`, `empty?`, `into`, `reduce` are right; `map` (and so `(apply list (map f w))`, `doall`)
produces one `nil`. A reader puts `:line` metadata on a `()` literal, so any code that rebuilds a form by
mapping over it turns `()` into `(nil)` -- which, evaluated, calls nil ("TypeError: nil is not a function").
cohost hit it in its boot recorder (stock `nthrest`'s `(or ... ())`) and works around it
(src/cohost/boot.cljc snap-encode: an empty seq is written `()`).

## 2. A regex prints, and `str`s, with every backslash doubled

    lg -e '(println (pr-str #"\r?\n") (str #"\r?\n") (pr-str (re-pattern "a\\d")))'
    ;; #"\\r?\\n" \\r?\\n #"a\\d"     -- expected: #"\r?\n" \r?\n #"a\d"

Read back, `#"\\r?\\n"` is another pattern (a literal backslash, then r), so a printed form does not
round-trip: cohost's cached boot made `clojure.string/split-lines` split nothing. cohost halves the
backslashes of a let-go-recorded pattern when it writes its modules (dev/perf/jank_snapshot_emit.clj,
:regex-halve?).
