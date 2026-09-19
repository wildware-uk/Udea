package dev.wildware.moba.agent

import com.github.quillraven.fleks.Family
import com.github.quillraven.fleks.World
import com.github.quillraven.fleks.World.Companion.family
import dev.wildware.moba.MobaControls
import dev.wildware.moba.MobaGame
import dev.wildware.moba.Position
import dev.wildware.moba.PositionReplicator
import dev.wildware.moba.audio.MobaAudio
import dev.wildware.moba.level.GameUnit
import dev.wildware.moba.level.GameUnitReplicator
import dev.wildware.moba.level.MobaBlueprints
import dev.wildware.moba.level.Team
import dev.wildware.moba.item.Inventory
import dev.wildware.moba.item.InventoryReplicator
import dev.wildware.moba.lane.Tower
import dev.wildware.moba.lane.TowerReplicator
import dev.wildware.moba.match.MatchState
import dev.wildware.moba.match.MatchStateReplicator
import dev.wildware.moba.entry.MobaEntry
import dev.wildware.moba.entry.MobaLaunch
import dev.wildware.moba.entry.MobaLaunchLevel
import dev.wildware.udea.agent.AgentBridge
import dev.wildware.udea.agent.AgentTimings
import dev.wildware.udea.agent.activity.AgentSessions
import dev.wildware.udea.agent.dispatch.AgentRuntime
import dev.wildware.udea.agent.dispatch.ToolIndex
import dev.wildware.udea.agent.host.AgentArtifacts
import dev.wildware.udea.agent.host.AgentGameLoop
import dev.wildware.udea.agent.host.AgentHost
import dev.wildware.udea.agent.host.AgentHostConfig
import dev.wildware.udea.agent.host.AgentHostTools
import dev.wildware.udea.agent.host.EditorViewTools
import dev.wildware.udea.agent.host.EditorViewToolset
import dev.wildware.udea.agent.host.AgentInputTools
import dev.wildware.udea.agent.host.EditorMode
import dev.wildware.udea.agent.host.ArtifactToolset
import dev.wildware.udea.agent.host.InputToolset
import dev.wildware.udea.agent.host.GameIdentity
import dev.wildware.udea.agent.host.HostShutdown
import dev.wildware.udea.agent.host.RenderControl
import dev.wildware.udea.agent.host.RenderToolset
import dev.wildware.udea.agent.host.ToolManifest
import dev.wildware.udea.agent.host.render.OffscreenRenderControl
import dev.wildware.udea.agent.host.render.WorldViewportControl
import dev.wildware.udea.render.view.WorldViewport
import dev.wildware.udea.agent.query.AgentComponentIndex
import dev.wildware.udea.agent.query.AgentComponentType
import dev.wildware.udea.agent.query.PositionRef
import dev.wildware.udea.agent.query.agentComponent
import dev.wildware.udea.agent.state.ArchetypeVisitor
import dev.wildware.udea.agent.state.DigestSources
import dev.wildware.udea.agent.state.EntityCensus
import dev.wildware.udea.agent.state.LoopStatus
import dev.wildware.udea.agent.state.StateDigest
import dev.wildware.udea.agent.tools.BlueprintCatalog
import dev.wildware.udea.agent.tools.DiagToolset
import dev.wildware.udea.agent.tools.EditorLevelStore
import dev.wildware.udea.agent.tools.EditorPlay
import dev.wildware.udea.agent.tools.EditorToolset
import dev.wildware.udea.agent.tools.EngineToolModules
import dev.wildware.udea.agent.tools.EventsToolset
import dev.wildware.udea.agent.tools.LifecycleToolset
import dev.wildware.udea.agent.tools.TimeToolset
import dev.wildware.udea.agent.tools.WorldToolset
import dev.wildware.udea.core.blueprint.blueprints
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.loop.barrier
import dev.wildware.udea.core.module.CoreModule
import dev.wildware.udea.core.spatial.Animator
import dev.wildware.udea.core.spatial.AnimatorReplicator
import dev.wildware.udea.generated.MobaUdeaRegistry
import dev.wildware.udea.render.OverlayResources
import dev.wildware.udea.render.OverlaySystem
import dev.wildware.udea.render.RenderRegistry
import dev.wildware.udea.render.input.InjectedIntent
import dev.wildware.udea.render.input.IntentState
import dev.wildware.udea.render.input.UiInput
import dev.wildware.udea.render.ui.UiLayer
import dev.wildware.udea.replay.tools.ReplayToolModules
import dev.wildware.udea.replay.tools.ReplayToolset
import java.nio.file.Path
import kotlinx.io.files.Path as LevelDirectory

