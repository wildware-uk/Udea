package dev.wildware.moba.audio

import dev.wildware.moba.CueNames
import dev.wildware.moba.MobaAssets
import dev.wildware.moba.MobaCharacters
import dev.wildware.moba.ability.MobaCues
import dev.wildware.udea.assets.AssetId
import dev.wildware.udea.assets.AssetRegistry
import dev.wildware.udea.assets.SoundCue
import dev.wildware.udea.audio.AudioBindings
import dev.wildware.udea.audio.AudioDevice
import dev.wildware.udea.audio.CueSound
import dev.wildware.udea.core.CueId

/**
 * Which cue plays which `soundCue`, and the reason six of the nine play nothing.
 *
 * ## Two cue namespaces, one `CueId` space
 *
 * `GameContext.cues` is a single `CueSink` and **two** independent things mint ids into it:
 *
 * - [MobaCues], hand-numbered `1..9` - `DAMAGE`, `MELEE_HIT`, `MELEE_SWOOSH`, `KNOCKBACK`, `HEAL`,
 *   `SPIN`, `ARROW_FIRED`, `ARROW_HIT`, `DEATH` - emitted by the ability execs and by `DeathSystem`;
 * - [CueNames], which numbers the animation notify names `0 until size` from a **sorted** list of
 *   whatever the bundle declares, and which `CharacterAnimationSystem` emits under.
 *
 * They overlap. Today the bundle declares seven notify names, so notify ids run `0..6` and every
 * `MobaCues` id from `DAMAGE` to `SPIN` is *also* a notify id:
 *
 * | id | ability cue | notify |
 * |---|---|---|
 * | 0 | - | `attack_hit` |
 * | 1 | `DAMAGE` | `attack_hit_2` |
 * | 2 | `MELEE_HIT` | `attack_hit_3` |
 * | 3 | `MELEE_SWOOSH` | `attack_hit_4` |
 * | 4 | `KNOCKBACK` | `fire_arrow` |
 * | 5 | `HEAL` | `heal` |
 * | 6 | `SPIN` | `swoosh` |
 *
 * A consumer holding a `Cue` cannot tell which of the two emitted it, and neither can this table.
 * Binding `SPIN` to the elite orc's shout would therefore play that shout on every unit's *swing
 * wind-up*, and binding `KNOCKBACK` to a grunt would grunt every time an archer looses an arrow.
 * That is an audible defect, so the ambiguous ids are left **silent** and [ambiguous] says which
 * they are. `MobaAudioTest` asserts the overlap, so the day the two namespaces are separated -
 * which is a change to `MobaCues`/`CueNames` and not to this file - that test goes red and
 * whoever separates them is told to finish the job here.
 *
 * ## What that leaves audible
 *
 * `attack_hit` (notify id 0, which no ability cue claims) is the frame a blade connects on, for
 * every character in the roster, and it is bound to `sounds/melee_hit`. Frame-accurate is the
 * *better* trigger for a hit sound than the ability's damage tick anyway, so this is not a
 * consolation binding. `ARROW_FIRED`, `ARROW_HIT` and `DEATH` are all above the notify range and
 * bind straight through.
 *
 * ## `heal` has no recording
 *
 * The pack `docs/art-assets.md` ships is twenty-four orc and melee recordings; there is no heal in
 * it, and there was none in the old game either - the old `PriestHealCue` spawned a particle
 * effect and made no sound. So `HEAL` would be unbound even without the collision above.
 */
