package dev.wildware.udea.assets.compiler

import dev.wildware.udea.assets.Ability
import dev.wildware.udea.assets.Axis2D
import dev.wildware.udea.assets.Axis2DBinding
import dev.wildware.udea.assets.Binding
import dev.wildware.udea.assets.Blueprint
import dev.wildware.udea.assets.Control
import dev.wildware.udea.assets.GameConfig
import dev.wildware.udea.assets.Level
import dev.wildware.udea.assets.SoundCue
import dev.wildware.udea.assets.SpriteAnimation
import dev.wildware.udea.assets.SpriteAnimationSet
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

    /**
     * A set of animations one character or effect plays, as the runtime `SpriteAnimationSet`.
     *
     * ### Why the members are declared beside it and not inside it
     *
     * The old corpus nested them - `spriteAnimationSet(animations = { spriteAnimation(...) })` -
     * and a nested builder would have reproduced that spelling exactly. It is not used, because
     * pass 1 only walks **top-level** statements: a declaration written inside a lambda argument
     * has no entry in the scan, and therefore no span, no pass-1 id, and no place in the
     * "located name for everything, before anything is compiled" property the rest of the
     * pipeline is built on. Flattening the sets is the price of keeping it, and it is the only
     * structural change the migration makes.
     */
    public fun spriteAnimationSet(name: String, animations: List<Ref>): Unit =
        declare(
            AssetKind.of<SpriteAnimationSet>(),
            "spriteAnimationSet",
            name,
            "animations" to animations.map { it.expecting<SpriteAnimation>() },
        )

    /**
     * A character: a blueprint with animation, audio, attribute and ability wiring.
     *
     * Still [AssetKind.Unpublishable]: `udea-assets` has no `Character`, and the old DSL's
     * `character(...)` returned a `Blueprint` only because it inlined a fixed component list at
     * *build* time. Claiming `Blueprint` here would put an id in the compile-time catalog whose
     * fields are not a `Blueprint`'s, which `AssetKind`'s KDoc calls out as worse than absence.
     * Closing it is issue #84's remaining half.
     *
     * @param animationMap role (`idle`, `walk`, `attack`, ...) to the animation played for it.
     *   References, not the bare name strings the old `gameUnitAnimations` took, so a role
     *   pointing at an animation nothing declares is `UDEA0004` and not a null at spawn time.
     * @param attributes initial attribute values by name, replacing the old
     *   `attributeSet = { CharacterAttributeSet(initHealth = 500F, ...) }` lambda. It is data
     *   here because the pack format holds data; the class that *interprets* the names is the
     *   game's.
     */
    public fun character(
        name: String,
        size: Float = 1f,
        health: Float = 100f,
        animations: List<Ref> = emptyList(),
        sounds: Map<String, Ref> = emptyMap(),
        spriteAnimationSet: Ref? = null,
        animationMap: Map<String, Ref> = emptyMap(),
        attributes: Map<String, Float> = emptyMap(),
        tags: List<String> = emptyList(),
        abilitySpecs: AbilitySpecScope.() -> Unit = {},
        components: ComponentScope.() -> Unit = {},
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
        "spriteAnimationSet" to spriteAnimationSet?.expecting<SpriteAnimationSet>(),
        "animationMap" to animationMap.mapValues { it.value.expecting<SpriteAnimation>() },
        "attributes" to LinkedHashMap(attributes),
        "tags" to tags,
        "abilitySpecs" to AbilitySpecScope().apply(abilitySpecs).build(),
        "components" to ComponentScope().apply(components).build(),
    )

    public fun blueprint(
        name: String = defaultName,
        parent: Ref? = null,
        components: List<String> = emptyList(),
    ): Unit =
        declare(
            AssetKind.of<Blueprint>(),
            "blueprint",
            name,
            "parent" to parent?.expecting<Blueprint>(),
            "components" to components,
        )

    /**
     * A blueprint whose components carry fields.
     *
     * An overload rather than a widening of the `List<String>` parameter, and [components] is
     * deliberately **not** defaulted: two overloads that are both applicable to `blueprint(name
     * = "x")` would be an ambiguity at every call site that omits components.
     */
    public fun blueprint(
        name: String = defaultName,
        parent: Ref? = null,
        components: ComponentScope.() -> Unit,
    ): Unit = declare(
        AssetKind.of<Blueprint>(),
        "blueprint",
        name,
        "parent" to parent?.expecting<Blueprint>(),
        "components" to ComponentScope().apply(components).build(),
    )

    public fun level(
        name: String = defaultName,
        systems: List<String> = emptyList(),
        entities: List<Ref> = emptyList(),
    ): Unit = declare(
        AssetKind.of<Level>(),
        "level",
        name,
        "systems" to systems,
        "entities" to entities.map { it.expecting<Blueprint>() },
    )

    /**
     * A level whose entities carry their own components, tags and spawn positions.
     *
     * The list overload above cannot express those: it takes bare blueprint references, and
     * `GraphPacker` has to invent an `EntityDefinition.name` from the index for them. Here the
     * author names each entity. [entities] is required for the reason [blueprint]'s is.
     */
    public fun level(
        name: String = defaultName,
        systems: List<String> = emptyList(),
        entities: EntityScope.() -> Unit,
    ): Unit = declare(
        AssetKind.of<Level>(),
        "level",
        name,
        "systems" to systems,
        "entities" to EntityScope().apply(entities).build(),
    )

    /**
     * `defaultCharacter` is deliberately **not** stamped with an expected kind: it points at a
     * `character`, which is [AssetKind.Unpublishable], so there is no `AssetData` type to
     * compare a resolved declaration against. See [Ref.expected].
     */
    public fun gameConfig(
        name: String = defaultName,
        defaultCharacter: Ref,
        defaultLevel: Ref? = null,
        physics: Map<String, Any?>? = null,
    ): Unit = declare(
        AssetKind.of<GameConfig>(),
        "gameConfig",
        name,
        "defaultCharacter" to defaultCharacter,
        "defaultLevel" to defaultLevel?.expecting<Level>(),
        "physics" to physics,
    )

    // --- input ---------------------------------------------------------------------------

    /** A named action the game asks about; bindings point at it. */
    public fun control(name: String): Unit = declare(AssetKind.of<Control>(), "control", name)

    /** A named two-dimensional axis; [axis2DBinding]s contribute directions to it. */
    public fun axis2D(name: String): Unit = declare(AssetKind.of<Axis2D>(), "axis2D", name)

    /** Binds one input to one [control]. */
    public fun binding(name: String, control: Ref, input: Map<String, Any?>): Unit = declare(
        AssetKind.of<Binding>(),
        "binding",
        name,
        "control" to control.expecting<Control>(),
        "input" to input,
    )

    /** Binds one input to one [axis2D], contributing [direction] while held. */
    public fun axis2DBinding(
        name: String,
        axis: Ref,
        input: Map<String, Any?>,
        direction: Map<String, Any?>,
    ): Unit = declare(
        AssetKind.of<Axis2DBinding>(),
        "axis2DBinding",
        name,
        "axis" to axis.expecting<Axis2D>(),
        "input" to input,
        "direction" to direction,
    )

    /** A keyboard key, by its libGDX key code. */
    public fun key(code: Int): Map<String, Any?> = linkedMapOf("kind" to "key", "code" to code)

    /** A mouse button, by its libGDX button code. */
    public fun mouse(button: Int): Map<String, Any?> =
        linkedMapOf("kind" to "mouseButton", "code" to button)

    /** A two-component vector, as `Vec2` is modelled in `udea-assets`. */
    public fun vec(x: Float, y: Float): Map<String, Any?> = linkedMapOf("x" to x, "y" to y)

    /** The `physics` block of a [gameConfig]. */
    public fun physics(gravity: Map<String, Any?>): Map<String, Any?> = linkedMapOf("gravity" to gravity)

    // --- gameplay ability system ----------------------------------------------------------

    /**
     * One ability: the class that runs it, and every number that tunes it.
     *
     * [exec] is a class **name** rather than a `KClass`, matching `Ability.exec`'s `UClass`.
     * The old scripts wrote `exec = UnitMeleeAttack::class`, which put a game class on the
     * asset compile classpath and made an asset edit depend on the game compiling.
     *
     * [cooldown] and [costs] are not stamped with an expected kind: they point at
     * `gameplayEffect`, which has no runtime type until `udea-gas` declares one.
     */
    public fun ability(
        name: String,
        exec: String,
        display: Map<String, Any?>? = null,
        params: Map<String, Any?> = emptyMap(),
        cooldown: Ref? = null,
        costs: List<Ref> = emptyList(),
        blockedBy: List<String> = emptyList(),
        tags: List<String> = emptyList(),
        setByCaller: Map<String, Float> = emptyMap(),
        range: Float? = null,
        blockAnimations: Boolean = false,
    ): Unit = declare(
        AssetKind.of<Ability>(),
        "ability",
        name,
        "exec" to exec,
        "display" to display,
        "params" to LinkedHashMap(params),
        "cooldown" to cooldown,
        "costs" to costs,
        "blockedBy" to blockedBy,
        "tags" to tags,
        "setByCaller" to LinkedHashMap(setByCaller),
        "range" to range,
        "blockAnimations" to blockAnimations,
    )

    /**
     * One gameplay effect.
     *
     * [AssetKind.Unpublishable]: `udea-gas` is an empty module and there is no `GameplayEffect`
     * type to name. The declaration is packed under its DSL word and read back as an opaque
     * asset, which loses nothing and pretends nothing.
     */
    public fun gameplayEffect(
        name: String,
        effectDuration: Map<String, Any?>,
        target: String? = null,
        modifierType: String? = null,
        magnitude: Map<String, Any?>? = null,
        period: Float? = null,
        cues: List<String> = emptyList(),
        tags: List<String> = emptyList(),
    ): Unit = declare(
        AssetKind.Unpublishable("gameplayEffect"),
        "gameplayEffect",
        name,
        "effectDuration" to effectDuration,
        "target" to target,
        "modifierType" to modifierType,
        "magnitude" to magnitude,
        "period" to period,
        "cues" to cues,
        "tags" to tags,
    )

    /** An effect that applies once and is over. */
    public fun instant(): Map<String, Any?> = linkedMapOf("kind" to "instant")

    /** An effect that lasts as long as the caller-supplied magnitude of [tag] says. */
    public fun duration(tag: String): Map<String, Any?> = linkedMapOf("kind" to "duration", "tag" to tag)

    /** An effect that never expires on its own. */
    public fun infinite(): Map<String, Any?> = linkedMapOf("kind" to "infinite")

    /** A magnitude the activating entity supplies at cast time, keyed by [tag]. */
    public fun setByCaller(tag: String): Map<String, Any?> =
        linkedMapOf("kind" to "setByCaller", "tag" to tag)

    /** A magnitude read from an attribute of the target, by name. */
    public fun attribute(name: String): Map<String, Any?> =
        linkedMapOf("kind" to "attribute", "attribute" to name)

    /**
     * A short-lived visual: an animation set, which of its animations to play, and for how long.
     *
     * A game kind, not an engine one - the old tree declared it in
     * `example/.../assets/effect.kt` - so it is [AssetKind.Unpublishable] like `character`.
     */
    public fun effect(
        name: String,
        animationSet: Ref,
        animation: String,
        duration: Float,
    ): Unit = declare(
        AssetKind.Unpublishable("effect"),
        "effect",
        name,
        "animationSet" to animationSet.expecting<SpriteAnimationSet>(),
        "animation" to animation,
        "duration" to duration,
    )

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
            "spriteAnimationSet",
            "character",
            "blueprint",
            "level",
            "gameConfig",
            "control",
            "axis2D",
            "binding",
            "axis2DBinding",
            "ability",
            "gameplayEffect",
            "effect",
            "key",
            "mouse",
            "vec",
            "physics",
            "instant",
            "duration",
            "infinite",
            "setByCaller",
            "attribute",
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

