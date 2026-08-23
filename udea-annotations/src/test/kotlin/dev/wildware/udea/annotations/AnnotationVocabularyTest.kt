package dev.wildware.udea.annotations

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The annotation vocabulary of `udea-annotations` is a cross-module contract: `udea-codegen`
 * (KSP2) and `udea-compiler-plugin` (K2 FIR) both bind to these declarations by name, and
 * spec 5 freezes the `@Net` parameter vocabulary. This test pins the whole set - which
 * annotations exist, what each may be applied to, and how long each survives - so a change
 * to any of it has to be a deliberate edit here as well.
 */
class AnnotationVocabularyTest {

    /**
     * The complete, frozen vocabulary. `expected[fqn] = allowed targets`.
     *
     * Every one of these is [AnnotationRetention.BINARY]: no consumer reads them at runtime.
     * KSP2 reads declarations, the FIR checkers read declarations, and everything the runtime
     * needs is baked into generated code (spec 3.1: "the MCP surface needs no reflection and
     * survives R8"). BINARY is what a KSP processor and a FIR checker need and no more, and
     * it keeps the markers out of the runtime-visible annotation table.
     */
    private val expectedAnnotations: Map<String, Set<AnnotationTarget>> = mapOf(
        "dev.wildware.udea.annotations.Replicated" to setOf(AnnotationTarget.CLASS),
        "dev.wildware.udea.annotations.Net" to setOf(AnnotationTarget.PROPERTY),
        "dev.wildware.udea.annotations.Sim" to setOf(AnnotationTarget.PROPERTY),
        "dev.wildware.udea.annotations.Q" to setOf(AnnotationTarget.PROPERTY),
        "dev.wildware.udea.annotations.AgentTool" to setOf(AnnotationTarget.FUNCTION),
        "dev.wildware.udea.annotations.Arg" to setOf(AnnotationTarget.VALUE_PARAMETER),
        // Agent-only, and deliberately not part of the Replicator field space: see AgentState's
        // KDoc. It shares this table because the targeting and retention contract is the same.
        "dev.wildware.udea.annotations.AgentState" to setOf(AnnotationTarget.PROPERTY),
    )

    private val expectedEnums = setOf("Authority", "Lifetime", "Visibility")

    @Test
    fun `every declared annotation is loadable and is an annotation type`() {
        for (fqn in expectedAnnotations.keys) {
            val type = Class.forName(fqn)
            assertTrue(type.isAnnotation, "$fqn must be an annotation class")
        }
    }

    @Test
    fun `each annotation allows exactly the targets the spec gives it`() {
        for ((fqn, targets) in expectedAnnotations) {
            val target = Class.forName(fqn).getAnnotation(Target::class.java)
                ?: error("$fqn declares no @Target; KSP and the FIR checkers rely on a precise one")
            assertEquals(
                targets,
                target.allowedTargets.toSet(),
                "$fqn has the wrong @Target",
            )
        }
    }

    @Test
    fun `every annotation is BINARY-retained so KSP and FIR see it but the runtime does not`() {
        for (fqn in expectedAnnotations.keys) {
            val retention = Class.forName(fqn).getAnnotation(Retention::class.java)
                ?: error("$fqn declares no explicit @Retention; Kotlin would default it to RUNTIME")
            assertEquals(
                AnnotationRetention.BINARY,
                retention.value,
                "$fqn must be BINARY-retained: nothing reads it reflectively at runtime",
            )
        }
    }

    @Test
    fun `the source tree declares no annotation the vocabulary does not name`() {
        val declared = declarationsIn(sourceDir, keyword = "annotation class")
        assertEquals(
            expectedAnnotations.keys.map { it.substringAfterLast('.') }.toSortedSet(),
            declared.toSortedSet(),
            "udea-annotations declares an annotation the frozen vocabulary does not name (or vice versa)",
        )
    }

