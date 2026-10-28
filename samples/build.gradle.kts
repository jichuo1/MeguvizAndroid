import java.security.MessageDigest
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import groovy.json.JsonOutput

plugins { id("com.android.application") version "9.4.1" apply false }
subprojects {
    configurations.configureEach { resolutionStrategy.cacheChangingModulesFor(0, "seconds") }
}
tasks.register("recordConsumption") {
    dependsOn(":plain:assembleRelease", ":embedded:assembleRelease")
    doLast {
        val rows = listOf("plain", "embedded").associateWith { flavor ->
            project(":$flavor").configurations.getByName("releaseRuntimeClasspath").resolvedConfiguration.resolvedArtifacts.map {
                val hash = MessageDigest.getInstance("SHA-256").digest(it.file.readBytes()).joinToString("") { b -> "%02x".format(b) }
                mapOf("group" to it.moduleVersion.id.group, "name" to it.name, "version" to it.moduleVersion.id.version,
                    "sha256" to hash, "file" to it.file.absolutePath,
                    "component_kind" to if (it.id.componentIdentifier is ModuleComponentIdentifier) "module" else "project")
            }
        }
        layout.buildDirectory.file("consumed-artifacts.json").get().asFile.apply {
            parentFile.mkdirs(); writeText(JsonOutput.prettyPrint(JsonOutput.toJson(rows)))
        }
    }
}
