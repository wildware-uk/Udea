package dev.wildware.moba.entry

import dev.wildware.moba.level.MobaLevel
import java.io.File

/**
 * Which level a desktop launcher boots: `-Plevel=<path>` on the Gradle run tasks (issue #192).
 *
 * `moba/desktop/build.gradle.kts` resolves the path against the repository root, where
 * `./gradlew` is typed, and forwards it to every `JavaExec` in this project as `-D`[PROPERTY],
 * because a forked JVM does not see a Gradle property. With nothing named, the launcher plays the
 * game's bundled test level, [MobaLevel.bundledBytes].
 *
 * Read here, in the launcher, and not in `:moba:game`: the game takes its level as an argument and
 * reads no system property, so where the choice is made is a fact about the launcher.
 */
public object MobaLaunchLevel {

    /**
     * The system property naming a level file. Unset or blank means the bundled test level. Public for
     * the editor's Play standalone, which starts a game on a level file by setting it.
     */
    public const val PROPERTY: String = "moba.level"

    /**
     * The launch level's bytes: the file [named] names, or the bundled test level.
     *
     * @param named a path to a `.udealevel`; `null` or blank means the bundled default.
     * @throws IllegalArgumentException naming the path when [named] is not a file. A typo on the
     *   launch line must not quietly boot the default level instead.
     */
    public fun bytes(named: String? = System.getProperty(PROPERTY)): ByteArray {
        val path = named?.trim().orEmpty()
        if (path.isEmpty()) return MobaLevel.bundledBytes()
        val file = File(path)
        require(file.isFile) { "-D$PROPERTY names $path, which is not a file (resolved to ${file.absolutePath})" }
        return file.readBytes()
    }
}
