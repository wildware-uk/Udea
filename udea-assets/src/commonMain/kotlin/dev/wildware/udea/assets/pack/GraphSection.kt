package dev.wildware.udea.assets.pack

import dev.wildware.udea.assets.AssetData
import dev.wildware.udea.assets.AssetId

/**
 * One asset as the bundle stores it: an id, a kind name, and a tagged field tree.
 *
 * `index` is its position in the graph, which is what every packed `Ref` holds and what an
 * `AssetIndex` in a snapshot means.
 */
internal class PackedRecord(
    val index: Int,
    val id: AssetId,
    val kind: String,
    val fields: Map<String, RawValue>,
)

/**
 * Decodes the `graph` section: a string table, then the asset table in ascending id order.
 *
 * One linear pass, no back-references, no seeking. The 15ms budget issue #89 sets for the
 * example corpus is met by not doing anything cleverer than that - the string table exists so
 * that `character/orc_elite` is one UTF-8 decode however many records name it, which is the
 * only optimisation here that is not just "read the bytes in order".
 */
internal object GraphSection {

    fun decode(bytes: ByteArray, codecs: AssetCodecs): DecodedGraph {
        val cursor = ByteCursor(bytes, BundleFormat.GRAPH_SECTION)

        val strings = Array(cursor.count("string table", bytesEach = Int.SIZE_BYTES)) {
            cursor.utf8(cursor.count("string"))
        }
        fun stringAt(kind: String): String {
            val at = cursor.i32()
            return strings.getOrNull(at)
                ?: cursor.corrupt("$kind names string $at of ${strings.size}")
        }

        val assetCount = cursor.count("asset", bytesEach = MIN_ASSET_BYTES)
        val ids = ArrayList<AssetId>(assetCount)
        val kinds = ArrayList<String>(assetCount)
        val fieldSets = ArrayList<Map<String, RawValue>>(assetCount)

        fun value(depth: Int): RawValue {
            if (depth > MAX_DEPTH) cursor.corrupt("value nesting deeper than $MAX_DEPTH")
            return when (val tag = cursor.u8()) {
                ValueTag.NULL -> RawValue.Null
                ValueTag.BOOL -> RawValue.Bool(cursor.u8() != 0)
                ValueTag.INT -> RawValue.I32(cursor.i32())
                ValueTag.LONG -> RawValue.I64(cursor.i64())
                ValueTag.FLOAT -> RawValue.F32(cursor.f32())
                ValueTag.TEXT -> RawValue.Text(stringAt("a text value"))
                ValueTag.PATH -> RawValue.PathText(stringAt("a path value"))
                ValueTag.REF -> RawValue.RefTo(cursor.i32())
                ValueTag.VEC -> RawValue.Vec(cursor.f32(), cursor.f32())
                ValueTag.LIST -> RawValue.Items(List(cursor.count("list element", bytesEach = 1)) { value(depth + 1) })
                ValueTag.STRUCT -> RawValue.Fields(
                    buildMap {
                        repeat(cursor.count("struct field", bytesEach = MIN_FIELD_BYTES)) {
                            put(stringAt("a struct field name"), value(depth + 1))
                        }
                    },
                )
                else -> cursor.corrupt(
                    "value tag $tag is not one this build knows (0..${ValueTag.MAX}); the " +
                        "bundle's format version claimed to be readable but its values are not",
                )
            }
        }

        repeat(assetCount) { index ->
            val id = AssetId(stringAt("asset $index's id"))
            val kind = stringAt("asset '$id's kind")
            val fields = buildMap<String, RawValue> {
                repeat(cursor.count("field", bytesEach = MIN_FIELD_BYTES)) {
                    put(stringAt("a field name of '$id'"), value(0))
                }
            }
            ids += id
            kinds += kind
            fieldSets += fields
        }
        if (cursor.remaining != 0) {
            cursor.corrupt("${cursor.remaining} trailing byte(s) after the last asset")
        }

        val binder = RefBinder()
        val values = Array<AssetData>(assetCount) { index ->
            val fields = AssetFields(ids[index], fieldSets[index], ids, binder)
            when (val codec = codecs[kinds[index]]) {
                null -> OpaqueAsset(ids[index], kinds[index], fields.toAssetValues())
                else -> codec.decode(fields).also { decoded ->
                    if (decoded.id != ids[index]) {
                        throw AssetDecodeException(
                            ids[index].value,
                            "its codec for '${kinds[index]}' produced an asset named " +
                                "'${decoded.id}'; a codec may not rename what it decodes",
                        )
                    }
                }
            }
        }
        return DecodedGraph(values, binder, ids, kinds)
    }

    /**
     * Nesting cap.
     *
     * Not a design limit - a `StackOverflowError` from a hostile or corrupt file is a crash the
     * `BundleCorruptException` contract promises not to produce, and recursion is the clearest
     * way to write this decoder.
     */
    const val MAX_DEPTH: Int = 64

    /** id index + kind index + field count: the smallest an asset record can be. */
    private const val MIN_ASSET_BYTES = 4 + 4 + 4

    /** name index + a one-byte tag: the smallest a field can be. */
    private const val MIN_FIELD_BYTES = 4 + 1
}

internal class DecodedGraph(
    val values: Array<AssetData>,
    val binder: RefBinder,
    val ids: List<AssetId>,
    val kinds: List<String>,
)
