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
rootProject.name = "vpn4tv"
include(":app")
// Generates the app's Baseline Profile (./gradlew :app:generateBaselineProfile, see baselineprofile/).
include(":baselineprofile")
