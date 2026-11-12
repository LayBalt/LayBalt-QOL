import org.gradle.api.*
import org.gradle.api.tasks.TaskProvider
import java.io.File
import java.util.*

private val directiveRegex = "^//\\s*".toRegex()
private val placeholderRegex = Regex("@(\\w+)@")
private const val marker = "//$"

private data class StackFrame(val outerInclude: Boolean, val branchWasTrue: Boolean)

private fun Project.loadGradleProperties(): Map<String, String> {
    val props = Properties().apply { file("gradle.properties").inputStream().use(::load) }
    return props.mapKeys { it.key as String }.mapValues { it.value as String }
}

internal fun replaceProperties(content: String, properties: Map<String, String>): String {
    return placeholderRegex.replace(content) { match ->
        properties[match.groupValues[1].lowercase()] ?: match.value
    }
}

/** The flags a variant is preprocessed with: `//#if FLAG` keeps its block when FLAG is set, `//#if !FLAG` when it is not. */
internal fun variantFlags(variantName: String): Set<String> = when (variantName) {
    "cheat" -> setOf("CHEAT")
    "legit" -> setOf("LEGIT")
    // the recorder-only jar is built from cheat sources (the recorder is cheat code) with its own guards
    "recorder" -> setOf("CHEAT", "RECORDER")
    else -> throw GradleException("Unknown variant $variantName")
}

/** Every flag some variant sets: an `//#if` on any other name is a typo that would silently drop (or, negated, keep) its code everywhere. */
private val knownFlags = listOf("cheat", "legit", "recorder").flatMap(::variantFlags).toSet()

/**
 * The flags the main source set is compiled with. It compiles the raw sources (only `@PROPERTY@`s are replaced), so a
 * `//#if CHEAT` block's code is live there and an inline directive's line is a comment there.
 */
private val mainFlags = variantFlags("cheat")

private val ifRegex = Regex("""^#if\s+(!?)([A-Z][A-Z0-9_]*)$""")
private val anyDirectiveRegex = Regex("""^#(?:if|else|endif|elif|ifdef|ifndef)\b""")

// NoammAddons 7a6f0254 + ba1595b6: one-line `//#if LEGIT <code>` keeps <code> (indented as the directive) in the variants
// with that flag and drops the line elsewhere; `//#if LEGIT //$<code>` works too. Only a condition that is false in the
// main sources may be inline (LEGIT, !CHEAT, RECORDER): an inline `//#if CHEAT <code>` would be a comment in main but
// live in cheat, so the dev client would silently run without it. Such a line fails the build instead.
private val inlineIfRegex = Regex("""^(\s*)//\s*#if\s+(!?)([A-Z][A-Z0-9_]*)\s+(\S.*)$""")

/** Whether `#if [negated][flag]` is true with [flags] set ([negated] is "!" or ""). */
private fun conditionHolds(negated: String, flag: String, flags: Set<String>) = (flag in flags) != negated.isNotEmpty()

private fun preprocessorError(vararg lines: String): Nothing = throw GradleException(
    listOf("--------------------------------", *lines, "--------------------------------").joinToString(separator = "\n")
)

