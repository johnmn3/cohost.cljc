# The REPL

*2026-11-02.*

`cohost` with no main option (or `-r`) is the REPL, as `clj` is. It reads a line at a time,
on a terminal or a pipe: a prompt, lines until they hold whole forms, then each form's value.
Forms on one line print under one prompt; an error is reported and the REPL goes on; the end
of input ends it.

```bash
printf '(def x\n  5)\nx (inc x)\n:done\n' | cohost
# => Clojure 1.12.0
# => user=> #'user/x
# => user=> 5
# => 6
# => user=> :done
# => user=>
```

There is no line editing or history: wrap it, as `clj` wraps `clojure`, with `rlwrap cohost` (`brew install rlwrap` on macOS, `sudo apt install rlwrap` on Debian/Ubuntu).
An nREPL server is not part of `cohost` today.
