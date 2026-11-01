# A macro is expanded with `&form` `((args))` and `&env` the macro's name

Status: REPRODUCED

Host: jank 0.1-noble (`dpkg-query -W` prints `jank	0.1-noble`; `jank --help` banner `jank compiler jank-0.1-alpha`).

## Repro

`/tmp/upstream/01b-macro.jank`:

```clojure
(defmacro show [a]
  (list 'hash-map :form (list 'quote &form) :env (list 'quote &env) :arg a))
(println "EXPAND" (pr-str (macroexpand '(show 1))))
(println "TOP" (pr-str (show 1)))
(defn caller [x]
  (show x))
(println "IN-FN" (pr-str (caller 2)))
```

```
jank run /tmp/upstream/01b-macro.jank
```

stdout (exit 0):

```
EXPAND (hash-map :form (quote ((1))) :env (quote show) :arg 1)
TOP {:env show, :arg 1, :form ((1))}
IN-FN {:env show, :arg 2, :form ((x))}
```

## Expected

`&form` is the call form, `(show 1)` at the top level and `(show x)` inside `caller`. `&env` is a map of the local bindings (empty at the top level, `{x ...}` inside `caller`).

## Actual

`&form` is `((1))` and `((x))`: the argument list wrapped in an extra list, with the macro name omitted. `&env` is the symbol `show`, the macro's own name, in both calls.

## Cohost today

`cohost.hosts.jank/expand-macro` applies the macro fn itself, passing the real call form and a map of the locals, and the codemod uses that instead of jank's expander.
