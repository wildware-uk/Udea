package dev.wildware.udea.gas

/**
 * The dense index of one attribute in the game's attribute table.
 *
 * ## What it replaces
 *
 * `MutableMap<String, Attribute>` (`common/ability/AttributeSet.kt:7-8`). A string lookup on
 * the per-tick recompute path is a charter violation on its own, but the map's real cost was
 * silent: `CharacterAttributeSet.kt` registered `maxHealth` and `health` under the same key
 * `"health"`, and `maxMana`/`mana` under `"mana"`, so two of its eight attributes were simply
 * unreachable by name and nothing said so. A dense id makes that shape a build failure — see
 * [AttributeTableBuilder.add] — and makes the lookup an array index.
 */
@JvmInline
public value class AttributeId(public val index: Int) : Comparable<AttributeId> {

    override fun compareTo(other: AttributeId): Int = index.compareTo(other.index)

    override fun toString(): String = "AttributeId#$index"

    public companion object {
        /** The id that names no attribute. Never valid in an [AttributeTable]. */
        public val NONE: AttributeId = AttributeId(-1)
    }
}

/**
 * A per-attribute-set clamp, the surviving half of `AttributeSet.preAttributeChanged`.
 *
 * The old hook took the whole `Attribute` and returned a `Float`
 * (`common/ability/AttributeSet.kt:10`), so a champion could clamp health against max health.
 * That is worth keeping and is not expressible as a constant min/max, so it stays — resolved
 * by [AttributeId] and handed the whole current vector rather than one attribute, because
 * "health may not exceed maxHealth" needs to read a second attribute.
 *
 * It runs *after* the id's min/max resolvers, and it must be a pure function of its arguments:
 * it runs inside the recompute loop, so a clamp that allocated or read a clock would fail the
 * allocation gate and the determinism gate respectively.
 */
public fun interface AttributeClamp {
    /** The value [id] should take, given the already-clamped [value] and the whole vector. */
    public fun clamp(id: AttributeId, value: Float, current: FloatArray): Float
}

/**
 * What one attribute is: its name, its starting value, its bounds and whether it replicates.
 *
 * This is the runtime shape of the row `udea-codegen` will emit per attribute. It is declared
 * here rather than in the generator because the *table* is the thing the simulation reads, and
 * a generator that also owned the runtime type would put codegen on the simulation's classpath.
 */
public class AttributeDecl(
    /** Fully-qualified name, e.g. `dev.wildware.moba.Character.health`. Ids sort by this. */
    public val name: String,
    /** The value a freshly spawned entity's `base` starts at. */
    public val defaultBase: Float = 0f,
    /** Lower bound, resolved per entity. Defaults to `-Float.MAX_VALUE`. */
    public val min: ValueResolver = ValueResolver.MIN,
    /** Upper bound, resolved per entity. Defaults to `Float.MAX_VALUE`. */
    public val max: ValueResolver = ValueResolver.MAX,
    /** Whether the authoritative `base` value is sent to clients. */
    public val replicated: Boolean = true,
    /** Optional cross-attribute clamp; see [AttributeClamp]. */
    public val clamp: AttributeClamp? = null,
) {
    init {
        require(name.isNotEmpty()) { "an attribute name must not be empty" }
    }

    override fun toString(): String = "AttributeDecl($name)"
}

/**
 * A module's contribution to the attribute table.
 *
 * Discovered through `ServiceLoader` by the host, exactly as `ComponentType` and `NetModule`
 * are, so a game module declares its own attributes without an engine-side edit (Trello #35).
 * The merge is order-independent because [AttributeTableBuilder] sorts, so it does not matter
 * which order the loader hands modules back in.
 */
public interface AttributeModule {
    /** Short name, for the lock file and for diagnostics. */
    public val moduleName: String

    /** Every attribute this module declares. */
    public fun attributes(): List<AttributeDecl>
}

/**
 * Every attribute in one game, with dense contiguous ids assigned from sorted names.
 *
 * Sorted-name assignment is the whole point: a table merged from modules discovered in a
 * different order still assigns `health` the same id, so a server built on one machine and a
 * client built on another agree about which slot of the `base` array is which. The checked-in
 * lock file ([render]) is what turns "they agree today" into a CI gate.
 */
