package dev.wildware.udea.assets.compiler.validate

import dev.wildware.udea.assets.ResPath
import dev.wildware.udea.assets.Shader
import dev.wildware.udea.assets.compiler.AssetKind
import dev.wildware.udea.assets.compiler.AssetScope
import dev.wildware.udea.assets.compiler.DeclaredAsset
import dev.wildware.udea.assets.compiler.ResFile
import dev.wildware.udea.assets.compiler.TestPaths
import dev.wildware.udea.assets.compiler.pack.BundleContent
import dev.wildware.udea.assets.compiler.pack.BundleWriter
import dev.wildware.udea.assets.compiler.pack.GraphPacker
import dev.wildware.udea.assets.compiler.shader.ShaderSources
import dev.wildware.udea.assets.compiler.toCatalog
import dev.wildware.udea.assets.pack.BundleReader
import dev.wildware.udea.assets.reference
import dev.wildware.udea.diagnostics.Severity
import dev.wildware.udea.diagnostics.UdeaDiagnostic
import dev.wildware.udea.diagnostics.UdeaRules
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A screen shader declared as an asset: `shader(name = "scanlines", file = "shaders/scanlines.frag")`.
 *
 * Every fixture is a real `.udea.kts` and a real `.frag` on disk, put through the real pass 2 and
 * the real pass 3, because the thing being proved is that the *pipeline* carries GLSL from a file
 * into a `.udeapak` - and a hand-built graph would carry whatever this file remembered to put in
 * it.
 *
 * The first test is the whole feature end to end: declared, validated, typed in the compile-time
 * catalog, packed, and read back out of the bundle with its text intact. The rest are the ways a
 * shader fails the build, one per test, each checked for the rule id, the span and - where the
 * defect is a name - the did-you-mean.
 */
class ShaderAssetTest {

    private val scanlines = """
        uniform float uStrength;

        vec4 udeaMain(vec2 uv) {
            vec3 colour = texture(uColor, uv).rgb;
            float line = mod(uv.y * uResolution.y, 2.0) < 1.0 ? 1.0 - uStrength : 1.0;
            return vec4(colour * line, 1.0);
        }
    """

    @Test
    fun `a declared shader validates, is published as a Shader, and packs with its GLSL inside`() {
        val context = ValidationFixture.context(
            "shader-ok",
            "shaders/scanlines.frag" to scanlines,
            "shaders/shaders.udea.kts" to """
                shader(name = "scanlines", file = "shaders/scanlines.frag")
            """,
        )

        val errors = ValidationFixture.report(context).diagnostics.filter { it.severity == Severity.Error }
        assertEquals(emptyList(), errors, "a shader whose file is a screen shader body is valid")

        // The compile-time catalog is what a `reference<Shader>("shaders/scanlines")` in a script
        // is checked against, and what gives the generated accessor its type parameter.
        val entry = assertNotNull(context.graph.toCatalog().catalog.resolve("shaders/scanlines"))
        assertEquals(Shader::class.qualifiedName, entry.kindFqn)

        // And this is the acceptance criterion the owner actually asked for: the GLSL reaches a
        // reader through the ordinary packed graph, with no loader between the file and the text.
        val packed = GraphPacker.pack(context.graph)
        assertFalse(packed.hasErrors, "packing reported ${packed.diagnostics}")
        BundleReader.open(BundleWriter.write(BundleContent(assets = packed.assets))).use { bundle ->
            val shader = bundle.registry[reference<Shader>("shaders/scanlines")]
            assertEquals(ResPath("shaders/scanlines.frag"), shader.file)
            assertEquals(scanlines.trimIndent(), shader.source)
            // Not a substring match: the whole file, byte for byte, is what a driver is handed.
            assertTrue("uStrength" in shader.source && "udeaMain" in shader.source, shader.source)
        }
    }

