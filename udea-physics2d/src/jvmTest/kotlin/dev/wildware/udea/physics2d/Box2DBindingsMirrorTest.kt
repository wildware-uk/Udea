package dev.wildware.udea.physics2d

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The desktop and Android `Box2DBindings.kt` differ in their Java package and in nothing else.
 *
 * `box2d-jni` publishes one API twice, as `box2d.*` in the JVM jar and `box2dandroid.*` in the
 * AAR, and `src/box2dMain` is compiled into both targets against the names these two files
 * declare. Android is compiled here but never run, so a name mapped to a different Box2D
 * function on one side - a transposed `getShapeIdA`, say - would ship silently. This test makes
 * the Android file a checked function of the desktop one instead.
 */
class Box2DBindingsMirrorTest {

    private val sources = File(checkNotNull(System.getProperty(PROJECT_DIR)) { "$PROJECT_DIR is not set" }, "src")

    @Test
    fun `the Android bindings are the desktop bindings with the Android package`() {
        val desktop = File(sources, "jvmMain/$BINDINGS").readText()
        val android = File(sources, "androidMain/$BINDINGS").readText()

        assertEquals(androidFrom(desktop), android)
    }

    @Test
    fun `the comparison is not vacuous`() {
        // Known negatives for the check above: both files really declare aliases, the package
        // rewrite really changes the text, and a single swapped name is really caught.
        val desktop = File(sources, "jvmMain/$BINDINGS").readText()
        assertTrue(Regex("""internal typealias \w+ = box2d\.""").findAll(desktop).count() > 20, "desktop aliases")
        assertTrue(androidFrom(desktop) != desktop, "the rewrite changed nothing")
        val swapped = androidFrom(desktop).replace("b2ContactBeginTouchEvent.Raw", "b2ContactEndTouchEvent.Raw")
        assertTrue(swapped != androidFrom(desktop), "a swapped alias would go unnoticed")
    }

    /** The desktop file with every desktop package name replaced by its Android one. */
    private fun androidFrom(desktop: String): String =
        desktop.replace("box2d.", "box2dandroid.").replace("de.fabmax.box2djni.", "de.fabmax.box2dandroid.")

    private companion object {
        const val PROJECT_DIR = "udea.physics2d.projectDir"
        const val BINDINGS = "kotlin/dev/wildware/udea/physics2d/Box2DBindings.kt"
    }
}