/**
 * `moba.agent`: the instance `game-bridge-mcp` launches.
 *
 * `gradlew.bat :moba:desktop:run -PdebugPort=7825 --console=plain` - which is exactly the command line
 * the generated `gamebridge.json` names, because `UdeaAgentPlugin` writes both from the same
 * property.
 *
 * ## Why this file is in `src/agent` and not in `src/main`
 *
 * It is the only code in `moba` that names `udea-agent-host`, and `ReleaseRules.CLASSPATH_RULE`
 * refuses a release build that resolves that module on `runtimeClasspath`. A source set of its
 * own keeps it off that classpath **and** out of the jar, which a `compileOnly` dependency would
 * not: that arrangement ships a `main` class whose first statement throws `NoClassDefFoundError`,
 * and calls it absence.
 *
 * ## Two loops, one simulation
 *
 * In [RenderMode.Headless] there is no render backend, so [AgentGameLoop] is the frame loop and
 * it pumps [AgentRuntime] itself. In the GL modes the render thread owns the cadence, so the same
 * [AgentGameLoop.pump] is handed to `Lwjgl3Backend.drive` as the frame callback. Both call the
 * identical three-step - drain the queue, advance the host, publish the digest - because handing
 * the backend `host::frame` instead (which is what a client does) would accept commands onto the
 * bridge and execute none of them.
 *
 * ## What is real here and what is not
 *
 * Real: the game, the loop, the barrier, the snapshot ring, the tool index, the HTTP surface, the
 * registry entry, and the pixels. In either GL mode [OffscreenRenderControl] - the engine's own
 * adapter, out of `udea-agent-host`'s `src/main` - joins [RenderToolset] to the live
 * `RenderPipeline`, so `render.screenshot` returns PNG bytes of the actual world and a rewind is
 * visible as a diff between two of them.
 *
 * Real, and Windowed-only: the agent activity overlay. [overlayFor] registers
 * [AgentOverlaySystem] over the same [AgentBridge] the toolsets narrate into, so a human running
 * `:moba:desktop:runClient`-style Windowed instance watches the panel while every capture taken through
 * the same process is byte-identical to one taken with the overlay off (spec 3.7).
 *
 * Real, and new: `input.*` and `render.follow_entity`. Input goes through the same
 * `IntentSource` seam a keyboard does, so the agent drives the character a player drives; and the
 * camera follows a game-supplied `PoseSource`, so following a `moba` unit genuinely moves the view
 * rather than answering `ok` and staying put.
 *
 * Still not real: `render.toggle_debug_draw`
 * flips a switch no renderer here reads; the overlay's world-space markers are never drawn,
 * because [overlayFor] has no projector to give it; and in [RenderMode.Headless] there is no
 * context at all, so every render tool correctly answers `no_render_context`.
 */
public object MobaAgent {

    /**
     * The one artifact store this process has.
     *
     * A property rather than a local, because two things now need it and they are built at
     * opposite ends of `main`: the bridge, which spills an oversized command answer into it
     * before anything renders, and the render toolset, which puts screenshots in it after the GL
     * context exists. Two stores would put a `render.screenshot` handle and a `resultRef` in
     * different directories and `GET /artifact` would resolve only one of them.
     */
    private val ARTIFACTS: AgentArtifacts =
        AgentArtifacts(Path.of("build", "udea-agent-artifacts").toAbsolutePath())

