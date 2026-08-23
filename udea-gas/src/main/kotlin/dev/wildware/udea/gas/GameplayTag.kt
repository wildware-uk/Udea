package dev.wildware.udea.gas

/**
 * A gameplay tag, as a dense id rather than a marker interface.
 *
 * The old engine declared `interface GameplayTag` and tagged effects with object references
 * (`common/ability/GameplayTag.kt`). Two things followed, and both are fatal here: a tag on a
 * live effect spec is *simulation state*, so a snapshot would have to capture an object
 * reference, which spec 3.1 forbids; and "does this entity have a blocking tag" degraded to a
 * linear scan of every active effect comparing references.
 *
 * A dense id makes the first a plain `Int` in a field store and the second a bit test in a
 * [TagSet]. Ids come from [GameplayTagTable], which assigns them from a *sorted* name list, so
 * two independently built processes agree on them for the same reason component type ids come
 * from sorted FQNs (spec 5).
 */
@JvmInline
public value class GameplayTag(public val id: Int) : Comparable<GameplayTag> {

    override fun compareTo(other: GameplayTag): Int = id.compareTo(other.id)

    override fun toString(): String = "GameplayTag#$id"

    public companion object {
        /** The tag that names nothing. Never present in any [TagSet]. */
        public val NONE: GameplayTag = GameplayTag(-1)
    }
}

/**
 * Names to [GameplayTag] ids for one game, assigned deterministically.
 *
 * Built once at start-up from every tag any module declares. Ids are assigned by ascending
 * name, so declaration order — and therefore `ServiceLoader` discovery order — cannot change
 * them. That is the same rule the attribute table follows, and for the same reason: an id that
 * depends on load order is an id two machines disagree about.
 */
public class GameplayTagTable private constructor(
    private val namesByIndex: Array<String>,
    private val indexByName: Map<String, Int>,
) {

    /** How many tags exist. Ids are `0 until size`. */
    public val size: Int get() = namesByIndex.size

    /** The tag named [name]. */
    public fun tagOf(name: String): GameplayTag =
        GameplayTag(indexByName[name] ?: throw NoSuchTagException(name, namesByIndex.toList()))

    /** The tag named [name], or [GameplayTag.NONE]. */
    public fun tagOrNone(name: String): GameplayTag =
        indexByName[name]?.let(::GameplayTag) ?: GameplayTag.NONE

    /** The name of [tag], for diagnostics and agent output. */
    public fun nameOf(tag: GameplayTag): String {
        require(tag.id in namesByIndex.indices) { "no tag with id ${tag.id}; table holds $size tag(s)" }
        return namesByIndex[tag.id]
    }

    /** An empty [TagSet] wide enough for every tag in this table. */
    public fun newSet(): TagSet = TagSet(size)

    /**
     * A [TagSet] holding exactly [tags]. Authoring-time, never per tick.
     *
     * A `List` rather than a `vararg`: [GameplayTag] is a value class, and Kotlin does not allow
     * one as a vararg element type.
     */
    public fun setOf(tags: List<GameplayTag>): TagSet = newSet().also { set -> tags.forEach(set::add) }

    /** A [TagSet] holding one tag. */
    public fun setOf(tag: GameplayTag): TagSet = newSet().also { it.add(tag) }

    override fun toString(): String = "GameplayTagTable($size tags)"

    public companion object {
        /**
         * Builds a table from [names], de-duplicated and sorted.
         *
         * A duplicate is *not* an error: two modules naming the same tag is how a game module
         * blocks on an engine tag. A duplicate *attribute* is an error, because two attributes
         * with one name means one of them is unreachable — see [AttributeTable].
         */
        public fun of(names: Collection<String>): GameplayTagTable {
            val sorted = names.toSortedSet().toTypedArray()
            require(sorted.none { it.isEmpty() }) { "a gameplay tag name must not be empty" }
            val byName = HashMap<String, Int>(sorted.size * 2)
            sorted.forEachIndexed { index, name -> byName[name] = index }
            return GameplayTagTable(sorted, byName)
        }
    }
}

