# Build environment for the Linux build (paths of /data/tools, see install.sh)
T=${PUBLIT_TOOLS:-/data/tools}
export JAVA_HOME=$T/jdk21 ANDROID_HOME=$T/android-sdk
export ANDROID_NDK_HOME=$T/android-sdk/ndk/28.2.13676358
export PATH=$T/shim:$T/swift/usr/bin:$T/skip/skip.artifactbundle/bin:$JAVA_HOME/bin:$T/gradle-9.4.1/bin:$ANDROID_HOME/platform-tools:$PATH
export SWIFT_ANDROID_SDK=$HOME/.swiftpm/swift-sdks/swift-6.4.1-RELEASE_android.artifactbundle
