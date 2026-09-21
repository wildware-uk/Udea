package dev.wildware.hollow.desktop

import com.github.quillraven.fleks.World
import com.github.quillraven.fleks.World.Companion.family
import dev.wildware.hollow.Fox
import dev.wildware.hollow.FoxWaves
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
import dev.wildware.udea.net.transport.NetConditions
import dev.wildware.udea.net.transport.NetEndpoint
import dev.wildware.udea.net.transport.NetHarness
import dev.wildware.udea.net.transport.PeerId
import dev.wildware.udea.render.capture.CaptureRequest
import dev.wildware.udea.render.capture.capture
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.atan2
import kotlin.system.exitProcess

/**
 * `sh gradlew :hollow:desktop:runFoxShot -Phollow.shot.client=<0|1>`: a wave of foxes closing in on
 * two players, seen by **one of the two clients** of a real session, one PNG per known tick
 * (issue #251).
 *
 * ## A session, not a staged scene
 *
 * Everything the picture holds crossed a network. This process runs an authoritative
 * [HollowServer] and two [HollowClient]s over `udea-net`'s in-process harness, with 100ms of latency
 * each way - the shape `FoxReplicationTest` asserts on - and draws the world of the client named by
 * `-Phollow.shot.client`. The foxes that client draws were spawned by the server's wave, steered by
 * the server's state machine, and put in the client's world by replication alone: no fox is created
 * on the drawing side at all.
 *
 * ## Two clients, two runs, the same foxes
 *
 * One process has one Kool context, so one run draws one client. Run it twice - `client=0` and
 * `client=1` - and the two sequences are the same match seen from two machines: the network is
 * stepped exactly one tick per frame and never by the wall clock, the server's match seed is fixed,
 * and each frame is taken at a named tick, so frame `n` of one run and frame `n` of the other show
 * the same server tick. Each run prints every fox's state and place at every frame, so the two
 * transcripts can be compared line for line rather than by eye.
 *
 * The camera is the rig a player has: behind and above the client's own character, turned to face
 * where the wave came from once it has arrived, and for the second client an eighth of a turn round
 * from there, so the two pictures are two players' views rather than one view twice.
 */
public object HollowFoxShot {

    private const val OUTPUT_PROPERTY: String = "udea.shot.out"

    private const val CLIENT_PROPERTY: String = "hollow.shot.client"