/** A tag name no [GameplayTagTable] knows. Loud, and it names what it does know. */
public class NoSuchTagException(
    public val name: String,
    public val known: List<String>,
) : IllegalArgumentException(
    "no gameplay tag named '$name'; the table holds ${known.size}: ${known.joinToString(limit = 16)}",
)

/**
 * A set of [GameplayTag]s as a fixed-width bitset.
 *
 * Sized to the tag table at construction and never resized, so `add`/`contains`/`intersects`
 * allocate nothing. That is what lets the ability system recompute "which tags does this
 * entity currently have" every tick for every unit in a 5v5 without touching the allocator —
 * the old code answered the same question with `_gameplayEffectSpecs.any { it.hasTag(tag) }`,
 * a scan with a lambda per call.
 */
public class TagSet(tagCount: Int) {

    init {
        require(tagCount >= 0) { "tagCount must not be negative, was $tagCount" }
    }

    /** Ceiling division: one 64-bit word per 64 tags, at least one so an empty table still works. */
    private val words = LongArray(maxOf(1, (tagCount + WORD_BITS - 1) / WORD_BITS))

    /** How many tags this set can hold. Ids at or above it are refused rather than truncated. */
    public val capacity: Int = tagCount

    /** Adds [tag]. */
    public fun add(tag: GameplayTag) {
        val index = checked(tag)
        words[index ushr WORD_SHIFT] = words[index ushr WORD_SHIFT] or (1L shl index)
    }

    /** Removes [tag]. */
    public fun remove(tag: GameplayTag) {
        val index = checked(tag)
        words[index ushr WORD_SHIFT] = words[index ushr WORD_SHIFT] and (1L shl index).inv()
    }

    /** True when [tag] is present. An out-of-range or [GameplayTag.NONE] tag is never present. */
    public operator fun contains(tag: GameplayTag): Boolean {
        if (tag.id < 0 || tag.id >= capacity) return false
        return words[tag.id ushr WORD_SHIFT] and (1L shl tag.id) != 0L
    }

    /** True if any tag is in both sets. The blocking-tag check, without a scan. */
    public fun intersects(other: TagSet): Boolean {
        val limit = minOf(words.size, other.words.size)
        var index = 0
        while (index < limit) {
            if (words[index] and other.words[index] != 0L) return true
            index++
        }
        return false
    }

    /** The lowest tag in both sets, or [GameplayTag.NONE]. Names *which* tag blocked. */
    public fun firstIntersection(other: TagSet): GameplayTag {
        val limit = minOf(words.size, other.words.size)
        var index = 0
        while (index < limit) {
            val both = words[index] and other.words[index]
            if (both != 0L) {
                return GameplayTag((index shl WORD_SHIFT) + java.lang.Long.numberOfTrailingZeros(both))
            }
            index++
        }
        return GameplayTag.NONE
    }

    /** Adds every tag of [other]. */
    public fun addAll(other: TagSet) {
        val limit = minOf(words.size, other.words.size)
        var index = 0
        while (index < limit) {
            words[index] = words[index] or other.words[index]
            index++
        }
    }

    /** Empties the set, keeping its capacity. */
    public fun clear() {
        java.util.Arrays.fill(words, 0L)
    }

    /** True when no tag is present. */
    public val isEmpty: Boolean
        get() {
            var index = 0
            while (index < words.size) {
                if (words[index] != 0L) return false
                index++
            }
            return true
        }

    /** Content hash, so a tag set can be folded into a determinism hash. */
    override fun hashCode(): Int = words.contentHashCode()

    override fun equals(other: Any?): Boolean = other is TagSet && words.contentEquals(other.words)

    override fun toString(): String = "TagSet(${words.sumOf { java.lang.Long.bitCount(it) }} set)"

    private fun checked(tag: GameplayTag): Int {
        require(tag.id in 0 until capacity) {
            "tag id ${tag.id} is outside this set's capacity (0 until $capacity)"
        }
        return tag.id
    }

    private companion object {
        const val WORD_BITS = 64
        const val WORD_SHIFT = 6
    }
}
