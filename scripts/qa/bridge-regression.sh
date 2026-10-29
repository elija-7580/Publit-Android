#!/usr/bin/env bash
# Run the exact pinned SkipBridge Reflector on the JVM (no emulator/native Swift needed).
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
T=${PUBLIT_TOOLS:-/data/tools}
L="$T/gradle-9.4.1/lib"
REFLECTOR="$ROOT/.build/checkouts/skip-bridge/Sources/SkipBridge/Skip/Reflector.kt"
[ -f "$REFLECTOR" ] || { echo "Run swift package resolve first"; exit 1; }
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
CP="$L/kotlin-stdlib-2.3.0.jar:$L/kotlin-reflect-2.3.0.jar"
"$T/jdk21/bin/java" -cp "$L/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -no-stdlib -no-reflect -classpath "$CP" -d "$OUT/test.jar" \
    "$REFLECTOR" "$ROOT/scripts/qa/BridgeRegression.kt" > "$OUT/compile.log" 2>&1 || { cat "$OUT/compile.log"; exit 1; }
"$T/jdk21/bin/java" -cp "$OUT/test.jar:$CP" BridgeRegressionKt
