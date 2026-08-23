package dev.wildware.udea.assets.compiler.pack

import dev.wildware.udea.assets.GameConfig

/**
 * What a level needs, computed from the `GameConfig` root by following references.
 *
 * ## What this is for
 *
 * A loading screen that can report progress in **bytes** rather than in files. The old
 * `GameAssetLoader` (`common/.../UdeaGameManager.kt:483-528`) walked every file under `assets/`,
 * loaded the whole tree whatever the level needed, and drove its bar off a file count - so the
 * bar moved in 300 equal steps while the actual work was one 8MB texture and 299 small ones.
 * The reachable set is what makes the honest denominator available: the packer marks those
 * sections `EAGER`, and their lengths are in the table of contents before a byte of them is read.
 *
 * ## Reachability is over the packed graph, not the declaration graph
 *
 * References here are already slots, so the traversal is over integers. That also means it
 * cannot follow a reference the packer refused to resolve - which is correct: an unresolved
 * reference is a build error, and a reachability set computed *through* one would be quietly
 * missing whatever hung off it.
 */
public object Reachability {

    /** The result: which assets, and which of their `ResPath` fields, the eager set needs. */
    public data class Set(
        /** Ids of every asset reachable from the roots, sorted. */
        public val assets: List<String>,
        /** Every `ResPath` those assets name, sorted. Section names for audio and blobs. */
        public val paths: List<String>,
    ) {
        public val size: Int get() = assets.size
    }

    /**
     * Every asset reachable from the [GameConfig] records in [assets].
     *
     * When the graph has no `GameConfig` the answer is the whole graph, not the empty set. A
     * bundle with no root is a library or a test fixture; marking everything eager there is the
     * behaviour that cannot surprise, and marking nothing eager would produce a game that
     * streams its first frame.
     */
    public fun fromGameConfig(assets: List<PackedAsset>): Set {
        val configKind = requireNotNull(GameConfig::class.qualifiedName)
        val roots = assets.withIndex().filter { it.value.kind == configKind }.map { it.index }
        return if (roots.isEmpty()) all(assets) else from(assets, roots)
    }

    /** Everything, for a bundle with no root. */
    public fun all(assets: List<PackedAsset>): Set = Set(
        assets = assets.map { it.id }.sorted(),
        paths = assets.flatMap { pathsIn(it.fields) }.distinct().sorted(),
    )

    /** Breadth-first from [roots], which are slots into [assets]. */
    public fun from(assets: List<PackedAsset>, roots: List<Int>): Set {
        val seen = BooleanArray(assets.size)
        val queue = ArrayDeque<Int>()
        roots.forEach { root ->
            require(root in assets.indices) { "root slot $root is outside 0..${assets.size - 1}" }
            if (!seen[root]) {
                seen[root] = true
                queue += root
            }
        }
        val reached = mutableListOf<PackedAsset>()
        while (queue.isNotEmpty()) {
            val slot = queue.removeFirst()
            val asset = assets[slot]
            reached += asset
            refsIn(asset.fields).forEach { target ->
                require(target in assets.indices) {
                    "'${asset.id}' references slot $target, outside 0..${assets.size - 1}"
                }
                if (!seen[target]) {
                    seen[target] = true
                    queue += target
                }
            }
        }
        return Set(
            assets = reached.map { it.id }.sorted(),
            paths = reached.flatMap { pathsIn(it.fields) }.distinct().sorted(),
        )
    }

    private fun refsIn(value: PackValue): List<Int> = when (value) {
        is PackValue.Ref -> listOf(value.index)
        is PackValue.Items -> value.values.flatMap { refsIn(it) }
        is PackValue.Fields -> value.values.flatMap { refsIn(it.second) }
        else -> emptyList()
    }

    private fun pathsIn(value: PackValue): List<String> = when (value) {
        is PackValue.Path -> listOf(value.value)
        is PackValue.Items -> value.values.flatMap { pathsIn(it) }
        is PackValue.Fields -> value.values.flatMap { pathsIn(it.second) }
        else -> emptyList()
    }
}
