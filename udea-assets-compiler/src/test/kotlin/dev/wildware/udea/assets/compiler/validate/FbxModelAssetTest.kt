package dev.wildware.udea.assets.compiler.validate

import dev.wildware.udea.assets.Model
import dev.wildware.udea.assets.ResPath
import dev.wildware.udea.assets.compiler.gen.GltfClips
import dev.wildware.udea.assets.compiler.pack.BundleContent
import dev.wildware.udea.assets.compiler.pack.BundleWriter
import dev.wildware.udea.assets.compiler.pack.GraphPacker
import dev.wildware.udea.assets.compiler.pipeline.AssetPipeline
import dev.wildware.udea.assets.pack.BundleReader
import dev.wildware.udea.assets.reference
import dev.wildware.udea.diagnostics.Severity
import dev.wildware.udea.diagnostics.UdeaDiagnostic
import org.junit.jupiter.api.Test
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * An `.fbx` declared as a model (issue #244): `model(name = "bender", file = "models/bender/Bender.fbx")`.
 *
 * Real scripts, compiled and validated by the real passes, over a tree holding the FBX fixture
 * and the `.glb` committed beside it. The model is published as that `.glb`, byte for byte; the
 * build never converts (`CommittedModelsTest` has the writer, the check, and a missing `.glb`).
 */
class FbxModelAssetTest {

    private val script = "models/bender.udea.kts" to """
        model(name = "bender", file = "models/bender/Bender.fbx")
    """

    @Test
    fun `an fbx declared as a model validates clean and packs as the glb committed beside it`() {
        val context = ValidationFixture.withFbx("fbx-clean", script)

        assertEquals(emptyList(), errors(context), "the fixture has its committed glb")

        val packed = GraphPacker.pack(context.graph)
        assertFalse(packed.hasErrors, "packing reported ${packed.diagnostics}")
        BundleReader.open(BundleWriter.write(BundleContent(assets = packed.assets))).use { bundle ->
            assertEquals(ResPath("models/bender/Bender.glb"), bundle.registry[reference<Model>("models/bender")].file)
        }

        val published = AssetPipeline.committedModels(context.assetRoot, context.graph)
        assertEquals(emptyList(), published.diagnostics)
        val glb = assertNotNull(published.files["models/bender/Bender.glb"], "published: ${published.files.keys}")
        assertEquals(listOf("Bend", "Twist"), GltfClips.read(glb).getOrThrow().map { it.name })
    }

    /**
     * The build reads the committed `.glb` and never runs Assimp, so an `.fbx` that no longer
     * converts is not the validator's to find: `udeaVerifyConvertedModels` finds it, on the one
     * platform whose Assimp made the `.glb` (`CommittedModelsTest`). What a game is given is the
     * committed file, and that is still sound.
     */
    @Test
    fun `the validator does not convert, so a broken fbx beside a sound glb validates clean`() {
        val context = ValidationFixture.withFbx("fbx-broken", script) { assets ->
            val fbx = assets.resolve("models/bender/Bender.fbx")
            val whole = fbx.readBytes()
            fbx.writeBytes(whole.copyOfRange(0, whole.size / 3))
        }

        assertEquals(emptyList(), errors(context))
        val published = AssetPipeline.committedModels(context.assetRoot, context.graph)
        assertEquals(emptyList(), published.diagnostics)
        assertEquals(setOf("models/bender/Bender.glb"), published.files.keys)
    }

    @Test
    fun `a committed glb that is not glTF is the model-file rule, naming the glb`() {
        val context = ValidationFixture.withFbx("fbx-bad-glb", script) { assets ->
            assets.resolve("models/bender/Bender.glb").writeBytes("not a model".toByteArray())
        }

        val diagnostic = errorFor(context, AssetValidationRules.MODEL_FILE.id)
        assertTrue("`models/bender/Bender.fbx`" in diagnostic.message, diagnostic.message)
        assertTrue("committed `models/bender/Bender.glb`" in diagnostic.message, diagnostic.message)
    }

    @Test
    fun `a missing fbx is the missing-file rule with its did-you-mean, not a conversion failure`() {
        val context = ValidationFixture.withFbx(
            "fbx-missing",
            "models/bender.udea.kts" to """
                model(name = "bender", file = "models/bender/Bendr.fbx")
            """,
        )

        val all = errors(context)
        val diagnostic = errorFor(context, AssetValidationRules.MISSING_FILE.id)
        assertTrue("did you mean `models/bender/Bender.fbx`?" in diagnostic.message, diagnostic.message)
        assertTrue(all.none { it.ruleId == AssetValidationRules.MODEL_CONVERSION.id }, "$all")
    }

    private fun errors(context: ValidationContext): List<UdeaDiagnostic> =
        ValidationFixture.report(context).diagnostics.filter { it.severity == Severity.Error }

    private fun errorFor(context: ValidationContext, ruleId: String): UdeaDiagnostic {
        val all = errors(context)
        return assertNotNull(all.singleOrNull { it.ruleId == ruleId }, "expected one $ruleId error in $all")
    }
}
