package dev.wildware.moba.audio

import dev.wildware.moba.MobaControls
import dev.wildware.moba.MobaGame
import dev.wildware.moba.ability.MobaCues
import dev.wildware.moba.entry.MobaEntry
import dev.wildware.udea.core.CueId
import dev.wildware.udea.core.CueQueue
import dev.wildware.udea.core.innermost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.render.input.InjectedIntent

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
 *   per count against a real OpenAL source; `unbound` is cues with no recording, which since the
 *   two cue id spaces were separated is the animation notifies and nothing else (see
 *   [MobaCueSounds] for why none of those is bound).
 * - the closing `Sound.play calls per cue` line - the per-*kind* count. All nine of this game's
 *   authored cues are bound now; a zero in that line is a cue nothing emits, not a cue nothing
 *   plays.
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

    /**
     * Frames between taps of the player special, or `0` to leave the controls alone.
     *
     * `-Dudea.audio.probe.special=0` for a run a human is playing rather than watching.
     */
    public const val SPECIAL_PROPERTY: String = "udea.audio.probe.special"

    /** A second. Longer than the spin cooldown is not, so most taps are refused, as a mash is. */
    public const val DEFAULT_SPECIAL_EVERY: Int = 60

    /** Frames between report lines. About one a second. */
    private const val REPORT_EVERY: Int = 60

    @JvmStatic
    public fun main(args: Array<String>) {
        val mode = MobaEntry.modeFromProperties(fallback = RenderMode.Windowed)
        val frameBudget = System.getProperty(FRAMES_PROPERTY)?.trim()?.toIntOrNull() ?: DEFAULT_FRAMES
        require(frameBudget > 0) { "-D$FRAMES_PROPERTY must be positive, was $frameBudget" }
        val specialEvery =
            System.getProperty(SPECIAL_PROPERTY)?.trim()?.toIntOrNull() ?: DEFAULT_SPECIAL_EVERY
        require(specialEvery >= 0) { "-D$SPECIAL_PROPERTY must not be negative, was $specialEvery" }
        println("[moba.audio] ${MobaGame.NAME} ${MobaGame.VERSION} $mode, $frameBudget frame(s)")

        MobaEntry.runWithGl(mode) { host, rendering ->
            val player = MobaEntry.seed(host)
            MobaEntry.follow(rendering, player)
            // The keyboard, plus a hand on the special. `wireInput` composes the two rather than
            // letting one replace the other, so a human at the window still drives the player and
            // the probe still fires the one cue no AI unit can: `ability/orc_elite_spin` is the
            // player unit's slot 1 and nothing else on the field has it, so a run that never
            // touches the controls reports `spin=0` and looks like a routing bug.
            val keys = InjectedIntent(MobaControls.BINDINGS.catalog)
            MobaEntry.wireInput(host, rendering, keys)
            // On the render thread: `Gdx.audio` has the same thread affinity every `Gdx` static
            // has, and this is the call that opens twenty-four OpenAL buffers.
            var built: MobaAudio? = null
            rendering.onRenderThread { built = MobaAudio.forHost(host) }
            val audio = checkNotNull(built) { "MobaAudio was not built on the render thread" }
            audio.listenTo(player)

            // `innermost()` and not a bare cast: a debug build may wrap the context's sink in a
            // `CueSinkDecorator` so an observer can see cues on the way past, and a cast through
            // one throws. See `MobaAudio.of`, which had exactly this fault and was found by an
            // agent instance refusing to boot.
            val queue = host.ctx.cues.innermost() as CueQueue
            println(
                "[moba.audio] ${audio.sounds.bindings.size} cue(s) bound, " +
                    "silent: ${audio.sounds.silent.ifEmpty { listOf("none") }}",
            )

            var frames = 0
            var peakQueue = 0
            MobaEntry.Attachment(
                frame = { delta ->
                    if (specialEvery > 0 && frames % specialEvery == 0) {
                        keys.tap(MobaControls.ATTACK_2_ACTION)
                    }
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
                    println("[moba.audio] Sound.play calls per cue: ${playsPerCue(audio)}")
                    audio.close()
                },
            )
        }
    }

    /**
     * Device plays per authored cue, in [MobaCues] order, as `name=count`.
     *
     * The line the wave is judged on. An aggregate `played` cannot tell a run where every cue
     * fired from one where the deaths carried it, and that is exactly the difference the id
     * collision made: the game bound four of its nine cues and the total looked healthy. A cue at
     * `0` here is a cue that is authored, routed, loaded - and never audible, which is a routing
     * or an emission bug and not a mixing one.
     */
    private fun playsPerCue(audio: MobaAudio): String =
        MobaCues.ids.joinToString(" ") { id ->
            "${MobaCues.nameOf(id)}=${audio.audio.playsOf(CueId(id))}"
        }
}
