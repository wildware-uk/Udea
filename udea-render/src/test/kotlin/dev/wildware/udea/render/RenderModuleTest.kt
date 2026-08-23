package dev.wildware.udea.render

import dev.wildware.udea.core.module.SimPhase
import dev.wildware.udea.core.module.UdeaGameDef
import dev.wildware.udea.core.physics.TeleportSystem
import dev.wildware.udea.render.interp.InterpSnapshotSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The one system this module puts inside the tick, and where it has to sit.
 */
class RenderModuleTest {

    @Test
    fun `the pose snapshot runs before the teleport that would erase its evidence`() {
        val game = UdeaGameDef(modules = listOf(RenderModule())).build()

        val order = game.manifest.entries.map { it.name }
        val snapshot = order.indexOf(InterpSnapshotSystem::class.java.name)
        val teleport = order.indexOf(TeleportSystem::class.java.name)

        assertTrue(snapshot >= 0, "InterpSnapshotSystem was not registered: $order")
        assertTrue(teleport >= 0, "TeleportSystem was not registered: $order")
        assertTrue(
            snapshot < teleport,
            "TeleportSystem removes the Teleport component as it applies it, so a pose " +
                "snapshot running after it can no longer tell a teleport from movement",
        )
    }

    @Test
    fun `the pose snapshot runs in PreSimulation`() {
        val game = UdeaGameDef(modules = listOf(RenderModule())).build()

        val entry = game.manifest.entries.single { it.name == InterpSnapshotSystem::class.java.name }

        assertEquals(SimPhase.PreSimulation, entry.phase)
    }

    @Test
    fun `a game that leaves the module out gets no presentation system at all`() {
        val game = UdeaGameDef(modules = emptyList()).build()

        assertTrue(
            game.manifest.entries.none { it.name == InterpSnapshotSystem::class.java.name },
            "the interpolation system arrived without anybody asking for it",
        )
    }
}
