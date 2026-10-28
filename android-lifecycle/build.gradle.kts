plugins { alias(libs.plugins.android.library); `maven-publish` }
android {
    namespace = "io.github.jichuo1.meguviz.android.lifecycle"
    compileSdk = 37
    defaultConfig { minSdk = 27; consumerProguardFiles("consumer-rules.pro") }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    publishing { singleVariant("release") { withSourcesJar() } }
    lint { abortOnError = true; checkTestSources = true }
    testOptions { unitTests.isIncludeAndroidResources = true }
}
dependencies {
    api(project(":android-window"))
    api(libs.androidx.lifecycle)
    api(libs.androidx.appcompat)
    testImplementation(libs.junit)
    testImplementation("org.robolectric:robolectric:4.17")
}
apply(from = rootProject.file("gradle/publish.gradle.kts"))
