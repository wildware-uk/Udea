package dev.wildware.udea.render.input

import dev.wildware.udea.core.identity.NetId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the pointer puts into an [Intent] (issue #262): **a world point, and never a pixel.**
 *
 * ## The rule this file is really about
 *
 * `Intent` is the only thing simulation reads about input, so whatever is in it is what the
 * simulation is allowed to know. A screen coordinate in here would mean a tick whose behaviour
 * depended on the window size and on where a camera happened to be pointing - unreproducible from a
 * recording, different on two machines watching the same match, and a direct route round the rule
 * that a `RenderSystem` reads the world and the world never reads a camera. So presentation
 * (`WorldPointer`) does the un-projection on the render thread and puts the **result** here: the
 * point on the ground the player is pointing at, and the entity they are pointing at.
 *
 * [`the intent names no pixel`] is that stated as a test rather than as a paragraph.
 */
class PointerIntentTest {

    private val catalog = InputCatalog.of(actions = listOf("game/attack"), axes = listOf("game/move"))

    @Test
    fun `a fresh intent is pointing at nothing`() {
        val intent = Intent(catalog)

        assertFalse(intent.hasPointer, "a fresh intent claims the player is pointing somewhere")
        assertEquals(NetId.NONE, intent.pointerEntity, "a fresh intent claims something is under the pointer")
        assertEquals(0f, intent.scroll, "a fresh intent claims a wheel turn")
        assertFalse(intent.dragStarted, "a fresh intent claims a drag began")
        assertFalse(intent.dragEnded, "a fresh intent claims a drag ended")
        assertTrue(intent.isIdle(), "an intent with nothing in it is not idle")
    }

    @Test
    fun `the point under the cursor is what the tick reads`() {
        val intent = Intent(catalog)

        intent.setPointer(12.5f, -3.25f, NetId.of(index = 7, generation = 2))

        assertTrue(intent.hasPointer, "the pointer was set and the intent says there is none")
        assertEquals(12.5f, intent.pointerX, "the pointer's world x")
        assertEquals(-3.25f, intent.pointerY, "the pointer's world y")
        assertEquals(NetId.of(index = 7, generation = 2), intent.pointerEntity, "what is under the pointer")
        assertFalse(intent.isIdle(), "a tick in which the player pointed at a unit counted as idle")
    }

    @Test
    fun `the intent names no pixel`() {
        // A structural check, and the cheapest one there is: if a screen coordinate is ever added to
        // this class, this test is what has to be deleted to do it - which is a conversation rather
        // than an accident. The names are the surface a system reads; `world` and `pointer` are
        // world units, and `pixel`, `screen` and `view` are the words presentation uses.
        // Java reflection, not `KClass.members`: this module does not put kotlin-reflect on a test
        // classpath, and the declared methods are the whole of what a system can call anyway.
        val named = Intent::class.java.declaredMethods.map { it.name } +
            Intent::class.java.declaredFields.map { it.name }

        val screenish = named.filter { name ->
            val lower = name.lowercase()
            "pixel" in lower || "screen" in lower || "viewx" in lower || "viewy" in lower
        }

        assertEquals(
            emptyList(),
            screenish,
            "Intent has grown something that sounds like a screen coordinate. The simulation reads " +
                "this class and must not read a camera: un-project in presentation and put the " +
                "world point here instead",
        )
    }

    @Test
    fun `a wheel notch and a drag reach the tick`() {
        val intent = Intent(catalog)

        intent.setScroll(-2f)
        intent.setDragStart(1f, 2f)
        intent.setDragEnd(5f, 6f)

        assertEquals(-2f, intent.scroll, "the wheel")
        assertTrue(intent.dragStarted, "a drag began and the intent does not say so")
        assertEquals(1f, intent.dragStartX, "where the drag began: x")
        assertEquals(2f, intent.dragStartY, "where the drag began: y")
        assertTrue(intent.dragEnded, "a drag ended and the intent does not say so")
        assertEquals(5f, intent.dragEndX, "where the drag ended: x")
        assertEquals(6f, intent.dragEndY, "where the drag ended: y")
    }

    @Test
    fun `clearing an intent forgets the pointer`() {
        // The tick is handed a *cleared* intent, and every source writes into it. A pointer left
        // behind would be last tick's order given again - the `Q.Axis8` bug's shape, which walked a
        // standing character across the map for ever.
        val intent = Intent(catalog).apply {
            setPointer(1f, 2f, NetId.of(index = 1, generation = 0))
            setScroll(3f)
            setDragStart(4f, 5f)
            setDragEnd(6f, 7f)
        }

        intent.clear()

        assertFalse(intent.hasPointer, "a cleared intent is still pointing somewhere")
        assertEquals(NetId.NONE, intent.pointerEntity, "a cleared intent still has something under it")
        assertEquals(0f, intent.scroll, "a cleared intent still has a wheel turn")
        assertFalse(intent.dragStarted, "a cleared intent still has a drag beginning")
        assertFalse(intent.dragEnded, "a cleared intent still has a drag ending")
    }

    @Test
    fun `a copied intent carries the pointer`() {
        val source = Intent(catalog).apply {
            setPointer(1f, 2f, NetId.of(index = 3, generation = 1))
            setScroll(4f)
            setDragStart(5f, 6f)
            setDragEnd(7f, 8f)
        }
        val copy = Intent(catalog)

        copy.copyFrom(source)

        assertTrue(copy.hasPointer, "the copy is not pointing anywhere")
        assertEquals(1f, copy.pointerX)
        assertEquals(2f, copy.pointerY)
        assertEquals(NetId.of(index = 3, generation = 1), copy.pointerEntity)
        assertEquals(4f, copy.scroll)
        assertEquals(5f, copy.dragStartX)
        assertEquals(8f, copy.dragEndY)
    }

    @Test
    fun `a pointer that is not a number is refused`() {
        val intent = Intent(catalog)

        assertFailsWith<IllegalArgumentException> { intent.setPointer(Float.NaN, 0f, NetId.NONE) }
        assertFailsWith<IllegalArgumentException> { intent.setScroll(Float.POSITIVE_INFINITY) }
        assertFailsWith<IllegalArgumentException> { intent.setDragStart(0f, Float.NaN) }
        assertFailsWith<IllegalArgumentException> { intent.setDragEnd(Float.NaN, 0f) }
    }
}
