package dev.wildware.udea.assets.compiler.pack

import dev.wildware.udea.assets.AssetData
import dev.wildware.udea.assets.Axis2D
import dev.wildware.udea.assets.Blueprint
import dev.wildware.udea.assets.Control
import dev.wildware.udea.assets.GameConfig
import dev.wildware.udea.assets.Level
import dev.wildware.udea.assets.SoundCue
import dev.wildware.udea.assets.SpriteAnimation
import dev.wildware.udea.assets.SpriteSheet
import dev.wildware.udea.assets.compiler.AssetCompilerRules
import dev.wildware.udea.assets.compiler.AssetGraph
import dev.wildware.udea.assets.compiler.DeclaredAsset
import dev.wildware.udea.assets.compiler.Ref
import dev.wildware.udea.assets.compiler.ResFile
import dev.wildware.udea.diagnostics.SourceSpan
import dev.wildware.udea.diagnostics.UdeaDiagnostic
import dev.wildware.udea.diagnostics.UdeaRules
import kotlin.reflect.KClass

/**
 * Turns the evaluated declaration graph into records the bundle writer can serialise.
 *
 * ## Where the asset index comes from
 *
 * Records are sorted by id and the sort position **is** the [dev.wildware.udea.assets.AssetIndex].
 * Nothing about the order depends on how the files were found: not directory walk order, not the
 * order the scripts evaluated, not a `HashMap`. That is the whole determinism argument, and it
 * is also what replaces the mutable static counters in `common/.../controls.kt:10-14,52-58`,
 * whose values differed between client and server because the two walked the tree in a different
 * order. See [ControlIds].
 *
 * ## Where the field names come from
 *
 * The `.udea.kts` DSL and the runtime model are two vocabularies and this is the only place they
 * meet. `spriteSheet(spritePath = ...)` becomes `SpriteSheet(texture = ...)`, `interruptable`
 * becomes `interruptible`, and a `blueprint(parent = reference(...))` becomes an
 * `inheritedFrom` entry. Every one of those is a rename someone would otherwise have made by
 * hand at read time, in a codec, where it would be invisible.
 *
 * That the mapping is *needed* is a statement about the DSL, not about this class: `AssetScope`
 * is still the provisional shape issue #86 landed, and several of its words - `character`, and
 * anything through `asset(kind, ...)` - have no runtime type at all. Those are packed as their
 * DSL word and read back as `OpaqueAsset`, which loses nothing and pretends nothing.
 */
public object GraphPacker {

    /** The packed graph, plus everything that went wrong producing it. */
    public data class Result(
        public val assets: List<PackedAsset>,
        public val diagnostics: List<UdeaDiagnostic>,
    ) {
        public val ids: List<String> get() = assets.map { it.id }

        public val hasErrors: Boolean
            get() = diagnostics.any { it.severity == dev.wildware.udea.diagnostics.Severity.Error }
    }

    /**
     * Packs [graph].
     *
     * A reference to an id nothing declares is an error diagnostic and the field is dropped,
     * rather than written as a sentinel index. `PackValue.Ref` refuses a negative index for the
     * same reason: a bundle that cannot be read without a string fallback is worse than a build
     * that failed.
     */
    public fun pack(graph: AssetGraph): Result {
        val ordered = graph.assets.values.sortedBy { it.id }
        val slots = HashMap<String, Int>(ordered.size * 2)
        ordered.forEachIndexed { slot, asset -> slots[asset.id] = slot }

        // The kind of each slot, so a reference can be checked against what the field it lands
        // in expects. Built from `kindFqn` rather than from the DSL word: an unpublishable kind
        // has no fqn, and "no runtime type" is exactly the case this check has to catch.
        val kinds = ordered.map { it.kindFqn }

        val diagnostics = mutableListOf<UdeaDiagnostic>()
        val packed = ordered.map { asset -> record(asset, slots, kinds, diagnostics) }
        return Result(packed, diagnostics)
    }