internal fun processDirectives(sourceFile: File, flags: Set<String>, stripLineComments: Boolean = false): List<String> {
    val stack = mutableListOf<StackFrame>()
    val output = mutableListOf<String>()
    var include = true

    sourceFile.readLines().forEachIndexed { index, line ->
        val lineNumber = index + 1
        val directive = line.trim().replaceFirst(directiveRegex, "")
        val condition = ifRegex.matchEntire(directive)
        val inline = if (condition == null) inlineIfRegex.matchEntire(line) else null

        // checked even inside a dropped block, so a bad directive fails every variant and not only the one that keeps it
        (condition?.groupValues?.get(2) ?: inline?.groupValues?.get(3))?.let { flag ->
            if (flag !in knownFlags) preprocessorError(
                "Unknown preprocessor flag $flag in #if (${sourceFile.name}:L$lineNumber).",
                "Known flags: ${knownFlags.joinToString()}."
            )
        }
        if (inline != null) {
            val (_, negated, flag) = inline.destructured
            if (conditionHolds(negated, flag, mainFlags)) preprocessorError(
                "Inline #if $negated$flag is not allowed (${sourceFile.name}:L$lineNumber).",
                "Use a #if $negated$flag ... #endif block instead to keep the main and cheat sources in sync."
            )
        }

        when {
            condition != null -> {
                val (negated, flag) = condition.destructured
                val value = conditionHolds(negated, flag, flags)
                stack += StackFrame(outerInclude = include, branchWasTrue = value)
                include = value && include
            }

            inline != null -> if (include) {
                val (indent, negated, flag, code) = inline.destructured
                if (conditionHolds(negated, flag, flags)) emitLine(indent + code, output, stripLineComments)
            }

            directive == "#else" -> {
                if (stack.isEmpty()) throw GradleException("Unmatched #else in $sourceFile at line $lineNumber")
                val frame = stack.last()
                include = ! frame.branchWasTrue && frame.outerInclude
            }

            directive == "#endif" -> {
                if (stack.isEmpty()) throw GradleException("Unmatched #endif in $sourceFile at line $lineNumber")
                include = stack.removeAt(stack.lastIndex).outerInclude
            }

            // a misspelt directive would silently keep or drop code (and unbalance the blocks around it)
            line.trim().startsWith("//") && anyDirectiveRegex.containsMatchIn(directive) ->
                throw GradleException("Unknown preprocessor directive \"$directive\" in $sourceFile at line $lineNumber")

            include -> emitLine(line, output, stripLineComments)
        }
    }

    if (stack.isNotEmpty()) throw GradleException("Unclosed #if in $sourceFile")
    return output
}

/** Adds a kept [line] to [output] with its first `//$` marker removed (or drops it when it is a plain comment and [stripLineComments]). */
private fun emitLine(line: String, output: MutableList<String>, stripLineComments: Boolean) {
    val trimmed = line.trim()
    if (stripLineComments && trimmed.startsWith("//") && ! trimmed.startsWith(marker)) return

    val markerIndex = line.indexOf(marker)
    output += if (markerIndex >= 0) line.removeRange(markerIndex, markerIndex + marker.length) else line
}

private fun Project.preprocessSources(variantName: String, outRoot: File) {
    val properties = loadGradleProperties()

    fun processTree(inputRoot: File, outputRoot: File) {
        if (! inputRoot.exists()) return

        fileTree(inputRoot).matching { include("**/*.kt", "**/*.java") }.files.forEach { sourceFile ->
            val outFile = File(outputRoot, inputRoot.toPath().relativize(sourceFile.toPath()).toString())
            outFile.parentFile.mkdirs()
            val content = processDirectives(sourceFile, variantFlags(variantName)).joinToString(System.lineSeparator())
            outFile.writeText(replaceProperties(content, properties))
        }
    }

    processTree(file("src/main/kotlin"), File(outRoot, "kotlin"))
    processTree(file("src/main/java"), File(outRoot, "java"))
}

private fun Project.replaceSourceProperties(outRoot: File) {
    val properties = loadGradleProperties()

    fun processTree(inputRoot: File, outputRoot: File) {
        if (! inputRoot.exists()) return

        fileTree(inputRoot).matching { include("**/*.kt", "**/*.java") }.files.forEach { sourceFile ->
            val outFile = File(outputRoot, inputRoot.toPath().relativize(sourceFile.toPath()).toString())
            outFile.parentFile.mkdirs()
            outFile.writeText(replaceProperties(sourceFile.readText(), properties))
        }
    }

    processTree(file("src/main/kotlin"), File(outRoot, "kotlin"))
    processTree(file("src/main/java"), File(outRoot, "java"))
}