    /**
     * A `.frag` packs to the same bytes whatever its line endings are.
     *
     * The shader's text is packed into the graph, and the graph's sha256 is the asset graph hash
     * every `.udearep` records and refuses to replay without. Git for Windows checks a text file
     * out with CRLF endings unless a `.gitattributes` says otherwise, and a game in its own
     * repository has none of ours (issue #265). So the same commit, cloned on Windows, used to
     * pack a different hash from the one it packs on Linux, and every checked-in moba replay
     * refused to load there.
     *
     * The file is written as exact bytes, not as script text, because that is the only way a
     * carriage return reaches the disk here (see [ValidationFixture.context]). The lone-CR case is
     * the old Mac ending; the mixed one is a file edited on two machines.
     */
    @Test
    fun `a shader packs the same bytes whether its file ends lines with LF, CRLF or CR`() {
        val lines = scanlines.trimIndent().lines()
        val lf = lines.joinToString("") { it + "\n" }
        val endings = linkedMapOf(
            "lf" to lf,
            "crlf" to lines.joinToString("") { it + "\r\n" },
            "cr" to lines.joinToString("") { it + "\r" },
            "mixed" to lines.withIndex().joinToString("") { (i, line) -> line + listOf("\n", "\r\n", "\r")[i % 3] },
        )
        // The fixtures really differ on disk, or the test below compares one file with itself.
        assertEquals(endings.size, endings.values.map { it.encodeToByteArray().toList() }.toSet().size)

        val packs = endings.mapValues { (name, text) ->
            val context = ValidationFixture.context(
                "shader-endings-$name",
                "shaders/shaders.udea.kts" to """
                    shader(name = "scanlines", file = "shaders/scanlines.frag")
                """,
            ) { assets ->
                val frag = assets.resolve("shaders").resolve("scanlines.frag")
                frag.parent.toFile().mkdirs()
                frag.toFile().writeBytes(text.encodeToByteArray())
            }
            val packed = GraphPacker.pack(context.graph)
            assertFalse(packed.hasErrors, "$name: packing reported ${packed.diagnostics}")
            BundleWriter.write(BundleContent(assets = packed.assets))
        }

        val expected = BundleReader.open(packs.getValue("lf")).use { it.contentHash.toList() }
        for ((name, bytes) in packs) {
            BundleReader.open(bytes).use { bundle ->
                val source = bundle.registry[reference<Shader>("shaders/scanlines")].source
                assertEquals(expected, bundle.contentHash.toList(), "$name: the asset graph hash moved")
                assertEquals(lf, source, "$name: the packed GLSL is not the file's text with LF endings")
            }
        }
    }

    @Test
    fun `a misspelled shader file fails the build with a did-you-mean over the frag files present`() {
        val context = ValidationFixture.context(
            "shader-missing",
            "shaders/scanlines.frag" to scanlines,
            "shaders/shaders.udea.kts" to """
                shader(name = "scanlines", file = "shaders/scanlies.frag")
            """,
        )

        val diagnostic = errorFor(context, UdeaRules.SHADER_SOURCE.id)
        assertEquals("shaders/scanlines", diagnostic.assetId)
        assertTrue(
            "Did you mean 'shaders/scanlines.frag'?" in diagnostic.message,
            "a missing shader must suggest the file that is there: ${diagnostic.message}",
        )
        val span = assertNotNull(diagnostic.span, "the diagnostic must name the declaring line")
        assertTrue(span.path.endsWith("shaders/shaders.udea.kts"), "span points elsewhere: $span")
        assertTrue(span.startLine >= 1, "line 0 means the location was lost: $diagnostic")
    }

    @Test
    fun `a shader that states its own version fails the build, with the reason`() {
        val context = ValidationFixture.context(
            "shader-version",
            "shaders/versioned.frag" to """
                #version 330 core
                vec4 udeaMain(vec2 uv) { return texture(uColor, uv); }
            """,
            "shaders/shaders.udea.kts" to """
                shader(name = "versioned", file = "shaders/versioned.frag")
            """,
        )

        val diagnostic = errorFor(context, UdeaRules.SHADER_SOURCE.id)
        assertTrue("#version" in diagnostic.message, diagnostic.message)
        assertTrue(
            "OpenGL ES" in diagnostic.message,
            "the message must say why the engine owns the line: ${diagnostic.message}",
        )
        assertTrue(
            "line 1" in diagnostic.message,
            "the message must say which line to delete: ${diagnostic.message}",
        )
    }

    /**
     * A `#version` inside a comment is refused too, and the message says so rather than claiming
     * the file "states its own".
     *
     * This is not a hypothetical. The first `.frag` this engine shipped carried a comment saying
     * *"No `#version` line - the engine writes it"*, and the build refused it: the check is
     * deliberately blind to comments (`Shader.VERSION_PRAGMA` gives the reason), so the comment
     * tripped the rule it was describing. The refusal is the behaviour we want - `udea-render`
     * refuses the same body at registration, so a comment-aware build would only move the failure
     * to the first draw - but the wording was false about that file, and a diagnostic an author
     * can read as false is one they go looking for a compiler bug behind.
     *
     * So what is pinned here is the *pair*: the refusal, and a message that survives being read
     * by the author it is about.
     */
    @Test
    fun `a version pragma inside a comment is refused, and the message admits comments are read`() {
        val context = ValidationFixture.context(
            "shader-version-comment",
            "shaders/commented.frag" to """
                // A body with no version pragma of its own.
                //
                // #version 330 core
                vec4 udeaMain(vec2 uv) { return texture(uColor, uv); }
            """,
            "shaders/shaders.udea.kts" to """
                shader(name = "commented", file = "shaders/commented.frag")
            """,
        )

        val diagnostic = errorFor(context, UdeaRules.SHADER_SOURCE.id)
        assertTrue(
            "line 3" in diagnostic.message,
            "the message must name the line the pragma is on, which is the comment: " +
                diagnostic.message,
        )
        assertTrue(
            "does not read comments" in diagnostic.message,
            "an author whose *comment* tripped this must be told that is what happened, or the " +
                "message reads as simply wrong about their file: ${diagnostic.message}",
        )
    }

