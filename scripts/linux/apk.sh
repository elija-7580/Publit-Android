#!/bin/bash
# Usage: apk.sh [debug|release]   env: ARCHS (default aarch64), SKIP_SWIFT=1 (Kotlin/Gradle only)
# 1. Swift for Android (native .so)  2. Kotlin sources (kotlinize)  3. Gradle. Log: /data/apk.log
D=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd); source $D/env.sh
P=$(cd $D/../.. && pwd); MODE=${1:-debug}; cd $P
MOD=$(grep '^PRODUCT_NAME' Skip.env | sed 's/.*= *//')
# Bootstrap skipstone on a fresh Linux checkout (no manually copied binaries/logs needed).
if [ ! -x "$T/skipstone-bin" ]; then
  swift package resolve || exit 1
  BIN=$(find "$P/.build/artifacts/skip" -path '*/x86_64-swift-linux-musl/skip' -type f -print -quit)
  [ -n "$BIN" ] || { echo "skipstone artifact missing"; exit 1; }
  cp "$BIN" "$T/skipstone-bin"; chmod +x "$T/skipstone-bin"
fi
chmod +x "$D/skiplog.sh"
export SKIP_COMMAND_OVERRIDE=$D/skiplog.sh
# Android ABIs packaged into the APK/AAB = the Swift architectures (aarch64 → arm64-v8a, x86_64, armv7 → armeabi-v7a)
export PUBLIT_ABIS=$(echo ${ARCHS:-aarch64} | sed 's/aarch64/arm64-v8a/; s/armv7/armeabi-v7a/' | tr ' ' ',')
{
if [ -z "$SKIP_SWIFT" ]; then
skip android build --arch ${ARCHS:-aarch64} --configuration $MODE -d .build/Android/$MOD/jni-libs \
  --toolchain $T/swift --swift $T/swift/usr/bin/swift --sdk $SWIFT_ANDROID_SDK --ndk $ANDROID_NDK_HOME \
  --swift-sdk-home $HOME/.swiftpm/swift-sdks --product $MOD \
  -Xcc -fPIC -Xswiftc -DSKIP_BRIDGE -Xswiftc -DTARGET_OS_ANDROID || { echo "SWIFT BUILD FAILED"; exit 1; }
# One latest command per module, in dependency order. Ignore test modules.
python3 "$D/skip-args.py" "$T/skipstone_args.log" "$T/kz_args.log"
bash $D/kotlinize.sh "$T/kz_args.log" || exit 1
fi
# the module's gradle reads jniLibs from its default build dir: point it at the native libs
for d in .build/plugins/outputs/*/$MOD/destination/skipstone/$MOD; do mkdir -p $d/build; rm -rf $d/build/jni-libs; ln -s $P/.build/Android/$MOD/jni-libs $d/build/jni-libs; done
cd Android && SKIP_BRIDGE_ANDROID_BUILD_DISABLED=1 gradle --no-daemon -Dorg.gradle.jvmargs=-Xmx3g -Pkotlin.compiler.execution.strategy=in-process -Dorg.gradle.workers.max=2 assemble${MODE^} ${GRADLE_EXTRA} || { echo "GRADLE FAILED"; exit 1; }
echo "APK OK"
} > /data/apk.log 2>&1
tail -3 /data/apk.log
