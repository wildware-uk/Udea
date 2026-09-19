package dev.wildware.udea.render.headless

import dev.wildware.udea.assets.Vec2
import org.lwjgl.opengl.GL11

/**
 * The two classes the headless gate exists to tell apart, compiled here in `udea-render` --
 * the one module allowed to see GL.
 *
 * Neither is ever *run*: a GL call cannot be made without a context, and does not need to be.
 * The gate reads compiled references, so compiling these is the whole fixture.
 *
 * Placing them here is also the third acceptance criterion of issue #117 made executable:
 * the identical class that fails the scan inside `udea-core` **passes** inside `udea-render`,
 * because the fix for a violation is always "move it here" and never "add an allowlist entry".
 *
 * Until issue #213 these named LibGDX types (`Texture`, `Vector2`, a `Viewport`, gdx's `Array`),
 * which needed LibGDX on this module's test classpath. LibGDX has left the tree, so the positive
 * control names LWJGL's GL binding - the binding Kool draws through on desktop - and the negative control
 * names an engine value type.
 */

/**
 * Names `org.lwjgl.opengl.GL11`, which is banned in every headless module.
 *
 * The shape that lost the property in the old tree: a GL type named from a class the world tick
 * touched, and nothing failed until "headless" meant booting a window.
 */
internal class GlNamingFixture {

    /** A member reference, so the scan has a member name to report and not just a class. */
    fun maxTextureSize(): Int = GL11.glGetInteger(GL11.GL_MAX_TEXTURE_SIZE)
}

/**
 * Names `dev.wildware.udea.assets.Vec2`, which is **not** banned anywhere.
 *
 * The negative control for the banned-owner table: a scan that reported every type from outside
 * the scanned module, rather than the banned ones, would fail here.
 */
internal class ValueNamingFixture {

    fun squaredLengthOf(vector: Vec2): Float = vector.x * vector.x + vector.y * vector.y
}
