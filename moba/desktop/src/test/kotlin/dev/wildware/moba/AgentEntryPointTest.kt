package dev.wildware.moba

import org.junit.jupiter.api.Test
import java.io.File
import java.lang.reflect.Modifier
import java.net.URL
import java.net.URLClassLoader
import kotlin.test.assertTrue

/**
 * `:moba:run`'s entry point really is a `main` the JVM will launch.
 *
 * ## The hole this closes
 *
 * `check` already compiles `src/agent` (see this module's build script), so an API break in
 * `udea-agent-host` fails the build. Compiling is not launching. During the Phase 2 integration
 * pass a KDoc block was inserted between `@JvmStatic` and `fun main`, which compiles perfectly:
 * the annotation moved onto the *next* declaration and `MobaAgent` no longer had a static `main`.
 * The whole build stayed green and `./gradlew :moba:run` died with
 *
 * ```
 * Error: Main method is not static in class dev.wildware.moba.agent.MobaAgent
 * ```
 *
 * which nothing would have caught until somebody tried to launch an instance - and `gamebridge.json`
 * points `game-bridge-mcp` at exactly that command, so "somebody" is an agent mid-session.
 *
 * ## Why reflection over a classloader rather than a plain reference
 *
 * `src/agent` is a debug-only source set and is deliberately absent from this module's ordinary
 * test classpath - `ReleaseRules.CLASSPATH_RULE` is the reason and it is not being weakened here.
 * So the class is loaded from the agent output directory the build script passes in, and only its
 * `main` is looked at. No agent code runs.
 */
class AgentEntryPointTest {

    @Test
    fun `MobaAgent exposes the static main that colon moba colon run invokes`() {
        val classesDir = checkNotNull(System.getProperty(CLASSES_PROPERTY)) {
            "system property '$CLASSES_PROPERTY' is not set; moba's build script sets it to the " +
                "agent source set's class output"
        }
        val roots = classesDir.split(File.pathSeparatorChar)
            .filter { it.isNotBlank() }
            .map { File(it) }
        assertTrue(roots.any { it.exists() }, "no agent classpath entry exists in $classesDir")

        val loader = URLClassLoader(
            roots.filter { it.exists() }.map { it.toURI().toURL() }.toTypedArray<URL>(),
            javaClass.classLoader,
        )
        val entry = loader.loadClass(ENTRY_POINT)
        val main = entry.declaredMethods.singleOrNull {
            it.name == "main" && it.parameterTypes.contentEquals(arrayOf(Array<String>::class.java))
        }
        assertTrue(main != null, "$ENTRY_POINT declares no main(String[]); ${entry.declaredMethods.size} methods")
        assertTrue(
            Modifier.isStatic(main!!.modifiers),
            "$ENTRY_POINT.main is not static, so the JVM refuses to launch it. In an `object`, " +
                "that means the @JvmStatic annotation is no longer attached to this function - " +
                "check for a declaration wedged between the two.",
        )
        assertTrue(Modifier.isPublic(main.modifiers), "$ENTRY_POINT.main is not public")
    }

    private companion object {
        /** The class `gamebridge.json`'s launch command ends up invoking. */
        const val ENTRY_POINT = "dev.wildware.moba.agent.MobaAgent"

        const val CLASSES_PROPERTY = "udea.moba.agentClasses"
    }
}
