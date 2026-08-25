plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.sih26168.idr"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.sih26168.idr"
        minSdk = 26 // rotation-vector + FusedLocationProvider both require this floor
        targetSdk = 34
        versionCode = 1
        versionName = "0.1-mvp"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    sourceSets["main"].kotlin.srcDirs("src/main/kotlin")
    sourceSets["test"].kotlin.srcDirs("src/test/kotlin")
}

dependencies {
    // Compose UI (PRD.md Section 21) — versions pinned via BOM so individual
    // artifacts stay compatible with each other.
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")

    // StateFlow (SensorRepository's thread-safe hand-back point, Slice 1 —
    // CLAUDE.md Android Rule 7) needs kotlinx-coroutines explicitly; it is
    // not transitively pulled in by lifecycle-runtime-ktx on its own.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // FusedLocationProvider for GNSS fixes (PRD.md Section 21) — added now so
    // the module resolves; LocationRepository.kt is not written yet (it
    // belongs to a later slice, GNSS outage detection, not Slice 1).
    implementation("com.google.android.gms:play-services-location:21.3.0")

    // ONNX Runtime Mobile — added ahead of Slice 6 per PRD.md Section 26;
    // no inference code exists yet, this only makes the dependency available.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.18.0")

    testImplementation("junit:junit:4.13.2")
}
