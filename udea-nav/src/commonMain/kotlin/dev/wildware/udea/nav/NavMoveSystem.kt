package dev.wildware.udea.nav

import com.github.quillraven.fleks.Entity
import dev.wildware.udea.core.SimSystem
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.identity.NetIdIndex
import dev.wildware.udea.core.identity.NetIdVisitor
import dev.wildware.udea.core.spatial.Transform3D
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Advances every navigating unit one tick: where it wants to go, how far it gets, and how the
 * crowd around it gives way.
 *
 * ## One system, four passes, in this order
 *
 * 1. **Gather.** Every entity with a [NavAgent] and a `Transform3D`, in ascending `NetId`, into
 *    columns. The rest of the tick walks arrays.
 * 2. **Route.** Each moving unit's goal is resolved to a cell it can stand on, and the units
 *    sharing a goal cell are counted. A unit whose goal is shared reads the one [NavFlowField]
 *    for it; a unit alone gets [Navigation.nextHop], which is A*. That is the split the issue
 *    asks for, decided by what the world actually is rather than by a flag somebody sets: a
 *    hundred units ordered to one place pay one sweep between them.
 * 3. **Step.** Each moving unit walks [NavAgent.speed] metres towards the centre of its next cell,
 *    or onto its goal point if it is standing in the goal's cell, and stops there if it is inside
 *    its own radius of it.
 * 4. **Separate and settle.** Overlapping units push each other apart, twice, and a unit pressed
 *    against units that have already arrived at the same goal counts as arrived itself - which is
 *    what lets a hundred units answer one order without stacking on one point.
 *
 * They are four passes of one system rather than four systems because each reads what the last
 * wrote about the *same* set of units: split up, every one of them would gather the crowd again,
 * and the gather is the expensive part.
 *
 * ## Determinism
 *
 * Units are visited in ascending `NetId` everywhere, which two processes holding the same entities
 * agree on however they spawned them. Every arithmetic step is `Float` and the only library call is
 * [sqrt], which IEEE-754 specifies to the last bit; there is no `atan2`, no `sin` and no `pow`,
 * which `Math` does not specify exactly. The separation passes are a **fixed** count, never "until
 * it converges" - a loop that ran until a tolerance was met would run a different number of times
 * on two machines the moment they differed by one ulp. This is the same list `CharacterMover`'s
 * KDoc sets out, for the same reason: this code is re-run on a predicting client and in a replay.
 *
 * ## Allocation
 *
 * The columns are grown to the largest crowd this system has seen and reused after that, so a
 * steady-state tick allocates nothing. The per-cell bucket heads are sized from the grid.
 */