public class MobaCueSounds private constructor(
    /** The table [dev.wildware.udea.audio.CueAudio] indexes, ready to play. */
    public val bindings: AudioBindings,
    /** Cue names left silent because both namespaces mint their id. Sorted, for a stable message. */
    public val ambiguous: List<String>,
) {

    override fun toString(): String =
        "MobaCueSounds(${bindings.size} bound, ${ambiguous.size} ambiguous)"

    public companion object {

        /**
         * The ability-cue half of the routing, by [MobaCues] id.
         *
         * Every entry is authored in `moba/assets/sounds/sounds.udea.kts`; the ids in the notify
         * range are declared here anyway rather than deleted, because they are the routing the
         * game *wants* and the reason they do not apply is a defect somewhere else. Deleting them
         * would leave nothing to re-enable when it is fixed.
         */
        internal val BY_ABILITY_CUE: Map<Int, String> = linkedMapOf(
            MobaCues.DAMAGE to "sounds/hurt",
            MobaCues.MELEE_HIT to "sounds/melee_hit",
            MobaCues.MELEE_SWOOSH to "sounds/melee_swoosh",
            MobaCues.KNOCKBACK to "sounds/knockback",
            MobaCues.SPIN to "sounds/spin",
            MobaCues.ARROW_FIRED to "sounds/arrow_fired",
            MobaCues.ARROW_HIT to "sounds/arrow_hit",
            MobaCues.DEATH to "sounds/death",
        )

        /**
         * The notify half, by animation notify name.
         *
         * One entry, and it is the important one: `attack_hit` is on every attack animation in the
         * roster, at the frame the weapon lands.
         */
        internal val BY_NOTIFY: Map<String, String> = linkedMapOf(
            "attack_hit" to "sounds/melee_hit",
        )

        /**
         * Cue ids that both [MobaCues] and [notifies] mint, and which are therefore unroutable.
         *
         * Computed rather than written down, so adding a notify name to any animation - which
         * renumbers the whole notify table - widens this set instead of silently aliasing a new
         * pair together. It is over the whole ability-cue *namespace* and not over
         * [BY_ABILITY_CUE], because `HEAL` is ambiguous whether or not anything has recorded a
         * sound for it, and a set that answered otherwise would say the collision was smaller than
         * it is.
         *
         * [MobaCues] declares one contiguous block from `DAMAGE` to `DEATH` and has no list of its
         * own to iterate, so the block is walked and each id checked against `nameOf` - a gap or a
         * renumber there fails here rather than quietly shrinking the set.
         */
        public fun ambiguousIds(notifies: CueNames = MobaCharacters.cues): Set<Int> {
            val namespace = MobaCues.DAMAGE..MobaCues.DEATH
            namespace.forEach { id ->
                check(!MobaCues.nameOf(id).startsWith(UNNAMED_CUE_PREFIX)) {
                    "MobaCues has a hole at id $id, so its namespace is no longer the contiguous " +
                        "range ${MobaCues.DAMAGE}..${MobaCues.DEATH} that ambiguousIds walks; " +
                        "give MobaCues an explicit id list and read it here"
                }
            }
            return namespace.filterTo(sortedSetOf()) { it < notifies.size }
        }

        /** What [MobaCues.nameOf] returns for an id it does not define. */
        private const val UNNAMED_CUE_PREFIX: String = "cue:"

        /**
         * Loads every routable cue's files through [device] and builds the table.
         *
         * @throws IllegalStateException when a `soundCue` named above is not in [registry]. The
         *   routing and the asset tree are two files that have to agree, nothing checks them
         *   against each other at build time, and a missing one would otherwise present as "the
         *   game went quiet" months later.
         */
        public fun load(
            device: AudioDevice,
            registry: AssetRegistry = MobaAssets.registry,
            notifies: CueNames = MobaCharacters.cues,
        ): MobaCueSounds {
            val ambiguous = ambiguousIds(notifies)
            val sounds = ArrayList<CueSound>(BY_ABILITY_CUE.size + BY_NOTIFY.size)

            BY_ABILITY_CUE.forEach { (id, assetId) ->
                if (id in ambiguous) return@forEach
                sounds += CueSound.load(CueId(id), soundCue(registry, assetId), device)
            }
            BY_NOTIFY.forEach { (name, assetId) ->
                val id = checkNotNull(notifies.idOf(name)) {
                    "no animation in this bundle declares a '$name' notify, so MobaCueSounds is " +
                        "routing a cue nothing emits; the notify names are $notifies"
                }
                if (id.raw in ambiguous) return@forEach
                sounds += CueSound.load(id, soundCue(registry, assetId), device)
            }

            return MobaCueSounds(
                bindings = AudioBindings.of(sounds),
                ambiguous = ambiguous.map(MobaCues::nameOf).sorted(),
            )
        }

        private fun soundCue(registry: AssetRegistry, assetId: String): SoundCue =
            checkNotNull(registry.find(AssetId(assetId)) as? SoundCue) {
                "'$assetId' is not a soundCue in this bundle. MobaCueSounds routes a cue to it " +
                    "and moba/assets/sounds/sounds.udea.kts is where it is declared."
            }
    }
}