public class AttributeTable internal constructor(
    private val decls: Array<AttributeDecl>,
    private val indexByName: Map<String, Int>,
    /** Which module declared each attribute, index-aligned with [decls]. For the lock file. */
    private val owners: Array<String>,
) {

    /** How many attributes exist. Ids are `0 until count`, and every `base` array is this long. */
    public val count: Int get() = decls.size

    /** The declaration for [id]. */
    public fun declOf(id: AttributeId): AttributeDecl {
        require(id.index in decls.indices) {
            "no attribute with id ${id.index}; the table holds $count"
        }
        return decls[id.index]
    }

    /** The id of the attribute named [name]. */
    public fun idOf(name: String): AttributeId =
        AttributeId(indexByName[name] ?: throw NoSuchAttributeException(name, decls.map { it.name }))

    /** The id of the attribute named [name], or [AttributeId.NONE]. */
    public fun idOrNone(name: String): AttributeId =
        indexByName[name]?.let(::AttributeId) ?: AttributeId.NONE

    /** The name of [id]. */
    public fun nameOf(id: AttributeId): String = declOf(id).name

    /** A `base` array filled with every attribute's default. What a fresh entity starts with. */
    public fun newBaseArray(): FloatArray = FloatArray(count) { decls[it].defaultBase }

    /**
     * The table as stable, diffable text — one line per attribute, LF-terminated.
     *
     * Checked in as `attribute-ids.lock` and diffed by CI, the same mechanism and for the same
     * reason as `net-protocol.lock` (spec 3.2): ids come from one place, and a change to one is
     * a deliberate, reviewed change rather than a silent renumbering that desyncs two builds.
     */
    public fun render(): String = buildString {
        for (index in decls.indices) {
            val decl = decls[index]
            append(index).append(' ').append(decl.name)
            append(" owner=").append(owners[index])
            append(" default=").append(decl.defaultBase.toRawBits())
            append(" replicated=").append(decl.replicated)
            append('\n')
        }
    }

    override fun toString(): String = "AttributeTable($count attributes)"

    public companion object {
        /** Merges [modules] into one table. Order-independent: names are sorted. */
        public fun of(modules: List<AttributeModule>): AttributeTable =
            AttributeTableBuilder().apply { modules.forEach(::add) }.build()
    }
}

/** An attribute name no [AttributeTable] knows. Loud, and it names what it does know. */
public class NoSuchAttributeException(
    public val name: String,
    public val known: List<String>,
) : IllegalArgumentException(
    "no attribute named '$name'; the table holds ${known.size}: ${known.joinToString(limit = 16)}",
)

/**
 * Two attributes declared under one name.
 *
 * This is `CharacterAttributeSet.kt:21`/`:26` — `maxHealth` and `health` both registered as
 * `"health"` — turned from a silent overwrite into a failure that names both declarations.
 * `udea-compiler-plugin`'s FIR checker is meant to catch this at the offending symbol; this is
 * the runtime backstop for a table assembled from modules the checker never saw together.
 */
public class DuplicateAttributeException(
    public val name: String,
    public val firstOwner: String,
    public val secondOwner: String,
) : IllegalArgumentException(
    "attribute '$name' is declared twice: by '$firstOwner' and by '$secondOwner'. One of the " +
        "two would be unreachable by name, which is exactly the live defect in the old " +
        "CharacterAttributeSet. Rename one.",
)

/** Merges [AttributeModule] contributions into an [AttributeTable]. */
public class AttributeTableBuilder {

    private val declared = LinkedHashMap<String, Pair<AttributeDecl, String>>()

    /** Adds every attribute [module] declares. */
    public fun add(module: AttributeModule) {
        for (decl in module.attributes()) add(decl, module.moduleName)
    }

    /**
     * Adds one declaration owned by [owner].
     *
     * @throws DuplicateAttributeException if the name is already declared, by this module or
     *   any other.
     */
    public fun add(decl: AttributeDecl, owner: String) {
        val existing = declared[decl.name]
        if (existing != null) throw DuplicateAttributeException(decl.name, existing.second, owner)
        declared[decl.name] = decl to owner
    }

    /** Assigns ids by ascending name and builds the table. */
    public fun build(): AttributeTable {
        val sorted = declared.entries.sortedBy { it.key }
        val decls = Array(sorted.size) { sorted[it].value.first }
        val owners = Array(sorted.size) { sorted[it].value.second }
        val byName = HashMap<String, Int>(sorted.size * 2)
        sorted.forEachIndexed { index, entry -> byName[entry.key] = index }
        return AttributeTable(decls, byName, owners)
    }
}
