package dev.wildware.hollow.desktop

import com.github.quillraven.fleks.World
import com.github.quillraven.fleks.World.Companion.family
import dev.wildware.hollow.Fox
import dev.wildware.hollow.FoxMode
import dev.wildware.hollow.FoxWaves
import dev.wildware.hollow.HollowCombat
import dev.wildware.hollow.net.HollowClient
import dev.wildware.hollow.net.HollowServer
import dev.wildware.udea.core.NetRole
import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.Ticks
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.identity.NetIdIndex
import dev.wildware.udea.core.module.CoreModule
import dev.wildware.udea.core.spatial.Transform3D
import dev.wildware.udea.gas.Attributes
import dev.wildware.udea.net.transport.NetConditions
import dev.wildware.udea.net.transport.NetEndpoint
import dev.wildware.udea.net.transport.NetHarness
import dev.wildware.udea.net.transport.PeerId
import dev.wildware.udea.render.capture.CaptureRequest
import dev.wildware.udea.render.capture.capture
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.atan2
import kotlin.math.sqrt
import kotlin.system.exitProcess

/**
 * `sh gradlew :hollow:desktop:runFightShot -Phollow.shot.client=<0|1>`: a wave of foxes fought off
 * by two players, seen by **one of the two clients** of a real session, one PNG per known tick, with
 * the HUD in every frame (issue #252).
 *
 * ## What it photographs, and what it proves
 *
 * Everything in the picture crossed a network. This process runs an authoritative [HollowServer] and
 * two [HollowClient]s over `udea-net`'s in-process harness with 100ms of latency each way, and draws
 * the world of the client named by `-Phollow.shot.client`. Both clients hold the attack control
 * down, so the swings, the damage, the deaths and the bites are the server's and reach the picture
 * only by replication. `HollowFoxShot` is the same harness without the fight.
 *
 * It is a **check**, not only a camera. The run fails, with a message and a non-zero exit, when:
 *
 * - a frame does not carry the HUD's two solid panels at the colours [HollowHudLook] names. A HUD
 *   that drew into the window instead of the capture, or one that stopped drawing part way through,
 *   fails here - which is what a `CapturedUi` is for, and the only thing that can tell the two apart;
 * - no fox ever lost a hit point, so nothing was fought;
 * - no fox was ever killed, so the fight never resolved;
 * - no player was ever bitten, so the foxes never fought back.
 *
 * That is what makes it this ticket's evidence command: it goes red if the combat is taken out and
 * red if the HUD is taken out, for two different reasons and with two different messages.
 *
 * ## Two clients, two runs, the same fight
 *
 * One process has one Kool context, so one run draws one client. Run it twice - `client=0` and
 * `client=1` - and the two sequences are the same fight from two machines: the network is stepped
 * exactly one tick per frame and never by the wall clock, the server's match seed is fixed, and each
 * frame is taken at a named tick. Each run prints every fighter's hit points at every frame, so the
 * two transcripts are compared line for line rather than by eye.
 */
public object HollowFightShot {

    private const val OUTPUT_PROPERTY: String = "udea.shot.out"

    private const val CLIENT_PROPERTY: String = "hollow.shot.client"

    @JvmStatic
    public fun main(args: Array<String>) {
        val dir = Path.of(System.getProperty(OUTPUT_PROPERTY) ?: "build/reports/udea/fight")
        val drawn = System.getProperty(CLIENT_PROPERTY)?.trim()?.toIntOrNull() ?: 0
        require(drawn in 0..1) { "-D$CLIENT_PROPERTY must be 0 or 1, not $drawn" }

        val started = HollowLaunch.start(RenderMode.Offscreen, NetRole.Client, WAVES)
        val harness = NetHarness(clients = 2, initialConditions = NetConditions(latencyTicks = LATENCY))
        val server = HollowServer(harness.transport(PeerId.SERVER), waves = WAVES, matchSeed = SEED)
        val characters = ArrayList<NetId>()
        val clients = harness.clientPeers().mapIndexed { index, peer ->
            characters += server.addClient(peer)
            if (index == drawn) HollowClient(peer, harness.transport(peer), started.opened) else HollowClient(peer, harness.transport(peer))
        }
        val eye = clients[drawn]
        // Whose HUD the drawn client's window shows, and as of when: exactly what a launcher sets.
        started.scene.hudSource = eye
        val failures = ArrayList<String>()
        var hurt = false
        var killed = false
        var bitten = false
        var taken = 0
        try {
            wire(harness, server, clients, characters)
            val host = started.host
            // The render thread steps the whole session - server, both clients - one tick a frame,
            // until tick [until] has been simulated and no further, and then draws. Nothing is
            // paced by the wall clock.
            started.backend.drive { _ ->
                if (host.tick <= until) {
                    harness.step(1)
                    host.run(1)
                }
                host.frame(0f)
            }

            val slot = checkNotNull(started.backend.pipeline?.capture) { "the pipeline has no capture slot" }
            // The clearing's textures, before anything moves: two byte-identical frames in a row.
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
                System.err.println("[hollow.fight] the world did not settle in $FRAME_BUDGET frames")
                exitProcess(1)
            }

            val begin = host.tick
            // Up to the moment the wave has arrived and been replicated, then turn the camera to it.
            until = begin + AIM_AT
            slot.capture(CaptureRequest(afterTick = until))
            started.backend.onRenderThread { aim(started, eye, drawn) }
            Files.createDirectories(dir.toAbsolutePath())
            for (index in 0 until SHOTS) {
                val at = begin + FIRST_SHOT + index.toLong() * STEP
                until = at
                val result = slot.capture(CaptureRequest(afterTick = at))
                val name = "fight-client$drawn-%02d.png".format(index)
                Files.write(dir.resolve(name), result.bytes)
                taken++
                failures += HudPanels.missingFrom(name, result.bytes)
                // Read on the render thread, which owns the world: it has stopped stepping at [at],
                // so this is the tick the frame was drawn on.
                val seen = started.backend.onRenderThread { fighters(eye) }
                hurt = hurt || seen.any { it.isFox && it.health < it.maxHealth }
                bitten = bitten || seen.any { !it.isFox && it.health < it.maxHealth }
                killed = killed || seen.count { it.isFox } < WAVE_SIZE
                println("[hollow.fight] $name tick ${result.tick.value} server ${eye.serverTick.value}: ${line(seen)}")
            }
        } finally {
            clients.forEach(HollowClient::close)
            server.close()
            harness.close()
            started.close()
        }

