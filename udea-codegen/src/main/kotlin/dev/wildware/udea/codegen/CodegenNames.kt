package dev.wildware.udea.codegen

import com.squareup.kotlinpoet.ClassName

/**
 * Fully-qualified names of the annotations the processor keys on.
 *
 * Held as strings rather than as `KClass` references: `udea-codegen` runs inside the Kotlin
 * compiler and must not force the annotation classes to load, and `Resolver` addresses
 * annotations by name anyway.
 */
internal object AnnotationNames {
    private const val PACKAGE = "dev.wildware.udea.annotations"

    const val REPLICATED: String = "$PACKAGE.Replicated"
    const val NET: String = "$PACKAGE.Net"
    const val SIM: String = "$PACKAGE.Sim"
    const val Q: String = "$PACKAGE.Q"
}

/**
 * The `udea-core` types generated code refers to.
 *
 * Deliberately [ClassName]s rather than a compile dependency on `udea-core`: the processor
 * only ever *names* these types, and depending on the runtime kernel from a build-time-only
 * module would put the kernel on every consumer's annotation-processor classpath.
 */
internal object CoreNames {
    private const val REPLICATION = "dev.wildware.udea.core.replication"

    val REPLICATOR: ClassName = ClassName(REPLICATION, "Replicator")
    val FIELD_MASK: ClassName = ClassName(REPLICATION, "FieldMask")
    val FIELD_STORE: ClassName = ClassName(REPLICATION, "FieldStore")
    val MASK_OPS: ClassName = ClassName(REPLICATION, "MaskOps")
    val BIT_WRITER: ClassName = ClassName(REPLICATION, "BitWriter")
    val BIT_READER: ClassName = ClassName(REPLICATION, "BitReader")
    val NO_SUCH_FIELD_INDEX: ClassName = ClassName(REPLICATION, "NoSuchFieldIndexException")
}
