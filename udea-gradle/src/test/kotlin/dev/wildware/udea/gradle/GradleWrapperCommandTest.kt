package dev.wildware.udea.gradle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The wrapper spelling, tested as a decision rather than as whatever this machine happens to be.
 *
 * A test that asserted `GradleWrapperCommand.current()` would pass on both platforms while
 * checking only one of the two branches, and the branch it skipped is the one that produced a
 * `launch_instance` failure reading `'gradlew.bat' is not recognized`.
 */
class GradleWrapperCommandTest {

    @Test
    fun `windows gets an explicitly relative bat`() {
        assertEquals(".\\gradlew.bat", GradleWrapperCommand.forOs("Windows 11"))
        assertEquals(".\\gradlew.bat", GradleWrapperCommand.forOs("windows 10"))
    }

    @Test
    fun `everything else gets the posix wrapper`() {
        assertEquals("./gradlew", GradleWrapperCommand.forOs("Linux"))
        assertEquals("./gradlew", GradleWrapperCommand.forOs("Mac OS X"))
        assertEquals("./gradlew", GradleWrapperCommand.forOs(""))
    }

    /**
     * The two spellings that were tried first and rejected, pinned so nobody "simplifies" back.
     *
     * `gradlew.bat` on its own is found by `cmd.exe` only when the working directory is on the
     * executable search path, and `NoDefaultCurrentDirectoryInExePath=1` - set in the environment
     * this was first launched in - removes it. `./gradlew.bat` is rejected by `cmd.exe` outright,
     * with `'.' is not recognized`.
     */
    @Test
    fun `the windows command is neither bare nor forward-slashed`() {
        val windows = GradleWrapperCommand.WINDOWS
        assertTrue(windows.startsWith(".\\"), "must be explicitly relative, was $windows")
        assertFalse(windows.contains('/'), "cmd.exe rejects a forward slash, was $windows")
    }
}
