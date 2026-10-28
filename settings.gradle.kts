pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "MeguvizAndroid"
include(":android-runtime", ":android-window", ":android-lifecycle", ":android-resources-loader")
include(":testkit")
