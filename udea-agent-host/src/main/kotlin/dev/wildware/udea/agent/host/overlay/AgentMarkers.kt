package dev.wildware.udea.agent.host.overlay

import com.github.quillraven.fleks.World
import dev.wildware.udea.agent.activity.AgentActivityRing
import dev.wildware.udea.agent.activity.AgentSessionId
import dev.wildware.udea.agent.activity.AnchorKind
import dev.wildware.udea.agent.query.AgentComponentIndex
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.identity.NetIdIndex

/**
 * Where an entity is *right now*, by packed [NetId].
 *
 * ## The `false` case is the interesting one
 *
 * A marker must **track** the entity it rings as the entity moves, so it cannot cache a position
 * from the moment the tool was called - the ring stores what the call was *about*, not where the
 * subject was. And a `NetId` whose generation has gone stale must draw **nothing**: the index is
 * dense and recycled, so ringing "whatever is in slot 7 now" would put a marker on an unrelated
 * entity and tell a human the agent had inspected it.
 *
 * That is precisely what [NetIdIndex.resolveOrNull] already guarantees - *"it is never the wrong
 * entity: that is the whole point of the generation counter"* - so [NetIdEntityLocator] is a
 * thin, honest adapter over it rather than a second implementation of identity.
 */
public fun interface EntityLocator {

    /**
     * Writes the world position of the entity with this packed NetId into [out].
     *
     * @param out a two-slot array the caller owns and reuses, `[x, y]`.
     * @return `false` when the id is stale, freed, unknown, or the entity carries no position.
     *   The caller must draw nothing; there is deliberately no "last known position" fallback.
     */
    public fun locate(packedNetId: Int, out: FloatArray): Boolean

    public companion object {
        /** Nothing is ever locatable. What an instance with no world index is wired with. */
        public val NONE: EntityLocator = EntityLocator { _, _ -> false }
    }
}

/**
 * [EntityLocator] over the real [NetIdIndex] and the real component index.
 *
 * Reads through [AgentComponentIndex.position] - the same `position.x` / `position.y`
 * convention `world.query_entities` uses for its `near` filter and its `pos` projection - so the
 * overlay marks an entity in the same place a query says it is. A second notion of "where things
 * are" here is exactly how an overlay ends up disagreeing with the tool output beside it.
 *
 * A world with no position component at all returns `false` for everything rather than throwing.
 * [AgentComponentIndex.requirePosition] throws a typed `bad_query`, which is right for a tool
 * answering an agent and wrong here: an exception thrown inside a frame would take the render
 * thread down over a cosmetic marker.
 */
public class NetIdEntityLocator(
    private val world: World,
    private val netIds: NetIdIndex,
    private val components: AgentComponentIndex,
) : EntityLocator {

    /** Resolved once: a per-frame per-marker lookup for a value fixed at construction. */
    private val position = components.position

    override fun locate(packedNetId: Int, out: FloatArray): Boolean {
        require(out.size >= 2) { "out needs two slots" }
        val reference = position ?: return false
        val netId = NetId.ofRaw(packedNetId)
        // The generation check lives here, in one call, and not in an `if` an overlay author
        // could forget: a stale id resolves to null, never to the recycled occupant.
        val entity = netIds.resolveOrNull(netId) ?: return false
        val x = reference.component.read(world, entity, reference.xIndex) as? Float ?: return false
        val y = reference.component.read(world, entity, reference.yIndex) as? Float ?: return false
        out[0] = x
        out[1] = y
        return true
    }

    override fun toString(): String = "NetIdEntityLocator(position=${position?.component?.name})"
}

