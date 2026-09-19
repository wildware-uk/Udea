package dev.wildware.udea.editor

import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.blueprint.BlueprintId
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.module.CoreModule
import dev.wildware.udea.core.module.UdeaGameDef
import dev.wildware.udea.core.physics.PhysicsBody
import dev.wildware.udea.editor.gizmo.FieldName
import dev.wildware.udea.editor.gizmo.FieldWrite
import dev.wildware.udea.editor.gizmo.Snap
import dev.wildware.udea.generated.CoreUdeaRegistry
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.math.PI
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Scene tab's snapping and axes (issue #236): what each setting does to a written value, that a
 * change is kept in the project's preferences file and read back the next time, and that the toolbar
 * over the Scene tab changes them.
 */
class GizmoPreferencesTest {

    private val dir: Path = Files.createTempDirectory("gizmo-preferences")
    private val file: Path = dir.resolve(".udea").resolve("editor-preferences.properties")

    @AfterTest
    fun clean() {
        dir.toFile().deleteRecursively()
    }

    private fun write(value: Float, snap: Snap) = FieldWrite(NetId.of(index = 1, generation = 0), PhysicsBody, FieldName("x"), value, snap)

    @Test
    fun `grid and angle snapping round only the kind of value they name, and only while on, and Ctrl bypasses both`() {
        val preferences = GizmoPreferences()
        assertEquals(1.3f, preferences.snapped(write(1.3f, Snap.Grid), bypass = false), "snapping is on before it is turned on")

        preferences.gridSnap = true
        preferences.gridStep = 0.5f
        preferences.angleSnap = true
        preferences.angleStepDegrees = 90f
        assertEquals(1.5f, preferences.snapped(write(1.3f, Snap.Grid), bypass = false))
        assertEquals((PI / 2).toFloat(), preferences.snapped(write(1.3f, Snap.Angle), bypass = false))
        assertEquals(1.3f, preferences.snapped(write(1.3f, Snap.None), bypass = false), "a write that asks for no snapping was snapped")
        assertEquals(1.3f, preferences.snapped(write(1.3f, Snap.Grid), bypass = true), "Ctrl did not bypass the grid")
        assertEquals(1.3f, preferences.snapped(write(1.3f, Snap.Angle), bypass = true), "Ctrl did not bypass the angle")
    }

    @Test
    fun `a change is written to the project's file at once and read back by the next editor`() {
        val first = GizmoPreferences.load(file)
        assertFalse(Files.exists(file), "loading defaults wrote a file nobody changed")
        first.gridSnap = true
        first.gridStep = 0.25f
        first.angleStepDegrees = 45f
        first.axes = GizmoAxes.Local
        assertTrue(Files.exists(file), "a change was not written")

        val second = GizmoPreferences.load(file)
        assertTrue(second.gridSnap)
        assertEquals(0.25f, second.gridStep)
        assertFalse(second.angleSnap)
        assertEquals(45f, second.angleStepDegrees)
        assertEquals(GizmoAxes.Local, second.axes)
    }

    @Test
    fun `a hand edit gone wrong is named rather than replaced by a default`() {
        Files.createDirectories(file.parent)
        file.writeText("gizmo.grid.step=-2\n")
        val refusal = assertFailsWith<IllegalArgumentException> { GizmoPreferences.load(file) }
        assertTrue("gizmo.grid.step" in refusal.message.orEmpty(), refusal.message)
    }

    @Test
    fun `the toolbar over the Scene tab turns grid snapping on, sets its step, and switches the axes`() {
        val host = GameHost(RenderMode.Headless, UdeaGameDef(registry = CoreUdeaRegistry, modules = emptyList()))
        val loop = EditorToolLoop(host, fixtureComponents())
        val preferences = GizmoPreferences.load(file)
        val session = EditorSession(
            tools = EditorTools(loop.bridge, loop.author),
            tick = { Tick(0) },
            paused = { true },
            spawn = EditorSpawn("Spawn", BlueprintId("skeleton"), 0f, 0f),
            views = EditorViews.detached(),
            gizmos = EditorGizmos(FixtureGizmos, host.world, host.ctx[CoreModule.NET_IDS], loop.components, BodyPlacement, preferences),
        )
        uiTest { session.window.content() }.use { ui ->
            ui.settle()
            assertTrue(ui.click(EditorTags.GRID_SNAP), "no grid checkbox:\n${ui.dump()}")
            assertTrue(ui.click(EditorTags.GRID_STEP), "no grid step box")
            // The box shows "1.0": clear it, then type the step.
            repeat("1.0".length) { ui.key(Key.Backspace) }
            ui.type("2.5")
            assertTrue(ui.click(EditorTags.AXES), "no axes switch")
            ui.settle()

            assertTrue(preferences.gridSnap, "the checkbox did not turn grid snapping on")
            assertEquals(2.5f, preferences.gridStep, "the box did not set the grid step")
            assertEquals(GizmoAxes.Local, preferences.axes, "the switch did not turn the axes local")
            assertEquals(listOf("Local"), ui.texts(EditorTags.AXES), "the switch does not say which axes it is on")
            assertTrue("gizmo.grid.step=2.5" in file.readText(), "the toolbar's change was not kept in the file")
        }
    }
}
