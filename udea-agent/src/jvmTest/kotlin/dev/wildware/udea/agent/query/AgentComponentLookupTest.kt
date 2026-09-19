package dev.wildware.udea.agent.query

import dev.wildware.udea.agent.Health
import dev.wildware.udea.agent.Team
import dev.wildware.udea.agent.Transform
import dev.wildware.udea.agent.healthAccess
import dev.wildware.udea.agent.transformAccess
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Finding a registered component by its Fleks type (issue #236): how the editor turns a gizmo's field
 * write - which names its component by `ComponentType` - into the `Component.field` an
 * `editor.update_edit` call spells.
 */
class AgentComponentLookupTest {

    @Test
    fun `a component is found by its Fleks type, and a type nobody registered is not`() {
        val index = AgentComponentIndex(listOf(transformAccess(), healthAccess()))

        assertEquals("Health", index.findByType(Health)?.name)
        assertEquals("Transform", index.findByType(Transform)?.name)
        assertNull(index.findByType(Team), "a type that was never registered was found")
    }
}
