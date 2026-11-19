# Vendoring: a library's source in your tree, your fixes sent back

This is how one Clojure-family project carries another's source, for example VS carrying eve, or later VS carrying cohost and core.clj.
The tool is `tools/vendor.py`, which `cohost vendor` runs. It needs only plain git and Python 3, with no service and no
credentials beyond what `git fetch` and `git push` already use.

## Declare

In `deps.edn`, under `:cohost/vendor` (never `:deps`: a vendored library is your own source, not a classpath dependency
you pass on to your users):

```clojure
:cohost/vendor
{eve/eve {:git/url "https://github.com/SeniorCareMarket/eve"
          :git/sha "e6cddc906da3620b912287f30046ffb67058ac4e"
          :dest "vendor/eve"
          :paths ["src" "hosts.edn" "LICENSE" "NOTICE"]
          :paths-from {"hosts.edn" :vs}}}   ; plus the roots hosts.edn lists under :vs
```

## The loop

| step | command | what happens |
|---|---|---|
| bring it in | `cohost vendor sync` | the declared paths at `:git/sha` are written to `:dest`; `<dest>/PIN` records the commit and each file's git blob id |
| fix it | edit the files under `:dest` | a fix that holds only on one host goes in a reader conditional, `#?(:vs …)` in a `.cljc`, so the file stays right on every host |
| see it | `cohost vendor status` · `cohost vendor diff` | your edits against the PIN's commit (M / A / D) |
| send it | `cohost vendor contribute eve/eve --branch fix/x` (or `--patch x.patch`) | your edits as one commit on top of the PIN's commit, pushed upstream as a branch (or written as a patch) |
| move up | bump `:git/sha`, then `cohost vendor sync` | each file is merged three ways (base: the PIN's commit; ours: your tree; theirs: the new pin). Files you never touched take upstream's version. An edit upstream already has disappears. A real clash is left in the file with conflict markers and named, with exit 4 |
| gate it | `cohost vendor check` | the PIN names the declared commit, and no conflict markers are left |

There is no overlay and no patch queue. The vendored tree on disk is the truth and the PIN is its base, so any editor
shows exactly what runs.

Exit codes: 0 ok · 1 check failed · 2 usage · 3 git/io · 4 merge conflicts. Tests: `python3 tools/test_vendor.py`.
