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
 * Which cue plays which `soundCue`, and what is still silent.
 *
 * ## What this file used to say, and why it no longer does
 *
 * `GameContext.cues` is a single `CueSink` and **two** independent things mint ids into it -
 * [MobaCues] for the ability cues, [CueNames] for the animation notify names. Both used to start
 * at zero and count up by hand, so ids `1..6` named two different events each: a consumer holding
 * a `Cue` could not tell `MobaCues.SPIN` from the `swoosh` notify, or `KNOCKBACK` from
 * `fire_arrow`. This table's answer was to bind only the four unambiguous ids and route **six of
 * the nine authored cues to silence**, because binding `SPIN` to the elite orc's shout would have
 * played that shout on every unit's swing wind-up.
 *
 * The namespaces are now allocated rather than typed: `MobaCues` mints the ability block and
 * [CueNames] allocates the notify block from [MobaCues.NOTIFY_BASE], which is wherever that block
 * ended. Nothing overlaps, so every authored cue below is bound and audible. [collisions] still
 * computes the overlap - it is a guard now rather than a policy, and `MobaAudioTest` pins it
 * empty so that a future block minted by hand fails a test instead of going quietly silent.
 *
 * ## Why no animation notify has a sound
 *
 * The notify ids are clean and routable; nothing is bound to one because in this bundle every
 * notify that can fire names an event an ability cue already emits, and the ability cue is the
 * better trigger of the two:
 *
 * - `attack_hit` and `swoosh` are on the five attack animations, and duplicate `MELEE_HIT` and
 *   `MELEE_SWOOSH`. `MELEE_HIT` fires only when the blow **connected** - the notify fires on a
 *   whiff too - and `MeleeAttackExec.HIT_TICK` is the same frame the notify sits on for three of
 *   the five characters, so binding both would start two copies of one recording on one tick.
 * - `attack_hit_2`, `attack_hit_3`, `attack_hit_4`, `fire_arrow` and `heal` sit on
 *   `orc_elite_spin`, `soldier_fire_arrow` and `priest_heal`, which are `CharacterEntry.extras`.
 *   `CharacterAnimationSystem` plays `CharacterView.state`, and a state is one of the five
 *   `UnitState`s, so no extra animation is ever the playing one and none of those five notifies
 *   can fire at all. Binding them would be routing cues nothing emits.
 *
 * [BY_NOTIFY] is therefore empty rather than deleted: the lookup, the id check and the failure
 * message are what a notify binding costs, and the day `orc_elite_spin` is a state the elite orc
 * can be in, its three extra impact frames are one line here.
 *
 * ## `heal` has no recording of its own
 *
 * The pack `docs/art-assets.md` ships is twenty-four melee and orc recordings and there is no heal
 * in it. `sounds/heal` is declared over two of the swooshes at well under a swing's volume - a
 * soft whoosh, which is a placeholder and is labelled as one in `sounds.udea.kts`. The old game
 * made no heal sound at all: `PriestHealCue` spawned a particle effect and nothing else.
 */
public class MobaCueSounds private constructor(
    /** The table [dev.wildware.udea.audio.CueAudio] indexes, ready to play. */
    public val bindings: AudioBindings,
    /** Authored cues with no sound bound to them. Empty is the claim. Sorted, for a message. */
    public val silent: List<String>,
) {

    override fun toString(): String = "MobaCueSounds(${bindings.size} bound, ${silent.size} silent)"

    public companion object {

        /**
         * The ability-cue half of the routing, by [MobaCues] id.
         *
         * Every entry is authored in `moba/assets/sounds/sounds.udea.kts`. All nine of them, which
         * is the change: six were declared here and skipped at load while the two id spaces
         * overlapped, so the game played four of its nine authored cues.
         */
        internal val BY_ABILITY_CUE: Map<Int, String> = linkedMapOf(
            MobaCues.DAMAGE to "sounds/hurt",
            MobaCues.MELEE_HIT to "sounds/melee_hit",
            MobaCues.MELEE_SWOOSH to "sounds/melee_swoosh",
            MobaCues.KNOCKBACK to "sounds/knockback",
            MobaCues.HEAL to "sounds/heal",
            MobaCues.SPIN to "sounds/spin",
            MobaCues.ARROW_FIRED to "sounds/arrow_fired",
            MobaCues.ARROW_HIT to "sounds/arrow_hit",
            MobaCues.DEATH to "sounds/death",
        )

        /**
         * The notify half, by animation notify name. Empty, for the reason the class KDoc gives:
         * every notify this bundle can fire duplicates an ability cue that is the better trigger.
         */
        internal val BY_NOTIFY: Map<String, String> = emptyMap()

        /**
         * Ids that both [MobaCues] and [notifies] mint. Empty by construction, checked anyway.
         *
         * This used to be the routing policy - an id in here was left silent, because a cue on it
         * could have come from either namespace. It is a **guard** now: neither block holds a
         * written-down id, so the only way to get an entry here is for somebody to hand-number a
         * new block, and this says so at load time rather than aliasing a heal onto a knockback.
         *
         * Computed over the whole ability namespace rather than over [BY_ABILITY_CUE], because an
         * id is ambiguous whether or not anything has recorded a sound for it.
         */
        public fun collisions(notifies: CueNames = MobaCharacters.cues): Set<Int> =
            MobaCues.ids.filterTo(sortedSetOf()) { it in notifies.ids }

        /**
         * Loads every routable cue's files through [device] and builds the table.
         *
         * @throws IllegalStateException when a `soundCue` named above is not in [registry], or
         *   when the two cue namespaces overlap. The routing and the asset tree are two files that
         *   have to agree, nothing checks them against each other at build time, and a missing one
         *   would otherwise present as "the game went quiet" months later.
         */
        public fun load(
            device: AudioDevice,
            registry: AssetRegistry = MobaAssets.registry,
            notifies: CueNames = MobaCharacters.cues,
        ): MobaCueSounds {
            val collisions = collisions(notifies)
            check(collisions.isEmpty()) {
                "the ability block and the notify block both mint " +
                    "${collisions.map(MobaCues::nameOf)}, so a Cue on one of those ids does not " +
                    "say what happened. MobaCues.NOTIFY_BASE is ${MobaCues.NOTIFY_BASE} and the " +
                    "notify block is ${notifies.ids}; one of the two was written down by hand."
            }
            val sounds = ArrayList<CueSound>(BY_ABILITY_CUE.size + BY_NOTIFY.size)

            BY_ABILITY_CUE.forEach { (id, assetId) ->
                sounds += CueSound.load(CueId(id), soundCue(registry, assetId), device)
            }
            BY_NOTIFY.forEach { (name, assetId) ->
                val id = checkNotNull(notifies.idOf(name)) {
                    "no animation in this bundle declares a '$name' notify, so MobaCueSounds is " +
                        "routing a cue nothing emits; the notify names are $notifies"
                }
                sounds += CueSound.load(id, soundCue(registry, assetId), device)
            }

            val bindings = AudioBindings.of(sounds)
            return MobaCueSounds(
                bindings = bindings,
                silent = MobaCues.ids
                    .filter { bindings[CueId(it)] == null }
                    .map(MobaCues::nameOf)
                    .sorted(),
            )
        }

        private fun soundCue(registry: AssetRegistry, assetId: String): SoundCue =
            checkNotNull(registry.find(AssetId(assetId)) as? SoundCue) {
                "'$assetId' is not a soundCue in this bundle. MobaCueSounds routes a cue to it " +
                    "and moba/assets/sounds/sounds.udea.kts is where it is declared."
            }
    }
}
