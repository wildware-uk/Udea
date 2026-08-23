package dev.wildware.udea.assets.compiler

import dev.wildware.udea.assets.Blueprint
import dev.wildware.udea.assets.GameConfig
import dev.wildware.udea.assets.Level
import dev.wildware.udea.assets.SoundCue
import dev.wildware.udea.assets.SpriteAnimation
import dev.wildware.udea.assets.SpriteSheet
import dev.wildware.udea.diagnostics.SourceSpan

/**
 * A `reference("id")` as it exists during evaluation: an id and, when it could be captured,
 * where in source the author wrote it.
 *
 * The compiler-side reference is deliberately *not* resolved. Pass 2 evaluates scripts before
 * pass 3 has decided whether every id exists, and a `Ref` that resolved eagerly would turn a
 * typo into an exception thrown from the middle of a script — the failure mode spec 3.6 exists
 * to delete. It carries an id and a span, and pass 3 decides.
 */
public data class Ref(
    public val id: String,
    /**
     * Where the author wrote it. Populated from a captured stack frame when
     * [UdeaBuildContext.captureOrigins] is set, and otherwise filled in from the pass-1
     * [dev.wildware.udea.assets.compiler.scan.ReferenceSpanIndex], which is the guaranteed
     * fallback (issue #86).
     */
    public val origin: SourceSpan? = null,
    /**
     * Fully qualified name of the [dev.wildware.udea.assets.AssetData] the *slot* this
     * reference was passed to requires, or `null` when the slot does not constrain it.
     *
     * This is the compiler-side half of `reference<Blueprint>("...")`. An author writes
     * `reference("id")` with no type argument - spec 3.6 keeps scripts on plain string ids -
     * so the expectation cannot come from the call. It comes from the *parameter*: the DSL
     * function that accepts the reference stamps it with [expecting], right at the signature
     * that states what belongs there. There is no table of DSL word to type to keep in step,
     * which is the only reason it will stay in step - the same argument [AssetKind] makes.
     *
     * `null` is a real answer and not a gap to be filled in later: `gameConfig`'s
     * `defaultCharacter` points at a `character`, and [AssetKind.Unpublishable] means there is
     * no runtime type to compare against. [dev.wildware.udea.assets.compiler.validate.ReferenceTypeValidator]
     * stays silent there rather than inventing one.
     */
    public val expected: String? = null,
) {
    /** This reference, recorded as having to point at a [T]. See [expected]. */
    public fun expecting(type: kotlin.reflect.KClass<out dev.wildware.udea.assets.AssetData>): Ref =
        copy(expected = type.qualifiedName)
}

/**
 * This reference, recorded as having to point at a [T]. See [Ref.expected].
 *
 * An extension rather than a member so the common `null`-able case (`parent: Ref?`) reads as
 * `parent?.expecting<Blueprint>()` without a second overload.
 */
public inline fun <reified T : dev.wildware.udea.assets.AssetData> Ref.expecting(): Ref =
    expecting(T::class)

/**
 * A path to a file inside the asset root, as a script wrote it.
 *
 * A distinct type and not a `String` for exactly one reason:
 * [dev.wildware.udea.assets.compiler.validate.MissingFileValidator] has to find every path a
 * declaration holds, and a `String` field is indistinguishable from a name, a tag or a
 * component type. Marking it at the DSL signature that accepts it means a kind added later
 * cannot forget to register its path fields in a table somewhere else, because there is no
 * table.
 *
 * [value] is normalised the way [dev.wildware.udea.assets.ResPath] normalises: the leading `/`
 * an author writes is stripped, `\` becomes `/`, and repeated separators collapse - so that
 * `"/sprites/orc/idle.png"` in a script and `sprites/orc/idle.png` in a loader are one value
 * rather than the two map keys that bug was (issue #84). Unlike `ResPath` this **never
 * throws**: a blank or `..`-escaping path is kept verbatim and reported as a diagnostic by the
 * validator, because a script that throws during pass 2 costs the author every other asset in
 * the file.
 */
public data class ResFile(public val value: String) {

    /** True when the path escaped the asset root or named nothing; the validator reports it. */
    public val isMalformed: Boolean
        get() = value.isBlank() || value.split('/').any { it == ".." || it == "." }

    override fun toString(): String = value

    public companion object {
        /** [raw] normalised. Never throws; see the class KDoc. */
        public fun of(raw: String): ResFile = ResFile(
            raw.replace('\\', '/').split('/').filter { it.isNotEmpty() }.joinToString("/"),
        )
    }
}

