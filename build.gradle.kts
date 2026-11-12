import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.jvm.tasks.Jar
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.util.*

plugins {
    id("net.fabricmc.fabric-loom")
    `maven-publish`
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("lbq.variants")
    id("lbq.preprocessing")
    id("lbq.loom")
    id("lbq.recorder")
    idea
}

idea {
    module {
        isDownloadSources = true
        isDownloadJavadoc = true
    }
}

val minecraft_version = project.property("minecraft_version") as String
val loader_version = project.property("loader_version") as String
val fabric_kotlin_version = project.property("fabric_kotlin_version") as String
val mod_version = project.property("mod_version") as String
val maven_group = project.property("maven_group") as String
val mod_name = project.property("mod_name") as String
val fabric_version = project.property("fabric_version") as String
val modmenu_version = project.property("modmenu_version") as String
val ktor_version = project.property("ktor_version") as String
val iris_version = project.property("iris_version") as String
val universalcraft_version = project.property("universalcraft_version") as String
val datafixer_version = project.property("datafixer_version") as String
val datafixerCapability = "me.owdding:item-data-fixer-$minecraft_version"

version = mod_version
group = maven_group

base { archivesName.set(mod_name) }

val bundled = configurations.create("bundled")
val processedIncludeJarsDir = layout.buildDirectory.dir("processIncludeJars")

configurations {
    implementation.get().extendsFrom(bundled)
}

repositories {
    maven("https://pkgs.dev.azure.com/djtheredstoner/DevAuth/_packaging/public/maven/v1")
    maven("https://repo.essential.gg/repository/maven-public")
    maven("https://maven.terraformersmc.com/releases/")
    maven("https://api.modrinth.com/maven")
    maven("https://jitpack.io")
    maven("https://maven.teamresourceful.com/repository/thatgravyboat/") {
        content { includeGroup("me.owdding") }
    }
}

dependencies {
    minecraft("com.mojang:minecraft:$minecraft_version")

    implementation("net.fabricmc:fabric-loader:$loader_version")
    implementation("net.fabricmc.fabric-api:fabric-api:$fabric_version")
    implementation("net.fabricmc:fabric-language-kotlin:$fabric_kotlin_version")

    runtimeOnly("me.djtheredstoner:DevAuth-fabric:1.2.2")
    compileOnly("maven.modrinth:iris:$iris_version")
    compileOnly("com.terraformersmc:modmenu:$modmenu_version")

    compileOnly("io.github.llamalad7:mixinextras-fabric:0.5.5")
    annotationProcessor("io.github.llamalad7:mixinextras-fabric:0.5.5")

    bundled("io.github.classgraph:classgraph:4.8.195")
    // Upstream meowdding item-data-fixer publishes one variant per Minecraft version, selected by capability.
    bundled("me.owdding:item-data-fixer:$datafixer_version") {
        capabilities { requireCapability(datafixerCapability) }
    }
    bundled("gg.essential:universalcraft-26.2-fabric:$universalcraft_version") {
        exclude(group = "net.fabricmc", module = "fabric-loader")
    }
    bundled("io.ktor:ktor-client-cio:$ktor_version")
    bundled("io.ktor:ktor-client-content-negotiation-jvm:$ktor_version")
    bundled("io.ktor:ktor-client-encoding:$ktor_version")
    bundled("io.ktor:ktor-serialization-kotlinx-json-jvm:$ktor_version")

    testImplementation(kotlin("test"))
}

/** Whether dotted version [a] is newer than [b] (1.11.1 > 1.11.0, 2.0 > 1.99); a non-numeric part counts as 0. */
fun isNewerVersion(a: String, b: String): Boolean {
    val x = a.split('.', '-', '+').map { it.toIntOrNull() ?: 0 }
    val y = b.split('.', '-', '+').map { it.toIntOrNull() ?: 0 }
    for (i in 0 until maxOf(x.size, y.size)) {
        val diff = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
        if (diff != 0) return diff > 0
    }
    return false
}

