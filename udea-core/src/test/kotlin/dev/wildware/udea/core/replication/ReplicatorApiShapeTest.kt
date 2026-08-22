package dev.wildware.udea.core.replication

import dev.wildware.udea.core.KotlinSource
import dev.wildware.udea.core.ModuleFiles
import java.io.File
import kotlin.reflect.KType
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.full.declaredMemberProperties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The shape of the frozen interface, checked rather than trusted.
 *
 * Spec 7 names `Replicator<T>` the highest-coupling interface in the project: four modules
 * break together if it moves. The extension everybody can already see coming is a component
 * with more than 64 replicated fields, forcing `Long` to `LongArray`. That is a non-breaking
 * change **only** while no signature says `Long` where it means "a set of fields", so that
 * is what this test enforces.
 */
class ReplicatorApiShapeTest {

    @Test
    fun `Replicator declares exactly the frozen members`() {
        val members = (
            Replicator::class.declaredMemberProperties.map { it.name } +
                Replicator::class.declaredMemberFunctions.map { it.name }
            ).sorted()

        assertEquals(
            listOf(
                "allMask",
                "apply",
                "capture",
                "diff",
                "fieldNames",
                "getField",
                "netMask",
                "read",
                "setField",
                "typeId",
                "write",
            ),
            members,
            "the Replicator surface is frozen: adding or removing a member is a four-module change",
        )
    }

    @Test
    fun `every field set in the Replicator surface is a FieldMask`() {
        val maskType = FieldMask::class.qualifiedName

        assertEquals(maskType, typeName(Replicator::class.declaredMemberProperties.single { it.name == "netMask" }.returnType))
        assertEquals(maskType, typeName(Replicator::class.declaredMemberProperties.single { it.name == "allMask" }.returnType))
        assertEquals(maskType, typeName(function("diff").returnType))
        assertEquals(maskType, typeName(function("read").returnType))
        assertEquals(maskType, typeName(parameterType("write", "mask")))
        assertEquals(maskType, typeName(parameterType("apply", "mask")))
    }

    @Test
    fun `no member of Replicator exposes a raw Long`() {
        // A value class erases to its underlying type on the JVM, so this has to run on
        // Kotlin's reflection. If it ever passes trivially, the reflection broke.
        val offenders = Replicator::class.declaredMemberFunctions.flatMap { function ->
            val types = function.parameters.map { it.name to it.type } + ("return" to function.returnType)
            types.filter { typeName(it.second) == "kotlin.Long" }
                .map { "${function.name}(${it.first})" }
        } + Replicator::class.declaredMemberProperties
            .filter { typeName(it.returnType) == "kotlin.Long" }
            .map { it.name }

        assertEquals(
            emptyList(),
            offenders.sorted(),
            "a Long here would be a mask in disguise, and widening the mask would break every caller",
        )
    }

    @Test
    fun `FieldMask keeps its storage internal`() {
        val bits = FieldMask::class.declaredMemberProperties.single { it.name == "bits" }

        assertEquals(kotlin.reflect.KVisibility.INTERNAL, bits.visibility)
        assertEquals(
            listOf("bits"),
            FieldMask::class.declaredMemberProperties.map { it.name },
            "FieldMask must carry exactly one storage property, and it must not be public",
        )
    }

    @Test
    fun `no bitwise operator is reachable on a FieldMask`() {
        val operators = setOf("and", "or", "xor", "inv", "shl", "shr", "ushr", "plus", "minus")
        val exposed = FieldMask::class.declaredMemberFunctions
            .map { it.name }
            .filter { it in operators }

        assertEquals(
            emptyList(),
            exposed,
            "every bit operation goes through MaskOps, or callers start depending on the storage",
        )
    }

    @Test
    fun `only udea-core and udea-net declare a property of type FieldMask`() {
        // The mask may be passed through the Replicator API, never stored in game code. A
        // component or system holding one is how a Long-to-LongArray widening turns into a
        // breaking change.
        val allowedModules = setOf("udea-core", "udea-net")
        val moduleRoots = ModuleFiles.repoRoot.listFiles()
            .orEmpty()
            .filter { it.isDirectory && (it.name.startsWith("udea-") || it.name == "moba") }
            .filterNot { it.name in allowedModules }
            .sortedBy { it.name }

        assertTrue(moduleRoots.isNotEmpty(), "expected sibling udea-* modules beside udea-core")

        val offenders = moduleRoots.flatMap { module ->
            ModuleFiles.kotlinFilesIn(module.resolve("src")).flatMap { file -> fieldMaskProperties(file) }
        }

        assertEquals(
            emptyList(),
            offenders,
            "FieldMask is passed through the Replicator API and never stored outside udea-core/udea-net",
        )
    }

    private fun fieldMaskProperties(file: File): List<String> {
        val code = KotlinSource.stripCommentsAndStrings(file.readText())
        return FIELD_MASK_PROPERTY.findAll(code)
            .map { "${ModuleFiles.relativePath(file)}:${KotlinSource.lineOf(code, it.range.first)}" }
            .toList()
    }

    private fun function(name: String) =
        Replicator::class.declaredMemberFunctions.single { it.name == name }

    private fun parameterType(functionName: String, parameterName: String): KType =
        function(functionName).parameters.single { it.name == parameterName }.type

    private fun typeName(type: KType): String? = (type.classifier as? kotlin.reflect.KClass<*>)?.qualifiedName

    private companion object {
        /** A `val`/`var` declaration whose declared type is `FieldMask`. */
        val FIELD_MASK_PROPERTY = Regex("""\b(?:val|var)\s+\w+\s*:\s*FieldMask\b""")
    }
}
