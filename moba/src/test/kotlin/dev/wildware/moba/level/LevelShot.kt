package dev.wildware.moba.level

import dev.wildware.moba.Position
import dev.wildware.moba.entry.MobaEntry
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.level.LevelOutcome
import dev.wildware.udea.core.level.LevelService
import dev.wildware.udea.core.loop.barrier
import dev.wildware.udea.core.module.CoreModule
import dev.wildware.udea.render.capture.CaptureResult
import java.io.ByteArrayInputStream
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import javax.imageio.ImageIO
import kotlin.io.path.createDirectories
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.system.exitProcess

/**
 * Issue #191, as pictures: a real match saved to a level file in one process, and loaded into a
 * fresh game in **another** process, photographed from the same camera at the same tick.
 *
 * Two phases, two JVMs, run in order by `:moba:runLevelShot`:
 *
 * - `save` plays the real level to [SAVE_TICK], pauses, saves `match.udealevel` through the
 *   barrier, points the camera at the player and writes `saved.png`.
 * - `load` boots a game that never loads the scene, photographs it empty (`fresh.png`), loads the
 *   level file through the barrier, points the camera at the same spot and writes `loaded.png`.
 *
 * Then it decodes both PNGs and counts differing pixels, and exits non-zero unless there are none.
 * A separate process is the point: nothing in the loaded game can have been left over from the
 * saved one, so every pixel in `loaded.png` came out of the file.
 *
 * Needs a GL driver, so it is run by name and never from `check`, for the reason `MatchShot` gives.
 */
public object LevelShot {

    /** `save` or `load`. */
    public const val PHASE_PROPERTY: String = "udea.levelshot.phase"

    /** Where the level, the pictures and the camera note are written and read. */
    public const val OUTPUT_PROPERTY: String = "udea.levelshot.dir"

    /** Mid-fight on the real level: units have met and abilities are going off. */
    public const val SAVE_TICK: Long = 420L

    /** Frames between placing the camera and asking for a picture, so the placement has landed. */
    private const val SETTLE_FRAMES: Int = 3

    private const val LEVEL_FILE: String = "match.${LevelService.FILE_EXTENSION}"

    private const val CAMERA_FILE: String = "camera.txt"

    private const val ZOOM: Float = 1f

    @JvmStatic
    public fun main(args: Array<String>) {
        val dir = Path.of(System.getProperty(OUTPUT_PROPERTY) ?: "build/reports/udea/level").also { it.createDirectories() }
        val log = StringBuilder()
        when (val phase = System.getProperty(PHASE_PROPERTY)) {
            "save" -> save(dir, log)
            "load" -> load(dir, log)
            else -> {
                System.err.println("[level.shot] -D$PHASE_PROPERTY must be save or load, was $phase")
                exitProcess(2)
            }
        }
        print(log)
    }

    private fun save(dir: Path, log: StringBuilder) {
        MobaEntry.runWithGl(RenderMode.Offscreen) { host, rendering ->
            val player = MobaEntry.seed(host)
            MobaEntry.follow(rendering, player)
            var settle = -1
            var shot: CompletableFuture<CaptureResult>? = null
            MobaEntry.Attachment(
                frame = { delta ->
                    host.frame(delta)
                    val now = host.ctx.clock.tick.value
                    if (settle < 0 && now >= SAVE_TICK) {
                        host.time.pause()
                        val bytes = saveLevel(host)
                        dir.resolve(LEVEL_FILE).writeBytes(bytes)
                        val at = playerPosition(host)
                        dir.resolve(CAMERA_FILE).writeText("${at.first} ${at.second}\n")
                        rendering.presentation().lookAt(at.first, at.second, ZOOM)
                        log.append("[level.shot] saved ").append(bytes.size).append(" bytes at tick ")
                            .append(now).append(", camera at ").append(at).append('\n')
                        settle = SETTLE_FRAMES
                    } else if (settle > 0) {
                        settle--
                    } else if (settle == 0 && shot == null) {
                        shot = rendering.presentation().capture()
                    }
                    shot?.takeIf { it.isDone }?.let { done ->
                        write(dir, "saved", done.get(), log)
                        rendering.requestExit()
                    }
                },
            )
        }
    }

    private fun load(dir: Path, log: StringBuilder) {
        val bytes = dir.resolve(LEVEL_FILE).readBytes()
        val (x, y) = dir.resolve(CAMERA_FILE).readText().trim().split(' ').map(String::toFloat)
        MobaEntry.runWithGl(RenderMode.Offscreen) { host, rendering ->
            host.time.pause()
            rendering.presentation().lookAt(x, y, ZOOM)
            var step = 0
            var settle = SETTLE_FRAMES
            var shot: CompletableFuture<CaptureResult>? = null
            MobaEntry.Attachment(
                frame = { delta ->
                    host.frame(delta)
                    when {
                        settle > 0 -> settle--
                        shot == null -> shot = rendering.presentation().capture()
                        shot!!.isDone && step == 0 -> {
                            write(dir, "fresh", shot!!.get(), log)
                            loadLevel(host, bytes)
                            rendering.presentation().lookAt(x, y, ZOOM)
                            log.append("[level.shot] loaded ").append(bytes.size).append(" bytes; clock now ")
                                .append(host.ctx.clock.tick.value).append('\n')
                            step = 1
                            settle = SETTLE_FRAMES
                            shot = null
                        }
                        shot!!.isDone && step == 1 -> {
                            write(dir, "loaded", shot!!.get(), log)
                            step = 2
                            rendering.requestExit()
                        }
                    }
                },
            )
        }
        val differing = differingPixels(dir.resolve("saved.png"), dir.resolve("loaded.png"))
        log.append("[level.shot] saved.png vs loaded.png: ").append(differing).append(" differing pixels\n")
        if (differing != 0L) {
            print(log)
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

    private fun differingPixels(a: Path, b: Path): Long {
        val left = ImageIO.read(ByteArrayInputStream(a.readBytes()))
        val right = ImageIO.read(ByteArrayInputStream(b.readBytes()))
        if (left.width != right.width || left.height != right.height) return left.width.toLong() * left.height
        var differing = 0L
        for (y in 0 until left.height) {
            for (x in 0 until left.width) {
                if (left.getRGB(x, y) != right.getRGB(x, y)) differing++
            }
        }
        return differing
    }
}
