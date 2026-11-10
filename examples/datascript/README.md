# DataScript on cohost — the example

One DataScript database, in one shared persistent Eve atom, worked by
programs running in **different cohost bodies at once** — each body a
different runtime hosting stock Clojure `core.clj` over the Eve data
backend. The DataScript implementation is not in this repo: it is the
single codebase at **johnmn3/datascript.cljc**, consumed as a dep.
This example is deliberately thin — a demo app, two body programs,
and the gate that proves the arrangement.

## What's here

| Path | What |
|---|---|
| `src/ds_example/demo.cljc` | The example app: init / add / read over a shared Eve domain. Plain `.cljc`, identical in every body. |
| `body/jvm_body.clj` | The JVM body (the caller): writes the lib's contract dataset into the shared domain, then asks the peer body two DataScript questions via `cohost.bodies/call`. |
| `body/lpy_body_ds.lpy` | The basilisk body (the server): evaluates the caller's forms with basilisk's own eval against the database it opened on the same domain. |
| `run-gate.sh` | The two-body gate: basilisk serves, JVM writes + calls, then fresh-process readbacks (JVM and basilisk) of the lib repo's contract driver. PASS conditions in the script header. |

## The arrangement

- **Bodies** are processes. Each loads cohost on its own runtime —
  the JVM body on Clojure, the basilisk body on basilisk — plus the
  Eve seat for its host and the multi-body layer (`cohost.bodies`,
  the mailbox, and the file-domain hosting), consumed from
  atom.broker's `multi-bodied/src` at the cohost-on-Eve release pin
  until the cohost release promotes that layer into this repo.
- **The database** is an Eve value in a persistent Eve atom in a
  journaled disk domain. Storing it is placing it in the atom; there
  is no storage layer. Bodies open the same domain path and mint the
  same atom names, so they hold the *same* database — a value one
  body transacts is the value the others read, seal-identical.
- **Cross-body calls** (`cohost.bodies/call`) carry a form as text;
  the callee evaluates it in its own runtime, with its own stock
  `core.clj` semantics, and replies with an Eve value. The gate's two
  calls ask the basilisk body for the shared DB's datom count and its
  sorted name query — answered from the basilisk body's own deref of
  the shared atom.

## Run it

```bash
bash examples/datascript/run-gate.sh <workdir> <eve-dir> <cohost-dir> <mb-src-dir> <ds-repo-dir> <basilisk-bin>
```

Needs: JDK + Clojure CLI, basilisk 0.5.1, and checkouts of
SeniorCareMarket/eve (the pin in datascript.cljc's `lib-ds/deps.edn`),
atom.broker's `multi-bodied/src`, and johnmn3/datascript.cljc.
Measured gate results live in the datascript.cljc repo's RESULTS.md
(the cohost plane rows) — the library's results doc, not this
example's, because the numbers are the library's proof.
