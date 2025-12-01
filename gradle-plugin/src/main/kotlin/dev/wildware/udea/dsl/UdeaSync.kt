package dev.wildware.udea.network.serde

/**
 * Specify this property to be synced over the network.
 * */
@Target(AnnotationTarget.PROPERTY)
annotation class UdeaSync(
    /**
     * How many ticks between synchronisations of this property.
     * */
    val tick: Int = 1,

    /**
     * When to sync this property.
     * */
    val syncMode: SyncMode = SyncMode.Eager,

    /**
     * Should this property be synced in-place?
     * */
    val inPlace: Boolean = true
) {
    enum class SyncMode {
        /**
         * Syncs every time.
         * */
        Eager,

        /**
         * Syncs only when the value changes.
         * */
        Dirty
    }
}