    @Test
    fun `the authority vocabulary frozen by spec 5 has exactly these constants`() {
        assertContentEquals(
            arrayOf(Authority.Server, Authority.OwnerPredicted, Authority.OwnerWritable),
            Authority.entries.toTypedArray(),
        )
        assertContentEquals(
            arrayOf(Lifetime.OnCreate, Lifetime.Always),
            Lifetime.entries.toTypedArray(),
        )
        assertContentEquals(
            arrayOf(Visibility.All, Visibility.OwnerOnly),
            Visibility.entries.toTypedArray(),
        )
        assertEquals(
            expectedEnums,
            declarationsIn(sourceDir, keyword = "enum class").toSet(),
            "the module declares an enum outside the frozen authority vocabulary",
        )
    }

    @Test
    fun `Net carries the four spec 5 parameters with the spec 5 defaults`() {
        val net = Net::class.java
        assertEquals(
            listOf("agentWritable", "authority", "lifetime", "visibility"),
            net.declaredMethods.map { it.name }.sorted(),
            "@Net's parameter list is frozen by spec 5",
        )
        assertEquals(Authority.Server, net.getMethod("authority").defaultValue)
        assertEquals(Lifetime.Always, net.getMethod("lifetime").defaultValue)
        assertEquals(Visibility.All, net.getMethod("visibility").defaultValue)
        assertEquals(
            false,
            net.getMethod("agentWritable").defaultValue,
            "agent write access is opt-in per field (spec 5)",
        )
    }

    @Test
    fun `Q takes the bit width and the range it quantises over`() {
        val q = Q::class.java
        assertEquals(listOf("bits", "max", "min"), q.declaredMethods.map { it.name }.sorted())
        assertEquals(Int::class.javaPrimitiveType, q.getMethod("bits").returnType)
        assertEquals(Float::class.javaPrimitiveType, q.getMethod("min").returnType)
        assertEquals(Float::class.javaPrimitiveType, q.getMethod("max").returnType)
    }

    @Test
    fun `Q has no default range because no default range is right`() {
        // A defaulted range is uncatchable: the FIR checkers can only see `min >= max`, so a
        // defaulted 0f..1f would clamp a rotation, a health pool or a world coordinate on
        // every packet and never report it. `defaultValue` is null exactly when the Kotlin
        // annotation parameter declares no default.
        val q = Q::class.java
        assertNull(q.getMethod("min").defaultValue, "@Q.min must have no default (issue-19)")
        assertNull(q.getMethod("max").defaultValue, "@Q.max must have no default (issue-19)")
        assertNull(q.getMethod("bits").defaultValue, "@Q.bits must have no default")
    }

    /**
     * Applying every annotation at a legal site. This does not run: it compiling at all is
     * the assertion, because a wrong `@Target` makes the fixture below fail to compile.
     */
    @Suppress("unused")
    @Replicated
    private class TargetFixture {
        @Net(authority = Authority.OwnerPredicted, lifetime = Lifetime.OnCreate, visibility = Visibility.OwnerOnly)
        @Q(bits = 12, min = -1f, max = 1f)
        var rotation: Float = 0f

        @Sim
        var lastGroundedTick: Long = 0L

        @AgentState(name = "match_state")
        val phase: String = "warmup"

        @AgentTool(name = "nudge", description = "fixture")
        fun nudge(@Arg(description = "how far", required = false) distance: Float): Float = distance
    }

    @Test
    fun `the target fixture applies every annotation at a legal site`() {
        // The fixture above proves targeting at compile time; this keeps it reachable so it
        // is compiled and cannot be dropped as dead code by a future cleanup.
        assertEquals(1.5f, TargetFixture().nudge(1.5f))
        assertEquals("warmup", TargetFixture().phase)
    }

    private companion object {
        /** Gradle runs tests with the project directory as the working directory. */
        val sourceDir = File("src/main/kotlin/dev/wildware/udea/annotations")

        fun declarationsIn(dir: File, keyword: String): List<String> {
            check(dir.isDirectory) { "expected source directory at ${dir.absolutePath}" }
            val pattern = Regex("""^public $keyword (\w+)""", RegexOption.MULTILINE)
            return dir.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .flatMap { file -> pattern.findAll(file.readText()).map { it.groupValues[1] } }
                .toList()
        }
    }
}
