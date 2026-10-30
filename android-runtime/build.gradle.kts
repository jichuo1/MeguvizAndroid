plugins { alias(libs.plugins.android.library); `maven-publish` }
android {
    namespace = "io.github.jichuo1.meguviz.android.runtime"
    compileSdk = 37
    compileSdkMinor = 2
    defaultConfig {
        minSdk = 27
        consumerProguardFiles("consumer-rules.pro")
        // API 37.2 types stay inside MemoryBudgetApi37 behind a runtime full-version check and never appear in a
        // public signature, so consumers keep compiling against 37.0; do not raise their requirement to 37.2.
        aarMetadata { minCompileSdk { version = release(37) } }
    }
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
