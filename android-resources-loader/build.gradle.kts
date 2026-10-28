plugins { alias(libs.plugins.android.library); `maven-publish` }
android {
    namespace = "io.github.jichuo1.meguviz.android.resourcesloader"
    compileSdk = 37
    defaultConfig {
        minSdk = 27
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    publishing { singleVariant("release") { withSourcesJar() } }
    lint { abortOnError = true; checkTestSources = true }
    testOptions { unitTests.isIncludeAndroidResources = true }
}
dependencies {
    api(project(":android-runtime"))
    testImplementation(libs.junit)
    testImplementation("org.robolectric:robolectric:4.17")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}
apply(from = rootProject.file("gradle/publish.gradle.kts"))
