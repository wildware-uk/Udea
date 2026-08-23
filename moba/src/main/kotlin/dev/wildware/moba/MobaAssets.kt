package dev.wildware.moba

import dev.wildware.udea.assets.AssetRegistry
import dev.wildware.udea.assets.pack.AtlasIndex
import dev.wildware.udea.assets.pack.Bundle
import dev.wildware.udea.assets.pack.BundleReader

/**
 * The `.udeapak` this process was built with, opened once.
 *
 * ## What this replaces
 *
 * `ChampionRenderSystem` used to call `Gdx.files.classpath("assets/sprites/champions/archer/idle.png")`,
 * upload it, divide `texture.width / 100` and slice the result into frames on the render thread.
 * Three separate things were wrong with that and only the first is obvious:
 *
 * - the frame grid was decided **at runtime**, so a sheet whose dimensions changed silently
 *   produced frames that blitted their neighbours;
 * - one texture per sheet meant one GL bind per unit type per draw, which is the cost the
 *   pack-time atlas exists to remove (`AtlasIndex`);
 * - the world size of a champion was a constant in the renderer (`HEIGHT = 34f`), so no authored
 *   value could change it and an artist had no way to say how big their art was.
 *
 * All three are pack-time facts now. [atlas] says where every frame is, [registry] carries the
 * authored `SpriteSheet.scale`, and the renderer multiplies rather than divides.
 *
 * ## Why it is process-wide
 *
 * A bundle is immutable and one process ships exactly one, so two `GameHost`s over one JVM read
 * the same graph rather than each decoding their own copy - which at 23KB is a small saving and
 * at a shipped game's size is the difference between a warm start and a cold one.
 *
 * The [registry] is process-wide for a stronger reason: it is the object a hot reload mutates.
 * `AssetHotReload` swaps values into it at the top of a `Simulation.step`, at slots that are
 * stable across the swap, and a second registry would leave the daemon patching a graph nothing
 * is drawing from. See `MobaAgent`.
 *
 * ## When the bundle is not there
 *
 * There is no fallback and that is deliberate. The old renderer printed a warning and drew a
 * white texel when its PNG was missing, which meant a capture on a machine with no art looked
 * like a working game with plain art. A missing bundle is a build that did not run
 * `udeaPackBundle`, and the honest response is a failure that names the task.
 */
public object MobaAssets {

    /**
     * Where `processResources` puts the bundle.
     *
     * Written once here and once as `UdeaAssetsPlugin.BUNDLE_RESOURCE_DIRECTORY` plus the
     * extension's `bundleName`; the two cannot be checked against each other by the compiler, so
     * `MobaAssetsTest` opens this exact resource and fails when they drift.
     */
    public const val RESOURCE: String = "udea/assets.udeapak"

    /**
     * The opened bundle.
     *
     * `by lazy` rather than eager: `MobaGame.componentRegistry` and the entry points are touched
     * by tests that have no reason to decode an asset graph, and a top-level `val` would make
     * every one of them pay for it.
     */
    public val bundle: Bundle by lazy { open() }

    /** The decoded graph, with every reference already bound to its slot. */
    public val registry: AssetRegistry get() = bundle.registry

    /** Where every sprite frame landed at pack time. */
    public val atlas: AtlasIndex get() = bundle.atlas

    private fun open(): Bundle {
        val stream = MobaAssets::class.java.classLoader.getResourceAsStream(RESOURCE)
            ?: error(
                "$RESOURCE is not on the classpath. It is written by `:moba:udeaPackBundle` and " +
                    "copied into the resources by `processResources`; a run that skipped either " +
                    "has no assets at all. There is deliberately no fallback - see MobaAssets.",
            )
        // Read whole and hand the bytes over, rather than opening a `BundleSource` on a path: the
        // bundle is inside a jar on a packaged run, so there is no file to seek in. Streaming
        // exists for a bundle beside the executable and this game's is 23KB.
        val bytes = stream.use { it.readBytes() }
        return BundleReader.open(bytes)
    }
}
