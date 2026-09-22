package dev.wildware.udea.assets.compiler.gen

import dev.wildware.udea.assets.compiler.AssetCompilerRules
import dev.wildware.udea.assets.compiler.TestPaths
import dev.wildware.udea.assets.compiler.pipeline.DeclarationsJsonReader
import dev.wildware.udea.assets.compiler.scan.Declaration
import dev.wildware.udea.assets.compiler.scan.DeclarationsJson
import dev.wildware.udea.assets.compiler.scan.GoldenResource
import dev.wildware.udea.assets.compiler.scan.UdeaDeclarationScanner
import dev.wildware.udea.diagnostics.SourceSpan
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `model(name = "fox", file = "models/fox/Fox.glb")` becomes `Fox.Clips.Survey`, `.Walk` and
 * `.Run` at build time, each carrying its length in ticks (issue #241).
 *
 * The path it takes: pass 1 records the `file` literal beside the declaration, the scan document
 * carries it to the accessors task, [ModelFileSource] reads the file's clips, and
 * [AccessorGenerator] emits one object per animated model. Each step is checked here, and the
 * emitted text against a committed golden.
 */
class ModelClipAccessorsTest {

    private val fox: Path = TestPaths.exampleAssets.resolve("models/fox/Fox.glb")

    /** An asset tree holding the Fox and a script declaring it, with [script] as that script. */
    private fun tree(name: String, script: String = FOX_SCRIPT, withFox: Boolean = true): Path {
        val root = TestPaths.scratch("model-clips-$name")
        root.resolve("models").createDirectories()
        root.resolve("models/fox.udea.kts").writeText(script)
        if (withFox) {
            root.resolve("models/fox").createDirectories()
            fox.copyTo(root.resolve("models/fox/Fox.glb"))
        }
        return root
    }

    private fun scan(root: Path): List<Declaration> =
        UdeaDeclarationScanner(TestPaths.repoRoot, root).use { it.scanTree() }.declarations

    // ---- pass 1 and the scan document ---------------------------------------------------------

    @Test
    fun `pass 1 records the literal file a model names`() {
        val declaration = scan(tree("scan")).single()

        assertEquals("models/fox", declaration.id)
        assertEquals("models/fox/Fox.glb", declaration.fileArgument)
    }

    @Test
    fun `the file survives the scan document, and a declaration with none stays without one`() {
        val root = tree("json")
        root.resolve("models/cube.udea.kts").writeText("gameConfig(tickRate = 60)\n")
        val report = UdeaDeclarationScanner(TestPaths.repoRoot, root).use { it.scanTree() }

        val text = DeclarationsJson.write(report)
        val read = DeclarationsJsonReader.parse(text)

        assertEquals(report.declarations.sortedBy { it.id }, read.sortedBy { it.id })
        assertNull(read.single { it.kind == "gameConfig" }.fileArgument)
        assertEquals(1, Regex("fileArgument").findAll(text).count(), "only the model carries one:\n$text")
    }

    // ---- reading the clips --------------------------------------------------------------------

    @Test
    fun `the clips of every model are read from the file its declaration names`() {
        val root = tree("read")
        val read = ModelFileSource.read(root, scan(root))

        assertEquals(emptyList(), read.diagnostics)
        assertEquals(listOf("Survey", "Walk", "Run"), read.clips.getValue("models/fox").map { it.name })
    }

    @Test
    fun `a model whose file is missing fails with a did-you-mean`() {
        val root = tree("missing", script = """model(name = "fox", file = "models/fox/Fx.glb")""" + "\n")
        val diagnostic = ModelFileSource.read(root, scan(root)).diagnostics.single()

        assertEquals(AssetCompilerRules.MODEL_CLIPS.id, diagnostic.ruleId)
        assertEquals("models/fox", diagnostic.assetId)
        assertTrue("Did you mean 'models/fox/Fox.glb'?" in diagnostic.message, diagnostic.message)
        assertEquals("models/fox.udea.kts", assertNotNull(diagnostic.span).path.substringAfter("model-clips-missing/"))
    }

    @Test
    fun `a model whose file is not a literal fails, because its clips cannot be known`() {
        val root = tree(
            "computed",
            script = "val where = \"models/fox\"\nmodel(name = \"fox\", file = where.plus(\"/Fox.glb\"))\n",
        )
        val diagnostic = ModelFileSource.read(root, scan(root)).diagnostics.single()

        assertEquals(AssetCompilerRules.MODEL_CLIPS.id, diagnostic.ruleId)
        assertTrue("literal" in diagnostic.message, diagnostic.message)
    }

    @Test
    fun `a model whose file is not glTF fails with the reason`() {
        val root = tree("broken", withFox = false)
        root.resolve("models/fox").createDirectories()
        root.resolve("models/fox/Fox.glb").writeText("not a model")
        val diagnostic = ModelFileSource.read(root, scan(root)).diagnostics.single()

        assertTrue("not a binary glTF 2.0 file" in diagnostic.message, diagnostic.message)
    }

    // ---- an .fbx (issue #244) -----------------------------------------------------------------

    /**
     * An asset tree holding the FBX fixture at `models/bender/`, with the `.glb` committed beside
     * it unless [withGlb] is false, declared by a script.
     */
    private fun fbxTree(name: String, withGlb: Boolean = true): Path {
        val root = TestPaths.scratch("model-clips-$name")
        val folder = root.resolve("models/bender").createDirectories()
        val fixture = TestPaths.repoRoot.resolve("udea-assets-compiler/src/test/resources/fbx/bender")
        fixture.resolve("Bender.fbx").copyTo(folder.resolve("Bender.fbx"))
        fixture.resolve("checker.png").copyTo(folder.resolve("checker.png"))
        if (withGlb) fixture.resolve("Bender.glb").copyTo(folder.resolve("Bender.glb"))
        root.resolve("models/bender.udea.kts").writeText("model(name = \"bender\", file = \"models/bender/Bender.fbx\")\n")
        return root
    }

    @Test
    fun `an fbx model's clips are read from the glb committed beside it, and generate typed clips`() {
        val root = fbxTree("fbx")
        val declarations = scan(root)
        val read = ModelFileSource.read(root, declarations)

        assertEquals(emptyList(), read.diagnostics)
        val text = AccessorGenerator.generate(declarations, read.clips)
            .single { it.path == "dev/wildware/udea/generated/Bender.kt" }.text
            .replace(Regex("\\s+"), " ")
        assertTrue("public val Bend: AnimationClip = AnimationClip(index = 0, name = \"Bend\", length = Ticks(60L))" in text, text)
        assertTrue("public val Twist: AnimationClip = AnimationClip(index = 1, name = \"Twist\", length = Ticks(38L))" in text, text)
    }

    @Test
    fun `an fbx with no committed glb fails the accessors pass with the conversion rule, not this one`() {
        val root = fbxTree("fbx-no-glb", withGlb = false)
        val diagnostic = ModelFileSource.read(root, scan(root)).diagnostics.single()

        assertEquals("UDEA0039", diagnostic.ruleId)
        assertEquals("models/bender", diagnostic.assetId)
        assertTrue("`models/bender/Bender.glb`" in diagnostic.message, diagnostic.message)
    }

    // ---- the generated source -----------------------------------------------------------------

    /**
     * The golden. Everything a game compiles against is in it: the object's name from the id,
     * each clip as a property named from the file's own name with the index and length it
     * carries, and each named node with the place it sits at rest (issue #260). On a difference
     * the actual text is written beside the build output, so a deliberate change is a copy
     * rather than a hand transcription.
     */
    @Test
    fun `the fox generates the golden Fox object`() {
        val root = tree("golden")
        val declarations = scan(root)
        val read = ModelFileSource.read(root, declarations)
        val generated = AccessorGenerator.generate(declarations, read.clips, read.nodes)
        val actual = assertNotNull(generated.singleOrNull { it.path == "dev/wildware/udea/generated/Fox.kt" }).text

        val golden = GoldenResource.read(FOX_GOLDEN)
        if (actual != golden) {
            val rejected = TestPaths.repoRoot.resolve("udea-assets-compiler/build/tmp/Fox.kt.actual.txt")
            rejected.parent.createDirectories()
            rejected.writeText(actual)
            assertEquals(golden, actual, "Fox.kt differs from $FOX_GOLDEN; actual written to $rejected")
        }
    }

    @Test
    fun `clip names become identifiers, and a clash or a missing name is numbered`() {
        val declaration = Declaration("model", "props/door_frame", "door_frame", SPAN, "props/door.glb")
        val clips = listOf(
            GltfClip(0, "open-slowly", 30L),
            GltfClip(1, null, 10L),
            GltfClip(2, "Open Slowly", 12L),
            GltfClip(3, "2hand|swing", 20L),
        )
        val text = AccessorGenerator.generate(listOf(declaration), mapOf("props/door_frame" to clips))
            .single { it.path.endsWith("/DoorFrame.kt") }.text
            // KotlinPoet wraps a long initializer after its `=`; the words are what is asserted.
            .replace(Regex("\\s+"), " ")

        assertTrue("public object DoorFrame" in text, text)
        assertTrue("public val OpenSlowly: AnimationClip = AnimationClip(index = 0, name = \"open-slowly\"" in text, text)
        assertTrue("public val Clip1: AnimationClip = AnimationClip(index = 1, name = \"Clip1\"" in text, text)
        assertTrue("public val OpenSlowly2: AnimationClip = AnimationClip(index = 2" in text, text)
        assertTrue("public val Clip2handSwing: AnimationClip = AnimationClip(index = 3" in text, text)
    }

    @Test
    fun `a model with neither clips nor nodes generates no object, and adds nothing else`() {
        val declaration = Declaration("model", "props/crate", "crate", SPAN, "props/crate.glb")
        val with = AccessorGenerator.generate(
            listOf(declaration),
            mapOf("props/crate" to emptyList()),
            mapOf("props/crate" to emptyList()),
        )

        assertEquals(AccessorGenerator.generate(listOf(declaration)), with)
    }

    // ---- the nodes a part is mounted on (issue #260) -------------------------------------------

    @Test
    fun `a model with nodes and no clips still gets an object, with its nodes and no Clips`() {
        val declaration = Declaration("model", "units/chassis", "chassis", SPAN, "units/chassis.glb")
        val node = GltfNode(
            index = 4, name = "socket_roof",
            x = 0f, y = 0f, z = 1.25f,
            qx = 0f, qy = 0f, qz = 0f, qw = 1f,
            scaleX = 1f, scaleY = 1f, scaleZ = 1f,
        )
        val text = AccessorGenerator.generate(
            listOf(declaration),
            clips = emptyMap(),
            nodes = mapOf("units/chassis" to listOf(node)),
        ).single { it.path.endsWith("/Chassis.kt") }.text
            // KotlinPoet wraps a long initializer after its `=`; the words are what is asserted.
            .replace(Regex("\\s+"), " ")

        assertTrue("public object Nodes" in text, text)
        assertTrue("public object Clips" !in text, "a file with no animations gets no Clips: $text")
        assertTrue(
            "public val socket_roof: ModelNode = ModelNode(index = 4, name = \"socket_roof\", x = 0.0f, " +
                "y = 0.0f, z = 1.25f, qx = 0.0f, qy = 0.0f, qz = 0.0f, qw = 1.0f, scaleX = 1.0f, " +
                "scaleY = 1.0f, scaleZ = 1.0f)" in text,
            text,
        )
        assertTrue("public val all: List<ModelNode> = listOf(socket_roof)" in text, text)
    }

    @Test
    fun `a node name that cannot be an identifier is made into one, and a clash is numbered`() {
        val declaration = Declaration("model", "units/chassis", "chassis", SPAN, "units/chassis.glb")
        fun node(index: Int, name: String) = GltfNode(index, name, 0f, 0f, 0f, 0f, 0f, 0f, 1f, 1f, 1f, 1f)
        val text = AccessorGenerator.generate(
            listOf(declaration),
            clips = emptyMap(),
            nodes = mapOf(
                "units/chassis" to listOf(
                    node(0, "socket roof"),
                    node(1, "socket_roof"),
                    node(2, "2_wheel"),
                    node(3, "all"),
                ),
            ),
        ).single { it.path.endsWith("/Chassis.kt") }.text.replace(Regex("\\s+"), " ")

        assertTrue("public val socket_roof: ModelNode = ModelNode(index = 0, name = \"socket roof\"" in text, text)
        assertTrue("public val socket_roof2: ModelNode = ModelNode(index = 1" in text, text)
        assertTrue("public val _2_wheel: ModelNode = ModelNode(index = 2" in text, text)
        assertTrue("public val all2: ModelNode = ModelNode(index = 3" in text, text)
        assertTrue("public val all: List<ModelNode> = listOf(" in text, "the list keeps the name `all`: $text")
    }

    @Test
    fun `a node's extras are written into its accessor, each as the value the file holds`() {
        val declaration = Declaration("model", "units/module", "module", SPAN, "units/module.glb")
        val node = GltfNode(
            0, "module", 0f, 0f, 0f, 0f, 0f, 0f, 1f, 1f, 1f, 1f,
            extras = mapOf(
                "armoured" to true,
                "fitting.slots" to 2,
                "mass" to 12.5f,
                "module_size" to "small \"mk2\"",
                "offset" to listOf(0f, 0.5f),
            ),
        )
        val text = AccessorGenerator.generate(
            listOf(declaration),
            clips = emptyMap(),
            nodes = mapOf("units/module" to listOf(node)),
        ).single { it.path.endsWith("/Module.kt") }.text.replace(Regex("\\s+"), " ")

        assertTrue(
            "scaleZ = 1.0f, extras = ModelExtras(mapOf(" +
                "\"armoured\" to AssetValue.BoolValue(true), " +
                "\"fitting.slots\" to AssetValue.IntValue(2), " +
                "\"mass\" to AssetValue.FloatValue(12.5f), " +
                "\"module_size\" to AssetValue.TextValue(\"small \\\"mk2\\\"\"), " +
                "\"offset\" to AssetValue.ListValue(listOf(AssetValue.FloatValue(0.0f), AssetValue.FloatValue(0.5f)))" +
                ")))" in text,
            text,
        )
    }

    @Test
    fun `a node with no extras is written exactly as before`() {
        val declaration = Declaration("model", "units/chassis", "chassis", SPAN, "units/chassis.glb")
        val node = GltfNode(0, "socket_roof", 0f, 0f, 0f, 0f, 0f, 0f, 1f, 1f, 1f, 1f)
        val text = AccessorGenerator.generate(
            listOf(declaration),
            clips = emptyMap(),
            nodes = mapOf("units/chassis" to listOf(node)),
        ).single { it.path.endsWith("/Chassis.kt") }.text

        assertTrue("extras" !in text && "ModelExtras" !in text, text)
    }

    private companion object {
        const val FOX_SCRIPT = "model(name = \"fox\", file = \"models/fox/Fox.glb\")\n"
        const val FOX_GOLDEN = "/golden/Fox.kt.txt"
        val SPAN = SourceSpan("props/props.udea.kts", 1, 1, 1, 6)
    }
}
