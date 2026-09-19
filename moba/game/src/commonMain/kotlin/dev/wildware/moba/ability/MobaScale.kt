package dev.wildware.moba.ability

/**
 * How many of this game's world units one of the old corpus's world units is worth.
 *
 * ## Why a conversion exists at all
 *
 * The ported ability content and the ported level were authored against two different worlds and
 * both of them are right about their own. `example/.../ability/UnitMeleeAttack.kt` wrote
 * `if (diff.len() > 0.8F) missed`, and `PriestHeal` wrote `getNearbyFriendlyUnits(entity, 3F)`:
 * in that game a character was about **one** world unit across, because a Box2D body was authored
 * in metres. In this one a character is a `spriteSheet` whose authored `scale` puts it at about
 * **forty** world units across, which is why `level/test_level` places its four clearings a
 * hundred units apart and why `TestLevelScene.SCATTER` is 40 rather than 4.
 *
 * Left unreconciled, every ability in the game silently does nothing: two units standing sprite
 * to sprite are thirty world units apart, `MeleeAttackExec` looks for an enemy within 0.8, finds
 * none, and the swing whiffs on every unit on the field forever. That is not a failure any test
 * of the ability layer catches, because a fixture that spawns its units 0.5 apart is *also*
 * self-consistent - it is only the seam between the two halves that is wrong, which is exactly
 * the class of bug an integration is for.
 *
 * ## Why the corpus numbers are kept and scaled rather than rewritten
 *
 * Every constant on the execs is written as `<the number the old asset had> * WORLD`. That keeps
 * the KDoc claim beside it (`Data.Knockback to .3F`) checkable against the corpus by reading,
 * which a rewritten `4.8f` would not be, and it means one edit here rescales the whole game
 * coherently rather than nine edits that can disagree. `CombatFixture` scales its spawn
 * coordinates by the same factor, so the ability tests still read in corpus units.
 *
 * ## What it is not
 *
 * Not a rendering scale, and nothing outside this package multiplies by it. `SpriteSheet.scale`
 * is the authored world size of a frame and is read out of the bundle per frame
 * ([dev.wildware.moba.CharacterRenderSystem]); this number is the simulation's opinion of how big
 * a character is, and the two agreeing is a property [dev.wildware.moba.MobaIntegrationTest]
 * asserts rather than one the types enforce. When issue #84 gives `character` a runtime type, a
 * unit's reach becomes an authored field on it and this constant goes away.
 */
public object MobaScale {

    /**
     * World units per corpus unit.
     *
     * Forty, because `orc_idle`'s frames are 100px at an authored scale that puts them near forty
     * world units tall, and because it makes [MeleeAttackExec.RANGE] 32 - just wider than the
     * widest [dev.wildware.moba.level.UnitKind.reach] a unit closes to, which is the relationship
     * `MobaIntegrationTest` pins.
     */
    public const val WORLD: Float = 40f
}
