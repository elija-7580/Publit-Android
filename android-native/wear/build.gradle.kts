plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Wear OS app: departures near you with no phone needed (WatchNearbyView on iOS), and the trip under way
// from the phone app as a live screen and an ongoing activity.
android {
    namespace = "io.github.elija7580.publit.wear"
    compileSdk = 36
    defaultConfig {
        applicationId = "io.github.elija7580.publit"
        minSdk = 30
        targetSdk = 36
        versionCode = 2_000_000 + (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "0.8.0"
        // Watches are ARM: no x86 copies of the map library in the APK.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }
    signingConfigs {
        create("test") {
            storeFile = file("../app/publit-test.jks")
            storePassword = "publit-test"; keyAlias = "publit-test"; keyPassword = "publit-test"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("test")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation("androidx.wear.compose:compose-material:1.4.1")
    implementation("androidx.wear.compose:compose-foundation:1.4.1")
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    // The trip under way from the phone (Data Layer) and as an ongoing activity on the watch face.
    implementation("com.google.android.gms:play-services-wearable:19.0.0")
    implementation("androidx.wear:wear-ongoing:1.0.0")
    implementation(libs.androidx.core.ktx)
    // The map of the trip under way (same map library and OpenFreeMap tiles as the phone app).
    implementation(libs.maplibre)
}
