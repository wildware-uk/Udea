package dev.wildware.udea.render.model

import dev.wildware.udea.assets.AssetIndex

/**
 * Where a `Drawn` slot becomes something drawable (issue #270).
 *
 * The simulation says *which asset* an entity draws, as an [AssetIndex] on a
 * `dev.wildware.udea.core.spatial.Drawn`; this says what that asset looks like once it is
 * loaded. The split is the whole point: the slot crosses the wire and goes into snapshots, and
 * the loaded model - meshes, materials, textures with GL handles behind them - never leaves the
 * renderer.
 *
 * `FileModelLibrary` on the JVM is the implementation a desktop game uses, and it reads each
 * file once. A game only writes one of these for something the engine cannot know about: models
 * it generates, or an atlas it packs itself.
 *
 * ## Called on the render thread, per drawn entity, per frame
 *
 * [ModelRenderSystem] asks only when an entity's slot has changed since it last looked, so a
 * frame in which nothing changed asks nothing. It is still a lookup and not a load: a load that
 * happened on every frame would stall the frame it happened on.
 */
public fun interface ModelLibrary {

    /**
     * The model at [slot].
     *
     * @throws ModelLoadException when [slot] names nothing this library can draw - an asset that
     *   is not a model, or a model whose file cannot be read. Never a silent substitute: an
     *   entity that quietly draws nothing is a bug nobody finds, which is why `ImportedModel`
     *   makes the same choice.
     */
    public fun modelAt(slot: AssetIndex): ModelSource
}
