// A stand-in for a video app, only for the athan test on an emulator (scripts/athan-e2e.sh).
// Never part of eTV: settings.gradle.kts only includes it when ETV_E2E is set.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.etv.fakeplayer"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.etv.fakeplayer"
        minSdk = 21
        targetSdk = 35
        versionCode = 1
        versionName = "1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}
