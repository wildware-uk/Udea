package dev.wildware.udea.core.host

/**
 * How a [GameHost] presents — and, more importantly, whether it presents at all.
 *
 * ## Why this enum exists
 *
 * "Headless" meant two incompatible things across the design analyses (spec 3.5): the core
 * analysis meant *no `RenderSystem` and metadata-only textures*, the agent analysis meant
 * *offscreen FrameBuffer screenshots* — and you cannot render to a framebuffer object without
 * a GL context. Settling it as one boolean would have preserved the ambiguity. Three named
 * modes cannot be ambiguous, and every one of them runs the **identical** `Simulation`: the
 * only difference is whether a `Presentation` exists.
 *
 * | Mode | GL context | Window | Screenshots | Used by |
 * |---|---|---|---|---|
 * | [Headless] | none | none | typed `no_render_context` | dedicated server, CI, harness, fast-forward |
 * | [Offscreen] | real LWJGL3 | hidden | full | the agent, by default |
 * | [Windowed] | real LWJGL3 | visible | full | the player |
 *
 * `/health` reports the mode, so an agent knows which toolsets are live before it calls one.
 *
 * ## Where the backends live
 *
 * `udea-core` implements [Headless] and nothing else. The [Offscreen] and [Windowed] LWJGL3
 * contexts belong to `udea-render`, because this module has no GL on its compile classpath and
 * would fail its own `udeaVerifyHeadless` gate for naming one. A host in either of those modes
 * is handed its `Presentation` by that module; the host itself is the same class.
 */
public enum class RenderMode {

    /** No GL context, no window, no `Presentation`. Runs as fast as the CPU allows. */
    Headless,

    /** A real GL context behind a hidden window. Captures work; nobody sees them live. */
    Offscreen,

    /** A real GL context in a visible window. The only mode the agent overlay draws in. */
    Windowed,
    ;

    /**
     * Whether a frame can be captured at all in this mode.
     *
     * The one predicate worth deriving: it is what separates "the screenshot tool is live"
     * from "the screenshot tool returns [RenderUnavailable.NoRenderContext]".
     *
     * An exhaustive `when` with no `else`, which is what makes the sentence above true. Written
     * as `this != Headless` it does the opposite of what it claims: a fourth constant would
     * compile, silently report `true`, and send `GameHost.screenshot` past its
     * [RenderUnavailable.NoRenderContext] branch into a backend factory nobody wired for it.
     * Like this, adding a constant fails to compile here until somebody answers the question.
     */
    public val hasRenderContext: Boolean
        get() = when (this) {
            Headless -> false
            Offscreen, Windowed -> true
        }
}
