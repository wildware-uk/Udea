package dev.wildware.udea.editor

import dev.wildware.udea.core.blueprint.BlueprintId

/**
 * What the Create panel's button spawns, and where: one `editor.spawn` call's arguments.
 *
 * The game's to choose, because the engine knows no blueprint by name and no world point worth
 * spawning at - `moba` hands in a unit and the middle of its map.
 */
public class EditorSpawn(
    /** The button's label, e.g. "Spawn skeleton at centre". */
    internal val label: String,
    /** The blueprint `editor.spawn` builds, as `world.list_blueprints` spells it. */
    internal val blueprint: BlueprintId,
    /** World x to place it at. */
    internal val x: Float,
    /** World y to place it at. */
    internal val y: Float,
) {
    init {
        require(label.isNotBlank()) { "a spawn button needs a label" }
    }

    override fun toString(): String = "EditorSpawn(${blueprint.value} at $x, $y)"
}
