package dev.wildware.udea.agent.host

import dev.wildware.udea.agent.AgentCommand
import dev.wildware.udea.agent.AgentResult
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * `render.compare_artifacts` - the last step of the Phase 1 demo, which both candidate owners had
 * disclaimed and nobody implemented.
 *
 * Every failure path is asserted to land as `ok:false` rather than as a throw, because a tool that
 * throws into the HTTP layer is reported to an agent as a crashed game.
 */
class CompareArtifactsTest {

    @TempDir
    lateinit var temp: Path

    @Test
    fun identicalImagesReportZero() {
        val store = AgentArtifacts(temp)
        val image = gradient(16, 12)
        val a = assertNotNull(store.put(png(image)))
        val b = assertNotNull(store.put(png(image)))

        val json = ok(ArtifactToolset(store).compareArtifacts(a.value, b.value, 0))

        assertContains(json, """"identical":true""")
        assertContains(json, """"differentPixels":0""")
        assertContains(json, """"maxChannelDelta":0""")
        assertContains(json, """"diffArtifactId":null""")
        assertEquals(2, store.count, "an identical comparison writes no diff artifact")
    }

    /**
     * The orientation assertion the notes ask for. `gradient` varies with **y**, so a comparison of
     * an image against a vertically flipped copy of itself is only zero if both sides were decoded
     * the same way up - and a capture/diff pair that disagreed about the framebuffer's bottom-up
     * rows would make every honest comparison a full-image difference.
     */
    @Test
    fun `orientation is consistent across the decode`() {
        val store = AgentArtifacts(temp)
        val upright = gradient(8, 8)
        val flipped = BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB).also { out ->
            for (y in 0 until 8) for (x in 0 until 8) out.setRGB(x, y, upright.getRGB(x, 7 - y))
        }
        val a = assertNotNull(store.put(png(upright)))
        val b = assertNotNull(store.put(png(flipped)))

