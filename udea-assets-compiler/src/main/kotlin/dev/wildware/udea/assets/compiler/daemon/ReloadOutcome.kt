package dev.wildware.udea.assets.compiler.daemon

import dev.wildware.udea.assets.AssetId
import dev.wildware.udea.assets.DeltaClassification
import dev.wildware.udea.assets.GraphDelta
import dev.wildware.udea.assets.RestartReason
import dev.wildware.udea.diagnostics.UdeaDiagnostic

/**
 * One structural difference between the running graph and a recompiled one.
 *
 * [code] is the wire string, and the three that a running [dev.wildware.udea.assets.AssetRegistry]
 * can also detect are taken **from** [RestartReason.code] rather than spelled again here: the
 * daemon decides ahead of the push and the registry decides again at the barrier, and two
 * spellings of "asset_removed" is how those two answers come to look like different defects.
 */
public data class StructuralChange(
    public val id: AssetId,
    public val code: String,
    /** One line an agent reads: what changed shape, and therefore why a restart. */
    public val detail: String,
) {
    public companion object {
        /** The recompiled graph declares an id the running one does not. */
        public fun added(id: AssetId): StructuralChange = StructuralChange(
            id, RestartReason.AssetAdded.code,
            "'$id' is new; the running graph has no slot for it, and slots are assigned at pack time",
        )

        /** The id is gone. Its slot would have to stay occupied by something, and nothing fits. */
        public fun removed(id: AssetId): StructuralChange = StructuralChange(
            id, RestartReason.AssetRemoved.code,
            "'$id' was deleted; every AssetIndex naming its slot - including ones inside snapshots " +
                "- would name nothing",
        )

        /** The id still exists but declares a different kind. Every `Ref` to it was type-checked. */
        public fun kindChanged(id: AssetId, from: String, to: String): StructuralChange =
            StructuralChange(
                id, RestartReason.KindChanged.code,
                "'$id' changed from `$from(...)` to `$to(...)`; every reference to it type-checked " +
                    "against the old kind",
            )

        /**
         * A blueprint's component list changed.
         *
         * Not a value change even though `Blueprint` is data: entities already spawned from it
         * hold the *old* component set, and swapping the value in place would leave the world
         * with two populations that no longer agree about what a `character/orc` is. Spec 3.6
         * names this as one of the three shape-changing edits, and it is the one this module can
         * see today - an ability's exec class and a component's `@Net` field set are `udea-gas`
         * and `udea-codegen`'s, and neither exists to be inspected.
         */
        public const val BLUEPRINT_COMPONENTS: String = "blueprint_components_changed"

        /** [BLUEPRINT_COMPONENTS] for one blueprint. */
        public fun blueprintComponents(id: AssetId, from: List<String>, to: List<String>): StructuralChange =
            StructuralChange(
                id, BLUEPRINT_COMPONENTS,
                "blueprint '$id' changed its component list from [${from.joinToString(", ")}] to " +
                    "[${to.joinToString(", ")}]; entities already spawned hold the old set",
            )
    }
}

/**
 * What one [AssetDaemon.reload] decided.
 *
 * Four outcomes and no fifth: a reload either has nothing to say, has a value delta, is refused
 * because the source is broken, or is refused because the graph's shape moved. The distinction
 * between the last two is the whole of spec 3.6's rewind interaction - a [Rejected] reload never
 * happened, so a rewind across it is not even a question, while a [RequiresRestart] one is the
 * single case `rewind()` must refuse.
 */
public sealed interface ReloadOutcome {

    /** Wall time the daemon spent, including the recompile. The number the budgets gate. */
    public val durationMs: Long

    /** Nothing in the recompiled graph differs from the running one. */
    public data class NoChange(override val durationMs: Long) : ReloadOutcome

    /** [delta] is ready to be pushed at a tick boundary. */
    public data class Applied(
        public val delta: GraphDelta,
        override val durationMs: Long,
    ) : ReloadOutcome {
        /** What changed, for the tool result and the log line. */
        public val changedIds: List<AssetId> get() = delta.ids
    }

    /**
     * The recompile produced errors. **Nothing was pushed and the daemon kept its last-good
     * graph**, which is the property an agent's broken edit rests on: a typo must not kill the
     * game the agent is driving.
     */
    public data class Rejected(
        public val diagnostics: List<UdeaDiagnostic>,
        override val durationMs: Long,
    ) : ReloadOutcome {
        init { require(diagnostics.isNotEmpty()) { "a Rejected reload must say what was wrong" } }
    }

    /**
     * The recompile was clean and the graph's *shape* moved, so no in-place swap exists.
     *
     * Carries [DeltaClassification.RequiresRestart.CODE] as its wire code - the same string the
     * registry refuses a delta with and `rewind()` refuses a cross-reload rewind with, so the
     * daemon, the runtime and the time-travel path cannot drift into three names for one answer.
     */
    public data class RequiresRestart(
        public val changes: List<StructuralChange>,
        override val durationMs: Long,
    ) : ReloadOutcome {
        init { require(changes.isNotEmpty()) { "a RequiresRestart reload must name a shape change" } }

        /** The stable code. */
        public val code: String get() = DeltaClassification.RequiresRestart.CODE
    }
}
