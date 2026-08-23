package dev.wildware.moba.entry

import dev.wildware.moba.GruntBlueprint
import dev.wildware.moba.MobaGame
import dev.wildware.moba.MobaScene
import dev.wildware.udea.core.blueprint.blueprints
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.module.UdeaGameDef
import dev.wildware.udea.render.RenderPipeline
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
     * What `moba` draws: one quad per unit, at that unit's position.
     *
     * Delegates to [MobaScene], which carries the reasoning. The short version is that this
     * returned an **empty** registry until the Phase 1 demo was driven end to end, and an empty
     * registry makes that demo's image diff structurally incapable of showing anything: every
     * capture is the same cleared framebuffer whatever the simulation is doing.
     */
    public fun scene(definition: UdeaGameDef): MobaScene = MobaScene.build(definition)

    /**
     * Puts one entity in the world and runs the tick that applies it.
     *
     * Shared by all three entry points so that a fresh instance is never an empty world: an
     * agent's first `get_state` on an empty world and its first `get_state` on a broken spawn
     * path look identical, and one of those is a bug. A spawn goes through the `SimBarrier`, so
     * the tick is what makes the entity exist rather than merely be requested.
     */
    public fun seed(host: GameHost) {
        host.ctx.blueprints.spawn(GruntBlueprint)
        host.run(1)
    }

    /**
     * Boots a GL backend in [mode], hands it the host, and blocks until the window closes.
     *
     * The two-step - construct the backend, build the host from it, *then* `drive` - is
     * [Lwjgl3Backend]'s, and is forced by a real cycle: a `GameHost` builds its presentation
     * inside its own constructor, so the presentation cannot be handed a host that does not yet
     * exist.
     *
     * @param attach runs once the host exists and before frames start flowing, and returns what
     *   drives a frame. That return value is the whole difference between a player's client and
     *   an agent's instance: a client hands back `host::frame`, and an agent hands back
     *   `AgentGameLoop::pump`, which drains the command queue on either side of the same call.
     *   A `GameHost` that a render backend drives directly can never execute an agent command,
     *   because nothing calls `AgentRuntime.beforeFrame`.
     */
    public fun runWithGl(mode: RenderMode, attach: (GameHost, Rendering) -> Attachment) {
        require(mode != RenderMode.Headless) { "RenderMode.Headless has no GL backend" }
        // The definition is built *here* rather than inside `MobaGame.host`, because the scene
        // needs it before the backend exists and the backend needs the scene: `CameraRig` resolves
        // a followed entity through the definition's `NetIdIndex`, and `Lwjgl3Backend.start` takes
        // a registry that is already complete. Three things in a fixed order, and the order is
        // forced rather than chosen: definition, scene, backend, host.
        val definition = MobaGame.definition()
        val scene = scene(definition)
        val backend = Lwjgl3Backend.start(mode, windowConfig(), scene.registry)
        var attachment: Attachment? = null
        try {
            val host = GameHost(mode, definition, backend)
            val pipeline = checkNotNull(backend.pipeline) {
                "GameHost built no presentation in $mode, so nothing can be captured"
            }
            val attached = attach(host, Rendering(scene, pipeline))
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
    ) {
        /** The control surface, for a caller that has an agent toolset to wire to it. */
        public fun presentation(): PresentationControl = scene.presentation(pipeline)
    }

    /** What [runWithGl]'s caller contributes: a frame driver, and its teardown. */
    public class Attachment(
        /** Called by the render thread once per frame with the wall delta in seconds. */
        public val frame: (Float) -> Unit,
        /** Run after the render loop exits and before the context is destroyed. */
        public val close: () -> Unit = {},
    )
}
