package dev.wildware.udea.render.backend

import java.nio.file.Path

/**
 * A launch check's request to a running game: after [frames] frames, write the captured frame to
 * [png], then close the window - at once, or once [stopFile] exists.
 *
 * It is how the `windows-launch` CI job proves a launcher *draws* and *exits* rather than only
 * starting a JVM: the job launches the game exactly as a developer would, reads the PNG back, and
 * waits for the process to end by itself. Closing the window is the path a player takes to quit, so
 * the game's own shutdown runs, not a kill.
 *
 * ## Why the environment
 *
 * Every launcher is a Gradle `JavaExec`, which forks the game's JVM and passes Gradle's environment
 * through to it, while a `-D` on the Gradle command line stops at Gradle's own JVM. So an
 * environment variable reaches every launcher in this repository - and a game's in its own
 * repository - with no build script forwarding it. Nothing is asked for unless [PNG_VARIABLE] is
 * set, so an ordinary run is untouched.
 *
 * ## The stop file
 *
 * A host's window has to stay open until the client that joins it has connected, which a frame
 * count cannot promise on a slow software rasteriser. With [STOP_VARIABLE] set, the probe writes
 * its frame and then waits for that file to appear before it closes the window, so the check - not
 * a clock - says when each process may go.
 */
internal class LaunchProbe(
    /** Where the captured frame is written, as PNG. */
    val png: Path,
    /** Frames drawn before the capture is taken. Positive. */
    val frames: Int,
    /** Closes the window when this file exists; `null` closes it straight after the capture. */
    val stopFile: Path?,
) {

    init {
        require(frames > 0) { "a launch probe needs a positive frame count, was $frames" }
    }

    override fun toString(): String = "LaunchProbe($png after $frames frames, stop=${stopFile ?: "at once"})"

    companion object {
        /** Names the PNG to write. Unset or blank, no probe runs. */
        const val PNG_VARIABLE: String = "UDEA_LAUNCH_PNG"

        /** Frames to draw first; [DEFAULT_FRAMES] when unset. */
        const val FRAMES_VARIABLE: String = "UDEA_LAUNCH_FRAMES"

        /** A file whose appearance closes the window; unset closes it after the capture. */
        const val STOP_VARIABLE: String = "UDEA_LAUNCH_STOP_FILE"

        /** Two seconds at the default rate: past the first texture uploads of a cold start. */
        const val DEFAULT_FRAMES: Int = 120

        /**
         * The probe [env] asks for, or `null` when it names no PNG.
         *
         * @throws IllegalArgumentException when [FRAMES_VARIABLE] is not a positive whole number.
         *   Refused rather than defaulted: a check that asked for 5 frames and silently got 120 is
         *   a check measuring something other than what it says.
         */
        fun fromEnvironment(env: (String) -> String?): LaunchProbe? {
            val png = env(PNG_VARIABLE)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val rawFrames = env(FRAMES_VARIABLE)?.trim()?.takeIf { it.isNotEmpty() }
            val frames = if (rawFrames == null) {
                DEFAULT_FRAMES
            } else {
                rawFrames.toIntOrNull()?.takeIf { it > 0 }
                    ?: throw IllegalArgumentException("$FRAMES_VARIABLE=$rawFrames is not a positive whole number")
            }
            val stop = env(STOP_VARIABLE)?.trim()?.takeIf { it.isNotEmpty() }
            return LaunchProbe(Path.of(png), frames, stop?.let { Path.of(it) })
        }
    }
}

/**
 * What the driver said it is, read with `glGetString` on the render thread when the context came up.
 *
 * Printed once per process, because "which OpenGL did this machine hand us" is the first question
 * about any launch that draws wrong - and on a CI runner with a software driver it is the positive
 * control that the driver installed is the one answering.
 */
internal class GlInfo(
    /** `GL_RENDERER`: `llvmpipe (LLVM ...)` for Mesa's software rasteriser, a GPU's name otherwise. */
    val renderer: String,
    /** `GL_VERSION`, with the vendor's own suffix. */
    val version: String,
) {
    override fun toString(): String = "GL_RENDERER=$renderer; GL_VERSION=$version"
}
