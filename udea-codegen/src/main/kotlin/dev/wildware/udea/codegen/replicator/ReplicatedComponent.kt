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

    /**
     * An enum, stored and sent as its ordinal.
     *
     * The ordinal alone is not the whole wire contract: the *constant list* is, because
     * `capture` writes `.ordinal` and `apply` reads `entries[ordinal]`. [ReplicatedField]
     * folds the constants into the lock token for that reason.
     */
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

    /**
     * `q:12:-3.1416:3.1416` — the token `net-protocol.lock` records for a quantised field.
     *
     * **The range is part of the token, not decoration.** `bits` alone fixes how many bits a
     * field costs; `min` and `max` fix what those bits *mean*, because `writeFixed` maps
     * `[min, max]` onto `0 until 2^bits` and `readFixed` maps it back. Widening `@Q(bits =
     * 12, min = -3.1416f, max = 3.1416f)` to `@Q(bits = 12, min = -100f, max = 100f)` leaves
     * the bit layout of every packet untouched and changes what every one of those packets
     * means — so a token of `q:12` would leave `protoHash` identical across a full-scale wire
     * break, with the connect-time check reporting agreement.
     *
     * The bounds are rendered with `Float.toString`, which is round-trip exact and locale
     * independent, so the token stays a pure function of the declaration.
     */
    val wireToken: String get() = "q:$bits:$min:$max"
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
 * @param enumConstants non-null when the field is an enum: its constants in ordinal order,
 *   which is the mapping the wire actually carries.
 * @param quantisation non-null when the property carried `@Q`; only ever set on a `Float`.
 * @param createOnly `true` for `@Net(lifetime = OnCreate)`: the field rides a `Create` and a
 *   full resend and is stripped from every delta (issue #114). Only ever `true` when [net] is,
 *   because a `@Sim` field never reaches a packet of any kind and "written once on a wire it
 *   is never on" is not a statement about anything.
 * @param ownerOnly `true` for `@Net(visibility = OwnerOnly)`: the field reaches the connection
 *   that owns the entity and is stripped from every other recipient's packet, create included
 *   (issue #167). Only ever `true` when [net] is, for [createOnly]'s reason. Independent of
 *   [createOnly]: one says when a field is sent, the other says to whom.
 */
internal data class ReplicatedField(
    val path: List<String>,
    val constant: String,
    val index: Int,
    val net: Boolean,
    val storage: FieldStorage,
    val declaredType: TypeName,
    val enumEntries: ClassName?,
    val enumConstants: List<String>?,
    val quantisation: Quantisation?,
    val createOnly: Boolean = false,
    val ownerOnly: Boolean = false,
) {
    /** The Kotlin property path; also the entry in `Replicator.fieldNames`. */
    val name: String = path.joinToString(".")

    /**
     * What `net-protocol.lock` records for this field, and therefore what `protoHash` covers.
     *
     * The rule is that **every declaration a peer must agree on appears here**, not merely
     * the field's width. What the two peers must agree on is what the bits *mean*, and a
     * declaration can change that while moving no bit at all:
     *
     * - a `@Q` range change reinterprets every packet, so the bounds are in the token
     *   (`q:12:-3.1416:3.1416`);
     * - reordering an enum's constants remaps every ordinal, so the constants are in the token
     *   (`enum:32:Standing,Crouching,Sprinting`);
     * - `lifetime` changes which packets carry the field, so `:oncreate` is in the token;
     * - `visibility` changes which recipients carry it, so `:owneronly` is in the token.
     *
     * Every one of those was invisible to a token of `q:12` / `enum:32`, which is one defect in
     * several places: a lock that pins the layout and not the meaning.
     */
    val wireDescription: String = buildString {
        when {
            quantisation != null -> append(quantisation.wireToken)
            enumConstants != null ->
                append("${storage.wireToken}:${storage.wireBits}:${enumConstants.joinToString(",")}")
            else -> append("${storage.wireToken}:${storage.wireBits}")
        }
        // Lifetime is part of the token for the same reason the `@Q` range is: it moves no bit
        // in any single packet and changes what the *stream* means. A peer that thinks `teamId`
        // is `Always` expects it in deltas; a peer that thinks it is `OnCreate` never sends it
        // there. Both decode every packet the other sends without complaint and disagree about
        // the value for the rest of the match, so it has to reach `protoHash`.
        if (createOnly) append(":oncreate")
        // Visibility is part of the token for lifetime's reason, and the argument is worth
        // stating rather than inheriting: a peer that thinks `gold` is `All` expects the server
        // to send it for every champion; a peer that thinks it is `OwnerOnly` expects it for
        // one. Both decode every packet the other sends without complaint, and the difference is
        // a permanent disagreement about what a client is owed. After `:oncreate` and never
        // before it, because the token is hashed: two builds that agreed about the field and
        // spelled its description in the other order would refuse each other's handshake.
        if (ownerOnly) append(":owneronly")
    }
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

    /**
     * The `@Net(lifetime = OnCreate)` fields, in bit-index order.
     *
     * Empty for all but a handful of components, and that emptiness is load-bearing: the
     * generated object only implements `udea-net`'s `CreateOnlyFields` when this is non-empty,
     * so a module with no create-only field never gains a reference to `udea-net` it did not
     * already have.
     */
    val createOnlyFields: List<ReplicatedField> get() = fields.filter { it.createOnly }

    /**
     * The `@Net(visibility = OwnerOnly)` fields, in bit-index order.
     *
     * Empty for all but a handful of components, and that emptiness is load-bearing for the same
     * reason [createOnlyFields]' is: the generated object only implements `udea-net`'s
     * `OwnerOnlyFields` when this is non-empty, so a module with no owner-only field never gains
     * a reference to `udea-net` it did not already have — and no packet gains a byte.
     */
    val ownerOnlyFields: List<ReplicatedField> get() = fields.filter { it.ownerOnly }
}
