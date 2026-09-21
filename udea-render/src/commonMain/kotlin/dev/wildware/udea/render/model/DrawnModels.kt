package dev.wildware.udea.render.model

import com.github.quillraven.fleks.Family
import com.github.quillraven.fleks.World
import com.github.quillraven.fleks.World.Companion.family
import dev.wildware.udea.assets.AssetIndex
import dev.wildware.udea.core.spatial.Drawn

/**
 * Keeps every entity's [ModelRenderer] in step with the `Drawn` the simulation gave it (issue
 * #270).
 *
 * This is the loop every game on this engine was writing for itself, and getting subtly wrong
 * the first time: the simulation spawns a chassis and five modules and says what each one is,
 * and something has to turn that into a loaded model on the **render thread** without putting a
 * GL handle anywhere the simulation, a snapshot or a level file can see it.
 *
 * [ModelRenderSystem] owns one of these and runs it at the top of the frame it draws, so a part
 * spawned last tick is drawn this frame, and the thread is the render thread by construction
 * rather than by a game remembering (`docs/engineering-standards.md` section 2, issue #224).
 *
 * ## What it writes, and what it leaves alone
 *
 * - An entity with a `Drawn` and no [ModelRenderer] gets one.
 * - An entity whose `Drawn` now names a different model has its existing renderer **pointed at
 *   it**, never replaced - so `ModelRenderer.mask`, which a game sets for the outline pass,
 *   survives a model change.
 * - An entity whose `Drawn` names nothing has the model taken off it, but only if this put it
 *   there. A renderer a game attached by hand is the game's, and is left where it is.
 *
 * [ModelRenderer] is presentation state - never snapshotted, saved or replicated - so none of
 * this is visible to the simulation, to a replay or to a level.
 *
 * ## A frame in which nothing changed does nothing
 *
 * The slot each entity was last synced at is remembered by Fleks id, so a steady frame is one
 * int comparison per drawn entity: no lookup, no `configure`, no allocation.
 */
internal class DrawnModels(private val library: ModelLibrary) {

    private var world: World? = null
    private var drawn: Family? = null

    /** The slot each entity was last synced at, by Fleks id, or [UNMANAGED]. */
    private var syncedSlot = IntArray(INITIAL_ENTITIES) { UNMANAGED }

    /** Entities whose renderer was added or repointed since the last [sync]. What the tests count. */
    var changed: Int = 0
        private set

    fun bind(world: World) {
        this.world = world
        this.drawn = world.family { all(Drawn) }
    }

    /** Brings every `Drawn` entity's [ModelRenderer] up to date. Render thread. */
    fun sync() {
        val world = this.world ?: return
        val drawn = this.drawn ?: return
        changed = 0
        with(world) {
            drawn.forEach { entity ->
                val slot = entity[Drawn].model
                val renderer = entity.getOrNull(ModelRenderer)
                remember(entity.id)
                if (renderer != null && syncedSlot[entity.id] == slot) return@forEach
                if (slot == Drawn.NONE) {
                    // Only what this put there is taken away again; a renderer a game attached
                    // itself is the game's.
                    if (renderer != null && syncedSlot[entity.id] != UNMANAGED) {
                        entity.configure { it -= ModelRenderer }
                        changed++
                    }
                    syncedSlot[entity.id] = UNMANAGED
                    return@forEach
                }
                val wanted = library.modelAt(AssetIndex(slot))
                if (renderer == null) {
                    entity.configure { it += ModelRenderer(wanted) }
                } else {
                    renderer.model = wanted
                }
                syncedSlot[entity.id] = slot
                changed++
            }
        }
    }

    /** Makes room for [id] in [syncedSlot], filling the new tail with [UNMANAGED]. */
    private fun remember(id: Int) {
        if (id < syncedSlot.size) return
        val grown = IntArray(maxOf(id + 1, syncedSlot.size * 2)) { UNMANAGED }
        syncedSlot.copyInto(grown)
        syncedSlot = grown
    }

    override fun toString(): String = "DrawnModels($library)"

    private companion object {
        /**
         * An entity this has never given a model to. Not `Drawn.NONE`, which is a slot that
         * *this* cleared: the two have to be told apart or a renderer a game attached by hand
         * would be removed the first time it met a `Drawn` naming nothing.
         */
        const val UNMANAGED: Int = Int.MIN_VALUE

        /** Room for this many entities before the synced slots grow. */
        const val INITIAL_ENTITIES = 64
    }
}
