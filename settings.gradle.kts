pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "eTV"
include(":app")
// The AirPlay receiver (GPLv3, from flymop/airplay-tv): see airplay/NOTICE.md.
include(":airplay")

// A stand-in video app for the athan test on an emulator (scripts/athan-e2e.sh); never in eTV.
if (System.getenv("ETV_E2E") != null) include(":fakeplayer")
