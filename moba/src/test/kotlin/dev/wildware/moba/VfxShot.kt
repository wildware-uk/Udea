package dev.wildware.moba

import dev.wildware.moba.ability.Corpse
import dev.wildware.moba.ability.Projectile
import dev.wildware.moba.entry.MobaEntry
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.module.CoreModule
import com.github.quillraven.fleks.World.Companion.family
import dev.wildware.udea.render.capture.CaptureResult
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import kotlin.io.path.createParentDirectories
import kotlin.io.path.writeBytes
import kotlin.system.exitProcess

/**
 * Three captures of the shipping level, each taken on the first tick its subject exists.
 *
 * ## Why this is a harness and not a test
 *
 * It creates a real GL context, so on a machine with no driver it is a failure rather than a
 * skip - the same reason `:moba:runShot` is deliberately not wired into `check` (see `MobaShot`).
 * It lives in the **test** source set so it is compiled by `check` and stays out of the shipped
 * jar; it carries no `@Test`, so JUnit never runs it. It is launched by hand, off the test
 * runtime classpath.
 *
 * ## Why the ticks are found rather than named
 *
 * `MobaShot` captures at a tick a caller names, which works because it stands its own line of
 * units up. This one watches the *level* fight, and the three things it has to photograph happen
 * when the fight decides they do: an arrow exists only while one is in flight, a heal flash only
 * while `ability/heal_over_time` is ticking on somebody, and a corpse only after the first unit
 * dies. So each subject is watched for, the camera is put on it, and the capture is asked for on
 * the tick it first appeared.
 *
 * The camera follows the subject rather than framing the level: `MobaScene.WORLD_WIDTH` is 320
 * world units and a character's frame is about 143 of them, so a level-wide shot is eight units
 * across and an arrow in it is nine pixels. Following puts the subject in the middle of the same
 * eight-unit view.
 */
public object VfxShot {

    /** Where the PNGs are written. One per subject, suffixed with what it shows. */
    public const val OUTPUT_PROPERTY: String = "udea.vfxshot.dir"

    /** Give up after this many ticks if a subject never appeared. */
    public const val DEADLINE_TICKS: Long = 1200L

    /** How long an arrow must have been in the air before it is worth photographing. */
    public const val AIRBORNE_TICKS: Int = 6

    /** How often the heartbeat line is written, in ticks. */
    public const val HEARTBEAT_TICKS: Long = 200L

    private class Shot(
        val name: String,
        val future: CompletableFuture<CaptureResult>,
        val tick: Long,
        val subject: NetId,
    )

