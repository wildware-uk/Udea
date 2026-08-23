package dev.wildware.udea.assets.compiler.worker

import dev.wildware.udea.assets.compiler.AssetGraph
import dev.wildware.udea.assets.compiler.DeclaredAsset
import dev.wildware.udea.assets.compiler.Ref
import dev.wildware.udea.assets.compiler.ResFile
import dev.wildware.udea.assets.compiler.scan.ReferenceSite
import dev.wildware.udea.assets.compiler.scan.ReferenceSpanIndex
import dev.wildware.udea.diagnostics.Severity
import dev.wildware.udea.diagnostics.SourceSpan
import dev.wildware.udea.diagnostics.UdeaDiagnostic
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable
import java.nio.file.Path
import kotlin.io.path.inputStream
import kotlin.io.path.outputStream

/**
 * What crosses the process boundary between [IsolatedAssetCompiler] and the worker.
 *
 * Java serialization over a temporary file, deliberately, with an **explicit** conversion at
 * each end rather than marking the compiler's own model `Serializable`:
 *
 * - `SourceSpan` lives in `udea-diagnostics`, a zero-dependency leaf this module does not get
 *   to change, and it is not `Serializable`. A wire type here is the alternative to reaching
 *   into another module for a marker interface.
 * - The conversion is where the *constraint* on asset field values is enforced. A field may
 *   hold a string, a number, a boolean, a [Ref], a list or a map, and [encodeValue] rejects
 *   anything else by name — so a DSL that starts putting a live object in a field fails with
 *   "field `sheet` of `character/orc` holds a Foo" instead of a `NotSerializableException`
 *   naming an anonymous class.
 *
 * A file rather than the worker's stdout because stdout is where the Kotlin compiler writes,
 * and mixing a payload into a stream something else also writes to is how a protocol acquires
 * an unreproducible failure mode.
 */
