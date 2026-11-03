#!/usr/bin/env bash
# clojurust 0.2.32: the crates.io crate `cljrs` (bin `cljrs`), built by cargo with its
# published lockfile, into ~/.cargo/bin. Installs a Rust toolchain first when cargo is
# missing (rustup, without touching shell profiles).
source "$(dirname "$0")/_lib.sh"
ver=0.2.32
export PATH="$HOME/.cargo/bin:$PATH"
already cljrs --version -- "$ver"
if ! have cargo; then
  say "cargo not found: installing a Rust toolchain with rustup"
  if [ "$os" = windows ]; then die "install Rust from https://rustup.rs and rerun"; fi
  curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y --profile minimal --no-modify-path
fi
cargo install cljrs --version "$ver" --locked
cljrs --version
bin_dir="$HOME/.cargo/bin" on_path_or_warn