/**
 * One asset declared by a script, as pass 2 produced it.
 *
 * [fields] is an ordered map rather than a per-kind data class because this module is the
 * *compiler*: the runtime model (`udea-assets`, issue #84) is what gets typed kinds. Keeping
 * the compiler-side value generic is what lets [AssetGraph] equality be an honest structural
 * comparison in `TranspilerParityTest` — two front ends producing "the same graph" means the
 * same ids, kinds and field values, and a map compares exactly that.
 */
public data class DeclaredAsset(
    public val kind: String,
    /**
     * Fully qualified name of the [dev.wildware.udea.assets.AssetData] this declaration yields,
     * or `null` when the provisional DSL has no runtime type for it.
     *
     * This - not [kind] - is what the compile-time catalog publishes and what
     * `udea-compiler-plugin`'s FIR checker resolves. It is a `String` here because it crosses
     * the worker boundary as one; it is only ever *produced* from a `KClass` by [AssetKind],
     * so it cannot drift from a type that exists. See `docs/contracts/asset-index.md`.
     */
    public val kindFqn: String?,
    public val id: String,
    public val fields: Map<String, Any?>,
    /** Where the declaration was written, when pass 1 or an origin capture could say. */
    public val origin: SourceSpan? = null,
) {
    /** Everything this asset points at, in declaration order. */
    public val referencedIds: List<String>
        get() = fields.values.flatMap { refsIn(it) }.map { it.id }

    private fun refsIn(value: Any?): List<Ref> = when (value) {
        is Ref -> listOf(value)
        is Iterable<*> -> value.flatMap { refsIn(it) }
        is Map<*, *> -> value.values.flatMap { refsIn(it) }
        else -> emptyList()
    }
}

/**
 * The output of pass 2: every asset every script declared, keyed by id.
 *
 * Equality is structural and order-independent over [assets], which is what
 * `TranspilerParityTest` compares. [origin] spans are deliberately excluded from that
 * comparison — see [sameContentAs] — because the two front ends legitimately disagree about
 * *where* a declaration lives (one points into `.udea.kts`, the other into generated `.kt`)
 * while agreeing completely about what it declares.
 */
public data class AssetGraph(
    public val assets: Map<String, DeclaredAsset>,
) {
    public val ids: Set<String> get() = assets.keys

    /** Same ids, same kinds, same field values — ignoring where each was declared. */
    public fun sameContentAs(other: AssetGraph): Boolean = stripOrigins() == other.stripOrigins()

    /** The differences [sameContentAs] found, as human-readable lines. Empty when equal. */
    public fun contentDiff(other: AssetGraph): List<String> {
        val mine = stripOrigins()
        val theirs = other.stripOrigins()
        val lines = mutableListOf<String>()
        (mine.keys - theirs.keys).sorted().forEach { lines += "only in left: $it" }
        (theirs.keys - mine.keys).sorted().forEach { lines += "only in right: $it" }
        (mine.keys intersect theirs.keys).sorted().forEach { id ->
            if (mine[id] != theirs[id]) lines += "differs: $id\n  left = ${mine[id]}\n  right = ${theirs[id]}"
        }
        return lines
    }

    private fun stripOrigins(): Map<String, DeclaredAsset> =
        assets.mapValues { (_, a) -> a.copy(origin = null, fields = a.fields.mapValues(::strip)) }

    private fun strip(entry: Map.Entry<String, Any?>): Any? = stripValue(entry.value)

    private fun stripValue(value: Any?): Any? = when (value) {
        is Ref -> value.copy(origin = null)
        is List<*> -> value.map(::stripValue)
        is Map<*, *> -> value.mapValues { stripValue(it.value) }
        else -> value
    }

    public companion object {
        public val EMPTY: AssetGraph = AssetGraph(emptyMap())

        /** Merges per-file results, last writer wins on a duplicate id (pass 3 reports those). */
        public fun of(assets: List<DeclaredAsset>): AssetGraph =
            AssetGraph(assets.associateByTo(LinkedHashMap()) { it.id })
    }
}

/**
 * Process-wide switches pass 2 reads.
 *
 * A mutable global is normally the wrong shape, and it is here too — but the alternative is
 * threading a context through the *script's* implicit receiver and therefore into the authored
 * DSL, and the whole point of `reference("id")` is that an author writes exactly that and
 * nothing else. It is set by [AssetCompiler] around one evaluation and restored afterwards.
 */
public object UdeaBuildContext {
    /**
     * When set, [AssetScope.reference] pays for a `Throwable().stackTrace[0]` to record where
     * the call was written.
     *
     * Off by default because it costs a stack capture per reference and pass 1 already knows
     * every span; on when a caller wants origins for references the syntactic scan could not
     * attribute (a `reference` written inside a helper function in game source, which pass 1
     * never sees).
     */
    public var captureOrigins: Boolean = false

