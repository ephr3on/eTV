plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.etv.launcher"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.etv.launcher"
        minSdk = 21
        targetSdk = 35
        // GitHub sets ETV_VERSION_CODE (100 + the build number) so every build counts as newer.
        versionCode = System.getenv("ETV_VERSION_CODE")?.toIntOrNull() ?: 6
        versionName = "1.8.4"
    }

    // One fixed key, so every new build installs over the previous one.
    // The key is never stored in this repository: GitHub reads it from the repository secrets
    // ETV_KEYSTORE_BASE64 and ETV_KEYSTORE_PASSWORD; on a Mac, put etv-release.jks in app/ and set
    // ETV_KEYSTORE_PASSWORD. Without them the build falls back to the debug key.
    val etvKeystore = file(System.getenv("ETV_KEYSTORE") ?: "etv-release.jks")
    val etvPassword = System.getenv("ETV_KEYSTORE_PASSWORD")
    val hasEtvKey = etvKeystore.exists() && !etvPassword.isNullOrEmpty()

    signingConfigs {
        if (hasEtvKey) {
            create("etv") {
                storeFile = etvKeystore
                storePassword = etvPassword
                keyAlias = "etv"
                keyPassword = etvPassword
            }
        }
    }

    buildTypes {
        release {
            // Small, fast APK.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName(if (hasEtvKey) "etv" else "debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // The athan recordings are played straight from the APK, so they must stay uncompressed.
    androidResources {
        noCompress += "mp3"
    }

    // Screenshots of the home screen (HomeShots), run on GitHub by .github/workflows/shots.yml.
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.systemProperty("shots.dir", layout.buildDirectory.dir("shots").get().asFile.path)
                System.getenv("ETV_SOUNDS_IN")?.let { dir -> it.systemProperty("sounds.in", dir) }
            }
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

// No libraries on purpose: plain Android Views keep the launcher tiny and quick to start.
// (The two below are only for the screenshot test; they never go into the APK.)
dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
