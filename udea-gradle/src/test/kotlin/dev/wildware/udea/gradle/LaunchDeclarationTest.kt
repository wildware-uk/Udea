package dev.wildware.udea.gradle

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The launch declaration: the parts a Gradle TestKit run cannot assert cheaply, asserted here.
 *
 * TestKit spends a whole Gradle invocation per case; the rules that matter - `{port}` present,
 * the port range clear of the hand-assigned ports, and byte-identical output - are decisions, so
 * they are tested as decisions.
 */
class LaunchDeclarationTest {

    @Test
    fun `a command without the port placeholder is refused`() {
        val refused = assertFailsWith<IllegalArgumentException> {
            LaunchDeclaration(name = "Moba", command = "./gradlew moba:run")
        }
        // The message has to say *why*, because the symptom is an instance the bridge cannot find.
        assertContains(refused.message ?: "", "{port}")
        assertContains(refused.message ?: "", "entire mechanism")
    }

    @Test
    fun `the default port range is clear of the ports people assign by hand`() {
        val range = LaunchDeclaration.DEFAULT_PORT_RANGE
        val (low, high) = range.split('-').map { it.toInt() }

        assertTrue(low > 7810, "the range must start above the 7800-7810 block")
        assertFalse(7777 in low..high, "7777 is the port people hand out by hand")
        assertEquals("7820-7839", range)
    }

    @Test
    fun `rendering is deterministic and orders the environment`() {
        val declaration = LaunchDeclaration(
            name = "Moba",
            command = "./gradlew :moba:run -PdebugPort={port} --console=plain",
            env = mapOf("Z" to "last", "A" to "first"),
        )

        val once = declaration.render()
        assertEquals(once, declaration.render(), "two renders must be byte-identical")
        assertTrue(
            once.indexOf(""""A"""") < once.indexOf(""""Z""""),
            "an unordered map would make the cacheable task's output non-deterministic",
        )
        assertContains(once, """"readyTimeoutMs": 180000""")
        assertContains(once, """"cwd": ".",""")
    }

    /**
     * `launch.cwd` is a Windows path half the time, and an unescaped backslash produces a document
     * the bridge's `JSON.parse` rejects with a column number rather than a cause.
     */
    @Test
    fun `windows paths and quotes are escaped`() {
        val declaration = LaunchDeclaration(
            name = """A "quoted" game""",
            command = """C:\builds\run.bat --port {port}""",
            cwd = """C:\Users\shaun\Workspace\udea""",
        )

        val json = declaration.render()
        assertContains(json, """C:\\builds\\run.bat""")
        assertContains(json, """C:\\Users\\shaun\\Workspace\\udea""")
        assertContains(json, """A \"quoted\" game""")
    }

    @Test
    fun `an empty environment still renders valid json`() {
        assertContains(LaunchDeclaration("Moba", "run {port}").render(), """"env": {}""")
    }

    @Test
    fun `a malformed port range is refused`() {
        assertFailsWith<IllegalArgumentException> {
            LaunchDeclaration("Moba", "run {port}", portRange = "7820..7839")
        }
    }

    /** The property the plugin passes and the property the game reads have to be the same string. */
    @Test
    fun `the plugin passes the property the agent host reads`() {
        assertEquals("udea.agent.port", UdeaAgentPlugin.AGENT_PORT_PROPERTY)
        assertEquals("debugPort", UdeaAgentPlugin.DEFAULT_PORT_PROPERTY)
    }
}
