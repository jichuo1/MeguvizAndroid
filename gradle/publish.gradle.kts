apply(plugin = "maven-publish")
configure<PublishingExtension> {
    repositories { maven { name = "Candidate"; url = uri(rootProject.layout.buildDirectory.dir("repo")) } }
    publications {
        register<MavenPublication>("release") {
            artifactId = "meguviz-${project.name}"
            pom {
                name.set(artifactId)
                description.set("MVX-1 scoped Android lifecycle, resources and scheduling. Development candidate; consult verification receipts.")
                url.set("https://github.com/jichuo1")
                licenses { license { name.set("Apache License 2.0"); url.set("https://www.apache.org/licenses/LICENSE-2.0") } }
            }
            afterEvaluate { from(components["release"]) }
        }
    }
}
