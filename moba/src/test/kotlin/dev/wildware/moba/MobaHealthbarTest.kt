package dev.wildware.moba

import dev.wildware.moba.level.Team
import dev.wildware.moba.ability.MobaAbilityModule
import dev.wildware.moba.ability.MobaUnits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The arithmetic and the geometry a health bar is wrong in, checked without a GL context.
 *
 * `render` itself needs a `Batch`, and a `Batch` needs a context, so the drawing is exercised by
 * `udeaGlTest` and by a capture. Everything a bar can be *silently* wrong about - a fill wider than
 * the frame, a NaN written by an agent through `world.set_component_field`, a bar drawn inside the
 * sprite it belongs to, two teams sharing a colour - is a pure function and is checked here.
 */
class MobaHealthbarTest {

    /** Full health fills the frame, half fills half, and death fills nothing. */
    @Test
    fun `the fill is the health fraction`() {
        assertEquals(1f, HealthbarRenderSystem.fractionOf(100f, 100f))
        assertEquals(0.5f, HealthbarRenderSystem.fractionOf(50f, 100f))
        assertEquals(0f, HealthbarRenderSystem.fractionOf(0f, 100f))
    }

    /**
     * An over-heal draws a full bar, not a bar past its own frame.
     *
     * `Position.hp` is `@Sim` and agent-writable through the world toolset, so `hp = 500` on a
     * 100-health soldier is one HTTP call away. Without the clamp that draws a 150-world-unit
     * rectangle across four other units. Delete the `coerceIn` and this fails.
     */
    @Test
    fun `an over-heal is clamped to the frame`() {
        assertEquals(1f, HealthbarRenderSystem.fractionOf(500f, 100f))
    }

    /**
     * Values that have no fraction at all draw nothing rather than propagating.
     *
     * A `NaN` width reaches the GPU as an undefined quad and takes the rest of the frame's
     * geometry with it on some drivers, which surfaces as a corrupted screenshot rather than as an
     * exception naming this system.
     */
    @Test
    fun `a non-finite health draws nothing`() {
        assertEquals(0f, HealthbarRenderSystem.fractionOf(Float.NaN, 100f))
        assertEquals(0f, HealthbarRenderSystem.fractionOf(Float.POSITIVE_INFINITY, 100f))
        assertEquals(0f, HealthbarRenderSystem.fractionOf(50f, 0f))
    }

    /** Negative health - one tick of overkill before `UnitDeathSystem` sweeps - draws empty. */
    @Test
    fun `overkill draws an empty bar rather than a negative one`() {
        assertEquals(0f, HealthbarRenderSystem.fractionOf(-30f, 100f))
    }

    /**
     * Every team the level fields is visually distinct.
     *
     * `test_level` spawns orcs, soldiers and skeletons, and a demo whose two sides are the same
     * colour is the "renders two colours" complaint this whole wave exists to answer.
     */
    @Test
    fun `each team gets its own colour`() {
        val orc = HealthbarRenderSystem.colourOf(Team.ORC)
        val soldier = HealthbarRenderSystem.colourOf(Team.SOLDIER)
        val undead = HealthbarRenderSystem.colourOf(Team.UNDEAD)
        assertNotEquals(orc, soldier)
        assertNotEquals(soldier, undead)
        assertNotEquals(orc, undead)
    }

    /** An unteamed entity draws neutral instead of throwing halfway through a frame. */
    @Test
    fun `an unknown team is neutral, not an exception`() {
        assertSame(HealthbarRenderSystem.NEUTRAL_COLOUR, HealthbarRenderSystem.colourOf(Team.NONE))
        assertSame(HealthbarRenderSystem.NEUTRAL_COLOUR, HealthbarRenderSystem.colourOf(99))
    }

    /**
     * The bar clears the sprite it belongs to.
     *
     * `CharacterRenderSystem` draws a frame centred on the entity's position, and `MobaAssetsTest`
     * pins a frame at 64px times the authored scale, which is 34 world units. So the top of a
     * sprite is 17 units above its position, and a bar at a smaller offset is drawn across the
     * unit's head. Lower `OFFSET_Y` below 17 and this fails.
     */
    @Test
    fun `the bar sits above the sprite`() {
        val spriteHalfHeight = SPRITE_WORLD_HEIGHT / 2f
        assertTrue(
            HealthbarRenderSystem.OFFSET_Y > spriteHalfHeight,
            "a bar at ${HealthbarRenderSystem.OFFSET_Y} is inside a sprite reaching $spriteHalfHeight",
        )
    }

    /** A bar narrower than its unit, so two neighbours' bars do not read as one. */
    @Test
    fun `the bar is narrower than the unit it belongs to`() {
        assertTrue(
            HealthbarRenderSystem.WIDTH < SPRITE_WORLD_HEIGHT,
            "a ${HealthbarRenderSystem.WIDTH}-wide bar is wider than a $SPRITE_WORLD_HEIGHT unit",
        )
    }

    /**
     * Every unit kind the level fields has a positive maximum, or its bar divides by zero.
     *
     * The renderer reads the `maxHealth` **attribute** as the denominator now - the same one
     * `dev.wildware.moba.ability.UnitBlueprint.dress` seeds a unit's health from - so a kind added
     * with a zero maximum would draw an empty bar over a unit at full health, which looks exactly
     * like a unit about to die. Asked of `MobaUnits.kinds` and not of the level's four, because
     * that is the list the attribute is actually seeded from.
     */
    @Test
    fun `every unit kind has a health maximum a bar can divide by`() {
        for (kind in MobaUnits.kinds(MobaAbilityModule().abilities)) {
            assertTrue(kind.health > 0f, "${kind.name} has health ${kind.health}")
            assertEquals(1f, HealthbarRenderSystem.fractionOf(kind.health, kind.health))
        }
    }

    /**
     * A unit with no mana gets no mana rail, and one with mana gets a full one.
     *
     * The rail is the half of the old `HealthbarSystem` that could not be ported until the ability
     * module was wired into the units the level spawns: before that, mana was an attribute nothing
     * on the field had, and drawing it meant drawing a constant zero under every soldier.
     */
    @Test
    fun `only a unit with mana has a mana rail to draw`() {
        val kinds = MobaUnits.kinds(MobaAbilityModule().abilities).associateBy { it.name }
        val priest = requireNotNull(kinds["priest"])
        val soldier = requireNotNull(kinds["soldier"])
        assertTrue(priest.mana > 0f, "the priest casts, so it must have mana to spend")
        assertEquals(1f, HealthbarRenderSystem.fractionOf(priest.mana, priest.mana))
        assertEquals(0f, soldier.mana, "a soldier has no mana, so it gets no second rail")
        assertEquals(0f, HealthbarRenderSystem.fractionOf(soldier.mana, soldier.mana))
    }

    private companion object {

        /** 64px frames at the roster's authored `SpriteSheet.scale` of 0.53125. */
        const val SPRITE_WORLD_HEIGHT: Float = 34f
    }
}
