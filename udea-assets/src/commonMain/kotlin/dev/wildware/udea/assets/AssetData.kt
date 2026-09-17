package dev.wildware.udea.assets

/**
 * One immutable, fully resolved asset value, as it exists after the build-time pipeline has
 * run (spec 3.6).
 *
 * ## What changed from `common/assets/assets.kt`
 *
 * The old `Asset<T>` was an abstract class with `var path`, `var name`, a self-referential
 * `override val value: T = this as T`, and `equals`/`hashCode` keyed on `path` alone — so the
 * thirteen assets declared in `character/orc_elite.udea.kts` all shared one `path`, compared
 * equal to each other and collapsed to a single entry in any `Set` or `Map`
 * (`common/.../assets.kt:81-87`). Here identity is [id], the whole value is a `data class`, and
 * nothing is mutable, so two assets from one file differ exactly as much as their contents do.
 *
 * ## Not a sealed hierarchy
 *
 * A game declares its own kinds — the old tree's `example` module has three — and a sealed
 * `AssetData` would make that impossible without editing this module. The pack format is
 * self-describing (spec 1: "every wire and disk format is self-describing"), so nothing here
 * needs an exhaustive `when`.
 *
 * ## Not on the snapshot path
 *
 * No implementation of this interface ever enters a `WorldSnapshot`. A `@Net`/`@Sim` field that
 * names an asset stores an [AssetIndex] int, which is stable across a hot reload, so a rewind
 * across a reload restores state that reads the *new* data (spec 3.6, issue #64). Putting an
 * `AssetData` in a snapshot would freeze the old values into the past and break that.
 */
public interface AssetData {

    /** This asset's stable name. Unique across the whole graph; [AssetRegistry] enforces it. */
    public val id: AssetId
}