    /** Boots, binds if a port was given, and blocks. */
    @JvmStatic
    public fun main(args: Array<String>) {
        val mode = MobaLaunch.modeFromProperties(fallback = RenderMode.Offscreen)
        // Built *here*, before anything renders: see [Wiring].
        val wiring = Wiring()
        if (mode == RenderMode.Headless) {
            val host = MobaGame.host(RenderMode.Headless, extraModules = wiring.extraModules, level = MobaLaunchLevel.bytes())
            host.ctx[IntentState.KEY].source = wiring.injected
            // No GL context in Headless, so no capture surface exists and `null` is the
            // honest answer: every `render.*` tool then answers `no_render_context`.
            val session = attach(host, RenderMode.Headless, null, wiring, EditorMode.resolve())
            Runtime.getRuntime().addShutdownHook(Thread { session.close("jvm shutdown hook") })
            session.loop.run()
            session.close("the frame loop ended")
            return
        }
        runWithGl(mode, wiring, editor = EditorMode.resolve(), screen = null)
    }

    /**
     * What one agent process builds before anything renders, once.
     *
     * The bridge and the session table are built before the GL backend, because the overlay has to
     * be registered into the `RenderRegistry` before `KoolBackend.start` builds a pipeline out of
     * it - and the overlay narrates this bridge and colours by this table. Two `AgentSessions` would
     * be the quiet version of the bug: the panel would name no session at all while the host
     * interned every caller into a table nothing drew.
     */
    internal class Wiring {
        /**
         * `resultSpill` and not the default: an answer larger than the digest's result ceiling is
         * otherwise dropped from `/state` outright and the agent that asked for it never learns what
         * it said. See `AgentBridge.complete`. The store is process-wide for the same reason the
         * bridge is - both are built before anything renders.
         */
        val bridge: AgentBridge = AgentBridge(resultSpill = ARTIFACTS.textSpill())

        val sessions: AgentSessions = AgentSessions()

        /**
         * The agent's hands, built once per process. It is an ordinary `IntentSource`, so the
         * simulation cannot tell it from a keyboard - which is the whole of issue #124's claim that
         * synthesised input is indistinguishable from a human's, made structural rather than
         * argued. In `Headless` it is the *only* source there is.
         */
        val injected: InjectedIntent = InjectedIntent(MobaControls.BINDINGS.catalog)

        /**
         * The cue mirror is appended to the game's own module list rather than replacing anything
         * in it: it decorates `GameContext.cues`, and a module `context` hook is the one place a
         * decorator can see the value it decorates. See `MobaCueMirrorModule`.
         */
        val extraModules: List<dev.wildware.udea.core.module.UdeaModule> = listOf(MobaCueMirrorModule(bridge))
    }

    /**
     * An interface shown over an agent instance's world - the editor's window is the one there is -
     * and what it adds to each frame.
     */
    internal class Screen(
        /** The layer shown, which takes keys ahead of the game. The backend owns and closes it. */
        val layer: UiLayer,
        /** Called on the render thread each frame, after the loop has pumped. */
        val frame: () -> Unit,
        /** Called once the render loop has exited and the backend has closed the layer. */
        val afterExit: () -> Unit,
    )

