package dev.wildware.udea.build

import org.gradle.api.Task
import org.gradle.process.JavaForkOptions

/**
 * Gives a JVM the build forks a `java.io.tmpdir` that belongs to its task alone (issue #214).
 *
 * LWJGL (under Kool) and `box2d-jni` unpack their native libraries at first use into a directory
 * under `java.io.tmpdir` - `lwjgl_<user>/<version>/x64/` and `de.fabmax.box2d-jni/` - which by
 * default every JVM on the machine shares. Neither holds a lock across the write: each checks the
 * file, finds it missing or different, and rewrites it. When two JVMs start together on a machine
 * where the files do not exist yet - a fresh CI runner, where the configuration cache makes Gradle
 * run `udeaGlTest` and `udeaAgentGlTest` at the same time - one of them `dlopen`s a library the
 * other is still writing, and the dynamic loader dies with `SIGBUS` touching a page past the end
 * of the file. That is the `gl tests (xvfb)` crash on run 35419154678, `ld-linux-x86-64.so.2`
 * named as the problematic frame.
 *
 * One directory per task removes the sharing. At the default `maxParallelForks` of one, the forks
 * of a single task run one after another, so a later fork finds the files whole and reuses them;
 * a task that raises it shares the directory between its own concurrent forks again.
 *
 * The property is the JVM's own rather than each library's switch
 * (`org.lwjgl.system.SharedLibraryExtractPath`, `box2d.nativeLibLocation`), so a native library
 * that arrives later is covered without anyone knowing it unpacks itself.
 *
 * Not covered: a JVM a test or tool starts for itself with `ProcessBuilder` - the editor's Play
 * standalone, the UDP proofs - gets the machine's default unless the code starting it passes the
 * property on.
 */
internal object ForkedJvmTmpdir {

    /** The JVM property every one of those loaders resolves its directory under. */
    const val PROPERTY: String = "java.io.tmpdir"

    /**
     * Points [options]' JVM at [task]'s own temporary directory.
     *
     * The directory is created again as the task starts, not only now: configuration runs before
     * any task, so a `clean` in the same build deletes it after this has run, and a JVM whose
     * temporary directory is missing cannot create a temporary file.
     */
    fun isolate(task: Task, options: JavaForkOptions) {
        val directory = task.temporaryDir
        options.systemProperty(PROPERTY, directory.absolutePath)
        task.doFirst { directory.mkdirs() }
    }
}
