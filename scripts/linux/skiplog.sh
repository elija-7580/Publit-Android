#!/bin/bash
# SKIP_COMMAND_OVERRIDE target: records every skipstone invocation so kotlinize.sh can replay it
echo "$(date +%T) $*" >> /data/tools/skipstone_args.log
exec /data/tools/skipstone-bin "$@"
