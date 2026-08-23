package dev.wildware.udea.codegen.rpc

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.TypeName

/**
 * How one `@Rpc` argument is written and read.
 *
 * Fixed width for everything, and that is deliberate rather than lazy. A varint would save
 * three bytes on a small ability slot; it would also make the number of bytes an argument
 * occupies a function of the value a **remote peer** chose, which is one more thing a decoder
 * has to be right about while being fed hostile input. RPC payloads are small and infrequent
 * next to a snapshot section, so the trade is nearly free in the direction of the thing D10
 * cares about.
 */
internal enum class RpcArgKind(val wireToken: String) {
    BOOLEAN("bool"),
    INT("i32"),
    LONG("i64"),
    FLOAT("f32"),

    /** An enum, sent as its ordinal and **bounds checked** on read. */
    ENUM("enum"),

    /** `NetId`, sent as its packed word. `NetId.ofRaw` rejects a reserved-bit pattern. */
    NET_ID("netid"),

    /** `Tick`, sent as its `Long`. */
    TICK("tick"),
}

/** One parameter of an `@Rpc` function. */
internal data class RpcArg(
    val name: String,
    val kind: RpcArgKind,
    val type: TypeName,
    /** Non-null for [RpcArgKind.ENUM]: the class the ordinal is decoded back through. */
    val enumEntries: ClassName?,
)

/**
 * An `@Rpc` function the emitter can turn into an `RpcDescriptor` without asking another
 * question.
 *
 * Same split as [dev.wildware.udea.codegen.replicator.ReplicatedComponent]: every diagnostic
 * happened while this was built, and emission is total.
 *
 * @param targetArg index into [args] of the `NetId` the authority guard is about, or `-1` when
 *   the declared authority needs no target. **A guard with no target is not a guard**, which is
 *   why an ownership authority without one is a build failure and not a permissive default.
 */
internal data class RpcFunction(
    val packageName: String,
    val functionName: String,
    val qualifiedName: String,
    val direction: String,
    val authority: String,
    val reliability: String,
    val relevancy: String,
    val ratePerSecond: Int,
    val burst: Int,
    val args: List<RpcArg>,
    val targetArg: Int,
) {

    /** `fireAbility` becomes `FireAbilityRpc`. */
    val objectName: String = functionName.replaceFirstChar(Char::uppercaseChar) + "Rpc"

    /** The `NetId` argument the guard reads, or `null`. */
    val target: RpcArg? get() = args.getOrNull(targetArg)

    /**
     * What a protocol lock would record for this RPC.
     *
     * Not written anywhere yet, and that is stated rather than implied: the RPC index is
     * assigned at runtime from the sorted name list (`RpcRegistry`), so nothing in a build
     * artefact pins it today.
     */
    val wireDescription: String =
        "$qualifiedName($direction,$authority) " + args.joinToString(",") { "${it.name}:${it.kind.wireToken}" }
}
