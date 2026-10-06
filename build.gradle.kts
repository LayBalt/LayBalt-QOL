plugins {
    id("net.fabricmc.fabric-loom")
    kotlin("jvm")
}

val minecraft_version: String by project
val loader_version: String by project
val fabric_api_version: String by project
val fabric_kotlin_version: String by project
val yacl_version: String by project
val modmenu_version: String by project
val mod_id: String by project
val mod_version: String by project
val maven_group: String by project
val archives_base_name: String by project

version = mod_version
group = maven_group

base {
    archivesName.set(archives_base_name)
}

repositories {
    // Loom adds the Minecraft/Fabric repositories automatically.
    maven("https://maven.isxander.dev/releases") { name = "Xander Maven (YACL)" }
    maven("https://maven.terraformersmc.com/releases") { name = "TerraformersMC (ModMenu)" }
}

loom {
    accessWidenerPath.set(file("src/main/resources/$mod_id.accesswidener"))

    runs {
        // Client-only mod: no server run configuration.
        remove(getByName("server"))
    }
}

dependencies {
    minecraft("com.mojang:minecraft:$minecraft_version")
    // No `mappings` line: Loom 1.17+ uses the official Mojang mappings by default.

    implementation("net.fabricmc:fabric-loader:$loader_version")
    implementation("net.fabricmc.fabric-api:fabric-api:$fabric_api_version")
    implementation("net.fabricmc:fabric-language-kotlin:$fabric_kotlin_version")

    // Config screen library (bundled into the mod jar).
    implementation("dev.isxander:yet-another-config-lib:$yacl_version")
    include("dev.isxander:yet-another-config-lib:$yacl_version")

    // Optional: adds a "Config" button in Mod Menu when it is installed.
    implementation("com.terraformersmc:modmenu:$modmenu_version")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
    withSourcesJar()
}

kotlin {
    jvmToolchain(25)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
}

tasks.processResources {
    val props = mapOf(
        "version" to mod_version,
        "mod_id" to mod_id,
        "minecraft_version" to minecraft_version,
        "loader_version" to loader_version,
        "fabric_kotlin_version" to fabric_kotlin_version,
    )
    inputs.properties(props)

    filesMatching("fabric.mod.json") {
        expand(props)
    }
}

tasks.jar {
    from("LICENSE") {
        rename { "${it}_${archives_base_name}" }
    }
}
