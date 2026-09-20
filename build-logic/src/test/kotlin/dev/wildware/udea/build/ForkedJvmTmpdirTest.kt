package dev.wildware.udea.build

import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Every JVM the build forks gets a `java.io.tmpdir` of its own task's (issue #214).
 *
 * The defect is `gl tests (xvfb)` on run 35419154678: a `SIGBUS` in `ld-linux-x86-64.so.2`. LWJGL
 * and `box2d-jni` both unpack their native libraries into a directory under `java.io.tmpdir` that
 * every JVM on the machine shares, and neither holds a lock across the write. Two test JVMs that
 * start together on a fresh runner write the same `.so` at once, and one `dlopen`s it while the
 * other is still writing: the loader touches a page past the end of the file. So the property is
 * checked the way the crash depends on it - in the JVM Gradle actually forks, for a `Test` task and
 * a `JavaExec` task, each of which must see a directory nothing else running can see.
 *
 * The probes are Java, so nothing here compiles Kotlin and the stand-in compiler plugin project is
 * never loaded by a compiler (see [GradleFixture.withCompilerPluginProject]).
 */
class ForkedJvmTmpdirTest {

    private val probe =
        """
        public class Probe {
            public static void main(String[] args) throws Exception {
                // A temporary file, not only the property: a directory that does not exist is a
                // directory the natives cannot be unpacked into either.
                java.io.File file = java.io.File.createTempFile("probe", ".tmp");
                System.out.println("tmpdir[" + args[0] + "]=" + file.getParentFile().getCanonicalPath());
            }
        }
        """.trimIndent()

    private val probeTest =
        """
        public class ProbeTest {
            @org.junit.jupiter.api.Test
            public void tmpdir() throws Exception {
                Probe.main(new String[] { System.getProperty("probe.label") });
            }
        }
        """.trimIndent()

    private fun module(label: String): String =
        """
        plugins { id("dev.wildware.udea.kotlin-library") }

        val probeMain = sourceSets.main.get()
        listOf("probeOne", "probeTwo").forEach { name ->
            tasks.register<JavaExec>(name) {
                classpath = probeMain.runtimeClasspath
                mainClass.set("Probe")
                args("$label/" + name)
            }
        }
        tasks.test {
            systemProperty("probe.label", "$label/test")
            testLogging.showStandardStreams = true
            outputs.upToDateWhen { false }
        }
        """.trimIndent()

    private fun fixture(root: File): GradleFixture {
        for (name in listOf("alpha", "beta")) {
            File(root, "$name/src/main/java").mkdirs()
            File(root, "$name/src/main/java/Probe.java").writeText(probe)
            File(root, "$name/src/test/java").mkdirs()
            File(root, "$name/src/test/java/ProbeTest.java").writeText(probeTest)
        }
        return GradleFixture(root).withVersionCatalog().withCompilerPluginProject()
            .project("alpha", module("alpha"))
            .project("beta", module("beta"))
    }

    /** Every `tmpdir[label]=path` line the forked JVMs printed. */
    private fun tmpdirs(output: String): Map<String, String> =
        Regex("""tmpdir\[([^\]]+)]=(.+)""").findAll(output)
            .associate { it.groupValues[1] to it.groupValues[2].trim() }

    private val tasks = arrayOf(
        ":alpha:probeOne", ":alpha:probeTwo", ":alpha:test",
        ":beta:probeOne", ":beta:probeTwo", ":beta:test",
    )

    @Test
    fun `every forked JVM gets a temporary directory no other task shares`(@TempDir root: File) {
        val result = fixture(root).build(*tasks)
        val seen = tmpdirs(result.output)

        assertEquals(
            setOf("alpha/probeOne", "alpha/probeTwo", "alpha/test", "beta/probeOne", "beta/probeTwo", "beta/test"),
            seen.keys,
            "not every forked JVM reported its temporary directory:\n${result.output}",
        )
        assertEquals(
            seen.size,
            seen.values.toSet().size,
            "two tasks' JVMs share a temporary directory, so two of them can unpack the same native " +
                "library into it at once: $seen",
        )
        for ((label, dir) in seen) {
            val project = label.substringBefore('/')
            val build = File(root, "$project/build").canonicalPath
            assertTrue(
                dir.startsWith(build + File.separator),
                "$label's JVM wrote its temporary file to $dir, outside its own project's build " +
                    "directory $build - the machine-wide temporary directory is the shared one",
            )
        }
    }

    @Test
    fun `the directory is there to use when a clean in the same build deleted it`(@TempDir root: File) {
        // Configuration runs before any task, so `clean` deletes the directory after the task that
        // is to use it has been configured - and with the configuration cache on, a later build may
        // not configure at all. A forked JVM whose temporary directory is missing cannot create a
        // temporary file, and neither can a native loader.
        val result = fixture(root).build(":alpha:clean", ":alpha:probeOne")
        val dir = assertNotNull(
            tmpdirs(result.output)["alpha/probeOne"],
            "after a clean the forked JVM could not create a temporary file:\n${result.output}",
        )
        assertTrue(
            dir.startsWith(File(root, "alpha/build").canonicalPath + File.separator),
            "the JVM used $dir, so this checked the machine-wide directory rather than the task's own",
        )
    }
}
