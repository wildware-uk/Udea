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
import kotlin.io.path.deleteExisting
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * An `.fbx` declared as a model (issue #244): `model(name = "bender", file = "models/bender/Bender.fbx")`.
 *
 * Real scripts, compiled and validated by the real passes, over a tree holding the FBX fixture. A
 * model that converts is published as the `.glb` it converts to; one that does not fails the whole
 * pipeline's report - what `udeaValidateAssets` fails the build on - with `UDEA0039` naming the
 * file, and the pack writes no `.glb` for it.
 */
class FbxModelAssetTest {

    private val script = "models/bender.udea.kts" to """
        model(name = "bender", file = "models/bender/Bender.fbx")
    """

    @Test
    fun `an fbx declared as a model validates clean and packs as the glb it converts to`() {
        val context = ValidationFixture.withFbx("fbx-clean", script)

        assertEquals(emptyList(), errors(context), "the committed fixture converts")

        val packed = GraphPacker.pack(context.graph)
        assertFalse(packed.hasErrors, "packing reported ${packed.diagnostics}")
        BundleReader.open(BundleWriter.write(BundleContent(assets = packed.assets))).use { bundle ->
            assertEquals(ResPath("models/bender/Bender.glb"), bundle.registry[reference<Model>("models/bender")].file)
        }

        val converted = AssetPipeline.convertModels(context.assetRoot, context.graph)
        assertEquals(emptyList(), converted.diagnostics)
        val glb = assertNotNull(converted.files["models/bender/Bender.glb"], "converted: ${converted.files.keys}")
        assertEquals(listOf("Bend", "Twist"), GltfClips.read(glb).getOrThrow().map { it.name })
    }

    @Test
    fun `a broken fbx fails the build with UDEA0039 naming the file`() {
        val context = ValidationFixture.withFbx("fbx-broken", script) { assets ->
            val fbx = assets.resolve("models/bender/Bender.fbx")
            val whole = fbx.readBytes()
            fbx.writeBytes(whole.copyOfRange(0, whole.size / 3))
        }

        val diagnostic = errorFor(context, AssetValidationRules.MODEL_CONVERSION.id)
        assertEquals("UDEA0039", diagnostic.ruleId)
        assertEquals("models/bender", diagnostic.assetId)
        assertTrue("`models/bender/Bender.fbx`" in diagnostic.message, diagnostic.message)
        assertTrue("could not be read as FBX" in diagnostic.message, diagnostic.message)
        assertNotNull(diagnostic.span, "a diagnostic with no location is a grep task")

        val converted = AssetPipeline.convertModels(context.assetRoot, context.graph)
        assertEquals(emptyMap(), converted.files, "no .glb is written for a model that did not convert")
        assertEquals(listOf("UDEA0039"), converted.diagnostics.map { it.ruleId })
    }

    @Test
    fun `an fbx whose texture is missing fails the build with UDEA0039 naming the texture`() {
        val context = ValidationFixture.withFbx("fbx-no-texture", script) { assets ->
            assets.resolve("models/bender/checker.png").deleteExisting()
        }

        val diagnostic = errorFor(context, AssetValidationRules.MODEL_CONVERSION.id)
        assertTrue("`models/bender/Bender.fbx`" in diagnostic.message, diagnostic.message)
        assertTrue("`models/bender/checker.png`" in diagnostic.message, diagnostic.message)
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
