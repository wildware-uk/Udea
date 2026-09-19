package dev.wildware.udea.agent.tools

import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.level.LevelService
import dev.wildware.udea.core.loop.TimeControl

/**
 * What `editor.play` and `editor.stop` need (issue #196): the game's [LevelService], which encodes
 * the world before Play and puts it back at Stop, and the [TimeControl] that starts and stops it.
 *
 * One value, as [EditorLevelStore] is, because the two are only meaningful together. An
 * [EditorToolset] built without one refuses both tools with `no_play`.
 */
public class EditorPlay(
    /** `UdeaGame.levels`. */
    internal val levels: LevelService,
    /** The host's `time`: the same instance `time.*` drives. */
    internal val time: TimeControl,
) {
    override fun toString(): String = "EditorPlay(paused=${time.paused})"
}

/**
 * A play under way: what `editor.stop` puts back.
 *
 * [level] is **bytes**, the whole world encoded by `LevelService.saveNow`, and never Fleks'
 * `snapshot()` map. That map holds the very component objects the world holds, and the running game
 * goes on changing those objects in place - a map kept for Stop would have been altered by every
 * tick of play, and would put back the world as it had become rather than as it was.
 */
internal class PlaySession(
    /** The world when Play was pressed, as a level. */
    val level: ByteArray,
    /** The tick Play was pressed on, and the tick Stop puts the clock back to. */
    val tick: Tick,
    /** Every author's undo history when Play was pressed. */
    val history: HistoryMark,
) {
    override fun toString(): String = "PlaySession(tick=${tick.value}, ${level.size} bytes)"
}
