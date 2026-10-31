#!/usr/bin/env bash
# clojurust 0.2.32: the crates.io crate `cljrs` (bin `cljrs`), built by cargo with its
# published lockfile, into ~/.cargo/bin.
set -euo pipefail
cargo install cljrs --version 0.2.32 --locked
cljrs --version
