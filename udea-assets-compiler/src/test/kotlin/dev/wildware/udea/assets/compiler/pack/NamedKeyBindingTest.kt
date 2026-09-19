package dev.wildware.udea.assets.compiler.pack

import dev.wildware.udea.assets.Binding
import dev.wildware.udea.assets.BindingInput
import dev.wildware.udea.assets.Control
import dev.wildware.udea.assets.InputKey
import dev.wildware.udea.assets.compiler.AssetCompileResult
import dev.wildware.udea.assets.compiler.AssetCompiler
import dev.wildware.udea.assets.compiler.AssetCompilerRules
import dev.wildware.udea.assets.compiler.TestPaths
import dev.wildware.udea.assets.pack.AssetCodecs
import dev.wildware.udea.assets.pack.AssetDecodeException
import dev.wildware.udea.assets.pack.BundleReader
import dev.wildware.udea.assets.reference
import dev.wildware.udea.diagnostics.Severity
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A controls asset names its keys, and a backend's number cannot be written in one (issue #228).
 *
 * `key(...)` takes an `InputKey` and nothing else, so the property "no backend key integer appears
 * in a controls asset" is the compiler's rather than a reviewer's: the second test is the script a
 * game used to write, and it does not compile. The first is the same binding written the way it is
 * now, carried by name through evaluation, packing and the runtime codec - and the third is what the
 * codec does with a name this build does not know, which a bundle from a newer build could hold.
 */
class NamedKeyBindingTest {

    private fun compile(root: Path, script: String): AssetCompileResult {
        val assets = root.resolve("assets").also { it.createDirectories() }
        val file = assets.resolve("controls.udea.kts")
        file.writeText(script.trimIndent())
        return AssetCompiler(root, assets, TestPaths.compilerClasspath, TestPaths.scratch("named-key-cache"))
            .compile(listOf(file))
    }

    @Test
    fun `a key written by name reaches the runtime binding by name`(@TempDir root: Path) {
        val result = compile(
            root,
            """
            control(name = "fire")
            binding(name = "fire_binding", control = reference("fire"), input = key(InputKey.Space))
            """,
        )
        assertEquals(emptyList(), result.diagnostics.filter { it.severity == Severity.Error })

        val packed = GraphPacker.pack(result.graph)
        BundleReader.open(BundleWriter.write(BundleContent(assets = packed.assets, atlas = PackedAtlas.EMPTY)))
            .use { bundle ->
                val binding = bundle.registry[reference<Binding>("fire_binding")]
                assertEquals(BindingInput.Key(InputKey.Space), binding.input)
            }
    }

    @Test
    fun `a backend key number does not compile in a controls asset`(@TempDir root: Path) {
        // 87 is GLFW's W, and the number this asset would have held before issue #228.
        val result = compile(
            root,
            """
            control(name = "walk")
            binding(name = "walk_binding", control = reference("walk"), input = key(87))
            """,
        )
        val errors = result.diagnostics.filter { it.severity == Severity.Error }
        assertTrue(
            errors.any { it.ruleId == AssetCompilerRules.SCRIPT_COMPILATION_FAILED.id },
            "`key(87)` must be refused as a script that does not compile; got $errors",
        )
        assertTrue(result.graph.ids.isEmpty(), "a binding on a number must not reach the graph")
    }

    @Test
    fun `a key name this build does not know is refused by name`() {
        val control = PackedAsset("fire", Control::class.qualifiedName!!, PackValue.Fields.of(emptyMap()))
        val binding = PackedAsset(
            "fire_binding",
            Binding::class.qualifiedName!!,
            PackValue.Fields.of(
                mapOf(
                    "control" to PackValue.Ref(0, "fire"),
                    "inputKind" to PackValue.Text(AssetCodecs.KEY),
                    "inputKey" to PackValue.Text("Hyper"),
                ),
            ),
        )
        val failure = assertFailsWith<AssetDecodeException> {
            BundleReader.open(
                BundleWriter.write(BundleContent(assets = listOf(control, binding), atlas = PackedAtlas.EMPTY)),
            ).use { bundle -> bundle.registry[reference<Binding>("fire_binding")] }
        }
        assertTrue("unknown key 'Hyper'" in failure.message.orEmpty(), failure.message)
    }
}
