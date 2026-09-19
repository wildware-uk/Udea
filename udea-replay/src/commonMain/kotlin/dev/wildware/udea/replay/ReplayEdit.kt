package dev.wildware.udea.replay

import dev.wildware.udea.core.Tick

/**
 * One call made to the world between ticks - an `editor.*` edit - as a recording carries it.
 *
 * ## Why a recording needs these at all
 *
 * A `.udearep` reproduces a match by feeding each tick's input back to a fresh world. An edit is
 * not input: an agent or the editor window makes it through the `SimBarrier`, which applies it at
 * the top of a tick before any system runs. So a world somebody edited cannot be reproduced from
 * its input alone, and a recording of an editing session carries each edit beside the input, with
 * the tick it was applied on (issue #232).
 *
 * The edit is kept as the call that made it - the tool, its author and its arguments, all as the
 * agent surface spells them - rather than as the fields it wrote, because the call is what a
 * replay can make again through the same tool, and a second description of an edit beside the
 * tool is the kind of thing that drifts. This module never interprets one: it stores it, hands it
 * back at its tick, and a game's [ReplayWorld.applyEdits] submits it.
 */
public data class ReplayEdit(
    /** The tick whose barrier drain applied it: before any system ran on that tick. */
    public val tick: Tick,
    /** Who made it, as the agent host's session table labels them. */
    public val author: String,
    /** The tool, as an agent calls it: `editor.update_edit`. */
    public val tool: String,
    /** Its arguments, verbatim. Written in name order, so a recording encodes to the same bytes whatever order they arrived in. */
    public val args: Map<String, String>,
) {

    init {
        require(tool.isNotBlank()) { "a recorded edit names the tool that made it" }
        require(args.size <= ReplayFormat.MAX_EDIT_ARGS) {
            "$tool carries ${args.size} arguments, past the ${ReplayFormat.MAX_EDIT_ARGS} a recorded edit may"
        }
    }

    override fun toString(): String = "$tick $author $tool $args"
}
