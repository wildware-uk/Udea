package dev.wildware.udea.annotations

import java.io.File
import kotlin.test.Test
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
        // Issue #192: the asset DSL's once-only lambda promise, read by the K2 loop checker.
        "dev.wildware.udea.annotations.AssetDsl" to setOf(AnnotationTarget.FUNCTION),
        // Issue #233: the editor's gizmo handles. A handle driving several fields marks the class
        // and names them; a handle driving one marks that field.
        "dev.wildware.udea.annotations.PositionHandle" to setOf(AnnotationTarget.CLASS),
        "dev.wildware.udea.annotations.SizeHandle" to setOf(AnnotationTarget.CLASS),
        "dev.wildware.udea.annotations.RotationHandle" to setOf(AnnotationTarget.CLASS),
        "dev.wildware.udea.annotations.RadiusHandle" to setOf(AnnotationTarget.PROPERTY),
        "dev.wildware.udea.annotations.RangeHandle" to setOf(AnnotationTarget.PROPERTY),
        // Written by `udea-codegen` on a module registry, never by hand: which of the module's
        // components carry a handle, for the editor's KSP run in another module to read.
        "dev.wildware.udea.annotations.HandleIndex" to setOf(AnnotationTarget.CLASS),
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
    fun `the source tree declares no enum outside the frozen authority vocabulary`() {
        // The constants of those three enums are pinned by `AuthorityVocabularyTest` in
        // `commonTest`, which runs on every target; this half reads the source tree, so it is JVM.
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
     * The defaults are the spec's (issue #233): a 2D position is `x`/`y` with no `z`, a size is
     * `width`/`height` with no `depth`, and a rotation is `rotation`. An empty string is "no such
     * axis", which is what makes one annotation mean 2D or 3D.
     */
    @Test
    fun `the multi-field handles name their fields as strings, with the spec's defaults`() {
        fun defaults(type: Class<*>): Map<String, Any?> =
            type.declaredMethods.associate { it.name to it.defaultValue }

        assertEquals(mapOf("x" to "x", "y" to "y", "z" to ""), defaults(PositionHandle::class.java))
        assertEquals(mapOf("width" to "width", "height" to "height", "depth" to ""), defaults(SizeHandle::class.java))
        assertEquals(mapOf("rotation" to "rotation"), defaults(RotationHandle::class.java))
        assertEquals(emptyMap(), defaults(RadiusHandle::class.java), "a one-field handle names nothing: it marks its field")
        assertEquals(emptyMap(), defaults(RangeHandle::class.java), "a one-field handle names nothing: it marks its field")
    }

    private companion object {
        /** Gradle runs tests with the project directory as the working directory. */
        val sourceDir = File("src/commonMain/kotlin/dev/wildware/udea/annotations")

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
