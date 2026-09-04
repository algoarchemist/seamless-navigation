import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Read local.properties by hand, same pattern settings.gradle.kts uses for
// MAPBOX_DOWNLOADS_TOKEN -- each Gradle script reads the file independently
// since there's no shared extra between settings and project scripts here.
val localProperties = Properties().apply {
    val localPropertiesFile = File(rootDir, "local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use { load(it) }
    }
}
val mapboxPublicToken: String = localProperties.getProperty("MAPBOX_PUBLIC_TOKEN") ?: ""

android {
    namespace = "com.sih26168.idr"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.sih26168.idr"
        minSdk = 26 // rotation-vector + FusedLocationProvider both require this floor
        targetSdk = 34
        versionCode = 1
        versionName = "0.1-mvp"

        // Mapbox public token (PRD.md Section 7 2026-09-04 amendment), read
        // from git-ignored local.properties -- exposed via BuildConfig so no
        // future map-init code has to touch file I/O or the properties
        // format directly. Public/pk. token only: safe to end up in the
        // compiled APK, unlike MAPBOX_DOWNLOADS_TOKEN which never leaves
        // the Gradle build environment.
        buildConfigField("String", "MAPBOX_PUBLIC_TOKEN", "\"$mapboxPublicToken\"")
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
        buildConfig = true
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

    // osmdroid — real OpenStreetMap street tiles for ui/map/StreetMapView.kt
    // (Slice 8b), STILL the live basemap renderer. Originally chosen over
    // Google Maps Compose/Mapbox specifically because it needs no API
    // key/billing account. See the Mapbox dependency below and PRD.md
    // Section 7's 2026-09-04 amendment for why that tradeoff was revisited
    // -- osmdroid stays in place and fully functional until StreetMapView
    // is actually migrated in a later slice (this dependency alone is
    // scaffolding only, not yet wired into any screen).
    implementation("org.osmdroid:osmdroid-android:6.1.20")

    // Mapbox Maps SDK (PRD.md Section 7 2026-09-04 amendment) — added ahead
    // of the actual StreetMapView migration so the dependency/credential
    // plumbing (settings.gradle.kts's private-Maven auth,
    // MAPBOX_PUBLIC_TOKEN via BuildConfig above) is proven to resolve and
    // compile first, in its own small slice, before any UI code depends on
    // it. Chosen over Google Maps SDK specifically because its OSM-derived
    // basemap geometry stays consistent with the OSM/OSRM road geometry
    // routing/RoutingRepository.kt and the Section 19 map-snap logic
    // already use -- see PRD.md for the full reasoning. No initialization
    // or MapView code exists yet; that is later, explicitly scoped work.
    implementation("com.mapbox.maps:android:11.29.1")

    // Mapbox Navigation SDK (PRD.md Section 7 2026-09-05 amendment,
    // developer-requested override of CLAUDE.md Rule 2/4's normal
    // discussion-first process) — full turn-by-turn: voice guidance, lane
    // guidance, automatic rerouting on off-route, free-drive mode. This is
    // Mapbox's separate "Drop-In UI" (ui/screens/DropInNavigationScreen.kt),
    // NOT a manual assembly of the lower-level Navigation Core APIs — the
    // pre-built component already implements all of free-drive/active-
    // guidance/rerouting, matching "smallest amount of new code for the
    // full requested feature set" even though the dependency footprint
    // itself is large. Routes for ACTIVE GUIDANCE come from Mapbox's own
    // Directions API via this SDK, not routing/RoutingRepository.kt's OSRM
    // call — voice/banner/lane instruction text is generated server-side
    // by Mapbox's routing engine and doesn't exist in a plain OSRM
    // response, so this is a real routing-backend split, not just a UI
    // swap: OSRM/RoutingRepository still powers the route PREVIEW (search,
    // distance/duration, the existing ActiveRouteCard) exactly as before;
    // only entering active turn-by-turn hands off to Mapbox's own routing.
    implementation("com.mapbox.navigationcore:android:3.30.0")
    implementation("com.mapbox.navigationcore:ui-maps:3.30.0")
    implementation("com.mapbox.navigationcore:voice:3.30.0")
    implementation("com.mapbox.navigationcore:tripdata:3.30.0")
    implementation("com.mapbox.navigationcore:ui-components:3.30.0")
    implementation("com.mapbox.navigationcore:navigation:3.30.0")
    // MapboxManeuverView (banner + lane guidance) extends ConstraintLayout
    // -- not otherwise pulled in transitively by the Navigation SDK.
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    testImplementation("junit:junit:4.13.2")
}