    /**
     * The GL half of [main], and of `MobaEditor.main`: boots a Kool backend in [mode], wires every
     * toolset, and blocks until the window closes.
     *
     * @param editor register the `editor.*` tools and report `"editor":true` on `/health`.
     * @param screen builds an interface over the running session before the first frame, or `null`
     *   for an instance that shows none.
     * @param renderers registers what the caller draws beside the game's own systems, before the
     *   backend starts: the editor's 3D models (issue #243).
     */
    internal fun runWithGl(
        mode: RenderMode,
        wiring: Wiring,
        editor: Boolean,
        renderers: (RenderRegistry) -> Unit = {},
        screen: ((GameHost, MobaLaunch.Rendering, Session) -> Screen)?,
    ) {
        var shown: Screen? = null
        MobaLaunch.runWithGl(
            mode,
            overlay = overlayFor(mode, wiring.bridge, wiring.sessions),
            extraModules = wiring.extraModules,
            renderers = renderers,
        ) { host, rendering ->
            // The engine's own adapter, out of `udea-agent-host`'s `src/main`. `moba` used to
            // carry a copy of it in this source set, because a headless agent host could not name
            // `PresentationControl`; the copy is gone with the rule that forced it.
            val control = OffscreenRenderControl(rendering.presentation())
            val session = attach(host, mode, control, wiring, editor)
            val screenShown = screen?.invoke(host, rendering, session)
            shown = screenShown
            // Keyboard *and* agent, combined rather than one replacing the other: a human
            // watching a Windowed agent instance can still play. See `CompositeIntent` for why
            // there is deliberately no priority rule between the two. An interface, when there is
            // one, takes its keys first.
            MobaLaunch.wireInput(
                host,
                MobaLaunch.keyboard(rendering, screenShown?.layer ?: UiInput.NONE),
                extra = wiring.injected,
            )
            // The camera goes on the unit the agent drives, so a screenshot after an `input.*`
            // call shows the thing that moved. Real now: `CameraRig` follows a game-supplied
            // `PoseSource` rather than only a `PhysicsBody`, which is what `moba` never had.
            MobaLaunch.follow(rendering, session.player)
            // The third step of `close` in a GL mode, and the one a headless process does not
            // have: stopping `AgentGameLoop` ends a loop nothing is running here, because the
            // render thread owns the cadence and `runWithGl` is parked on `awaitExit`. Without
            // it, `close` would release the port and leave the window up - a clean close, as far
            // as the bridge could tell, over a game that is still running.
            session.shutdown.onClose("render-loop", rendering.requestExit)
            // Silent, and it still drains. An agent instance wants no sound - a play session is
            // watched through screenshots - but it must not be the one process where
            // `GameContext.cues` fills its 1024 slots and starts discarding, because the cue
            // stream is what `events.*` and `EffectSpawnSystem` read. `AudioDevice.Silent` opens
            // no device, loads no file and allocates nothing per frame, so this costs the drain
            // and nothing else. See `MobaAudio` for why the drain is not a Fleks system.
            val audio = MobaAudio.silent(host)
            audio.listenTo(session.player)
            MobaLaunch.Attachment(
                frame = { delta ->
                    session.loop.pump(delta)
                    audio.frame()
                    screenShown?.frame?.invoke()
                },
                close = {
                    audio.close()
                    session.close("the render loop ended")
                },
            )
        }
        shown?.afterExit?.invoke()
    }

