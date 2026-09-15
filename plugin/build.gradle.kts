import io.papermc.paperweight.userdev.ReobfArtifactConfiguration

plugins {
    java
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.23"
}

fun gitVersion(): String {
    System.getenv("VERSION")?.takeIf { it.isNotEmpty() }?.let { return it }
    val tag = runCatching {
        ProcessBuilder("git", "describe", "--tags", "--exact-match")
            .directory(projectDir).start().inputStream.bufferedReader().readText().trim()
    }.getOrNull()
    if (!tag.isNullOrEmpty()) return tag
    return runCatching {
        ProcessBuilder("git", "rev-parse", "--short", "HEAD")
            .directory(projectDir).start().inputStream.bufferedReader().readText().trim()
    }.getOrNull().takeUnless { it.isNullOrEmpty() } ?: "dev"
}

group = "io.github.octarect"
version = gitVersion()

repositories {
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    paperweight.paperDevBundle("26.2.build.+")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

paperweight.reobfArtifactConfiguration = ReobfArtifactConfiguration.MOJANG_PRODUCTION

tasks.jar {
    archiveBaseName = "autonomous-npc-plugin"
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}
