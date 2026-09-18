package dev.wildware.udea.render.overlay

/**
 * Whether a hardware key is physically down **right now**, read from the device.
 *
 * ## Why this is its own port, and the whole of issue #161
 *
 * The agent can synthesise input. That is a deliberate feature - an agent has to be able to
 * drive the game - and it is implemented by injecting into whatever the game reads intents from.
 * If the overlay's hotkey were read from *that*, an agent replaying a recorded input stream, or
 * fuzzing, or simply pressing the wrong key, could **switch the human's overlay off, or on, in
 * the middle of a capture**. On means the human sees nothing they expected; on mid-capture is
 * worse, because the agent has then changed a thing it is not supposed to be able to observe and
 * a human's understanding of the session silently diverges from it.
 *
 * So this reads the physical device, upstream of any injected intent source, and there is
 * deliberately no way to write to it: the interface has one method and it returns a state rather
 * than consuming an event queue.
 *
 * ## No production implementation yet
 *
 * The LibGDX one - `GdxOverlayKey`, `Gdx.input.isKeyPressed` - went with LibGDX in issue #211.
 * Its Kool replacement needs the same input plumbing issue #224 is building for Kool input to
 * intents, so it is not written here: nothing in the tree constructs one today (the panel this
 * exists for has never been wired into a real composition root either, per
 * `dev.wildware.udea.agent.host.overlay.AgentOverlayView`'s own history), and [NEVER] - what
 * `Headless` and `Offscreen` already use - is the honest, reversible default until #224 lands a
 * real one.
 */
public fun interface HardwareKeyState {

    /** Whether the key is down at this instant. */
    public fun isOverlayKeyDown(): Boolean

    public companion object {
        /** Never pressed. What a headless or offscreen instance is wired with. */
        public val NEVER: HardwareKeyState = HardwareKeyState { false }
    }
}
