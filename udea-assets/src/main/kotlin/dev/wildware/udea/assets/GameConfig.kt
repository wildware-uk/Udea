package dev.wildware.udea.assets

/** How a game's characters move, which decides which movement systems a scene runs. */
public enum class MovementType { TopDown, Sidescroller }

/** The ports a listen server binds. */
public data class NetworkConfig(
    public val tcpPort: Int = DEFAULT_TCP_PORT,
    public val udpPort: Int = DEFAULT_UDP_PORT,
) {
    init {
        require(tcpPort in PORT_RANGE) { "tcpPort $tcpPort is outside $PORT_RANGE" }
        require(udpPort in PORT_RANGE) { "udpPort $udpPort is outside $PORT_RANGE" }
        require(tcpPort != udpPort) { "tcpPort and udpPort are both $tcpPort" }
    }

    public companion object {
        /** The old tree's defaults, kept so an existing `gameConfig` needs no edit. */
        public const val DEFAULT_TCP_PORT: Int = 28855
        public const val DEFAULT_UDP_PORT: Int = 28856

        /** Non-privileged ports only: a game binding below 1024 needs root, and should not. */
        public val PORT_RANGE: IntRange = 1024..65535
    }
}

/** World gravity, in world units per second squared. */
public data class PhysicsConfig(public val gravity: Vec2 = Vec2(0F, -9.81F))

/**
 * The 2D lighting setup.
 *
 * [fboWidth] and [fboHeight] are required rather than defaulted to `Gdx.graphics.width`, which is
 * what the old `Lighting` did (`common/.../gameConfig.kt`). That default read a live GL context
 * *at asset construction time*, so the value depended on when the script happened to be evaluated,
 * and it could not be evaluated at all without a window - which is exactly why the old asset tree
 * could not be compiled at build time.
 */
public data class LightingConfig(
    public val shadows: Boolean = true,
    public val ambientLight: Float = 0.5F,
    public val fboWidth: Int,
    public val fboHeight: Int,
    public val blur: Boolean = true,
    public val blurPasses: Int = 3,
) {
    init {
        require(ambientLight in 0F..1F) { "ambientLight $ambientLight is not a 0..1 fraction" }
        require(fboWidth > 0 && fboHeight > 0) { "light buffer is ${fboWidth}x$fboHeight" }
        require(blurPasses >= 0) { "blurPasses $blurPasses" }
    }
}

/** The Scene2D skin a game's UI uses, when it has one. */
public data class UiConfig(public val defaultSkin: ResPath? = null)

/**
 * The game's own top-level settings: what to load first, and how the engine is configured for it.
 *
 * Its sub-configurations are plain data classes and not assets. In the old tree `Lighting`,
 * `Network`, `Physics` and `Scene2D` each extended `Asset<T>`, which gave four things with no
 * identity an `AssetId`, a `path`, a `name` and a place in the global map, purely so that the DSL
 * generator would produce builders for them. Nothing referenced them, and nothing could: they were
 * reachable only through the `GameConfig` that owned them, which is what "not an asset" means.
 */
public data class GameConfig(
    override val id: AssetId,
    public val defaultLevel: Ref<Level>? = null,
    public val defaultCharacter: Ref<Blueprint>? = null,
    public val backgroundTexture: ResPath? = null,
    public val network: NetworkConfig = NetworkConfig(),
    public val physics: PhysicsConfig = PhysicsConfig(),
    public val lighting: LightingConfig? = null,
    public val ui: UiConfig? = null,
    public val movementType: MovementType = MovementType.TopDown,
) : AssetData