    @JvmStatic
    @Suppress("LongMethod", "NestedBlockDepth")
    public fun main(args: Array<String>) {
        val dir = Path.of(System.getProperty(OUTPUT_PROPERTY) ?: "build/reports/udea/vfx")
        val mode = MobaEntry.modeFromProperties(fallback = RenderMode.Offscreen)
        val pending = ArrayList<Shot>()
        val written = HashSet<String>()
        val log = StringBuilder()
        MobaEntry.runWithGl(mode) { host, rendering ->
            MobaEntry.seed(host)
            rendering.scene.frameLevel()
            val netIds = host.ctx[CoreModule.NET_IDS]
            var asked = ""
            var lastBeat = -1L
            MobaEntry.Attachment(
                frame = { delta ->
                    host.frame(delta)
                    val now = host.ctx.clock.tick.value
                    // One subject at a time: the camera can only be on one thing, and a capture
                    // asked for while the previous one is still in flight would be framed on
                    // whatever the camera moved to next.
                    if (asked.isEmpty()) {
                        val subject = findSubject(host, written)
                        if (subject != null) {
                            // The camera is deliberately NOT moved onto the subject. `CameraRig`
                            // eases over about a fifth of a second and an arrow's whole flight is
                            // about ten ticks at `FireArrowExec.SPEED`, so a follow would still
                            // be sliding when the shutter opened. `frameLevel`'s framing is fixed
                            // from frame one; the subject's world position is logged instead, so
                            // a reader can convert it to a pixel and look there.
                            pending += Shot(
                                subject.first,
                                rendering.presentation().capture(afterTick = now + 1),
                                now + 1,
                                subject.second,
                            )
                            asked = subject.first
                            log.append("[vfx.shot] ").append(subject.first)
                                .append(" found at tick ").append(now)
                                .append(", netId ").append(subject.second.raw).append('\n')
                        }
                    }
                    val done = pending.filter { it.future.isDone }
                    for (shot in done) {
                        val result = shot.future.get()
                        val out = dir.resolve(shot.name + ".png")
                        out.createParentDirectories()
                        out.writeBytes(result.bytes)
                        written += shot.name
                        pending.remove(shot)
                        asked = ""
                        // Read where the subject *is* rather than where it was when it was
                        // spotted: an arrow covers `FireArrowExec.SPEED` world units every tick,
                        // and a pixel computed from its position two ticks ago is a crop of the
                        // grass behind it.
                        val at = netIds.resolveOrNull(shot.subject)
                            ?.let { with(host.world) { it.getOrNull(Position) } }
                        log.append("[vfx.shot] wrote ").append(out.toAbsolutePath())
                            .append(' ').append(result.width).append('x').append(result.height)
                            .append(" at tick ").append(result.tick.value)
                            .append(", subject at world (").append(at?.x).append(", ")
                            .append(at?.y).append(")\n")
                    }
                    // A heartbeat, so a run that ends with "never saw X" says what the world held
                    // while it was looking rather than only that it did not find one.
                    if (now / HEARTBEAT_TICKS != lastBeat) {
                        lastBeat = now / HEARTBEAT_TICKS
                        log.append("[vfx.shot] tick ").append(now)
                            .append(" corpses=").append(host.world.family { all(Corpse) }.entities.size)
                            .append(" arrows=").append(host.world.family { all(Projectile) }.entities.size)
                            .append(" views=").append(host.world.family { all(SpriteView) }.entities.size)
                            .append(" asked='").append(asked).append("' written=").append(written)
                            .append('\n')
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
            System.err.println("[vfx.shot] never saw: $missing")
            exitProcess(1)
        }
    }

    /** The three things this harness exists to photograph. */
    private val SUBJECTS = listOf("corpse", "arrow", "heal")

    /**
     * The first subject in the world that has not been captured yet, and what to point at.
     *
     * Ordered so the cheapest-to-miss goes first: an arrow is gone in under three seconds and a
     * heal flash in under half of one, while a corpse lies there for
     * `dev.wildware.moba.ability.DeathSystem.CORPSE_TICKS`.
     */
    private fun findSubject(
        host: dev.wildware.udea.core.host.GameHost,
        written: Set<String>,
    ): Pair<String, NetId>? {
        val netIds = host.ctx[CoreModule.NET_IDS]
        val world = host.world
        if ("arrow" !in written) {
            val arrows = world.family { all(Projectile, Position) }.entities
            with(world) {
                var index = 0
                while (index < arrows.size) {
                    val entity = arrows[index]
                    // Not the tick it was loosed: an arrow starts at its archer's own position and
                    // is drawn on top of him, so the first frame of its life is a picture of a
                    // soldier. `AIRBORNE_TICKS` of flight at `FireArrowExec.SPEED` puts it clear.
                    val flown = Projectile.DEFAULT_LIFE_TICKS - entity[Projectile].lifeTicks
                    if (flown >= AIRBORNE_TICKS) return "arrow" to netIds.netIdOf(entity)
                    index++
                }
            }
        }
        if ("heal" !in written) {
            with(world) {
                val flashes = world.family { all(SpriteView, Position) }.entities
                var index = 0
                while (index < flashes.size) {
                    val entity = flashes[index]
                    if (entity[SpriteView].animation == EffectKind.Heal.animation) {
                        return "heal" to netIds.netIdOf(entity)
                    }
                    index++
                }
            }
        }
        if ("corpse" !in written) {
            val bodies = world.family { all(Corpse, Position) }.entities
            if (bodies.size > 0) return "corpse" to netIds.netIdOf(bodies[0])
        }
        return null
    }
}
