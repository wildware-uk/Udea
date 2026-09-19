package dev.wildware.udea.core.level

import dev.wildware.udea.core.SceneId
import dev.wildware.udea.core.scene.Scene
import dev.wildware.udea.core.scene.SceneScope

/**
 * A level file, used as a [Scene]: every swap to [id] puts the level's entities back (issue #192).
 *
 * ## Why a scene, and not only [LevelService.load]
 *
 * A game that restarts a match in-process swaps its scene back in, and a level that is content
 * rather than code has to be reloadable that way. A scene swap is also what gives the world an
 * active scene id, which snapshot restore is gated on.
 *
 * ## What a swap restores, and what it leaves alone
 *
 * The entities and their components, with a physics body rebuilt for each that declares one. It
 * does **not** move the clock, restore the random streams or put back a module's [LevelSection]:
 * a swap happens in the middle of a running game, and a restart that wound the clock back or
 * replayed the streams from the level would make every match the same match. That is what
 * [LevelService.load] is for - the whole saved moment, clock and streams included, for a game
 * that starts from a level.
 *
 * `NetId`s are minted fresh from index zero in the order the level saved them, exactly as a scene
 * that spawns its entities mints them. So a level saved from a freshly populated world comes back
 * on the indices it was saved with, and an id held from before the swap reads stale.
 *
 * ## When the bytes are wrong
 *
 * The level is decoded and checked against the running game on every swap. Bytes that are not a
 * level this game can load throw [LevelFormatException] from [populate], which the scene manager
 * turns into an empty world with no active scene and a failed-swap count.
 */
public class LevelScene(
    override val id: SceneId,
    private val bytes: ByteArray,
) : Scene {

    /** A level draws nothing at random while it populates, so nothing derives from this. */
    override val seed: Long = 0L

    override fun populate(scope: SceneScope) {
        val levels = checkNotNull(scope.levels) {
            "scene $id is a level file, and this scene manager was never given the game's " +
                "LevelService; UdeaGameDef.build() is what wires it"
        }
        levels.populate(levels.read(bytes), scope)
    }

    override fun toString(): String = "LevelScene($id, ${bytes.size} bytes)"
}
