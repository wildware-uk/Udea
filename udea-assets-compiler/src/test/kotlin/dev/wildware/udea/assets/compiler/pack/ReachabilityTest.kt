package dev.wildware.udea.assets.compiler.pack

import dev.wildware.udea.assets.compiler.TestPaths
import dev.wildware.udea.assets.pack.BundleReader
import dev.wildware.udea.assets.pack.EntryClass
import dev.wildware.udea.assets.pack.SectionKind
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The per-level reachability set, and the eager/streamed split it decides.
 *
 * The point of the set is a loading bar denominated in **bytes**: the eager sections' lengths
 * are in the table of contents, so the denominator is known before the first read. The old
 * `GameAssetLoader` counted files.
 */
class ReachabilityTest {

    private fun packed(): List<PackedAsset> =
        GraphPacker.pack(PackFixture.compile(TestPaths.repoRoot, PackFixture.assetRoot, "reach")).assets

    @Test
    fun `the set rooted at gameConfig is what the config actually reaches`() {
        val assets = packed()

        val reached = Reachability.fromGameConfig(assets)

        // config -> blueprint/player. `blueprint/minion` names `player` as its parent, but
        // `inheritedFrom` is a list of ids rather than a `Ref` - the model deliberately does not
        // make ancestry resolvable without the registry - so it is not an edge and minion is not
        // reached from the config. That is the honest answer for this corpus rather than a
        // convenient one.
        assertEquals(listOf("blueprint/player", "config"), reached.assets)
    }

    @Test
    fun `a graph with no root marks everything eager rather than nothing`() {
        val withoutConfig = packed().filterNot { it.id == "config" }

        val reached = Reachability.fromGameConfig(withoutConfig)

        assertEquals(withoutConfig.map { it.id }.sorted(), reached.assets)
    }

    @Test
    fun `the set collects the resource paths its assets name`() {
        val assets = packed()
        // Root the walk at the sprite animation instead, which does reach a sheet, so the path
        // collection is exercised on an edge the config corpus does not have.
        val animation = assets.indexOfFirst { it.id == "character/orc_idle_anim" }

        val reached = Reachability.from(assets, listOf(animation))

        assertEquals(listOf("character/orc_idle", "character/orc_idle_anim"), reached.assets)
        assertEquals(listOf("sprites/orc/idle.png"), reached.paths)
    }

    /**
     * A blob the eager set names is `EAGER`; one it does not is `STREAMED`, and the reader
     * reports the two byte totals separately.
     */
    @Test
    fun `reachable blobs are eager and the rest stream`() {
        val assets = packed()
        val blobs = listOf(
            BundleSection(SectionKind.AUDIO, "audio/sounds/orc/attack.ogg", EntryClass.STREAMED, ByteArray(32)),
            BundleSection(SectionKind.BLOB, "blob/sprites/orc/idle.png", EntryClass.STREAMED, ByteArray(64)),
        )
        // Rooted at the animation, so exactly one of the two blobs is reachable.
        val animation = assets.indexOfFirst { it.id == "character/orc_idle_anim" }
        val reached = Reachability.from(assets, listOf(animation)).paths.toSet()
        val content = BundleContent(
            assets = assets,
            blobs = blobs,
            eagerBlobs = blobs.map { it.name }.filter { name -> reached.any { name.endsWith("/$it") } }.toSet(),
        )

        BundleReader.open(BundleWriter.write(content)).use { bundle ->
            assertEquals(EntryClass.EAGER, bundle.entry("blob/sprites/orc/idle.png")?.entryClass)
            assertEquals(EntryClass.STREAMED, bundle.entry("audio/sounds/orc/attack.ogg")?.entryClass)
            assertEquals(32L, bundle.streamedBytes, "only the unreachable blob streams")
            assertTrue(bundle.eagerBytes > 64, "the eager total covers the graph plus the reached blob")
        }
    }

    /** The factory does the same thing, so a caller does not have to reimplement the match. */
    @Test
    fun `BundleContent reachable classifies the blobs from the graph root`() {
        val assets = packed()
        val blobs = listOf(
            BundleSection(SectionKind.AUDIO, "audio/sounds/orc/attack.ogg", EntryClass.STREAMED, ByteArray(8)),
            BundleSection(SectionKind.BLOB, "blob/sprites/orc/idle.png", EntryClass.STREAMED, ByteArray(8)),
        )

        val content = BundleContent.reachable(assets, blobs = blobs)

        // Rooted at `config`, which reaches `blueprint/player` and no resource path at all, so
        // nothing is eager. Stated rather than glossed: for this corpus the reachable set is
        // genuinely small, and a factory that marked everything eager would look the same on a
        // corpus where it happened not to matter.
        assertEquals(emptySet(), content.eagerBlobs)
    }

    @Test
    fun `a reference to a slot outside the graph is refused rather than followed`() {
        val assets = packed()

        val failure = runCatching { Reachability.from(assets, listOf(assets.size)) }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException, "expected a rejected root, got $failure")
    }
}
