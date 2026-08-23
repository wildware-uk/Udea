package dev.wildware.moba

import dev.wildware.moba.entry.MobaEntry
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.replication.MaskOps
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/**
 * What `moba` promises the engine, checked without a GL context.
 *
 * The Phase 1 demo drives the real thing end to end and is the stronger evidence; these are the
 * checks that fail *at build time* rather than the next time somebody boots an instance.
 */
class MobaGameTest {

    /**
     * The generated replicator and the hand-assembled snapshot schema agree.
     *
     * This is the one that matters. [MobaGame.componentRegistry] types out
     * `listOf(FieldKind.Float, FieldKind.Float, FieldKind.Float)` by hand, while
     * `PositionReplicator` is generated from the `@Net`/`@Sim` annotations. Add a `String` field
     * to [Position] and the generator will happily widen the replicator while the schema still
     * claims three floats - and the failure surfaces as a corrupt snapshot restore at run time,
     * which is a very long way from the edit that caused it.
     */
    @Test
    fun `the generated replicator has the field count the snapshot schema is built for`() {
        assertEquals(3, PositionReplicator.FIELD_COUNT)
        assertEquals(listOf("hp", "x", "y"), PositionReplicator.fieldNames)
    }

    /** `@Net` on x and y, `@Sim` on hp - the masks the agent's field table reads. */
    @Test
    fun `hp is snapshotted but never replicated`() {
        val net = PositionReplicator.netMask
        assertTrue(MaskOps.test(net, PositionReplicator.FIELD_X), "x is @Net")
        assertTrue(MaskOps.test(net, PositionReplicator.FIELD_Y), "y is @Net")
        assertTrue(
            !MaskOps.test(net, PositionReplicator.FIELD_HP),
            "hp is @Sim, so it must be absent from the net mask or it goes on the wire",
        )
        assertTrue(MaskOps.test(PositionReplicator.allMask, PositionReplicator.FIELD_HP))
    }

    /**
     * `getField`/`setField` by index - the exact path `world.set_component_field` writes through.
     *
     * A wrong index here does not throw; it writes y into x, which surfaces as an entity that
     * teleports when an agent nudges it. Pinning the mapping is cheaper than debugging that.
     */
    @Test
    fun `getField and setField address the fields their indices name`() {
        val position = Position(x = 3f, y = 5f, hp = 7f)

        assertEquals(3f, PositionReplicator.getField(position, PositionReplicator.FIELD_X))
        assertEquals(5f, PositionReplicator.getField(position, PositionReplicator.FIELD_Y))
        assertEquals(7f, PositionReplicator.getField(position, PositionReplicator.FIELD_HP))

        PositionReplicator.setField(position, PositionReplicator.FIELD_X, 11f)
        assertEquals(11f, position.x)
        assertEquals(5f, position.y, "writing x must not touch y")
        assertEquals(7f, position.hp, "writing x must not touch hp")
    }

    /**
     * A definition per call, because two hosts over one definition share a world and a barrier.
     *
     * [MobaGame.definition]'s KDoc states this; nothing checked it, and it is the sort of thing a
     * later "cache the definition, it is expensive" refactor silently breaks.
     */
    @Test
    fun `every definition is a fresh world`() {
        assertNotSame(MobaGame.definition().core, MobaGame.definition().core)
    }

    /**
     * An unrecognised render mode throws rather than falling back.
     *
     * A launcher that misspells the mode would otherwise get a headless process whose `/health`
     * says `Headless` and whose render tools all answer `no_render_context` - a silent downgrade
     * an agent cannot tell apart from a machine with no GL driver.
     */
    @Test
    fun `an unknown render mode is refused, not defaulted`() {
        assertEquals(
            RenderMode.Offscreen,
            MobaEntry.modeFromProperties(RenderMode.Offscreen) { null },
        )
        assertEquals(
            RenderMode.Windowed,
            MobaEntry.modeFromProperties(RenderMode.Offscreen) { "Windowed" },
        )
        val failure = assertFailsWith<IllegalArgumentException> {
            MobaEntry.modeFromProperties(RenderMode.Offscreen) { "Offscren" }
        }
        assertTrue("Offscren" in failure.message.orEmpty(), failure.message.orEmpty())
    }
}
