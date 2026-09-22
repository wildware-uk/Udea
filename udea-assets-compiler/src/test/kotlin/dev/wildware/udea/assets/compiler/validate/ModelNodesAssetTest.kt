package dev.wildware.udea.assets.compiler.validate

import dev.wildware.udea.assets.AssetValue
import dev.wildware.udea.assets.Model
import dev.wildware.udea.assets.ModelExtras
import dev.wildware.udea.assets.ModelNode
import dev.wildware.udea.assets.compiler.TestPaths
import dev.wildware.udea.assets.compiler.gen.GltfNode
import dev.wildware.udea.assets.compiler.gen.ModelFileSource
import dev.wildware.udea.assets.compiler.pack.BundleContent
import dev.wildware.udea.assets.compiler.pack.BundleWriter
import dev.wildware.udea.assets.compiler.pack.GraphPacker
import dev.wildware.udea.assets.compiler.pack.PackedValues
import dev.wildware.udea.assets.pack.BundleReader
import dev.wildware.udea.assets.reference
import dev.wildware.udea.diagnostics.Severity
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A game holding a `Ref<Model>` asks it what nodes it has, and what its artist wrote in Blender's
 * Custom Properties (issue #271).
 *
 * Real scripts and real model files, through the real pass 2 and the real packer, read back out
 * of a real `.udeapak` by the reader a game uses. The fixtures are the Khronos Fox, the FBX test
 * fixture, and `models/module/module.glb`, which `build_module.py` beside it makes in headless
 * Blender - so the `extras` read here are where Blender's own exporter put them, not where a test
 * guessed it would.
 *
 * The comparison that matters most is against [ModelFileSource]: that is what the generated
 * `Fox.Nodes` accessors are written from, so a packed list equal to it is a runtime list that
 * cannot disagree with the names a game compiles against.
 */
class ModelNodesAssetTest {

    private val scripts = arrayOf(
        "models/models.udea.kts" to """
            model(name = "fox", file = "models/fox/Fox.glb")
            model(name = "module", file = "models/module/module.glb")
        """,
    )

    private val context by lazy {
        ValidationFixture.withFox("model-nodes", *scripts) { assets -> copyModule(assets) }
    }

    @Test
    fun `a model's nodes come out of the bundle from a Ref to it`() {
        val fox = packedModel("models/fox")

        assertEquals(26, fox.nodes.size, "the Fox names 26 nodes: ${fox.nodes.map { it.name }}")
        assertEquals("root", fox.nodes.first().name)
        assertEquals(listOf(0, 1, 2), fox.nodes.take(3).map { it.index })
    }

    @Test
    fun `the packed nodes are the nodes the generated accessors are written from`() {
        val accessorNodes = ModelFileSource.read(context.assetRoot, context.declarations).nodes

        for (id in listOf("models/fox", "models/module")) {
            val expected = accessorNodes.getValue(id).map(::asModelNode)
            assertEquals(expected, packedModel(id).nodes, "$id's packed nodes differ from its accessors'")
        }
    }

    @Test
    fun `a node's Blender custom properties are readable from the game`() {
        val module = packedModel("models/module")
        val body = module.nodes.single { it.name == "module" }

        assertEquals("small", body.extras.text("module_size"))
        assertEquals(12.5f, body.extras.float("mass"))
        assertEquals(3, body.extras.int("power"))
        assertEquals(true, body.extras.bool("armoured"))
        assertEquals(listOf(0f, 0f, 0.5f), body.extras.floats("offset"))
        // A Custom Property group is a nested object in glTF; it is read as dotted keys.
        assertEquals(2, body.extras.int("fitting.slots"))

        val socket = module.nodes.single { it.name == "socket_top" }
        assertEquals("small", socket.extras.text("accepts"))
        assertEquals(listOf("accepts"), socket.extras.keys, "one node's properties are not another's")
    }

    @Test
    fun `the scene's custom properties are the model's own`() {
        val module = packedModel("models/module")

        assertEquals(2, module.extras.int("tier"))
        assertEquals(listOf("tier"), module.extras.keys)
    }

    @Test
    fun `a file with no custom properties packs none`() {
        val fox = packedModel("models/fox")

        assertTrue(fox.extras.keys.isEmpty(), "${fox.extras}")
        assertTrue(fox.nodes.all { it.extras.keys.isEmpty() }, "${fox.nodes.filter { it.extras.keys.isNotEmpty() }}")
    }

    @Test
    fun `the daemon's values carry the same nodes the bundle does`() {
        // The dev daemon hands a hot reload `PackedValues`, not a bundle a build wrote; a model
        // whose nodes only reached one of the two would lose them on the first reload.
        val values = PackedValues.of(context.graph)
        assertFalse(values.diagnostics.any { it.severity == Severity.Error }, "${values.diagnostics}")

        val module = values.values.getValue(dev.wildware.udea.assets.AssetId("models/module")) as Model
        assertEquals(packedModel("models/module"), module)
    }

    @Test
    fun `an fbx model packs the nodes of the glb committed beside it`() {
        val context = ValidationFixture.withFbx(
            "model-nodes-fbx",
            "models/bender.udea.kts" to """
                model(name = "bender", file = "models/bender/Bender.fbx")
            """,
        )
        val bender = packed(context, "models/bender")

        val expected = ModelFileSource.read(context.assetRoot, context.declarations).nodes.getValue("models/bender")
        assertTrue(expected.isNotEmpty(), "the FBX fixture has named nodes")
        assertEquals(expected.map(::asModelNode), bender.nodes)
    }

    private fun packedModel(id: String): Model = packed(context, id)

    private fun packed(context: ValidationContext, id: String): Model {
        val packed = GraphPacker.pack(context.graph)
        assertFalse(packed.hasErrors, "packing reported ${packed.diagnostics}")
        return BundleReader.open(BundleWriter.write(BundleContent(assets = packed.assets))).use { bundle ->
            bundle.registry[reference<Model>(id)]
        }
    }

    private companion object {

        /** The Blender-made fixture, copied to `models/module/module.glb` in the scratch tree. */
        fun copyModule(assets: Path) {
            val target = assets.resolve("models/module")
            target.createDirectories()
            TestPaths.repoRoot.resolve("udea-assets-compiler/src/test/resources/models/module/module.glb")
                .copyTo(target.resolve("module.glb"), overwrite = true)
        }

        /** What the accessor generator is handed, as the runtime type the pack produces. */
        fun asModelNode(node: GltfNode): ModelNode = ModelNode(
            index = node.index,
            name = node.name,
            x = node.x, y = node.y, z = node.z,
            qx = node.qx, qy = node.qy, qz = node.qz, qw = node.qw,
            scaleX = node.scaleX, scaleY = node.scaleY, scaleZ = node.scaleZ,
            extras = extrasOf(node.extras),
        )

        /** The build's plain values as the runtime's typed ones: numbers, text, flags, vectors. */
        fun extrasOf(values: Map<String, Any>): ModelExtras = ModelExtras(
            values.mapValues { (_, value) ->
                when (value) {
                    is Int -> AssetValue.IntValue(value)
                    is Float -> AssetValue.FloatValue(value)
                    is String -> AssetValue.TextValue(value)
                    is Boolean -> AssetValue.BoolValue(value)
                    is List<*> -> AssetValue.ListValue(value.map { AssetValue.FloatValue(it as Float) })
                    else -> error("not a plain extras value: $value")
                }
            },
        )
    }
}