private fun Project.preprocessResources(variantName: String, outRoot: File) {
    val resourcesIn = file("src/main/resources")
    if (! resourcesIn.exists()) return

    val properties = loadGradleProperties()
    val modId = findProperty("mod_id")

    fun preprocessFile(srcName: String, outName: String, expandVersion: Boolean) {
        val srcFile = File(resourcesIn, srcName)
        if (! srcFile.exists()) return

        val outFile = File(outRoot, outName).also { it.parentFile.mkdirs() }
        var content = processDirectives(srcFile, variantFlags(variantName), stripLineComments = true).joinToString(System.lineSeparator())
        if (expandVersion) content = content.replace("\${version}", project.version.toString())
        content = replaceProperties(content, properties)
        outFile.writeText(content)
    }

    preprocessFile("fabric.mod.json5", "fabric.mod.json", expandVersion = true)
    preprocessFile("$modId.mixins.json5", "$modId.mixins.json", expandVersion = false)
}

fun Project.registerPreprocessTask(variantName: String): TaskProvider<Task> {
    val capitalised = variantName.replaceFirstChar { it.uppercase() }
    return tasks.register("preprocess$capitalised") {
        val outRoot = layout.buildDirectory.dir("preprocessed/$variantName")
        val resourcesOut = layout.buildDirectory.dir("preprocessed/$variantName/resources")

        inputs.dir("src/main/kotlin")
        inputs.dir("src/main/java")
        inputs.file("src/main/resources/fabric.mod.json5")
        inputs.file("src/main/resources/${findProperty("mod_id")}.mixins.json5")
        inputs.file("gradle.properties")
        inputs.property("version", project.version.toString())
        outputs.dir(outRoot)
        outputs.dir(resourcesOut)

        doFirst { delete(outRoot.get().asFile) }
        doLast {
            preprocessSources(variantName, outRoot.get().asFile)
            preprocessResources(variantName, resourcesOut.get().asFile)
        }
    }
}

fun Project.registerPreprocessMainTask(): TaskProvider<Task> {
    return tasks.register("preprocessMain") {
        val outRoot = layout.buildDirectory.dir("preprocessed/main")

        inputs.dir("src/main/kotlin")
        inputs.dir("src/main/java")
        inputs.file("gradle.properties")
        inputs.property("version", project.version.toString())
        outputs.dir(outRoot)

        doFirst { delete(outRoot.get().asFile) }
        doLast {
            replaceSourceProperties(outRoot.get().asFile)
        }
    }
}

/**
 * `preprocessRecorder`: preprocesses only [sharedSources] (paths relative to `src/main`, such as
 * `kotlin/com/laybalt/event/EventBus.kt`) with the recorder flags into `build/preprocessed/recorder/{kotlin,java}`.
 * Nothing else from `src/main` reaches the recorder-only jar, so a shared file that needs an unlisted one fails to compile.
 */
fun Project.registerRecorderPreprocessTask(sharedSources: List<String>): TaskProvider<Task> {
    return tasks.register("preprocessRecorder") {
        val outRoot = layout.buildDirectory.dir("preprocessed/recorder")
        val sources = sharedSources.map { file("src/main/$it") }

        inputs.files(sources)
        inputs.file("gradle.properties")
        outputs.dir(outRoot)

        doFirst { delete(outRoot.get().asFile) }
        doLast {
            val properties = loadGradleProperties()
            val flags = variantFlags("recorder")
            sharedSources.zip(sources).forEach { (path, sourceFile) ->
                if (! sourceFile.isFile) throw GradleException("Recorder source src/main/$path does not exist")
                if (! path.startsWith("kotlin/") && ! path.startsWith("java/")) throw GradleException("Recorder source $path is not under kotlin/ or java/")
                val outFile = File(outRoot.get().asFile, path).also { it.parentFile.mkdirs() }
                val content = processDirectives(sourceFile, flags).joinToString(System.lineSeparator())
                outFile.writeText(replaceProperties(content, properties))
            }
        }
    }
}