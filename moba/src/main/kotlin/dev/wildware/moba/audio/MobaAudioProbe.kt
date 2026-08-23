package dev.wildware.moba.audio

import dev.wildware.moba.MobaGame
import dev.wildware.moba.entry.MobaEntry
import dev.wildware.udea.core.CueQueue
import dev.wildware.udea.core.host.RenderMode

/**
 * A windowed `moba` with its audio wired, so that "a melee hit is audible" is a thing you can run.
 *
 * ## Why this exists beside `MobaClient` rather than inside it
 *
 * One line. `MobaClient` returns `MobaEntry.Attachment(frame = host::frame)`; an audible client
 * returns a frame that calls `host.frame(dt)` and then [MobaAudio.frame]. That line belongs in
 * `MobaClient`, which this wave does not own, so it is written here where it can be run and
 * reviewed, and the report asks for it to be moved. Everything else in this file is instrumentation
 * that a player's client would not want: the counters, the tick budget and the automatic exit.
 *
 * ## What it prints, and why those numbers
 *
 * - `queue` - `CueQueue.size` sampled *before* the drain. On the shipped build this pins at
 *   `CueQueue.DEFAULT_CAPACITY` within a couple of seconds and `dropped` climbs for the rest of the
 *   run. With the drain in place it stays at one frame's worth.
 * - `dropped` - `CueQueue.droppedCount`, the cues the queue threw away because nobody emptied it.
 *   Any non-zero value here means the drain is not keeping up; zero is the claim.
 * - `played` / `unbound` / `suppressed` - the mixer's own ledger. `played` climbing is a `Sound.play`
 *   per count against a real OpenAL source; `unbound` is cues with no recording, which is expected
 *   and large (see [MobaCueSounds] for the cue-id collision that keeps four of them silent).
 *
 * ## Running it
 *
 * `./gradlew :moba:runAudio`, or `-Dudea.render.mode=Offscreen` for a hidden window on a machine
 * you do not want a window on. `-Dudea.audio.probe.frames=N` bounds the run; it exits on its own so
 * an agent session does not leave a window open.
 */
public object MobaAudioProbe {

    /** How many frames to run before asking the window to close. */
    public const val FRAMES_PROPERTY: String = "udea.audio.probe.frames"

    /** Twenty seconds at 60Hz - past the first death at tick 70 and well into the melee. */
    public const val DEFAULT_FRAMES: Int = 1200

    /** Frames between report lines. About one a second. */
    private const val REPORT_EVERY: Int = 60

    @JvmStatic
    public fun main(args: Array<String>) {
        val mode = MobaEntry.modeFromProperties(fallback = RenderMode.Windowed)
        val frameBudget = System.getProperty(FRAMES_PROPERTY)?.trim()?.toIntOrNull() ?: DEFAULT_FRAMES
        require(frameBudget > 0) { "-D$FRAMES_PROPERTY must be positive, was $frameBudget" }
        println("[moba.audio] ${MobaGame.NAME} ${MobaGame.VERSION} $mode, $frameBudget frame(s)")

        MobaEntry.runWithGl(mode) { host, rendering ->
            val player = MobaEntry.seed(host)
            MobaEntry.follow(rendering, player)
            // On the render thread: `Gdx.audio` has the same thread affinity every `Gdx` static
            // has, and this is the call that opens twenty-four OpenAL buffers.
            var built: MobaAudio? = null
            rendering.onRenderThread { built = MobaAudio.forHost(host) }
            val audio = checkNotNull(built) { "MobaAudio was not built on the render thread" }
            audio.listenTo(player)

            val queue = host.ctx.cues as CueQueue
            println(
                "[moba.audio] ${audio.sounds.bindings.size} cue(s) bound, " +
                    "silent for id collisions: ${audio.sounds.ambiguous}",
            )

            var frames = 0
            var peakQueue = 0
            MobaEntry.Attachment(
                frame = { delta ->
                    host.frame(delta)
                    peakQueue = maxOf(peakQueue, queue.size)
                    val depthBeforeDrain = queue.size
                    audio.frame()
                    frames++
                    if (frames % REPORT_EVERY == 0) {
                        println(
                            "[moba.audio] frame $frames tick ${host.tick.value} " +
                                "queue=$depthBeforeDrain peak=$peakQueue dropped=${queue.droppedCount} " +
                                "drained=${audio.audio.drained} played=${audio.audio.played} " +
                                "unbound=${audio.audio.unbound} suppressed=${audio.audio.suppressed}",
                        )
                    }
                    if (frames >= frameBudget) rendering.requestExit()
                },
                close = {
                    println(
                        "[moba.audio] done: $frames frame(s), peak queue depth $peakQueue, " +
                            "dropped ${queue.droppedCount}, played ${audio.audio.played}",
                    )
                    audio.close()
                },
            )
        }
    }
}
