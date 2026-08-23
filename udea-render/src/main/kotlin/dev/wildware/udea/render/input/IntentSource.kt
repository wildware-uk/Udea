package dev.wildware.udea.render.input

/**
 * Whatever produces one tick's [Intent]. **The seam an agent, a replay and a test all use.**
 *
 * There are exactly three shipped implementations and they are interchangeable by construction:
 * [DeviceIntent] reads a keyboard and a gamepad, [InjectedIntent] is written by the agent's
 * `input.*` tools, and [NONE] is what a dedicated server has. The simulation cannot tell which
 * one it is running against, which is the property that makes an agent's synthesised input
 * indistinguishable from a human's - the whole point of issue #124's note.
 *
 * ## The contract
 *
 * [sample] is called **exactly once per simulation tick**, from the simulation thread, at
 * `SimPhase.Intent`. It is handed an [Intent] that has already been cleared, and it must write
 * the state for this tick and return. It must not block, and it must not allocate: it is on the
 * per-tick path.
 */
public fun interface IntentSource {

    /** Writes this tick's input into [into], which arrives cleared. */
    public fun sample(into: Intent)

    public companion object {

        /**
         * No input, ever. What a dedicated server and a replay-less headless host use.
         *
         * Not a null check at the call site: a `null` source would make "is input wired" a
         * question every reader has to ask, and a game whose input silently stopped working
         * looks the same either way. This one is explicit and costs a virtual call per tick.
         */
        public val NONE: IntentSource = IntentSource { }
    }
}
