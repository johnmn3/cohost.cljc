#!/usr/bin/env bash
# Every runtime cohost runs on, one dev/install/<host>.sh at a time; a host that fails (or
# has no build for this platform) is reported and the rest go on. Hosts as arguments pick
# a subset:  bash dev/install/all.sh jolt lg
set -uo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
hosts=("$@")
[ ${#hosts[@]} -gt 0 ] || hosts=(jolt basilisp cljw lg phel jank cljrs)
ok=(); failed=()
for h in "${hosts[@]}"; do
  printf '\n== %s\n' "$h" >&2
  if bash "$here/$h.sh"; then ok+=("$h"); else failed+=("$h"); fi
done
printf '\ninstalled: %s\n' "${ok[*]:-none}" >&2
if [ ${#failed[@]} -gt 0 ]; then printf 'failed:    %s (see the messages above)\n' "${failed[*]}" >&2; exit 1; fi
