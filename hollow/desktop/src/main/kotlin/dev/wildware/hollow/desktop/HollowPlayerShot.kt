package dev.wildware.hollow.desktop

import com.github.quillraven.fleks.World
import dev.wildware.hollow.HollowControls
import dev.wildware.hollow.HollowGame
import dev.wildware.hollow.Player
import dev.wildware.hollow.Prop
import dev.wildware.hollow.Scenery
import dev.wildware.udea.core.GameContext
import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.loop.BarrierAction
import dev.wildware.udea.core.loop.barrier
import dev.wildware.udea.core.module.CoreModule
import dev.wildware.udea.core.spatial.Animator
import dev.wildware.udea.core.spatial.Transform3D
import dev.wildware.udea.render.capture.CaptureRequest
import dev.wildware.udea.render.capture.capture
import dev.wildware.udea.render.input.IntentSource
import dev.wildware.udea.render.input.IntentState
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * `sh gradlew :hollow:desktop:runPlayerShot`: the character walking, then running a lap of the
 * clearing, one PNG per known tick (issue #250).
 *
 * A sequence rather than a picture, because everything this ticket added **moves**: the walk, the
 * change of gait, which way the model is pointing. A single frame cannot tell a running character
 * from a sliding one, and this project has no clip recorder - the stated alternative is to step the
 * simulation deliberately and take a shot per step, which is what this does. Every file is named
 * with the tick it was taken on, so a frame can be read back against the route below.
 *
 * ## The route is a function of the tick, and that is the point
 *
 * [axisAt] is the whole script: given how many ticks have passed since the run began, it answers
 * what the player is holding. It is installed as an ordinary [IntentSource], the same seam a
 * keyboard is wired through, so what this shot photographs is the game a player plays rather than a
 * pose arranged for a camera. Nothing writes a position, a rotation or a clip.
 *
 * Reading the route: a still start, a walk out, a lap at a run taken as eight legs 45 degrees
 * apart, and then a straight charge east into a rock, which is where the sequence ends - standing
 * against it, playing the idle, because the solver stopped the character and the gait follows the
 * solver. The legs are short enough to keep the lap inside the clearing's own stones, which stand
 * from 5.5 units out (`ClearingLayout`).
 *
 * Exits non-zero if the first frame never settles, so a run that photographed a half-loaded world
 * cannot look green.
 */
public object HollowPlayerShot {

    private const val OUTPUT_PROPERTY: String = "udea.shot.out"

    @JvmStatic
    public fun main(args: Array<String>) {
        val dir = Path.of(System.getProperty(OUTPUT_PROPERTY) ?: "build/reports/udea/player")
        val started = HollowLaunch.start(RenderMode.Offscreen)
        var taken = 0
        try {
            HollowGame.seed(started.host)
            val host = started.host
            host.ctx[IntentState.KEY].source = IntentSource { into ->
                val axis = axisAt(host.tick)
                into.setAxis(HollowControls.MOVE_AXIS, axis.x, axis.y)
                into.setPressed(HollowControls.RUN_ACTION, axis.running)
            }
            started.backend.drive(host)

            val slot = checkNotNull(started.backend.pipeline?.capture) { "the pipeline has no capture slot" }
            // Wait for the forest's textures, with no character in the world yet. "Whole" is two
            // byte-identical frames, which a still clearing gives and an animated character never
            // would: the idle breathes, so a settle taken with the human already standing there
            // would never fire and the run would exit having photographed nothing.
            var previous = slot.capture(CaptureRequest())
            var frames = 1
            var settled = false
            while (!settled && frames < FRAME_BUDGET) {
                val current = slot.capture(CaptureRequest())
                frames++
                settled = frames >= MIN_FRAMES && current.bytes.contentEquals(previous.bytes)
                previous = current
            }
            if (!settled) {
                System.err.println("[hollow.player] the world did not settle in $FRAME_BUDGET frames")
                exitProcess(1)
            }
            // The simulation runs on the render thread, so the character is spawned where every
            // other mutation from outside a system goes in this engine: the barrier, drained at the
            // top of a step.
            val spawn = SpawnCharacter()
            host.ctx.barrier.submit(spawn)
            var waited = 0
            while (spawn.character == NetId.NONE && waited < FRAME_BUDGET) {
                slot.capture(CaptureRequest())
                waited++
            }
            val character = spawn.character
            check(character != NetId.NONE) { "the character was never spawned" }
            started.backend.onRenderThread { started.follow(character) }

            val start = host.tick + SETTLE_MARGIN
            begin = start
            Files.createDirectories(dir.toAbsolutePath())

            for (index in 0 until SHOTS) {
                // The rock the last leg runs into, put down one shot *into* the charge rather
                // than at its start: by then the character is running straight east and its y has
                // stopped changing, so a rock placed level with it is a rock placed on the line it
                // is actually on. Put down at the lap's end instead, the character meets it
                // off-centre and slides round it, which is correct and is not the picture.
                //
                // Placed by the shot and not found in the level, because where the lap ends is the
                // solver's answer and not a number this file can know in advance. It is an ordinary
                // `Scenery` prop: `ClearingBodySystem` gives it its collision circle on the next
                // tick, exactly as it does for every tree in the clearing.
                if (index == ROCK_SHOT) host.ctx.barrier.submit(PlaceRock(character))
                val at = start + index.toLong() * STEP
                val result = slot.capture(CaptureRequest(afterTick = at))
                val name = "player-%03d.png".format(index)
                Files.write(dir.resolve(name), result.bytes)
                taken++
                println("[hollow.player] $name ${describe(host, character, result.tick)}")
            }
        } finally {
            started.close()
        }
        println("[hollow.player] ${dir.toAbsolutePath()}: $taken frames")
    }

    /**
     * Where the route has got to at [now], or a still character before it starts.
     *
     * Read on the render thread, at `SimPhase.Intent`, once a tick.
     */
    private fun axisAt(now: Tick): Held {
        val start = begin
        if (now < start) return STILL
        val since = now.value - start.value
        if (since < STILL_TICKS) return STILL
        val walking = since - STILL_TICKS
        if (walking < WALK_TICKS) return Held(0f, 1f, running = false)
        val lap = walking - WALK_TICKS
        if (lap >= LEGS * LEG_TICKS) return CHARGE
        val leg = (lap / LEG_TICKS).toInt()
        return LAP[leg]
    }

    /** Puts the character in the middle of the clearing, at the top of a step. */
    private class SpawnCharacter : BarrierAction {
        override val label: String = "hollow.player.spawn"

        /** The spawned character, read by the main thread once it is no longer [NetId.NONE]. */
        @Volatile
        var character: NetId = NetId.NONE
            private set

        override fun apply(world: World, ctx: GameContext) {
            character = Player.spawn(world, ctx[CoreModule.NET_IDS], x = 0f, y = 0f)
        }
    }

    /** Puts a big stone on the charge line, [ROCK_AHEAD] east of wherever the character now is. */
    private class PlaceRock(private val character: NetId) : BarrierAction {
        override val label: String = "hollow.player.rock"

        override fun apply(world: World, ctx: GameContext) {
            val netIds = ctx[CoreModule.NET_IDS]
            val entity = netIds.resolveOrNull(character) ?: return
            val at = with(world) { entity[Transform3D] }
            val rock = world.entity {
                it += Transform3D(
                    x = at.x + ROCK_AHEAD,
                    y = at.y,
                    scaleX = ROCK_SCALE,
                    scaleY = ROCK_SCALE,
                    scaleZ = ROCK_SCALE,
                )
                it += Scenery(Prop.STONE_LARGE_A)
            }
            netIds.allocate(rock)
        }
    }

    /** What the player is holding: a move axis and whether the run control is down. */
    private class Held(val x: Float, val y: Float, val running: Boolean)

    /** A line for the transcript: where the character is and what it is playing. */
    private fun describe(host: GameHost, character: NetId, at: Tick): String {
        val entity = host.ctx[CoreModule.NET_IDS].resolveOrNull(character) ?: return "tick ${at.value}: gone"
        val (place, clip) = with(host.world) { entity[Transform3D] to entity[Animator].current.clip }
        return "tick ${at.value}: (%.2f, %.2f) facing %.2f, clip %d".format(place.x, place.y, place.rotationZ, clip)
    }

    /** The tick the route starts on. Written once, by the main thread, after the world has loaded. */
    @Volatile
    private var begin: Tick = Tick(Long.MAX_VALUE)

    private val STILL = Held(0f, 0f, running = false)

    /** The last leg: straight east at a run, into the rock. */
    private val CHARGE = Held(1f, 0f, running = true)

    /** Eight directions, 45 degrees apart, each held at a run: one lap of the clearing. */
    private val LAP: List<Held> = listOf(
        Held(1f, 0f, running = true),
        Held(DIAGONAL, -DIAGONAL, running = true),
        Held(0f, -1f, running = true),
        Held(-DIAGONAL, -DIAGONAL, running = true),
        Held(-1f, 0f, running = true),
        Held(-DIAGONAL, DIAGONAL, running = true),
        Held(0f, 1f, running = true),
        Held(DIAGONAL, DIAGONAL, running = true),
    )

    /** Frames to draw before a repeat counts as settled: past the first texture upload. */
    private const val MIN_FRAMES = 10

    private const val FRAME_BUDGET = 600

    /** Ticks between the settle and the first shot, so the first frame is unambiguously idle. */
    private const val SETTLE_MARGIN = 12L

    /** Ticks the character stands still for, so the sequence opens on the idle. */
    private const val STILL_TICKS = 30L

    /** Ticks walking - not running - out towards the ring, so the walk has its own frames. */
    private const val WALK_TICKS = 60L

    private const val LEGS = 8L

    /** Ticks per leg of the lap. Eight of these at the run speed keep the lap inside the stones. */
    private const val LEG_TICKS = 30L

    /** Ticks between one shot and the next. */
    private const val STEP = 15L

    /** The shot the lap ends on, and the charge begins. */
    private const val CHARGE_SHOT = ((STILL_TICKS + WALK_TICKS + LEGS * LEG_TICKS) / STEP).toInt()

    /** The shot the rock is put down on: one into the charge, when the run is straight. */
    private const val ROCK_SHOT = CHARGE_SHOT + 1

    /** Ticks spent running at the rock, which is long enough to reach it and stand against it. */
    private const val CHARGE_TICKS = 120L

    /** How many frames the sequence is: the still, the walk, the lap and the charge. */
    private const val SHOTS = CHARGE_SHOT + (CHARGE_TICKS / STEP).toInt()

    /** How far ahead of the character the rock is put down, in world units. */
    private const val ROCK_AHEAD = 6f

    /** The scale the clearing's big stones stand at. */
    private const val ROCK_SCALE = 2.4f

    /** A unit diagonal, so a diagonal leg is no faster than a straight one. */
    private const val DIAGONAL = 0.70710677f
}
