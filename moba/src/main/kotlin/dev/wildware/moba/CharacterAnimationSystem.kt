package dev.wildware.moba

import com.github.quillraven.fleks.World.Companion.family
import dev.wildware.udea.core.Cue
import dev.wildware.udea.core.SimSystem
import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.identity.NetId

/**
 * Fires each unit's animation notifies on the exact tick its state machine says they land on.
 *
 * ## What it does, and what it deliberately does not
 *
 * It reads [CharacterView] - which character, which state, which tick that state started - and
 * emits a [Cue] on the tick each of that animation's `animNotify` frames falls on. It does **not**
 * choose the state. Whatever gameplay system decides that a unit is swinging writes
 * `CharacterView.enter(Attack, tick)`, and this turns "frame 4 of the attack" into "tick 24 after
 * the swing began" and raises it.
 *
 * The split is the point. `moba` has two gameplay implementations landing in parallel right now
 * (`dev.wildware.moba.level` and `dev.wildware.moba.combat`), and an animation system that named
 * either one would be a renderer with a favourite. It names neither: the seam is one component
 * with four fields, and either side can drive it.
 *
 * ## Why the notify is emitted here and not by the renderer
 *
 * `udea-render`'s `DrawComponents` states the rule - "a notify that must affect the simulation is
 * raised through a gameplay event rather than by the animation advancing" - and a presentation
 * system firing an event the simulation acts on is exactly the inversion it forbids. Because the
 * playhead is tick-denominated ([CharacterAnimator]), the simulation can compute the same frame
 * number the renderer will draw without looking at the renderer at all. That is the only
 * arrangement in which a notify is both frame-accurate and deterministic: `attack_hit` on frame 4
 * of a six-frame animation fires on exactly one tick, on every machine, and a rewind takes it
 * back with everything else.
 *
 * The old game timed its damage off that notify, which is what made a swing *read* - the axe
 * connected halfway through the arc rather than the instant the ability fired. Hooking damage
 * onto the cue is a change in a combat system, not in this file.
 *
 * ## Stubbed, and named as such
 *
 * The cue carries [NetId.NONE] as its source rather than the unit that swung. `MobaModule` does
 * not publish the `NetIdIndex` on the context, so a system cannot turn a Fleks entity into a
 * `NetId` without a field being added to it - and a wrong source would be worse than an absent
 * one, because a consumer would place the effect on some other unit. [NotifyLog] carries the
 * character name in the meantime, which is enough for a test and not enough for a renderer.
 */
public class CharacterAnimationSystem(
    private val roster: CharacterRoster = MobaCharacters.roster,
    private val cueNames: CueNames = MobaCharacters.cues,
    /** Where fired notifies are recorded for tests and debug readouts. Never simulation state. */
    public val log: NotifyLog = NotifyLog(),
) : SimSystem() {

    private val units = family { all(CharacterView) }

    /** The tick this system last ran, so a notify window is `(lastTick, tick]`. */
    private var lastTick: Long = Long.MIN_VALUE

    /** Notify cues emitted since construction. A health signal a test can read. */
    public var emittedCount: Long = 0L
        private set

    override fun onTick() {
        val now = tick.value
        // `Long.MIN_VALUE` is the never-run sentinel and must not reach the subtraction below:
        // `MIN_VALUE - startTick` overflows to a large positive number, and every notify in every
        // animation would land inside that window on the first tick.
        //
        // A rewind moves the clock backwards. Re-firing every notify between the two ticks would
        // replay a hundred hits into the cue queue; treating a backwards step as a fresh start is
        // the only behaviour that leaves the world after a rewind indistinguishable from the
        // world before the ticks that were undone.
        val previous = if (lastTick == Long.MIN_VALUE || lastTick > now) now - 1 else lastTick
        units.forEach { entity ->
            val view = entity[CharacterView]
            val character = roster.at(view.character)
            val animation = character.animation(view.state)
            CharacterAnimator.notifiesBetween(
                animation = animation,
                after = previous - view.startTick,
                upTo = now - view.startTick,
                tickRate = ctx.clock.tickRate,
            ) { notify ->
                val id = cueNames.idOf(notify.name) ?: return@notifiesBetween
                ctx.cues.emit(Cue(id, Tick(now), NetId.NONE))
                log.record(NotifyRecord(character.name, notify.name, Tick(now)))
                emittedCount++
            }
        }
        lastTick = now
    }

}

/**
 * The last few notifies that fired, newest last.
 *
 * Bounded and overwriting, for the reason `CueQueue` is bounded: a process nobody reads this from
 * runs for hours, and an unbounded list of every hit in a session is a leak with a plausible
 * excuse. It is **not** simulation state and never enters a snapshot - a rewind does not unfire
 * what a debug readout has already shown.
 */
public class NotifyLog(
    /** How many records to keep. */
    public val capacity: Int = 64,
) {

    init {
        require(capacity > 0) { "capacity must be positive, was $capacity" }
    }

    private val records = ArrayDeque<NotifyRecord>(capacity)

    /** Records kept, oldest first. */
    public val entries: List<NotifyRecord> get() = records.toList()

    /** Total records offered, evicted ones included. */
    public var totalCount: Long = 0L
        private set

    /** Adds [record], evicting the oldest when full. */
    public fun record(record: NotifyRecord) {
        totalCount++
        if (records.size == capacity) records.removeFirst()
        records += record
    }

    override fun toString(): String = "NotifyLog(${records.size}/$capacity, total=$totalCount)"
}
