pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        exclusiveContent {
            forRepository { maven { name = "ThisCandidate"; url = uri("../build/repo") } }
            filter { includeGroup("io.github.jichuo1.meguviz") }
        }
        google(); mavenCentral()
    }
}
rootProject.name = "MeguvizAndroidConsumers"
include(":plain", ":embedded")
