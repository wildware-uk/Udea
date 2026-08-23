package dev.wildware.moba.ability

/**
 * Every presentation event this game emits, as a stable id.
 *
 * ## Why these are ints and not objects
 *
 * The old cues were `@Serializable object`s implementing `GameplayEffectCue`, and each one
 * *reached into presentation from inside the simulation*: `DamageCue` called
 * `world.system<AnimationSetSystem>().setAnimation(...)` and `world.system<SoundSystem>()`,
 * `PriestHealCue` spawned an entity, `KnockbackCue` applied a Box2D impulse. Three consequences,
 * all of which cost real time to debug:
 *
 * - a headless server ran the audio path, because the cue could not tell it was headless;
 * - a rollback re-simulation replayed every hit sound, because a direct call cannot be suppressed;
 * - `KnockbackCue` was not presentation at all - it moved a body from inside a "cue", so the
 *   simulation's outcome depended on whether the cue ran.
 *
 * Here a cue is an id plus a source, a target and two payload floats on
 * [dev.wildware.udea.gas.GasCueQueue], which `GasCueForwardSystem` drains into `GameContext.cues`
 * once per tick, in `SimPhase.Cleanup`. Presentation reads that queue. Suppressing a
 * re-simulation's cues is one field ([dev.wildware.udea.gas.CueMode]), and the *simulation* half
 * of the old knockback cue is now an impulse the ability applies itself.
 *
 * ## Stated plainly: nothing draws or plays these yet
 *
 * `udea-render` and audio are the other half of this wave. Every id below is emitted by a real
 * code path and forwarded to `GameContext.cues`; what a listener does with one is not decided
 * here. [nameOf] exists so a test - and an agent reading an event ring - can say which cue fired
 * without a lookup table of its own.
 */
public object MobaCues {

    /** An `ability/damage` application landed. `target` is who took it. */
    public const val DAMAGE: Int = 1

    /** A melee blow connected: the old `MeleeDamageCue`'s `melee_hit_sound_cue`. */
    public const val MELEE_HIT: Int = 2

    /** A melee swing started: the old `swoosh` animation notify. */
    public const val MELEE_SWOOSH: Int = 3

    /** A unit was pushed. `payload0`/`payload1` are the impulse, in world units per tick. */
    public const val KNOCKBACK: Int = 4

    /** A heal-over-time was applied: the old `PriestHealCue`, which spawned `effects/heal_effect`. */
    public const val HEAL: Int = 5

    /** The elite orc's spin started: the old `orc_elite_big_shout_cue` and its swoosh. */
    public const val SPIN: Int = 6

    /** An arrow left the bow. */
    public const val ARROW_FIRED: Int = 7

    /** An arrow hit a unit. */
    public const val ARROW_HIT: Int = 8

    /** A unit's health reached zero. */
    public const val DEATH: Int = 9

    /** [id]'s name, or `cue:<id>` for one this game does not define. */
    public fun nameOf(id: Int): String = when (id) {
        DAMAGE -> "damage"
        MELEE_HIT -> "melee_hit"
        MELEE_SWOOSH -> "melee_swoosh"
        KNOCKBACK -> "knockback"
        HEAL -> "heal"
        SPIN -> "spin"
        ARROW_FIRED -> "arrow_fired"
        ARROW_HIT -> "arrow_hit"
        DEATH -> "death"
        else -> "cue:$id"
    }
}
