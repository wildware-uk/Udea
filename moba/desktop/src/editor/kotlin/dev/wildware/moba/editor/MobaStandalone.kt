package dev.wildware.moba.editor

import dev.wildware.moba.agent.MobaAgent
import dev.wildware.moba.entry.MobaLaunch
import dev.wildware.moba.entry.MobaLaunchLevel
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.editor.StandaloneLauncher
import java.io.File
import java.nio.file.Path

/**
 * Play standalone for `moba` (issue #196): a new JVM running what `:moba:desktop:run -Plevel=<file>`
 * runs - [MobaAgent]'s `main`, with `-Dmoba.level` naming the file - in a window of its own.
 *
 * ## A JVM, not a Gradle build
 *
 * The issue names `:moba:desktop:run -Plevel=<path>`. That task is a `JavaExec` whose whole effect
 * is a JVM started on the agent source set's runtime classpath with `-Dmoba.level` set; this starts
 * the same main class with the same property, directly. Going through Gradle from inside the editor
 * would start a second Gradle client (and, with the editor's own build holding the first daemon,
 * a second daemon) and wait out a configuration pass before the game appeared. The classpath is the
 * editor's own, which is the agent runtime classpath plus `udea-editor`; nothing on the game's path
 * loads an editor class.
 *
 * The game opens [RenderMode.Windowed] whatever the editor was started in - it is for a person to
 * play - and is a normal run: no `editor.*` tools, and no agent port, so it cannot collide with the
 * editor's. Its output goes to a `.log` beside the level file rather than into the editor's console.
 */
internal class MobaStandalone(
    /** Where the child's `java` is: the editor's own. */
    private val java: String = ProcessHandle.current().info().command().orElse("java"),
    /** The child's classpath: the editor's own. */
    private val classpath: String = System.getProperty("java.class.path"),
) : StandaloneLauncher {

    /** The command line [launch] runs for [level]. */
    fun command(level: Path): List<String> = listOf(
        java,
        "-cp",
        classpath,
        "-D${MobaLaunchLevel.PROPERTY}=${level.toAbsolutePath()}",
        "-D${MobaLaunch.RENDER_MODE_PROPERTY}=${RenderMode.Windowed.name}",
        MAIN_CLASS,
    )

    override fun launch(level: Path) {
        val log = File(level.toAbsolutePath().toString().removeSuffix(".udealevel") + ".log")
        val process = ProcessBuilder(command(level))
            .redirectErrorStream(true)
            .redirectOutput(log)
            .start()
        println("moba.editor: Play standalone started pid ${process.pid()} on $level, output in $log")
    }

    override fun toString(): String = "MobaStandalone($MAIN_CLASS)"

    internal companion object {
        /** `:moba:desktop:run`'s main class. */
        val MAIN_CLASS: String = MobaAgent::class.java.name
    }
}
