package dev.wildware.moba.editor

import com.github.quillraven.fleks.Entity
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.moba.MobaAssets
import dev.wildware.moba.MobaGame
import dev.wildware.moba.agent.MobaAgent
import dev.wildware.moba.entry.MobaLaunchLevel
import dev.wildware.udea.agent.AgentCommand
import dev.wildware.udea.agent.AgentResult
import dev.wildware.udea.core.Ticks
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.module.CoreModule
import dev.wildware.udea.core.snapshot.SnapshotService
import dev.wildware.udea.core.snapshot.WorldHasher
import dev.wildware.udea.core.spatial.Animator
import dev.wildware.udea.core.spatial.Transform3D
import dev.wildware.udea.editor.AnimatedModel
import dev.wildware.udea.editor.AnimationTags
import dev.wildware.udea.editor.EditorAnimation
import dev.wildware.udea.editor.EditorSession
import dev.wildware.udea.editor.EditorViews
import dev.wildware.udea.generated.Fox
import dev.wildware.udea.generated.GameAssets
import dev.wildware.udea.render.model.ModelPreview
import dev.wildware.udea.render.model.ModelRenderer
import dev.wildware.udea.render.model.loadModel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Animation panel against the real `EditorToolset`, in a real `moba` world with the game's own
 * Fox in it (issue #243). Headless: the preview's picture is `GlAnimationPreviewTest`'s; this is
 * what the panel sends and what the world does about it.
 *
 * The entity is selected the way an agent selects it - `editor.select` sent with `session=editor`
 * - so the panel is shown to follow `editor.selection` rather than a click it does not have yet
 * (issue #235).
 */
class MobaAnimationPanelTest {

    private val wiring = MobaAgent.Wiring()
    private val host: GameHost =
        MobaGame.host(RenderMode.Headless, extraModules = wiring.extraModules, level = MobaLaunchLevel.bytes())
    private val session: MobaAgent.Session = MobaAgent.attach(host, RenderMode.Headless, null, wiring, editor = true)
    private val netIds = host.ctx[CoreModule.NET_IDS]
    private val foxModel = loadModel(
        Path.of(System.getProperty("udea.moba.gameAssets") ?: error("-Dudea.moba.gameAssets is not set")),
        MobaAssets.registry[GameAssets.models.fox],
    )
    private val views = EditorViews.detached()

    @AfterTest
    fun close() {
        session.close("test over")
    }

    @Test
    fun `choosing a clip sets the Animator as one undoable edit, and editor undo puts it back`() {
        val fox = spawnFox()
        val editor = editor()
        uiTest { editor.window.content() }.use { ui ->
            select(fox)
            frames(ui, editor)
            assertTrue("models/fox" in ui.text(AnimationTags.TARGET), "the panel shows \"${ui.text(AnimationTags.TARGET)}\"")
            val before = historyAsAnAgent()

            assertTrue(ui.click(AnimationTags.clip(Fox.Clips.Run.name)), "the Run button took no click:\n${ui.dump()}")
            frames(ui, editor)

            val animator = animatorOf(fox)
            assertEquals(Fox.Clips.Run.index, animator.current.clip, "the Animator is not playing Run")
            assertEquals(Fox.Clips.Run.length.count, animator.current.length, "Run's length was not written with it")
            val after = historyAsAnAgent()
            val added = after.take(after.size - before.size)
            assertEquals(listOf("editor.commit_edit"), added, "choosing a clip must be exactly one edit in the editor's history")

            val undo = asAnAgent("editor.undo", mapOf("overwrite" to "false"))
            assertTrue(undo is AgentResult.Ok, "editor.undo failed: $undo")
            val restored = animatorOf(fox)
            assertEquals(Fox.Clips.Survey.index, restored.current.clip, "undo did not put Survey back")
            assertEquals(Fox.Clips.Survey.length.count, restored.current.length, "undo did not put Survey's length back")
            assertEquals(before, historyAsAnAgent(), "the undo left an edit behind")
        }
    }

    @Test
    fun `the scrub preview poses the entity in the Scene view only, and never touches the world`() {
        val fox = spawnFox()
        val editor = editor()
        uiTest { editor.window.content() }.use { ui ->
            select(fox)
            frames(ui, editor)
            val hash = worldHash()
            assertNull(views.scene.modelPreview, "a preview was on before anyone asked for one")

            assertTrue(ui.click(AnimationTags.PREVIEW), "the preview switch took no click:\n${ui.dump()}")
            frames(ui, editor)
            assertEquals(ModelPreview.Pose(fox, Fox.Clips.Survey, Ticks(0L)), views.scene.modelPreview, "the preview starts on the clip playing, at its start")

            // Three quarters of the way along the scrubber: a press puts the knob where it lands.
            val bar = ui.node(AnimationTags.SCRUBBER).boundsInRoot
            assertTrue(ui.click(Offset(bar.left + bar.width * SCRUB_AT, bar.centre.y)), "the scrubber took no click:\n${ui.dump()}")
            frames(ui, editor)
            val scrubbed = views.scene.modelPreview as? ModelPreview.Pose
            val survey = Fox.Clips.Survey.length.count
            assertTrue(
                scrubbed != null && scrubbed.entity == fox && scrubbed.clip == Fox.Clips.Survey &&
                    scrubbed.at.count in (survey * SCRUB_LOW).toLong()..(survey * SCRUB_HIGH).toLong(),
                "a press three quarters along a $survey-tick clip previewed $scrubbed",
            )
            assertNull(views.game.modelPreview, "the Game tab must keep the simulated pose")
            assertEquals(hash, worldHash(), "scrubbing changed the world")

            assertTrue(ui.click(AnimationTags.PREVIEW), "the preview switch took no second click:\n${ui.dump()}")
            frames(ui, editor)
            assertNull(views.scene.modelPreview, "leaving the preview must show the simulated pose again")
            assertEquals(hash, worldHash(), "the preview changed the world")
            assertEquals(Fox.Clips.Survey.index, animatorOf(fox).current.clip)
        }
    }

    private fun editor(): EditorSession = MobaEditor.session(
        host,
        session,
        views = views,
        animation = EditorAnimation(host.world, netIds, listOf(AnimatedModel(foxModel, Fox.Clips.all)), renderer = null),
    )

    /** A Fox playing Survey, as `MobaEditorModels` puts one beside the player. */
    private fun spawnFox(): NetId {
        val entity: Entity = host.world.entity {
            it += Transform3D()
            it += ModelRenderer(model = foxModel)
            it += Animator().apply { play(Fox.Clips.Survey, host.ctx.clock.tick) }
        }
        return netIds.allocate(entity)
    }

    private fun select(entity: NetId) {
        val answer = asAnAgent("editor.select", mapOf("entities" to entity.raw.toString()))
        assertTrue(answer is AgentResult.Ok, "editor.select failed: $answer")
    }

    private fun animatorOf(id: NetId): Animator = with(host.world) { checkNotNull(netIds.resolveOrNull(id))[Animator] }

    /** `WorldHasher.hash` over a whole-world capture, the one `MobaEditorPlayTest` takes. */
    private fun worldHash(): Long =
        WorldHasher.hash(SnapshotService(MobaGame.componentRegistry(), host.world, host.ctx, netIds).capture())

    /** A few frames of the order `runWithGl` runs them in: pump the loop, then the editor's frame. */
    private fun frames(ui: UiTest, editor: EditorSession) {
        repeat(FRAMES) {
            session.loop.pump(1f / 60f)
            editor.frame()
            ui.settle()
        }
    }

    /** [tool] as an outside agent calls it, under the editor's author. */
    private fun asAnAgent(tool: String, args: Map<String, String>): AgentResult {
        val command = AgentCommand(tool, args, session = wiring.sessions.intern(MobaEditor.AUTHOR))
        wiring.bridge.submit(command)
        session.loop.pump(1f / 60f)
        return wiring.bridge.commandResults().single { it.id == command.id }.result
    }

    /** The tools of the editor author's history, newest first. */
    private fun historyAsAnAgent(): List<String> {
        val answer = asAnAgent("editor.history", mapOf("limit" to "20"))
        check(answer is AgentResult.Ok) { "editor.history failed: $answer" }
        return Json.parseToJsonElement(answer.json).jsonObject.getValue("edits").jsonArray.map {
            it.jsonObject.getValue("tool").jsonPrimitive.content
        }
    }

    private companion object {
        /** Enough for a click to be sent, and the three calls of an edit session run and answered. */
        const val FRAMES = 10

        /** Where along the scrubber the press lands, and the clip times that counts as reaching. */
        const val SCRUB_AT = 0.75f
        const val SCRUB_LOW = 0.6
        const val SCRUB_HIGH = 0.9
    }
}