/**
 * A component list, as `ComponentSpec(type, fields)` records it.
 *
 * This is where the old `components = lazy { networkable(); team(Team.OrcTeam) }` lands. The old
 * form named ECS component *functions* in game source, so every asset script was a compile
 * dependency on the game - the thing that made an asset edit cost a Gradle build. A component is
 * data here: a type name and its fields, checked against the real component types by the K2
 * checker rather than by making the script import them.
 */
public class ComponentScope internal constructor() {

    private val components = mutableListOf<Map<String, Any?>>()

    /** One component: its type name, and the fields that tune it. */
    public fun component(type: String, vararg fields: Pair<String, Any?>) {
        components += linkedMapOf<String, Any?>(
            "type" to type,
            "fields" to linkedMapOf<String, Any?>(*fields),
        )
    }

    internal fun build(): List<Map<String, Any?>> = components.toList()
}

/** The abilities a character is granted, and the slots and tags they are granted with. */
public class AbilitySpecScope internal constructor() {

    private val specs = mutableListOf<Map<String, Any?>>()

    /** Grants [ability]. The reference is unstamped: `ability` is checked by the graph packer. */
    public fun abilitySpec(ability: Ref, tags: List<String> = emptyList(), level: Int = 1) {
        specs += linkedMapOf<String, Any?>(
            "ability" to ability.expecting<Ability>(),
            "tags" to tags,
            "level" to level,
        )
    }

    internal fun build(): List<Map<String, Any?>> = specs.toList()
}

/** The entities a level spawns, as `EntityDefinition` records them. */
public class EntityScope internal constructor() {

    private val entities = mutableListOf<Map<String, Any?>>()

    /**
     * One entity: which blueprint it instantiates, what is added on top, and where it starts.
     *
     * [blueprint] is not stamped with an expected kind because the corpus points these at
     * `character(...)` declarations, which have no runtime type. That is the same gap
     * `ReferenceTypeValidator` documents, not a new one.
     */
    public fun entity(
        name: String,
        blueprint: Ref? = null,
        position: Map<String, Any?>? = null,
        tags: List<String> = emptyList(),
        components: ComponentScope.() -> Unit = {},
    ) {
        entities += linkedMapOf<String, Any?>(
            "name" to name,
            "blueprint" to blueprint,
            "position" to position,
            "tags" to tags,
            "components" to ComponentScope().apply(components).build(),
        )
    }

    internal fun build(): List<Map<String, Any?>> = entities.toList()
}