    /**
     * The agent activity overlay. **Always `null` on this branch, and that is a gap, not a rule.**
     *
     * ## What it was
     *
     * `null` in any mode that must not draw one (spec 3.7), and a drawn panel in `Windowed`: the
     * session colour, the caption and the tool history, above the world, where a human watching an
     * agent work could read what it had just done. The `null` was the mode rule as far as this
     * process was concerned, and deliberately not the only guard - [MobaLaunch.runWithGl] refuses
     * a non-`Windowed` overlay outright, and `AgentOverlayView.isEnabled` is false outside
     * `Windowed` whatever it is handed.
     *
     * ## Why it draws nothing now
     *
     * `AgentOverlaySystem` was the *drawing* half and it went with LibGDX in issue #211, along
     * with `GdxOverlayKey`, the hardware key that stepped its verbosity. `udea-agent-host` still
     * has the model, the markers, the palette, the verbosity ladder and `AgentOverlayView` itself
     * - everything except something that puts pixels on a screen. Writing that is a `udea-render`
     * and `udea-agent-host` change and neither module is issue #212's; #188 owns the ComposeGL UI
     * this would be drawn with.
     *
     * ## What is actually lost, and what is not
     *
     * Nothing an agent does. The overlay never existed outside `Windowed`, `moba.agent` defaults
     * to `Offscreen`, and `FrameCapture` never reads the surface the overlay drew onto - that
     * exclusion is spec 3.7's first rule and it is structural. So every tool call, every
     * screenshot and every proof in this repository behaves exactly as before. What is lost is a
     * human sitting in front of a `Windowed` agent run and being able to see what it is doing.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun overlayFor(
        mode: RenderMode,
        bridge: AgentBridge,
        sessions: AgentSessions,
    ): ((OverlayResources) -> OverlaySystem)? = null

    /**
     * Wires every toolset, binds the HTTP surface if `-Dudea.agent.port` was passed, and seeds.
     *
     * The surface is bound **before** the first frame and the digest is published once here, so a
     * `/state` that beats the loop reads a document rather than an empty string. The registry
     * entry is written by [AgentHost] itself, after the port is bound - never before, because an
     * entry naming a port nobody claimed is worse than no entry.
     *
     * @param editor wire the `editor.*` tools and report `"editor":true` on `/health`: an instance
     *   started with `-Peditor=true`, or `MobaEditor`'s.
     */
    internal fun attach(
        host: GameHost,
        mode: RenderMode,
        control: RenderControl?,
        wiring: Wiring,
        editor: Boolean,
    ): Session {
        val bridge = wiring.bridge
        val sessions = wiring.sessions
        val injected = wiring.injected
        val timings = AgentTimings()
        val census = MobaCensus(host.world)
        val digest = StateDigest(
            bridge = bridge,
            sources = DigestSources(entities = census, loop = LoopView(host)),
            timings = timings,
        )
        val artifacts = ARTIFACTS
        // Registered here, appended to as each thing it has to stop comes into existence. The
        // tool index is built before the loop and the surface, so the toolset cannot be handed
        // either of them directly.
        val shutdown = HostShutdown()

        val position = positionAccess()
        val components = AgentComponentIndex(
            listOf(position, unitAccess(), matchAccess(), inventoryAccess(), animatorAccess(), towerAccess()),
        )
        val worldTools = WorldToolset(
            world = host.world,
            components = components,
            netIds = host.ctx[CoreModule.NET_IDS],
            bridge = bridge,
            clock = host.ctx.clock,
            catalog = BlueprintCatalog.of(host.ctx[MobaBlueprints.KEY].all),
            spawner = host.ctx.blueprints,
            // The artifact store, for the same reason `EventsToolset` is given it: twenty-seven
            // rows of `query_entities` is ~857 characters and a command result is guaranteed only
            // 256 bytes, so without a spill the whole answer is dropped and the agent that asked
            // pages it back ten calls at a time. With it, the complete document is filed and its
            // handle comes back as `resultRef` for one `GET /artifact`.
            spill = artifacts.textSpill(),
        )
        // `editor.*` only when this process was started as an editor - `-Peditor=true`, or
        // `runEditor` - because its tools write fields `world.set_component_field` refuses. A
        // normal run's `/tools` does not list them, and `/health` says which this is.
        val editorTools = if (editor) {
            EditorToolset(
                world = host.world,
                components = components,
                netIds = host.ctx[CoreModule.NET_IDS],
                sessions = sessions,
                bridge = bridge,
                clock = host.ctx.clock,
                // `moba`'s position is `Position.x`/`Position.y`, not the lowered
                // `position.x`/`position.y` the index would find on its own.
                position = PositionRef(position, PositionReplicator.FIELD_X, PositionReplicator.FIELD_Y),
                catalog = BlueprintCatalog.of(host.ctx[MobaBlueprints.KEY].all),
                spawner = host.ctx.blueprints,
                levels = EditorLevelStore(host.game.levels, LevelDirectory("build", "editor-levels")),
                // `editor.play` and `editor.stop` (issue #196): the same level encoder, and the
                // same `TimeControl` `time.*` drives.
                play = EditorPlay(host.game.levels, host.time),
            )
        } else {
            null
        }
        // `editor.screenshot` (issue #234) with the rest of `editor.*`. Its views are the editor
        // window's, which opens after this index is built, so they are bound when it does
        // (`Session.editorViews`); until then the tool answers `no_editor_window`.
        val editorViews = if (editor) EditorViewToolset(mode, artifacts) else null
        val tools = EngineToolModules
            .wireAll(
                // Every generated `ToolModule` facet on this game's registry (issue #202). None of
                // `moba`'s modules sets `udea.toolModuleService` today, so this adds no tool; it is
                // the wiring a game's own `@AgentTool` would arrive through.
                ToolIndex.builder().registry(MobaUdeaRegistry),
                *listOfNotNull(worldTools, editorTools).toTypedArray(),
                TimeToolset(host.time, host.ctx.clock, bridge),
                // The artifact store, not `TextSpill.NONE`: an event message too long for the
                // bytes a command result is guaranteed goes there and comes back through
                // `GET /artifact`, the same door a screenshot uses.
                EventsToolset(bridge, host.ctx.clock, artifacts.textSpill()),
                LifecycleToolset(bridge, shutdown),
                DiagToolset(
                    bridge = bridge,
                    clock = host.ctx.clock,
                    timings = timings,
                    census = census,
                    digest = digest,
                    barrier = host.ctx.barrier,
                ),
            )
            .module(AgentHostTools)
            .toolset(RenderToolset(mode, control, artifacts))
            .toolset(ArtifactToolset(artifacts))
            .let { builder -> if (editorViews == null) builder else builder.module(EditorViewTools).toolset(editorViews) }
            // `input.*`, over the same source a keyboard writes through. A separate module from
            // `AgentHostTools` because a `ToolModule` promises every tool in it has a receiver,
            // and a host that only wants screenshots must not be forced to wire input as well.
            .module(AgentInputTools)
            .toolset(InputToolset(injected))
            // `replay.*`: load a `.udearep`, verify it against this build, seek, step, rewind.
            // The bisect loop of issue #149. It is here rather than in `EngineToolModules`
            // because a `ReplayToolset` needs a `ReplayHost`, which knows how to build a world
            // of a specific game - nothing but this game can supply one, and no amount of
            // service discovery can invent it.
            //
            // The world these tools step is **not** the world this host is running: it is a
            // second headless `moba` built from the recording. So `world.query_entities` does
            // not see it, and every answer that matters - the tick, both hashes - is in the
            // tool result itself. See `ReplayToolset`'s KDoc for the cost of a long seek.
            .let { ReplayToolModules.wire(it, ReplayToolset(MobaReplayHost(host))) }

        // `assets.*`, over the real corpus and the real running graph. Registered here rather
        // than in `EngineToolModules` for the reason `AssetToolModule` gives: the daemon carries
        // a Kotlin script compiler, which `UDEA-MG-005` forbids on a shipped game's classpath.
        // Absent - not present and failing - when this process has no asset source tree.
        val assets = MobaAssetTools.wire(tools, host)
        val index = assets.builder.build()

        val identity = GameIdentity(MobaGame.NAME, MobaGame.VERSION)
        val agentHost = AgentHost.startIfRequested(
            bridge = bridge,
            config = { port ->
                AgentHostConfig(
                    port = port,
                    identity = identity,
                    renderMode = mode,
                    manifest = ToolManifest.of(identity, index.tools),
                    artifacts = artifacts,
                    paused = { host.time.paused },
                    // The *same* table the overlay colours from. Left to default, the host would
                    // intern every caller into a table of its own and the panel would name no
                    // session while showing that session's tool calls.
                    sessions = sessions,
                    editor = editor,
                )
            },
            // The generated per-variant flag, not `udea-agent-host`'s hand-written `true`. A
            // `-Pudea.release=true` build regenerates this as false, and the gate then refuses to
            // bind even if somebody put the module back on the classpath.
            agentAllowed = UdeaAgentBuildFlags.AGENT_ALLOWED,
        )

        val player = MobaEntry.seed(host)
        digest.publish()
        if (agentHost == null) {
            System.err.println(
                "[moba.agent] no agent surface; pass -PdebugPort=<port> (or -Dudea.agent.port). " +
                    "Running as a plain $mode instance.",
            )
        } else {
            println(
                "[moba.agent] listening on http://127.0.0.1:${agentHost.port} in $mode with " +
                    "${index.tools.size} tools",
            )
        }
        val loop = AgentGameLoop(host, AgentRuntime(bridge, index, host.world, host.ctx, digest))
        // Loop first: stopping the surface while a tool call is mid-drain would leave the caller
        // holding a closed connection to a command that did run.
        shutdown
            .onClose("frame-loop") { loop.stop() }
            .onClose("agent-host") { agentHost?.stop() }
        return Session(loop = loop, shutdown = shutdown, player = player, wiring = wiring, components = components, views = editorViews)
    }

