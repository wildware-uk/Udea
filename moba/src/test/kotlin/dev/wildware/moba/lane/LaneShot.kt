package dev.wildware.moba.lane

import com.github.quillraven.fleks.World.Companion.family
import dev.wildware.moba.Player
import dev.wildware.moba.Position
import dev.wildware.moba.ability.Corpse
import dev.wildware.moba.entry.MobaEntry
import dev.wildware.moba.level.Team
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.render.capture.CaptureResult
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import kotlin.io.path.createParentDirectories
import kotlin.io.path.writeBytes
import kotlin.system.exitProcess

/**
 * Three captures of the lane: the wave walking it, the two lines meeting under the towers, and
 * the champion standing in it with a purse.
 *
 * ## Why this is a harness and not a test
 *
 * The same reason `MatchShot` is one, and stated there at length: it creates a real GL context,
 * so on a machine with no driver it is a **failure** rather than a skip, and a gate that turns
 * into a skip is a failure mode this repository has already shipped once. It lives in the test
 * source set so `check` compiles it and the shipped jar does not carry it, and it has no `@Test`,
 * so JUnit never runs it. `./gradlew :moba:runLaneShot` is what runs it.
 *
 * ## What it photographs, and why these three
 *
 * | file | the claim it is evidence for |
 * |---|---|
 * | `wave.png` | creeps spawn at a base and walk a lane that is visibly a lane |
 * | `clash.png` | the two lines meet, under fire from two towers |
 * | `farm.png` | a champion is standing in the lane with creeps around it |
 *
 * The camera is **not** following the player for the first two: `level/test_level` drops the
 * champion in the orc clearing four hundred units south of the lane, so a frame framed on it
 * would be a picture of the brawl. It is pointed at the lane by world coordinates instead, which
 * is what `render.set_camera` does over the agent port. For `farm.png` the champion is walked
 * into the lane first - by writing its `Position`, which is what `world.set_component_field`
 * does - and the camera follows it, so the third frame is the game as a player in the lane sees
 * it.
 */
public object LaneShot {

    /** Where the PNGs are written. One per subject. */
    public const val OUTPUT_PROPERTY: String = "udea.laneshot.dir"

    /** Give up after this many ticks. */
    public const val DEADLINE_TICKS: Long = 3_000L

    /** Where the champion is stood for `farm.png`: on the lane, between mid and the tower. */
    public const val FARM_X: Float = -70f

    /** @see FARM_X */
    public const val FARM_Y: Float = 430f

    private class Shot(
        val name: String,
        val future: CompletableFuture<CaptureResult>,
        val note: String,
    )

