package dev.wildware.udea.agent.tools

import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.level.LevelService
import dev.wildware.udea.core.loop.TimeControl
import kotlin.jvm.JvmInline

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
    /**
     * Every entity live when Play was pressed, by `NetId`: the ones Stop brings back, and so the only
     * ones a play edit can be kept on. An entity spawned during Play has an id outside this set - a
     * reused index comes back with a new generation - however it got there.
     */
    val existing: Set<NetId>,
) {

    /** The play edits `editor.keep` has marked, which Stop re-applies. */
    val kept: MutableSet<PlayEditId> = LinkedHashSet()

    override fun toString(): String = "PlaySession(tick=${tick.value}, ${level.size} bytes, ${kept.size} kept)"
}

/**
 * Which play edit `editor.keep` and `editor.unkeep` mean: the edit's place in the order every
 * author's edits were made in ([EditorEdit.sequence]), the number `editor.history` lists it under
 * too, so an agent can name a play edit it read in either.
 */
@JvmInline
internal value class PlayEditId(val sequence: Long) {
    override fun toString(): String = "play edit #$sequence"
}
