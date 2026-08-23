package dev.wildware.moba

import dev.wildware.udea.assets.AnimNotify
import dev.wildware.udea.assets.AssetId
import dev.wildware.udea.assets.AssetRegistry
import dev.wildware.udea.assets.SpriteAnimation
import dev.wildware.udea.assets.SpriteAnimationSet
import dev.wildware.udea.assets.SpriteSheet
import dev.wildware.udea.core.CueId
import dev.wildware.udea.core.Tick
import kotlin.math.roundToLong

/**
 * The five states every unit in this game can be in, and the id suffix that names each one.
 *
 * ## Why a suffix and not a map
 *
 * The old tree carried this as `character(animationMap = mapOf("idle" to reference(...)))`, and
 * that is the better spelling. It is not available: `character` is `AssetKind.Unpublishable`, so
 * a `character(...)` declaration packs as an opaque record with no runtime type and a game
 * cannot load a bundle whose units are opaque records (see `moba/assets/character/orc.udea.kts`).
 * `SpriteAnimationSet` - which *is* publishable - is an ordered list of references with no keys
 * on it at all.
 *
 * So the key is carried in the asset id: `character/orc_walk` is the `walk` of `orc`. That is a
 * convention rather than a type, and the honest cost is stated here rather than buried: nothing
 * in the compiler enforces it, so a misnamed animation is caught by [CharacterRoster]'s own
 * `require` at bundle-open time instead of by the asset validator at build time. It is checked
 * loudly and early, and it goes away when issue #84 gives `character` a runtime type.
 */
public enum class UnitState(
    /** The last underscore-separated word of the animation's id. */
    public val suffix: String,
) {
    /** Standing still. The state a unit is in when nothing else applies. */
    Idle("idle"),

    /** Moving. */
    Walk("walk"),

    /** Swinging. This is the state whose animation carries the `attack_hit` notify. */
    Attack("attack"),

    /** Taking a hit. Not interruptible in the authored data. */
    Hit("hit"),

    /** Dying. Not interruptible, and does not loop. */
    Death("death"),
    ;

    public companion object {
        /** The state whose [suffix] is [suffix], or `null` for an animation that is not a state. */
        public fun bySuffix(suffix: String): UnitState? = entries.firstOrNull { it.suffix == suffix }
    }
}

/**
 * Everything about one character that is a pure function of the bundle: its art, split by state.
 *
 * Holds no GL object and no Fleks type, on purpose. `CharacterRenderSystem` needs textures and
 * `CharacterAnimationSystem` needs frame counts and notify frames, and only one of those two may
 * touch a GL context - so the shared half is here, in a class both can hold, and neither module
 * boundary has to be crossed to get at it.
 */
public class CharacterEntry(
    /** The unit's name, as `blueprint/<name>` spells it. */
    public val name: String,
    /** The `spriteAnimationSet` this was read from. */
    public val animationSet: AssetId,
    /** Every animation in the character's set, by the state its id names. */
    public val states: Map<UnitState, SpriteAnimation>,
    /** Animations in the set that are not one of the five states: a spin, a heal, a bow shot. */
    public val extras: Map<String, SpriteAnimation>,
    /** The sheet behind each state's animation, for frame counts and for the authored scale. */
    public val sheets: Map<UnitState, SpriteSheet>,
) {

    init {
        val missing = UnitState.entries.filter { it !in states }
        require(missing.isEmpty()) {
            "character '$name' has no animation for $missing. Every unit must be able to stand, " +
                "walk, swing, flinch and die, because `CharacterAnimationSystem` can put it in any " +
                "of those states and a renderer with no frame to draw draws nothing at all - " +
                "which looks like art direction rather than a missing asset. The animation for " +
                "state `s` is the one in `${animationSet.value}`'s animation set whose id ends " +
                "`_${'$'}{s.suffix}`."
        }
    }

    /** The animation for [state]. Never null; the constructor refused a set that was short one. */
    public fun animation(state: UnitState): SpriteAnimation = states.getValue(state)

    /** How many frames [state] holds. */
    public fun frameCount(state: UnitState): Int = sheets.getValue(state).frameCount

    override fun toString(): String = "CharacterEntry($name, ${states.size} states)"
}

/**
 * Every character the bundle declares, read out of the asset graph rather than listed in Kotlin.
 *
 * ## How a character is found
 *
 * By id: an asset called `character/<name>_animation_set` **is** the character called `<name>`.
 * Its `SpriteAnimationSet` names the animations, each animation names its sheet, and each
 * animation's id suffix names the state it is for.
 *
 * That is a convention rather than a type, and the reason is worth stating rather than hiding:
 * `character(...)` - the declaration that carried the name, the animation map, the sounds, the
 * attributes and the ability list in one place - is `AssetKind.Unpublishable`, so it packs as an
 * opaque record with no runtime type and a game cannot load a bundle whose units are opaque
 * records. `spriteAnimationSet` is publishable and is an ordered list of references with no keys
 * on it at all. So the keys live in the ids, and this class checks them loudly at bundle-open
 * time instead of the asset validator checking them at build time. It goes away when issue #84
 * gives `character` a runtime type, and this class becomes a `registry[blueprint.character]`.
 *
 * Adding a seventh character is therefore one file under `moba/assets/character/` and no Kotlin.
 * That is the property this class exists for; a `listOf("orc", "priest", ...)` here would have
 * been three lines shorter and would have made the assets decorative.
 */
