package dev.wildware.moba.ability

/**
 * Every presentation event this game emits, as a stable id - and the one place ids are minted.
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
 * ## The defect this file used to be: two id spaces, one queue
 *
 * `GameContext.cues` is a single sink and **two** independent things mint ids into it - the
 * ability cues below, and `dev.wildware.moba.CueNames`, which numbers the animation notify names
 * this bundle declares. Both used to start at zero and count up by hand, so ids `1..6` named two
 * different events each: `SPIN` was indistinguishable from the `swoosh` notify and `KNOCKBACK`
 * from `fire_arrow`. A consumer holding a `Cue` could not tell which half emitted it, so
 * `MobaCueSounds` deliberately routed six of the nine authored cues to **silence** rather than
 * grunt every time an archer loosed an arrow.
 *
 * The fix is that ids are not written down any more. [mint] is the only thing in this game that
 * produces one: the ability block takes the first nine, [NOTIFY_BASE] is wherever that block
 * happened to end, and `CueNames` allocates the notify block from there. Adding a tenth ability
 * cue is one `mint()` call and moves the notify block up by one; adding a notify name to an
 * animation renumbers nothing but the notify block. Neither can land on the other, because
 * neither is a literal. `MobaAudioTest` pins the disjointness so a future block minted by hand
 * fails a test instead of going quietly silent.
 *
 * The cost, stated: these are `val` and not `const val`, so a `when` over them compiles to a
 * comparison chain rather than a `tableswitch`. Nine comparisons on the cue-drain path, which
 * runs once per emitted cue and not once per entity per tick, is the price of the ids being
 * allocated rather than typed.
 */
public object MobaCues {

    /**
     * The next id [mint] will hand out.
     *
     * Mutable, and mutated only while this object initialises - every call to [mint] below is a
     * property initialiser, so by the time any other code can reach `MobaCues` the counter has
     * stopped moving and reads [NOTIFY_BASE]. It is `private`, so nothing outside can restart it.
     */
    private var nextId: Int = FIRST_ID

    /** Takes the next free id. The only place in this game a cue id comes from. */
    private fun mint(): Int = nextId++

    /** An `ability/damage` application landed. `target` is who took it. */
    public val DAMAGE: Int = mint()

    /** A melee blow connected: the old `MeleeDamageCue`'s `melee_hit_sound_cue`. */
    public val MELEE_HIT: Int = mint()

    /** A melee swing started: the old `swoosh` animation notify. */
    public val MELEE_SWOOSH: Int = mint()

    /** A unit was pushed. `payload0`/`payload1` are the impulse, in world units per tick. */
    public val KNOCKBACK: Int = mint()

    /** A heal-over-time was applied: the old `PriestHealCue`, which spawned `effects/heal_effect`. */
    public val HEAL: Int = mint()

    /** The elite orc's spin started: the old `orc_elite_big_shout_cue` and its swoosh. */
    public val SPIN: Int = mint()

    /** An arrow left the bow. */
    public val ARROW_FIRED: Int = mint()

    /** An arrow hit a unit. */
    public val ARROW_HIT: Int = mint()

    /** A unit's health reached zero. */
    public val DEATH: Int = mint()

    /**
     * Every id the ability block holds, ascending, with the name each one carries.
     *
     * Declared *after* the block so it is a record of what was minted rather than a second place
     * the ids are written down - the whole point of [mint] is that this list and the constants
     * above cannot disagree. [names] and [ids] read it; so does the disjointness check in
     * `MobaCueSounds`, which needs the ability namespace as data and had to walk a hand-written
     * range before.
     */
    private val block: List<Pair<Int, String>> = listOf(
        DAMAGE to "damage",
        MELEE_HIT to "melee_hit",
        MELEE_SWOOSH to "melee_swoosh",
        KNOCKBACK to "knockback",
        HEAL to "heal",
        SPIN to "spin",
        ARROW_FIRED to "arrow_fired",
        ARROW_HIT to "arrow_hit",
        DEATH to "death",
    )

    /** Every ability-cue id, ascending. */
    public val ids: List<Int> = block.map { it.first }

    /** Every ability-cue name, in [ids] order. */
    public val names: List<String> = block.map { it.second }

    /**
     * The first id no ability cue holds: where `CueNames` starts the notify block.
     *
     * Read from the counter rather than written as a number, so the two blocks stay adjacent and
     * disjoint however many cues are minted above. This is the value that used to be `0` on both
     * sides and is the whole collision in one line.
     */
    public val NOTIFY_BASE: Int = nextId

    init {
        check(ids == ids.distinct()) { "mint() handed out a duplicate id: $ids" }
        check(ids == (FIRST_ID until NOTIFY_BASE).toList()) {
            "the ability block is not the contiguous range $FIRST_ID until $NOTIFY_BASE; a cue " +
                "id was written down instead of minted, and the notify block starts at the wrong " +
                "place. Ids are $ids"
        }
    }

    /** [id]'s name, or `cue:<id>` for one this game's ability block does not define. */
    public fun nameOf(id: Int): String {
        val at = id - FIRST_ID
        return if (at in names.indices) names[at] else "$UNNAMED_PREFIX$id"
    }

    /** What [nameOf] returns for an id outside the ability block. */
    public const val UNNAMED_PREFIX: String = "cue:"

    /**
     * The first id any block may use.
     *
     * One and not zero: `CueId(0)` is what an uninitialised `Int` field, a zeroed packet buffer
     * and a default-constructed `CueEvent` all hold, and a game whose first real cue is `0`
     * cannot tell "damage landed" from "nobody set this". Leaving it unminted costs one slot in
     * `AudioBindings`' dense table.
     */
    public const val FIRST_ID: Int = 1
}
