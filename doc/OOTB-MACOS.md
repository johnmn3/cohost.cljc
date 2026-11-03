# Out-of-the-box run: macOS arm64, 2026-11-02

A first-time run of the README, followed as written, on a Mac. Then a pass that made
every runtime work there. Below is every paper cut found, in the order it was hit, with
its status on branch `fix/macos-universal-installs`.

**Box:** macOS 26 (Darwin 25.5), arm64, 32 GB, stock `/bin/bash` 3.2 plus Homebrew bash 5.3.
Tools: bb 1.12.218, Clojure CLI 1.12.5.1645, OpenJDK 26.0.1, node 22.22.3, go 1.27.3,
uv present, Homebrew jolt 0.8.23 already installed.

**Started from:** `main` @ `ff30ce1`, cloned to `~/cohost` as the README says, project made
from the template at `c6b8eaa`.

**Status key:** **fixed** = fixed on the branch and verified here; **open** = not fixed;
**upstream** = belongs to another project.

## Summary

| # | paper cut | status |
|---|---|---|
| 1 | 6 of 8 `dev/install/*.sh` are Linux-only | fixed |
| 2 | `phel.sh` exits 127 with no message | fixed |
| 3 | `cohost :<host>` with the host missing: a babashka stack trace | fixed |
| 4 | `xargs -d` (GNU only) in the jank/lpy cache keys | fixed |
| 5 | `cohost -e` on `:jvm` prints the CLI's deprecation warning | fixed |
| 6 | first cljs build warns `undeclared Var cohost.boot/+'` | fixed |
| 7 | `:phel` prints 763 PHP warnings per command | fixed |
| 8 | `cohost :jank -m/-X` with a fresh snapshot: FileNotFoundException trace | fixed |
| 9 | `export PATH` lasts one shell; README doesn't say so | fixed (docs) |
| 10 | README's `-Spath` wording vs what `:jvm` prints | fixed (docs) |
| 11 | README's runtime table: no platform info | fixed (docs) |
| 12 | classpath split/join on `:` (breaks on Windows) | fixed (untested on Windows) |
| 13 | no Windows entry point for `bin/cohost` | fixed (untested on Windows) |
| 14 | Homebrew 5 refuses jank's tap until trusted | handled: script stops with the command |
| 15 | default target is `:jvm`; nothing reaches cohost-on-a-host by default | open (docs) |
| 16 | jank's core snapshot is never built automatically, and goes stale on any update | open |
| 17 | first `:jank` run: 58 s, one cryptic line | open |
| 18 | `:lpy` is ~20 s per command (34 s first, silent); the CLI never uses the lpy snapshot | open |
| 19 | `:cljw` ~17 s, `:phel` ~22 s per command; no user-facing numbers | open (docs) |
| 20 | Phel leaves compiled files in `$TMPDIR/phel/tmp` and never cleans them | open |
| 21 | Homebrew PHP's ini shows each PHP warning twice | open |
| 22 | rustup `--no-modify-path`: `cljrs` isn't on PATH afterwards | documented by the script |
| 23 | `rlwrap` suggested, no install hint | open (docs) |
| 24 | deps-new on JDK 26 prints 3 `Final field ... mutated reflectively` warnings | upstream |
| 25 | `bb boot:jank-snapshot` prints `load error: set! ...` lines while passing | open |
| 26 | `jank-src.sh` is Debian/root-only | open |
| 27 | the template pins `c6b8eaa`; fixes reach new projects only at the next release | release step |
| 28 | Windows: the Clojure CLI is a PowerShell module, and `bin/cohost` runs `clojure` | open, untested |
| 29 | `brew install php` prints php-fpm service caveats during `phel.sh` | cosmetic |

## Details

### Install and runtime installers

**1. Six of eight installers were Linux-only.** (fixed)
The README's runtime table gives `dev/install/<host>.sh` as the install. On this Mac:

| script | what happened |
|---|---|
| `jolt.sh` | downloaded `jolt-v0.8.23-x86_64-linux.tar.gz`, checksum OK, then `install: /usr/local/bin/...: Permission denied` (rc 71) |
| `cljw.sh` | `cljw-linux-x86_64.tar.gz`, same failure (rc 71) |
| `lg.sh` | `let-go_1.13.0_linux_amd64.tar.gz`, same failure (rc 71) |
| `jank.sh` | `/etc/apt/trusted.gpg.d/jank.gpg: No such file or directory` (rc 1) |
| `phel.sh` | no output, rc 127 (see 2) |
| `cljrs.sh` | `cargo: command not found` (rc 127); Rust is not listed as a prerequisite |
| `basilisp.sh` | worked (uv) |

