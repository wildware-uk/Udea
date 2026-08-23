package dev.wildware.udea.render.draw

/**
 * The one switch every debug renderer reads, and the thing `render.toggle_debug_draw` flips.
 *
 * ## Why a shared object rather than a flag per system
 *
 * A game draws debug information from several places — collision shapes here, paths there,
 * spawn markers somewhere else — and an agent asking for "the debug overlay" means all of it.
 * A boolean per renderer would make the tool's answer depend on which renderers the host
 * happened to hand it, so `toggle_debug_draw` would report `true` while half the debug drawing
 * stayed off. One object, passed to every debug renderer at registration, cannot drift.
 *
 * ## Why the field is volatile
 *
 * It is written by the agent's tool call and read by the renderer. On an `Offscreen` or
 * `Windowed` host those are usually the same thread, but a host whose agent loop runs on a
 * thread of its own is a legitimate arrangement, and a non-volatile flag read in a hot render
 * loop is exactly the field a JIT hoists out of the loop — so the toggle would appear to do
 * nothing until something unrelated invalidated the compilation. There is no ordering here to
 * get wrong beyond the single value, so a volatile is the whole of it.
 */
public class DebugDraw(
    /** Whether debug renderers draw. `true` by default: a debug system nobody switched on is off. */
    enabled: Boolean = true,
) {

    /** Whether debug renderers draw this frame. Safe to read and write from any thread. */
    @Volatile
    public var enabled: Boolean = enabled

    /**
     * Flips the switch and reports the new state.
     *
     * @param requested `true` or `false` to set it outright, `null` to invert it. The tri-state
     *   is what lets `render.toggle_debug_draw` be called with no argument at all, which is what
     *   an agent that just wants to see the other version does.
     */
    public fun set(requested: Boolean?): Boolean {
        val next = requested ?: !enabled
        enabled = next
        return next
    }

    override fun toString(): String = "DebugDraw(enabled=$enabled)"
}