    /** [Position], with x and y writable and `hp` not - so `field_not_writable` is reachable. */
    private fun positionAccess(): AgentComponentType = agentComponent(
        name = "Position",
        replicator = PositionReplicator,
        componentType = Position,
        agentWritableFields = setOf(PositionReplicator.FIELD_X, PositionReplicator.FIELD_Y),
    )

    /**
     * `GameUnit`, so an agent can ask the question the level exists to answer.
     *
     * With this registered, `world.query_entities with=GameUnit where team=0` is the orc count
     * from outside the process, and running it either side of a `time.step` is what shows that
     * the fight happened. Nothing here is agent-writable: a caller that could set `team` could
     * make two armies change sides mid-battle, which would make every count it then read a
     * statement about its own writes rather than about the simulation.
     */
    private fun unitAccess(): AgentComponentType = agentComponent(
        name = "GameUnit",
        replicator = GameUnitReplicator,
        componentType = GameUnit,
    )

    /**
     * `MatchState`, so the score is readable without a screenshot.
     *
     * With this registered, `world.query_entities with=MatchState` is the whole scoreboard from
     * outside the process - which match this is, who is alive on each side, who won and on which
     * tick - read off the **authoritative component** rather than off `MatchService`, which is a
     * mirror and could in principle be a tick behind it.
     *
     * Nothing here is agent-writable, and that is the point rather than an omission: a caller
     * that could set `winner` could declare itself the victor, and every later reading of the
     * match would then be a statement about that write rather than about the game.
     */
    private fun matchAccess(): AgentComponentType = agentComponent(
        name = "MatchState",
        replicator = MatchStateReplicator,
        componentType = MatchState,
    )

