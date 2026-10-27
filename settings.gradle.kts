pluginManagement {
    repositories {
        google()
        // Google-hosted Maven Central mirror first; Maven Central rate-limits (HTTP 429) shared egress.
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        // Google-hosted Maven Central mirror first; Maven Central rate-limits (HTTP 429) shared egress.
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
    }
}
rootProject.name = "OWLAcoustics"
include(":domain", ":app", ":lab")
