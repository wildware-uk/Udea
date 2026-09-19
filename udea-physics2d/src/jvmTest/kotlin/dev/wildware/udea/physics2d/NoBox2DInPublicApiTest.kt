package dev.wildware.udea.physics2d

import java.io.File
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.KVisibility
import kotlin.reflect.full.declaredMembers
import kotlin.reflect.full.memberProperties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * No Box2D type appears in anything a game can see.
 *
 * Read through Kotlin's own metadata rather than the class files' access flags, because Kotlin
 * compiles an `internal` class to a public JVM class: a bytecode scan would see
 * `Box2DPhysicsWorld` as public and would have to be told to look away, which is how a scan goes
 * blind. Every class this module compiles is visited, and for each one Kotlin calls public, every
 * public member's parameter types, return type and the class's supertypes are checked for the
 * two packages `box2d-jni` publishes into.
 *
 * Kotlin reflection cannot read a file facade - the class holding a file's top-level functions
 * and properties - so those are checked at the source instead: none of this module's may be
 * public, which leaves classes as the whole public surface.
 */
class NoBox2DInPublicApiTest {

    @Test
    fun `no public declaration of this module names a Box2D type`() {
        val classes = moduleClasses()
        val public = classes.filter { it.visibility == KVisibility.PUBLIC }

        assertTrue(Physics2DModule::class in public && Physics2DSettings::class in public, "the public API was scanned")
        assertTrue(Box2DPhysicsWorld::class in classes && Box2DPhysicsWorld::class !in public, "the solver was seen and is not public")
        assertEquals(emptyList(), public.flatMap(::box2DMentions))
    }

    @Test
    fun `no top-level function or property in this module's main sources is public`() {
        val sources = File(checkNotNull(System.getProperty("udea.physics2d.projectDir")), "src")
        val mainFiles = sources.listFiles { dir -> dir.name.endsWith("Main") }.orEmpty()
            .flatMap { dir -> dir.walk().filter { it.extension == "kt" }.toList() }
        assertTrue(mainFiles.size >= 5, "only ${mainFiles.size} main sources found under $sources")

        val offenders = mainFiles.flatMap { file ->
            file.readLines().filter { TOP_LEVEL_PUBLIC.matches(it) }.map { "${file.name}: ${it.trim()}" }
        }
        assertEquals(emptyList(), offenders)
        // Known negative for the pattern, so an empty result is not a pattern that matches nothing.
        assertTrue(TOP_LEVEL_PUBLIC.matches("public fun leak(vector: box2d.b2Vec2) {}"))
        assertTrue(!TOP_LEVEL_PUBLIC.matches("    public fun member() {}"))
        assertTrue(!TOP_LEVEL_PUBLIC.matches("public class Physics2DModule("))
    }

    @Test
    fun `the scan finds a Box2D type where one is exposed`() {
        // The known negative: a public class that does leak one, declared here for the purpose.
        assertEquals(
            listOf("Leaky.leak: box2d.b2Vec2"),
            box2DMentions(Leaky::class).map { it.substringBefore(" (") },
        )
    }

    /** Every Box2D type [type] exposes through its public surface, as `Class.member: type`. */
    private fun box2DMentions(type: KClass<*>): List<String> {
        val found = ArrayList<String>()
        fun check(where: String, t: KType) {
            val name = (t.classifier as? KClass<*>)?.qualifiedName ?: return
            if (BOX2D_PACKAGES.any { name.startsWith(it) }) found += "${type.simpleName}.$where: $name"
            t.arguments.forEach { argument -> argument.type?.let { check(where, it) } }
        }
        type.supertypes.forEach { check("supertype", it) }
        for (member in type.declaredMembers) {
            if (member.visibility != KVisibility.PUBLIC && member.visibility != KVisibility.PROTECTED) continue
            member.parameters.forEach { check(member.name, it.type) }
            check(member.name, member.returnType)
        }
        type.constructors.filter { it.visibility == KVisibility.PUBLIC }.forEach { constructor ->
            constructor.parameters.forEach { check("<init>", it.type) }
        }
        type.memberProperties.size // resolves every property's metadata, so a broken one throws here
        return found
    }

    /** Every class under this module's package, from the directory its main classes load from. */
    private fun moduleClasses(): List<KClass<*>> {
        val root = File(Physics2DModule::class.java.protectionDomain.codeSource.location.toURI())
        val packageDir = File(root, "dev/wildware/udea/physics2d")
        return packageDir.walk()
            .filter { it.isFile && it.extension == "class" }
            .map { file ->
                val name = file.relativeTo(root).path.removeSuffix(".class").replace(File.separatorChar, '.')
                Class.forName(name, false, javaClass.classLoader)
            }
            // Kind 1 is a class; file facades (2) are covered by the source check above.
            .filter { it.getAnnotation(Metadata::class.java)?.kind == 1 && !it.isAnonymousClass && !it.isLocalClass }
            .map { it.kotlin }
            .toList()
    }

    /** Exposes a Box2D type on purpose, as the known negative for the scan. */
    class Leaky {
        @Suppress("unused")
        fun leak(vector: box2d.b2Vec2): Unit = Unit
    }

    private companion object {
        val BOX2D_PACKAGES = listOf("box2d.", "box2dandroid.")

        /** A function, property or alias at column zero with an explicit `public`: a top-level one. */
        val TOP_LEVEL_PUBLIC = Regex("""^public\s+(?:\w+\s+)*(?:fun|val|var|typealias)\b.*""")
    }
}