Even with root, the scripts would have put Linux ELF binaries on a Mac's PATH.

Fix: `dev/install/_lib.sh`, sourced by every script, does the following:
- detects the OS (linux, macos, windows under Git Bash/MSYS2) and the CPU;
- picks that platform's release asset;
- checks sha256 with whichever of `sha256sum`, `shasum` or `certutil` exists;
- installs to `$COHOST_BIN`, else `/usr/local/bin` when writable, else `~/.local/bin`, and
  warns when that directory isn't on PATH;
- uses `sudo` only when not root;
- skips a host whose pinned version is already on PATH (`COHOST_FORCE=1` reinstalls).

Missing toolchains are now installed: uv (basilisp), PHP 8.5 via Homebrew or
ppa:ondrej/php (phel), and Rust via rustup (cljrs). jank comes from jank's Homebrew tap on
macOS arm64. `dev/install/all.sh` installs every host and lists any it couldn't. All
scripts were verified under `/bin/bash` 3.2.

Per-platform coverage, from each project's release assets:
- **jolt:** linux-x86_64, macos-aarch64, macos-x86_64, windows-x86_64.
- **cljw:** linux-x86_64, macos-aarch64.
- **let-go:** linux and darwin, amd64 and arm64; Windows via `go install`.
- **phel:** wherever PHP 8.5 runs.
- **jank:** Ubuntu (apt) and macOS arm64 (tap).
- **basilisp, cljrs:** everywhere uv or cargo runs.

**2. `phel.sh` failed silently.** (fixed)
It looks for a python that can `import apt_pkg`. With none, `$py` was empty, and
`"$py" /usr/bin/add-apt-repository` exited 127 with no message. Now it uses an existing
PHP ≥ 8.5 if there is one. Otherwise it installs PHP the platform's way, and each failure
path says what to install.

**14. Homebrew 5 refuses jank's tap.** (handled)
`brew install jank` fails with `Refusing to load formula jank-lang/jank/jank from untrusted
tap`. Trusting a tap is the user's decision, so `jank.sh` stops with the exact command,
`brew trust --formula jank-lang/jank/jank`, and only trusts it itself when
`COHOST_TRUST_TAPS=1`. Documented in README and guide 07.

**22. `cljrs` not on PATH after install.** (documented by the script)
rustup runs with `--no-modify-path`, so the toolchain and `cljrs` land in `~/.cargo/bin`
without touching shell profiles. The script prints the `export PATH=...` line to add.

**26. `jank-src.sh` is Debian/root-only.** (open)
It uses apt and `dpkg-query`, writes to `/opt` and `/usr/local`, needs the apt jank package
for its LLVM, and uses `nproc`. It isn't in the README's table, so it isn't on the
out-of-the-box path, but a Mac contributor can't build jank's patch series.

**29. PHP service caveats.** (cosmetic)
`brew install php` prints "To start php now and restart at login: brew services start
php ..." in the middle of `phel.sh`. Phel doesn't need php-fpm.

### First run of the README (`:jvm`)

The whole README flow works on `:jvm`: new project, `-M -m`, `-M:main`, `-X:run`, `-e`,
REPL, `-Sdeps`, `-X:test`, `-T:build build`, and the artifact run from `/tmp`.

**5. `cohost -e` on `:jvm` warned.** (fixed)
It printed `WARNING: Implicit use of clojure.main with options is deprecated, use -M -e ...`,
which isn't in the README's expected output. `bin/cohost` now inserts `-M` before
clojure.main's options when it forwards to `clojure` without one.

**9. PATH only for the current shell.** (fixed, docs)
The README's `export PATH="$HOME/cohost/bin:$PATH"` is gone in the next terminal. README
and guide 01 now say to add it to `~/.zshrc` / `~/.bashrc`.

**10. `-Spath` wording.** (fixed, docs)
The README said `cohost -Spath` "prints the source roots". On `:jvm` it's the Clojure CLI's
own `-Spath`, which prints the whole classpath with jars. The README now says which is which.

**11. No platform information.** (fixed, docs)
The README's install block is marked "not checked: installs", and the runtime table
has no OS column, so nothing said six of eight scripts were Ubuntu/x86_64-only. README
and guides 01 and 07 now state platform coverage.