public class CharacterRoster private constructor(
    /** The characters, sorted by name so two processes over one bundle agree on every index. */
    public val entries: List<CharacterEntry>,
) {

    private val indices: Map<String, Int> =
        entries.withIndex().associate { (at, entry) -> entry.name to at }

    /** How many characters the bundle declares. */
    public val size: Int get() = entries.size

    /** The character at [index], wrapping, so an index can never be out of range. */
    public fun at(index: Int): CharacterEntry = entries[Math.floorMod(index, entries.size)]

    /** The index of the character called [name], or `-1`. */
    public fun indexOf(name: String): Int = indices[name] ?: -1

    /** The character called [name], or `null`. */
    public fun byName(name: String): CharacterEntry? = indices[name]?.let(entries::get)

    override fun toString(): String = "CharacterRoster(${entries.map { it.name }})"

    public companion object {

        /** The id prefix every character's assets live under. */
        public const val PREFIX: String = "character/"

        /** The id suffix that makes a `spriteAnimationSet` a character rather than an effect. */
        public const val SET_SUFFIX: String = "_animation_set"

        /**
         * Reads every character in [registry].
         *
         * @throws IllegalStateException when a set names an animation the graph does not hold, or
         *   a character is short one of the five states. Loud in both cases: a roster that
         *   silently dropped a character would render a unit with no art, and "one of my units is
         *   invisible" is the hardest class of bug in a renderer to attribute.
         */
        public fun read(registry: AssetRegistry): CharacterRoster {
            val entries = registry.ids
                .filter { it.value.startsWith(PREFIX) && it.value.endsWith(SET_SUFFIX) }
                .mapNotNull { id -> (registry.find(id) as? SpriteAnimationSet)?.let { id to it } }
                .map { (id, set) -> entryOf(registry, id, set) }
                .sortedBy { it.name }
            check(entries.isNotEmpty()) {
                "the bundle declares no `$PREFIX*$SET_SUFFIX`, so this game has no characters at " +
                    "all. `:moba:udeaPackBundle` reports what it packed; a character is a " +
                    "`spriteAnimationSet` under `moba/assets/character/`."
            }
            return CharacterRoster(entries)
        }

        private fun entryOf(
            registry: AssetRegistry,
            id: AssetId,
            set: SpriteAnimationSet,
        ): CharacterEntry {
            val name = id.value.removePrefix(PREFIX).removeSuffix(SET_SUFFIX)
            val states = LinkedHashMap<UnitState, SpriteAnimation>()
            val extras = LinkedHashMap<String, SpriteAnimation>()
            val sheets = LinkedHashMap<UnitState, SpriteSheet>()
            for (ref in set.animations) {
                val animation = checkNotNull(registry.find(ref.id) as? SpriteAnimation) {
                    "'${id.value}' names '${ref.id}', which the graph does not hold as an animation"
                }
                val suffix = animation.id.value.substringAfterLast('_')
                val state = UnitState.bySuffix(suffix)
                if (state == null) {
                    extras[suffix] = animation
                } else {
                    states[state] = animation
                    sheets[state] = checkNotNull(registry.find(animation.sheet.id) as? SpriteSheet) {
                        "animation '${animation.id}' names sheet '${animation.sheet.id}', which " +
                            "the graph does not hold"
                    }
                }
            }
            return CharacterEntry(name, id, states, extras, sheets)
        }
    }
}

/**
 * The bundle's roster and its cue table, read once per process.
 *
 * Process-wide for the reason `MobaAssets` is: the graph behind it is immutable and one process
 * ships exactly one bundle, so two hosts over one JVM share the read rather than each doing it.
 */
public object MobaCharacters {

    /** Every character the bundle declares, sorted by name. */
    public val roster: CharacterRoster by lazy { CharacterRoster.read(MobaAssets.registry) }

    /** The notify-name-to-`CueId` table for that roster. */
    public val cues: CueNames by lazy { CueNames.of(roster) }
}

/**
 * The playhead, and the notify schedule, as arithmetic on ticks.
 *
 * ## Why ticks and not seconds
 *
 * `udea-render`'s own `SpriteAnimation` component is wall-timed, and its KDoc argues for that: an
 * animation is drawn at whatever rate the renderer runs, and a headless server should not advance
 * playheads for pictures nobody sees. Both of those are right in general and both are wrong here,
 * for the reason `ChampionRenderSystem` already gave for `moba`: an agent captures a **paused**
 * world, and `render.compare_artifacts` measures the difference between two captures. A
 * wall-timed playhead makes two screenshots of an identical, paused, unmutated world differ by
 * however long the agent spent thinking - which drowns the signal the tool exists to report.
 *
 * Tick-denominated, the picture is a pure function of the simulation state, and the notify
 * schedule is too: `attack_hit` on frame 4 of a six-frame animation fires on exactly one tick, on
 * every machine, and a rewind takes it back with everything else. That is what makes it safe for
 * the *simulation* to be the thing that emits the notify (see [CharacterStateSystem]) rather than
 * the renderer shouting backwards into the world.
 *
 * Everything here is pure and has no GL, no Fleks and no world, so `MobaCharacterTest` drives all
 * of it with no context at all.
 */
