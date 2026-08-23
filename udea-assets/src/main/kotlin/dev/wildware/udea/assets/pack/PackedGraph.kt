package dev.wildware.udea.assets.pack

import dev.wildware.udea.assets.AssetData
import dev.wildware.udea.assets.AssetId
import dev.wildware.udea.assets.AssetValue
import dev.wildware.udea.assets.Ref
import dev.wildware.udea.assets.ResPath
import dev.wildware.udea.assets.Vec2
import dev.wildware.udea.assets.assetRef
import kotlin.reflect.KClass

/**
 * A value as it came off disk, before a codec has said what it means.
 *
 * Distinct from [AssetValue] for one reason: a packed reference is an **integer**, and
 * `AssetValue.RefValue` holds a `Ref`, which cannot be constructed with its binding until the
 * [dev.wildware.udea.assets.AssetRegistry] exists. Decoding into the model type directly would
 * mean either building refs that resolve by string on first use - which is the cost this
 * format exists to remove - or a mutable half-built `Ref` visible to callers.
 */
internal sealed interface RawValue {

    data object Null : RawValue

    data class Bool(val value: Boolean) : RawValue

    data class I32(val value: Int) : RawValue

    data class I64(val value: Long) : RawValue

    data class F32(val value: Float) : RawValue

    data class Text(val value: String) : RawValue

    data class PathText(val value: String) : RawValue

    data class RefTo(val index: Int) : RawValue

    data class Vec(val x: Float, val y: Float) : RawValue

    data class Items(val values: List<RawValue>) : RawValue

    data class Fields(val values: Map<String, RawValue>) : RawValue
}

/**
 * Collects every `Ref` a codec builds, with the asset index the bundle recorded for it.
 *
 * Binding cannot happen while the codecs run: the target of a reference may be decoded after
 * the asset holding it, and the binding needs the registry's layout, which needs every value.
 * So the refs are parked here and bound in one pass at the end, which is also what makes
 * "resolution needs no string lookup" true of the *whole* graph rather than of whatever
 * happened to be resolved first.
 */
internal class RefBinder {

    private val refs = ArrayList<Ref<*>>()
    private val targets = ArrayList<Int>()

    val size: Int get() = refs.size

    fun <T : AssetData> create(id: AssetId, expected: KClass<T>, target: Int): Ref<T> {
        val ref = assetRef(id, expected)
        refs += ref
        targets += target
        return ref
    }

    fun bindAll(bind: (Ref<*>, Int) -> Unit) {
        for (i in refs.indices) bind(refs[i], targets[i])
    }
}

/**
 * The typed view of one packed record that an [AssetCodec] reads.
 *
 * Deliberately not a `Map<String, Any?>`: every accessor names the asset and the field when it
 * fails, so a bundle written by a newer compiler against an older runtime produces
 * "asset 'character/orc_idle' ... field 'columns' is a Text, not an Int" instead of a
 * `ClassCastException` with the field name nowhere in it.
 */
