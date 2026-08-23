package dev.wildware.udea.assets

/**
 * One entity a level places: which blueprint, where, and what it changes about it.
 *
 * The old `EntityDefinition` had `var id: Long = -1L`, assigned by a `nextEntityId()` that scanned
 * the whole list for a maximum (`common/.../levels.kt`). Identity here is position in
 * [Level.entities]: it is stable in the pack, needs no counter, and cannot drift from the list it
 * indexes.
 *
 * [components] and [tags] are per-spawn *additions*, applied after the blueprint's own - the shape
 * `udea-core`'s `SpawnOverrides` describes, and the same order the old spawn loop used
 * (`common/UdeaGameManager.kt:196-201`).
 */
public data class EntityDefinition(
    /** A human-readable label for diagnostics and for the agent's `list_entities`. */
    public val name: String = "Entity",
    public val blueprint: Ref<Blueprint>? = null,
    public val components: List<ComponentSpec> = emptyList(),
    public val tags: List<EntityTagName> = emptyList(),
    /** Where to put it, or `null` to leave the blueprint's own placement alone. */
    public val position: Vec2? = null,
) {
    init {
        require(name.isNotBlank()) { "an entity definition must have a name; it is what a report names" }
    }
}

/**
 * A scene's contents: the systems it runs and the entities it starts with.
 *
 * [systems] are class names rather than `KClass<out IntervalSystem>` (the old `Level` held the
 * latter, which is why levels could only be read by a JVM that already had the game on its
 * classpath). A build-time tool can read a `.udeapak` level without loading a single game class,
 * and the runtime turns a name into a system through the generated registry.
 */
public data class Level(
    override val id: AssetId,
    public val systems: List<UClass<Any>> = emptyList(),
    public val entities: List<EntityDefinition> = emptyList(),
) : AssetData
