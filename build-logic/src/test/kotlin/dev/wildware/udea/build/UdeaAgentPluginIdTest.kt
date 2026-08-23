package dev.wildware.udea.build

import java.io.File
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The two declarations of one plugin id, held level.
 *
 * `UdeaAgentPlugin` is compiled twice: once as `:udea-gradle`, the artifact a game outside this
 * repository would consume, and once inside `build-logic`, which is the only way this build can
 * apply it to `:moba` (a subproject's plugin cannot be applied to a sibling subproject). Each
 * compilation declares the id separately - `udea-gradle` through a `META-INF/gradle-plugins`
 * properties file, `build-logic` through `gradlePlugin { }` - and there is no mechanism that
 * makes the two agree.
 *
 * A drift there is silent in the worst way: `:moba` keeps working, because it resolves the
 * `build-logic` declaration, while the published plugin gains an id nobody applies. So the two
 * files are read and compared.
 */
class UdeaAgentPluginIdTest {

    /** Walked up rather than assumed: this test runs from `build-logic`, not from the repo root. */
    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile && File(it, "AGENTS.md").isFile }

    private val descriptor: File = repoRoot.resolve(
        "udea-gradle/src/main/resources/META-INF/gradle-plugins/$PLUGIN_ID.properties",
    )

    private val buildLogicScript: File = repoRoot.resolve("build-logic/build.gradle.kts")

    @Test
    fun `udea-gradle declares the plugin id as a resource`() {
        assertTrue(
            descriptor.isFile,
            "expected $descriptor: without it, `id(\"$PLUGIN_ID\")` resolves nothing for a game " +
                "consuming the published jar, and the plugin is a class nobody can apply - which " +
                "is the state this repository was in",
        )
        val properties = Properties().apply { descriptor.inputStream().use(::load) }
        assertEquals(IMPLEMENTATION_CLASS, properties.getProperty("implementation-class"))
    }

    @Test
    fun `build-logic declares the same id and the same class`() {
        val script = buildLogicScript.readText()
        assertTrue(
            script.contains("""id = "$PLUGIN_ID""""),
            "build-logic/build.gradle.kts must register the id `$PLUGIN_ID`, or nothing in this " +
                "build can apply the plugin to :moba",
        )
        assertTrue(
            script.contains("""implementationClass = "$IMPLEMENTATION_CLASS""""),
            "build-logic must point the id at $IMPLEMENTATION_CLASS",
        )
    }

    /** The shared compilation: if the source directory moves, both declarations become fiction. */
    @Test
    fun `build-logic compiles udea-gradle's plugin sources`() {
        assertTrue(
            buildLogicScript.readText().contains("udea-gradle/src/main/kotlin"),
            "build-logic must add udea-gradle's sources to its own main source set; otherwise " +
                "the id it registers points at a class it does not contain",
        )
        assertTrue(
            repoRoot.resolve("udea-gradle/src/main/kotlin/dev/wildware/udea/gradle/UdeaAgentPlugin.kt")
                .isFile,
            "the plugin source both declarations name has moved",
        )
    }

    private companion object {
        const val PLUGIN_ID = "dev.wildware.udea.agent"
        const val IMPLEMENTATION_CLASS = "dev.wildware.udea.gradle.UdeaAgentPlugin"
    }
}
