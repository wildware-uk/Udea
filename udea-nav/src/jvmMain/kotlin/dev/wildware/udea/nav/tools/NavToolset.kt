package dev.wildware.udea.nav.tools

import dev.wildware.udea.agent.AgentErrorKind
import dev.wildware.udea.agent.AgentResult
import dev.wildware.udea.agent.Json
import dev.wildware.udea.annotations.AgentTool
import dev.wildware.udea.annotations.Arg
import dev.wildware.udea.nav.NavCell
import dev.wildware.udea.nav.NavGrid
import dev.wildware.udea.nav.NavPath
import dev.wildware.udea.nav.NavRouteOutcome
import dev.wildware.udea.nav.Navigation

/**
 * `nav.*`: what the ground looks like to the pathfinder, and what route it would give.
 *
 * ## Why an agent needs this at all
 *
 * A unit that will not go where it was sent looks identical from the outside whatever the cause -
 * the goal is inside a building, the gap is too narrow for that unit, the grid still has a
 * demolished building on it, or the route is simply long. `world.describe_entity` shows the order
 * and the position and says nothing about any of them. These two tools answer the question
 * directly: `nav.path` gives the route the units themselves would take, or names why there is
 * none, and `nav.grid` reports what the pathfinder currently believes is standing.
 *
 * It is the same [Navigation] the simulation uses and the same A* - `NavPathfinderTest` pins that a
 * unit's next step is the second cell of this route - so a reported route is the route, not a
 * second implementation that could drift from it. What it cannot promise is the exact cells a unit
 * will pass through: a unit re-asks from every cell it enters, open ground holds many routes of
 * identical length, and a crowd shoulders its members off the line anyway. The length and the
 * destination are what hold.
 *
 * ## Read-only
 *
 * Neither tool moves anything or changes the grid. An order is given with the components, through
 * `world.set_component_field` or a game's own tool, and the grid is the buildings.
 */
