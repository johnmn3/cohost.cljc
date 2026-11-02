# {{name}}

{{description}}

    cohost -M:main            # Hello, world!
    cohost -M:main you        # Hello, you!
    cohost -X:run :who '"you"'
    cohost -X:test            # clojure.test
    cohost                    # a REPL
    cohost -T:build build     # the artifact for the target (an uberjar on :jvm)

The runtime is `:cohost/target` in deps.edn; `cohost :<target> ...` runs on another.
