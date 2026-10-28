plugins { alias(libs.plugins.android.library); `maven-publish` }
android {
    namespace = "io.github.jichuo1.meguviz.android.runtime"
    compileSdk = 37
    defaultConfig { minSdk = 27; consumerProguardFiles("consumer-rules.pro") }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    publishing { singleVariant("release") { withSourcesJar() } }
    lint { abortOnError = true; checkTestSources = true }
    testOptions { unitTests.isIncludeAndroidResources = true }
}
dependencies { testImplementation(project(":testkit")); testImplementation(libs.junit); testImplementation("org.robolectric:robolectric:4.17") }
apply(from = rootProject.file("gradle/publish.gradle.kts"))