/**
 * The world-space markers the overlay draws: a ring that follows an anchored entity, a cross
 * where a positional call landed (spec 3.7, issue #160).
 *
 * ## What is refreshed when
 *
 * Two different rates, and conflating them is what makes an overlay either stale or expensive:
 *
 * - **which** calls have markers changes when the agent calls a tool. Collected on
 *   [AgentActivityRing.version] changing, like the panel;
 * - **where** each marker is changes every frame, because the entity is moving. Resolved through
 *   [EntityLocator] on the draw pass.
 *
 * So the collected form is a packed anchor, never a position. A marker that cached a position
 * would sit still while its entity walked away, which is worse than no marker: it points at
 * empty ground with complete confidence.
 *
 * ## Fading
 *
 * A marker is drawn for [ttlSeconds] of **wall** time after its call, then stops. Wall and not
 * ticks, because the overlay is presentation state and must fade on a paused game too - a human
 * who pauses to look at something should not be left with every marker of the session frozen on
 * screen at once. The elapsed time is accumulated from the `dtSeconds` the pipeline hands the
 * overlay, so this class reads no clock of any kind.
 */
public class AgentMarkers(
    private val activity: AgentActivityRing,
    /** How long a marker stays up, in wall seconds. */
    public val ttlSeconds: Float = DEFAULT_TTL_SECONDS,
    /** How many markers are drawn at once, newest first. */
    public val capacity: Int = DEFAULT_CAPACITY,
) {

    init {
        require(ttlSeconds > 0f) { "ttlSeconds must be positive, was $ttlSeconds" }
        require(capacity > 0) { "a marker set holds at least one, was $capacity" }
    }

    private val kinds = arrayOfNulls<AnchorKind>(capacity)
    private val netIds = IntArray(capacity)
    private val xs = FloatArray(capacity)
    private val ys = FloatArray(capacity)
    private val sessions = IntArray(capacity)
    private val writes = BooleanArray(capacity)

    /**
     * Wall seconds since each marker's call was recorded.
     *
     * Reset to zero for a marker seen for the first time and advanced by `dtSeconds` thereafter,
     * so a marker that was already on screen keeps its age across a refresh and does not restart
     * its fade every time the agent calls an unrelated tool.
     */
    private val ages = FloatArray(capacity)

    private val commandIds = LongArray(capacity)

    /** Markers currently collected, including any that have aged out. */
    public var count: Int = 0
        private set

    private var seenVersion: Long = -1L

    /** Marker collections since construction. The allocation test's subject. */
    public var refreshes: Long = 0L
        private set

    /**
     * Advances every marker's age and re-collects from the ring if it has changed.
     *
     * @param dtSeconds wall seconds since the previous frame, as the pipeline clamps it.
     */
    public fun update(dtSeconds: Float) {
        require(dtSeconds >= 0f) { "dtSeconds must not be negative, was $dtSeconds" }
        val version = activity.version
        if (version != seenVersion) {
            seenVersion = version
            collect()
            refreshes++
        }
        // Ages advance *after* the collect, so a marker first seen on this frame absorbs this
        // frame's elapsed time rather than starting from zero. That matters for exactly one
        // case, and it is not a rounding detail: while the overlay is switched off the frames
        // still run and still carry seconds, so a marker collected on the frame the overlay
        // comes back on would otherwise be born fresh - and a human turning the overlay on
        // would see a burst of markers for calls made minutes ago.
        for (index in 0 until count) ages[index] += dtSeconds
    }

    /**
     * Draws every live marker through [canvas], skipping any whose anchor cannot be resolved.
     *
     * @param projector world to screen. A marker off screen draws nothing.
     * @param locator where an anchored entity is now. A stale generation draws nothing.
     */
    public fun draw(canvas: OverlayCanvas, projector: WorldProjector, locator: EntityLocator) {
        for (index in 0 until count) {
            val age = ages[index]
            if (age >= ttlSeconds) continue
            val kind = kinds[index] ?: continue
            val worldX: Float
            val worldY: Float
            when (kind) {
                AnchorKind.ENTITY -> {
                    // The stale-generation case: nothing is drawn at all, rather than a ring
                    // around whatever recycled the slot.
                    if (!locator.locate(netIds[index], scratchWorld)) continue
                    worldX = scratchWorld[0]
                    worldY = scratchWorld[1]
                }

                AnchorKind.POINT -> {
                    worldX = xs[index]
                    worldY = ys[index]
                }

                AnchorKind.NONE -> continue
            }
            if (!projector.project(worldX, worldY, scratchScreen)) continue

            val write = writes[index]
            val base = OverlayPalette.forSession(AgentSessionId(sessions[index]))
            // Two channels at once: the fade says how long ago, and the read/write dimming says
            // whether it changed anything. A read at full age is faint on purpose.
            val fade = 1f - age / ttlSeconds
            val colour = OverlayPalette.withAlpha(
                base,
                fade * if (write) 1f else OverlayPalette.READ_ALPHA,
            )
            val thickness = if (write) WRITE_THICKNESS else READ_THICKNESS
            if (kind == AnchorKind.ENTITY) {
                canvas.ring(scratchScreen[0], scratchScreen[1], ENTITY_RADIUS, thickness, colour)
            } else {
                canvas.cross(scratchScreen[0], scratchScreen[1], POINT_SIZE, thickness, colour)
            }
        }
    }

    override fun toString(): String = "AgentMarkers($count/$capacity, ttl ${ttlSeconds}s)"

    /**
     * Re-reads the newest anchored calls out of the ring.
     *
     * Ages are carried across by command id, not by slot: the ring's newest-first order shifts
     * every time a call is recorded, so matching on position would restart the fade of every
     * marker whenever the agent called anything at all.
     */
    private fun collect() {
        // Snapshot the ages that are about to be re-indexed. Bounded by `capacity`, so this is
        // two fixed-size arrays and no allocation.
        System.arraycopy(commandIds, 0, previousIds, 0, capacity)
        System.arraycopy(ages, 0, previousAges, 0, capacity)
        val previousCount = count

        count = 0
        activity.forEachRecent(capacity) { call ->
            if (count >= capacity) return@forEachRecent
            if (call.anchorKind == AnchorKind.NONE) return@forEachRecent
            val slot = count
            kinds[slot] = call.anchorKind
            netIds[slot] = call.anchorNetId
            xs[slot] = call.anchorX
            ys[slot] = call.anchorY
            sessions[slot] = call.session.raw
            writes[slot] = AgentCallKind.isWrite(call.toolName)
            commandIds[slot] = call.commandId
            ages[slot] = ageOf(call.commandId, previousIds, previousAges, previousCount)
            count++
        }
    }

    private fun ageOf(
        commandId: Long,
        ids: LongArray,
        previous: FloatArray,
        previousCount: Int,
    ): Float {
        for (index in 0 until previousCount) {
            if (ids[index] == commandId) return previous[index]
        }
        return 0f
    }

    private val previousIds = LongArray(capacity)
    private val previousAges = FloatArray(capacity)

    /** Reused by [draw]. Not shared with [scratchScreen]: the two are live at the same time. */
    private val scratchWorld = FloatArray(2)

    private val scratchScreen = FloatArray(2)

    public companion object {

        /**
         * Four seconds.
         *
         * Long enough to look up from a keyboard and see what the agent just touched, short
         * enough that a busy session does not leave the map covered in rings. Deliberately
         * shorter than `agent.say`'s default caption, which describes a piece of work rather
         * than one call.
         */
        public const val DEFAULT_TTL_SECONDS: Float = 4f

        /** Eight markers, matching the depth a human can take in at a glance. */
        public const val DEFAULT_CAPACITY: Int = 8

        /** Screen-pixel radius of an entity ring. */
        public const val ENTITY_RADIUS: Float = 18f

        /** Screen-pixel span of a point cross. */
        public const val POINT_SIZE: Float = 14f

        /** A write is drawn thicker as well as brighter: two channels, not one. */
        public const val WRITE_THICKNESS: Float = 2.5f

        /** A read is thin. */
        public const val READ_THICKNESS: Float = 1.5f
    }
}
