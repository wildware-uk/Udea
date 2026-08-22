package dev.wildware.udea.codegen.replicator

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.TypeName

/**
 * How a field is held in a `FieldStore` and put on the wire.
 *
 * One name serves both, because `FieldStore.setInt`/`getInt` and `BitWriter.writeInt`/
 * `BitReader.readInt` are deliberately named alike: a generated line is
 * `out.writeInt(store.getInt(slot, FIELD_X))` for every storage kind, which is what keeps the
 * emitter free of the per-type `when` cascade the old `NetworkGenerator.serializeLine` grew.
 */
internal enum class FieldStorage(
    /** The suffix of the store and wire accessors: `setInt`, `getInt`, `writeInt`, `readInt`. */
    val accessor: String,
) {
    BOOLEAN("Boolean"),
    INT("Int"),
    LONG("Long"),
    FLOAT("Float"),
}

/**
 * One replicated or snapshotted field of a component, at a fixed bit index.
 *
 * @param name the Kotlin property name; also the entry in `Replicator.fieldNames`.
 * @param constant the generated `FIELD_…` constant naming [index].
 * @param index the bit index, assigned by [FieldOrder] and by nothing else.
 * @param net `true` for `@Net` (in `netMask` and `allMask`), `false` for `@Sim` (in `allMask`
 *   only). Spec 3.1: a field in `netMask` but not `allMask` is a contradiction, so this is one
 *   flag and not two.
 * @param storage how the value is held in the `FieldStore`.
 * @param declaredType the property's Kotlin type, used for the `setField` cast.
 * @param enumEntries non-null when the property is an enum, in which case the ordinal is what
 *   is stored and this is the class the ordinal is decoded back through.
 */
internal data class ReplicatedField(
    val name: String,
    val constant: String,
    val index: Int,
    val net: Boolean,
    val storage: FieldStorage,
    val declaredType: TypeName,
    val enumEntries: ClassName?,
)

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
