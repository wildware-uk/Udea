package dev.wildware.moba.entry

import com.github.quillraven.fleks.World.Companion.family
import dev.wildware.moba.MobaGame
import dev.wildware.moba.MobaScene
import dev.wildware.moba.Player
import dev.wildware.moba.level.TestLevelScene
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.module.CoreModule
import dev.wildware.udea.core.module.UdeaGameDef
import dev.wildware.udea.render.OverlayResources
import dev.wildware.udea.render.OverlaySystem
import dev.wildware.udea.render.RenderPipeline
import dev.wildware.udea.render.input.CompositeIntent
import dev.wildware.udea.render.input.DeviceIntent
import dev.wildware.udea.render.input.GdxKeyboard
import dev.wildware.udea.render.input.IntentSource
import dev.wildware.udea.render.input.IntentState
import dev.wildware.udea.render.backend.Lwjgl3Backend
import dev.wildware.udea.render.backend.WindowConfig
import dev.wildware.udea.render.control.PresentationControl

/**
 * What every entry point does in common, so the three differ only where spec 3.5 says they may.
 *
 * There is one thing worth stating here and it is not a convenience: the [RenderMode] is the
 * **only** parameter of a `moba` process. `MobaGame.definition()` takes no mode, no role and no
 * branch on either, so there is no arrangement in which "the server" and "the agent's instance"
 * simulate differently.
 */
public object MobaEntry {

    /** System property naming the mode. Set by `UdeaAgentPlugin` on a launched instance. */
    public const val RENDER_MODE_PROPERTY: String = "udea.render.mode"

    /**
     * The [RenderMode] named by `-Dudea.render.mode`, or [fallback].
     *
     * An unrecognised value throws rather than falling back. A launcher that misspells the mode
     * would otherwise get a headless process whose `/health` says `Headless` and whose render
     * tools all answer `no_render_context` - a silent downgrade an agent has no way to
     * distinguish from a machine with no GL driver.
     */
    public fun modeFromProperties(
        fallback: RenderMode,
        properties: (String) -> String? = System::getProperty,
    ): RenderMode {
        val raw = properties(RENDER_MODE_PROPERTY)?.trim().orEmpty()
        if (raw.isEmpty()) return fallback
        return RenderMode.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
            ?: throw IllegalArgumentException(
                "-D$RENDER_MODE_PROPERTY=$raw is not a RenderMode; expected one of " +
                    RenderMode.entries.joinToString { it.name },
            )
    }

    /**
     * The window and framebuffer a `moba` process asks a GL backend for.
     *
     * One config for both GL modes, which is what makes an `Offscreen` capture and a `Windowed`
     * capture the same size and the same framing - see [WindowConfig]'s own KDoc for the four
     * wall-clock inputs that still stop them being byte-identical.
     */
    public fun windowConfig(): WindowConfig = WindowConfig(title = "moba")

    /**
     * What `moba` draws: one animated champion per unit, at that unit's position.
     *
     * Delegates to [MobaScene], which carries the reasoning. The short version is that this
     * returned an **empty** registry until the Phase 1 demo was driven end to end, and an empty
     * registry makes that demo's image diff structurally incapable of showing anything: every
     * capture is the same cleared framebuffer whatever the simulation is doing.
     */
    public fun scene(definition: UdeaGameDef): MobaScene = MobaScene.build(definition)

    /**
     * Loads `level/test_level` and runs the tick that applies the swap.
     *
     * Shared by all three entry points, which is the point of it: `runServer`, `runClient` and
     * the agent's instance load the **same scene** over the same `Simulation`, so a fight that
     * unfolds one way in a capture and another way on the server would be a real defect rather
     * than two games that were never the same to begin with.
     *
     * A scene swap is a `BarrierAction`: `requestScene` submits, and the next tick is what
     * clears the world, resets the net ids and populates. So the `run(1)` is not a nicety - a
     * caller that skipped it would read an empty world from `/state` and could not tell that
     * from a level that failed to spawn. `MobaGame.definition` is where the scene was registered
     * under this id; requesting an unregistered one throws with the known ids in the message.
     */
    public fun seed(host: GameHost): NetId {
        host.ctx.scenes.requestScene(TestLevelScene.ID)
        host.run(1)
        // The player is the level's own `player` entity, not a twenty-eighth unit spawned beside
        // it: `TestLevelScene` puts the `Player` component on the authored entity of that name,
        // so the unit a human drives is the one standing in the orc clearing where the old game
        // dropped it. Resolved from the world rather than returned by the swap, because a scene
        // swap is a barrier action and the entities do not exist until the tick above drained it.
        return playerId(host)
    }

