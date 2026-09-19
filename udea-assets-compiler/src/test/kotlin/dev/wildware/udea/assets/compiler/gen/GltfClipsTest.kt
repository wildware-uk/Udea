package dev.wildware.udea.assets.compiler.gen

import dev.wildware.udea.assets.compiler.TestPaths
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Clip names and lengths read out of a glTF file with no renderer and no Kool (issue #241).
 *
 * The asset build runs headless, so it cannot ask Kool's loader. It reads what the glTF spec
 * guarantees instead: every animation sampler's input accessor carries its `max` time, so a
 * clip's length is the largest of those, and nothing in the binary buffer has to be decoded.
 */
class GltfClipsTest {

    private val fox: Path = TestPaths.exampleAssets.resolve("models/fox/Fox.glb")

    @Test
    fun `the fox's three clips come out in file order with their lengths in ticks`() {
        val clips = GltfClips.read(fox).getOrThrow()

        assertEquals(
            listOf(
                GltfClip(index = 0, name = "Survey", ticks = 205L),
                GltfClip(index = 1, name = "Walk", ticks = 43L),
                GltfClip(index = 2, name = "Run", ticks = 70L),
            ),
            clips,
        )
    }

    /**
     * The rule: seconds times 60, rounded **up**, except that a product within a thousandth of a
     * tick above a whole number is that whole number.
     *
     * Up, so that a clip played once is never declared finished before its last keyframe. The
     * thousandth is because a float32 cannot hold `n/60` exactly: the Fox's Survey is authored at
     * 205 ticks and stored as 3.4166667461395264 s, which is 205.0000048 ticks - and rounding
     * that up to 206 would add a tick of standing still to every loop.
     */
    @Test
    fun `seconds become ticks by rounding up, forgiving float32's error on a whole tick`() {
        assertEquals(60L, GltfClips.ticksOf(1f))
        assertEquals(30L, GltfClips.ticksOf(0.5f))
        assertEquals(31L, GltfClips.ticksOf(0.51f), "30.6 ticks rounds up")
        assertEquals(1L, GltfClips.ticksOf(0.001f), "any movement at all is at least one tick")
        assertEquals(0L, GltfClips.ticksOf(0f))
        assertEquals(205L, GltfClips.ticksOf(205f / 60f), "205 ticks as float32 seconds is still 205")
        assertEquals(43L, GltfClips.ticksOf(0.7083333f), "42.49999 ticks rounds up")
        assertEquals(62L, GltfClips.ticksOf(61f / 60f + 0.0001f), "six thousandths of a tick over a whole tick is over it")
    }

    @Test
    fun `a clip's length is the longest of its channels, and an unnamed clip has no name`() {
        val file = gltf(
            name = "two-channel",
            animations = """[{"channels": [], "samplers": [{"input": 0}, {"input": 1}]}]""",
            accessors = """[{"max": [0.5]}, {"max": [2.0]}]""",
        )

        assertEquals(listOf(GltfClip(index = 0, name = null, ticks = 120L)), GltfClips.read(file).getOrThrow())
    }

    @Test
    fun `a file with no animations has no clips`() {
        val file = gltf(name = "still", animations = null, accessors = "[]")

        assertEquals(emptyList(), GltfClips.read(file).getOrThrow())
    }

    @Test
    fun `a sampler whose input accessor states no max time is refused, naming the clip`() {
        val file = gltf(
            name = "no-max",
            animations = """[{"name": "Wave", "channels": [], "samplers": [{"input": 0}]}]""",
            accessors = """[{"min": [0.0]}]""",
        )

        val problem = GltfClips.read(file).exceptionOrNull()?.message.orEmpty()
        assertTrue("Wave" in problem && "max" in problem, problem)
    }

    @Test
    fun `a sampler naming an accessor the file does not have is refused`() {
        val file = gltf(
            name = "dangling",
            animations = """[{"name": "Wave", "channels": [], "samplers": [{"input": 3}]}]""",
            accessors = """[{"max": [1.0]}]""",
        )

        val problem = GltfClips.read(file).exceptionOrNull()?.message.orEmpty()
        assertTrue("accessor 3" in problem, problem)
    }

    @Test
    fun `a negative or non-numeric max time is refused`() {
        val negative = gltf(
            name = "negative",
            animations = """[{"name": "Back", "channels": [], "samplers": [{"input": 0}]}]""",
            accessors = """[{"max": [-1.0]}]""",
        )
        val text = gltf(
            name = "text",
            animations = """[{"name": "Word", "channels": [], "samplers": [{"input": 0}]}]""",
            accessors = """[{"max": ["soon"]}]""",
        )

        assertTrue("Back" in GltfClips.read(negative).exceptionOrNull()?.message.orEmpty())
        assertTrue("Word" in GltfClips.read(text).exceptionOrNull()?.message.orEmpty())
    }

    @Test
    fun `a file that is not glTF is refused with the validator's own reason`() {
        val file = TestPaths.scratch("gltf-clips-not-glb").resolve("broken.glb")
        file.writeBytes(ByteArray(8) { 1 })

        val problem = GltfClips.read(file).exceptionOrNull()?.message.orEmpty()
        assertTrue("not a binary glTF 2.0 file" in problem, problem)
    }

    /** A minimal JSON glTF with [animations] (omitted when `null`) and [accessors]. */
    private fun gltf(name: String, animations: String?, accessors: String): Path {
        val file = TestPaths.scratch("gltf-clips-$name").resolve("$name.gltf")
        val animationsEntry = if (animations == null) "" else """"animations": $animations,"""
        file.writeText("""{"asset": {"version": "2.0"}, $animationsEntry "accessors": $accessors}""")
        return file
    }
}