public class NavToolset(
    /** The live navigation service, as `ctx[NavModule.NAVIGATION]`. */
    private val navigation: Navigation,
) {

    /** Reused across calls: the toolset is called on the simulation thread, one call at a time. */
    private val path = NavPath()

    /**
     * The route a unit of a given size would take between two points on the ground plane.
     */
    @AgentTool(
        name = "nav.path",
        description = "Find the route a ground unit of the given radius would take from one point " +
            "to another, over the live nav grid. Answers the cells it passes through in order, " +
            "their world positions, and what the route costs; or, when there is none, which of " +
            "off-grid, blocked start, blocked goal or no-route it was. Reads the same A* the units " +
            "do and changes nothing.",
    )
    public fun path(
        @Arg(description = "World x the route starts at, in metres.")
        fromX: Float,
        @Arg(description = "World y the route starts at, in metres.")
        fromY: Float,
        @Arg(description = "World x the route ends at, in metres.")
        toX: Float,
        @Arg(description = "World y the route ends at, in metres.")
        toY: Float,
        @Arg(
            description = "The unit's radius in metres, which decides the gaps it fits through.",
            default = "0.5",
        )
        radius: Float = 0.5f,
    ): AgentResult {
        if (radius <= 0f) {
            return AgentResult.failed(AgentErrorKind.BAD_ARGUMENT, "radius must be positive, not $radius")
        }
        val outcome = navigation.path(fromX, fromY, toX, toY, radius, path)
        val grid = navigation.grid
        return AgentResult.ok {
            put("outcome", outcome.name)
            put("found", outcome == NavRouteOutcome.Found)
            put("reason", reasonFor(outcome, grid))
            put("clearanceCells", grid.clearanceCellsFor(radius))
            put("cost", path.cost)
            put("cells", path.size)
            // The route as world points, which is what a caller draws or compares against a unit's
            // position; the cell index is there too, because that is what the grid tools speak.
            arr("route") {
                for (step in 0 until path.size) {
                    element {
                        val cell = path.cellAt(step)
                        put("cell", cell.index)
                        put("column", grid.cellX(cell))
                        put("row", grid.cellY(cell))
                        put("x", grid.centreX(cell))
                        put("y", grid.centreY(cell))
                    }
                }
            }
        }
    }

    /**
     * What the pathfinder thinks is standing: the grid's shape, and the blocked ground in a window
     * of it.
     */
    @AgentTool(
        name = "nav.grid",
        description = "Report the nav grid - where it sits, how finely it is cut, and how much of " +
            "it is blocked - plus a row-by-row picture of the cells in a window around a point, " +
            "with '#' for ground a unit of the given radius cannot stand on and '.' for ground it " +
            "can. The grid is rebuilt from the buildings in the world, so this is what the " +
            "pathfinder currently believes is there.",
    )
    public fun grid(
        @Arg(description = "World x the window is centred on, in metres.", default = "0")
        centreX: Float = 0f,
        @Arg(description = "World y the window is centred on, in metres.", default = "0")
        centreY: Float = 0f,
        @Arg(description = "How many cells out from the centre the window reaches. At most 32.", default = "12")
        halfSpan: Int = 12,
        @Arg(
            description = "The unit's radius in metres, which decides which cells count as blocked for it.",
            default = "0.5",
        )
        radius: Float = 0.5f,
    ): AgentResult {
        if (halfSpan < 0 || halfSpan > MAX_HALF_SPAN) {
            return AgentResult.failed(
                AgentErrorKind.BAD_ARGUMENT,
                "halfSpan must be between 0 and $MAX_HALF_SPAN, not $halfSpan",
            )
        }
        if (radius <= 0f) {
            return AgentResult.failed(AgentErrorKind.BAD_ARGUMENT, "radius must be positive, not $radius")
        }
        val grid = navigation.grid
        val clearanceCells = grid.clearanceCellsFor(radius)
        val centre = grid.cellAt(centreX, centreY)
        var blocked = 0
        for (index in 0 until grid.cellCount) if (grid.isBlocked(NavCell(index))) blocked++
        return AgentResult.ok {
            put("width", grid.width)
            put("height", grid.height)
            put("cellSize", grid.cellSize)
            put("originX", grid.originX)
            put("originY", grid.originY)
            put("blockedCells", blocked)
            put("clearanceCells", clearanceCells)
            put("centreCell", centre.index)
            if (!centre.isValid) {
                put("window", "the point ($centreX, $centreY) is off the grid")
            } else {
                arr("window") {
                    val column = grid.cellX(centre)
                    val row = grid.cellY(centre)
                    // Top row first, so the picture reads the way the map looks with y up.
                    for (pictureRow in row + halfSpan downTo row - halfSpan) {
                        val line = StringBuilder(halfSpan * 2 + 1)
                        for (pictureColumn in column - halfSpan..column + halfSpan) {
                            val cell = grid.cellOf(pictureColumn, pictureRow)
                            line.append(if (grid.fits(cell, clearanceCells)) '.' else '#')
                        }
                        value(line.toString())
                    }
                }
            }
        }
    }

    private fun reasonFor(outcome: NavRouteOutcome, grid: NavGrid): String = when (outcome) {
        NavRouteOutcome.Found -> "there is a route"
        NavRouteOutcome.StartOffGrid ->
            "the start is off the nav grid, which covers ${grid.width}x${grid.height} cells of " +
                "${grid.cellSize}m from (${grid.originX}, ${grid.originY})"

        NavRouteOutcome.GoalOffGrid ->
            "the goal is off the nav grid, which covers ${grid.width}x${grid.height} cells of " +
                "${grid.cellSize}m from (${grid.originX}, ${grid.originY})"

        NavRouteOutcome.StartBlocked ->
            "the start is under a building and there is no ground a unit this size fits on near it"

        NavRouteOutcome.GoalBlocked ->
            "the goal is under a building and there is no ground a unit this size fits on near it"

        NavRouteOutcome.NoRoute ->
            "both ends are standable and nothing joins them - a unit this size cannot get from " +
                "one to the other; try a smaller radius, which fits through narrower gaps"
    }

    private companion object {
        /**
         * The widest window `nav.grid` will draw, in cells from the centre.
         *
         * 32 either way is a 65-character line and 65 of them, which is about as much as is worth
         * reading in one answer. A caller that wants more of the map asks about another point.
         */
        const val MAX_HALF_SPAN: Int = 32
    }
}
