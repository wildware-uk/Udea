package dev.wildware.udea.core.replication

import dev.wildware.udea.core.ModuleFiles
import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.identity.NetId
import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.KType
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.full.declaredMemberProperties
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A committed dump of the frozen contract, so changing it is a reviewed diff.
 *
 * This stands in for a binary-compatibility `apiCheck`: the goal is that no signature in the
 * `Replicator` contract moves without the change appearing in a checked-in file that a
 * reviewer has to approve. Four modules compile against these declarations, and they break
 * together.
 *
 * To accept an intentional change: run this test, read the diff it prints, and copy the
 * actual dump (written beside the build output) over `udea-core/api/replicator-contract.api`.
 */
class ReplicatorApiDumpTest {

    @Test
    fun `the frozen contract matches its committed dump`() {
        val actual = dump(
            Replicator::class,
            ComponentTypeId::class,
            FieldMask::class,
            MaskOps::class,
            FieldStore::class,
            BitWriter::class,
            BitReader::class,
            NetId::class,
            Tick::class,
        )

        val golden = ModuleFiles.moduleDir.resolve("api/replicator-contract.api")
        val actualFile = ModuleFiles.moduleDir.resolve("build/replicator-contract.api.actual")
        actualFile.parentFile.mkdirs()
        actualFile.writeText(actual)

        assertEquals(
            if (golden.isFile) golden.readText().replace("\r\n", "\n") else "",
            actual,
            "the frozen contract changed; if that is intentional, copy $actualFile over $golden",
        )
    }

    private fun dump(vararg types: KClass<*>): String = buildString {
        for (type in types.sortedBy { it.qualifiedName }) {
            appendLine("${kindOf(type)} ${type.qualifiedName}")
            type.declaredMemberProperties
                .filterNot { it.visibility == kotlin.reflect.KVisibility.PRIVATE }
                .map { "  ${visibility(it.visibility)}val ${it.name}: ${render(it.returnType)}" }
                .sorted()
                .forEach(::appendLine)
            type.declaredMemberFunctions
                .filterNot { it.visibility == kotlin.reflect.KVisibility.PRIVATE }
                .map { function ->
                    val parameters = function.parameters
                        .filter { it.kind == KParameter.Kind.VALUE }
                        .joinToString { "${it.name}: ${render(it.type)}" }
                    "  ${visibility(function.visibility)}fun ${function.name}($parameters): " +
                        render(function.returnType)
                }
                .sorted()
                .forEach(::appendLine)
            appendLine()
        }
    }

    private fun kindOf(type: KClass<*>): String = when {
        type.java.isInterface -> "interface"
        type.objectInstance != null -> "object"
        type.isValue -> "value class"
        else -> "class"
    }

    private fun visibility(visibility: kotlin.reflect.KVisibility?): String =
        if (visibility == kotlin.reflect.KVisibility.PUBLIC) "" else "${visibility.toString().lowercase()} "

    private fun render(type: KType): String {
        val classifier = type.classifier
        val base = when (classifier) {
            is KClass<*> -> classifier.qualifiedName ?: classifier.toString()
            else -> classifier.toString()
        }
        val arguments = if (type.arguments.isEmpty()) {
            ""
        } else {
            type.arguments.joinToString(prefix = "<", postfix = ">") { argument ->
                argument.type?.let { render(it) } ?: "*"
            }
        }
        return base + arguments + if (type.isMarkedNullable) "?" else ""
    }
}
