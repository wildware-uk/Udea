package dev.wildware.udea.render.input

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The id space, the axis arithmetic and the edge detection — with no window, no context and no
 * hardware anywhere in the process.
 *
 * That is the headline property, not a convenience: the old `ControllerSystem` called `Gdx.input`
 * from inside `onTick`, so *none* of this could be asserted without booting a display, and none
 * of it ever was.
 */
class InputModelTest {

    // --- ids ---------------------------------------------------------------------------------

    /**
     * The bug `ControlId++` had: ids depended on the order assets were evaluated in.
     *
     * Every permutation of the same names has to produce the same assignment, or a recorded input
     * stream means something different in a process that loaded its scripts in another order.
     */
    @Test
    fun `ids are assigned from sorted names, whatever order they are declared in`() {
        val forwards = InputCatalog.of(
            listOf("game/attack", "game/block", "game/dash"),
            listOf("game/aim", "game/move"),
        )
        val backwards = InputCatalog.of(
            listOf("game/dash", "game/block", "game/attack"),
            listOf("game/move", "game/aim"),
        )

        assertEquals(forwards.actions, backwards.actions)
        assertEquals(forwards.axes, backwards.axes)
        assertEquals(0, forwards.action("game/attack").value)
        assertEquals(0, backwards.action("game/attack").value)
        assertEquals(2, backwards.action("game/dash").value)
        assertEquals(1, backwards.axis("game/move").value)
    }

