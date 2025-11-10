import io.papermc.hangarpublishplugin.model.Platforms
import org.gradle.jvm.toolchain.JavaLanguageVersion

plugins {
    java
    `maven-publish`
    id("io.papermc.hangar-publish-plugin") version "0.1.3"
}

group = "net.nando256"
version = (findProperty("version.override") as String?) ?: "1.0.0"

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

tasks.jar {
    archiveBaseName.set("PDCATimer")
}

tasks.processResources {
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}

hangarPublish {
    publications.register("plugin") {

        id.set("PDCATimer")

        version.set(project.version.toString())

        channel.set(providers.gradleProperty("hangar.channel").orElse("Snapshot"))

        apiKey.set(System.getenv("HANGAR_API_TOKEN"))

        platforms {
            register(Platforms.PAPER) {
                jar.set(tasks.jar.flatMap { it.archiveFile })

                val versions = (property("paperVersion") as String).split(",").map { it.trim() }
                platformVersions.set(versions)
            }
        }
    }
}
