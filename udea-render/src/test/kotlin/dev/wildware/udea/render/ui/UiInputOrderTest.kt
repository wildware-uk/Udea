package dev.wildware.udea.render.ui

import androidx.compose.runtime.Composable
import com.badlogic.gdx.Input
import dev.wildware.composegl.ui.backend.HeadlessBackend
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.onKeyEvent
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.widget.Button
import dev.wildware.udea.render.FrameTime
import dev.wildware.udea.render.RenderResources
import dev.wildware.udea.render.input.GdxKeyboard
import dev.wildware.udea.render.support.HeadlessGl
import dev.wildware.udea.render.support.RecordingBatch
import dev.wildware.udea.render.support.testTargets
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The input contract [GdxKeyboard.install] states and this ticket had to keep: **the interface
 * goes first**, and what it consumes never reaches the gameplay binding under it.
 *
 * Driven through `GdxKeyboard.install` rather than through an `InputMultiplexer` built here, so
 * the order under test is the shipped one. A test that assembled its own chain would pass whatever
 * order the installer used.
 *
 * ## The negative is half the test
 *
 * "The keyboard saw nothing" is a green result for a chain that is wired backwards *and* for a
 * chain that is not wired at all -- a `GdxKeyboard` that never receives anything from anybody
 * looks identical to one the interface is correctly shielding. So every consumed case here has a
 * declined case beside it: a key the composition does not want, and a click on empty screen, both
 * of which must arrive.
 */
class UiInputOrderTest {

    private var gl: HeadlessGl? = null

    private val batch = RecordingBatch()

    private val targets = testTargets(batch = batch.batch, width = 800, height = 600)

    private val frameTime = object : FrameTime {
        override val frameSeconds: Float = 1f / 60f
    }

    @BeforeEach
    fun installGl() {
        gl = HeadlessGl.installed(width = 800, height = 600)
    }

    @AfterEach
    fun removeGl() {
        gl?.uninstall()
        gl = null
    }

    @Test
    fun `a key the composition consumes never reaches the keyboard`() {
        val keyboard = GdxKeyboard()
        val layer = layerShowing(KeyScreen())
        GdxKeyboard.install(layer.input, keyboard)
        // What the device was actually handed, not what `install` returned. The chain under test
        // is the one a key press would arrive on.
        val chain = checkNotNull(gl?.inputProcessor)

        val taken = chain.keyDown(Input.Keys.ESCAPE)

        assertTrue(taken, "the chain should report the interface took Escape")
        assertEquals(
            0,
            keyboard.pressesSince(Input.Keys.ESCAPE),
            "Escape closed a panel AND fired the gameplay binding under it",
        )
    }

    @Test
    fun `a key the composition declines still reaches the keyboard`() {
        val keyboard = GdxKeyboard()
        val layer = layerShowing(KeyScreen())
        GdxKeyboard.install(layer.input, keyboard)
        // What the device was actually handed, not what `install` returned. The chain under test
        // is the one a key press would arrive on.
        val chain = checkNotNull(gl?.inputProcessor)

        val taken = chain.keyDown(Input.Keys.W)

        assertFalse(taken, "nothing in the chain consumes W, so the chain declined it")
        assertEquals(
            1,
            keyboard.pressesSince(Input.Keys.W),
            "walking forward stopped working: the interface is eating keys it does not want",
        )
    }

    @Test
    fun `a click on a button is consumed before the keyboard sees the frame`() {
        val keyboard = GdxKeyboard()
        val screen = ButtonScreen()
        val layer = layerShowing(screen)
        GdxKeyboard.install(layer.input, keyboard)
        // What the device was actually handed, not what `install` returned. The chain under test
        // is the one a key press would arrive on.
        val chain = checkNotNull(gl?.inputProcessor)
        val button = layer.host.root.find(ButtonScreen.TAG)

        val taken = chain.touchDown(
            button.boundsInRoot.centre.x.toInt(),
            button.boundsInRoot.centre.y.toInt(),
            0,
            Input.Buttons.LEFT,
        )
        chain.touchUp(
            button.boundsInRoot.centre.x.toInt(),
            button.boundsInRoot.centre.y.toInt(),
            0,
            Input.Buttons.LEFT,
        )

        assertTrue(taken, "the press on the button was not consumed by the interface")
        assertEquals(1, screen.clicks, "the button's onClick never ran")
    }

    @Test
    fun `a click on empty screen is declined so the world still gets it`() {
        // The control for the test above. `GdxKeyboard` consumes no pointer event either, so what
        // is being asserted is the chain's answer: an interface that returned true for every click
        // would be an interface a player could not shoot through.
        val keyboard = GdxKeyboard()
        val layer = layerShowing(ButtonScreen())
        GdxKeyboard.install(layer.input, keyboard)
        // What the device was actually handed, not what `install` returned. The chain under test
        // is the one a key press would arrive on.
        val chain = checkNotNull(gl?.inputProcessor)

        val taken = chain.touchDown(799, 599, 0, Input.Buttons.LEFT)

        assertFalse(taken, "a click on the bottom-right corner of empty screen was consumed")
    }

    // --- fixture -------------------------------------------------------------------------

    /**
     * A layer with [screen] mounted and one frame drawn, because a click lands by geometry.
     *
     * Nothing can be pointed at until the tree has been laid out, and the layer lays out inside
     * `render`. A test that clicked before drawing would be clicking at the origin and asserting
     * about a button that has no box yet.
     */
    private fun layerShowing(screen: UiScreen): UiLayer {
        val layer = UiLayer(
            RenderResources(batch.batch, targets.offscreen),
            frameTime,
            HeadlessBackend(),
            // One framebuffer pixel per window unit. `HeadlessGl` has no real window behind it,
            // and the shipped default asks `Gdx.graphics` for the back-buffer scale.
            hdpiScale = { 1f },
        )
        layer.show(screen)
        layer.render(targets.offscreen, 0f)
        return layer
    }

    /**
     * A panel that takes Escape and nothing else, with a focused button inside it.
     *
     * The button is not decoration. A key starts at the focused node and walks outwards, so a
     * handler on a panel with nothing focused inside it is never asked -- which is
     * `KeyRouter`'s documented shape and the reason this screen is the realistic one.
     */
    private class KeyScreen : UiScreen {

        @Composable
        override fun content() {
            Box(
                Modifier
                    .fillMaxSize()
                    .onKeyEvent { event -> event.key == Key.Escape }
                    .testTag(TAG),
            ) {
                Button("RESUME", {}, Modifier.testTag(FOCUSED), initialFocus = true)
            }
        }

        companion object {
            const val TAG: String = "udea-test-keys"
            const val FOCUSED: String = "udea-test-keys-focused"
        }
    }

    /**
     * One button in the top-left corner of a full-surface box, so the rest of the surface is
     * empty and a click there has nothing to land on.
     */
    private class ButtonScreen : UiScreen {

        var clicks: Int = 0
            private set

        @Composable
        override fun content() {
            Box(Modifier.fillMaxSize()) {
                Button("PLAY", { clicks++ }, Modifier.testTag(TAG))
            }
        }

        companion object {
            const val TAG: String = "udea-test-button"
        }
    }
}
