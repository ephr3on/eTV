// The AirPlay receiver inside eTV: flymop/airplay-tv's engine (UxPlay's AirPlay server, OpenSSL,
// libplist, a minimal FFmpeg for ALAC, Oboe for sound, Media3 for videos sent as links), without
// its own screens. eTV draws the screens (app/.../AirPlayActivity.kt) and the settings.
// GPLv3, like the code it comes from: see LICENSE and NOTICE.md in this folder.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// Which processors to build the native code for. The TV needs ARM; GitHub's emulator rehearsal
// (ETV_E2E) runs on x86_64. ETV_ABIS=arm64-v8a (say) builds just one, for a quicker build by hand.
val etvAbis: List<String> = System.getenv("ETV_ABIS")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
    ?: (listOf("armeabi-v7a", "arm64-v8a") + if (System.getenv("ETV_E2E") != null) listOf("x86_64") else emptyList())

android {
    namespace = "com.flymop.airplaytv"
    compileSdk = 35
    ndkVersion = "27.0.12077973"

    defaultConfig {
        // AAudio, MediaCodec and NsdManager as the receiver uses them need Android 8.
        // eTV itself still starts on older TVs; it just doesn't offer AirPlay there.
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
                )
            }
        }
        ndk {
            abiFilters.addAll(etvAbis)
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        prefab = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

/**
 * Applies the small fixes in src/main/cpp/patches/<submodule>/ to the submodules before the native
 * build (UxPlay: flymop's fixes; openssl-cmake: fetch OpenSSL from its own GitHub releases).
 * Each run starts from the submodule's own files, so applying twice is harmless.
 */
tasks.register("applyNativePatches") {
    val patchRoot = file("src/main/cpp/patches")
    val thirdParty = file("src/main/cpp/third_party")
    inputs.dir(patchRoot)
    doLast {
        patchRoot.listFiles { f -> f.isDirectory }?.sortedBy { it.name }?.forEach { dir ->
            val repo = File(thirdParty, dir.name)
            check(File(repo, ".git").exists()) {
                "${repo.path} is missing: run `git submodule update --init --recursive` first"
            }
            fun git(vararg args: String): Pair<Int, String> {
                val proc = ProcessBuilder("git", "-C", repo.absolutePath, *args).redirectErrorStream(true).start()
                val out = proc.inputStream.bufferedReader().readText()
                return proc.waitFor() to out
            }
            val patches = dir.listFiles { f -> f.extension == "patch" }?.sortedBy { it.name } ?: emptyList()
            // Put back every file the patches touch, then apply them all again in order.
            val touched = patches.flatMap { p ->
                val (code, out) = git("apply", "--numstat", p.absolutePath)
                if (code == 0) out.trim().lines().map { it.substringAfterLast("\t").trim() } else emptyList()
            }.filter { it.isNotEmpty() }.distinct()
            if (touched.isNotEmpty()) git("checkout", "--", *touched.toTypedArray())
            patches.forEach { p ->
                val (ok, _) = git("apply", "--check", "--unidiff-zero", p.absolutePath)
                if (ok == 0) {
                    val (code, out) = git("apply", "--unidiff-zero", p.absolutePath)
                    check(code == 0) { "Applying ${p.name} failed:\n$out" }
                } else {
                    logger.warn("eTV: ${dir.name}/${p.name} doesn't apply; skipped")
                }
            }
        }
    }
}

tasks.configureEach {
    if (name.startsWith("configureCMake")) dependsOn("applyNativePatches")
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    // api: eTV talks to the service directly (a LifecycleService with StateFlows)
    api("androidx.lifecycle:lifecycle-service:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Oboe: low-latency sound out of the native engine (a prefab AAR)
    implementation("com.google.oboe:oboe:1.9.3")

    // The sender's media session (title, artwork, play / pause from the remote and Now playing)
    implementation("androidx.media:media:1.7.0")

    // Videos sent as a link (Safari, YouTube, ...): HLS and MP4
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.3.1")
}
