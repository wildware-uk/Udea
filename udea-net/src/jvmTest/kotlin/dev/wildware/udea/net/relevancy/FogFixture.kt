package dev.wildware.udea.net.relevancy

import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.net.transport.PeerId

/**
 * A body in a fog test: an id, a place, a side, and how far it can see.
 *
 * A mutable holder rather than a data class copied per tick, because a fog test's whole shape is
 * "move something a little and solve again", and reassigning a field is what a game does too.
 */
internal class Body(
    val netId: NetId,
    var x: Float,
    var y: Float,
    val team: Int,
    var sight: Float = 0f,
)

/**
 * The smallest thing that can hold a fog solve: a grid, a roster and a tick counter.
 *
 * Deliberately not a Fleks world. The properties under test here — what a team can see, what
 * changed, what it cost — are properties of [FogOfWar] over positions, and driving a world to
 * produce those positions would test the world instead. `AntiCheatWireTest` is where the real
 * world, the real ring and the real packer come in, because *there* the claim is about bytes.
 */
internal class FogFixture(
    settings: FogSettings = FogSettings(),
    teams: Int = 2,
    cellSize: Float = 8f,
    cells: Int = 32,
) {

    val fog: FogOfWar = FogOfWar(
        grid = VisionGrid(originX = 0f, originY = 0f, cellSize = cellSize, columns = cells, rows = cells),
        teams = teams,
        settings = settings,
        capacity = 512,
    )

    val bodies: MutableList<Body> = mutableListOf()

    private var nextIndex = 0
    private var tick = Tick.ZERO

    /** The tick the last [solve] ran for. */
    val lastTick: Tick get() = tick

    /** Adds a body and returns it. Ids are handed out densely from zero. */
    fun add(x: Float, y: Float, team: Int, sight: Float = 0f, generation: Int = 0): Body {
        val body = Body(NetId.of(nextIndex++, generation), x, y, team, sight)
        bodies += body
        return body
    }

    /** Puts [client] on [team]. */
    fun assign(client: PeerId, team: Int): PeerId {
        fog.assign(client, team)
        return client
    }

    /** Advances one tick and solves the fog from the current roster. */
    fun solve(): Tick {
        tick = Tick(tick.value + 1)
        fog.beginSolve(tick)
        for (body in bodies) fog.observe(body.netId, body.x, body.y, body.team, body.sight)
        fog.endSolve()
        return tick
    }

    /** Advances one tick and solves from [roster] only, so a test can make a body cease to exist. */
    fun solveWith(roster: List<Body>): Tick {
        tick = Tick(tick.value + 1)
        fog.beginSolve(tick)
        for (body in roster) fog.observe(body.netId, body.x, body.y, body.team, body.sight)
        fog.endSolve()
        return tick
    }
}
