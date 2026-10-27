#!/bin/bash
# Installs JDK 21, Gradle, Skip, Android SDK/NDK, Swift 6.4.1 + Swift SDK for Android into /data/tools
set -x
# Resolve the script folder before changing directory (BASH_SOURCE may be relative).
D=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
for c in curl unzip tar python3; do command -v $c >/dev/null || { echo "missing: $c (Debian/Ubuntu: apt-get install unzip curl python3 libncurses6 libedit2 libcurl4)"; exit 1; }; done
mkdir -p /data/tools && cd /data/tools
[ -d jdk21 ] || { curl -sL -o jdk.tgz "https://api.adoptium.net/v3/binary/latest/21/ga/linux/x64/jdk/hotspot/normal/eclipse" && mkdir -p jdk21 && tar xzf jdk.tgz -C jdk21 --strip-components=1 && rm jdk.tgz && echo JDK_DONE; }
[ -d gradle-9.4.1 ] || { curl -sL -o g.zip https://services.gradle.org/distributions/gradle-9.4.1-bin.zip && unzip -q g.zip && rm g.zip && echo GRADLE_DONE; }
[ -d skip ] || { curl -sL -o skip-linux.zip https://github.com/skiptools/skip/releases/download/1.9.16/skip-linux.zip && unzip -qo skip-linux.zip -d skip && rm skip-linux.zip && echo SKIP_DONE; }
[ -d android-sdk/cmdline-tools/latest ] || { curl -sL -o clt.zip https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip && mkdir -p android-sdk/cmdline-tools && unzip -q clt.zip -d android-sdk/cmdline-tools && mv android-sdk/cmdline-tools/cmdline-tools android-sdk/cmdline-tools/latest && rm clt.zip; }
export JAVA_HOME=/data/tools/jdk21 ANDROID_HOME=/data/tools/android-sdk
yes | android-sdk/cmdline-tools/latest/bin/sdkmanager --licenses > /dev/null
android-sdk/cmdline-tools/latest/bin/sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0" "ndk;28.2.13676358" > sdkm.log 2>&1 && echo ANDROID_DONE
[ -d swift ] || { curl -sL -o swift.tgz https://download.swift.org/swift-6.4.1-release/ubi9/swift-6.4.1-RELEASE/swift-6.4.1-RELEASE-ubi9.tar.gz && mkdir -p swift && tar xzf swift.tgz -C swift --strip-components=1 && rm swift.tgz && echo SWIFT_DONE; }
u=https://download.swift.org/swift-6.4.1-release/android-sdk/swift-6.4.1-RELEASE/swift-6.4.1-RELEASE_android.artifactbundle.tar.gz
[ -d $HOME/.swiftpm/swift-sdks/swift-6.4.1-RELEASE_android.artifactbundle ] || { curl -sL -o asdk.tgz $u && sum=$(sha256sum asdk.tgz | cut -d' ' -f1) && /data/tools/swift/usr/bin/swift sdk install $u --checksum $sum && rm asdk.tgz && echo ASDK_DONE; }
echo ALL_DONE
# --- after the downloads: shims and Android sysroot for the Swift SDK
mkdir -p /data/tools/shim && cp $D/xcrun /data/tools/shim/xcrun && chmod +x /data/tools/shim/xcrun
. $D/env.sh && bash $SWIFT_ANDROID_SDK/swift-android/scripts/setup-android-sdk.sh
