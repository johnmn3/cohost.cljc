# The lexer rejects non-ASCII text under the C locale

Status: REPRODUCED (C / POSIX locale). A UTF-8 locale reads the same files.

Host: jank 0.1-noble (`jank compiler jank-0.1-alpha`). `dpkg-query -W jank` prints `jank	0.1-noble`.

An em dash (U+2014, UTF-8 bytes `e2 80 94`) in a comment or a string fails when `LANG` and `LC_ALL` are unset. With `LC_ALL=C.UTF-8` both files run. On this machine `en_US.UTF-8` is installed (`locale -a` lists `en_US.utf8`), and `LC_ALL=en_US.UTF-8` also runs both files. That third run differs from a box where `en_US.UTF-8` is not installed: there jank aborts with `std::runtime_error`. Here it does not.

`locale -a` on this machine:

```
C
C.utf8
en_US.utf8
POSIX
```

## Repro

`/tmp/upstream/g6-comment.jank`, bytes `b';; em dash \xe2\x80\x94 in a comment\n(println "comment-ok")\n'`:

```clojure
;; em dash — in a comment
(println "comment-ok")
```

`/tmp/upstream/g6-string.jank`, bytes `b'(println "em dash \xe2\x80\x94 in a string")\n'`:

```clojure
(println "em dash — in a string")
```

### `LANG` and `LC_ALL` unset

`env -u LANG -u LC_ALL locale` prints `LANG=` and `LC_ALL=` and every `LC_*` category as `POSIX`.

```
env -u LANG -u LC_ALL jank run /tmp/upstream/g6-comment.jank
```

exit 1, stderr:

```
─ lex/invalid-unicode ──────────────────────────────────────────────────────────
error: The Unicode character literal is unfinished.

─────┬──────────────────────────────────────────────────────────────────────────
     │ /tmp/upstream/g6-comment.jank
─────┼──────────────────────────────────────────────────────────────────────────
  1  │ ;; em dash — in a comment
     │            ^ Found here.
─────┼──────────────────────────────────────────────────────────────────────────
  🗎  │ https://book.jank-lang.org/reference/error/lex/invalid-unicode.html
─────┴──────────────────────────────────────────────────────────────────────────
```

```
env -u LANG -u LC_ALL jank run /tmp/upstream/g6-string.jank
```

exit 1, stderr:

```
─ lex/unterminated-string ──────────────────────────────────────────────────────
error: Unterminated string.

─────┬──────────────────────────────────────────────────────────────────────────
     │ /tmp/upstream/g6-string.jank
─────┼──────────────────────────────────────────────────────────────────────────
  1  │ (println "em dash — in a string")
     │          ^^^^^^^^^ Found here.
─────┼──────────────────────────────────────────────────────────────────────────
  🗎  │ https://book.jank-lang.org/reference/error/lex/unterminated-string.html
─────┴──────────────────────────────────────────────────────────────────────────
```

### `LC_ALL=C.UTF-8`

`LC_ALL=C.UTF-8 locale` succeeds (`locale -a` lists `C.utf8`).

```
env -u LANG LC_ALL=C.UTF-8 jank run /tmp/upstream/g6-comment.jank
```

exit 0, stdout:

```
comment-ok
```

```
env -u LANG LC_ALL=C.UTF-8 jank run /tmp/upstream/g6-string.jank
```

exit 0, stdout:

```
em dash — in a string
```

### `LC_ALL=en_US.UTF-8`

`en_US.UTF-8` is installed here. `LC_ALL=en_US.UTF-8 locale` succeeds.

```
env -u LANG LC_ALL=en_US.UTF-8 jank run /tmp/upstream/g6-comment.jank
```

exit 0, stdout:

```
comment-ok
```

```
env -u LANG LC_ALL=en_US.UTF-8 jank run /tmp/upstream/g6-string.jank
```

exit 0, stdout:

```
em dash — in a string
```

## Expected

Non-ASCII text in a comment or a string is either read under every locale, or rejected the same way under every locale.

## Actual

Under the C / POSIX locale the comment fails with `lex/invalid-unicode` ("The Unicode character literal is unfinished.") and the string fails with `lex/unterminated-string`. Under `C.UTF-8` and under this machine's installed `en_US.UTF-8`, both files run.

## Cohost today

cohost's own sources stay ASCII (an em dash is written `--`, and the battery's non-ASCII characters are `\u` escapes), so a C-locale jank still reads them.
