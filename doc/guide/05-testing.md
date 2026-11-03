# Testing

*2026-11-02.*

Tests are `clojure.test`, the library's own source, on every runtime. The `cohost/app`
template's `:test` alias runs the test namespace's `run`, which calls `run-tests` and ends the
run with exit code 1 when a test fails or errors:

```bash
cohost -X:test; echo "exit $?"
# => Testing acme.myapp-test
# => Ran 1 tests containing 2 assertions.
# => 0 failures, 0 errors.
# => exit 0
```

`deftest`, `is`, `are`, `testing`, `thrown?`, fixtures and `run-tests` are Clojure's. A test
runner that discovers namespaces by scanning the classpath for files needs JVM file APIs; the
template names its test namespace instead.
