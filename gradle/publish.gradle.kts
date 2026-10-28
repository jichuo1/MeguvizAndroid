apply(plugin = "maven-publish")
configure<PublishingExtension> {
    repositories { maven { name = "Candidate"; url = uri(rootProject.layout.buildDirectory.dir("repo")) } }
    publications {
        register<MavenPublication>("release") {
            artifactId = "meguviz-${project.name}"
            pom {
                name.set(artifactId)
                description.set("MVX-1 scoped Android lifecycle, resources and scheduling. Development candidate; consult verification receipts.")
                url.set("https://github.com/jichuo1/MeguvizAndroid")
                licenses { license { name.set("Apache License 2.0"); url.set("https://www.apache.org/licenses/LICENSE-2.0") } }
                developers { developer { id.set("jichuo1"); name.set("jichuo1") } }
                scm {
                    url.set("https://github.com/jichuo1/MeguvizAndroid")
                    connection.set("scm:git:https://github.com/jichuo1/MeguvizAndroid.git")
                    developerConnection.set("scm:git:ssh://git@github.com/jichuo1/MeguvizAndroid.git")
                }
            }
            afterEvaluate { from(components["release"]) }
        }
    }
}