**15. The default target is `:jvm`.** (open, docs)
The template's `template.edn` sets `:target "jvm"`, so a fresh project runs on the real
`clojure` CLI, and nothing installs or compiles any other runtime. A first-time user who
expects "cohost" to mean stock core on jank (or any host) gets plain JVM Clojure unless
they add `:target <host>` at creation or `cohost :<host>` per command. The README says so
("A project without the key runs on the JVM"), but only after the run section.
Suggestion: state it in "A new project" next to the create command.

**23. `rlwrap`.** (open, docs)
The REPL section suggests `rlwrap`. It wasn't installed here, and there's no hint
(`brew install rlwrap`, `apt install rlwrap`).

**24. deps-new on JDK 26.** (upstream)
Creating the project prints three `WARNING: Final field licenses in class
org.spdx.storage.listedlicense.LicenseJsonTOC has been mutated reflectively ...` lines,
from gson inside deps-new. It's the first output a new user sees.

### Other runtimes

**3. A missing host binary gave a stack trace.** (fixed)
`cohost :jank ...` with no jank installed ended in a babashka error block (see 4).
`bin/cohost` now checks each wired host's program before launching and prints one line
naming the install script:

    cohost: :lg runs on `lg`, which is not on PATH
      install it: bash /path/to/cohost/dev/install/lg.sh

**4. GNU `xargs -d` in cache keys.** (fixed)
`dev/perf/jank_snapshot_key.clj`, `jank_aot_ensure.clj`, `jank_snapshot_cli.clj` and
`lpy_snapshot_ensure.clj` ran `xargs -d '\n' sha256sum`. BSD xargs has no `-d`, and older
macOS has no `sha256sum`. On a Mac, `cohost :jank` died there before anything checked for
jank:

    jank: no module path from dev/perf/jank_snapshot_key.clj: xargs: invalid option -- d

`dev/perf/sha256.clj` now hashes in-process. Its output is byte-identical to the old
pipelines (checked: same digest over `src/cohost` both ways), so existing Linux stamps
stay valid. `dev/bench_programs.clj` uses it too.

**6. cljs `+'` warning.** (fixed)
The first `:cljs` build printed `WARNING: Use of undeclared Var cohost.boot/+' at line 37
src/cohost/boot.cljc`. ClojureScript has no `+'`. `prof+` is now `+` on `:cljs`.

**7. 763 PHP warnings per Phel command.** (fixed)
Each `cohost :phel ...` printed `PHP Warning: Undefined variable $__phel_..._...` 763
times, all from one place: `cohost.rewrite/with-hinted-locals`, `(fn [[b _]] ...)`. Phel
compiles the unused `_` to a PHP variable that it never assigns but still lists in an
inner closure's `use(...)`. That's a Phel compiler bug; worth reporting upstream. The
destructure is now `(first pair)`: zero warnings, and `-e` went from 55 s to 23 s.

**8. jank project build crashed.** (fixed)
With a fresh core snapshot, every `cohost :jank -m/-X` printed a `FileNotFoundException`
for `target/cohost-jank/src/cohost/project.jank` and fell back to running without the
project's ahead-of-time build. `jank_snapshot_emit.clj --project` created the directories
of the project's own modules (`acme/...`) but never `cohost/`. This isn't platform-specific.
After the fix, the project builds once (5.4 s), then `cohost :jank -M -m acme.myapp` takes
0.54 s.

**12. Classpath separator.** (fixed, untested on Windows)
`bin/cohost` split the CLI's classpath on `:` and joined `-Spath` with `:`. It now uses
`File/pathSeparator`, and the filter that drops Clojure's own jar accepts `\`.

**13. Windows entry point.** (fixed, untested on Windows)
`bin/cohost` is a bb script with a shebang, which Windows doesn't run. `bin/cohost.cmd`
runs it with `bb`.

**16. jank's snapshot is manual and goes stale silently.** (open)
Nothing builds jank's core snapshot: not the installer, not the first run. Without it,
`:jank` takes about 10 s per command; with it, 1.4 s, and 0.54 s with the project build.
Any change under `src/cohost`, the stock sources or the snapshot scripts changes the key,
so after a `git pull` jank quietly falls back to the slow path. The only signal is one
line: `cohost: jank: SNAPSHOT-STALE ... (bb boot:jank-snapshot builds it)`.

Building it takes about 2m16s, with jank and clang peaking around 3 GB. Not building it
automatically looks deliberate in `jank_snapshot_cli.clj` ("never builds"). Options:
- `jank.sh` / `all.sh` build it after installing jank;
- the CLI builds it on first use with a progress line;
- the README says to run `bb boot:jank-snapshot` once after installing jank, and after
  each update.

