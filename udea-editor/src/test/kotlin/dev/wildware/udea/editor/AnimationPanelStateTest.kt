package dev.wildware.udea.editor

import com.github.quillraven.fleks.configureWorld
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.udea.agent.AgentBridge
import dev.wildware.udea.agent.AgentCommand
import dev.wildware.udea.agent.AgentErrorKind
import dev.wildware.udea.agent.AgentResult
import dev.wildware.udea.agent.activity.AgentSessions
import dev.wildware.udea.assets.AssetId
import dev.wildware.udea.assets.Model
import dev.wildware.udea.assets.ResPath
import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.Ticks
import dev.wildware.udea.core.blueprint.BlueprintId
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.identity.NetIdIndex
import dev.wildware.udea.core.spatial.AnimationClip
import dev.wildware.udea.core.spatial.Animator
import dev.wildware.udea.render.model.ModelPreview
import dev.wildware.udea.render.model.ModelRenderer
import dev.wildware.udea.render.model.loadModel
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Animation panel against the tool surface's own seam, with the simulation's half played by hand
 * (issue #243), as `EditorSessionTest` does for the rest of the window: what the panel sends, and
 * what it does with the answers. The real `EditorToolset` answering the real calls is
 * `MobaAnimationPanelTest`'s; the picture is `GlAnimationPreviewTest`'s.
 */
class AnimationPanelStateTest {

    private val survey = AnimationClip(index = 0, name = "Survey", length = Ticks(205L))
    private val walk = AnimationClip(index = 1, name = "Walk", length = Ticks(43L))
    private val run = AnimationClip(index = 2, name = "Run", length = Ticks(70L))

    private val fox = loadModel(
        Path.of(System.getProperty("udea.render.exampleAssets") ?: error("-Dudea.render.exampleAssets is not set")),
        Model(AssetId("models/fox"), ResPath("models/fox/Fox.glb")),
    )

    private val world = configureWorld { }
    private val netIds = NetIdIndex()
    private val bridge = AgentBridge()
    private val views = EditorViews.detached()
    private val session = EditorSession(
        tools = EditorTools(bridge, AgentSessions().intern("editor")),
        tick = { Tick(0) },
        paused = { true },
        spawn = EditorSpawn("Spawn", BlueprintId("skeleton"), 0f, 0f),
        views = views,
        animation = EditorAnimation(world, netIds, listOf(AnimatedModel(fox, listOf(survey, walk, run))), renderer = null),
    )
    private val panel = checkNotNull(session.animation)

    /** A fox playing Walk, and a second entity with an Animator and no model. */
    private val animated: NetId = netIds.allocate(
        world.entity {
            it += ModelRenderer(model = fox)
            it += Animator().apply { play(walk, Tick(0)) }
        },
    )
    private val modelless: NetId = netIds.allocate(world.entity { it += Animator() })

    /** What the editor asked for since the last call. */
    private fun drain(): List<AgentCommand> = ArrayList<AgentCommand>().also { bridge.drain(it) }

    /**
     * One editor frame, then every call it sent answered as the simulation would: the selection as
     * [selected], the history empty, and [answers] for anything else.
     */
    private fun frame(selected: List<NetId>, answers: (AgentCommand) -> AgentResult = { AgentResult.Ok("{}") }): List<AgentCommand> {
        session.frame()
        val sent = drain()
        for (command in sent) {
            val answer = when (command.name) {
                "editor.selection" ->
                    AgentResult.Ok("""{"you":"editor","authors":[{"author":"editor","ids":[${selected.joinToString(",") { it.raw.toString() }}]}]}""")
                "editor.history" -> AgentResult.Ok("""{"author":"editor","size":0,"edits":[]}""")
                else -> answers(command)
            }
            bridge.complete(command.id, answer)
        }
        return sent
    }

    private fun settle(selected: List<NetId>) = repeat(SETTLE_FRAMES) { frame(selected) }

    /**
     * The selection changing to [selected] as it does for real: an `editor.select` completes - a
     * click's, or an agent's - and the editor reads the selection again.
     */
    private fun select(selected: List<NetId>) {
        val command = AgentCommand("editor.select", mapOf("entities" to selected.joinToString(",") { it.raw.toString() }), session = AgentSessions().intern("editor"))
        bridge.submit(command)
        drain()
        bridge.complete(command.id, AgentResult.Ok("{}"))
        settle(selected)
    }

    @Test
    fun `the panel follows the editor's selection to the first entity drawn with an animated model`() {
        settle(listOf(modelless))
        assertNull(panel.target, "an entity with no model has no clips to show")

        select(listOf(modelless, animated))
        assertEquals(animated, panel.target)
        assertEquals(listOf(survey, walk, run), panel.targetModel?.clips, "the panel lists the model's clips")
        assertEquals(walk.index, panel.playingClip, "the panel marks the clip the Animator is playing")

        select(emptyList())
        assertNull(panel.target, "clearing the selection leaves the panel with nothing")
    }

    @Test
    fun `an idle window sends nothing, with the panel's selection read beside the history's`() {
        // The two reads must not wake each other: each re-reads after a command completes, and a
        // read is a command.
        settle(listOf(animated))
        repeat(IDLE_FRAMES) {
            assertEquals(emptyList(), frame(listOf(animated)).map { it.name }, "an idle editor kept calling tools")
        }
    }

    @Test
    fun `any command another caller completes makes the panel read the selection again`() {
        settle(emptyList())
        assertNull(panel.target)
        val select = AgentCommand("editor.select", mapOf("entities" to animated.raw.toString()), session = AgentSessions().intern("agent"))
        bridge.submit(select)
        drain()
        bridge.complete(select.id, AgentResult.Ok("{}"))

        val sent = frame(listOf(animated)).map { it.name }
        assertTrue("editor.selection" in sent, "a completed command did not make the panel read the selection again: $sent")
        settle(listOf(animated))
        assertEquals(animated, panel.target)
    }

    @Test
    fun `choosing a clip is an edit session on the Animator's clip and length, closed as one edit`() {
        settle(listOf(animated))
        panel.choose(run)

        val begin = frame(listOf(animated)) { AgentResult.Ok("""{"sessionId":7,"start":[]}""") }.single { it.name != "editor.history" && it.name != "editor.selection" }
        assertEquals("editor.begin_edit", begin.name)
        assertEquals(mapOf("entities" to "${animated.raw}", "fields" to "Animator.current.clip,Animator.current.length"), begin.args)

        val update = frame(listOf(animated)).single { it.name == "editor.update_edit" }
        assertEquals(mapOf("sessionId" to "7", "values" to "Animator.current.clip=2,Animator.current.length=70"), update.args)

        val commit = frame(listOf(animated)).single { it.name == "editor.commit_edit" }
        assertEquals(mapOf("sessionId" to "7"), commit.args)
        assertEquals(walk.index, with(world) { checkNotNull(netIds.resolveOrNull(animated))[Animator] }.current.clip, "the panel wrote the world itself")
    }

    @Test
    fun `a refused edit says why, and sends nothing after it`() {
        settle(listOf(animated))
        panel.choose(run)
        val refused = frame(listOf(animated)) { AgentResult.failed(AgentErrorKind("no_such_field"), "no component named Animator") }
        assertTrue(refused.any { it.name == "editor.begin_edit" })
        assertEquals(emptyList(), frame(listOf(animated)).filter { it.name.endsWith("_edit") }.map { it.name })
        assertTrue(panel.problem.orEmpty().startsWith("editor.begin_edit refused"), "the panel says \"${panel.problem}\"")
    }

    @Test
    fun `the preview is a Scene view setting that follows play, pause and the scrubber, and clears on leaving`() {
        settle(listOf(animated))
        panel.preview(true)
        settle(listOf(animated))
        assertEquals(ModelPreview.Pose(animated, walk, Ticks(0L)), views.scene.modelPreview, "the preview starts on the clip playing, at its start")
        assertNull(views.game.modelPreview)

        panel.play(true)
        repeat(5) { frame(listOf(animated)) }
        assertEquals(ModelPreview.Pose(animated, walk, Ticks(5L)), views.scene.modelPreview, "playing moves the preview one clip tick a frame")

        panel.scrub(40L)
        assertTrue(!panel.playing, "the scrubber pauses the preview")
        frame(listOf(animated))
        assertEquals(ModelPreview.Pose(animated, walk, Ticks(40L)), views.scene.modelPreview)

        panel.play(true)
        repeat(5) { frame(listOf(animated)) }
        assertEquals(Ticks(1L), (views.scene.modelPreview as ModelPreview.Pose).at, "playing past the clip's end starts it again")

        panel.preview(false)
        frame(listOf(animated))
        assertNull(views.scene.modelPreview, "leaving the preview must show the simulated pose again")
    }

    @Test
    fun `selecting another entity ends the preview`() {
        settle(listOf(animated))
        panel.preview(true)
        settle(listOf(animated))
        select(listOf(modelless))
        assertNull(views.scene.modelPreview, "a preview outlived its entity's selection")
    }

    @Test
    fun `a model can be previewed on its own, turning, with its clips`() {
        settle(emptyList())
        val model = panel.models.single()
        panel.showModel(model)
        frame(emptyList())
        val first = views.scene.modelPreview as ModelPreview.Asset
        frame(emptyList())
        val second = views.scene.modelPreview as ModelPreview.Asset
        assertEquals(fox, first.model)
        assertTrue(second.turnDegrees > first.turnDegrees, "the model preview does not turn")

        panel.previewOf(run)
        frame(emptyList())
        assertEquals(run, (views.scene.modelPreview as ModelPreview.Asset).clip)

        panel.showModel(model)
        frame(emptyList())
        assertNull(views.scene.modelPreview, "a second press does not put the model away")
    }

    @Test
    fun `the panel shows the selected entity's clips`() {
        uiTest { AnimationPanel(panel) }.use { ui ->
            settle(listOf(animated))
            ui.settle()
            assertTrue("models/fox" in ui.text(AnimationTags.TARGET), "the panel shows \"${ui.text(AnimationTags.TARGET)}\"")
            ui.assertExists(AnimationTags.clip("Survey"))
            assertTrue("playing" in ui.text(AnimationTags.clip("Walk")), "Walk is not marked as playing: ${ui.text(AnimationTags.clip("Walk"))}")
            ui.assertExists(AnimationTags.clip("Run"))
        }
    }

    private companion object {
        /** Enough frames for a read to be sent, answered and delivered. */
        const val SETTLE_FRAMES = 3

        const val IDLE_FRAMES = 5
    }
}