    @JvmStatic
    @Suppress("LongMethod", "NestedBlockDepth", "CyclomaticComplexMethod")
    public fun main(args: Array<String>) {
        val dir = Path.of(System.getProperty(OUTPUT_PROPERTY) ?: "build/reports/udea/lane")
        val mode = MobaEntry.modeFromProperties(fallback = RenderMode.Offscreen)
        val pending = ArrayList<Shot>()
        val written = HashSet<String>()
        val log = StringBuilder()
        MobaEntry.runWithGl(mode) { host, rendering ->
            val player = MobaEntry.seed(host)
            var framed = false
            var walked = false
            MobaEntry.Attachment(
                frame = { delta ->
                    host.frame(delta)
                    val lane = laneState(host)
                    val now = host.ctx.clock.tick.value
                    if (!framed) {
                        rendering.presentation().lookAt(0f, LaneGeometry.TOWER_Y, LANE_ZOOM)
                        framed = true
                    }
                    // The first wave, a little after it has left its base and is visibly walking.
                    if (lane != null && lane.waveNumber >= 1 && "wave" !in written &&
                        pending.none { it.name == "wave" }
                    ) {
                        pending += Shot(
                            "wave",
                            rendering.presentation().capture(afterTick = now + WALK_TICKS),
                            "wave " + lane.waveNumber + " walking the lane",
                        )
                    }
                    // The clash: the tick the first creep body appears is the tick the two lines
                    // have met, which is the moment worth a picture.
                    if ("clash" !in written && pending.none { it.name == "clash" } &&
                        creepBodies(host) > 0
                    ) {
                        pending += Shot(
                            "clash",
                            rendering.presentation().capture(afterTick = now + 2),
                            "the lines met: " + creepCount(host) + " creeps, " +
                                creepBodies(host) + " down",
                        )
                    }
                    // The champion, walked into the lane, camera on it.
                    if (!walked && "wave" in written) {
                        walked = true
                        moveChampion(host)
                        rendering.scene.follow(player)
                    }
                    if (walked && "farm" !in written && pending.none { it.name == "farm" }) {
                        pending += Shot(
                            "farm",
                            rendering.presentation().capture(afterTick = now + FARM_TICKS),
                            "the champion in the lane",
                        )
                    }
                    // Hold the champion in the lane while the farm frame is being waited for:
                    // `UnitBattleSystem` does not walk a player, but a knockback will.
                    if (walked && "farm" !in written) moveChampion(host)
                    val done = pending.filter { it.future.isDone }
                    for (shot in done) {
                        val result = shot.future.get()
                        val out = dir.resolve(shot.name + ".png")
                        out.createParentDirectories()
                        out.writeBytes(result.bytes)
                        written += shot.name
                        pending.remove(shot)
                        val purse = wallet(host)
                        log.append("[lane.shot] wrote ").append(out.toAbsolutePath())
                            .append(" ").append(result.width).append("x").append(result.height)
                            .append(" at tick ").append(result.tick.value)
                            .append(" - ").append(shot.note)
                            .append(" | wave=").append(lane?.waveNumber ?: 0)
                            .append(" creeps=").append(creepCount(host))
                            .append(" bodies=").append(creepBodies(host))
                            .append(" towers=").append(towerShots(host))
                            .append(" gold=").append(purse?.gold ?: 0)
                            .append(" cs=").append(purse?.lastHits ?: 0)
                            .append(" level=").append(purse?.level ?: 0)
                            .append("\n")
                    }
                    if (written.size == SUBJECTS.size || now > DEADLINE_TICKS) {
                        rendering.requestExit()
                    }
                },
            )
        }
        print(log)
        val missing = SUBJECTS - written
        if (missing.isNotEmpty()) {
            System.err.println("[lane.shot] never captured: $missing")
            exitProcess(1)
        }
    }

    /** The three subjects. */
    private val SUBJECTS = listOf("wave", "clash", "farm")

    /** How far the wave frame waits after the wave spawns, so the creeps are visibly walking. */
    private const val WALK_TICKS: Long = 90L

    /** How long the farm frame waits, so creeps have reached the champion. */
    private const val FARM_TICKS: Long = 120L

    /** Camera zoom for the two lane-wide frames. Wider than the default, so both towers fit. */
    private const val LANE_ZOOM: Float = 2.4f

    private fun laneState(host: GameHost): LaneState? {
        val entities = host.world.family { all(LaneState) }.entities
        if (entities.size == 0) return null
        return with(host.world) { entities[0][LaneState] }
    }

    private fun wallet(host: GameHost): Wallet? {
        val entities = host.world.family { all(Player, Wallet) }.entities
        if (entities.size == 0) return null
        return with(host.world) { entities[0][Wallet] }
    }

    private fun creepCount(host: GameHost): Int =
        host.world.family { all(LaneCreep) }.entities.size

    private fun creepBodies(host: GameHost): Int {
        val entities = host.world.family { all(LaneCreep, Corpse) }.entities
        return entities.size
    }

    private fun towerShots(host: GameHost): Int {
        val entities = host.world.family { all(Tower) }.entities
        var total = 0
        var index = 0
        with(host.world) {
            while (index < entities.size) {
                total += entities[index][Tower].shots
                index++
            }
        }
        return total
    }

    /** Stands the champion in the lane. What `world.set_component_field` does over HTTP. */
    private fun moveChampion(host: GameHost) {
        val entities = host.world.family { all(Player, Position) }.entities
        if (entities.size == 0) return
        with(host.world) {
            val position = entities[0][Position]
            position.x = FARM_X
            position.y = FARM_Y
        }
    }

    /** A label for a log line. */
    @Suppress("unused")
    private fun sideOf(team: Int): String = Team.nameOf(team)
}
