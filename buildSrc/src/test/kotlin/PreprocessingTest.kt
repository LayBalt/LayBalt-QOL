import org.gradle.api.GradleException
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** NoammAddons 6541bcf6: tests for the variant comments, with LayBalt's `//$` marker and its RECORDER variant. */
class PreprocessingTest {
    private fun process(variant: String, source: String, stripLineComments: Boolean = false): List<String> {
        val file = File.createTempFile("preprocess", ".kt").also(File::deleteOnExit)
        file.writeText(source.trimIndent())
        return processDirectives(file, variantFlags(variant), stripLineComments)
    }

    @Test
    fun cheatBlock() {
        val source = """
            a
            //#if CHEAT
            cheat
            //#endif
            b
        """
        assertEquals(listOf("a", "cheat", "b"), process("cheat", source))
        assertEquals(listOf("a", "b"), process("legit", source))
        assertEquals(listOf("a", "cheat", "b"), process("recorder", source))
    }

    @Test
    fun legitBlock() {
        val source = """
            //#if LEGIT
            //${'$'}legit()
            //#endif
        """
        assertEquals(listOf("legit()"), process("legit", source))
        assertEquals(emptyList(), process("cheat", source))
        assertEquals(emptyList(), process("recorder", source))
    }

    @Test
    fun elseBlock() {
        val source = """
            //#if CHEAT
            cheat
            //#else
            legit
            //#endif
        """
        assertEquals(listOf("cheat"), process("cheat", source))
        assertEquals(listOf("legit"), process("legit", source))
    }

    @Test
    fun recorderBlocks() {
        val source = """
            //#if RECORDER
            //${'$'}recorder()
            //#else
            full()
            //#endif
            //#if !RECORDER
            notRecorder()
            //#endif
        """
        assertEquals(listOf("recorder()"), process("recorder", source))
        assertEquals(listOf("full()", "notRecorder()"), process("cheat", source))
        assertEquals(listOf("full()", "notRecorder()"), process("legit", source))
    }

    @Test
    fun nestedBlocks() {
        val source = """
            //#if CHEAT
            //#if LEGIT
            never
            //#else
            cheat
            //#endif
            //#endif
        """
        assertEquals(listOf("cheat"), process("cheat", source))
        assertEquals(emptyList(), process("legit", source))
    }

    @Test
    fun inlineLegit() {
        val source = """
            object Foo(
                //#if LEGIT name = "Legit",
                id = "foo"
            )
        """
        assertEquals(listOf("object Foo(", "    name = \"Legit\",", "    id = \"foo\"", ")"), process("legit", source))
        assertEquals(listOf("object Foo(", "    id = \"foo\"", ")"), process("cheat", source))
        assertEquals(listOf("object Foo(", "    id = \"foo\"", ")"), process("recorder", source))
    }

    @Test
    fun inlineLegitMarker() {
        assertEquals(listOf("import foo"), process("legit", "//#if LEGIT //${'$'}import foo"))
        assertEquals(emptyList(), process("cheat", "//#if LEGIT //${'$'}import foo"))
    }

    @Test
    fun inlineLegitInCheatBlock() {
        val source = """
            //#if CHEAT
            //#if LEGIT legit
            //#endif
        """
        assertEquals(emptyList(), process("legit", source))
        assertEquals(emptyList(), process("cheat", source))
    }

    @Test
    fun inlineFalseInMain() {
        // a condition that is false in the main sources may be inline: its line is a comment there as well
        assertEquals(listOf("x", "  legit()"), process("legit", "x\n  //#if !CHEAT legit()"))
        assertEquals(listOf("x"), process("cheat", "x\n  //#if !CHEAT legit()"))
        assertEquals(listOf("recorder()"), process("recorder", "//#if RECORDER recorder()"))
        assertEquals(emptyList(), process("cheat", "//#if RECORDER recorder()"))
    }

    @Test
    fun inlineTrueInMain() {
        // its code would be live in cheat but a comment in the main sources the dev client runs
        assertFailsWith<GradleException> { process("cheat", "//#if CHEAT cheat()") }
        assertFailsWith<GradleException> { process("legit", "    //#if CHEAT cheat()") }
        assertFailsWith<GradleException> { process("recorder", "//#if !RECORDER full()") }
        assertFailsWith<GradleException> { process("cheat", "//#if !LEGIT cheat()") }
        // also inside a block the variant drops
        assertFailsWith<GradleException> { process("legit", "//#if CHEAT\n//#if CHEAT cheat()\n//#endif") }
    }

    @Test
    fun unknownFlagsAndDirectives() {
        assertFailsWith<GradleException> { process("cheat", "//#if CHEET\n//#endif") }
        assertFailsWith<GradleException> { process("legit", "//#if !LEGT\n//#endif") }
        assertFailsWith<GradleException> { process("legit", "//#if LEGT legit()") }
        assertFailsWith<GradleException> { process("cheat", "//#ifdef CHEAT\n//#endif") }
        assertFailsWith<GradleException> { process("cheat", "//#elif LEGIT") }
    }

    @Test
    fun stripComments() {
        val source = """
            {
              // comment
              //${'$'}"key": "value",
              //#if LEGIT "legit": true
            }
        """
        assertEquals(listOf("{", "  \"key\": \"value\",", "  \"legit\": true", "}"), process("legit", source, stripLineComments = true))
        assertEquals(listOf("{", "  \"key\": \"value\",", "}"), process("cheat", source, stripLineComments = true))
    }

    @Test
    fun unbalanced() {
        assertFailsWith<GradleException> { process("cheat", "//#else") }
        assertFailsWith<GradleException> { process("cheat", "//#endif") }
        assertFailsWith<GradleException> { process("cheat", "//#if CHEAT") }
    }

    @Test
    fun placeholders() {
        val properties = mapOf("mod_id" to "lbqmod")
        assertEquals("id = lbqmod, @UNKNOWN@", replaceProperties("id = @MOD_ID@, @UNKNOWN@", properties))
    }

    /** Every source and preprocessed resource in the tree still goes through every variant (the inline rules reject none of them). */
    @Test
    fun projectSources() {
        val root = File("../src/main").canonicalFile
        val files = root.walkTopDown().filter { it.isFile && it.extension in setOf("kt", "java", "json5") }.toList()
        assertTrue(files.isNotEmpty(), "no sources under $root")
        for (variant in listOf("cheat", "legit", "recorder")) for (file in files) {
            processDirectives(file, variantFlags(variant), stripLineComments = file.extension == "json5")
        }
    }
}
