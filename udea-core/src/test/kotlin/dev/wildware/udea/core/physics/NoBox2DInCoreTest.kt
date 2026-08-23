package dev.wildware.udea.core.physics

import dev.wildware.udea.core.KotlinSource
import dev.wildware.udea.core.ModuleFiles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Nothing in `udea-core` names a Box2D type — not a signature, not a component field, not an
 * import.
 *
 * The kernel has no GL and no natives on its compile classpath, and the moment a
 * `com.badlogic.gdx.physics.box2d` type appears in one of its signatures every consumer of the
 * kernel inherits that dependency. It is also the structural half of "Box2D is never snapshot
 * state" (spec 3.4): a component field whose type came from the solver would end up in a
 * `Replicator`'s field table, which is exactly the thing that must never happen.
 */
class NoBox2DInCoreTest {

    @Test
    fun `no udea-core source imports a LibGDX physics type`() {
        val offenders = ArrayList<String>()
        for (file in ModuleFiles.mainSources + ModuleFiles.testFixtureSources) {
            val path = ModuleFiles.relativePath(file)
            // Comments stripped, so the KDoc in this module that cites `Box2DSystem.kt` and
            // the fully qualified package name does not count as an import.
            val code = KotlinSource.stripCommentsAndStrings(file.readText())
            code.lineSequence().forEachIndexed { index, text ->
                if (BADLOGIC.containsMatchIn(text)) offenders += "$path:${index + 1}  ${text.trim()}"
            }
        }

        assertEquals(
            emptyList(),
            offenders,
            "udea-core must compile with no com.badlogic.gdx reference; the Box2D-backed " +
                "PhysicsWorld lives in its own module",
        )
    }

    @Test
    fun `no PhysicsWorld signature mentions a solver type`() {
        val offenders = PhysicsWorld::class.java.declaredMethods.flatMap { method ->
            (method.parameterTypes.toList() + method.returnType)
                .filter { it.name.startsWith(FORBIDDEN_PACKAGE) }
                .map { "${method.name}: ${it.name}" }
        }

        assertTrue(
            PhysicsWorld::class.java.declaredMethods.size >= 10,
            "expected the whole PhysicsWorld surface to be scanned, saw " +
                "${PhysicsWorld::class.java.declaredMethods.size} methods",
        )
        assertEquals(emptyList(), offenders, "a Box2D type escaped through the boundary interface")
    }

    @Test
    fun `no physics component carries a solver-typed field`() {
        // The field-table check, at the level udea-core can make it: these are the components a
        // Replicator would be generated for, so a solver type here is a solver type in a
        // snapshot column.
        val components = listOf(
            PhysicsBody::class.java,
            Box::class.java,
            Circle::class.java,
            Capsule::class.java,
            Chain::class.java,
            Teleport::class.java,
        )

        val offenders = components.flatMap { type ->
            type.declaredFields
                .filterNot { it.isSynthetic }
                .filter { it.type.name.startsWith(FORBIDDEN_PACKAGE) }
                .map { "${type.simpleName}.${it.name}: ${it.type.name}" }
        }

        assertEquals(emptyList(), offenders)
        assertTrue(
            components.all { it.declaredFields.any { field -> !field.isSynthetic } },
            "every component was actually inspected",
        )
    }

    @Test
    fun `no BodyHandle property is annotated for capture`() {
        // The structural guard for the one field on PhysicsBody that must never be snapshotted.
        // Reflection cannot make it: BodyHandle is a value class over Int, so `handle` is an
        // `int` field by the time the JVM sees it, and the test above — which looks for
        // solver-typed fields — waves it straight through. An `@Sim var handle` would lower to
        // an ordinary int column and be restored *before* rebuildFrom reassigns it, handing out
        // handles to bodies that no longer exist. So this rule is checked in source, where the
        // annotation and the declared type are both still visible.
        val offenders = ArrayList<String>()
        var declarationsScanned = 0

        for (file in ModuleFiles.mainSources + ModuleFiles.testFixtureSources) {
            val path = ModuleFiles.relativePath(file)
            val lines = KotlinSource.stripCommentsAndStrings(file.readText()).lines()
            lines.forEachIndexed { index, text ->
                val declaration = HANDLE_PROPERTY.find(text) ?: return@forEachIndexed
                declarationsScanned++
                // Annotations sit either ahead of the keyword on the same line or on the
                // contiguous lines above it, which is every form Kotlin allows here.
                //
                // A line above counts only if it is an annotation *and nothing else*. Taking
                // every line that merely starts with `@` attributes `@Sim var x: Float = 0f`
                // to the BodyHandle property declared underneath it, so this architecture
                // gate would go red for an edit that has nothing to do with BodyHandle.
                val attached = ArrayList<String>()
                attached += text.substring(0, declaration.range.first)
                var above = index - 1
                while (above >= 0 && ANNOTATION_ONLY.matches(lines[above].trim())) {
                    attached += lines[above]
                    above--
                }
                val captured = attached.flatMap { line -> CAPTURE_ANNOTATION.findAll(line).map { it.value } }
                if (captured.isNotEmpty()) {
                    offenders += "$path:${index + 1}  $captured on ${declaration.value.trim()}"
                }
            }
        }

        assertEquals(
            emptyList(),
            offenders,
            "a BodyHandle is derived state: it is invalidated by every rebuildFrom, so a " +
                "snapshot carrying one restores a dangling handle that still type-checks",
        )
        assertTrue(
            declarationsScanned >= 2,
            "the scan found only $declarationsScanned BodyHandle properties; it must at least " +
                "be seeing PhysicsBody.handle and RayHit.body, or it is guarding nothing",
        )
    }

    private companion object {
        /** Any LibGDX type at all, not only `physics.box2d`: the kernel is free of the lot. */
        val BADLOGIC = Regex("""\bcom\.badlogic\.gdx\b""")

        const val FORBIDDEN_PACKAGE = "com.badlogic.gdx"

        /** A `val`/`var` whose declared type is `BodyHandle`, wherever it is declared. */
        val HANDLE_PROPERTY = Regex("""\b(?:val|var)\s+\w+\s*:\s*BodyHandle\b""")

        /**
         * The two annotations that put a field in a `FieldStore` column, qualified or not,
         * and with or without a use-site target — `@field:Sim` opens the same column as
         * `@Sim`, so a scan that missed it would guard only the spelling nobody used.
         */
        val CAPTURE_ANNOTATION = Regex("""@(?:\w+:)?(?:[\w.]+\.)?(?:Net|Sim)\b""")

        /**
         * A line that is an annotation and nothing else, which is the only kind that can
         * belong to a declaration further down.
         */
        val ANNOTATION_ONLY = Regex("""(?:@[\w.:]+(?:\([^)]*\))?\s*)+""")
    }
}
