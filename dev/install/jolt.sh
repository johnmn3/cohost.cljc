#!/usr/bin/env bash
# jolt v0.8.23 (the version the existing jolt rows were measured on): the prebuilt release
# archive for this platform (Linux x86_64, macOS arm64/x86_64, Windows x86_64), checked
# against the release's .sha256 sidecar.
source "$(dirname "$0")/_lib.sh"
ver=v0.8.23
already jolt --version -- "jolt $ver"
case "$os-$arch" in
  linux-x86_64|macos-aarch64|macos-x86_64) name="jolt-$ver-$arch-$os"; ext=tar.gz ;;
  windows-x86_64) name="jolt-$ver-x86_64-windows"; ext=zip ;;
  *) unsupported jolt "build it from https://github.com/jolt-lang/jolt" ;;
esac
dir="$hosts_dir/$name"
mkdir -p "$dir" && cd "$dir"
base="https://github.com/jolt-lang/jolt/releases/download/$ver"
fetch "$base/$name.$ext" "$name.$ext"
fetch "$base/$name.$ext.sha256" "$name.$ext.sha256"
verify_sha256 "$name.$ext" "$(sum_from "$name.$ext.sha256" "$name.$ext")"
if [ "$ext" = zip ]; then unzip -oq "$name.$ext"; else tar -xzf "$name.$ext"; fi
exe=jolt; [ "$os" = windows ] && exe=jolt.exe
src="$(find . -type f -name "$exe" | head -1)"
[ -n "$src" ] || die "no $exe in $name.$ext"
install_bin "$src" "$exe"
"$bin_dir/$exe" -e '(+ 1 2)'
