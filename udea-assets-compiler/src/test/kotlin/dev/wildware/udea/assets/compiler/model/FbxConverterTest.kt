package dev.wildware.udea.assets.compiler.model

import dev.wildware.udea.assets.compiler.ResFile
import dev.wildware.udea.assets.compiler.TestPaths
import dev.wildware.udea.assets.compiler.gen.GltfClips
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * An `.fbx` becomes a self-contained binary glTF at asset-build time (issue #244).
 *
 * The fixture is `fbx/bender/Bender.fbx`, made by Blender's own FBX exporter from
 * `fbx/make_bender.py`: one mesh skinned to two bones, a checker texture linked from beside the
 * file, and two takes Blender names `Rig|Bend` (24 frames at 24fps) and `Rig|Twist` (15 frames).
 * Every test converts that real file, or a copy of it with one thing taken away, with the real
 * Assimp; nothing here is a glTF a test wrote.
 */
class FbxConverterTest {

    private val fixture: Path = TestPaths.repoRoot.resolve("udea-assets-compiler/src/test/resources/fbx/bender")

    /** An asset root holding the fixture at `models/bender/`, after [prepare] has had its way. */
    private fun root(name: String, withTexture: Boolean = true, prepare: (Path) -> Unit = {}): Path {
        val root = TestPaths.scratch("fbx-$name")
        val folder = root.resolve("models/bender").createDirectories()
        fixture.resolve("Bender.fbx").copyTo(folder.resolve("Bender.fbx"))
        if (withTexture) fixture.resolve("checker.png").copyTo(folder.resolve("checker.png"))
        prepare(folder)
        return root
    }

    private val file = ResFile.of("models/bender/Bender.fbx")

    @Test
    fun `an fbx converts to a glb whose clips are the file's takes, named without the armature`() {
        val glb = FbxConverter.convert(root("clips"), file).getOrThrow()

        val clips = GltfClips.read(glb).getOrThrow()
        // 24 frames at 24fps is one second, 60 ticks; 15 frames is 0.625s, 37.5 ticks, rounded up.
        assertEquals(listOf("Bend" to 60L, "Twist" to 38L), clips.map { it.name to it.ticks })
    }

    @Test
    fun `the skin survives, with a joint for each bone that moves a vertex`() {
        val document = GlbContainer.read(FbxConverter.convert(root("skin"), file).getOrThrow()).getOrThrow().json

        val skin = (document["skins"] as JsonArray).single().jsonObject
        assertEquals(2, skin.getValue("joints").jsonArray.size)
        assertTrue("inverseBindMatrices" in skin, "a skin with no inverse bind matrices cannot pose: $skin")
        val attributes = document.getValue("meshes").jsonArray.single().jsonObject
            .getValue("primitives").jsonArray.single().jsonObject.getValue("attributes").jsonObject
        assertEquals(setOf("JOINTS_0", "NORMAL", "POSITION", "TEXCOORD_0", "WEIGHTS_0"), attributes.keys)
    }

    @Test
    fun `the texture beside the fbx is embedded, so the glb needs no other file`() {
        val root = root("texture")
        val container = GlbContainer.read(FbxConverter.convert(root, file).getOrThrow()).getOrThrow()

        val image = container.json.getValue("images").jsonArray.single().jsonObject
        assertNull(image["uri"], "an embedded image names no file: $image")
        assertEquals("image/png", image.getValue("mimeType").jsonPrimitive.content)
        val view = container.json.getValue("bufferViews").jsonArray[image.getValue("bufferView").jsonPrimitive.int].jsonObject
        val offset = (view["byteOffset"] as? JsonPrimitive)?.int ?: 0
        val length = view.getValue("byteLength").jsonPrimitive.int
        assertContentEquals(
            root.resolve("models/bender/checker.png").readBytes(),
            container.bin.copyOfRange(offset, offset + length),
            "the embedded bytes are the texture file's",
        )
    }

    /**
     * Assimp leaves uninitialised memory in the slack at the end of the binary buffer it writes,
     * so two runs over one file differ there. The build cache keys on these bytes, so the
     * converter writes the buffer compacted, and this is what holds it to that.
     */
    @Test
    fun `one fbx always converts to the same bytes`() {
        val root = root("deterministic")
        val first = FbxConverter.convert(root, file).getOrThrow()
        repeat(3) { assertContentEquals(first, FbxConverter.convert(root, file).getOrThrow()) }
    }

    @Test
    fun `an fbx whose texture is missing fails, naming the texture and where it was looked for`() {
        val failure = FbxConverter.convert(root("no-texture", withTexture = false), file).exceptionOrNull()

        val message = failure?.message.orEmpty()
        assertTrue("`checker.png`" in message, message)
        assertTrue("`models/bender/checker.png`" in message, message)
    }

    @Test
    fun `a texture that is not png or jpeg fails, because glTF allows only those two`() {
        val root = root("tga", withTexture = false) { folder ->
            folder.resolve("checker.png").writeText("this is not an image")
        }
        val message = FbxConverter.convert(root, file).exceptionOrNull()?.message.orEmpty()

        assertTrue("`checker.png`" in message, message)
        assertTrue("PNG or JPEG" in message, message)
    }

    @Test
    fun `a truncated fbx fails with Assimp's reason`() {
        val root = root("truncated") { folder ->
            val whole = folder.resolve("Bender.fbx").readBytes()
            folder.resolve("Bender.fbx").writeBytes(whole.copyOfRange(0, whole.size / 3))
        }
        val message = FbxConverter.convert(root, file).exceptionOrNull()?.message.orEmpty()

        assertTrue(message.startsWith("could not be read as FBX"), message)
    }

    @Test
    fun `a file that is not fbx at all fails the same way`() {
        val root = root("text") { folder -> folder.resolve("Bender.fbx").writeText("; just some text\n") }
        val message = FbxConverter.convert(root, file).exceptionOrNull()?.message.orEmpty()

        assertTrue(message.startsWith("could not be read as FBX"), message)
    }

    @Test
    fun `the glb a converted fbx is published as sits beside it with the extension changed`() {
        assertEquals(ResFile.of("models/bender/Bender.glb"), ModelSources.runtimeFile(file))
        assertEquals(ResFile.of("models/fox/Fox.glb"), ModelSources.runtimeFile(ResFile.of("models/fox/Fox.glb")))
        assertEquals(ResFile.of("a/B.gltf"), ModelSources.runtimeFile(ResFile.of("a/B.gltf")))
        assertEquals(ResFile.of("a/Upper.glb"), ModelSources.runtimeFile(ResFile.of("a/Upper.FBX")))
    }

    @Test
    fun `a clip name keeps what follows the last bar, and a name with none is left alone`() {
        assertEquals("Walk", FbxConverter.clipName("Human Armature|Walk"))
        assertEquals("Walk", FbxConverter.clipName("a|b|Walk"))
        assertEquals("Walk", FbxConverter.clipName("Walk"))
        // A take named only by its armature keeps the whole name rather than becoming nameless.
        assertEquals("Rig|", FbxConverter.clipName("Rig|"))
    }
}
