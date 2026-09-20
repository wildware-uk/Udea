package dev.wildware.udea.nav.shot

import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.nav.NavAgent
import dev.wildware.udea.nav.NavCell
import dev.wildware.udea.nav.NavFlowField
import dev.wildware.udea.nav.NavGrid
import dev.wildware.udea.nav.NavGridLayout
import dev.wildware.udea.nav.NavPath
import dev.wildware.udea.nav.NavScene
import dev.wildware.udea.nav.NavState
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Draws what navigation is doing, as PNGs: the grid, one unit's A* route, the flow field a group
 * shares, and a hundred units crossing the map at known ticks.
 *
 * Run by `:udea-nav:udeaNavShot`. It is in the **test** source set on purpose: a PNG encoder is not
 * part of a navigation library, and `udea-nav` must not grow a JVM-only image dependency for a
 * picture a reviewer looks at once. Nothing asserts here - `jvmTest` is what asserts; this is for
 * eyes.
 *
 * Every frame is a stated tick of the same simulation the tests run, so a tile can be named back to
 * a tick rather than to "somewhere in the middle".
 */
public object NavShotMain {

    @JvmStatic
    public fun main(args: Array<String>) {
        val out = File(argument(args, "--out") ?: "build/reports/udea/nav")
        out.mkdirs()
        val layout = NavGridLayout(originX = -16f, originY = -16f, cellSize = 0.5f, width = 64, height = 64)

        singleUnitRoute(layout, File(out, "issue264-astar-route.png"))
        groupField(layout, File(out, "issue264-flow-field.png"))
        crowd(layout, out)
        println("nav shots written to ${out.absolutePath}")
    }

    /** One unit, two buildings, and the A* route `nav.path` reports for it. */
    private fun singleUnitRoute(layout: NavGridLayout, file: File) {
        val scene = NavScene(layout)
        buildings(scene)
        val unit = scene.spawnUnit(x = -13f, y = -8f)
        scene.order(unit, x = 12f, y = 6f)
        scene.step()

        val path = NavPath()
        scene.navigation.path(-13f, -8f, 12f, 6f, radius = 0.3f, out = path)
        val grid = scene.navigation.grid
        draw(grid, file, "A* for one unit: ${path.size} cells, cost ${path.cost}") { canvas ->
            canvas.route(grid, path)
            canvas.units(scene, grid)
            canvas.goal(grid, 12f, 6f)
        }
    }

    /** The integration field a group shares, drawn as its descent to the goal. */
    private fun groupField(layout: NavGridLayout, file: File) {
        val scene = NavScene(layout)
        buildings(scene)
        scene.step()
        val grid = scene.navigation.grid
        val goal = grid.cellAt(12f, 0f)
        val field = NavFlowField(grid, goal, clearanceCells = grid.clearanceCellsFor(0.3f))
        draw(grid, file, "the flow field a group ordered to one point reads") { canvas ->
            canvas.field(grid, field)
            canvas.goal(grid, 12f, 0f)
        }
    }

    /** A hundred units crossing the map, one frame per named tick. */
    private fun crowd(layout: NavGridLayout, out: File) {
        val scene = NavScene(layout)
        val units = ArrayList<NetId>()
        for (row in 0 until 10) {
            for (column in 0 until 10) {
                units += scene.spawnUnit(x = -13f + column * 0.8f, y = -4f + row * 0.8f, radius = 0.3f)
            }
        }
        buildings(scene)
        for (unit in units) scene.order(unit, x = 12f, y = 0f)

        var tick = 0
        for (frame in FRAME_TICKS) {
            while (tick < frame) {
                scene.step()
                tick++
            }
            val grid = scene.navigation.grid
            val arrived = units.count { scene.agentOf(it).state == NavState.Arrived }
            draw(grid, File(out, "issue264-crowd-t%04d.png".format(tick)), "tick $tick: $arrived of 100 arrived") { canvas ->
                canvas.units(scene, grid)
                canvas.goal(grid, 12f, 0f)
            }
        }
    }

    /** The two buildings every shot has, which is what the crowd has to get round. */
    private fun buildings(scene: NavScene) {
        scene.spawnBuilding(x = 0f, y = 4f, halfWidth = 4f, halfDepth = 1f)
        scene.spawnBuilding(x = 2f, y = -5f, halfWidth = 1f, halfDepth = 5f)
    }

    // --- drawing ---------------------------------------------------------------------------

    private fun draw(grid: NavGrid, file: File, caption: String, body: (Canvas) -> Unit) {
        val image = BufferedImage(
            grid.width * CELL_PIXELS,
            grid.height * CELL_PIXELS + CAPTION_HEIGHT,
            BufferedImage.TYPE_INT_RGB,
        )
        val graphics = image.createGraphics()
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val canvas = Canvas(graphics, grid)
        canvas.ground()
        body(canvas)
        canvas.caption(caption)
        graphics.dispose()
        ImageIO.write(image, "png", file)
    }

    /** Cells to pixels, and the few things worth drawing on top of them. */
    private class Canvas(private val graphics: Graphics2D, private val grid: NavGrid) {

        private val height = grid.height * CELL_PIXELS

        fun ground() {
            graphics.color = OPEN
            graphics.fillRect(0, 0, grid.width * CELL_PIXELS, height + CAPTION_HEIGHT)
            for (index in 0 until grid.cellCount) {
                val cell = NavCell(index)
                if (!grid.isBlocked(cell)) continue
                graphics.color = BLOCKED
                fillCell(cell)
            }
            graphics.color = GRID_LINES
            for (column in 0..grid.width) {
                graphics.drawLine(column * CELL_PIXELS, 0, column * CELL_PIXELS, height)
            }
            for (row in 0..grid.height) {
                graphics.drawLine(0, row * CELL_PIXELS, grid.width * CELL_PIXELS, row * CELL_PIXELS)
            }
        }

