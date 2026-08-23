package dev.wildware.udea.codegen

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.MemberName

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

    const val AGENT_TOOL: String = "$PACKAGE.AgentTool"
    const val ARG: String = "$PACKAGE.Arg"

    /**
     * `@AgentState`, and it is deliberately not in the group above.
     *
     * The four names above address the `Replicator` field space; this one does not touch it.
     * A property annotated with it gets no `fieldNames` entry, no `FieldMask` bit and no
     * `FieldStore` slot, because the frozen contract makes those three the same index and a
     * property owning one of them without the others cannot exist there.
     */
    const val AGENT_STATE: String = "$PACKAGE.AgentState"
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
    private const val IDENTITY = "dev.wildware.udea.core.identity"
    private const val CORE = "dev.wildware.udea.core"

    val REPLICATOR: ClassName = ClassName(REPLICATION, "Replicator")
    val COMPONENT_TYPE_ID: ClassName = ClassName(REPLICATION, "ComponentTypeId")
    val FIELD_MASK: ClassName = ClassName(REPLICATION, "FieldMask")
    val FIELD_STORE: ClassName = ClassName(REPLICATION, "FieldStore")
    val MASK_OPS: ClassName = ClassName(REPLICATION, "MaskOps")
    val BIT_WRITER: ClassName = ClassName(REPLICATION, "BitWriter")
    val BIT_READER: ClassName = ClassName(REPLICATION, "BitReader")
    val NO_SUCH_FIELD_INDEX: ClassName = ClassName(REPLICATION, "NoSuchFieldIndexException")

    /** `NetId` is a *primitive* field type, not a special case (spec 5, "Entity identity"). */
    val NET_ID: ClassName = ClassName(IDENTITY, "NetId")

    /** The universal unit of simulation time; stored and sent as its `Long` value. */
    val TICK: ClassName = ClassName(CORE, "Tick")

    /** Fully-qualified names of the value types that get their own `FieldStore` accessor. */
    const val NET_ID_FQN: String = "$IDENTITY.NetId"
    const val TICK_FQN: String = "$CORE.Tick"
}

/**
 * The `udea-net` bit codecs generated code calls.
 *
 * Only a component with a `@Q` field reaches these, and such a component's module needs
 * `udea-net` on its runtime classpath. That is the ownership the frozen contract already
 * states: `udea-core` declares `BitWriter`/`BitReader`, `udea-net` implements them "along
 * with framing, buffer management, `@Q` quantisation and the packet header". Emitting the
 * quantiser here rather than re-deriving the arithmetic in generated source is what keeps
 * one implementation of the mapping in the engine instead of two that can disagree by a
 * rounding mode.
 */
internal object NetNames {
    private const val BITS = "dev.wildware.udea.net.bits"

    val WRITE_FIXED: MemberName = MemberName(BITS, "writeFixed")
    val READ_FIXED: MemberName = MemberName(BITS, "readFixed")
}

/**
 * Where the module-level generated declarations live.
 *
 * One fixed package for every module, with the module name carried in the *class* name, so
 * two modules' indexes can never collide and neither needs to know the other's package.
 */
internal object GeneratedNames {
    const val PACKAGE: String = "dev.wildware.udea.generated"

    /** `Moba` becomes `MobaNetProtocol`. */
    fun netProtocol(moduleName: String): ClassName = ClassName(PACKAGE, "${moduleName}NetProtocol")

    /** `Moba` becomes `MobaNetModule`. */
    fun netModule(moduleName: String): ClassName = ClassName(PACKAGE, "${moduleName}NetModule")

    /** `Moba` becomes `MobaToolModule`: this module's `@AgentTool` index. */
    fun toolModule(moduleName: String): ClassName = ClassName(PACKAGE, "${moduleName}ToolModule")

    /** `Moba` becomes `MobaStateModule`: this module's `@AgentState` index. */
    fun stateModule(moduleName: String): ClassName = ClassName(PACKAGE, "${moduleName}StateModule")
}
