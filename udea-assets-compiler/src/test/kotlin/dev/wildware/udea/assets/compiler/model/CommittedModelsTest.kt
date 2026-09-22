package dev.wildware.udea.assets.compiler.model

import dev.wildware.udea.assets.compiler.ResFile
import dev.wildware.udea.assets.compiler.TestPaths
import dev.wildware.udea.assets.compiler.gen.GltfClips
import dev.wildware.udea.assets.compiler.gen.ModelFileSource
import dev.wildware.udea.assets.compiler.pipeline.AssetPipeline
import dev.wildware.udea.assets.compiler.scan.Declaration
import dev.wildware.udea.assets.compiler.scan.UdeaDeclarationScanner
import dev.wildware.udea.assets.compiler.validate.ValidationFixture
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteExisting
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * An `.fbx` model is published as the `.glb` committed beside it, and the build never runs Assimp
 * (the follow-up to issue #244).
 *
 * LWJGL's Assimp is two different native builds on Linux and on Windows, and the two convert
 * one `.fbx` to floats that differ in their last bits - enough to move the asset graph's hash,
 * since #271 packs node transforms. So Assimp runs once, on purpose, through
 * [CommittedModels.WRITE_TASK], and every platform reads the same committed bytes.
 *
 * The committed file stands in here as a `.glb` that is plainly *not* the `.fbx`'s conversion -
 * the Khronos Fox, whose clips are `Survey`, `Walk` and `Run` where the FBX fixture's are `Bend`
 * and `Twist`. A build that still converted would read `Bend`; one that reads what is committed
 * reads `Survey`.
 */
class CommittedModelsTest {

    private val fixture: Path = TestPaths.repoRoot.resolve("udea-assets-compiler/src/test/resources/fbx/bender")
    private val fox: Path = TestPaths.exampleAssets.resolve("models/fox/Fox.glb")
    private val fbx = ResFile.of("models/bender/Bender.fbx")
    private val script = "models/bender.udea.kts" to "model(name = \"bender\", file = \"models/bender/Bender.fbx\")\n"

    /** An asset root holding the FBX fixture and its texture at `models/bender/`, declared by a script. */
    private fun tree(name: String, committed: Path? = fox): Path {
        val root = TestPaths.scratch("committed-models-$name")
        val folder = root.resolve("models/bender").createDirectories()
        fixture.resolve("Bender.fbx").copyTo(folder.resolve("Bender.fbx"))
        fixture.resolve("checker.png").copyTo(folder.resolve("checker.png"))
        committed?.copyTo(folder.resolve("Bender.glb"))
        root.resolve("models/bender.udea.kts").writeText("model(name = \"bender\", file = \"models/bender/Bender.fbx\")\n")
        return root
    }

    private fun scan(root: Path): List<Declaration> =
        UdeaDeclarationScanner(TestPaths.repoRoot, root).use { it.scanTree() }.declarations

    @Test
    fun `an fbx model's clips are read from the glb committed beside it, not from a conversion`() {
        val root = tree("read")
        val read = ModelFileSource.read(root, scan(root))

        assertEquals(emptyList(), read.diagnostics)
        assertEquals(listOf("Survey", "Walk", "Run"), read.clips.getValue("models/bender").map { it.name })
    }

    @Test
    fun `the pack publishes the committed glb byte for byte`() {
        val context = ValidationFixture.withFbx("committed-pack", script) { assets ->
            fox.copyTo(assets.resolve("models/bender/Bender.glb"), overwrite = true)
        }
        val published = AssetPipeline.committedModels(context.assetRoot, context.graph)

        assertEquals(emptyList(), published.diagnostics)
        assertContentEquals(fox.readBytes(), published.files.getValue("models/bender/Bender.glb"))
    }

    @Test
    fun `an fbx with no committed glb fails with UDEA0039, naming the file and the task that writes it`() {
        val root = tree("missing", committed = null)
        val diagnostic = ModelFileSource.read(root, scan(root)).diagnostics.single()

        assertEquals("UDEA0039", diagnostic.ruleId)
        assertEquals("models/bender", diagnostic.assetId)
        assertTrue("`models/bender/Bender.glb`" in diagnostic.message, diagnostic.message)
        assertTrue("udeaWriteConvertedModels" in diagnostic.message, diagnostic.message)
        assertTrue(!root.resolve("models/bender/Bender.glb").exists(), "reading wrote nothing")
    }

    @Test
    fun `the validator reports a missing committed glb as UDEA0039 too`() {
        val context = ValidationFixture.withFbx("committed-missing", script) { assets ->
            assets.resolve("models/bender/Bender.glb").takeIf { it.exists() }?.deleteExisting()
        }
        val errors = ValidationFixture.report(context).diagnostics.filter { it.ruleId == "UDEA0039" }

        assertEquals(1, errors.size, "$errors")
        assertTrue("udeaWriteConvertedModels" in errors.single().message, errors.single().message)
    }

    // ---- the writer and the check -------------------------------------------------------------

    /** The host the committed files are made on. */
    private val linux = ConversionHost("Linux", "amd64")

    @Test
    fun `the writer puts the fbx's conversion beside it, and the check then finds it current`() {
        val root = tree("write", committed = null)
        val written = CommittedModels.write(root, scan(root))

        assertEquals(emptyList(), written.diagnostics)
        assertEquals(listOf(ResFile.of("models/bender/Bender.glb")), written.files)
        assertContentEquals(
            FbxConverter.convert(root, fbx).getOrThrow(),
            root.resolve("models/bender/Bender.glb").readBytes(),
        )
        val verdict = CommittedModels.verify(root, scan(root), linux)
        verdict as CommittedModels.Verdict.Checked
        assertEquals(emptyList(), verdict.diagnostics)
        assertEquals(listOf(ResFile.of("models/bender/Bender.glb")), verdict.current)
    }

    @Test
    fun `the writer replaces a stale glb`() {
        val root = tree("rewrite")
        CommittedModels.write(root, scan(root))

        assertEquals(listOf("Bend", "Twist"), GltfClips.read(root.resolve("models/bender/Bender.glb")).getOrThrow().map { it.name })
    }

    /**
     * The case the check exists for: somebody edits what the `.fbx` converts from and does not run
     * the writer. Here the texture beside it is swapped for another PNG, which the conversion
     * embeds, so the fresh conversion and the committed file part ways.
     */
    @Test
    fun `a source changed after the glb was written fails the check with UDEA0039 naming the writer`() {
        val root = tree("stale", committed = null)
        CommittedModels.write(root, scan(root))
        TestPaths.repoRoot.resolve("moba/game/assets/models/human/ClothedLightSkin.png").copyTo(root.resolve("models/bender/checker.png"), overwrite = true)

        val verdict = CommittedModels.verify(root, scan(root), linux)
        verdict as CommittedModels.Verdict.Checked
        val diagnostic = verdict.diagnostics.single()
        assertEquals("UDEA0039", diagnostic.ruleId)
        assertEquals("models/bender", diagnostic.assetId)
        assertTrue("`models/bender/Bender.fbx`" in diagnostic.message, diagnostic.message)
        assertTrue("no longer converts to the `models/bender/Bender.glb`" in diagnostic.message, diagnostic.message)
        assertTrue("./gradlew udeaWriteConvertedModels" in diagnostic.message, diagnostic.message)
        assertEquals(emptyList(), verdict.current)
    }

    @Test
    fun `the check fails a missing glb rather than passing it`() {
        val root = tree("check-missing", committed = null)
        val verdict = CommittedModels.verify(root, scan(root), linux) as CommittedModels.Verdict.Checked

        assertTrue("udeaWriteConvertedModels" in verdict.diagnostics.single().message, verdict.diagnostics.single().message)
    }

    @Test
    fun `a broken fbx fails the writer and the check with UDEA0039, and the writer leaves the glb alone`() {
        val root = tree("broken")
        val folder = root.resolve("models/bender")
        val whole = folder.resolve("Bender.fbx").readBytes()
        folder.resolve("Bender.fbx").writeBytes(whole.copyOfRange(0, whole.size / 3))

        val written = CommittedModels.write(root, scan(root))
        assertEquals(listOf("UDEA0039"), written.diagnostics.map { it.ruleId })
        assertTrue("could not be read as FBX" in written.diagnostics.single().message, written.diagnostics.single().message)
        assertEquals(emptyList(), written.files)
        assertContentEquals(fox.readBytes(), folder.resolve("Bender.glb").readBytes(), "a failed conversion overwrote the glb")

        val verdict = CommittedModels.verify(root, scan(root), linux) as CommittedModels.Verdict.Checked
        assertEquals(listOf("UDEA0039"), verdict.diagnostics.map { it.ruleId })
        assertTrue("could not be read as FBX" in verdict.diagnostics.single().message, verdict.diagnostics.single().message)
    }

    @Test
    fun `a missing texture fails the writer with UDEA0039 naming the texture`() {
        val root = tree("no-texture", committed = null)
        root.resolve("models/bender/checker.png").deleteExisting()
        val diagnostic = CommittedModels.write(root, scan(root)).diagnostics.single()

        assertEquals("UDEA0039", diagnostic.ruleId)
        assertTrue("`models/bender/checker.png`" in diagnostic.message, diagnostic.message)
        assertTrue(!root.resolve("models/bender/Bender.glb").exists())
    }

    /**
     * Anywhere but the reference platform the check does not convert at all - even a stale file
     * passes there - and it says why, because a skip that says nothing reads as a pass.
     */
    @Test
    fun `the check is skipped on every other platform, and says why`() {
        val root = tree("elsewhere")
        for (host in listOf(
            ConversionHost("Windows 11", "amd64"),
            ConversionHost("Mac OS X", "aarch64"),
            ConversionHost("Linux", "aarch64"),
        )) {
            val verdict = CommittedModels.verify(root, scan(root), host)
            verdict as CommittedModels.Verdict.Skipped
            assertTrue(verdict.reason.startsWith("skipped on $host"), verdict.reason)
            assertTrue("Linux x86_64" in verdict.reason, verdict.reason)
        }
        assertTrue(ConversionHost("Linux", "x86_64").isReference)
        assertTrue(linux.isReference)
    }

    @Test
    fun `a model that names a glb is none of the writer's business`() {
        val root = TestPaths.scratch("committed-models-glb-only")
        root.resolve("models/fox").createDirectories()
        fox.copyTo(root.resolve("models/fox/Fox.glb"))
        root.resolve("models/fox.udea.kts").writeText("model(name = \"fox\", file = \"models/fox/Fox.glb\")\n")

        assertEquals(emptyList(), CommittedModels.write(root, scan(root)).files)
        assertEquals(emptyList(), (CommittedModels.verify(root, scan(root), linux) as CommittedModels.Verdict.Checked).current)
    }

    /**
     * This module's own FBX fixture carries its committed `.glb`, and it has to be current for the
     * tests that read it to mean anything. Checked where the check itself runs; on a mismatch the
     * fresh conversion is written under `build/committed-models/`, so a deliberate change is a copy.
     */
    @Test
    fun `the fixture's committed glb is the conversion of the fbx beside it`() {
        Assumptions.assumeTrue(ConversionHost.current().isReference, "the committed files are checked on Linux x86_64 only")
        val root = tree("fixture", committed = null)
        val converted = FbxConverter.convert(root, fbx).getOrThrow()
        val committed = fixture.resolve("Bender.glb").takeIf { it.exists() }?.readBytes()
        if (committed == null || !converted.contentEquals(committed)) {
            val actual = TestPaths.repoRoot.resolve("udea-assets-compiler/build/committed-models/Bender.glb")
            actual.parent.createDirectories()
            actual.writeBytes(converted)
            error("fbx/bender/Bender.glb is not Bender.fbx's conversion; the conversion is at $actual")
        }
    }
}