    /** A name declared twice would leave one of the two unaddressable. */
    @Test
    fun `a duplicate name is refused rather than deduplicated`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            InputCatalog.of(listOf("g/a", "g/a"), emptyList())
        }
        assertTrue("unaddressable" in failure.message.orEmpty(), failure.message.orEmpty())
    }

    /** An unknown name is loud: the alternative is a control that silently never fires. */
    @Test
    fun `an unknown name names what does exist`() {
        val catalog = InputCatalog.of(listOf("g/attack"), listOf("g/move"))
        val failure = assertFailsWith<IllegalArgumentException> { catalog.action("g/atack") }
        assertTrue("g/attack" in failure.message.orEmpty(), failure.message.orEmpty())
    }

    // --- axes --------------------------------------------------------------------------------

    /** Opposing keys are what a player means by "both": nothing. */
    @Test
    fun `an opposing key pair cancels to exactly zero`() {
        val keys = FakeKeyboard()
        val source = DeviceIntent(BINDINGS, keys)
        val intent = Intent(BINDINGS.catalog)

        keys.hold(KEY_LEFT)
        keys.hold(KEY_RIGHT)
        source.sample(intent)

        assertEquals(0f, intent.axisX(MOVE))
        assertEquals(0f, intent.axisY(MOVE))
    }

    /**
     * A diagonal is length 1, so walking north-east is not 41% faster than walking north.
     *
     * This is the half of `ControllerSystem.nor()` that was right and is kept.
     */
    @Test
    fun `a keyboard diagonal normalises to length one`() {
        val keys = FakeKeyboard()
        val source = DeviceIntent(BINDINGS, keys)
        val intent = Intent(BINDINGS.catalog)

        keys.hold(KEY_RIGHT)
        keys.hold(KEY_UP)
        source.sample(intent)

        val length = sqrt(
            intent.axisX(MOVE) * intent.axisX(MOVE) + intent.axisY(MOVE) * intent.axisY(MOVE),
        )
        assertTrue(abs(length - 1f) < 1e-5f, "diagonal length was $length")
        assertTrue(intent.axisX(MOVE) > 0f && intent.axisY(MOVE) > 0f)
    }

    /** A single key is full deflection, not `1/sqrt(2)` of one. */
    @Test
    fun `one key is full deflection`() {
        val keys = FakeKeyboard()
        val source = DeviceIntent(BINDINGS, keys)
        val intent = Intent(BINDINGS.catalog)

        keys.hold(KEY_LEFT)
        source.sample(intent)

        assertEquals(-1f, intent.axisX(MOVE))
    }

    /** Inside the dead area the vector is zero, not "nearly zero" — a drifting stick is a bug. */
    @Test
    fun `a stick inside the deadzone reads exactly zero`() {
        val pad = FakePad(x = 0.2f, y = 0.1f)
        val source = DeviceIntent(BINDINGS, KeyboardState.NONE, pad)
        val intent = Intent(BINDINGS.catalog)

        source.sample(intent)

        assertEquals(0f, intent.axisX(MOVE))
        assertEquals(0f, intent.axisY(MOVE))
    }

    /**
     * A half-pushed stick walks; it does not sprint.
     *
     * `ControllerSystem` called `nor()` unconditionally, which is the line that made every
     * deflection full deflection and a gamepad useless for anything but running.
     */
    @Test
    fun `a partly deflected stick keeps its magnitude`() {
        val pad = FakePad(x = 0.6f, y = 0f)
        val source = DeviceIntent(BINDINGS, KeyboardState.NONE, pad)
        val intent = Intent(BINDINGS.catalog)

        source.sample(intent)

        val x = intent.axisX(MOVE)
        assertTrue(x > 0.4f && x < 0.6f, "expected a partial deflection, was $x")
    }

    /** Pads report up as negative; world space calls up positive, and the binding flips it. */
    @Test
    fun `the stick y axis is flipped into world space`() {
        val pad = FakePad(x = 0f, y = -1f)
        val source = DeviceIntent(BINDINGS, KeyboardState.NONE, pad)
        val intent = Intent(BINDINGS.catalog)

        source.sample(intent)

        assertTrue(intent.axisY(MOVE) > 0.9f, "stick up read ${intent.axisY(MOVE)}")
    }

    // --- edges -------------------------------------------------------------------------------

    /**
     * The acceptance case: a key pressed **and released** between two rendered frames.
     *
     * The key is never down at any sample point, so `Gdx.input.isKeyPressed` reports nothing at
     * all and `isKeyJustPressed` — reset per frame — reports it to whichever tick the frame
     * happened to contain, or to none. Counted edges report it exactly once.
     */
    @Test
    fun `a press and release between two ticks produces exactly one just-pressed`() {
        val keys = FakeKeyboard()
        val source = DeviceIntent(BINDINGS, keys)
        val intent = Intent(BINDINGS.catalog)

        keys.tap(KEY_FIRE)
        var edges = 0
        repeat(5) {
            intent.clear()
            source.sample(intent)
            if (intent.isJustPressed(FIRE)) edges++
            assertFalse(intent.isPressed(FIRE), "a released key must never read as held")
        }

        assertEquals(1, edges, "one tap produced $edges just-pressed intents")
    }

    /** A key held across many ticks is one edge and then a level. */
    @Test
    fun `a held key is one edge followed by a level`() {
        val keys = FakeKeyboard()
        val source = DeviceIntent(BINDINGS, keys)
        val intent = Intent(BINDINGS.catalog)

        keys.press(KEY_FIRE)
        var edges = 0
        var held = 0
        repeat(4) {
            intent.clear()
            source.sample(intent)
            if (intent.isJustPressed(FIRE)) edges++
            if (intent.isPressed(FIRE)) held++
        }

        assertEquals(1, edges, "holding a key produced $edges edges")
        assertEquals(4, held)
    }

    /** Two actions on one key must both see the press; the first must not eat it. */
    @Test
    fun `two actions bound to one key both receive the edge`() {
        val bindings = InputBindings(
            actions = listOf(
                ActionBinding("g/a", keys = intArrayOf(KEY_FIRE)),
                ActionBinding("g/b", keys = intArrayOf(KEY_FIRE)),
            ),
            axes = emptyList(),
        )
        val keys = FakeKeyboard()
        val source = DeviceIntent(bindings, keys)
        val intent = Intent(bindings.catalog)

        keys.tap(KEY_FIRE)
        source.sample(intent)

        assertTrue(intent.isJustPressed(bindings.catalog.action("g/a")))
        assertTrue(intent.isJustPressed(bindings.catalog.action("g/b")))
    }

    // --- injected ----------------------------------------------------------------------------

    /** The agent's press is a level plus an edge, exactly like a key's. */
    @Test
    fun `an injected press produces one edge and holds until released`() {
        val injected = InjectedIntent(BINDINGS.catalog)
        val intent = Intent(BINDINGS.catalog)

        injected.press(FIRE)
        intent.clear(); injected.sample(intent)
        assertTrue(intent.isJustPressed(FIRE))
        assertTrue(intent.isPressed(FIRE))

        intent.clear(); injected.sample(intent)
        assertFalse(intent.isJustPressed(FIRE), "one press produced a second edge")
        assertTrue(intent.isPressed(FIRE))

        injected.release(FIRE)
        intent.clear(); injected.sample(intent)
        assertFalse(intent.isPressed(FIRE))
    }

    /** An injected tap is the synthesised version of a key pressed between two frames. */
    @Test
    fun `an injected tap is an edge with no hold`() {
        val injected = InjectedIntent(BINDINGS.catalog)
        val intent = Intent(BINDINGS.catalog)

        injected.tap(FIRE)
        intent.clear(); injected.sample(intent)

        assertTrue(intent.isJustPressed(FIRE))
        assertFalse(intent.isPressed(FIRE))
    }

    /** An out-of-range axis is clamped at the door, not at every reader. */
    @Test
    fun `an injected axis is clamped`() {
        val injected = InjectedIntent(BINDINGS.catalog)
        val intent = Intent(BINDINGS.catalog)

        injected.setAxis(MOVE, 5f, Float.NaN)
        injected.sample(intent)

        assertEquals(1f, intent.axisX(MOVE))
        assertEquals(0f, intent.axisY(MOVE))
    }

    /** A disconnecting agent must not leave the character walking into a wall forever. */
    @Test
    fun `releaseAll centres everything`() {
        val injected = InjectedIntent(BINDINGS.catalog)
        val intent = Intent(BINDINGS.catalog)

        injected.press(FIRE)
        injected.setAxis(MOVE, 1f, 1f)
        injected.releaseAll()
        injected.sample(intent)

        assertTrue(intent.isIdle(), "after releaseAll the intent was $intent")
    }

    // --- composite ---------------------------------------------------------------------------

    /** A human and an agent can both drive; neither cancels the other's held action. */
    @Test
    fun `a composite ors held actions and clamps summed axes`() {
        val keys = FakeKeyboard()
        val injected = InjectedIntent(BINDINGS.catalog)
        val composite = CompositeIntent(
            BINDINGS.catalog,
            listOf(DeviceIntent(BINDINGS, keys), injected),
        )
        val intent = Intent(BINDINGS.catalog)

        keys.hold(KEY_RIGHT)
        injected.setAxis(MOVE, 1f, 0f)
        injected.press(FIRE)
        composite.sample(intent)

        assertTrue(intent.isPressed(FIRE), "the agent's press did not reach the intent")
        assertEquals(1f, intent.axisX(MOVE), "two sources pushing right exceeded full deflection")
    }

    private companion object {

        const val KEY_LEFT: Int = 29
        const val KEY_RIGHT: Int = 32
        const val KEY_UP: Int = 51
        const val KEY_DOWN: Int = 47
        const val KEY_FIRE: Int = 62

        val BINDINGS: InputBindings = InputBindings(
            actions = listOf(ActionBinding("g/fire", keys = intArrayOf(KEY_FIRE))),
            axes = listOf(
                Axis2DBinding(
                    name = "g/move",
                    negativeX = KEY_LEFT,
                    positiveX = KEY_RIGHT,
                    negativeY = KEY_DOWN,
                    positiveY = KEY_UP,
                    gamepadAxisX = 0,
                    gamepadAxisY = 1,
                ),
            ),
        )

        val MOVE: AxisId = BINDINGS.catalog.axis("g/move")
        val FIRE: ActionId = BINDINGS.catalog.action("g/fire")
    }
}