afterEvaluate {
    // NoammAddons 20a78018: Fabric Language Kotlin already ships the Kotlin stdlib and kotlinx (coroutines, serialization,
    // io, ...), and fabric.mod.json requires at least the version built against, so those are not nested again.
    // A library that ktor or another bundled one needs newer than Fabric Language Kotlin's is still nested
    // (Fabric Loader then loads the newest copy), so dropping one never downgrades it at runtime.
    val fabricKotlinVersions = configurations.detachedConfiguration(
        dependencies.create("net.fabricmc:fabric-language-kotlin:$fabric_kotlin_version")
    ).resolvedConfiguration.resolvedArtifacts.associate { it.moduleVersion.id.module to it.moduleVersion.id.version }

    bundled.resolvedConfiguration.resolvedArtifacts.forEach { artifact ->
        artifact.moduleVersion.id.let { id ->
            // WebSocket support was removed from LayBalt QOL; keep the transitive ktor
            // websocket artifacts off the shipped jar (nothing loads them at runtime).
            if (id.group == "io.ktor" && id.name.startsWith("ktor-websocket")) return@let
            val fabricKotlinVersion = fabricKotlinVersions[id.module]
            if (fabricKotlinVersion != null) {
                if (! isNewerVersion(id.version, fabricKotlinVersion)) return@let
                logger.info("Nesting ${id.module}:${id.version}: Fabric Language Kotlin only ships $fabricKotlinVersion")
            }
            val include = dependencies.add("include", "${id.group}:${id.name}:${id.version}") as ModuleDependency
            if ("${id.group}:${id.name}" == "me.owdding:item-data-fixer") {
                include.capabilities { requireCapability(datafixerCapability) }
            }
        }
    }
}

tasks.withType<JavaCompile>().configureEach { options.release.set(25) }
tasks.withType<KotlinCompile>().configureEach { compilerOptions { jvmTarget.set(JvmTarget.JVM_25) } }

fun File.writeWithoutNestedJarMetadata() {
    if (! isFile) return

    val modJson = JsonSlurper().parse(this).let { parsed ->
        @Suppress("UNCHECKED_CAST")
        (parsed as Map<String, Any?>).toMutableMap()
    }

    modJson.remove("jars")
    writeText(JsonOutput.prettyPrint(JsonOutput.toJson(modJson)))
}

fun ProcessResources.writeNestedJarMetadata() {
    dependsOn("processIncludeJars")
    inputs.dir(processedIncludeJarsDir)

    doLast {
        val modJsonFile = destinationDir.resolve("fabric.mod.json")
        val modJson = JsonSlurper().parse(modJsonFile).let { parsed ->
            @Suppress("UNCHECKED_CAST")
            (parsed as Map<String, Any?>).toMutableMap()
        }

        modJson["jars"] = processedIncludeJarsDir.get().asFile.listFiles()
            ?.filter { it.isFile && it.extension == "jar" }
            ?.sortedBy { it.name }
            ?.map { mapOf("file" to "META-INF/jars/${it.name}") }
            .orEmpty()

        modJsonFile.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(modJson)))
    }
}

fun ProcessResources.writeBuildInfo() {
    doLast {
        val props = Properties()
        props.setProperty("ci", (System.getenv("GITHUB_ACTIONS") == "true").toString())
        props.setProperty("built_at", System.currentTimeMillis().toString())
        destinationDir.resolve("build-info.properties").outputStream().use { props.store(it, null) }
    }
}

tasks.named<ProcessResources>("processResources") {
    doLast {
        destinationDir.resolve("fabric.mod.json").writeWithoutNestedJarMetadata()
    }
}

listOf("processCheatResources", "processLegitResources").forEach { taskName ->
    tasks.named<ProcessResources>(taskName) {
        writeNestedJarMetadata()
        writeBuildInfo()
    }
}

tasks.named<Jar>("jar") {
    destinationDirectory.set(layout.buildDirectory.dir("tmp/intermediateJars"))
    archiveClassifier.set("dev")

    from("LICENSE") {
        rename { "${it}_$mod_name" }
    }
    from("THIRD_PARTY_NOTICES.md") {
        rename { "THIRD_PARTY_NOTICES_$mod_name.md" }
    }
}

listOf("jarCheat", "jarLegit").forEach { taskName ->
    tasks.named<Jar>(taskName) {
        // LayBaltQOL-<mod_version>-<minecraft_version>[-legit].jar
        archiveVersion.set("$mod_version-$minecraft_version")

        dependsOn("processIncludeJars")
        from(processedIncludeJarsDir) {
            into("META-INF/jars")
        }
    }
}

// The published artifact is the cheat variant, with no classifier: LayBaltQOL-<version>-26.2.jar
tasks.named<Jar>("jarCheat") {
    archiveClassifier.set("")
}

// The legit variant is kept available (./gradlew jarLegit) but is not part of `build`.
listOf("jarLegit", "legitSourcesJar", "legitClasses", "compileLegitKotlin", "compileLegitJava").forEach { taskName ->
    tasks.named(taskName) { enabled = false }
}

tasks.named<Test>("test") {
    failOnNoDiscoveredTests = false
    maxHeapSize = "2g"
}
