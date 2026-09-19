package dev.wildware.moba.editor

import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.widget.SceneDrawScope
import dev.wildware.moba.Position
import dev.wildware.moba.agent.MobaAgent
import dev.wildware.moba.entry.MobaLaunch
import dev.wildware.udea.core.blueprint.BlueprintId
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.module.CoreModule
import dev.wildware.udea.editor.EditorSession
import dev.wildware.udea.editor.EditorSpawn
import dev.wildware.udea.editor.EditorTools
import dev.wildware.udea.editor.StandaloneLauncher
import dev.wildware.udea.editor.editorFonts
import dev.wildware.udea.render.ui.UiLayer

/**
 * `sh gradlew :moba:desktop:runEditor`: `moba` in the editor window (issue #194).
 *
 * The same process `:moba:desktop:run -Peditor=true` starts - the same game, the same loop, every
 * agent toolset including `editor.*`, and the HTTP surface when `-PdebugPort=N` is passed - with the
 * editor window shown over it: docked panels, and the world drawn by Kool in a ComposeGL `SceneView`.
 *
 * ## Why it is in an `editor` source set
 *
 * So that no release classpath carries it. `udea-editor` is on this source set's classpath and on no
 * other in the project, and `UDEA-MG-010` fails the build if `:moba:desktop`'s `runtimeClasspath` -
 * the one its jar runs on - ever resolves it. `jar` packages `main` alone.
 *
 * ## It starts paused
 *
 * The world is paused before the first frame, so what the viewport shows is the level as it loaded
 * and nothing moves until someone asks it to: Play and Step in the window's toolbar (issue #196), or
 * `editor.play`, `time.resume` and `time.step` from an agent.
 */
public object MobaEditor {

    /**
     * Who the window's edits are filed under. An agent sending `session=editor` is the same author,
     * so it sees and can undo them.
     */
    internal const val AUTHOR: String = "editor"

    /**
     * Where the spawn button puts its skeleton: this far to the right of the player, in world units.
     *
     * The camera starts on the player, so the middle of the view is the player's own sprite, and a unit
     * spawned exactly there would be hidden under it.
     */
    internal const val SPAWN_OFFSET_X: Float = 48f

    /** The size the window's interface is laid out at, fitted to whatever the window is. */
    private val DESIGN = Size(1280f, 720f)

    /** Boots, shows the window, and blocks until it is closed. */
    @JvmStatic
    public fun main(args: Array<String>) {
        val mode = MobaLaunch.modeFromProperties(fallback = RenderMode.Windowed)
        require(mode != RenderMode.Headless) {
            "the editor is a window, and RenderMode.Headless has none; run :moba:desktop:run -Peditor=true " +
                "for the editor.* tools with no window"
        }
        MobaAgent.runWithGl(mode, MobaAgent.Wiring(), editor = true) { host, rendering, session ->
            open(host, rendering, session)
        }
    }

    /** Puts the window over the world, paused. Before the first frame. */
    private fun open(host: GameHost, rendering: MobaLaunch.Rendering, session: MobaAgent.Session): MobaAgent.Screen {
        val world = rendering.world()
        val editor = session(host, session, viewport = { world.drawInto(this) }, standalone = MobaStandalone())
        val fonts = editorFonts()
        val layer = UiLayer(fonts, DESIGN)
        rendering.show(layer)
        layer.show(editor.window)
        return MobaAgent.Screen(layer, frame = editor::frame, afterExit = fonts::close)
    }

    /**
     * The editor over a wired agent [session] on [host]: everything except the window's pixels, so a
     * test can press its buttons with no GL context. Pauses [host] first: the editor starts paused.
     * [standalone] is what Play standalone launches; `null` leaves the button out.
     */
    internal fun session(
        host: GameHost,
        session: MobaAgent.Session,
        viewport: SceneDrawScope.() -> Unit,
        standalone: StandaloneLauncher? = null,
    ): EditorSession {
        host.time.pause()
        return EditorSession(
            tools = EditorTools(session.wiring.bridge, session.wiring.sessions.intern(AUTHOR)),
            tick = { host.ctx.clock.tick },
            paused = { host.time.paused },
            spawn = spawnBeside(host, session.player),
            viewport = viewport,
            standalone = standalone,
        )
    }

    /** A skeleton, [SPAWN_OFFSET_X] to the right of where [player] stands now. */
    private fun spawnBeside(host: GameHost, player: NetId): EditorSpawn {
        val entity = checkNotNull(host.ctx[CoreModule.NET_IDS].resolveOrNull(player)) {
            "the player $player is not in the world the editor opened on"
        }
        val position = with(host.world) { entity[Position] }
        return EditorSpawn("Spawn skeleton beside the player", BlueprintId("skeleton"), position.x + SPAWN_OFFSET_X, position.y)
    }
}
