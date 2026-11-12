import net.fabricmc.loom.api.LoomGradleExtensionAPI
import net.fabricmc.loom.task.prod.ClientProductionRunTask
import org.gradle.jvm.tasks.Jar
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.util.zip.ZipFile

/*
 * LayBalt Recorder: the Fishing Recorder on its own, for other players to record their fishing.
 * `./gradlew jarRecorder` builds build/libs/LayBaltRecorder-<recorder_version>-<minecraft_version>.jar (not part of `build`).
 *
 * It is compiled from [recorderShared] (preprocessed with the CHEAT and RECORDER flags) plus src/recorder: small stand-ins
 * for the few LayBalt QOL classes the shared files use, the entrypoint, three mixins and the resources. Nothing else from
 * src/main is on its source path, so a shared file that starts using another LayBalt QOL class fails to compile here, and
 * verifyRecorderJar checks the finished jar.
 */

plugins {
    id("net.fabricmc.fabric-loom")
    kotlin("jvm")
}

val recorderVersion = project.property("recorder_version") as String
val minecraftVersion = project.property("minecraft_version") as String
val fabricVersion = project.property("fabric_version") as String
val fabricKotlinVersion = project.property("fabric_kotlin_version") as String

/** The LayBalt QOL sources the recorder-only jar is built from, relative to src/main. */
val recorderShared = listOf(
    "kotlin/com/laybalt/features/impl/fishing/FishingRecorder.kt",
    "kotlin/com/laybalt/features/impl/fishing/SeaCreatureTracker.kt",
    "kotlin/com/laybalt/features/impl/fishing/SeaCreatures.kt",
    "kotlin/com/laybalt/event/Event.kt",
    "kotlin/com/laybalt/event/EventBus.kt",
    "kotlin/com/laybalt/event/EventContext.kt",
    "kotlin/com/laybalt/event/EventListener.kt",
    "kotlin/com/laybalt/event/priority/EventPriority.kt",
    "kotlin/com/laybalt/event/priority/PriorityComparator.kt",
    "kotlin/com/laybalt/event/impl/ChatMessageEvent.kt",
    "kotlin/com/laybalt/event/impl/ContainerEvent.kt",
    "kotlin/com/laybalt/event/impl/MessageSentEvent.kt",
    "kotlin/com/laybalt/event/impl/ShutdownEvent.kt",
    "kotlin/com/laybalt/event/impl/TickEvent.kt",
    "kotlin/com/laybalt/event/impl/WorldChangeEvent.kt",
    "kotlin/com/laybalt/init/types/ISelfInit.kt",
    "kotlin/com/laybalt/utils/Extensions.kt",
)

/** Class name prefixes the recorder-only jar may contain (every one of them is listed above or in src/recorder). */
val recorderClassPrefixes = listOf(
    "com/laybalt/LayBaltQOL",
    "com/laybalt/event/",
    "com/laybalt/features/Feature",
    "com/laybalt/features/Shortcuts",
    "com/laybalt/features/impl/fishing/FishingRecorder",
    "com/laybalt/features/impl/fishing/SeaCreatureTracker",
    "com/laybalt/features/impl/fishing/SeaCreatures",
    "com/laybalt/init/types/ISelfInit",
    "com/laybalt/recorder/",
    "com/laybalt/utils/ChatUtils",
    "com/laybalt/utils/ExtensionsKt",
    "com/laybalt/utils/items/ItemRarity",
    "com/laybalt/utils/items/ItemUtils",
)

/** The other files the jar may contain. */
val recorderFiles = setOf(
    "META-INF/MANIFEST.MF",
    "META-INF/laybaltrecorder.kotlin_module",
    "fabric.mod.json",
    "laybaltrecorder.mixins.json",
    "LICENSE_LayBaltRecorder",
    "THIRD_PARTY_NOTICES_LayBaltRecorder.md",
    "assets/laybaltrecorder/lang/en_us.json",
    "assets/laybaltrecorder/lang/ru_ru.json",
    "assets/lbqmod/sea-creatures.json",
)

/**
 * Libraries on the recorder's compile or dev classpath that a plain Fabric install with Fabric API and Fabric Language
 * Kotlin does not have (LayBalt QOL bundles or soft-depends on them), and names of features the jar must not carry.
 */
val forbiddenReferences = listOf(
    "gg/essential/", "io/ktor/", "io/github/classgraph/", "me/owdding/",
    "net/irisshaders/", "net/coderbot/", "com/terraformersmc/", "me/djtheredstoner/",
)
val forbiddenWords = listOf(
    "AutoMobKiller", "Auto Mob Killer", "AutoFishing", "autoFishing", "Auto Fishing", "BehaviorProfile", "BehaviorLearner",
    "KillRules", "LoadoutSwitcher", "HumanAim", "Humanize", "MotorModel", "TimingModel", "ViewPolicy", "KillerTrace",
    "BobberFlight", "BobberWorld", "GlancePolicy", "GlanceMeasure",
)

val preprocessRecorder = registerRecorderPreprocessTask(recorderShared)

val sourceSets = the<SourceSetContainer>()
val mainSourceSet = sourceSets.named("main").get()
val preprocessedJava = layout.buildDirectory.dir("preprocessed/recorder/java")
val preprocessedKotlin = layout.buildDirectory.dir("preprocessed/recorder/kotlin")

