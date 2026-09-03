plugins {
    kotlin("jvm") version "2.4.10"
    id("net.fabricmc.fabric-loom") version "1.17-SNAPSHOT"
}

version = "1.0.0"
group = "org.kyowa"

base {
    archivesName = "FamilyAddons"
}

repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net/")
    maven("https://maven.terraformersmc.com/releases/")
    maven("https://jitpack.io")
    maven { url = uri("https://maven.notenoughupdates.org/releases/") }
}

dependencies {
    // Un-obfuscated: no `mappings(...)` line and deps use the standard
    // `implementation`/`compileOnly` configurations (loom no longer remaps).
    minecraft("com.mojang:minecraft:26.2")
    implementation("net.fabricmc:fabric-loader:0.19.5")
    implementation("net.fabricmc.fabric-api:fabric-api:0.159.0+26.2")
    implementation("net.fabricmc:fabric-language-kotlin:1.13.13+kotlin.2.4.10")
    compileOnly("com.terraformersmc:modmenu:20.0.1")
    implementation("org.notenoughupdates.moulconfig:modern-26.2:4.7.2")
    include("org.notenoughupdates.moulconfig:modern-26.2:4.7.2")
}

loom {
    accessWidenerPath = file("src/main/resources/familyaddons.accesswidener")
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
}

// Use a Java 25 toolchain so the build compiles with JDK 25 regardless of which
// JVM launches Gradle (fixes "release version 25 not supported" when the Gradle
// JVM is older). Gradle auto-detects the installed JDK 25.
kotlin {
    jvmToolchain(25)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

// Un-obfuscated: the final artifact is produced by `jar` (no `remapJar`).
tasks.jar {
    archiveVersion.set("1.0.0")
}
