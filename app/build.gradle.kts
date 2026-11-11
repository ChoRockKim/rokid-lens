import com.android.build.api.dsl.ApplicationExtension

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.paparazzi)
}

// App version "X.Y.Z" from -PappVersion (scripts/release.sh passes it).
// versionCode = X*10000 + Y*100 + Z, so every release installs over the previous one.
val appVersion = providers.gradleProperty("appVersion").orNull?.removePrefix("v") ?: "0.1.0"
val appVersionCode = appVersion.split(".").map { it.toInt() }.let { (x, y, z) -> x * 10000 + y * 100 + z }

// Release signing key, kept outside the repository (see scripts/release.sh). Without it, release
// builds fall back to the debug key for local use.
val releaseKeystore = providers.gradleProperty("RELEASE_KEYSTORE").orNull
val releaseKeystorePassword = providers.gradleProperty("RELEASE_KEYSTORE_PASS").orNull
val releaseKeyAlias = providers.gradleProperty("RELEASE_KEY_ALIAS").orNull ?: "rokidyoutube"
// OTA: this app's folder on the rokid-conversate server (see update/Updater.kt).
val updateUrl = providers.gradleProperty("updateUrl").orNull
    ?: "https://server-v2-production-8f97.up.railway.app/apps/rokid-lens"

val translateUrl = providers.gradleProperty("translateUrl").orNull
    ?: "https://server-v2-production-8f97.up.railway.app/lens/translate"

kotlin {
    jvmToolchain(21)
}

configure<ApplicationExtension> {
    namespace = "com.chorockkim.rokidlens"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.chorockkim.rokidlens"
        // Rokid Glasses run Android 12 (API 31/32).
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersion

        buildConfigField("String", "UPDATE_URL", "\"$updateUrl\"")
        // Photo translation with Gemini on the rokid-conversate server (falls back to ML Kit).
        buildConfigField("String", "TRANSLATE_URL", "\"$translateUrl\"")
        // The glasses are arm64; leaving out other ABIs keeps the bundled ML Kit models small.
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    signingConfigs {
        if (releaseKeystore != null && releaseKeystorePassword != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeystorePassword // PKCS12: same as the store password
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar)

    implementation(libs.androidx.activity)
    implementation(libs.androidx.core)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.kotlinx.coroutines.android)

    // Glasses camera.
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)

    // On-device OCR and translation, the same ML Kit set as Rokid Nexus' Lens (bundled models,
    // so no Google Play services on the glasses are needed for OCR).
    implementation(libs.mlkit.text.latin)
    implementation(libs.mlkit.text.korean)
    implementation(libs.mlkit.text.japanese)
    implementation(libs.mlkit.text.chinese)
    implementation(libs.mlkit.text.devanagari)
    implementation(libs.mlkit.languageid)
    implementation(libs.mlkit.translate)

    // OTA.
    implementation(libs.okhttp)
    implementation(libs.nanojson)

    testImplementation(libs.junit)
}

tasks.withType<Test>().configureEach {
    testLogging { showStandardStreams = true }
}
