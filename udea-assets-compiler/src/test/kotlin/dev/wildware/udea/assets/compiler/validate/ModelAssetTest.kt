package dev.wildware.udea.assets.compiler.validate

import dev.wildware.udea.assets.Model
import dev.wildware.udea.assets.ResPath
import dev.wildware.udea.assets.compiler.pack.BundleContent
import dev.wildware.udea.assets.compiler.pack.BundleWriter
import dev.wildware.udea.assets.compiler.pack.GraphPacker
import dev.wildware.udea.assets.compiler.toCatalog
import dev.wildware.udea.assets.pack.BundleReader
import dev.wildware.udea.assets.reference
import dev.wildware.udea.diagnostics.Severity
import dev.wildware.udea.diagnostics.UdeaDiagnostic
import dev.wildware.udea.diagnostics.UdeaRules
import org.junit.jupiter.api.Test
import kotlin.io.path.writeBytes
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A glTF model declared as an asset (issue #240): `model(name = "fox", file = "models/fox/Fox.glb")`.
 *
 * Every fixture here is a real `.udea.kts`, compiled and validated by the real passes, over a tree
 * holding the committed Khronos Fox. The build fails on a model in three ways and each is its own
 * test: the file is not there ([AssetValidationRules.MISSING_FILE]), the file is there and is not
 * glTF 2.0 ([AssetValidationRules.MODEL_FILE]), and a reference to the model misspells its id
 * ([UdeaRules.UNRESOLVED_REFERENCE]). "Fails the build" is read off the whole pipeline's report,
 * which is what `udeaValidateAssets` fails on, rather than off one validator.
 */
class ModelAssetTest {

    @Test
    fun `the fox declared as a model validates clean, is published as a Model, and packs typed`() {
        val context = ValidationFixture.withFox(
            "model-fox",
            "models/fox.udea.kts" to """
                model(name = "fox", file = "/models/fox/Fox.glb")
            """,
        )

        val errors = ValidationFixture.report(context).diagnostics.filter { it.severity == Severity.Error }
        assertEquals(emptyList(), errors, "the committed Fox is a valid model")

        // The compile-time catalog is what `reference<Model>("models/fox")` in game code is checked
        // against by the K2 checker; a model missing from it would make every correct spelling
        // `UDEA0004` too.
        val entry = assertNotNull(context.graph.toCatalog().catalog.resolve("models/fox"))
        assertEquals(Model::class.qualifiedName, entry.kindFqn)

        // And the runtime reads it back as a `Model` holding a normalised path.
        val packed = GraphPacker.pack(context.graph)
        assertFalse(packed.hasErrors, "packing reported ${packed.diagnostics}")
        BundleReader.open(BundleWriter.write(BundleContent(assets = packed.assets))).use { bundle ->
            val fox = bundle.registry[reference<Model>("models/fox")]
            assertEquals(ResPath("models/fox/Fox.glb"), fox.file)
        }
    }

    @Test
    fun `a missing model file fails the build with a did-you-mean over the files that exist`() {
        val context = ValidationFixture.withFox(
            "model-missing",
            "models/fox.udea.kts" to """
                model(name = "fox", file = "models/fox/Fx.glb")
            """,
        )

        val diagnostic = errorFor(context, AssetValidationRules.MISSING_FILE.id)
        assertEquals("models/fox", diagnostic.assetId)
        assertTrue("did you mean `models/fox/Fox.glb`?" in diagnostic.message, diagnostic.message)
    }

    @Test
    fun `a model file that is not glTF 2 fails the build naming what it is instead`() {
        val context = ValidationFixture.withFox(
            "model-unreadable",
            "models/fox.udea.kts" to """
                model(name = "notes", file = "models/fox/NOTICE.md")
                model(name = "broken", file = "models/broken.glb")
            """,
        ) { assets -> assets.resolve("models/broken.glb").writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)) }

        val diagnostics = errors(context).filter { it.ruleId == AssetValidationRules.MODEL_FILE.id }
        assertEquals(
            listOf("models/broken", "models/notes"),
            diagnostics.mapNotNull { it.assetId }.sorted(),
            "both files must be refused: $diagnostics",
        )
        val notes = diagnostics.single { it.assetId == "models/notes" }
        assertTrue("`models/fox/NOTICE.md`" in notes.message, notes.message)
        assertTrue(".glb or .gltf" in notes.message, notes.message)
        val broken = diagnostics.single { it.assetId == "models/broken" }
        assertTrue("not a binary glTF" in broken.message, broken.message)
    }

    @Test
    fun `a misspelled model id fails the build with a did-you-mean`() {
        val context = ValidationFixture.withFox(
            "model-misspelled",
            "models/fox.udea.kts" to """
                model(name = "fox", file = "models/fox/Fox.glb")
                asset("prop", "statue", "model" to reference("models/fxo"))
            """,
        )

        val diagnostic = errorFor(context, UdeaRules.UNRESOLVED_REFERENCE.id)
        assertTrue("did you mean `models/fox`?" in diagnostic.message, diagnostic.message)
    }

    private fun errors(context: ValidationContext): List<UdeaDiagnostic> =
        ValidationFixture.report(context).diagnostics.filter { it.severity == Severity.Error }

    private fun errorFor(context: ValidationContext, ruleId: String): UdeaDiagnostic {
        val all = errors(context)
        return assertNotNull(all.singleOrNull { it.ruleId == ruleId }, "expected one $ruleId error in $all")
    }
}
