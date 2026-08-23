package dev.wildware.udea.assets.compiler.pack

import dev.wildware.udea.assets.Axis2D
import dev.wildware.udea.assets.Control

/**
 * The integer identity of a control, assigned at pack time.
 *
 * ## What this replaces
 *
 * `common/src/main/kotlin/dev/wildware/udea/assets/controls.kt:10-14,52-58` gave `Control` and
 * `Axis2D` their ids from **mutable static counters** incremented as the asset tree was walked.
 * The walk order was the filesystem's, so the number a control got depended on which machine
 * enumerated the directory - and client and server enumerate separately. Two processes that
 * disagree about which integer means "attack" is a bug that looks like input desync, and it is
 * the kind of bug that only shows up on somebody else's filesystem.
 *
 * ## What replaces it, and why there is no new counter
 *
 * The packed **asset slot** is the control id. `GraphPacker` sorts records by id and the sort
 * position is the [dev.wildware.udea.assets.AssetIndex], so `control/attack` has the same
 * number in every build of the same asset set, on every machine, in either process.
 *
 * A second counter would have been the obvious implementation and would have been wrong twice
 * over: it would be a number that has to be kept in step with the slot across a hot reload
 * (spec 3.6 requires the *index* to be what survives one), and it would be a second thing to
 * get wrong in exactly the way the first one was.
 */
public object ControlIds {

    /** Fully qualified names of the kinds that get an id. */
    public val KINDS: Set<String> = setOf(
        requireNotNull(Control::class.qualifiedName),
        requireNotNull(Axis2D::class.qualifiedName),
    )

    /**
     * Control id per control asset, taken from [assets] - which must already be in packed
     * order.
     *
     * Takes the packed list rather than the raw declarations precisely so that it cannot
     * disagree with the slot: there is no arithmetic here that could drift, only a filter over
     * an order somebody else established.
     */
    public fun assign(assets: List<PackedAsset>): Map<String, Int> {
        require(assets.map { it.id } == assets.map { it.id }.sorted()) {
            "control ids are the packed slots, so the list must already be in packed order"
        }
        return assets.withIndex()
            .filter { (_, asset) -> asset.kind in KINDS }
            .associate { (slot, asset) -> asset.id to slot }
    }
}
