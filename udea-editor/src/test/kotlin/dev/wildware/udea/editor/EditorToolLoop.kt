package dev.wildware.udea.editor

import dev.wildware.udea.agent.AgentBridge
import dev.wildware.udea.agent.AgentCommand
import dev.wildware.udea.agent.AgentResult
import dev.wildware.udea.agent.activity.AgentSessionId
import dev.wildware.udea.agent.activity.AgentSessions
import dev.wildware.udea.agent.dispatch.AgentRuntime
import dev.wildware.udea.agent.dispatch.ToolIndex
import dev.wildware.udea.agent.query.AgentComponentIndex
import dev.wildware.udea.agent.query.AgentComponentType
import dev.wildware.udea.agent.query.agentComponent
import dev.wildware.udea.agent.tools.EditorToolset
import dev.wildware.udea.agent.tools.EngineToolModules
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.module.CoreModule
import dev.wildware.udea.core.physics.PhysicsBody
import dev.wildware.udea.core.physics.PhysicsBodyReplicator
import dev.wildware.udea.render.view.PickBounds
import dev.wildware.udea.render.view.PickSink
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The real `editor.*` tools over [host]'s world, answering whatever the editor window sends: the
 * simulation's half of the loop, pumped by hand.
 *
 * What the picking tests need is the selection as an agent would read it - `editor.selection` for the
 * `editor` author - and not the window's copy of it, so the window's `editor.select` calls go through
 * the same `EditorToolset` an agent's HTTP call reaches, and [selection] asks it the way an agent does.
 */
internal class EditorToolLoop(
    val host: GameHost,
    /** What the tools can address. One by default, because an index of none refuses to exist; selecting reads none. */
    components: List<AgentComponentType> = listOf(agentComponent("PhysicsBody", PhysicsBodyReplicator, PhysicsBody)),
) {

    val bridge: AgentBridge = AgentBridge()

    val sessions: AgentSessions = AgentSessions()

    /** The components the tools address, by name: what the editor's gizmos are given to name theirs. */
    val components: AgentComponentIndex = AgentComponentIndex(components)

    /** The author the editor window files under. */
    val author: AgentSessionId = sessions.intern(AUTHOR)

    private val runtime = AgentRuntime(
        bridge = bridge,
        tools = EngineToolModules.wireAll(
            ToolIndex.builder(),
            EditorToolset(
                world = host.world,
                components = this.components,
                netIds = host.ctx[CoreModule.NET_IDS],
                sessions = sessions,
                bridge = bridge,
                clock = host.ctx.clock,
            ),
        ).build(),
        world = host.world,
        ctx = host.ctx,
        digest = {},
    )

    /** Runs every command queued so far, between ticks, as a paused game's loop does. */
    fun pump() {
        runtime.beforeFrame()
        runtime.afterFrame(0)
    }

    /** A new entity in the world, with a `NetId`. */
    fun spawn(): NetId = host.ctx[CoreModule.NET_IDS].allocate(host.world.entity { })

    /** The `editor` author's selection, as an agent calling `editor.selection` with `session=editor` reads it. */
    fun selection(): List<NetId> {
        val answer = call("editor.selection")
        // Read here rather than through the window's own parser, so a fault in that cannot agree with itself.
        val authors = Json.parseToJsonElement(answer).jsonObject.getValue("authors").jsonArray.map { it.jsonObject }
        val own = authors.singleOrNull { it.getValue("author").jsonPrimitive.content == AUTHOR } ?: return emptyList()
        return own.getValue("ids").jsonArray.map { NetId.ofRaw(it.jsonPrimitive.int) }
    }

    /** Selects [entities] for the `editor` author, as an agent calling `editor.select` with `session=editor` does. */
    fun select(entities: List<NetId>) {
        call("editor.select", mapOf("entities" to entities.joinToString(",") { it.raw.toString() }, "mode" to "replace"))
    }

    /** The tools of the `editor` author's undo history, newest first, as `editor.history` lists them. */
    fun history(): List<String> =
        Json.parseToJsonElement(call("editor.history")).jsonObject.getValue("edits").jsonArray
            .map { it.jsonObject.getValue("tool").jsonPrimitive.content }

    /** Runs [tool] as the `editor` author, now, and answers its JSON; fails the test if the tool refused. */
    private fun call(tool: String, args: Map<String, String> = emptyMap()): String {
        val command = AgentCommand(tool, args, session = author)
        bridge.submit(command)
        pump()
        val answer = bridge.commandResults().single { it.id == command.id }.result
        check(answer is AgentResult.Ok) { "$tool failed: $answer" }
        return answer.json
    }

    override fun toString(): String = "EditorToolLoop($host)"

    companion object {
        const val AUTHOR = "editor"
    }
}

/**
 * A render system in all but drawing: it reports squares of world units, as a game's own render
 * system reports what it draws, in the order given - back to front.
 */
internal class Squares(private vararg val squares: Square) : PickBounds {

    override fun reportPickBounds(out: PickSink) {
        for (square in squares) {
            out.rect(square.entity, square.x - square.half, square.y - square.half, square.x + square.half, square.y + square.half)
        }
    }

    /** [entity] drawn as a square [half] world units either side of ([x], [y]). */
    class Square(val entity: NetId, val x: Float, val y: Float, val half: Float)
}