    /**
     * Repo-relative path of the script being evaluated, used to turn a captured stack frame
     * into a [SourceSpan]. Null outside an evaluation.
     */
    public var currentScript: String? = null
}

/**
 * The implicit receiver every `.udea.kts` is compiled against, and the interface the
 * transpiled `.kt` front end calls into (issues #86, #87).
 *
 * The file *is* the bundle: there is no `bundle { }` wrapper and no return value, so the
 * "script evaluated and returned a non-Asset object" failure mode has nowhere to live.
 * Multiple named assets per file are preserved — each DSL call appends one.
 *
 * ### Provisional
 *
 * These declaration functions stand in for the generated DSL that issue #84 owns in
 * `udea-assets`. They are modelled on the real kinds in `example/src/main/resources/assets`
 * and are deliberately narrow: this module needed *a* receiver to compile scripts against
 * before #84 landed one, and inventing a wide DSL here would be inventing the thing #84 is
 * for. See the report for the seam.
 */
public class AssetScope(
    /**
     * The id prefix for declarations in this file: the script's directory relative to the
     * asset root, e.g. `character`. Empty for a script at the root.
     */
    public val idPrefix: String,
    /** The script's own file name, used when a declaration carries no name literal. */
    public val defaultName: String,
) {
    private val declared = mutableListOf<DeclaredAsset>()

    /** Everything declared so far, in declaration order. */
    public val assets: List<DeclaredAsset> get() = declared.toList()

    /** `prefix/name`, or just `name` for a script at the asset root. */
    public fun idOf(name: String): String = if (idPrefix.isEmpty()) name else "$idPrefix/$name"

    /**
     * Records a reference to another asset by id.
     *
     * Never resolves and never throws: an id nothing declares is pass 3's diagnostic, raised
     * with a span, not an exception raised from inside an author's script.
     */
    public fun reference(id: String): Ref = Ref(id, captureOrigin())

    // --- declaration kinds -------------------------------------------------------------

    public fun spriteSheet(
        name: String,
        spritePath: String,
        rows: Int = 1,
        columns: Int = 1,
        scale: Float = 1f,
    ): Unit = declare(
        AssetKind.of<SpriteSheet>(),
        "spriteSheet",
        name,
        "spritePath" to resPath(spritePath),
        "rows" to rows,
        "columns" to columns,
        "scale" to scale,
    )

    public fun soundCue(
        name: String,
        pitchVariance: Float = 0f,
        volume: Float = 1f,
        sounds: List<String> = emptyList(),
    ): Unit = declare(
        AssetKind.of<SoundCue>(),
        "soundCue",
        name,
        "pitchVariance" to pitchVariance,
        "volume" to volume,
        "sounds" to sounds.map(::resPath),
    )

    /**
     * @param notifies notify name to zero-based frame index into [sheet]'s grid. A map rather
     *   than a list of a `Notify` type because a notify is matched by name and a name may only
     *   appear once (`SpriteAnimation`'s own `init` says so), and because a map of `String` to
     *   `Int` already crosses the worker boundary without a new wire record.
     */
    public fun spriteAnimation(
        name: String,
        sheet: Ref,
        loop: Boolean = true,
        interruptable: Boolean = true,
        notifies: Map<String, Int> = emptyMap(),
    ): Unit = declare(
        AssetKind.of<SpriteAnimation>(),
        "spriteAnimation",
        name,
        "sheet" to sheet.expecting<SpriteSheet>(),
        "loop" to loop,
        "interruptable" to interruptable,
        "notifies" to LinkedHashMap(notifies),
    )

    public fun character(
        name: String,
        size: Float = 1f,
        health: Float = 100f,
        animations: List<Ref> = emptyList(),
        sounds: Map<String, Ref> = emptyMap(),
    ): Unit = declare(
        // No runtime kind: `udea-assets` has no `Character`. Reported by `AssetGraph.toCatalog`
        // rather than guessed at - see `AssetKind.Unpublishable`.
        AssetKind.Unpublishable("character"),
        "character",
        name,
        "size" to size,
        "health" to health,
        "animations" to animations.map { it.expecting<SpriteAnimation>() },
        "sounds" to sounds.mapValues { it.value.expecting<SoundCue>() },
    )

    public fun blueprint(name: String, parent: Ref? = null, components: List<String> = emptyList()): Unit =
        declare(
            AssetKind.of<Blueprint>(),
            "blueprint",
            name,
            "parent" to parent?.expecting<Blueprint>(),
            "components" to components,
        )

    public fun level(name: String = defaultName, entities: List<Ref> = emptyList()): Unit =
        declare(AssetKind.of<Level>(), "level", name, "entities" to entities.map { it.expecting<Blueprint>() })

    /**
     * `defaultCharacter` is deliberately **not** stamped with an expected kind: it points at a
     * `character`, which is [AssetKind.Unpublishable], so there is no `AssetData` type to
     * compare a resolved declaration against. See [Ref.expected].
     */
    public fun gameConfig(name: String = defaultName, defaultCharacter: Ref): Unit =
        declare(AssetKind.of<GameConfig>(), "gameConfig", name, "defaultCharacter" to defaultCharacter)

    /**
     * The generic escape for a kind this provisional DSL does not model.
     *
     * Present so a fixture can exercise a kind without this class growing a function per kind
     * before #84 decides what the kinds are.
     */
    public fun asset(kind: String, name: String, vararg fields: Pair<String, Any?>): Unit =
        declare(AssetKind.Unpublishable(kind), kind, name, *fields)

    /**
     * Marks [path] as a file inside the asset root, so the validator checks that it is there.
     *
     * The typed kinds call this for you. It is public for [asset], the generic escape: a
     * particle file or a skin declared through `asset("particle", ..., "file" to
     * resource("effects/blood.p"))` is checked by
     * [dev.wildware.udea.assets.compiler.validate.MissingFileValidator] exactly like a sprite
     * sheet's texture, without this class first growing a `particle` function.
     */
    public fun resource(path: String): ResFile = ResFile.of(path)

    private fun declare(
        type: AssetKind,
        kind: String,
        name: String,
        vararg fields: Pair<String, Any?>,
    ) {
        declared += DeclaredAsset(kind, type.fqn, idOf(name), linkedMapOf(*fields), captureOrigin())
    }

    /** Strips the leading `/` that authors write and loaders then fail to strip (issue #84). */
    private fun resPath(path: String): ResFile = ResFile.of(path)

    /**
     * The stack frame of the *author's* call, or null.
     *
     * Frame 0 is this function, frame 1 the DSL function, frame 2 the caller. That is
     * arithmetic on an implementation detail, so it is guarded: any frame that is not in the
     * script currently being evaluated yields null and the pass-1 span index takes over.
     */
    public companion object {
        /**
         * The names of [AssetScope]'s callable members.
         *
         * Consumed by the transpiler (issue #87), which qualifies a call to one of these as
         * `scope.` when rewriting a script's implicit-receiver body into a
         * `fun build(scope: AssetScope)`. A syntactic front end has no resolver, so it needs
         * the vocabulary as data.
         *
         * Hand-written and then *checked* against the class by `AssetScopeApiTest` rather than
         * derived by reflection at run time: the transpiler must work in a build where
         * `kotlin-reflect` is not on the classpath, and a list that silently misses a newly
         * added DSL function would produce output that compiles and calls the wrong thing.
         */
        public val MEMBER_NAMES: Set<String> = setOf(
            "reference",
            "spriteSheet",
            "soundCue",
            "spriteAnimation",
            "character",
            "blueprint",
            "level",
            "gameConfig",
            "asset",
            "resource",
            "idOf",
        )

        /**
         * Members that are *properties*, not functions.
         *
         * The transpiler cannot qualify these by rewriting a call, because there is no call to
         * rewrite - so a script that reads one is reported as unsupported rather than silently
         * emitted with an unresolved name.
         */
        public val PROPERTY_NAMES: Set<String> = setOf("assets", "idPrefix", "defaultName")
    }

    private fun captureOrigin(): SourceSpan? {
        if (!UdeaBuildContext.captureOrigins) return null
        val script = UdeaBuildContext.currentScript ?: return null
        val scriptFileName = script.substringAfterLast('/')
        val frame = Throwable().stackTrace.firstOrNull { it.fileName == scriptFileName } ?: return null
        if (frame.lineNumber <= 0) return null
        return SourceSpan(script, frame.lineNumber, 0, frame.lineNumber, 0)
    }
}

/**
 * The transpiled front end's entry point (issue #87).
 *
 * A `.udea.kts` becomes a class implementing this, discovered by `ServiceLoader`. Every
 * downstream pass sees an [AssetGraph] and cannot tell which front end produced it.
 */
public interface AssetSource {
    /** The script's directory relative to the asset root, e.g. `character`. */
    public val idPrefix: String

    /** The script's base name, used by declarations that carry no name literal. */
    public val defaultName: String

    /** Declares this file's assets into [scope]. The body of the original script. */
    public fun build(scope: AssetScope)
}
