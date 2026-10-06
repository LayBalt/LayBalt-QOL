pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        mavenCentral()
        gradlePluginPortal()
    }

    val loom_version: String by settings
    val kotlin_version: String by settings

    plugins {
        id("net.fabricmc.fabric-loom") version loom_version
        kotlin("jvm") version kotlin_version
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "LayBaltQOL"
