#!/bin/bash
# Re-runs recorded skipstone commands in Kotlin (non-bridge) mode: generates the Kotlin sources that the
# macOS/Xcode build would normally produce. Usage: kotlinize.sh [argslog]
set -e
LOG=${1:-/data/tools/kz_args.log}
while IFS= read -r line; do
  args=$(echo "$line" | cut -d' ' -f2- | sed -E 's/--skip-bridge-output [^ ]+//')
  name=$(echo "$args" | grep -oE -- '--module-root [^ ]+' | awk -F/ '{print $NF}')
  env -u SKIP_BRIDGE /data/tools/skipstone-bin $args > /tmp/kz_$name.log 2>&1; echo "$name exit $?"
done < "$LOG"
