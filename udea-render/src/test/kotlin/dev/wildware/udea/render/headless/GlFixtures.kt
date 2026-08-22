package dev.wildware.udea.render.headless

import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.math.Vector2

/**
 * The two classes the headless gate exists to tell apart, compiled here in `udea-render` --
 * the one module allowed to see GL.
 *
 * Neither is ever *run*: a `Texture` cannot be constructed without a GL context, and does not
 * need to be. The gate reads compiled references, so compiling these is the whole fixture.
 *
 * Placing them here is also the third acceptance criterion of issue #117 made executable:
 * the identical class that fails the scan inside `udea-core` **passes** inside `udea-render`,
 * because the fix for a violation is always "move it here" and never "add an allowlist entry".
 */

/**
 * Names `com.badlogic.gdx.graphics.Texture`, which is banned in every headless module.
 *
 * This is the shape that lost the property in the old tree: `SpriteRenderer.kt` imported
 * `Texture` into a component the world tick touched, and nothing failed until "headless"
 * meant booting a window.
 */
internal class TextureNamingFixture {

    /** A member reference, so the scan has a member name to report and not just a class. */
    fun widthOf(texture: Texture): Int = texture.width
}

/**
 * Names `com.badlogic.gdx.math.Vector2`, which is **not** banned anywhere.
 *
 * The negative control for the banned-owner table. `gdx-math` is headless value types the
 * simulation legitimately uses; a gate that banned all of `com.badlogic.gdx` would be turned
 * off within a week, and `UDEA-MG-002` draws the same line at the dependency level.
 */
internal class MathNamingFixture {

    fun lengthOf(vector: Vector2): Float = vector.len()
}
