# ClojureWasm ReleaseFast, built on lane D's box (D-16 step 2, the user approved the install)

Zig: the version ClojureWasm v1.15.4's build.zig.zon names (`.minimum_zig_version = "0.16.0"`):

    curl -fsS https://ziglang.org/download/index.json -o index.json   # 0.16.0 / x86_64-linux: tarball, shasum
    curl -fsS -o zig.tar.xz https://ziglang.org/download/0.16.0/zig-x86_64-linux-0.16.0.tar.xz
    echo "70e49664a74374b48b51e6f3fdfbf437f6395d42509050588bd49abe52ba3d00  zig.tar.xz" | sha256sum -c   # OK
    mkdir -p ~/.cache/zig && tar -xJf zig.tar.xz -C ~/.cache/zig
    ~/.cache/zig/zig-x86_64-linux-0.16.0/zig version                    # 0.16.0

ClojureWasm at its v1.15.4 tag (65e5c0e), built ReleaseFast into its own prefix (the installed
/usr/local/bin/cljw, the shipped ReleaseSafe, untouched):

    git clone --depth 1 --branch v1.15.4 https://github.com/clojurewasm/ClojureWasm.git cw
    cd cw && ~/.cache/zig/zig-x86_64-linux-0.16.0/zig build -Doptimize=ReleaseFast --prefix /tmp/claude-0/d/cljw-fast
    # 4 m 59.8 s; bin/cljw 6,590,416 bytes (the shipped binary: 9,616,336)
    /tmp/claude-0/d/cljw-fast/bin/cljw --version                        # ClojureWasm v1.15.4 (ReleaseFast)

Measured with `bb bench:programs cljw <programs>` on cohost d/release-perf (= coord/release3 @ ffcc0cb's
method), each binary first on PATH: cljw-safe.edn, cljw-fast.edn.