**17. The first `:jank` run is slow and unexplained.** (open)
58 s, during which cohost's AOT objects compile at -O3. The only output is the
SNAPSHOT-STALE line, which speaks the developer tasks' vocabulary (`bb boot:jank-snapshot`)
rather than telling the user what is happening or how long it will take.

**18. `:lpy` takes ~20 s per command.** (open)
The first run was 34 s with no output at all; cljs, by contrast, prints "compiling the
ClojureScript host build". Every later command took 19–25 s. `bin/cohost`'s lpy launch
runs `basilisp run -p src` and boots `core.clj` from source each time. The lpy snapshot
(`dev/perf/lpy_snapshot_ensure.clj`, `bb boot:lpy-snapshot`) exists but the CLI never uses
it. Wiring it in would be the lpy counterpart of jank's snapshot path. At minimum, print
a line while booting.

**19. Startup per runtime isn't published.** (open, docs)
Measured here, per README command:

| runtime | per command |
|---|---|
| `:jvm` | ~1 s |
| `:jank` | 0.5 s, with snapshot + project build (10 s without) |
| `:jolt` | 1–2 s |
| `:cljs` | ~2 s (5 s first build) |
| `:lg` | 4–6 s (10 s first) |
| `:cljw` | ~17 s |
| `:lpy` | 19–25 s |
| `:phel` | ~22 s |

Guide 07's table covers only jvm, cljs and jank, and says the others "are being
re-measured". A user picking `:lpy`, `:phel` or `:cljw` can't tell that 20 s is expected.

**20. Phel temp files pile up.** (open)
Phel writes compiled PHP to `$TMPDIR/phel/tmp/__phel_*.php` and doesn't delete it. After
about a dozen commands there were 1,525 files (7.3 MB). It grows with use and is only
cleared when macOS purges `$TMPDIR`.

**21. Every PHP warning is shown twice.** (open)
Homebrew's `php.ini` is the development one: `display_errors=1` and `log_errors=1`, with no
`error_log`. So each warning appears as both `PHP Warning: ...` (log, stderr) and
`Warning: ...` (display). Item 7 removed the warnings cohost caused, but any other warning
will still double. The `phel` wrapper could pass `-d log_errors=0`, or
`-d display_errors=stderr`.

**25. Noise in `bb boot:jank-snapshot`.** (open)
While passing (core 165/167, libraries 62/62), it prints `load error: set!: The symbol
'set!' could not be resolved.` (×5), `The symbol 'proxy' could not be resolved`,
`sun.misc.Signal/handle could not be resolved`, and `A cpp_type expression was found
during codegen, but that should not be possible`. These are known non-portable vars,
reported as errors. Someone running the build for the first time reads them as failures.

### Release and platforms

**27. The template pins `c6b8eaa`.** (release step)
The README's create command, and the generated project's `:build` alias, name cohost by
that sha. Projects made from the README get these fixes only after the template line names
a commit that includes them.

**28. Windows is unverified.** (open)
The installers' Windows branches (jolt zip, `go install` for let-go, uv's PowerShell
installer, `certutil`) and `bin/cohost.cmd` are written but have not been run. On Windows
the Clojure CLI is a PowerShell module, not an executable. `bin/cohost` runs `clojure`
through babashka.process, which probably won't find it. deps.clj (`deps.exe`) installed as
`clojure` is a likely route. Needs a Windows box.

## Verification on this Mac (branch `fix/macos-universal-installs`)

- **README command set:** each runtime ran the 8 commands (`-M -m`, `-M:main`, `-X:run`,
  `-e`, `-X:test`, `-Sdeps` + tools.cli, REPL over stdin, `-T:build build` + the artifact
  run from `/tmp`). All 64 pass, on jvm, cljs, jolt, lg, cljw, lpy, phel and jank. No
  warnings in the output.
- **`bb docs:check`:** 0 failing commands on jvm (22 commands), jolt and jank.
- **`bb cli:check jvm cljs jolt lg`:** 28/28 SAME.
- **`bb lint:reader-order`:** 0 problems.
- **jank snapshot:** core battery 165/167, library battery 62/62.

**Not verified:** Linux (this box has no container runtime; the Linux branches keep their
old commands, plus `sudo` when not root), Windows (item 28), and `cljrs` through `cohost`
(not wired yet; installs and runs `cljrs --version`).
