package dev.wildware.moba.ai

import dev.wildware.moba.ability.Teams

/**
 * Which units run away, and which stand and die.
 *
 * ## Where this comes from
 *
 * `example/.../system/UnitAISystem.kt:66` reads `AITag.Fearless !in gameUnit.aiTags`, and the
 * corpus carries that tag on exactly three characters - `character/orc.udea.kts:37`,
 * `character/orc_elite.udea.kts:39` and `character/skeleton.udea.kts:29` all declare
 * `component("...GameUnit", "aiTags" to listOf("AITag.Fearless"))`. `soldier`, `priest` and
 * `wizard` declare no `aiTags` block at all, so they flee.
 *
 * ## Why it is keyed on the team and not on the character
 *
 * Because there is nowhere on a live entity to read a character name from. A unit dressed by
 * `dev.wildware.moba.ability.UnitBlueprint.dress` carries `Combatant` (a team id), `Attributes`,
 * `GameplayEffects` and `Abilities` - and none of them names the kind. The honest fix is a
 * `fearless` field on `dev.wildware.moba.ability.UnitKind` written onto the entity at spawn, and
 * that is a change to two files this agent does not own (see the report).
 *
 * What makes the stand-in safe rather than a guess is that the corpus's Fearless set is *exactly*
 * two whole teams: `orc` and `orc_elite` are `OrcTeam`, `skeleton` is `UndeadTeam`, and every
 * character on `SoldierTeam` is missing the tag. [FEARLESS_CHARACTERS] pins the roster the
 * corpus actually declares and `UnitAiProofTest.fearless roster agrees with the corpus` fails the
 * build the moment a character is added whose team disagrees with its tag - which is the tick at
 * which the per-kind field stops being optional.
 */
public object AiRoster {

    /** The characters whose `character/<name>.udea.kts` declares `AITag.Fearless`. */
    public val FEARLESS_CHARACTERS: Set<String> = setOf("orc", "orc_elite", "skeleton")

    /**
     * Whether a unit on [teamId] holds its ground at low health.
     *
     * [Teams.NEUTRAL] is fearless: it has no enemies, so it never has anything to run from, and
     * answering `false` would make it the one thing on the field that panics at nobody.
     */
    public fun isFearless(teamId: Int): Boolean =
        teamId == Teams.ORC || teamId == Teams.UNDEAD || teamId == Teams.NEUTRAL
}