    /**
     * The net id of the level's player unit.
     *
     * Exactly one, and a failure when there is not: zero means the level lost its `player` entity
     * or the override that marks it, and two means something spawned a second one - and both of
     * those end as "the camera follows the wrong soldier", which is the kind of bug that gets
     * blamed on the camera. `render.follow_entity` is handed whatever this returns, so the
     * refusal belongs here rather than in the rig.
     */
    public fun playerId(host: GameHost): NetId {
        val players = host.world.family { all(Player) }
        val entities = players.entities
        check(entities.size == 1) {
            "the level must contain exactly one Player, and this world has ${entities.size}; " +
                "`TestLevelScene` marks the authored entity named " +
                "'${TestLevelScene.PLAYER_ENTITY}'"
        }
        return host.ctx[CoreModule.NET_IDS].netIdOf(entities[0])
    }

    /**
     * Points [rendering]'s camera at [player] and puts the view on it immediately.
     *
     * `snapToTarget` as well as `requestFollow`, because the first frame of a scene is one of the
     * two frames easing is wrong on: the camera's previous position describes the default framing
     * rather than a world it was tracking, and easing from it drags the view across the level
     * while the player is trying to work out where their character is.
     */
    public fun follow(rendering: Rendering, player: NetId) {
        rendering.scene.follow(player)
    }

    /**
     * Wires this process's keyboard into the simulation's [IntentState], and returns the source.
     *
     * ## The two halves, and why only one of them names LibGDX
     *
     * [GdxKeyboard] is the device half - the one class in the tree that reads a physical key -
     * and it is installed as the window's input processor here, on the render thread, because
     * `Gdx.input` has the same thread affinity every `Gdx` static has. [DeviceIntent] is the
     * mapping half: it turns key states into the [dev.wildware.udea.render.input.Intent] the
     * simulation reads, names no GL type, and is what `MobaInputTest` drives with no window.
     *
     * ## Where an agent's input joins
     *
     * [extra] is the agent's [dev.wildware.udea.render.input.InjectedIntent], or `null` for a
     * plain client. When it is present the two are combined rather than one replacing the other,
     * so a human watching a Windowed agent instance can still play - see `CompositeIntent` for
     * why there is deliberately no priority rule between them.
     *
     * ## What an agent still cannot reach
     *
     * The overlay hotkey. `GdxOverlayKey` polls `Gdx.input.isKeyPressed` directly and is not in
     * the chain installed here, so no `input.*` tool and no injected source can toggle the panel
     * that narrates what the agent is doing (issue #161). That is structural: the arrow runs from
     * a device to an intent, and there is no arrow back.
     */
    public fun wireInput(
        host: GameHost,
        rendering: Rendering,
        extra: IntentSource? = null,
    ): IntentSource {
        val state = host.ctx[IntentState.KEY]
        val keyboard = GdxKeyboard()
        val device = DeviceIntent(state.bindings, keyboard)
        val source = if (extra == null) {
            device
        } else {
            CompositeIntent(state.bindings.catalog, listOf(device, extra))
        }
        rendering.onRenderThread {
            // The scene2d stage would go first in this chain when this game grows one; the
            // keyboard consumes nothing, so it is safe at the end of any chain.
            GdxKeyboard.install(keyboard)
        }
        state.source = source
        return source
    }

    /**
     * Boots a GL backend in [mode], hands it the host, and blocks until the window closes.
     *
     * The two-step - construct the backend, build the host from it, *then* `drive` - is
     * [Lwjgl3Backend]'s, and is forced by a real cycle: a `GameHost` builds its presentation
     * inside its own constructor, so the presentation cannot be handed a host that does not yet
     * exist.
     *
     * @param overlay the agent activity overlay, or `null` for a process that draws none. Refused
     *   outright outside [RenderMode.Windowed]: spec 3.7 makes the overlay a Windowed-only thing,
     *   and this is the composition root, so this is where that is decided rather than left to
     *   `AgentOverlayView.isEnabled` to absorb quietly. A client passes `null`; only
     *   `dev.wildware.moba.agent.MobaAgent` has anything to narrate.
     * @param attach runs once the host exists and before frames start flowing, and returns what
     *   drives a frame. That return value is the whole difference between a player's client and
     *   an agent's instance: a client hands back `host::frame`, and an agent hands back
     *   `AgentGameLoop::pump`, which drains the command queue on either side of the same call.
     *   A `GameHost` that a render backend drives directly can never execute an agent command,
     *   because nothing calls `AgentRuntime.beforeFrame`.
     */
    public fun runWithGl(
        mode: RenderMode,
        overlay: ((OverlayResources) -> OverlaySystem)? = null,
        attach: (GameHost, Rendering) -> Attachment,
    ) {
        require(mode != RenderMode.Headless) { "RenderMode.Headless has no GL backend" }
        require(overlay == null || mode == RenderMode.Windowed) {
            "an OverlaySystem was passed for $mode. Spec 3.7: the overlay exists only in " +
                "RenderMode.Windowed - Offscreen exists solely to produce captures, so a panel " +
                "drawn there is a panel drawn for nobody, and the one arrangement that must " +
                "never be possible is the one where it is drawn for an agent. This is a refusal " +
                "and not a silent skip, because a host that thought it had wired an overlay and " +
                "had not is exactly what took a wave to notice last time."
        }
        // The definition is built *here* rather than inside `MobaGame.host`, because the scene
        // needs it before the backend exists and the backend needs the scene: `CameraRig` resolves
        // a followed entity through the definition's `NetIdIndex`, and `Lwjgl3Backend.start` takes
        // a registry that is already complete. Three things in a fixed order, and the order is
        // forced rather than chosen: definition, scene, backend, host.
        // The three `StartupTrace` brackets are the phase breakdown issue #94 requires, and they
        // are here rather than inside `MobaBench` for the reason that gate exists: a benchmark
        // that instruments a *copy* of the boot sequence measures the copy. The cost is two
        // `nanoTime` calls per phase, once per process.
        val definition = StartupTrace.world { MobaGame.definition() }
        val scene = StartupTrace.world { scene(definition) }
        // Registered before `start`, because `Lwjgl3Backend.start` builds the pipeline out of the
        // registry and a registration after that point reaches nothing.
        if (overlay != null) scene.registry.overlay(overlay)
        val backend = StartupTrace.gl { Lwjgl3Backend.start(mode, windowConfig(), scene.registry) }
        var attachment: Attachment? = null
        try {
            val host = StartupTrace.world { GameHost(mode, definition, backend) }
            val pipeline = checkNotNull(backend.pipeline) {
                "GameHost built no presentation in $mode, so nothing can be captured"
            }
            val attached = attach(
                host,
                Rendering(
                    scene,
                    pipeline,
                    requestExit = { requestExit(backend) },
                    onRenderThread = { block -> backend.onRenderThread(block) },
                ),
            )
            attachment = attached
            backend.drive(attached.frame)
            backend.awaitExit()
        } finally {
            attachment?.close?.invoke()
            backend.close()
        }
    }

