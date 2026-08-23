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

    private companion object {
        /** Any LibGDX type at all, not only `physics.box2d`: the kernel is free of the lot. */
        val BADLOGIC = Regex("""\bcom\.badlogic\.gdx\b""")

        const val FORBIDDEN_PACKAGE = "com.badlogic.gdx"
    }
}