    private fun record(
        asset: DeclaredAsset,
        slots: Map<String, Int>,
        kinds: List<String?>,
        diagnostics: MutableList<UdeaDiagnostic>,
    ): PackedAsset {
        val schema = SCHEMAS[asset.kind]
        val context = Context(asset, slots, kinds, diagnostics)
        val fields = schema?.map(context) ?: context.verbatim()
        return PackedAsset(
            id = asset.id,
            // An unpublishable kind is packed under its DSL word. `AssetKind.Unpublishable`
            // already refuses to guess an FQN from it (docs/contracts/asset-index.md), and
            // inventing one here would be the same guess in a different file.
            kind = asset.kindFqn ?: asset.kind,
            fields = PackValue.Fields.of(fields),
        )
    }

    /** What one declaration's fields become. Absent for a kind with no runtime type. */
    private fun interface Schema {
        fun map(context: Context): Map<String, PackValue>
    }

    private val SCHEMAS: Map<String, Schema> = mapOf(
        "spriteSheet" to Schema { it.spriteSheet() },
        "soundCue" to Schema { it.soundCue() },
        "spriteAnimation" to Schema { it.spriteAnimation() },
        "blueprint" to Schema { it.blueprint() },
        "level" to Schema { it.level() },
        "gameConfig" to Schema { it.gameConfig() },
    )

    /** The DSL words this packer maps onto a runtime type, sorted. For `DslCoverageTest`. */
    public val MAPPED_KINDS: List<String> = SCHEMAS.keys.sorted()

    /** The runtime type each mapped word produces, for the same test. */
    public val MAPPED_TYPES: Map<String, String> = mapOf(
        "spriteSheet" to fqn(SpriteSheet::class.qualifiedName),
        "soundCue" to fqn(SoundCue::class.qualifiedName),
        "spriteAnimation" to fqn(SpriteAnimation::class.qualifiedName),
        "blueprint" to fqn(Blueprint::class.qualifiedName),
        "level" to fqn(Level::class.qualifiedName),
        "gameConfig" to fqn(GameConfig::class.qualifiedName),
        "control" to fqn(Control::class.qualifiedName),
        "axis2D" to fqn(Axis2D::class.qualifiedName),
    )

    private fun fqn(name: String?): String = requireNotNull(name)

    /**
     * The span used when neither the reference nor its declaration has an origin.
     *
     * `SourceSpan` refuses a blank path - a path relative to nothing is worse than a name
     * (spec 5) - so the honest fallback names the pass rather than pretending to a file. Origins
     * are only captured when `UdeaBuildContext.captureOrigins` is on, which the daemon sets and
     * a plain pack does not.
     */
    private val UNLOCATED: SourceSpan = SourceSpan("<asset graph>", 0, 0, 0, 0)