public class AssetFields internal constructor(
    /** The asset these fields belong to, for error messages and for [ref]'s identity. */
    public val id: AssetId,
    internal val raw: Map<String, RawValue>,
    internal val ids: List<AssetId>,
    internal val binder: RefBinder,
) {
    /** Field names present on this record, in the order the bundle stores them (sorted). */
    public val names: Set<String> get() = raw.keys

    public operator fun contains(name: String): Boolean = name in raw

    public fun int(name: String): Int = when (val value = required(name)) {
        is RawValue.I32 -> value.value
        else -> wrongType(name, value, "an Int")
    }

    public fun int(name: String, default: Int): Int = if (name in raw) int(name) else default

    public fun long(name: String, default: Long): Long = when (val value = raw[name]) {
        null, RawValue.Null -> default
        is RawValue.I64 -> value.value
        is RawValue.I32 -> value.value.toLong()
        else -> wrongType(name, value, "a Long")
    }

    public fun float(name: String): Float = when (val value = required(name)) {
        is RawValue.F32 -> value.value
        is RawValue.I32 -> value.value.toFloat()
        else -> wrongType(name, value, "a Float")
    }

    public fun float(name: String, default: Float): Float = if (name in raw) float(name) else default

    public fun bool(name: String, default: Boolean): Boolean = when (val value = raw[name]) {
        null, RawValue.Null -> default
        is RawValue.Bool -> value.value
        else -> wrongType(name, value, "a Boolean")
    }

    public fun text(name: String): String = when (val value = required(name)) {
        is RawValue.Text -> value.value
        is RawValue.PathText -> value.value
        else -> wrongType(name, value, "a String")
    }

    public fun text(name: String, default: String): String = if (name in raw) text(name) else default

    public fun textOrNull(name: String): String? = when (raw[name]) {
        null, RawValue.Null -> null
        else -> text(name)
    }

    public fun path(name: String): ResPath = ResPath(text(name))

    public fun pathOrNull(name: String): ResPath? = textOrNull(name)?.let { ResPath(it) }

    public fun vec(name: String, default: Vec2): Vec2 = when (val value = raw[name]) {
        null, RawValue.Null -> default
        is RawValue.Vec -> Vec2(value.x, value.y)
        else -> wrongType(name, value, "a Vec2")
    }

    /** The list at [name], or empty when the field is absent - never null, never a crash. */
    public fun list(name: String): List<AssetFields> = items(name).map { child(name, it) }

    public fun textList(name: String): List<String> = items(name).map {
        when (it) {
            is RawValue.Text -> it.value
            is RawValue.PathText -> it.value
            else -> wrongType(name, it, "a String")
        }
    }

    public fun pathList(name: String): List<ResPath> = textList(name).map { ResPath(it) }

    /** A typed reference. Registered with the binder, so the registry can patch its index. */
    public fun <T : AssetData> ref(name: String, expected: KClass<T>): Ref<T> =
        when (val value = required(name)) {
            is RawValue.RefTo -> binder.create(targetId(name, value.index), expected, value.index)
            else -> wrongType(name, value, "a Ref")
        }

    public fun <T : AssetData> refOrNull(name: String, expected: KClass<T>): Ref<T>? =
        when (raw[name]) {
            null, RawValue.Null -> null
            else -> ref(name, expected)
        }

    public fun <T : AssetData> refList(name: String, expected: KClass<T>): List<Ref<T>> =
        items(name).map { value ->
            when (value) {
                is RawValue.RefTo -> binder.create(targetId(name, value.index), expected, value.index)
                else -> wrongType(name, value, "a Ref")
            }
        }

    /**
     * Everything on this record as [AssetValue], for [OpaqueAsset].
     *
     * The refs it builds are registered with the binder too, so a game kind's references
     * resolve by index exactly like an engine kind's. An unparsed blob would not.
     */
    internal fun toAssetValues(): Map<String, AssetValue> =
        raw.mapValues { (name, value) -> toAssetValue(name, value) }

    private fun toAssetValue(name: String, value: RawValue): AssetValue = when (value) {
        // AssetValue has no null case by design - an absent field is absent. An explicit null
        // survives as an empty struct rather than being silently dropped, so a round trip
        // through OpaqueAsset does not change the field set.
        RawValue.Null -> AssetValue.StructValue(emptyMap())
        is RawValue.Bool -> AssetValue.BoolValue(value.value)
        is RawValue.I32 -> AssetValue.IntValue(value.value)
        is RawValue.I64 -> AssetValue.LongValue(value.value)
        is RawValue.F32 -> AssetValue.FloatValue(value.value)
        is RawValue.Text -> AssetValue.TextValue(value.value)
        is RawValue.PathText -> AssetValue.PathValue(ResPath(value.value))
        is RawValue.Vec -> AssetValue.VecValue(Vec2(value.x, value.y))
        is RawValue.RefTo ->
            AssetValue.RefValue(binder.create(targetId(name, value.index), AssetData::class, value.index))
        is RawValue.Items -> AssetValue.ListValue(value.values.map { toAssetValue(name, it) })
        is RawValue.Fields ->
            AssetValue.StructValue(value.values.mapValues { toAssetValue(it.key, it.value) })
    }

    private fun items(name: String): List<RawValue> = when (val value = raw[name]) {
        null, RawValue.Null -> emptyList()
        is RawValue.Items -> value.values
        else -> wrongType(name, value, "a List")
    }

    private fun child(name: String, value: RawValue): AssetFields = when (value) {
        is RawValue.Fields -> AssetFields(id, value.values, ids, binder)
        else -> wrongType(name, value, "a nested record")
    }

    private fun targetId(name: String, index: Int): AssetId = ids.getOrNull(index)
        ?: throw AssetDecodeException(
            id.value,
            "field '$name' references asset index $index, but the graph holds ${ids.size} assets",
        )

    private fun required(name: String): RawValue = raw[name]?.takeIf { it != RawValue.Null }
        ?: throw AssetDecodeException(
            id.value,
            "field '$name' is missing; the record has ${raw.keys.sorted()}",
        )

    private fun wrongType(name: String, value: RawValue, wanted: String): Nothing =
        throw AssetDecodeException(
            id.value,
            "field '$name' is a ${value::class.simpleName}, not $wanted",
        )
}
