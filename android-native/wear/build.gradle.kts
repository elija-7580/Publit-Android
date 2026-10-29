plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Standalone Wear OS app: departures near you, no phone needed (WatchNearbyView on iOS).
android {
    namespace = "io.github.elija7580.publit.wear"
    compileSdk = 36
    defaultConfig {
        applicationId = "io.github.elija7580.publit"
        minSdk = 30
        targetSdk = 36
        versionCode = 2_000_000 + (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "0.8.0"
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
}
