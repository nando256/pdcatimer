import org.gradle.jvm.toolchain.JavaLanguageVersion

plugins {
    java
}

group = "net.nando256"
version = "1.0.0"

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
