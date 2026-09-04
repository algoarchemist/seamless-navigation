import java.util.Properties
import org.gradle.authentication.http.BasicAuthentication

// Read local.properties by hand (rather than via providers.gradleProperty,
// which only sees gradle.properties/-P flags/env) since this project's
// existing convention already keeps per-machine, git-ignored config
// (sdk.dir) in local.properties -- MAPBOX_DOWNLOADS_TOKEN follows the same
// pattern instead of introducing a second config file.
val localProperties = Properties().apply {
    val localPropertiesFile = File(rootDir, "local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use { load(it) }
    }
}
val mapboxDownloadsToken: String = localProperties.getProperty("MAPBOX_DOWNLOADS_TOKEN") ?: ""

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
        // Mapbox Maps SDK (PRD.md Section 7 2026-09-04 amendment) is hosted
        // on Mapbox's own private Maven repo, not mavenCentral -- Gradle
        // must authenticate every fetch with a secret DOWNLOADS:READ token
        // (see local.properties). Username is literally the string
        // "mapbox", not a personal account name -- that's Mapbox's own
        // fixed convention for this repo's basic auth.
        maven {
            url = uri("https://api.mapbox.com/downloads/v2/releases/maven")
            authentication {
                create<BasicAuthentication>("basic")
            }
            credentials {
                username = "mapbox"
                password = mapboxDownloadsToken
            }
        }
    }
}

rootProject.name = "IntelligentDeadReckoning"
include(":app")
