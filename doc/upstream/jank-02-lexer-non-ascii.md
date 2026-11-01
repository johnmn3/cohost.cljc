# Lexer rejects any non-ASCII character, including in a comment

Status: NOT REPRODUCED

Host: jank 0.1-noble (`jank compiler jank-0.1-alpha`).

## Repro

Each file was written as UTF-8 and run with `jank run <file>`. All four exited 0.

Comment with U+0301 (combining acute), file bytes `b';; cafe\xcc\x81\n(println "comment-ok")\n'`:

```
jank run /tmp/upstream/02-comment.jank
```

```
comment-ok
```

String with U+00E9, file bytes `b'(println "caf\xc3\xa9")\n'`:

```
jank run /tmp/upstream/02-string.jank
```

```
café
```

Comment and string with U+65E5, file text `;; 日` then `(println "日")`:

```
jank run /tmp/upstream/02b-cjk.jank
```

```
日
```

String with a raw U+263A (not a `\u` escape):

```
jank run /tmp/upstream/02d-raw.jank
```

```
hi ☺
```

## Expected

If the lexer rejects every non-ASCII character, each of these files fails to read.

## Actual

Each file reads and runs. The comment, the Latin-1 character, the CJK character, and the raw emoji are all accepted.

## Cohost today

cohost's own sources are ASCII anyway (an em dash is written `--`, and the battery's non-ASCII characters are `\u` escapes), which is the workaround recorded when an earlier binary rejected them.
