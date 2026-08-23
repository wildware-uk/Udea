package dev.wildware.udea.assets

/**
 * A grid of frames in one texture.
 *
 * [texture] is a [ResPath] and not a `String`, which is the fix for the specific bug that
 * `spritePath = "/sprites/orc_elite/orc_elite_idle.png"` in a script
 * (`example/.../orc_elite.udea.kts:122`) and the loader's stripped `sprites/...` key
 * (`common/UdeaGameManager.kt:506`) were two different map entries for one file. There is now no
 * spelling of that path that produces two values.
 *
 * No `TextureRegion`, no `Sprite`, no `by lazy` that reaches into a global asset manager - all of
 * which the old `SpriteAnimation` had. This is data; `udea-render` owns everything with a GL handle
 * in it.
 */
public data class SpriteSheet(
    override val id: AssetId,
    public val texture: ResPath,
    public val columns: Int,
    public val rows: Int,
    /** World units per pixel. The old tree's per-character `orcEliteScale`. */
    public val scale: Float = 1.0F,
) : AssetData {

    init {
        require(columns > 0) { "sprite sheet '$id' has $columns columns" }
        require(rows > 0) { "sprite sheet '$id' has $rows rows" }
        require(scale > 0F && scale.isFinite()) { "sprite sheet '$id' has scale $scale" }
    }

    /** How many frames the grid holds. */
    public val frameCount: Int get() = columns * rows
}

/**
 * A point in an animation that fires something: the frame a sword connects on.
 *
 * Not an [AssetData]: a notify has no identity of its own, is never referenced from anywhere else,
 * and only means anything inside the animation that declares it. Giving it an [AssetId] would be
 * inventing identity so that a list could be typed uniformly, which is how the old tree ended up
 * with `Lighting` and `Network` extending `Asset` for no reason at all.
 */
public data class AnimNotify(
    /** Zero-based frame index into the owning animation's sheet. */
    public val frame: Int,
    /** What listens for it. Matched by name, so it is a name and not an index. */
    public val name: String,
) {
    init {
        require(frame >= 0) { "anim notify '$name' is on frame $frame" }
        require(name.isNotBlank()) { "an anim notify must be named; a listener matches on the name" }
    }
}

/**
 * One animation: a sheet, how fast to run it, and what it fires along the way.
 *
 * `frameTime` is seconds per frame, as authored. It is *presentation* time, not simulation time,
 * which is why it is not tick-denominated the way `udea-gas` durations are: an animation is drawn
 * at whatever rate the renderer runs, and a notify that must affect the simulation is raised
 * through a gameplay event rather than by the animation advancing.
 */
public data class SpriteAnimation(
    override val id: AssetId,
    public val sheet: Ref<SpriteSheet>,
    public val frameTime: Float = 0.1F,
    public val loop: Boolean = true,
    /** Whether another animation may cut this one short. The old spelling was `interruptable`. */
    public val interruptible: Boolean = true,
    public val notifies: List<AnimNotify> = emptyList(),
) : AssetData {

    init {
        require(frameTime > 0F && frameTime.isFinite()) {
            "animation '$id' has frameTime $frameTime; a frame that takes no time never advances"
        }
        val duplicates = notifies.groupingBy { it.name }.eachCount().filterValues { it > 1 }.keys
        require(duplicates.isEmpty()) { "animation '$id' declares notify $duplicates twice" }
    }
}

/** The animations one character can play, looked up by the state machine that drives it. */
public data class SpriteAnimationSet(
    override val id: AssetId,
    public val animations: List<Ref<SpriteAnimation>>,
) : AssetData {

    init {
        require(animations.isNotEmpty()) { "animation set '$id' holds no animations" }
    }
}