        assertContains(ok(ArtifactToolset(store).compareArtifacts(a.value, a.value, 0)), """"identical":true""")
        assertContains(ok(ArtifactToolset(store).compareArtifacts(a.value, b.value, 0)), """"identical":false""")
    }

    @Test
    fun shiftedImageReportsBoundingBox() {
        val store = AgentArtifacts(temp)
        val base = solid(32, 32, 0xFF102030.toInt())
        val moved = solid(32, 32, 0xFF102030.toInt()).also { out ->
            for (y in 10 until 14) for (x in 20 until 25) out.setRGB(x, y, 0xFFFFFFFF.toInt())
        }
        val a = assertNotNull(store.put(png(base)))
        val b = assertNotNull(store.put(png(moved)))

        val json = ok(ArtifactToolset(store).compareArtifacts(a.value, b.value, 0))

        assertContains(json, """"identical":false""")
        assertContains(json, """"differentPixels":20""")
        assertContains(json, """"bbox":{"x":20,"y":10,"w":5,"h":4}""")
        // The visualisation is written so the agent can look when the scalars are ambiguous.
        val diffId = json.substringAfter(""""diffArtifactId":"""").substringBefore('"')
        val diff = assertNotNull(store.get(assertNotNull(ArtifactId.parse(diffId))))
        val decoded = assertNotNull(ImageIO.read(diff.path.toFile()))
        assertEquals(ImageDiff.HIGHLIGHT, decoded.getRGB(22, 11), "a changed pixel is highlighted")
    }

    @Test
    fun toleranceSuppressesNoise() {
        val store = AgentArtifacts(temp)
        val base = solid(8, 8, 0xFF808080.toInt())
        val nudged = solid(8, 8, 0xFF818181.toInt())
        val a = assertNotNull(store.put(png(base)))
        val b = assertNotNull(store.put(png(nudged)))
        val toolset = ArtifactToolset(store)

        assertContains(ok(toolset.compareArtifacts(a.value, b.value, 0)), """"identical":false""")
        assertContains(ok(toolset.compareArtifacts(a.value, b.value, 1)), """"identical":true""")
    }

    @Test
    fun dimensionMismatchIsTypedError() {
        val store = AgentArtifacts(temp)
        val a = assertNotNull(store.put(png(solid(8, 8, -1))))
        val b = assertNotNull(store.put(png(solid(9, 8, -1))))

        val failed = ArtifactToolset(store).compareArtifacts(a.value, b.value, 0)

        assertTrue(failed is AgentResult.Failed)
        assertEquals("artifact_size_mismatch", failed.error.kind.id)
        assertContains(failed.error.message, "8x8")
        assertContains(failed.error.message, "9x8")
    }

    @Test
    fun `an unknown id is a typed error, and an evicted one says which`() {
        val store = AgentArtifacts(temp, maxEntries = 1, maxBytes = Long.MAX_VALUE)
        val dropped = assertNotNull(store.put(png(solid(4, 4, -1))))
        val kept = assertNotNull(store.put(png(solid(4, 4, -1))))
        val toolset = ArtifactToolset(store)

        val unknown = toolset.compareArtifacts(kept.value, "cap_9999", 0)
        assertTrue(unknown is AgentResult.Failed)
        assertEquals("artifact_not_found", unknown.error.kind.id)
        assertContains(unknown.error.message, "never stored")

        val evicted = toolset.compareArtifacts(kept.value, dropped.value, 0)
        assertTrue(evicted is AgentResult.Failed)
        assertContains(evicted.error.message, "dropped by the artifact LRU")
    }

    /**
     * A truncated PNG is the realistic way to make a decoder throw, and it is exactly the case a
     * Phase 1 exit criterion names: a throwing tool must land as `ok:false` without stalling.
     */
    @Test
    fun `a truncated artifact is a typed error rather than a throw`() {
        val store = AgentArtifacts(temp)
        val good = assertNotNull(store.put(png(solid(8, 8, -1))))
        val truncated = assertNotNull(store.put(png(solid(8, 8, -1)).copyOfRange(0, 20)))

        val failed = ArtifactToolset(store).compareArtifacts(good.value, truncated.value, 0)

        assertTrue(failed is AgentResult.Failed)
        assertEquals("artifact_unreadable", failed.error.kind.id)
    }

    @Test
    fun `the tool routes through its declaration and is published by the manifest`() {
        val store = AgentArtifacts(temp)
        val image = gradient(4, 4)
        val a = assertNotNull(store.put(png(image)))
        val b = assertNotNull(store.put(png(image)))

        val produced = CompareArtifactsTool.invoke(
            ArtifactToolset(store),
            AgentCommand("render.compare_artifacts", mapOf("a" to a.value, "b" to b.value)),
        )
        assertTrue(produced is AgentResult.Ok)
        assertContains(produced.json, """"identical":true""")

        val manifest = ToolManifest.of(GameIdentity("Test", "1"), AgentHostTools.tools)
        assertContains(manifest.json, """"name":"render.compare_artifacts"""")
        assertContains(manifest.json, "Compare two stored screenshots")
        assertContains(manifest.toolsetNames, "render")
    }

    /** No GL anywhere on this path, which is why a Headless CI job can diff artifacts. */
    @Test
    fun `comparison works with no render context`() {
        val store = AgentArtifacts(temp)
        val a = assertNotNull(store.put(png(solid(4, 4, -1))))
        val b = assertNotNull(store.put(png(solid(4, 4, -1))))

        // `toggle_debug_draw` rather than `screenshot`: the two capture tools answer through
        // `AgentContext.answerLater`, so calling one needs a real dispatch. That path is covered
        // by `HeadlessRenderToolsTest`, which drives all five tools through an `AgentRuntime`;
        // what this test is about is that the *diff* needs no render context, and this line is
        // here to show a render tool refusing beside it.
        val render = RenderToolset(dev.wildware.udea.core.host.RenderMode.Headless)
        val refused = render.toggleDebugDraw(null)
        assertTrue(refused is AgentResult.Failed)
        assertEquals("no_render_context", refused.error.kind.id)

        // ...and the diff still answers.
        assertContains(ok(ArtifactToolset(store).compareArtifacts(a.value, b.value, 0)), """"identical":true""")
    }

    private fun ok(result: AgentResult): String {
        assertTrue(result is AgentResult.Ok, "expected success, got $result")
        return result.json
    }

    private fun png(image: BufferedImage): ByteArray {
        val out = ByteArrayOutputStream()
        check(ImageIO.write(image, "png", out)) { "no PNG writer" }
        return out.toByteArray()
    }

    private fun solid(w: Int, h: Int, argb: Int) =
        BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB).also { image ->
            for (y in 0 until h) for (x in 0 until w) image.setRGB(x, y, argb)
        }

    private fun gradient(w: Int, h: Int) =
        BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB).also { image ->
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val v = (y * 255) / (h - 1)
                    image.setRGB(x, y, (0xFF shl 24) or (v shl 16) or (v shl 8) or v)
                }
            }
        }

    init {
        // ImageIO's disk cache would write into the JVM's temp directory during a test that is
        // otherwise entirely inside @TempDir. Nothing here is large enough to need it.
        ImageIO.setUseCache(false)
        check(Files.exists(Path.of(System.getProperty("java.io.tmpdir"))))
    }
}
