package dev.wildware.moba

import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.ComponentType

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
 * ## What it deliberately is not
 *
 * **Not `@Replicated` and not in `MobaGame.componentRegistry`.** Two consequences, both real and
 * both chosen rather than overlooked:
 *
 * - it does not cross the wire, so a networked client would have to derive a unit's animation
 *   from its replicated state rather than be told it;
 * - **it does not survive a `time.rewind`.** A snapshot restore rebuilds entities from the
 *   registry, and a component the registry does not know about is not rebuilt. A rewound world
 *   would draw six units with no art.
 *
 * The reason is the project-wide `net-components.lock`: a `@Replicated` component mints protocol
 * identity in a reviewed file at the repository root, and that is a decision about the wire
 * format rather than about a renderer. It is the right next step and it is not this change's to
 * take.
 *
 * [startTick] is a tick and not a wall-clock instant, which is what makes the drawn frame and the
 * notify schedule pure functions of the simulation - see [CharacterAnimator].
 */
public class CharacterView(
    /**
     * Index into [CharacterRoster.entries]. An index and not a name because it is read once per
     * entity per frame on the render thread, and a string compare there is a per-frame allocation
     * waiting to happen the first time somebody writes `name == "orc"`.
     */
    public var character: Int = 0,
    /** What the unit is doing. Written by simulation systems only. */
    public var state: UnitState = UnitState.Idle,
    /** The tick [state] was entered on. The playhead is `clock.tick - startTick`. */
    public var startTick: Long = 0L,
    /** Mirrors the sprite. The art faces right, so a unit walking left sets this. */
    public var flipX: Boolean = false,
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
