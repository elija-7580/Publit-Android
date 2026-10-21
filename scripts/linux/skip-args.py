#!/usr/bin/env python3
"""Extract the latest skipstone invocation for each bridge module."""
import shlex, sys
from pathlib import Path
commands = {}
for line in Path(sys.argv[1]).read_text().splitlines():
    args = shlex.split(line)
    if "--module-root" in args and "--skip-bridge-output" in args:
        name = Path(args[args.index("--module-root") + 1]).name
        commands[name] = line
order = ["SkipLib", "SkipFoundation", "SkipModel", "SkipBridge", "SkipUI", "SkipAndroidBridge", "SkipSwiftUI", "Publit"]
Path(sys.argv[2]).write_text("\n".join(commands[n] for n in order if n in commands) + "\n")
