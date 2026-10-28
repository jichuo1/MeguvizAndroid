plugins { alias(libs.plugins.android.library); `maven-publish` }
android {
    namespace = "io.github.jichuo1.meguviz.android.window"
    compileSdk = 37
    defaultConfig { minSdk = 27; consumerProguardFiles("consumer-rules.pro") }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    publishing { singleVariant("release") { withSourcesJar() } }
    lint { abortOnError = true; checkTestSources = true }
    testOptions { unitTests.isIncludeAndroidResources = true }
}
dependencies { api(project(":android-runtime")); api(libs.androidx.core); testImplementation(libs.junit); testImplementation("org.robolectric:robolectric:4.17") }
apply(from = rootProject.file("gradle/publish.gradle.kts"))
val windowFixture = layout.buildDirectory.dir("generated/window-fixture")
val copyWindowFixture by tasks.registering(Copy::class) {
    from(rootProject.file("verification/fixtures/window-compatibility/WindowCompatibilityFixture.kt"))
    into(windowFixture)
}
extensions.configure<com.android.build.api.dsl.LibraryExtension> {
    sourceSets.named("test") { kotlin.directories += windowFixture.get().asFile.absolutePath }
}
tasks.matching { it.name.contains("UnitTest") }.configureEach { dependsOn(copyWindowFixture) }
