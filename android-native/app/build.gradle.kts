plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.github.elija7580.publit"
    compileSdk = 36

    defaultConfig {
        // The real app ID: the native app replaces the old Skip build (uninstall that once: other signing key).
        applicationId = "io.github.elija7580.publit"
        minSdk = 26
        targetSdk = 36
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "0.8.0"
        // Optional Google Wallet export: the signing service's HTTPS URL (services/google-wallet-signer).
        // Without it the button stays hidden. The issuer key never goes into the app.
        manifestPlaceholders["walletSignerUrl"] = (project.findProperty("publit.walletSignerUrl") as String?) ?: ""
    }

    signingConfigs {
        // A fixed test key (committed, not secret) so every test build installs over the previous one.
        // The Play upload key is never used here.
        create("test") {
            storeFile = file("publit-test.jks")
            storePassword = "publit-test"
            keyAlias = "publit-test"
            keyPassword = "publit-test"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("test")
        }
        debug {
            applicationIdSuffix = ".debug"
            signingConfig = signingConfigs.getByName("test")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // MapLibre ships native code: one APK per ARM architecture keeps each small (Play uses bundles).
    splits { abi { isEnable = true; reset(); include("arm64-v8a", "armeabi-v7a"); isUniversalApk = false } }
    buildFeatures { compose = true }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.play.services.location)
    implementation(libs.maplibre)
    implementation("androidx.glance:glance-appwidget:1.1.1")
    // Tickets, read on the device: ML Kit through Play Services (no models in the APK), ZXing draws codes again.
    implementation("com.google.android.gms:play-services-mlkit-text-recognition:19.0.1")
    implementation("com.google.android.gms:play-services-mlkit-barcode-scanning:18.3.1")
    implementation("com.google.android.gms:play-services-mlkit-document-scanner:16.0.0")
    implementation("com.google.zxing:core:3.5.4")
    implementation("androidx.exifinterface:exifinterface:1.4.1")
    debugImplementation(libs.compose.ui.tooling)
    testImplementation(libs.junit)
}
