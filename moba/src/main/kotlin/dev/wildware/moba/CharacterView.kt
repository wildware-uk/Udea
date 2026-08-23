package dev.wildware.moba

import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.ComponentType
import dev.wildware.udea.annotations.Net
import dev.wildware.udea.annotations.Replicated
import dev.wildware.udea.annotations.Sim

/**
 * Which character an entity wears, what it is doing, and when it started doing it.
 *
 * ## The one seam between the simulation and the picture
 *
 * A simulation system writes [state] and calls [enter]; the renderer reads all three fields and
 * draws. Nothing goes the other way. That is the whole contract, and it is why the *renderer* has
 * no state machine in it: `AnimationSetSystem.setAnimation` used to be reachable from simulation
 * code in the old tree, which made which picture was showing something a system that decides what
 * happens could branch on.
 *
 * ## It is `@Replicated`, and why that had to change
 *
 * It was not, and the KDoc here used to say so and name the consequence: "it does not survive a
 * `time.rewind`... a rewound world would draw six units with no art." That turned out to
 * understate it. A component outside `MobaGame.componentRegistry` is not merely un-restored - it
 * is invisible to capture, so a unit that died after the keyframe came back from a rewind as a
 * bare `Position` and `GameUnit` with no art, no combat state and no attributes. A play agent
 * measured exactly that: 22 units before a rewind, 27 after, five of them shells.
 *
 * Minting protocol identity for it is a decision about the wire format and not a renderer's to
 * take unilaterally, which is why it was deferred. It is taken now, in the reviewed file where
 * such decisions belong: `net-components.lock` at the repository root carries
 * `dev.wildware.moba.CharacterView`, and every id after it in the sorted space moved by one.
 * Nothing has shipped against the old numbering, so nothing decodes a recorded packet with it.
 *
 * All four fields are `@Net`. A client could in principle derive a unit's animation from its
 * replicated position and its combat state, but every rule that did so would be a second copy of
 * [CharacterStateSystem] living on the receiving side, free to disagree with the authoritative
 * one about what a unit is doing. Four small fields is the cheaper of the two.
 *
 * [startTick] is a tick and not a wall-clock instant, which is what makes the drawn frame and the
 * notify schedule pure functions of the simulation - see [CharacterAnimator].
 */
@Replicated
public class CharacterView(
    /**
     * Index into [CharacterRoster.entries]. An index and not a name because it is read once per
     * entity per frame on the render thread, and a string compare there is a per-frame allocation
     * waiting to happen the first time somebody writes `name == "orc"`.
     */
    @Net public var character: Int = 0,
    /** What the unit is doing. Written by simulation systems only. */
    @Net public var state: UnitState = UnitState.Idle,
    /** The tick [state] was entered on. The playhead is `clock.tick - startTick`. */
    @Net public var startTick: Long = 0L,
    /** Mirrors the sprite. The art faces right, so a unit walking left sets this. */
    @Net public var flipX: Boolean = false,
) : Component<CharacterView> {

    /**
     * Puts the unit in [state] as of [tick], restarting the animation.
     *
     * Re-entering the state that is already showing is a no-op rather than a restart: a system
     * that writes `enter(Walk, tick)` every tick while a unit walks would otherwise hold the
     * animation on frame 0 forever, which reads as a unit sliding along in a T-pose and is the
     * single most common way an animation state machine is got wrong.
     */
    public fun enter(state: UnitState, tick: Long) {
        if (this.state == state) return
        this.state = state
        this.startTick = tick
    }

    override fun type(): ComponentType<CharacterView> = CharacterView

    override fun toString(): String = "CharacterView(character=$character, $state@$startTick)"

    /** Fleks' handle for this component. */
    public companion object : ComponentType<CharacterView>()
}
