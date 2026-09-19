package dev.wildware.moba.entry

import com.github.quillraven.fleks.World.Companion.family
import dev.wildware.moba.MobaScene
import dev.wildware.moba.Player
import dev.wildware.moba.level.TestLevelScene
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.module.CoreModule
import dev.wildware.udea.core.module.UdeaGameDef

/**
 * What every entry point does in common, so the launchers differ only where spec 3.5 says they may.
 *
 * ## Why this is in `:moba:game` and the launcher half is not
 *
 * It used to be one object, `MobaEntry`, holding both this and the LWJGL3 boot sequence. Issue #212
 * split the game from its launchers, and the line fell here: everything below runs with no render
 * context, no window and no system property, so the dedicated server, an Android build and a
 * browser build can all call it. `MobaLaunch`, in `:moba:desktop`, is the half that opens a Kool
 * context and blocks on it, and nothing in this module can name it.
 *
 * The thing worth stating is what did *not* move: `MobaGame.definition()` takes no mode, no role
 * and no branch on either, so there is no arrangement in which "the server" and "the agent's
 * instance" simulate differently, and the split cannot introduce one.
 */
public object MobaEntry {

    /**
     * What `moba` draws: one animated champion per unit, at that unit's position.
     *
     * Delegates to [MobaScene], which carries the reasoning. The short version is that this
     * returned an **empty** registry until the Phase 1 demo was driven end to end, and an empty
     * registry makes that demo's image diff structurally incapable of showing anything: every
     * capture is the same cleared framebuffer whatever the simulation is doing.
     */
    public fun scene(definition: UdeaGameDef): MobaScene = MobaScene.build(definition)

    /**
     * Loads `level/test_level` and runs the tick that applies the swap.
     *
     * Shared by every entry point, which is the point of it: `runServer`, `runClient`, the agent's
     * instance and the phone build load the **same scene** over the same `Simulation`, so a fight
     * that unfolds one way in a capture and another way on the server would be a real defect rather
     * than two games that were never the same to begin with.
     *
     * A scene swap is a `BarrierAction`: `requestScene` submits, and the next tick is what clears
     * the world, resets the net ids and populates. So the `run(1)` is not a nicety - a caller that
     * skipped it would read an empty world from `/state` and could not tell that from a level that
     * failed to spawn. `MobaGame.definition` is where the scene was registered under this id;
     * requesting an unregistered one throws with the known ids in the message.
     */
    public fun seed(host: GameHost): NetId {
        host.ctx.scenes.requestScene(TestLevelScene.ID)
        host.run(1)
        // The player is the level's own `player` entity, not one more unit spawned beside it:
        // `TestLevelScene` puts the `Player` component on the authored entity of that name, so the
        // unit a human drives is the one standing in the orc clearing where the old game dropped
        // it. Resolved from the world rather than returned by the swap, because a scene swap is a
        // barrier action and the entities do not exist until the tick above drained it.
        return playerId(host)
    }

    /**
     * The net id of the level's player unit.
     *
     * Exactly one, and a failure when there is not: zero means the level lost its `player` entity
     * or the override that marks it, and two means something spawned a second one - and both of
     * those end as "the camera follows the wrong soldier", which is the kind of bug that gets
     * blamed on the camera. `render.follow_entity` is handed whatever this returns, so the refusal
     * belongs here rather than in the rig.
     */
    public fun playerId(host: GameHost): NetId {
        val players = host.world.family { all(Player) }
        val entities = players.entities
        check(entities.size == 1) {
            "the level must contain exactly one Player, and this world has ${entities.size}; " +
                "`TestLevelScene` marks the authored entity named " +
                "'${TestLevelScene.PLAYER_ENTITY}'"
        }
        return host.ctx[CoreModule.NET_IDS].netIdOf(entities[0])
    }

    /**
     * The player's net id, or `null` when the world has no player in it right now.
     *
     * [playerId]'s refusal is right at boot - a level that lost its player is a broken level and
     * saying so loudly is worth a crash. It is wrong on a **restart**: a match swap tears the world
     * down and repopulates it inside one barrier action, and a frame callback that asked during
     * that window would kill a player's client over a state that lasts one tick. So the per-frame
     * caller uses this one and the boot caller keeps the check.
     */
    public fun playerIdOrNull(host: GameHost): NetId? {
        val entities = host.world.family { all(Player) }.entities
        if (entities.size != 1) return null
        return host.ctx[CoreModule.NET_IDS].netIdOf(entities[0])
    }
}