val recorderSourceSet = sourceSets.create("recorder") {
    java.setSrcDirs(listOf(preprocessedJava.get().asFile, file("src/recorder/java")))
    (extensions.getByName("kotlin") as SourceDirectorySet).setSrcDirs(listOf(preprocessedKotlin.get().asFile, file("src/recorder/kotlin")))
    resources.setSrcDirs(listOf("src/recorder/resources"))
    compileClasspath += mainSourceSet.compileClasspath
    runtimeClasspath += mainSourceSet.runtimeClasspath - mainSourceSet.output
}

val compileRecorderKotlin = tasks.named<KotlinCompile>("compileRecorderKotlin") {
    dependsOn(preprocessRecorder)
    compilerOptions { moduleName.set("laybaltrecorder") }
}

tasks.named<JavaCompile>("compileRecorderJava") {
    dependsOn(preprocessRecorder, compileRecorderKotlin)
    options.release.set(25)
    classpath += files(compileRecorderKotlin.flatMap { it.destinationDirectory })
}

tasks.named<ProcessResources>("processRecorderResources") {
    inputs.property("version", recorderVersion)
    filesMatching("fabric.mod.json") { expand(mapOf("version" to recorderVersion)) }
    from("src/main/resources") { include("assets/lbqmod/sea-creatures.json") }
    from("LICENSE") { rename { "LICENSE_LayBaltRecorder" } }
}

val jarRecorder = tasks.register<Jar>("jarRecorder") {
    group = "build"
    description = "Builds the recorder-only jar (the Fishing Recorder on its own) for other players."
    from(recorderSourceSet.output)
    archiveBaseName.set("LayBaltRecorder")
    archiveVersion.set("$recorderVersion-$minecraftVersion")
    archiveClassifier.set("")
    destinationDirectory.set(layout.buildDirectory.dir("libs"))
    finalizedBy("verifyRecorderJar")
}

tasks.register("verifyRecorderJar") {
    group = "verification"
    description = "Checks that the recorder-only jar holds the recorder and nothing else of LayBalt QOL."
    val jarFile = jarRecorder.flatMap { it.archiveFile }
    inputs.file(jarFile)
    doLast {
        val problems = mutableListOf<String>()
        ZipFile(jarFile.get().asFile).use { zip ->
            val entries = zip.entries().toList().filterNot { it.isDirectory }
            val classes = entries.map { it.name }.filter { it.endsWith(".class") }.map { it.removeSuffix(".class") }.toSet()
            entries.map { it.name }.filterNot { it.endsWith(".class") }.filter { it !in recorderFiles }.forEach { problems += "unexpected file $it" }
            classes.filter { c -> recorderClassPrefixes.none { c.startsWith(it) } }.forEach { problems += "unexpected class $it" }

            val laybaltReference = Regex("""com/laybalt/[A-Za-z0-9_/$]+""")
            for (c in classes) {
                val text = zip.getInputStream(zip.getEntry("$c.class")).readBytes().toString(Charsets.ISO_8859_1)
                laybaltReference.findAll(text).map { it.value }.toSet().filter { it !in classes }
                    .forEach { problems += "$c refers to $it, which is not in the jar" }
                forbiddenReferences.filter { it in text }.forEach { problems += "$c refers to $it (a library LayBalt QOL bundles)" }
                forbiddenWords.filter { it in text }.forEach { problems += "$c contains \"$it\"" }
            }
            for (name in listOf("fabric.mod.json", "laybaltrecorder.mixins.json")) {
                if (zip.getEntry(name) == null) problems += "missing $name"
            }
            if (zip.getInputStream(zip.getEntry("fabric.mod.json")).readBytes().toString(Charsets.UTF_8).contains("\${version}")) problems += "fabric.mod.json version not expanded"
        }
        if (problems.isNotEmpty()) throw GradleException("The recorder-only jar is not clean:\n" + problems.joinToString("\n") { "  - $it" })
        logger.lifecycle("Recorder jar OK: ${jarFile.get().asFile}")
    }
}

configure<LoomGradleExtensionAPI> {
    runs {
        // ./gradlew runRecorderClient: the recorder in the dev client (its classpath still has LayBalt QOL's libraries)
        maybeCreate("recorderClient").apply {
            inherit(named("client").get())
            displayName.set("Recorder Client")
            runDirectory.set(file("run/"))
            sourceSet.set(recorderSourceSet.name)
            generateRunConfig.set(false)
        }
    }
}

// ./gradlew runRecorderProduction: the finished jar in a real Fabric install with only Fabric API and Fabric Language Kotlin
dependencies {
    "productionRuntimeMods"("net.fabricmc.fabric-api:fabric-api:$fabricVersion") { isTransitive = false }
    "productionRuntimeMods"("net.fabricmc:fabric-language-kotlin:$fabricKotlinVersion") { isTransitive = false }
}

tasks.register<ClientProductionRunTask>("runRecorderProduction") {
    group = "fabric"
    description = "Runs the recorder-only jar in a production Fabric client with only Fabric API and Fabric Language Kotlin."
    mods.setFrom(jarRecorder, configurations.named("productionRuntimeMods"))
    runDir.set(layout.projectDirectory.dir("run-recorder-prod"))
    (findProperty("recorderArgs") as String?)?.split(' ')?.filter { it.isNotBlank() }?.let { programArgs.addAll(it) }
}
