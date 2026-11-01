# `count`, `conj`, `assoc`, and `meta` panic on an object type they do not know

Status: REPRODUCED

Host: jank 0.1-noble (`jank compiler jank-0.1-alpha`).

## Repro

Each file defines the same one-field C++ `object` subclass whose `object_type` is `object_type::max + 1` (raw value 71, past jank's own tags), boxes one, and calls one core fn. `make` is `jank::runtime::make_box<alien>()`.

```
jank run /tmp/upstream/08b-panic.jank
```

stdout, then the process aborts (exit 134):

```
made alien unknown
WHICH count
```

stderr:

```
Panic encountered: invalid object type: unknown, raw value 71
```

```
jank run /tmp/upstream/08-conj.jank
```

stdout `op conj`, exit 134, stderr `Panic encountered: invalid object type: unknown, raw value 71`.

```
jank run /tmp/upstream/08-assoc.jank
```

stdout `op assoc`, exit 134, same panic.

```
jank run /tmp/upstream/08-meta.jank
```

stdout `op meta`, exit 134, same panic.

## Expected

An unknown `object` subclass is a value: `count` / `conj` / `assoc` / `meta` throw a catchable exception, or dispatch to an overridable method. They do not abort the process.

## Actual

Each call panics with `invalid object type: unknown, raw value 71` and aborts (SIGABRT, exit 134). `(type a)` before the call printed `unknown`.

## Cohost today

The shim's jank `RT-count`, conj, assoc, and `with-meta` look up `instance-desc` and handle a cohost instance themselves before calling jank's fn, which panics on that type.
