package dev.wildware.moba.android

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import dev.wildware.moba.MobaGame
import dev.wildware.moba.entry.MobaEntry
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import kotlin.concurrent.thread

/**
 * Boots the real `moba` simulation on the phone and reports what it built.
 *
 * ## Why it does not draw, and why that is stated here rather than hidden
 *
 * `udea-render` has a Kool backend for the JVM and none for Android (its `jvmMain` holds
 * `KoolBackend`; there is no `androidMain`). A `GameHost` in [RenderMode.Offscreen] or
 * [RenderMode.Windowed] needs a `PresentationFactory`, and the only honest way for this activity to
 * supply one is for `udea-render` to grow an Android backend - which is that module's ticket, not
 * this one's, and writing a second backend here would put a renderer outside the one module spec
 * section 3 allows to have one.
 *
 * So this runs [RenderMode.Headless], which is a real mode rather than a stub: the same
 * `MobaGame.definition()`, the same scene swap through the `SimBarrier`, the same ticks, over the
 * same `.udeapak` the desktop build loads - here read out of this APK's own resources. What it
 * proves is the thing nothing else in the build can: that the game module does not merely *compile*
 * for Android but boots on it, with its assets, its registry and its level.
 *
 * ## Why a thread and not the main looper
 *
 * Seeding the level decodes the bundle and runs a tick, and Android kills an application that does
 * that on the UI thread. One thread, once, at start; there is no loop, because there is nothing to
 * present between ticks yet.
 */
public class MobaActivity : Activity() {

    /** How many ticks to run past the scene swap, so the report describes a world that has moved. */
    private val ticksToRun: Int = TICKS_TO_RUN

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view = TextView(this)
        view.text = BOOTING
        setContentView(view)

        thread(name = "moba-boot") {
            val report = runCatching { boot() }
                .fold(onSuccess = { it }, onFailure = { "moba failed to boot: $it" })
            runOnUiThread { view.text = report }
        }
    }

    /**
     * Builds the game, loads `level/test_level`, runs [ticksToRun] ticks and describes the result.
     *
     * Every line of this is the desktop server's boot sequence with the window taken off it:
     * `MobaEntry.seed` is the same function `MobaServer` and the agent's instance call.
     */
    private fun boot(): String {
        val host = GameHost(RenderMode.Headless, MobaGame.definition())
        val player = MobaEntry.seed(host)
        host.run(ticksToRun)
        return buildString {
            append("moba booted on Android\n\n")
            append("mode: ").append(host.mode).append('\n')
            append("tick: ").append(host.ctx.clock.tick).append('\n')
            append("entities: ").append(host.world.numEntities).append('\n')
            append("player: ").append(player).append('\n')
            append('\n')
            append(NO_RENDERER)
        }
    }

    private companion object {

        /** Shown until the boot thread has something to say. */
        const val BOOTING: String = "booting moba…"

        /** Enough ticks for the wave timers and the unit brains to have done something. */
        const val TICKS_TO_RUN: Int = 120

        /**
         * The one thing a person holding the phone would otherwise have to guess at.
         *
         * Written on the screen rather than only in this file's KDoc: an APK that shows numbers and
         * no game looks broken, and "it is not drawing yet, and here is why" is the difference
         * between a known state of the port and a bug report.
         */
        const val NO_RENDERER: String =
            "Nothing is drawn yet: udea-render's Kool backend is JVM-only, so this build runs the " +
                "simulation headless. The renderer is the next Android ticket."
    }
}