/**
 * A keyboard a test can press, with the same level-plus-counted-edge shape the real one has.
 *
 * [tap] is the case that cannot be expressed by any `isKeyPressed`-shaped fake: the key goes down
 * and up between two samples and is never observably held.
 */
internal class FakeKeyboard : KeyboardState {

    private val down = HashSet<Int>()
    private val presses = HashMap<Int, Int>()

    /** Holds a key down, recording the edge. */
    fun press(keycode: Int) {
        down += keycode
        presses[keycode] = (presses[keycode] ?: 0) + 1
    }

    /** Holds a key down with no edge, for a key already held when the test started. */
    fun hold(keycode: Int) {
        down += keycode
    }

    /** Down and up again before anything sampled: the edge exists, the level never did. */
    fun tap(keycode: Int) {
        presses[keycode] = (presses[keycode] ?: 0) + 1
    }

    fun release(keycode: Int) {
        down -= keycode
    }

    override fun isKeyDown(keycode: Int): Boolean = keycode in down

    override fun pressesSince(keycode: Int): Int = presses[keycode] ?: 0

    override fun endSample() {
        presses.clear()
    }
}

/** A stick held at one deflection. */
internal class FakePad(private val x: Float, private val y: Float) : GamepadState {

    override val isConnected: Boolean get() = true

    override fun axis(axis: Int): Float = if (axis == 0) x else y

    override fun isButtonDown(button: Int): Boolean = false

    override fun pressesSince(button: Int): Int = 0

    override fun endSample(): Unit = Unit
}
