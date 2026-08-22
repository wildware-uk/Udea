package dev.wildware.udea.codegen.replicator

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.TypeName

/**
 * How a field is held in a `FieldStore`, and how wide it is on the wire by default.
 *
 * [accessor] is the suffix of the store accessor pair — `setInt`/`getInt`,
 * `setNetId`/`getNetId` — so capture and restore are one emitted line per field for every
 * kind. The *wire* encoding is not uniform (a `NetId` is written as its packed word, a
 * `Tick` as its `Long`, a quantised `Float` as a folded bit field), and that asymmetry lives
 * in `FieldIo` rather than being smeared through the emitter.
 *
 * [wireToken] and [wireBits] exist for `net-protocol.lock`, which records the declared width
 * of every field so that a wire-format change is visible in a reviewed diff rather than only
 * in a desync.
 */
internal enum class FieldStorage(
    /** The suffix of the store accessors: `setInt`, `getInt`, `setNetId`, `getNetId`. */
    val accessor: String,
    /** How the kind is spelled in `net-protocol.lock`. */
    val wireToken: String,
    /** Bits on the wire when the field is not quantised. */
    val wireBits: Int,
) {
    BOOLEAN("Boolean", "bool", 1),
    INT("Int", "i32", 32),
    LONG("Long", "i64", 64),
    FLOAT("Float", "f32", 32),

    /** An enum, stored and sent as its ordinal. */
    ENUM("Int", "enum", 32),

    /** [dev.wildware.udea.codegen.CoreNames.NET_ID], sent as its packed 32-bit word. */
    NET_ID("NetId", "netid", 32),

    /** [dev.wildware.udea.codegen.CoreNames.TICK], sent as its 64-bit count. */
    TICK("Tick", "tick", 64),
}

/**
 * A resolved `@Q(bits, min, max)` declaration, folded to literals at generation time.
 *
 * Nothing about this survives into the generated file as an annotation: the emitter writes
 * the three numbers directly into the `writeFixed`/`readFixed` calls, which is the thing a
 * runtime codec fundamentally cannot do (spec 3.1).
 *
 * @param bits the width of the packed field, `1..32`.
 * @param min inclusive lower bound; values below it clamp here.
 * @param max inclusive upper bound; values above it clamp here.
 */
internal data class Quantisation(val bits: Int, val min: Float, val max: Float) {

    /**
     * The largest error a round trip through `write`/`read` can introduce, in the field's own
     * units: half a step of `(max - min) / (2^bits - 1)`.
     *
     * `Q.Fixed` computes exactly this at runtime, and the two agree because both divide the
     * range into `2^bits - 1` steps between two exactly-representable endpoints. It is
     * documented into the generated file's KDoc so that a reader can see what a declaration
     * costs without running anything.
     */
    val epsilon: Float
        get() = (((max.toDouble() - min.toDouble()) / ((1L shl bits) - 1L)) / 2.0).toFloat()

    /** `q:12` — the token `net-protocol.lock` records for a quantised field. */
    val wireToken: String get() = "q"
}

/**
 * One replicated or snapshotted field of a component, at a fixed bit index.
 *
 * A field is **not** the same thing as an annotated property. A composite value type is
 * lowered to one field per primitive component (the frozen contract,
 * `docs/contracts/replicator.md`, "Composite values are lowered"), so `@Net val position:
 * Vec2` becomes the two fields `position.x` and `position.y`, each with its own mask bit,
 * its own store column and its own entry in `fieldNames`. [path] is what makes that work:
 * it is the property access path from the component, and `path.joinToString(".")` is the
 * field's name.
 *
 * @param path the property access chain from the component, e.g. `["position", "x"]`.
 * @param constant the generated `FIELD_…` constant naming [index].
 * @param index the bit index, assigned by [FieldOrder] and by nothing else.
 * @param net `true` for `@Net` (in `netMask` and `allMask`), `false` for `@Sim` (in `allMask`
 *   only). Spec 3.1: a field in `netMask` but not `allMask` is a contradiction, so this is one
 *   flag and not two.
 * @param storage how the value is held in the `FieldStore`.
 * @param declaredType the type at the end of [path], used for the `setField` cast.
 * @param enumEntries non-null when the field is an enum, in which case the ordinal is what
 *   is stored and this is the class the ordinal is decoded back through.
 * @param quantisation non-null when the property carried `@Q`; only ever set on a `Float`.
 */
internal data class ReplicatedField(
    val path: List<String>,
    val constant: String,
    val index: Int,
    val net: Boolean,
    val storage: FieldStorage,
    val declaredType: TypeName,
    val enumEntries: ClassName?,
    val quantisation: Quantisation?,
) {
    /** The Kotlin property path; also the entry in `Replicator.fieldNames`. */
    val name: String = path.joinToString(".")

    /** `q:12` for a quantised field, otherwise the storage kind's own token and width. */
    val wireDescription: String =
        quantisation?.let { "${it.wireToken}:${it.bits}" } ?: "${storage.wireToken}:${storage.wireBits}"
}

/**
 * A `@Replicated` component the emitter can turn into a `Replicator<T>` without asking the
 * compiler another question.
 *
 * Building this is where every diagnostic happens; emission is total. That split is the point
 * of the rewrite: the old generator interleaved resolution and string building inside one
 * `try/catch (e: Exception)` per symbol, so a component it could not handle became a log line
 * and a silently missing serializer.
 */
internal data class ReplicatedComponent(
    val className: ClassName,
    val qualifiedName: String,
    /** Already in bit-index order. `fields[i].index == i`. */
    val fields: List<ReplicatedField>,
) {
    /** The name of the generated object, e.g. `TransformReplicator`. */
    val replicatorName: String = className.simpleNames.joinToString("") + "Replicator"

    /** What generated error messages call the component. */
    val typeName: String = className.simpleNames.joinToString(".")

    val netFields: List<ReplicatedField> get() = fields.filter { it.net }
}
