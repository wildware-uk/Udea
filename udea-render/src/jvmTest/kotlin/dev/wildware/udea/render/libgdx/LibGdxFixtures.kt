package dev.wildware.udea.render.libgdx

import com.badlogic.gdx.math.MathUtils
import com.badlogic.gdx.scenes.scene2d.Stage

/*
 * The classes `LibGdxScanTest` tells apart. Neither is run: the gate reads compiled references,
 * so compiling them is the whole fixture.
 *
 * The negative control is `ValueNamingFixture`, shared with the headless gate: it names only an
 * engine value type.
 */

/** Names scene2d's `Stage`: the UI toolkit the ComposeGL layer replaced (issues #185-#189). */
internal class Scene2dNamingFixture {

    fun actOnce(): Float = Stage().act(DELTA)

    private companion object {
        const val DELTA: Float = 1f / 60f
    }
}

/** Names LibGDX outside scene2d, so only the table's general entry covers it. */
internal class GdxNamingFixture {

    fun clampToUnit(value: Float): Float = MathUtils.clamp(value, 0f, 1f)
}
