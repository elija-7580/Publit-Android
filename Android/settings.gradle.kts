// This gradle project is part of a conventional Skip app project.
pluginManagement {
    // Initialize the Skip plugin folder and perform a pre-build for non-Xcode builds
    val pluginPath = File.createTempFile("skip-plugin-path", ".tmp")

    // overriding outputs for an Android IDE can be done by un-commenting and setting the Xcode path:
    //System.setProperty("BUILT_PRODUCTS_DIR", "${System.getProperty("user.home")}/Library/Developer/Xcode/DerivedData/MySkipProject-HASH/Build/Products/Debug-iphonesimulator")

    // apk.sh has already compiled Swift for Android and generated Kotlin. Repeating a
    // host (macOS/iOS) pre-build on Linux fails on CoreGraphics and invalidates outputs.
    // Keep Android Studio's normal pre-build unless the completed CLI pipeline opts out.
    val prebuildFlag = if (System.getenv("PUBLIT_SKIP_HOST_PREBUILD") == "1") "--no-prebuild" else "--prebuild"
    val skipPluginResult = providers.exec {
        commandLine("/bin/sh", "-c", "skip plugin ${prebuildFlag} --package-path '${settings.rootDir.parent}' --plugin-ref '${pluginPath.absolutePath}'")
        environment("PATH", "${System.getenv("PATH")}:/opt/homebrew/bin")
    }
    val skipPluginOutput = skipPluginResult.standardOutput.asText.get()
    print(skipPluginOutput)
    val skipPluginError = skipPluginResult.standardError.asText.get()
    print(skipPluginError)

    includeBuild(pluginPath.readText()) {
        name = "skip-plugins"
    }
}

plugins {
    id("skip-plugin") apply true
}

// Standalone watch app; no Swift runtime is needed on Wear OS.
include(":wear")

// Keep the requested Android 8 floor even when Skip's test defaults move upwards.
dependencyResolutionManagement {
    versionCatalogs.named("libs") { version("android-sdk-min", "26") }
}