    /**
     * `Inventory`, so what a champion is carrying is readable without a screenshot.
     *
     * This ticket's shop has no drawn surface - item icon art is out of scope in issue #132 and
     * nothing renders an inventory yet - so without this line the whole feature is invisible from
     * outside the process and there is nothing for an agent to look at. With it,
     * `world.query_entities with=Inventory fields=Inventory` is a champion's six slots and its
     * trinket, read off the **authoritative component** rather than off a mirror, and running it
     * either side of a purchase is what shows that the purchase happened.
     *
     * Each slot reads back as the `AssetIndex` an [dev.wildware.moba.item.Inventory] stores, or
     * `-1` for empty. That is a slot in the packed graph, so `assets.list` turns it into an id -
     * the same indirection a snapshot uses, and the reason a rewind across a hot reload restores
     * the right item rather than the right *name*.
     *
     * Nothing here is agent-writable, for `matchAccess`'s reason with a sharper edge: a caller
     * that could write a slot could hand itself `item/aegis` without paying for it, and every
     * later reading of the economy would be a statement about that write rather than about the
     * game. Buying goes through `ShopService`, which charges.
     */
    private fun inventoryAccess(): AgentComponentType = agentComponent(
        name = "Inventory",
        replicator = InventoryReplicator,
        componentType = Inventory,
    )

    /**
     * `Animator`, so which clip an animated entity is playing is readable without a screenshot, and
     * so the editor can set it (issue #243): the Animation panel chooses a clip with
     * `editor.begin_edit` / `update_edit` / `commit_edit` on `Animator.current.clip` and
     * `Animator.current.length`, which name fields through this index. `editor.*` writes any field
     * it names, so nothing here needs to be agent-writable, and nothing is: a game's animation is
     * its systems' to direct, and `world.set_component_field` keeps refusing it.
     */
    private fun animatorAccess(): AgentComponentType = agentComponent(
        name = "Animator",
        replicator = AnimatorReplicator,
        componentType = Animator,
    )

