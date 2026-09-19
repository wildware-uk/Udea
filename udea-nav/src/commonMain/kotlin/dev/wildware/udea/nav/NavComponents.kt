package dev.wildware.udea.nav

import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.ComponentType
import dev.wildware.udea.annotations.Net
import dev.wildware.udea.annotations.RadiusHandle
import dev.wildware.udea.annotations.Replicated
import dev.wildware.udea.annotations.Sim
import kotlinx.serialization.Serializable

/**
 * What a unit under navigation is doing.
 *
 * A state rather than a pair of booleans, so that "ordered somewhere it cannot reach" is a thing a
 * game can draw a cross over, and so a `when` over it fails to compile when a case is added.
 */
public enum class NavState {

    /** No order. The unit stands still and the steering systems pass over it. */
    Idle,

    /** Walking to [NavAgent.goalX] / [NavAgent.goalY]. */
    Moving,

    /**
     * It got there. Either the goal itself, or the edge of the crowd already standing on it - a
     * hundred units ordered to one point cannot all stand on it, and one that has packed in
     * against units already there is as arrived as it is going to get.
     */
    Arrived,

    /**
     * There is no route. The goal is inside a building with no way in, or the unit itself is
     * walled in.
     *
     * Reported rather than retried silently: an order that cannot be carried out is something the
     * player has to be told about, and a unit that kept re-searching every tick would burn a
     * search per unit per tick on a question whose answer cannot change until the buildings do.
     * It **is** re-tried when the grid changes, because the grid changing is exactly the event
     * that can change the answer.
     */
    Unreachable,
}

/**
 * A ground unit that walks to where it is told, around what is in the way.
 *
 * ## The order is the state, and that is the whole design
 *
 * Everything here is a goal, a size or a speed - there is no path, no waypoint index and no "which
 * cell was I in last tick". A unit's next step is computed from the cell it is standing in and the
 * goal it holds, every tick, so the step it takes is a pure function of state a snapshot already
 * carries.
 *
 * That is what makes the second acceptance criterion of issue #264 true by construction: a client
 * that rewinds to a restored snapshot and re-simulates asks the same question of the same grid and
 * gets the same answer, and a replay does too. A unit following a stored path would need the path
 * in the snapshot, and every peer would have to have chosen the same one of several equally short
 * routes.
 *
 * ## The fields
 *
 * [goalX] and [goalY] are a point on the ground plane, not a cell: a cell is a detail of the grid,
 * and the grid is rebuilt whenever a building is placed. [radius] is the unit's own size - it
 * decides both the gaps it fits through and how close another unit may come. [speed] is metres per
 * **tick**, because seconds are a presentation unit (`AGENTS.md`, "The tick model"); [metresPerTick]
 * converts once, where a game says how fast its units are.
 *
 * Position is `Transform3D`, which the unit must also carry: this module moves an entity by writing
 * `Transform3D.x` and `y` and leaves `z` and every rotation to the game.
 *
 * `@Net` on the order and the unit's dimensions so a client sees both and predicts the same walk;
 * `@Sim` on the velocity, which is output rather than order - a client recomputes it from the same
 * inputs, and sending it would be sending a value the receiver is about to overwrite.
 */
@Serializable
@Replicated
public class NavAgent(
    /** Where it was told to go, on x. Meaningless unless [state] is [NavState.Moving]. */
    @Net public var goalX: Float = 0f,
    /** Where it was told to go, on y. */
    @Net public var goalY: Float = 0f,
    /** The unit's own radius in metres: what it fits through, and how close another may come. */
    @Net @RadiusHandle public var radius: Float = 0.5f,
    /** How far it walks in one tick, in metres. See [metresPerTick]. */
    @Net public var speed: Float = 0.05f,
    /** What it is doing. */
    @Net public var state: NavState = NavState.Idle,
    /** How far it moved on x last tick: output, for animation and for debug drawing. */
    @Sim public var velocityX: Float = 0f,
    /** How far it moved on y last tick. */
    @Sim public var velocityY: Float = 0f,
) : Component<NavAgent> {

    /** Orders it to ([x], [y]) and sets it walking. The one way a game gives an order. */
    public fun orderTo(x: Float, y: Float) {
        goalX = x
        goalY = y
        state = NavState.Moving
    }

    /** Cancels the order. The unit stops where it is. */
    public fun stop() {
        state = NavState.Idle
        velocityX = 0f
        velocityY = 0f
    }

    override fun type(): ComponentType<NavAgent> = NavAgent

    override fun toString(): String = "NavAgent($state to ($goalX, $goalY) r=$radius speed=$speed)"

    public companion object : ComponentType<NavAgent>() {

        /**
         * [metresPerSecond] as the metres-per-tick [speed] wants, at [tickRate].
         *
         * Here rather than in each game, so the conversion happens once and in one place: a
         * per-tick distance recomputed from seconds inside the tick loop is how a speed ends up
         * depending on how long a frame took.
         */
        public fun metresPerTick(metresPerSecond: Float, tickRate: Int): Float =
            metresPerSecond / tickRate.toFloat()
    }
}

/**
 * A building's footprint: the ground it stands on is not walkable.
 *
 * The rectangle is centred on the entity's `Transform3D` and is axis-aligned, which is what an RTS
 * building is. [NavGridSystem] stamps every one of these onto the grid, and re-stamps them all when
 * any of them appears, moves or is destroyed - so "updated when buildings are placed or destroyed"
 * is a comparison the tick makes rather than an event somebody has to remember to raise.
 *
 * `@Net` on both, so a client blocks the same ground the server does; the grid itself is never on
 * the wire, because it is derived from these and from nothing else.
 */
@Serializable
@Replicated
public class NavObstacle(
    /** Half the footprint's size along x, in metres. */
    @Net public var halfWidth: Float = 0.5f,
    /** Half the footprint's size along y, in metres. */
    @Net public var halfDepth: Float = 0.5f,
) : Component<NavObstacle> {

    override fun type(): ComponentType<NavObstacle> = NavObstacle

    override fun toString(): String = "NavObstacle(${halfWidth * 2}m x ${halfDepth * 2}m)"

    public companion object : ComponentType<NavObstacle>()
}
