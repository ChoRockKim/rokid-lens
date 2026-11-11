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
        // nanojson (the small JSON parser shared with rokid-youtube) is only published on JitPack.
        maven(url = "https://jitpack.io")
    }
}

rootProject.name = "rokid-lens"
include(":app")
