import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The single source of truth for the app's semantic version -- bump it by
// hand when you mean to. CI appends its own build metadata on top (see
// appVersionName below); this is only the fallback base for local builds.
val baseVersionName = Properties().apply {
    val propsFile = file("version.properties")
    if (propsFile.exists()) propsFile.inputStream().use { load(it) }
}.getProperty("VERSION_NAME", "1.0.0")

android {
    namespace = "com.ridelink.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ridelink.app"
        // Nearby Connections + Compose only; adaptive launcher icons need API 26+.
        minSdk = 26
        targetSdk = 35
        // CI always passes both explicitly (computed from git in the
        // workflow, so Play Store gets a monotonically increasing
        // versionCode regardless of this file). Local builds fall back to
        // a plain, never-published version.
        versionCode = (project.findProperty("appVersionCode") as String?)?.toIntOrNull() ?: 1
        versionName = (project.findProperty("appVersionName") as String?) ?: baseVersionName
    }

    signingConfigs {
        create("release") {
            // Populated by CI from the KEYSTORE_* secrets (see
            // .github/workflows/build-apk.yml and release.yml). Left unset
            // for a local build -- attempting assembleRelease without them
            // fails loudly at signing time, which is correct: there's no
            // meaningful unsigned fallback for a release build.
            val keystorePath = System.getenv("KEYSTORE_PATH")
            if (!keystorePath.isNullOrEmpty()) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
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
        compose = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.core:core-ktx:1.13.1")

    // Google Play services: Nearby Connections API (local P2P, no server).
    implementation("com.google.android.gms:play-services-nearby:19.3.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
