package dev.wildware.udea.core.scene

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.EntityCreateContext
import com.github.quillraven.fleks.World
import dev.wildware.udea.core.GameContext
import dev.wildware.udea.core.SceneId
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.identity.NetIdIndex

/**
 * The contents of one level, as code that can run with no `Gdx.app` and no window.
 *
 * ## What it replaces
 *
 * Level loading happened inside a `Gdx.app.postRunnable` in `GameScreen`'s `init`
 * (`common/UdeaGameManager.kt:191-220`): entities were spawned on some later frame, a `started`
 * flag (`:91`) made `render()` return early until then (`:224`), and the world was therefore
 * silently empty for one or more frames. It could not be awaited, it could not be tested, and
 * it needed a LibGDX `Application` to happen at all.
 *
 * [populate] is an ordinary synchronous function. `SceneManager` runs it inside one
 * `SimBarrier` action, so the tick that follows a swap sees a fully populated world — there is
 * no flag and no empty frame to guard against.
 *
 * ## Determinism
 *
 * Entity creation order decides entity ids, entity ids decide [NetId]s, and [NetId] order
 * decides snapshot row order and the determinism hash. So [populate] must create entities in
 * an order that depends only on [seed] and the scene's own data — never on a `HashMap`
 * iteration, a file listing, or anything a wall clock touched. [SceneScope.spawn] is the
 * sanctioned way to create one, because it allocates the id at the same moment.
 */
public interface Scene {

    /** Stable identity. Carried in snapshots, so a restore can refuse a foreign scene. */
    public val id: SceneId

    /**
     * The seed every random choice in [populate] must derive from.
     *
     * Separate from `EngineConfig.seed` so that reloading one scene reproduces that scene
     * exactly, whatever the simulation drew before it.
     */
    public val seed: Long

    /** Creates this scene's entities. Runs once, synchronously, inside the swap barrier action. */
    public fun populate(scope: SceneScope)
}

/**
 * What a [Scene] is given while it populates.
 *
 * A parameter object rather than a context receiver: context parameters are still experimental
 * in Kotlin 2.2, and this is a public cross-module contract that other modules compile
 * against.
 */
public class SceneScope internal constructor(
    /** The world being populated. Empty when [Scene.populate] starts. */
    public val world: World,
    /** The context every system will be built against. */
    public val ctx: GameContext,
    /** Where ids are minted. Cleared before populate, so ids start from the bottom every time. */
    public val netIds: NetIdIndex,
    /** The scene's own seed, forwarded so a populate body need not reach back for it. */
    public val seed: Long,
) {

    /** How many entities [spawn] has created in this populate. */
    public var spawnCount: Int = 0
        private set

    /**
     * Creates an entity and gives it a [NetId] in one step.
     *
     * One step because two steps is where the bug lives: an entity created now and registered
     * later has a window in which it exists without an identity, and if anything reorders
     * those registrations the ids stop matching between two runs of the same scene.
     *
     * @return the id, which is what everything above the ECS speaks.
     */
    public fun spawn(configure: EntityCreateContext.(Entity) -> Unit): NetId {
        val entity = world.entity(configure)
        spawnCount++
        return netIds.allocate(entity)
    }

    override fun toString(): String = "SceneScope(seed=$seed, spawned=$spawnCount)"
}
