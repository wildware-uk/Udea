package dev.wildware.udea.assets.compiler.pack

import dev.wildware.udea.assets.AssetId
import dev.wildware.udea.assets.Blueprint
import dev.wildware.udea.assets.GameConfig
import dev.wildware.udea.assets.Level
import dev.wildware.udea.assets.SoundCue
import dev.wildware.udea.assets.SpriteSheet
import dev.wildware.udea.assets.compiler.TestPaths
import dev.wildware.udea.assets.pack.BundleReader
import dev.wildware.udea.assets.pack.BundleSource
import dev.wildware.udea.assets.pack.OpaqueAsset
import dev.wildware.udea.assets.reference
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The writer's output, read back by `udea-assets`, is the graph that went in.
 *
 * Deliberately end to end: it compiles the five fixture scripts, packs the result, and opens
 * the bytes with the *runtime* reader. A test that compared `PackedAsset` trees on both sides
 * would pass with the serialiser entirely broken.
 */
class BundleRoundTripTest {

    private fun bundle() = BundleReader.open(
        PackFixture.bundle(TestPaths.repoRoot, PackFixture.assetRoot, "roundtrip"),
    )

    @Test
    fun `every declared asset comes back, in sorted id order`() {
        bundle().use { bundle ->
            assertEquals(
                PackFixture.EXPECTED_IDS.sorted(),
                bundle.registry.ids.map { it.value },
                "the graph read back is not the corpus that was packed",
            )
        }
    }

    @Test
    fun `a sprite sheet comes back typed, with the DSL field names translated`() {
        bundle().use { bundle ->
            val sheet = bundle.registry[reference<SpriteSheet>("character/orc_idle")]

            assertEquals(AssetId("character/orc_idle"), sheet.id)
            // `spriteSheet(spritePath = "/sprites/orc/idle.png")` in the script; `texture` on
            // the model; the leading slash removed by the DSL's own resPath.
            assertEquals("sprites/orc/idle.png", sheet.texture.value)
            assertEquals(6, sheet.columns)
            assertEquals(1, sheet.rows)
            assertEquals(0.02F, sheet.scale)
        }
    }

    @Test
    fun `a sound cue's paths come back as ResPath`() {
        bundle().use { bundle ->
            val cue = bundle.registry[reference<SoundCue>("character/orc_attack_cue")]

            assertEquals(listOf("sounds/orc/attack.ogg"), cue.sounds.map { it.value })
            assertEquals(0.8F, cue.pitchVariance)
        }
    }

    /**
     * The reference is bound to a slot, and resolving it is that slot.
     *
     * `resolvedIndex` is non-null *before anything asks the registry for it*, which is the
     * acceptance criterion: "every `Ref.index` is non-negative and resolution needs no string
     * lookup". A `Ref` that resolved lazily would be null here and would still pass a test that
     * only checked `registry[ref]`.
     */
    @Test
    fun `references come back already bound to a non-negative slot`() {
        bundle().use { bundle ->
            val config = bundle.registry[reference<GameConfig>("config")]
            val target = assertNotNull(config.defaultCharacter, "config declares a defaultCharacter")

            val index = assertNotNull(
                target.resolvedIndex,
                "the packed reference was not bound when the bundle was opened",
            )
            assertTrue(index.value >= 0)
            assertEquals(AssetId("blueprint/player"), bundle.registry.at(index).id)
            assertSame(
                bundle.registry.at(index),
                bundle.registry[target],
                "resolving the ref did not return the asset at its bound slot",
            )
        }
    }

    /** Not one reference in the whole graph is left unbound. */
    @Test
    fun `every reference in the graph is bound`() {
        bundle().use { bundle ->
            val unbound = bundle.registry.ids
                .mapNotNull { bundle.registry.find(it) }
                .flatMap { refsIn(it) }
                .filter { it.resolvedIndex == null }

            assertTrue(unbound.isEmpty(), "these references resolve by string: $unbound")
        }
    }

    private fun refsIn(asset: dev.wildware.udea.assets.AssetData): List<dev.wildware.udea.assets.Ref<*>> =
        when (asset) {
            is OpaqueAsset -> asset.fields.values.flatMap { refsIn(it) }
            is GameConfig -> listOfNotNull(asset.defaultCharacter, asset.defaultLevel)
            is Level -> asset.entities.mapNotNull { it.blueprint }
            is dev.wildware.udea.assets.SpriteAnimation -> listOf(asset.sheet)
            is dev.wildware.udea.assets.SpriteAnimationSet -> asset.animations
            else -> emptyList()
        }

    private fun refsIn(value: dev.wildware.udea.assets.AssetValue): List<dev.wildware.udea.assets.Ref<*>> =
        when (value) {
            is dev.wildware.udea.assets.AssetValue.RefValue -> listOf(value.value)
            is dev.wildware.udea.assets.AssetValue.ListValue -> value.values.flatMap { refsIn(it) }
            is dev.wildware.udea.assets.AssetValue.StructValue -> value.fields.values.flatMap { refsIn(it) }
            else -> emptyList()
        }

    @Test
    fun `a blueprint's parent survives as an inheritance edge`() {
        bundle().use { bundle ->
            val spawn = bundle.registry[reference<Blueprint>("blueprint/minion")]

            assertEquals(listOf(AssetId("blueprint/player")), spawn.inheritedFrom)
            assertEquals(listOf("ai"), spawn.components.map { it.type.value })
        }
    }

    @Test
    fun `a level's entities come back as blueprint references`() {
        bundle().use { bundle ->
            val level = bundle.registry[reference<Level>("level/arena")]

            assertEquals(4, level.entities.size, "three minions and a player")
            assertEquals(
                listOf("blueprint/minion", "blueprint/minion", "blueprint/minion", "blueprint/player"),
                level.entities.map { assertNotNull(it.blueprint).id.value },
            )
        }
    }

    /**
     * A kind the runtime has no type for is readable, not lost and not fatal.
     *
     * `character` is the live example: `AssetScope` declares it, `udea-assets` has no
     * `Character`, and `AssetKind.Unpublishable` refuses to guess one. The fields still arrive.
     */
    @Test
    fun `an unpublishable kind reads back as an OpaqueAsset with its fields intact`() {
        bundle().use { bundle ->
            val orc = assertIs<OpaqueAsset>(bundle.registry.find(AssetId("character/orc")))

            assertEquals("character", orc.kind)
            assertTrue("health" in orc.fields, "the fields of an opaque asset are kept")
            assertEquals(
                dev.wildware.udea.assets.AssetValue.FloatValue(500F),
                orc.fields["health"],
            )
            val animations = assertIs<dev.wildware.udea.assets.AssetValue.ListValue>(orc.fields["animations"])
            assertEquals(1, animations.values.size, "orc names one animation")
        }
    }

    @Test
    fun `the content hash in the header is the hash of the body`() {
        val bytes = PackFixture.bundle(TestPaths.repoRoot, PackFixture.assetRoot, "roundtrip-hash")

        BundleSource.of(bytes).use { source ->
            assertTrue(BundleReader.verifyContentHash(source), "the stored hash is not the body's hash")
        }
    }

    @Test
    fun `flipping one body byte makes the content hash disagree`() {
        val bytes = PackFixture.bundle(TestPaths.repoRoot, PackFixture.assetRoot, "roundtrip-tamper")
        val tampered = bytes.copyOf()
        tampered[tampered.size - 1] = (tampered[tampered.size - 1] + 1).toByte()

        BundleSource.of(tampered).use { source ->
            assertTrue(!BundleReader.verifyContentHash(source), "the hash accepted a tampered body")
        }
    }
}