        for (failure in failures) System.err.println("[hollow.fight] HUD missing: $failure")
        if (!hurt) System.err.println("[hollow.fight] no fox ever lost a hit point: nothing was fought")
        if (!killed) System.err.println("[hollow.fight] no fox was ever killed: the fight never resolved")
        if (!bitten) System.err.println("[hollow.fight] no player was ever bitten: the foxes never fought back")
        println("[hollow.fight] ${dir.toAbsolutePath()}: $taken frames of client $drawn")
        if (failures.isNotEmpty() || !hurt || !killed || !bitten) exitProcess(1)
    }

    /** The last tick the render thread simulates. Written by the main thread. */
    @Volatile
    private var until: Tick = Tick(0L)

    /** Wires the server and both clients to the harness, as `CombatReplicationTest` does. */
    private fun wire(
        harness: NetHarness,
        server: HollowServer,
        clients: List<HollowClient>,
        characters: List<NetId>,
    ) {
        harness.register(
            object : NetEndpoint {
                override val peer: PeerId = PeerId.SERVER

                override fun onReceive(from: PeerId, buffer: ByteArray, offset: Int, length: Int) {
                    server.onPacket(from, buffer, offset, length)
                }

                override fun onTick(tick: Tick) = server.tick()
            },
        )
        for ((index, client) in clients.withIndex()) {
            harness.register(
                object : NetEndpoint {
                    override val peer: PeerId = client.peer

                    override fun onReceive(from: PeerId, buffer: ByteArray, offset: Int, length: Int) {
                        client.onPacket(buffer, offset, length)
                    }

                    // Both players hunt: the attack control is held for the whole run, and the
                    // move axis points at the nearest living fox until the player is inside its
                    // own reach, when it stands and swings.
                    //
                    // Standing still is not enough for a picture of a *resolved* fight. A fox
                    // below `FoxBrain.FLEE_AT` runs away (issue #251), so two players holding
                    // their ground wound the whole wave and then never touch it again - measured,
                    // on the run before this one: five foxes at 18, 2, 16, 7 and 11 hit points and
                    // fourteen frames with every number frozen. A player runs at 5.4 m/s and a
                    // fleeing fox at 4.4, so a hunting player closes and finishes one.
                    override fun onTick(tick: Tick) {
                        val axis = towards(server, characters[index])
                        client.tick(
                            tick,
                            client.command(
                                tick = tick,
                                moveX = axis.first,
                                moveY = axis.second,
                                running = true,
                                attack = true,
                            ),
                        )
                    }
                },
            )
        }
    }

    /**
     * A unit move axis from [character] towards the nearest living fox in [server]'s world, or
     * `(0, 0)` when there is none or the player is already close enough to swing.
     *
     * The server's world because this process holds it and it is the authority on where a fox is;
     * what the axis becomes is an ordinary `MoveInput`, so it reaches the character through the
     * same seam a keyboard does. `HollowPlayerShot` scripts a route the same way.
     */
    private fun towards(server: HollowServer, character: NetId): Pair<Float, Float> {
        val host = server.host
        val me = host.ctx[CoreModule.NET_IDS].resolveOrNull(character) ?: return STILL
        val world = host.world
        return with(world) {
            val at = me[Transform3D]
            var closestX = 0f
            var closestY = 0f
            var closest = Float.MAX_VALUE
            world.family { all(Fox, Transform3D) }.forEach { fox ->
                if (fox[Fox].mode == FoxMode.Dead) return@forEach
                val there = fox[Transform3D]
                val dx = there.x - at.x
                val dy = there.y - at.y
                val away = dx * dx + dy * dy
                if (away < closest) {
                    closest = away
                    closestX = dx
                    closestY = dy
                }
            }
            if (closest == Float.MAX_VALUE || closest <= HOLD_SQUARED) {
                STILL
            } else {
                val length = sqrt(closest)
                closestX / length to closestY / length
            }
        }
    }

    /** No axis: stand still. */
    private val STILL: Pair<Float, Float> = 0f to 0f

    /**
     * How close a hunting player gets before it stands and swings, in metres, squared.
     *
     * Inside the swing's own reach, which `CombatRules.ATTACK_RANGE` fixes at 1.8 metres and this
     * file cannot name, because `CombatRules` is internal to `hollow:game` and a launcher has no
     * business knowing a game's damage numbers. 1.4 is comfortably under it either way.
     */
    private const val HOLD_SQUARED: Float = 1.4f * 1.4f

    /**
     * Points the drawing client's camera at its own character, turned to look out at the fight.
     * **Render thread**: the rig is that thread's.
     */
    private fun aim(started: HollowLaunch.Started, eye: HollowClient, drawn: Int) {
        val character = eye.character
        check(character != NetId.NONE) { "the drawing client was never told which character is its own" }
        started.follow(character)
        val rig = checkNotNull(started.scene.rig) { "the pipeline has no camera rig" }
        val world = started.host.world
        val ids = started.host.ctx[CoreModule.NET_IDS]
        var sumX = 0f
        var sumY = 0f
        var count = 0
        world.family { all(Fox, Transform3D) }.forEach { entity ->
            sumX += entity[Transform3D].x
            sumY += entity[Transform3D].y
            count++
        }
        val me = checkNotNull(ids.resolveOrNull(character)) { "$character is not in this world" }
        val at = with(world) { me[Transform3D] }
        // Presentation, not simulation: which way a camera looks is never read back into a world.
        val towards = if (count == 0) 90f else Math.toDegrees(atan2(sumY / count - at.y, sumX / count - at.x).toDouble()).toFloat()
        rig.yawDegrees = towards + drawn * SECOND_CLIENT_TURN
        rig.pitchDegrees = PITCH
        rig.distance = DISTANCE
    }

    /** One fighter in the drawing client's world: what it is, and what it has left. */
    private class FighterLine(val id: Int, val isFox: Boolean, val health: Float, val maxHealth: Float)

    private fun fighters(eye: HollowClient): List<FighterLine> {
        val host = eye.host
        val ids = host.ctx[CoreModule.NET_IDS]
        val combat = host.ctx[HollowCombat.KEY]
        val out = ArrayList<FighterLine>()
        host.world.family { all(Attributes) }.forEach { entity ->
            val attributes = entity[Attributes]
            out += FighterLine(
                id = ids.netIdOf(entity).raw,
                isFox = entity has Fox,
                health = attributes.base(combat.health),
                maxHealth = attributes.base(combat.maxHealth),
            )
        }
        out.sortBy { it.id }
        return out
    }

    private fun line(seen: List<FighterLine>): String = seen.joinToString(" ") {
        "${if (it.isFox) "fox" else "player"}${it.id}=%.0f".format(it.health)
    }

    /** Six ticks each way: 100ms. */
    private const val LATENCY = 6

    private const val SEED = 252L

    /** One wave, a second in: five foxes. The second wave is long after the last frame. */
    private val WAVES = FoxWaves(first = Tick(60L), every = Ticks(3_600L), size = WAVE_SIZE, growth = 2, cap = 24)

    /** How many foxes the one wave brings. Fewer than this alive means one was killed. */
    private const val WAVE_SIZE = 5

    /** Ticks after the settle to the camera turning: the wave has arrived and crossed the link. */
    private const val AIM_AT = 80L

    /** Ticks after the settle to the first frame: the pack is walking in out of the trees. */
    private const val FIRST_SHOT = 150L

    /** Ticks between frames: a fifth of a second. */
    private const val STEP = 12L

    /**
     * Frames: about four and a half seconds, which is the whole fight now that the players hunt.
     *
     * Measured from the run that set these: the wave arrives, the first bites land by tick 200, the
     * last fox dies by 320 and its corpse is gone by 410. Twenty-four frames from tick 152 at
     * [STEP] cover 152 to 428, so the sequence is the fight rather than the fight and then fourteen
     * identical frames of an empty clearing, which is what the first pass produced.
     */
    private const val SHOTS = 24

    /** How far the second client's camera is turned from the first's, in degrees. */
    private const val SECOND_CLIENT_TURN = 45f

    /** Higher and further back than a player's default, so the whole fight is in the picture. */
    private const val PITCH = 32f
    private const val DISTANCE = 9f

    private const val MIN_FRAMES = 10

    private const val FRAME_BUDGET = 600
}
