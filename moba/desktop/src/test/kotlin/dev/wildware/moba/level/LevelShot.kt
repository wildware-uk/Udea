package dev.wildware.moba.level

import dev.wildware.moba.Position
import dev.wildware.moba.entry.MobaEntry
import dev.wildware.moba.entry.MobaLaunch
import dev.wildware.moba.entry.MobaLaunch.Rendering
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.level.LevelOutcome
import dev.wildware.udea.core.level.LevelService
import dev.wildware.udea.core.loop.barrier
import dev.wildware.udea.core.module.CoreModule
import dev.wildware.udea.render.capture.CaptureResult
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.nio.file.Path
import kotlinx.coroutines.Deferred
import javax.imageio.ImageIO
import kotlin.io.path.createDirectories
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.system.exitProcess

/**
 * Issue #191, as pictures: a real match saved to a level file in one process, and loaded into a
 * fresh game in **another** process, photographed from the same camera.
 *
 * Two phases, two JVMs, run in order by `:moba:runLevelShot`:
 *
 * - `save` plays the real level to [SAVE_TICK], pauses, saves `match.udealevel` through the
 *   barrier and photographs the world (`saved.png`), then steps one tick and photographs it again
 *   (`saved-next.png`).
 * - `load` boots a game that never lays out the level and photographs it (`fresh.png`), loads the
 *   file through the barrier and photographs it (`loaded.png`), then steps one tick
 *   (`loaded-next.png`).
 *
 * A separate process is the point: nothing in the loaded game can have been left over from the
 * saved one, so everything in `loaded.png` came out of the file.
 *
 * ## Why the pair that must match is the one a tick later
 *
 * `saved.png` and `loaded.png` differ, in one place: the score strip across the top. The HUD draws
 * it from `MatchService`, which is a mirror `MatchSystem` rewrites on every tick from the saved
 * `MatchState` component - and a world that has been loaded but not yet stepped has not had that
 * tick. The strip is not missing from the level; it is a cache the next tick fills. So the
 * harness prints how many pixels that pair differs by and where, and **fails** only on the pair
 * that has both had a tick: `saved-next.png` against `loaded-next.png`, which must be identical
 * to the pixel. It also fails if `loaded.png` equals `fresh.png`, which is a load that did nothing.
 *
 * Needs a GL driver, so it is run by name and never from `check`, for the reason `MatchShot` gives.
 */