    /**
     * The two render-side objects a caller may need, handed over once the backend is up.
     *
     * Bundled rather than passed as two parameters so that adding a third does not change every
     * entry point's signature. A client ignores both; the agent turns them into the
     * `PresentationControl` its render toolset is wired to.
     */
    public class Rendering(
        /** What was registered, and what owns the camera and the debug switch. */
        public val scene: MobaScene,
        /** The live pipeline. Its offscreen target is the only surface a capture reads. */
        public val pipeline: RenderPipeline,
        /**
         * Asks the render loop to exit, so [runWithGl] returns and the process winds down.
         *
         * This is the half of `close` a headless host does not have. `AgentGameLoop.stop` ends a
         * loop this class is not running: in either GL mode the render thread owns the cadence
         * and `awaitExit` is what the main thread is parked on, so nothing short of ending the
         * GL loop makes the process leave. Without it, `close` would unbind the port and leave a
         * window on the desktop - and the bridge, which takes silence on the port as its
         * confirmation, would report a clean close over a game that is still running.
         *
         * **Safe to call from a frame**, which is the reason it exists at all rather than the
         * caller being handed the backend. `Lwjgl3Backend.close` submits the pipeline dispose to
         * the GL thread and then waits for the loop to finish; in Offscreen the GL thread *is*
         * the simulation thread, so a tool calling it directly would be waiting for the thread
         * it is running on. See [requestExit].
         */
        public val requestExit: () -> Unit = {},
        /**
         * Runs a block on the render thread and waits for it.
         *
         * Exists for exactly one caller and it is worth naming: `Gdx.input` has the same thread
         * affinity every other `Gdx` static has, and installing the window's input chain is the
         * one piece of start-up work that touches it. Handing over the whole `Lwjgl3Backend` so
         * an entry point could call `onRenderThread` itself would also hand it `close`, and
         * `close` from inside a frame deadlocks (see [requestExit]).
         *
         * The default runs inline, for a `Rendering` a test builds with no backend behind it.
         */
        public val onRenderThread: (() -> Unit) -> Unit = { it() },
    ) {
        /** The control surface, for a caller that has an agent toolset to wire to it. */
        public fun presentation(): PresentationControl = scene.presentation(pipeline)
    }

    /**
     * Ends [backend]'s render loop, from a thread that is not it.
     *
     * A daemon thread and not a queued task: `close` disposes on the GL thread and then joins
     * it, so the frame that asked has to be allowed to return and drain that task. The thread
     * lives for the length of one shutdown and swallows its own failure - there is nothing left
     * to report to by then, and a throw here would only replace an exit with a hang.
     */
    private fun requestExit(backend: Lwjgl3Backend) {
        val closer = Thread({ runCatching { backend.close() } }, "moba-render-exit")
        closer.isDaemon = true
        closer.start()
    }

    /** What [runWithGl]'s caller contributes: a frame driver, and its teardown. */
    public class Attachment(
        /** Called by the render thread once per frame with the wall delta in seconds. */
        public val frame: (Float) -> Unit,
        /** Run after the render loop exits and before the context is destroyed. */
        public val close: () -> Unit = {},
    )
}