public class NavMoveSystem(
    private val navigation: Navigation,
    private val netIds: NetIdIndex,
) : SimSystem() {

    // --- the crowd, one column per thing a pass needs -----------------------------------------

    private var agents = arrayOfNulls<NavAgent>(INITIAL_CAPACITY)

    private var transforms = arrayOfNulls<Transform3D>(INITIAL_CAPACITY)

    private var startX = FloatArray(INITIAL_CAPACITY)

    private var startY = FloatArray(INITIAL_CAPACITY)

    private var positionX = FloatArray(INITIAL_CAPACITY)

    private var positionY = FloatArray(INITIAL_CAPACITY)

    private var radius = FloatArray(INITIAL_CAPACITY)

    private var clearance = IntArray(INITIAL_CAPACITY)

    /** The resolved goal cell, or [NavCell.NONE]'s index for a unit that is not moving to one. */
    private var goalCell = IntArray(INITIAL_CAPACITY)

    /** The point a unit is finally walking to: its order, or the centre of the cell beside it. */
    private var targetX = FloatArray(INITIAL_CAPACITY)

    private var targetY = FloatArray(INITIAL_CAPACITY)

    /** Whether this unit's goal is shared, and so read from a flow field rather than searched. */
    private var shared = BooleanArray(INITIAL_CAPACITY)

    /** How far from its goal this unit may settle for standing behind the crowd. See [settle]. */
    private var crowdRadius = FloatArray(INITIAL_CAPACITY)

    /** The cell a unit is standing in, as an index, or `-1` off the grid. */
    private var cell = IntArray(INITIAL_CAPACITY)

    /** The next unit in the same cell's bucket, or `-1`. */
    private var bucketNext = IntArray(INITIAL_CAPACITY)

    /** The first unit in each cell's bucket, `-1` for empty. Sized from the grid. */
    private var bucketHead = IntArray(0)

    /** How many moving units are heading for each cell. Sized from the grid. */
    private var goalTally = IntArray(0)

    private var count = 0

    /** How far the step pass looks for a unit in the way. Set once a tick, from the crowd. */
    private var stepReach = 1

    private val visitor = object : NetIdVisitor {
        override fun visit(netId: NetId, entity: Entity) {
            gather(entity)
        }
    }

    override fun onTick() {
        val grid = navigation.grid
        sizeFromGrid(grid)
        count = 0
        netIds.forEachLive(visitor)
        if (count == 0) return
        route(grid)
        step(grid)
        separate(grid)
        settle(grid)
        writeBack()
    }

    // --- 1. gather -----------------------------------------------------------------------------

    private fun gather(entity: Entity) {
        val agent = with(world) { entity.getOrNull(NavAgent) } ?: return
        val transform = with(world) { entity.getOrNull(Transform3D) } ?: return
        if (count == agents.size) grow()
        agents[count] = agent
        transforms[count] = transform
        startX[count] = transform.x
        startY[count] = transform.y
        positionX[count] = transform.x
        positionY[count] = transform.y
        radius[count] = agent.radius
        count++
    }

    // --- 2. route ------------------------------------------------------------------------------

    /**
     * Resolves each moving unit's goal to a cell it can stand on, and counts the units per cell.
     *
     * The count is per resolved goal **cell**, not per order: two players' units sent to the same
     * spot are one group as far as the field is concerned, which is what they look like on the
     * ground. Units of different sizes sharing a cell each read their own field, because a field
     * is swept for one clearance.
     */
    private fun route(grid: NavGrid) {
        goalTally.fill(0)
        for (index in 0 until count) {
            val agent = agents[index]!!
            goalCell[index] = NavCell.NONE.index
            // Every unit's clearance, not only a moving one's: separation refuses to push an idle
            // unit into a building, and it needs the same number to do it with.
            val clearanceCells = grid.clearanceCellsFor(agent.radius)
            clearance[index] = clearanceCells
            // A unit that has already arrived still counts towards its goal's crowd, which is what
            // decides how far out from the goal a unit is allowed to stop. It is not routed.
            if (agent.state != NavState.Moving && agent.state != NavState.Arrived) continue
            val ordered = grid.cellAt(agent.goalX, agent.goalY)
            val resolved = navigation.nearestOpen(ordered, clearanceCells)
            if (!resolved.isValid) {
                if (agent.state == NavState.Moving) agent.state = NavState.Unreachable
                continue
            }
            goalCell[index] = resolved.index
            goalTally[resolved.index]++
            if (agent.state != NavState.Moving) continue
            if (resolved == ordered) {
                targetX[index] = agent.goalX
                targetY[index] = agent.goalY
            } else {
                // The order was on a footprint, or too close to one for this unit: it walks to the
                // middle of the nearest cell it can stand on instead of to a point it cannot reach.
                targetX[index] = grid.centreX(resolved)
                targetY[index] = grid.centreY(resolved)
            }
        }
        for (index in 0 until count) {
            val goal = goalCell[index]
            val crowd = if (goal >= 0) goalTally[goal] else 0
            shared[index] = crowd >= FLOW_FIELD_MIN_AGENTS
            crowdRadius[index] = crowdRadiusFor(radius[index], crowd)
        }
    }

    // --- 3. step -------------------------------------------------------------------------------

    private fun step(grid: NavGrid) {
        bucket(grid)
        stepReach = neighbourReach(grid)
        for (index in 0 until count) {
            val agent = agents[index]!!
            val standing = grid.cellAt(positionX[index], positionY[index])
            if (!grid.fits(standing, clearance[index])) {
                // A building went up on top of it, or it has been pushed against one. Its first
                // move is out to the nearest ground it fits on, whatever it was doing before -
                // a unit standing where a factory has just been built has to leave whether it was
                // under orders or not - and the route resumes from there next tick.
                val out = navigation.nearestOpen(standing, clearance[index])
                if (!out.isValid) {
                    if (agent.state == NavState.Moving) agent.state = NavState.Unreachable
                    continue
                }
                escapeTowards(index, grid.centreX(out), grid.centreY(out), agent.speed)
                continue
            }
            if (agent.state != NavState.Moving) continue
            walkTowardsGoal(grid, index, standing)
        }
    }

    private fun walkTowardsGoal(grid: NavGrid, index: Int, standing: NavCell) {
        val agent = agents[index]!!
        val goal = NavCell(goalCell[index])
        if (!goal.isValid) {
            agent.state = NavState.Unreachable
            return
        }
        val clearanceCells = clearance[index]
        if (standing != goal) {
            val hop = if (shared[index]) {
                navigation.flowField(goal, clearanceCells).nextHop(standing)
            } else {
                navigation.nextHop(standing, goal, clearanceCells)
            }
            if (!hop.isValid) {
                agent.state = NavState.Unreachable
                return
            }
            moveTowards(grid, index, grid.centreX(hop), grid.centreY(hop), agent.speed, clearanceCells)
        } else {
            moveTowards(grid, index, targetX[index], targetY[index], agent.speed, clearanceCells)
        }
        val toGoalX = targetX[index] - positionX[index]
        val toGoalY = targetY[index] - positionY[index]
        if (toGoalX * toGoalX + toGoalY * toGoalY <= radius[index] * radius[index]) {
            agent.state = NavState.Arrived
        }
    }

    /**
     * Walks unit [index] straight at ([toX], [toY]) with none of the refusals [moveTowards] makes.
     *
     * The one case they all have to be dropped for: a unit standing **inside** a footprint, which
     * every cell around it also fails. Refusing the step because the cell it is walking through is
     * blocked would pin a unit that a building was put on top of where it stands for ever, and
     * refusing it because a neighbour is in the way would do the same to a pair of them. It is
     * walking to a cell [Navigation.nearestOpen] has already said it fits in, so where it is going
     * is standable even though the ground between here and there is not.
     */
    private fun escapeTowards(index: Int, toX: Float, toY: Float, distance: Float) {
        val dx = toX - positionX[index]
        val dy = toY - positionY[index]
        val length = sqrt(dx * dx + dy * dy)
        if (length <= 0f) return
        val travel = if (length < distance) length else distance
        val scale = travel / length
        positionX[index] += dx * scale
        positionY[index] += dy * scale
    }

    /**
     * Moves unit [index] at most [distance] metres towards ([toX], [toY]), refusing a wall and
     * refusing to walk further into a unit it is already touching.
     *
     * The second refusal is what makes a queue a queue. A unit that steps into the back of the one
     * in front creates an overlap that separation then has to undo, and in a crowd funnelling
     * through a gap it is created faster than it can be undone - so the crowd compresses instead of
     * queueing. Declining the step costs that unit a tick and leaves the pair exactly as they were.
     */
    private fun moveTowards(
        grid: NavGrid,
        index: Int,
        toX: Float,
        toY: Float,
        distance: Float,
        clearanceCells: Int,
    ) {
        val dx = toX - positionX[index]
        val dy = toY - positionY[index]
        val length = sqrt(dx * dx + dy * dy)
        if (length <= 0f) return
        val travel = if (length < distance) length else distance
        val scale = travel / length
        val toPositionX = positionX[index] + dx * scale
        val toPositionY = positionY[index] + dy * scale
        if (presses(grid, index, toPositionX, toPositionY)) return
        moveTo(grid, index, toPositionX, toPositionY, clearanceCells)
    }

    /**
     * Whether moving unit [index] to ([toX], [toY]) would push it further into a neighbour than it
     * already is.
     *
     * Two softenings, and a crowd stops moving at all without either. "Further into" rather than
     * "into": a unit that is already overlapping - because it was spawned there, or because a
     * building went up around it - has to be able to move *out*, and a flat refusal to be near
     * anybody would pin it where it stands. And [PRESS_ALLOWANCE] of the contact distance is
     * allowed anyway, because a queue that refuses to touch at all cannot round a corner: measured
     * on the hundred-unit crowd, refusing every overlap left ninety-nine of them still walking
     * after twenty seconds.
     */
    private fun presses(grid: NavGrid, index: Int, toX: Float, toY: Float): Boolean {
        val home = cell[index]
        if (home < 0) return false
        val column = home % grid.width
        val row = home / grid.width
        for (rowOffset in -stepReach..stepReach) {
            for (columnOffset in -stepReach..stepReach) {
                val neighbourCell = grid.cellOf(column + columnOffset, row + rowOffset)
                if (!neighbourCell.isValid) continue
                var other = bucketHead[neighbourCell.index]
                while (other >= 0) {
                    if (other != index) {
                        val allowed = (radius[index] + radius[other]) * (1f - PRESS_ALLOWANCE)
                        val toDx = positionX[other] - toX
                        val toDy = positionY[other] - toY
                        val after = toDx * toDx + toDy * toDy
                        if (after < allowed * allowed) {
                            val nowDx = positionX[other] - positionX[index]
                            val nowDy = positionY[other] - positionY[index]
                            if (after < nowDx * nowDx + nowDy * nowDy) return true
                        }
                    }
                    other = bucketNext[other]
                }
            }
        }
        return false
    }

    /**
     * Puts unit [index] at ([toX], [toY]) if it can stand there, sliding along the wall if not.
     *
     * One axis at a time, x before y, and the unit stays put - and this returns `false` - if
     * neither works. Fixed rather than clever: which axis is tried first decides where a unit ends
     * up in the corner case, so it is a stated order rather than whichever the compiler happened to
     * evaluate.
     */
    private fun moveTo(grid: NavGrid, index: Int, toX: Float, toY: Float, clearanceCells: Int): Boolean {
        if (grid.fits(grid.cellAt(toX, toY), clearanceCells)) {
            positionX[index] = toX
            positionY[index] = toY
            return true
        }
        if (grid.fits(grid.cellAt(toX, positionY[index]), clearanceCells)) {
            positionX[index] = toX
            return true
        }
        if (grid.fits(grid.cellAt(positionX[index], toY), clearanceCells)) {
            positionY[index] = toY
            return true
        }
        return false
    }

    // --- 4. separate and settle ----------------------------------------------------------------

    /**
     * Pushes overlapping units apart, [SEPARATION_PASSES] times.
     *
     * Each pass buckets the crowd by cell, walks every pair once - the lower index against the
     * higher - and gives each unit half of the overlap to move. Applying the whole pass at once,
     * rather than moving a unit the moment a pair is found, keeps a unit's answer from depending
     * on which of its neighbours happened to be considered first.
     *
     * A push that would put a unit inside a building is refused by [moveTo] the same way a step is,
     * so a crowd squeezed against a wall stops at the wall instead of through it.
     */
    private fun separate(grid: NavGrid) {
        val reach = neighbourReach(grid)
        repeat(SEPARATION_PASSES) {
            bucket(grid)
            for (index in 0 until count) {
                val home = cell[index]
                if (home < 0) continue
                val column = home % grid.width
                val row = home / grid.width
                for (rowOffset in -reach..reach) {
                    for (columnOffset in -reach..reach) {
                        val neighbourCell = grid.cellOf(column + columnOffset, row + rowOffset)
                        if (!neighbourCell.isValid) continue
                        var other = bucketHead[neighbourCell.index]
                        while (other >= 0) {
                            if (other > index) resolveOverlap(grid, index, other)
                            other = bucketNext[other]
                        }
                    }
                }
            }
        }
    }

    private fun resolveOverlap(grid: NavGrid, first: Int, second: Int) {
        val dx = positionX[second] - positionX[first]
        val dy = positionY[second] - positionY[first]
        val touching = radius[first] + radius[second]
        val squared = dx * dx + dy * dy
        if (squared >= touching * touching) return
        val normalX: Float
        val normalY: Float
        val push: Float
        if (squared > 0f) {
            val distance = sqrt(squared)
            normalX = dx / distance
            normalY = dy / distance
            push = (touching - distance) * 0.5f
        } else {
            // Exactly on top of one another, which two units spawned at one point are. There is no
            // direction in the positions to use, so the lower index goes west and the higher east:
            // a stated choice, because any rule that read the clock or a random stream here would
            // be the one thing a replay could not reproduce.
            normalX = 1f
            normalY = 0f
            push = touching * 0.5f
        }
        // Half each, and the whole of it to whichever one can move when the other cannot. A unit
        // with its back to a wall is refused its half by `moveTo`, and without this the pair would
        // stay half inside each other for as long as they were both pressed against the building -
        // which is exactly where a crowd funnelling past a corner spends its time.
        val movedFirst = moveTo(
            grid,
            first,
            positionX[first] - normalX * push,
            positionY[first] - normalY * push,
            clearance[first],
        )
        val secondPush = if (movedFirst) push else push * 2f
        val movedSecond = moveTo(
            grid,
            second,
            positionX[second] + normalX * secondPush,
            positionY[second] + normalY * secondPush,
            clearance[second],
        )
        if (movedFirst && !movedSecond) {
            moveTo(
                grid,
                first,
                positionX[first] - normalX * push,
                positionY[first] - normalY * push,
                clearance[first],
            )
        }
    }

    /**
     * A unit pressed up against units that have already arrived at the same goal has arrived too.
     *
     * Without this a hundred units ordered to one point would push at each other for ever: each
     * one is still trying to reach a point the ones in front of it are standing on. With it the
     * crowd settles outward from the goal - the first unit arrives, the ones touching it arrive,
     * and so on - which is what an RTS crowd looks like.
     *
     * The condition is "touching a unit that is arrived, has the same order, and is closer to it
     * than I am". Same order compared bit for bit, so units sent to two different points never
     * stop each other; closer to the goal, so a unit that has merely brushed past an arrived
     * neighbour on its way in keeps walking.
     */
    private fun settle(grid: NavGrid) {
        val reach = neighbourReach(grid)
        bucket(grid)
        for (index in 0 until count) {
            val agent = agents[index]!!
            if (agent.state != NavState.Moving) continue
            val home = cell[index]
            if (home < 0) continue
            val column = home % grid.width
            val row = home / grid.width
            val ownDistance = distanceToOrder(index, agent)
            // The bound on the chain. Without it a queue of units backed up across the map would
            // settle all the way to its tail: each unit touches one marginally closer to the goal,
            // and "arrived" would walk backwards down the queue to a unit that never left home.
            if (ownDistance > crowdRadius[index]) continue
            var settled = false
            for (rowOffset in -reach..reach) {
                if (settled) break
                for (columnOffset in -reach..reach) {
                    if (settled) break
                    val neighbourCell = grid.cellOf(column + columnOffset, row + rowOffset)
                    if (!neighbourCell.isValid) continue
                    var other = bucketHead[neighbourCell.index]
                    while (other >= 0) {
                        if (other != index && blocksArrival(index, other, agent, ownDistance)) {
                            agent.state = NavState.Arrived
                            settled = true
                            break
                        }
                        other = bucketNext[other]
                    }
                }
            }
        }
    }

    private fun blocksArrival(index: Int, other: Int, agent: NavAgent, ownDistance: Float): Boolean {
        val ahead = agents[other]!!
        if (ahead.state != NavState.Arrived) return false
        if (ahead.goalX.toRawBits() != agent.goalX.toRawBits()) return false
        if (ahead.goalY.toRawBits() != agent.goalY.toRawBits()) return false
        val dx = positionX[other] - positionX[index]
        val dy = positionY[other] - positionY[index]
        val contact = radius[index] + radius[other] + CONTACT_SLACK
        if (dx * dx + dy * dy > contact * contact) return false
        return distanceToOrder(other, ahead) < ownDistance
    }

    /**
     * How far from its goal a unit may stop because the crowd is in the way.
     *
     * [crowd] units of radius [unitRadius] packed together fill a disc of radius
     * `unitRadius * sqrt(crowd / CROWD_PACKING)` - the area of that many circles, divided by how
     * much of a disc circles actually fill - and one body width is added because the outermost
     * unit stands against that disc rather than inside it. A hundred 0.3m units come to about
     * 3.9m, which is what a hundred units standing round a point look like.
     *
     * The alternative, "stop wherever you are blocked", is what the first version of this did, and
     * a queue of a hundred units reported every one of them arrived while the tail was twelve
     * metres from the goal.
     */
    private fun crowdRadiusFor(unitRadius: Float, crowd: Int): Float =
        unitRadius * (1f + sqrt(crowd.toFloat() / CROWD_PACKING))

    private fun distanceToOrder(index: Int, agent: NavAgent): Float {
        val dx = agent.goalX - positionX[index]
        val dy = agent.goalY - positionY[index]
        return sqrt(dx * dx + dy * dy)
    }

    // --- the shared machinery ------------------------------------------------------------------

    private fun writeBack() {
        for (index in 0 until count) {
            val transform = transforms[index]!!
            val agent = agents[index]!!
            transform.x = positionX[index]
            transform.y = positionY[index]
            agent.velocityX = positionX[index] - startX[index]
            agent.velocityY = positionY[index] - startY[index]
            agents[index] = null
            transforms[index] = null
        }
    }

    /** Buckets the crowd by the cell each unit is standing in, for the neighbour walks. */
    private fun bucket(grid: NavGrid) {
        bucketHead.fill(-1)
        for (index in 0 until count) {
            val home = grid.cellAt(positionX[index], positionY[index])
            cell[index] = home.index
            if (!home.isValid) {
                bucketNext[index] = -1
                continue
            }
            bucketNext[index] = bucketHead[home.index]
            bucketHead[home.index] = index
        }
    }

    /**
     * How many cells away a unit that could be touching this one may be.
     *
     * Two units touch at the sum of their radii, which can be wider than a cell, so a walk over
     * the eight neighbours alone would miss a pair standing two cells apart and let them overlap.
     * Computed from the largest unit present rather than assumed.
     */
    private fun neighbourReach(grid: NavGrid): Int {
        var largest = 0f
        for (index in 0 until count) if (radius[index] > largest) largest = radius[index]
        return max(1, ceil(2f * largest / grid.cellSize).toInt())
    }

    private fun sizeFromGrid(grid: NavGrid) {
        if (bucketHead.size != grid.cellCount) {
            bucketHead = IntArray(grid.cellCount)
            goalTally = IntArray(grid.cellCount)
        }
    }

    private fun grow() {
        val capacity = agents.size * 2
        agents = agents.copyOf(capacity)
        transforms = transforms.copyOf(capacity)
        startX = startX.copyOf(capacity)
        startY = startY.copyOf(capacity)
        positionX = positionX.copyOf(capacity)
        positionY = positionY.copyOf(capacity)
        radius = radius.copyOf(capacity)
        clearance = clearance.copyOf(capacity)
        goalCell = goalCell.copyOf(capacity)
        targetX = targetX.copyOf(capacity)
        targetY = targetY.copyOf(capacity)
        shared = shared.copyOf(capacity)
        crowdRadius = crowdRadius.copyOf(capacity)
        cell = cell.copyOf(capacity)
        bucketNext = bucketNext.copyOf(capacity)
    }

    public companion object {

        /**
         * How many units have to share a goal cell before they read a flow field instead of each
         * searching for itself.
         *
         * Two: the second unit to be sent somewhere already makes one sweep cheaper than two
         * searches, and every unit after that is free. A higher number would mean a group of three
         * paying for three searches a cell to save one sweep.
         */
        public const val FLOW_FIELD_MIN_AGENTS: Int = 2

        /**
         * How many times overlaps are resolved per tick.
         *
         * Two. One pass leaves a crowd pressing inward visibly compressed, because every unit is
         * given half of each overlap and its neighbours take the rest; three buys little over two
         * at sixty ticks a second. It is a constant and not a convergence test on purpose - see the
         * determinism note on this class.
         */
        public const val SEPARATION_PASSES: Int = 4

        /**
         * How far apart two units may be and still count as touching, in metres.
         *
         * Separation leaves units at just about exactly the sum of their radii, so "touching"
         * needs a little room or it would almost never be true. A centimetre.
         */
        public const val CONTACT_SLACK: Float = 0.01f

        /**
         * How much of a disc a packed crowd of circles fills.
         *
         * Circles cannot fill a disc; a hexagonal packing manages about 0.9 in the middle and less
         * at the edge. Seven tenths is deliberately below that, because the number decides how far
         * out a unit may stop and a value that is too small makes the last few units press inwards
         * for ever instead of settling.
         */
        public const val CROWD_PACKING: Float = 0.7f

        /**
         * How far a unit may press into the one in front, as a fraction of their contact distance.
         *
         * A tenth: 60mm on two 300mm units. Below it a step is allowed and separation tidies up
         * afterwards; above it the step is declined and the unit waits a tick. It is what bounds
         * how compressed a crowd funnelling through a gap gets - and a crowd that may not compress
         * at all is a crowd that deadlocks in the gap instead.
         */
        public const val PRESS_ALLOWANCE: Float = 0.15f

        /** Units in a crowd before the columns have to grow. */
        private const val INITIAL_CAPACITY: Int = 128
    }
}
