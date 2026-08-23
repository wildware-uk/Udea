package dev.wildware.udea.agent.tools

import dev.wildware.udea.core.blueprint.Blueprint
import dev.wildware.udea.core.blueprint.BlueprintId

/**
 * The blueprints an agent may spawn, by name.
 *
 * ## Why this exists here and what replaces it
 *
 * `spawn_blueprint` takes a name and needs a [Blueprint]. `udea-core` has [Blueprint] and
 * [dev.wildware.udea.core.blueprint.BlueprintSpawner] but no registry - a spawner is handed a
 * blueprint, it does not find one - and `udea-assets`, which owns the `.udeapak` the real
 * catalogue is read from (spec 3.6), is an empty module. So the lookup is an interface the host
 * satisfies, and [of] is the map a game or a test uses today.
 *
 * When the asset pipeline lands, a packed bundle implements this and nothing on the tool side
 * changes. That is the point of it being an interface rather than a map: the did-you-mean text
 * below is deliberately *not* the build-time validator's Levenshtein diagnostic, because this
 * catalogue has no rule ids to quote yet, and issue #72's own ownership note moves that promise
 * to Phase 2 where the validator lives.
 */
public interface BlueprintCatalog {

    /** Every spawnable name, ascending. What `world.list_blueprints` publishes. */
    public val names: List<String>

    /** The blueprint called [name], or `null`. Case-sensitive: a [BlueprintId] is an asset key. */
    public fun find(name: String): Blueprint?

    public companion object {

        /** A game with no blueprint catalogue wired. Every spawn answers `no_such_blueprint`. */
        public val EMPTY: BlueprintCatalog = of(emptyList())

        /** A catalogue over a fixed list. Keyed by [Blueprint.id]. */
        public fun of(blueprints: List<Blueprint>): BlueprintCatalog {
            val byName = blueprints.associateBy { it.id.value }
            require(byName.size == blueprints.size) {
                "two blueprints share an id: " +
                    blueprints.groupBy(Blueprint::id)
                        .filterValues { it.size > 1 }
                        .keys
                        .joinToString(transform = BlueprintId::value)
            }
            return object : BlueprintCatalog {
                override val names: List<String> = byName.keys.sorted()

                override fun find(name: String): Blueprint? = byName[name]

                override fun toString(): String = "BlueprintCatalog(${names.size} blueprint(s))"
            }
        }
    }
}
