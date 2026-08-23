package dev.wildware.udea.assets.compiler.pack

import dev.wildware.udea.assets.Axis2D
import dev.wildware.udea.assets.Control
import dev.wildware.udea.assets.compiler.AssetGraph
import dev.wildware.udea.assets.compiler.DeclaredAsset
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A control's integer identity does not depend on the order the tree was enumerated.
 *
 * ## What this is really testing
 *
 * `common/.../controls.kt:10-14,52-58` numbered controls from a static counter incremented
 * during a filesystem walk. Client and server walk separately, so the two could disagree about
 * which integer means "attack" - an input desync that only appears on a machine whose
 * filesystem enumerates differently. The fix is not a better counter; it is that there is no
 * counter. See [ControlIds].
 *
 * The declarations are built here rather than compiled from a script because the current
 * `AssetScope` DSL has **no `control()` or `axis2D()` function** - the new corpus cannot
 * declare one yet. That is a real gap and it is stated rather than papered over: what is
 * proven here is that the *packer* assigns stable ids to control-kinded records, not that the
 * DSL can produce them.
 */
class ControlIdStabilityTest {

    private val controlKind = requireNotNull(Control::class.qualifiedName)
    private val axisKind = requireNotNull(Axis2D::class.qualifiedName)

    private fun declaration(id: String, kind: String, fqn: String) =
        DeclaredAsset(kind = kind, kindFqn = fqn, id = id, fields = emptyMap())

    private fun corpus(): List<DeclaredAsset> = listOf(
        declaration("control/attack", "control", controlKind),
        declaration("control/attack_2", "control", controlKind),
        declaration("control/move", "axis2D", axisKind),
        declaration("control/attack_binding", "binding", requireNotNull(dev.wildware.udea.assets.Binding::class.qualifiedName)),
        declaration("character/orc_idle", "spriteSheet", requireNotNull(dev.wildware.udea.assets.SpriteSheet::class.qualifiedName)),
    )

    @Test
    fun `enumerating the declarations in reverse produces identical control ids`() {
        val forward = GraphPacker.pack(AssetGraph.of(corpus()))
        val backward = GraphPacker.pack(AssetGraph.of(corpus().reversed()))

        val a = ControlIds.assign(forward.assets)
        val b = ControlIds.assign(backward.assets)

        assertEquals(a, b, "reversing the enumeration changed a control id")
        assertTrue(a.isNotEmpty(), "the fixture declares no controls, so this proves nothing")
    }

    @Test
    fun `only controls and axes get an id, and the id is the packed slot`() {
        val packed = GraphPacker.pack(AssetGraph.of(corpus()))

        val ids = ControlIds.assign(packed.assets)

        assertEquals(setOf("control/attack", "control/attack_2", "control/move"), ids.keys)
        ids.forEach { (id, slot) ->
            assertEquals(id, packed.assets[slot].id, "'$id' was given a slot holding something else")
        }
    }

    /**
     * Adding an unrelated asset that sorts *before* the controls renumbers them.
     *
     * That is the correct behaviour and it is asserted rather than left implicit: the slot is
     * the identity, so it moves when the graph does, and everything that stores one - a
     * snapshot, a saved keybinding - is a `.udeapak`-versioned artifact for exactly that
     * reason. A reader that assumed the number was stable across asset-set changes would be
     * wrong, and this test is where that is written down.
     */
    @Test
    fun `an asset sorting before a control shifts its slot`() {
        val extra = declaration("ability/dash", "blueprint", requireNotNull(dev.wildware.udea.assets.Blueprint::class.qualifiedName))

        val before = ControlIds.assign(GraphPacker.pack(AssetGraph.of(corpus())).assets)
        val after = ControlIds.assign(GraphPacker.pack(AssetGraph.of(corpus() + extra)).assets)

        assertEquals(before.keys, after.keys)
        assertEquals(before.getValue("control/attack") + 1, after.getValue("control/attack"))
    }
}