    /**
     * `Tower`, so the editor's range ring can edit a tower's `attackRange` (issue #236), and an agent
     * can read what every tower is doing.
     *
     * Nothing here is agent-writable, for `unitAccess`'s reason: `world.set_component_field` is a
     * running game's tool, and a caller that could set a tower's target or its cooldown would make
     * every later reading of the lane a statement about that write. The editor's `editor.*` edits are
     * authoring, filed with an undo, and do not ask this.
     */
    private fun towerAccess(): AgentComponentType = agentComponent(
        name = "Tower",
        replicator = TowerReplicator,
        componentType = Tower,
    )

    /**
     * The loop and the teardown, so one `close` runs the same steps whoever asked for it.
     *
     * The steps used to be two lines in this class's `close`, which meant the `close` **tool**
     * could not run them: it lives in `udea-agent` and cannot name anything here. Moving them
     * into a [HostShutdown] the toolset was constructed with is what makes the tool and the JVM
     * shutdown hook the same teardown rather than two that have to be kept in step - and
     * `HostShutdown` runs once whichever gets there first.
     */
    internal class Session(
        val loop: AgentGameLoop,
        val shutdown: HostShutdown,
        /** The unit an agent's `input.*` calls steer, and the one the camera follows. */
        val player: dev.wildware.udea.core.identity.NetId,
        /** The bridge and session table the toolsets were wired over. */
        val wiring: Wiring,
        /** Every component the tools can address, by name: what the editor's gizmos name theirs by. */
        val components: AgentComponentIndex,
        /** `editor.screenshot`'s toolset, on an editor instance; `null` on any other. */
        private val views: EditorViewToolset? = null,
    ) {
        fun close(reason: String) {
            shutdown.shutdown(reason)
        }

        /** Hands the editor window's two views to `editor.screenshot`, when the window opens them. */
        fun editorViews(scene: WorldViewport, game: WorldViewport) {
            checkNotNull(views) { "this instance was not attached as an editor, so it has no editor.screenshot" }
                .bind(WorldViewportControl(scene, game))
        }
    }
}

/**
 * Counts what Fleks already counts.
 *
 * [EntityCensus]'s contract is that counts are maintained incrementally and never by walking the
 * world, and `World.numEntities` honours that - Fleks keeps it as a field. The archetype
 * breakdown is **not** honest bookkeeping: `moba` has one blueprint, so every entity is reported
 * under `grunt`. A game with several must count them at its own spawn and despawn sites.
 */
private class MobaCensus(private val world: World) : EntityCensus {

    private val units: Family = world.family { all(GameUnit) }

    override val entityCount: Int get() = world.numEntities

    /**
     * One row per side, plus what is left over.
     *
     * This **does** walk a family, which [EntityCensus] asks implementations not to do, and the
     * trade is stated rather than hidden: it is 27 reads of one component on a digest publish and
     * not on a tick, and the alternative - three counters maintained at the spawn site and at
     * every death - is bookkeeping that goes wrong silently the first time a unit is removed by
     * something other than `UnitDeathSystem`. A count that is late is a bug; a count that is
     * wrong is worse. When a spawn/despawn seam exists that every removal goes through, the
     * counters move there.
     */
    override fun forEachArchetype(visitor: ArchetypeVisitor) {
        val counts = IntArray(TEAM_COUNT)
        val entities = units.entities
        var index = 0
        with(world) {
            while (index < entities.size) {
                val team = entities[index][GameUnit].team
                index++
                if (team in counts.indices) counts[team]++
            }
        }
        var team = 0
        while (team < counts.size) {
            visitor.visit(Team.nameOf(team), counts[team])
            team++
        }
        val other = entityCount - counts.sum()
        if (other > 0) visitor.visit("unaligned", other)
    }

    private companion object {

        /** `Team.ORC`, `Team.SOLDIER`, `Team.UNDEAD`. */
        const val TEAM_COUNT: Int = 3
    }
}

/** The digest's window onto the real loop, so `/state.paused` is the loop's own answer. */
private class LoopView(private val host: GameHost) : LoopStatus {
    override val paused: Boolean get() = host.loop.paused
    override val timeScale: Float get() = host.loop.timeScale
    override val fps: Float get() = 0f
}