public data class WorkerRequest(
    val repoRoot: String,
    val assetRoot: String,
    val scriptClasspath: List<String>,
    val cacheDirectory: String,
    val files: List<String>,
    val captureOrigins: Boolean,
    /** Pass 1's reference spans, flattened; rebuilt into a [ReferenceSpanIndex] in the worker. */
    val referenceSpans: List<SpanRecord>,
) : Serializable {
    public companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/** The worker's answer: a graph and diagnostics, both in wire form. */
public data class WorkerResponse(
    val assets: List<AssetRecord>,
    val diagnostics: List<DiagnosticRecord>,
    val cacheHits: Int,
) : Serializable {
    public companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/** A [SourceSpan] plus the reference target it belongs to. */
public data class SpanRecord(
    val target: String,
    val path: String,
    val startLine: Int,
    val startColumn: Int,
    val endLine: Int,
    val endColumn: Int,
    val from: String?,
) : Serializable {
    public companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/** A [DeclaredAsset] in wire form; [fields] holds only [encodeValue]-approved values. */
public data class AssetRecord(
    val kind: String,
    /**
     * [DeclaredAsset.kindFqn]. Carried explicitly rather than derived on the far side: the
     * worker JVM has the same classes, but a kind is allowed to be a *game's* own
     * `AssetData`, and reconstructing an FQN from the DSL word is exactly the guess
     * `AssetKind` exists to forbid.
     */
    val kindFqn: String?,
    val id: String,
    val fields: LinkedHashMap<String, Any?>,
    val origin: SpanRecord?,
) : Serializable {
    public companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/** A [Ref] in wire form. */
public data class RefRecord(
    val id: String,
    val origin: SpanRecord?,
    /** [Ref.expected]: the kind the slot requires, or null when it does not constrain one. */
    val expected: String? = null,
) : Serializable {
    public companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/** A [UdeaDiagnostic] in wire form. */
public data class DiagnosticRecord(
    val severity: String,
    val ruleId: String,
    val message: String,
    val span: SpanRecord?,
    val assetId: String?,
    val causedBy: String?,
) : Serializable {
    public companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/** A [ResFile] in wire form. Carried as its own record so a path stays distinguishable from a name. */
public data class ResFileRecord(val value: String) : Serializable {
    public companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/** Thrown when an asset field holds something the wire format has no representation for. */
public class UnencodableAssetValue(
    public val assetId: String,
    public val field: String,
    public val value: Any,
) : IllegalArgumentException(
    "field `$field` of asset `$assetId` holds a ${value.javaClass.name}, which the asset " +
        "compiler's worker protocol cannot carry. Asset fields may hold strings, numbers, " +
        "booleans, references, resource paths, lists and maps of those, and nothing else.",
)

// --- conversions ---------------------------------------------------------------------------

internal fun SourceSpan.toRecord(target: String = "", from: String? = null): SpanRecord =
    SpanRecord(target, path, startLine, startColumn, endLine, endColumn, from)

internal fun SpanRecord.toSpan(): SourceSpan =
    SourceSpan(path, startLine, startColumn, endLine, endColumn)

internal fun List<SpanRecord>.toReferenceSpanIndex(): ReferenceSpanIndex =
    ReferenceSpanIndex(map { ReferenceSite(it.target, it.toSpan(), it.from) })

internal fun DeclaredAsset.toRecord(): AssetRecord = AssetRecord(
    kind = kind,
    kindFqn = kindFqn,
    id = id,
    fields = LinkedHashMap(fields.mapValues { encodeValue(id, it.key, it.value) }),
    origin = origin?.toRecord(),
)

internal fun AssetRecord.toAsset(): DeclaredAsset = DeclaredAsset(
    kind = kind,
    kindFqn = kindFqn,
    id = id,
    fields = LinkedHashMap(fields.mapValues { decodeValue(it.value) }),
    origin = origin?.toSpan(),
)

internal fun UdeaDiagnostic.toRecord(): DiagnosticRecord =
    DiagnosticRecord(severity.name, ruleId, message, span?.toRecord(), assetId, causedBy)

internal fun DiagnosticRecord.toDiagnostic(): UdeaDiagnostic = UdeaDiagnostic(
    severity = Severity.valueOf(severity),
    ruleId = ruleId,
    message = message,
    span = span?.toSpan(),
    assetId = assetId,
    causedBy = causedBy,
)

/** The graph a worker's [WorkerResponse] describes. */
public fun WorkerResponse.toGraph(): AssetGraph = AssetGraph.of(toDeclared())

/**
 * Every declaration the worker made, **in declaration order and duplicates included**.
 *
 * [toGraph] keys by id and therefore cannot show a duplicate; this can, which is what
 * `DuplicateIdValidator` needs.
 */
public fun WorkerResponse.toDeclared(): List<DeclaredAsset> = assets.map { it.toAsset() }

/** The diagnostics a worker's [WorkerResponse] describes. */
public fun WorkerResponse.toDiagnostics(): List<UdeaDiagnostic> = diagnostics.map { it.toDiagnostic() }

private fun encodeValue(assetId: String, field: String, value: Any?): Any? = when (value) {
    null, is String, is Boolean, is Int, is Long, is Float, is Double -> value
    is Ref -> RefRecord(value.id, value.origin?.toRecord(value.id), value.expected)
    is ResFile -> ResFileRecord(value.value)
    is List<*> -> ArrayList(value.map { encodeValue(assetId, field, it) })
    is Map<*, *> -> LinkedHashMap<Any?, Any?>().apply {
        value.forEach { (k, v) -> put(encodeValue(assetId, field, k), encodeValue(assetId, field, v)) }
    }
    else -> throw UnencodableAssetValue(assetId, field, value)
}

private fun decodeValue(value: Any?): Any? = when (value) {
    is RefRecord -> Ref(value.id, value.origin?.toSpan(), value.expected)
    is ResFileRecord -> ResFile(value.value)
    is List<*> -> value.map(::decodeValue)
    is Map<*, *> -> LinkedHashMap<Any?, Any?>().apply {
        value.forEach { (k, v) -> put(decodeValue(k), decodeValue(v)) }
    }
    else -> value
}

internal fun writeObject(path: Path, value: Serializable) {
    ObjectOutputStream(path.outputStream().buffered()).use { it.writeObject(value) }
}

internal fun <T> readObject(path: Path): T {
    @Suppress("UNCHECKED_CAST")
    return ObjectInputStream(path.inputStream().buffered()).use { it.readObject() } as T
}