        fun route(grid: NavGrid, path: NavPath) {
            graphics.color = ROUTE
            for (step in 0 until path.size) fillCell(path.cellAt(step))
            graphics.color = ROUTE_LINE
            graphics.stroke = BasicStroke(2f)
            for (step in 1 until path.size) {
                val from = path.cellAt(step - 1)
                val to = path.cellAt(step)
                graphics.drawLine(pixelX(grid.centreX(from)), pixelY(grid.centreY(from)), pixelX(grid.centreX(to)), pixelY(grid.centreY(to)))
            }
        }

        /** The field as a shade per cell, darker the further from the goal, with a hop arrow. */
        fun field(grid: NavGrid, field: NavFlowField) {
            var longest = 1
            for (index in 0 until grid.cellCount) {
                val cost = field.cost(NavCell(index))
                if (cost != NavFlowField.UNREACHABLE && cost > longest) longest = cost
            }
            for (index in 0 until grid.cellCount) {
                val cell = NavCell(index)
                val cost = field.cost(cell)
                if (cost == NavFlowField.UNREACHABLE) continue
                val far = cost.toFloat() / longest
                graphics.color = Color(
                    (40 + 120 * far).toInt().coerceIn(0, 255),
                    (200 - 130 * far).toInt().coerceIn(0, 255),
                    (160 - 60 * far).toInt().coerceIn(0, 255),
                )
                fillCell(cell)
                // Every other cell, checkerboard: one arrow per cell is a grey wash at this size.
                if ((grid.cellX(cell) + grid.cellY(cell)) % 2 != 0) continue
                val hop = field.nextHop(cell)
                if (!hop.isValid) continue
                graphics.color = HOP
                graphics.drawLine(
                    pixelX(grid.centreX(cell)),
                    pixelY(grid.centreY(cell)),
                    pixelX((grid.centreX(cell) + grid.centreX(hop)) / 2f),
                    pixelY((grid.centreY(cell) + grid.centreY(hop)) / 2f),
                )
            }
        }

        fun units(scene: NavScene, grid: NavGrid) {
            scene.netIds.forEachLive { netId, entity ->
                val agent = with(scene.world) { entity.getOrNull(NavAgent) } ?: return@forEachLive
                val transform = scene.transformOf(netId)
                val pixels = (agent.radius / grid.cellSize * CELL_PIXELS).toInt()
                graphics.color = when (agent.state) {
                    NavState.Arrived -> ARRIVED
                    NavState.Moving -> MOVING
                    NavState.Unreachable -> STUCK
                    NavState.Idle -> IDLE
                }
                graphics.fillOval(
                    pixelX(transform.x) - pixels,
                    pixelY(transform.y) - pixels,
                    pixels * 2,
                    pixels * 2,
                )
            }
        }

        fun goal(grid: NavGrid, x: Float, y: Float) {
            graphics.color = GOAL
            graphics.stroke = BasicStroke(3f)
            val size = CELL_PIXELS
            graphics.drawLine(pixelX(x) - size, pixelY(y) - size, pixelX(x) + size, pixelY(y) + size)
            graphics.drawLine(pixelX(x) - size, pixelY(y) + size, pixelX(x) + size, pixelY(y) - size)
        }

        fun caption(text: String) {
            graphics.color = Color.BLACK
            graphics.fillRect(0, height, grid.width * CELL_PIXELS, CAPTION_HEIGHT)
            graphics.color = Color.WHITE
            graphics.drawString(text, 8, height + CAPTION_HEIGHT - 8)
        }

        private fun fillCell(cell: NavCell) {
            graphics.fillRect(
                grid.cellX(cell) * CELL_PIXELS,
                height - (grid.cellY(cell) + 1) * CELL_PIXELS,
                CELL_PIXELS,
                CELL_PIXELS,
            )
        }

        /** World x to pixels. */
        private fun pixelX(x: Float): Int = ((x - grid.originX) / grid.cellSize * CELL_PIXELS).toInt()

        /** World y to pixels, flipped: y is up in the world and down on an image. */
        private fun pixelY(y: Float): Int = height - ((y - grid.originY) / grid.cellSize * CELL_PIXELS).toInt()
    }

    private fun argument(args: Array<String>, name: String): String? {
        val index = args.indexOf(name)
        return if (index >= 0 && index + 1 < args.size) args[index + 1] else null
    }

    /** The ticks the crowd is drawn at: the start, the squeeze, the far side and the settle. */
    private val FRAME_TICKS = intArrayOf(1, 120, 240, 360, 480, 600, 720, 773)

    private const val CELL_PIXELS = 10

    private const val CAPTION_HEIGHT = 26

    private val OPEN = Color(232, 232, 226)

    private val BLOCKED = Color(70, 62, 58)

    private val GRID_LINES = Color(214, 214, 208)

    private val ROUTE = Color(255, 232, 150)

    private val ROUTE_LINE = Color(200, 120, 20)

    private val HOP = Color(30, 60, 50)

    private val MOVING = Color(40, 110, 200)

    private val ARRIVED = Color(30, 160, 90)

    private val STUCK = Color(200, 60, 60)

    private val IDLE = Color(120, 120, 120)

    private val GOAL = Color(220, 40, 120)
}