/*
 * `Deferred.getCompleted` is `@ExperimentalCoroutinesApi`, and it is read here only behind an
 * `isCompleted` this frame has already checked - the one shape the annotation exists to guard.
 * Awaiting instead would block the render thread that settles the capture.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
public object LevelShot {

    /** `save` or `load`. */
    public const val PHASE_PROPERTY: String = "udea.levelshot.phase"

    /** Where the level, the pictures and the camera note are written and read. */
    public const val OUTPUT_PROPERTY: String = "udea.levelshot.dir"

    /** Mid-fight on the real level: the lines have met, the same moment `MatchShot` calls the melee. */
    public const val SAVE_TICK: Long = 420L

    /** Frames between a stage's action and its picture, so a camera placement has landed. */
    private const val SETTLE_FRAMES: Int = 3

    private const val LEVEL_FILE: String = "match.${LevelService.FILE_EXTENSION}"

    private const val CAMERA_FILE: String = "camera.txt"

    private const val ZOOM: Float = 1f

    /** One picture: [action] runs, the frames settle, then the frame is written as `<name>.png`. */
    private class Stage(val name: String, val action: () -> Unit)

    /**
     * Runs [stages] one after another across render frames, then asks the renderer to exit.
     *
     * The capture is asked for on a frame and read on a later one because the render thread is the
     * one that settles it; blocking on it here would deadlock the frame that has to draw it.
     */
    private class Script(
        private val rendering: Rendering,
        private val dir: Path,
        private val log: StringBuilder,
        private val stages: List<Stage>,
    ) {
        private var index = 0
        private var started = false
        private var settle = 0
        private var shot: Deferred<CaptureResult>? = null

        fun frame() {
            val pending = shot
            when {
                pending != null -> if (pending.isCompleted) {
                    write(dir, stages[index].name, pending.getCompleted(), log)
                    shot = null
                    started = false
                    index++
                }
                index == stages.size -> rendering.requestExit()
                !started -> {
                    stages[index].action()
                    started = true
                    settle = SETTLE_FRAMES
                }
                settle > 0 -> settle--
                else -> shot = rendering.presentation().capture()
            }
        }
    }

    @JvmStatic
    public fun main(args: Array<String>) {
        val dir = Path.of(System.getProperty(OUTPUT_PROPERTY) ?: "build/reports/udea/level")
        dir.createDirectories()
        val log = StringBuilder()
        when (val phase = System.getProperty(PHASE_PROPERTY)) {
            "save" -> save(dir, log)
            "load" -> load(dir, log)
            else -> {
                System.err.println("[level.shot] -D$PHASE_PROPERTY must be save or load, was $phase")
                exitProcess(2)
            }
        }
    }

    private fun save(dir: Path, log: StringBuilder) {
        MobaLaunch.runWithGl(RenderMode.Offscreen) { host, rendering ->
            val player = MobaEntry.seed(host)
            MobaLaunch.follow(rendering, player)
            var camera = 0f to 0f
            val script = Script(
                rendering,
                dir,
                log,
                listOf(
                    Stage("saved") {
                        host.time.pause()
                        val bytes = saveLevel(host)
                        dir.resolve(LEVEL_FILE).writeBytes(bytes)
                        camera = playerPosition(host)
                        dir.resolve(CAMERA_FILE).writeText("${camera.first} ${camera.second}\n")
                        rendering.presentation().lookAt(camera.first, camera.second, ZOOM)
                        log.append("[level.shot] saved ").append(bytes.size).append(" bytes at tick ")
                            .append(host.ctx.clock.tick.value).append(", camera at ").append(camera).append('\n')
                    },
                    Stage("saved-next") {
                        host.time.step(1)
                        rendering.presentation().lookAt(camera.first, camera.second, ZOOM)
                    },
                ),
            )
            MobaLaunch.Attachment(
                frame = { delta ->
                    host.frame(delta)
                    if (host.ctx.clock.tick.value >= SAVE_TICK) script.frame()
                },
            )
        }
        print(log)
    }

    private fun load(dir: Path, log: StringBuilder) {
        val bytes = dir.resolve(LEVEL_FILE).readBytes()
        val (x, y) = dir.resolve(CAMERA_FILE).readText().trim().split(' ').map(String::toFloat)
        MobaLaunch.runWithGl(RenderMode.Offscreen) { host, rendering ->
            val script = Script(
                rendering,
                dir,
                log,
                listOf(
                    Stage("fresh") {
                        host.time.pause()
                        rendering.presentation().lookAt(x, y, ZOOM)
                    },
                    Stage("loaded") {
                        loadLevel(host, bytes)
                        rendering.presentation().lookAt(x, y, ZOOM)
                        log.append("[level.shot] loaded ").append(bytes.size).append(" bytes; clock now ")
                            .append(host.ctx.clock.tick.value).append('\n')
                    },
                    Stage("loaded-next") {
                        host.time.step(1)
                        rendering.presentation().lookAt(x, y, ZOOM)
                    },
                ),
            )
            MobaLaunch.Attachment(
                frame = { delta ->
                    host.frame(delta)
                    script.frame()
                },
            )
        }
        val fresh = compare(dir, "fresh", "loaded", log)
        compare(dir, "saved", "loaded", log)
        val next = compare(dir, "saved-next", "loaded-next", log)
        print(log)
        if (fresh.count == 0L) {
            System.err.println("[level.shot] loaded.png is the fresh game's picture: the load changed nothing")
            exitProcess(1)
        }
        if (next.count != 0L) {
            System.err.println("[level.shot] one tick after the save and one tick after the load look different")
            exitProcess(1)
        }
    }

    private fun saveLevel(host: GameHost): ByteArray {
        val action = host.game.levels.save(host.ctx.barrier)
        host.ctx.barrier.drain(host.world, host.ctx)
        return when (val outcome = action.outcome) {
            is LevelOutcome.Completed -> outcome.value
            else -> error("the level save did not complete: $outcome")
        }
    }

    private fun loadLevel(host: GameHost, bytes: ByteArray) {
        val levels = host.game.levels
        val action = levels.load(levels.read(bytes), host.ctx.barrier)
        host.ctx.barrier.drain(host.world, host.ctx)
        check(action.outcome is LevelOutcome.Completed) { "the level load did not complete: ${action.outcome}" }
    }

    private fun playerPosition(host: GameHost): Pair<Float, Float> {
        val player = MobaEntry.playerId(host)
        val entity = checkNotNull(host.ctx[CoreModule.NET_IDS].resolveOrNull(player)) { "no player at $player" }
        return with(host.world) { entity[Position].let { it.x to it.y } }
    }

    private fun write(dir: Path, name: String, result: CaptureResult, log: StringBuilder) {
        val out = dir.resolve("$name.png")
        out.writeBytes(result.bytes)
        log.append("[level.shot] wrote ").append(out.toAbsolutePath()).append(' ')
            .append(result.width).append('x').append(result.height)
            .append(" at tick ").append(result.tick.value).append('\n')
    }

    /** How many pixels two pictures differ in, and the rows those pixels span. */
    private class Difference(val count: Long, val firstRow: Int, val lastRow: Int)

    private fun compare(dir: Path, left: String, right: String, log: StringBuilder): Difference {
        val a = image(dir.resolve("$left.png"))
        val b = image(dir.resolve("$right.png"))
        check(a.width == b.width && a.height == b.height) { "$left.png and $right.png are different sizes" }
        var count = 0L
        var firstRow = -1
        var lastRow = -1
        for (row in 0 until a.height) {
            for (column in 0 until a.width) {
                if (a.getRGB(column, row) != b.getRGB(column, row)) {
                    count++
                    if (firstRow < 0) firstRow = row
                    lastRow = row
                }
            }
        }
        val difference = Difference(count, firstRow, lastRow)
        log.append("[level.shot] ").append(left).append(".png vs ").append(right).append(".png: ")
            .append(count).append(" of ").append(a.width.toLong() * a.height).append(" pixels differ")
        if (count > 0) log.append(", rows ").append(firstRow).append('-').append(lastRow).append(" from the top")
        log.append('\n')
        return difference
    }

    private fun image(path: Path): BufferedImage = ImageIO.read(ByteArrayInputStream(path.readBytes()))
}