    @JvmStatic
    public fun main(args: Array<String>) {
        val dir = Path.of(System.getProperty(OUTPUT_PROPERTY) ?: "build/reports/udea/foxes")
        val drawn = System.getProperty(CLIENT_PROPERTY)?.trim()?.toIntOrNull() ?: 0
        require(drawn in 0..1) { "-D$CLIENT_PROPERTY must be 0 or 1, not $drawn" }

        val started = HollowLaunch.start(RenderMode.Offscreen, NetRole.Client)
        val harness = NetHarness(clients = 2, initialConditions = NetConditions(latencyTicks = LATENCY))
        val server = HollowServer(harness.transport(PeerId.SERVER), waves = WAVES, matchSeed = SEED)
        val clients = harness.clientPeers().mapIndexed { index, peer ->
            server.addClient(peer)
            if (index == drawn) HollowClient(peer, harness.transport(peer), started.opened) else HollowClient(peer, harness.transport(peer))
        }
        val eye = clients[drawn]
        var taken = 0
        try {
            wire(harness, server, clients)
            val host = started.host
            // The render thread steps the whole session - server, both clients - one tick a frame,
            // until tick [until] has been simulated and no further, and then draws. Nothing is paced
            // by the wall clock. "Simulated" is the capture's own test: tick `t` is done when the
            // clock has passed it.
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
                System.err.println("[hollow.foxes] the world did not settle in $FRAME_BUDGET frames")
                exitProcess(1)
            }

            val begin = host.tick
            // Up to the moment the wave has arrived and been replicated, then turn the camera to it,
            // so every frame is taken through the camera the sequence is about.
            until = begin + AIM_AT
            slot.capture(CaptureRequest(afterTick = until))
            started.backend.onRenderThread { aim(started, eye, drawn) }
            Files.createDirectories(dir.toAbsolutePath())
            for (index in 0 until SHOTS) {
                val at = begin + FIRST_SHOT + index.toLong() * STEP
                until = at
                val result = slot.capture(CaptureRequest(afterTick = at))
                val name = "foxes-client$drawn-%02d.png".format(index)
                Files.write(dir.resolve(name), result.bytes)
                taken++
                // Read on the render thread, which owns the world: it has stopped stepping at [at],
                // so this is the tick the frame was drawn on.
                val line = started.backend.onRenderThread { describe(eye, result.tick) }
                println("[hollow.foxes] $name $line")
            }
        } finally {
            clients.forEach(HollowClient::close)
            server.close()
            harness.close()
            started.close()
        }
        println("[hollow.foxes] ${dir.toAbsolutePath()}: $taken frames of client $drawn")
    }

    /** The last tick the render thread simulates. Written by the main thread. */
    @Volatile
    private var until: Tick = Tick(0L)

    /** Wires the server and both clients to the harness, as `FoxReplicationTest` does. */
    private fun wire(harness: NetHarness, server: HollowServer, clients: List<HollowClient>) {
        harness.register(
            object : NetEndpoint {
                override val peer: PeerId = PeerId.SERVER

                override fun onReceive(from: PeerId, buffer: ByteArray, offset: Int, length: Int) {
                    server.onPacket(from, buffer, offset, length)
                }

                override fun onTick(tick: Tick) = server.tick()
            },
        )
        for (client in clients) {
            harness.register(
                object : NetEndpoint {
                    override val peer: PeerId = client.peer

                    override fun onReceive(from: PeerId, buffer: ByteArray, offset: Int, length: Int) {
                        client.onPacket(buffer, offset, length)
                    }

                    // Nobody touches a key: both players stand where they joined, and the foxes come.
                    override fun onTick(tick: Tick) {
                        client.tick(tick, client.command(tick))
                    }
                },
            )
        }
    }

    /**
     * Points the drawing client's camera at its own character, turned to look out at where the
     * foxes are. **Render thread**: the rig is that thread's.
     */
    private fun aim(started: HollowLaunch.Started, eye: HollowClient, drawn: Int) {
        val character = eye.character
        check(character != NetId.NONE) { "the drawing client was never told which character is its own" }
        started.follow(character)
        val rig = checkNotNull(started.scene.rig) { "the pipeline has no camera rig" }
        val world = started.host.world
        val ids = started.host.ctx[CoreModule.NET_IDS]
        val (sumX, sumY, count) = foxCentre(world)
        val me = transform(world, ids, character)
        // Presentation, not simulation: which way a camera looks is never read back into a world.
        val towards = if (count == 0) 90f else Math.toDegrees(atan2(sumY / count - me.y, sumX / count - me.x).toDouble()).toFloat()
        rig.yawDegrees = towards + drawn * SECOND_CLIENT_TURN
        rig.pitchDegrees = PITCH
        rig.distance = DISTANCE
    }

    private fun foxCentre(world: World): Triple<Float, Float, Int> {
        var x = 0f
        var y = 0f
        var count = 0
        world.family { all(Fox, Transform3D) }.forEach { entity ->
            x += entity[Transform3D].x
            y += entity[Transform3D].y
            count++
        }
        return Triple(x, y, count)
    }

    /** A transcript line: the tick, what the drawing client was last told, and every fox. */
    private fun describe(eye: HollowClient, at: Tick): String {
        val host = eye.host
        val ids = host.ctx[CoreModule.NET_IDS]
        val foxes = StringBuilder()
        host.world.family { all(Fox, Transform3D) }.forEach { entity ->
            val fox = entity[Fox]
            val place = entity[Transform3D]
            foxes.append(" ${ids.netIdOf(entity).raw}:${fox.mode}@(%.3f, %.3f)".format(place.x, place.y))
        }
        return "tick ${at.value} server ${eye.serverTick.value}:$foxes"
    }

    private fun transform(world: World, ids: NetIdIndex, id: NetId): Transform3D {
        val entity = checkNotNull(ids.resolveOrNull(id)) { "$id is not in this world" }
        return with(world) { entity[Transform3D] }
    }

    /** Six ticks each way: 100ms. */
    private const val LATENCY = 6

    private const val SEED = 251L

    /** One wave, a second in: five foxes. The second wave is after the last frame. */
    private val WAVES = FoxWaves(first = Tick(60L), every = Ticks(3_600L), size = 5, growth = 2, cap = 24)

    /**
     * Ticks after the settle to the camera turning: the wave has arrived on the server's tick 60 and
     * crossed the link to the client.
     */
    private const val AIM_AT = 80L

    /** Ticks after the settle to the first frame, just after the camera has turned. */
    private const val FIRST_SHOT = 90L

    /** Ticks between frames: a third of a second. */
    private const val STEP = 20L

    /** Frames: seven seconds, from the wave arriving to the foxes standing at the players' heels. */
    private const val SHOTS = 21

    /** How far the second client's camera is turned from the first's, in degrees. */
    private const val SECOND_CLIENT_TURN = 45f

    /** Higher and further back than a player's default, so the whole pack is in the picture. */
    private const val PITCH = 32f
    private const val DISTANCE = 9f

    private const val MIN_FRAMES = 10

    private const val FRAME_BUDGET = 600
}