    /** One declaration being mapped, with the slot table and somewhere to put complaints. */
    private class Context(
        val asset: DeclaredAsset,
        val slots: Map<String, Int>,
        val kinds: List<String?>,
        val diagnostics: MutableList<UdeaDiagnostic>,
    ) {
        fun spriteSheet(): Map<String, PackValue> = buildMap {
            path("texture", "spritePath")
            int("columns", default = 1)
            int("rows", default = 1)
            float("scale", default = 1F)
        }

        fun soundCue(): Map<String, PackValue> = buildMap {
            put("sounds", PackValue.Items(paths("sounds").map { PackValue.Path(it) }))
            float("pitchVariance", default = 0F)
            float("volume", default = 1F)
        }

        fun spriteAnimation(): Map<String, PackValue> = buildMap {
            ref("sheet", SpriteSheet::class)?.let { put("sheet", it) }
            bool("loop", default = true)
            // The DSL spells it `interruptable`; `SpriteAnimation` spells it `interruptible`.
            // Both spellings are in the tree today, so the rename happens here rather than in
            // a script edit that would break every game that already wrote the other one.
            put("interruptible", PackValue.Bool(boolOf("interruptable", true)))
        }

        fun blueprint(): Map<String, PackValue> = buildMap {
            put(
                "components",
                PackValue.Items(
                    strings("components").map {
                        PackValue.Fields.of(mapOf("type" to PackValue.Text(it)))
                    },
                ),
            )
            // `parent = reference("...")` is a single inheritance edge; `Blueprint` models the
            // chain as a list, so one becomes a list of one. Stored as the id rather than as a
            // slot because `inheritedFrom` is a `List<AssetId>` - the model deliberately does
            // not make a blueprint's ancestry resolvable without asking the registry.
            val parent = asset.fields["parent"] as? Ref
            if (parent != null) {
                requireDeclared(parent)
                put("inheritedFrom", PackValue.Items(listOf(PackValue.Text(parent.id))))
            }
        }

        fun level(): Map<String, PackValue> = buildMap {
            val entities = (asset.fields["entities"] as? List<*>).orEmpty()
            put(
                "entities",
                PackValue.Items(
                    entities.mapIndexedNotNull { index, value ->
                        val ref = value as? Ref ?: return@mapIndexedNotNull null
                        val slot = resolve(ref, Blueprint::class) ?: return@mapIndexedNotNull null
                        PackValue.Fields.of(
                            mapOf(
                                // The DSL's `level(entities = listOf(reference(...)))` names no
                                // entity, and `EntityDefinition.name` must not be blank because
                                // it is what a report names. The index is the honest answer:
                                // it is what the author wrote, positionally.
                                "name" to PackValue.Text("${ref.id}#$index"),
                                "blueprint" to PackValue.Ref(slot, ref.id),
                            ),
                        )
                    },
                ),
            )
        }

        fun gameConfig(): Map<String, PackValue> = buildMap {
            ref("defaultCharacter", Blueprint::class)?.let { put("defaultCharacter", it) }
            ref("defaultLevel", Level::class)?.let { put("defaultLevel", it) }
        }

        /**
         * Fields as the DSL wrote them, for a kind with no runtime type.
         *
         * Not a fallback that loses information: the reader materialises this as an
         * `OpaqueAsset` with the same field names, so a game reads back exactly what its script
         * declared. What it does not get is a typed accessor, which is the honest consequence
         * of the engine not having the type.
         */
        fun verbatim(): Map<String, PackValue> =
            asset.fields.entries.associate { (name, value) -> name to convert(name, value) }

        private fun MutableMap<String, PackValue>.path(name: String, from: String) {
            val value = pathOf(asset.fields[from]) ?: return
            put(name, PackValue.Path(value))
        }

        /**
         * The DSL's own resource-path type, or a bare string.
         *
         * `AssetScope.resPath` returns a [ResFile] - a normalised, never-throwing path that the
         * validator checks separately - and both spellings are accepted because a field written
         * through `asset(kind, "spritePath" to "...")` is still a plain string.
         */
        private fun pathOf(value: Any?): String? = when (value) {
            is ResFile -> value.value
            is String -> value
            else -> null
        }

        private fun MutableMap<String, PackValue>.int(name: String, default: Int) {
            put(name, PackValue.I32((asset.fields[name] as? Int) ?: default))
        }

        private fun MutableMap<String, PackValue>.float(name: String, default: Float) {
            put(name, PackValue.F32(floatOf(name, default)))
        }

        private fun MutableMap<String, PackValue>.bool(name: String, default: Boolean) {
            put(name, PackValue.Bool(boolOf(name, default)))
        }

        private fun MutableMap<String, PackValue>.ref(
            name: String,
            expected: KClass<out AssetData>,
        ): PackValue.Ref? {
            val ref = asset.fields[name] as? Ref ?: return null
            val slot = resolve(ref, expected) ?: return null
            return PackValue.Ref(slot, ref.id)
        }

        private fun floatOf(name: String, default: Float): Float = when (val value = asset.fields[name]) {
            is Float -> value
            is Double -> value.toFloat()
            is Int -> value.toFloat()
            else -> default
        }

        private fun boolOf(name: String, default: Boolean): Boolean =
            (asset.fields[name] as? Boolean) ?: default

        private fun strings(name: String): List<String> =
            (asset.fields[name] as? List<*>).orEmpty().filterIsInstance<String>()

        private fun paths(name: String): List<String> =
            (asset.fields[name] as? List<*>).orEmpty().mapNotNull { pathOf(it) }

        private fun requireDeclared(ref: Ref) {
            resolve(ref)
        }

        /**
         * The slot of [ref], checked against the type the field it lands in declares.
         *
         * A mismatch is [UdeaRules.REFERENCE_KIND_MISMATCH] and the field is dropped, because
         * the alternative is a bundle nobody can open: `BundleReader` binds every reference at
         * load time and refuses one whose target is not an instance of the declared type. A
         * writer that emitted it anyway would turn a build error into a launch crash.
         *
         * This is where the provisional DSL shows: `character(...)` has no runtime type, so a
         * `gameConfig(defaultCharacter = reference("character/orc"))` is reported here. That is
         * not a false positive - `GameConfig.defaultCharacter` really is a `Ref<Blueprint>` -
         * and it stops being reported when `udea-assets` owns the generated DSL (#84's
         * remaining half) and `character` yields a `Blueprint`.
         */
        fun resolve(
            ref: Ref,
            expected: KClass<out AssetData>,
        ): Int? {
            val slot = resolve(ref) ?: return null
            val actual = kinds[slot]
            val wanted = expected.qualifiedName
            if (actual != wanted) {
                diagnostics += UdeaRules.REFERENCE_KIND_MISMATCH.diagnostic(
                    message = "'${asset.id}' references '${ref.id}', which is " +
                        (actual?.let { "a $it" } ?: "a kind with no runtime type") +
                        ", where a $wanted is required",
                    span = ref.origin ?: asset.origin ?: UNLOCATED,
                )
                return null
            }
            return slot
        }

        /** The slot of [ref], or null with a diagnostic already filed. */
        fun resolve(ref: Ref): Int? {
            val slot = slots[ref.id]
            if (slot == null) {
                diagnostics += UdeaRules.UNRESOLVED_REFERENCE.diagnostic(
                    message = "'${asset.id}' references '${ref.id}', which nothing declares",
                    span = ref.origin ?: asset.origin ?: UNLOCATED,
                )
            }
            return slot
        }

        fun convert(name: String, value: Any?): PackValue = when (value) {
            null -> PackValue.Null
            is Boolean -> PackValue.Bool(value)
            is Int -> PackValue.I32(value)
            is Long -> PackValue.I64(value)
            is Float -> PackValue.F32(value)
            is Double -> PackValue.F32(value.toFloat())
            is String -> PackValue.Text(value)
            // A path keeps its own tag rather than becoming text: the reader turns it back into
            // a `ResPath`, and a game kind's `spritePath` field is as much a path as a
            // `spriteSheet`'s is.
            is ResFile -> PackValue.Path(value.value)
            is Ref -> resolve(value)?.let { PackValue.Ref(it, value.id) } ?: PackValue.Null
            is List<*> -> PackValue.Items(value.map { convert(name, it) })
            is Map<*, *> -> PackValue.Fields.of(
                value.entries.associate { (key, item) ->
                    val keyName = key as? String ?: keyNotAString(name, key)
                    keyName to convert(keyName, item)
                },
            )
            else -> {
                diagnostics += AssetCompilerRules.UNPACKABLE_VALUE.diagnostic(
                    message = "'${asset.id}' field '$name' holds a ${value::class.simpleName}, " +
                        "which has no bundle encoding; a .udea.kts field must be a primitive, " +
                        "a string, a reference, a list or a map",
                    span = asset.origin ?: UNLOCATED,
                )
                PackValue.Null
            }
        }

        private fun keyNotAString(name: String, key: Any?): Nothing = error(
            "'${asset.id}' field '$name' is keyed by a ${key?.let { it::class.simpleName }}; " +
                "a bundle struct is keyed by strings",
        )
    }
}