    /**
     * A file whose lines end in a lone CR still has its `#version` named at the right line.
     *
     * The line is counted in newlines, so a file with no `\n` in it at all put every pragma on
     * line 1 until its endings were made `\n` as it was read.
     */
    @Test
    fun `a version pragma in a file with lone-CR endings is named at its real line`() {
        val context = ValidationFixture.context(
            "shader-version-cr",
            "shaders/shaders.udea.kts" to """
                shader(name = "old_mac", file = "shaders/old_mac.frag")
            """,
        ) { assets ->
            val frag = assets.resolve("shaders").resolve("old_mac.frag")
            frag.parent.toFile().mkdirs()
            val text = "// written on a machine that ends lines with CR\r\r#version 330 core\r" +
                "vec4 udeaMain(vec2 uv) { return texture(uColor, uv); }\r"
            frag.toFile().writeBytes(text.encodeToByteArray())
        }

        val diagnostic = errorFor(context, UdeaRules.SHADER_SOURCE.id)
        assertTrue("line 3" in diagnostic.message, diagnostic.message)
    }

    @Test
    fun `a shader with no udeaMain fails the build, naming the function it must define`() {
        val context = ValidationFixture.context(
            "shader-no-entry",
            "shaders/headless.frag" to """
                vec4 notTheEntryPoint(vec2 uv) { return texture(uColor, uv); }
            """,
            "shaders/shaders.udea.kts" to """
                shader(name = "headless", file = "shaders/headless.frag")
            """,
        )

        val diagnostic = errorFor(context, UdeaRules.SHADER_SOURCE.id)
        assertTrue("udeaMain" in diagnostic.message, diagnostic.message)
    }

    @Test
    fun `an empty shader file fails the build`() {
        val context = ValidationFixture.context(
            "shader-empty",
            "shaders/blank.frag" to "\n   \n",
            "shaders/shaders.udea.kts" to """
                shader(name = "blank", file = "shaders/blank.frag")
            """,
        )

        val diagnostic = errorFor(context, UdeaRules.SHADER_SOURCE.id)
        assertTrue("is empty" in diagnostic.message, diagnostic.message)
    }

    @Test
    fun `a shader that names something other than a frag fails the build`() {
        val context = ValidationFixture.context(
            "shader-wrong-extension",
            "shaders/scanlines.txt" to scanlines,
            "shaders/shaders.udea.kts" to """
                shader(name = "scanlines", file = "shaders/scanlines.txt")
            """,
        )

        val diagnostic = errorFor(context, UdeaRules.SHADER_SOURCE.id)
        assertTrue("not a .frag file" in diagnostic.message, diagnostic.message)
    }

    /**
     * The one branch no fixture on disk can produce, made reachable on purpose.
     *
     * `ShaderSources.fill` sets `source` on every shader it sees, so a declaration without the
     * field can only come from something that assembled a graph without running pass 2's fill.
     * Skipping it silently would make every check above pass for the wrong reason on exactly the
     * graph that has no text in it, which is the failure this whole ticket is about.
     */
    @Test
    fun `a shader declaration the compiler never filled is reported as a build-tool defect`() {
        val context = ValidationContext(
            declared = listOf(
                DeclaredAsset(
                    kind = ShaderSources.KIND,
                    kindFqn = Shader::class.qualifiedName,
                    id = "shaders/unfilled",
                    fields = mapOf(ShaderSources.FILE_FIELD to ResFile.of("shaders/unfilled.frag")),
                ),
            ),
            repoRoot = TestPaths.repoRoot,
            assetRoot = unfilledRoot(),
            sources = emptyList(),
        )

        val found = ShaderFileValidator.validate(context)
        val diagnostic = assertNotNull(found.singleOrNull(), "exactly one diagnostic: $found")
        assertEquals(UdeaRules.SHADER_SOURCE.id, diagnostic.ruleId)
        assertTrue("defect in the build tool" in diagnostic.message, diagnostic.message)
    }

    /** The declaration function really is one of the words the transpiler knows how to qualify. */
    @Test
    fun `shader is a member of the receiver and has a runtime type behind it`() {
        assertTrue(ShaderSources.KIND in AssetScope.MEMBER_NAMES)
        assertEquals(
            Shader::class.qualifiedName,
            AssetKind.of<Shader>().fqn,
            "the DSL stamps the kind off the KClass, so this is the one that reaches the catalog",
        )
    }

    /**
     * An asset root holding the file the unfilled declaration names, so the test above fails on
     * the missing `source` field rather than on a missing file - the check before it in order.
     */
    private fun unfilledRoot(): java.nio.file.Path {
        val root = TestPaths.scratch("validate/shader-unfilled")
        val file = root.resolve("shaders").resolve("unfilled.frag")
        file.parent.toFile().mkdirs()
        file.toFile().writeText("vec4 udeaMain(vec2 uv) { return texture(uColor, uv); }\n")
        return root
    }

    private fun errorFor(context: ValidationContext, ruleId: String): UdeaDiagnostic {
        val report = ValidationFixture.report(context)
        return assertNotNull(
            report.diagnostics.firstOrNull { it.ruleId == ruleId && it.severity == Severity.Error },
            "no $ruleId error in ${report.diagnostics.map { "${it.ruleId}: ${it.message}" }}",
        )
    }
}