public object CharacterAnimator {

    /**
     * Simulation ticks each frame of [animation] is held for, at [tickRate].
     *
     * At least one, always. `frameTime` is authored in seconds and `SpriteAnimation`'s own `init`
     * refuses zero, but a 1ms frame at 60Hz still rounds to zero ticks - and a frame that takes
     * no ticks makes [frameAt] divide by zero on the render thread. Clamping is the only
     * behaviour that is not a crash, and it is stated rather than discovered.
     */
    public fun ticksPerFrame(animation: SpriteAnimation, tickRate: Int): Long {
        require(tickRate > 0) { "tickRate must be positive, was $tickRate" }
        return maxOf(1L, (animation.frameTime.toDouble() * tickRate).roundToLong())
    }

    /**
     * Which frame of [animation] is showing [elapsed] ticks after it started.
     *
     * `floorMod` and not `%` for a looping animation: `time.rewind` can put the clock before the
     * tick a state started on, and `%` hands back a negative index - an
     * `ArrayIndexOutOfBoundsException` on the render thread, reachable from a tool an agent
     * calls. A non-looping animation clamps at both ends instead, which is what "the death pose
     * stays on the last frame" means.
     */
    public fun frameAt(animation: SpriteAnimation, frames: Int, elapsed: Long, tickRate: Int): Int {
        require(frames > 0) { "animation '${animation.id}' has no frames to draw" }
        val index = elapsed / ticksPerFrame(animation, tickRate)
        return if (animation.loop) {
            Math.floorMod(index, frames.toLong()).toInt()
        } else {
            index.coerceIn(0L, (frames - 1).toLong()).toInt()
        }
    }

    /** The tick, relative to the animation's start, that [notify] fires on. */
    public fun notifyTick(animation: SpriteAnimation, notify: AnimNotify, tickRate: Int): Long =
        notify.frame * ticksPerFrame(animation, tickRate)

    /**
     * Every notify of [animation] that falls in `(after, upTo]`, relative to the animation's start.
     *
     * A half-open lower bound so that stepping the clock one tick at a time and stepping it a
     * hundred at once fire each notify exactly once between them - which is the property that
     * makes `time.step(100)` and a hundred `time.step(1)`s produce the same world, and the
     * property a `>=` on both ends would break by firing frame 0's notify on every tick.
     */
    public fun notifiesBetween(
        animation: SpriteAnimation,
        after: Long,
        upTo: Long,
        tickRate: Int,
        emit: (AnimNotify) -> Unit,
    ) {
        if (upTo <= after) return
        for (notify in animation.notifies) {
            val at = notifyTick(animation, notify, tickRate)
            if (at > after && at <= upTo) emit(notify)
        }
    }
}

/**
 * The `CueId` a notify name is emitted under, and the way back.
 *
 * `Cue` carries a `CueId`, which is an `Int`, so a notify called `attack_hit` has to become a
 * number that both sides agree on. The table is built from a **sorted** list of the names this
 * game's animations actually declare, so it is a function of the bundle rather than of the order
 * anything happened to be loaded in - two processes over one `.udeapak` mint the same ids, which
 * is what a replicated cue would need and what a test reading a capture back needs today.
 *
 * It is deliberately not a hash of the name: a 32-bit hash of an arbitrary string collides, and a
 * collision here means an arrow spawning when a priest heals.
 */
public class CueNames private constructor(private val names: List<String>) {

    /** How many distinct notify names this bundle declares. */
    public val size: Int get() = names.size

    /** The id for [name], or `null` when no animation in the bundle declares it. */
    public fun idOf(name: String): CueId? = names.indexOf(name).takeIf { it >= 0 }?.let(::CueId)

    /** The name behind [id], or `null` when it is not one of this game's notify cues. */
    public fun nameOf(id: CueId): String? = names.getOrNull(id.raw)

    override fun toString(): String = "CueNames($names)"

    public companion object {

        /** Every notify name declared by any animation in [roster], sorted and de-duplicated. */
        public fun of(roster: CharacterRoster): CueNames = CueNames(
            roster.entries
                .flatMap { entry -> entry.states.values + entry.extras.values }
                .flatMap { animation -> animation.notifies }
                .map { it.name }
                .distinct()
                .sorted(),
        )
    }
}

/** One notify that fired, kept for a test or a debug overlay to read. Never simulation state. */
public data class NotifyRecord(
    public val character: String,
    public val notify: String,
    public val tick: Tick,
)
