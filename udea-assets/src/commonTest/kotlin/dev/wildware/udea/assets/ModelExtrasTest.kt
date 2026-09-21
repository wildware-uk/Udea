package dev.wildware.udea.assets

import dev.wildware.udea.assets.AssetValue.BoolValue
import dev.wildware.udea.assets.AssetValue.FloatValue
import dev.wildware.udea.assets.AssetValue.IntValue
import dev.wildware.udea.assets.AssetValue.ListValue
import dev.wildware.udea.assets.AssetValue.TextValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The values an artist typed into a model's Custom Properties, as a game reads them (issue #271).
 *
 * A game asks for the type it expects. Three answers are possible and each is pinned here: the
 * value, `null` when the model does not say, and a failure naming the key when the model says
 * something of another type - because a `mass` typed as the text `"12"` is an authoring mistake,
 * and reading it as "no mass" would hide it.
 */
class ModelExtrasTest {

    private val module = ModelExtras(
        mapOf(
            "module_size" to TextValue("small"),
            "mass" to FloatValue(12.5f),
            "power" to IntValue(3),
            "armoured" to BoolValue(true),
            "offset" to ListValue(listOf(FloatValue(0f), FloatValue(0f), FloatValue(0.5f))),
            "clearance" to FloatValue(2f),
        ),
    )

    @Test
    fun `each typed read returns the value the artist wrote`() {
        assertEquals("small", module.text("module_size"))
        assertEquals(12.5f, module.float("mass"))
        assertEquals(3, module.int("power"))
        assertEquals(true, module.bool("armoured"))
        assertEquals(listOf(0f, 0f, 0.5f), module.floats("offset"))
    }

    @Test
    fun `a whole number reads as a float and a whole float reads as an int`() {
        // Blender writes an Int property as `3` and a Float one as `3.0`; a game asking for a
        // number should not have to know which box the artist ticked.
        assertEquals(3f, module.float("power"))
        assertEquals(2, module.int("clearance"))
    }

    @Test
    fun `a fractional float is not an int`() {
        val failure = assertFailsWith<ModelExtraTypeException> { module.int("mass") }
        assertTrue("mass" in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun `a key the model does not have reads as null for every type`() {
        assertNull(module.float("ground_clearance"))
        assertNull(module.int("ground_clearance"))
        assertNull(module.text("ground_clearance"))
        assertNull(module.bool("ground_clearance"))
        assertNull(module.floats("ground_clearance"))
        assertFalse("ground_clearance" in module)
        assertTrue("mass" in module)
    }

    @Test
    fun `a value of another type fails naming the key and both types`() {
        val failure = assertFailsWith<ModelExtraTypeException> { module.float("module_size") }

        val message = failure.message.orEmpty()
        assertTrue("module_size" in message, message)
        assertTrue("text" in message, "it must say what the value is: $message")
        assertTrue("number" in message, "it must say what was asked for: $message")
        assertEquals("module_size", failure.key)
    }

    @Test
    fun `the keys are listed in sorted order`() {
        assertEquals(listOf("armoured", "clearance", "mass", "module_size", "offset", "power"), module.keys)
    }

    @Test
    fun `two extras holding the same values are equal`() {
        assertEquals(ModelExtras(mapOf("mass" to FloatValue(1f))), ModelExtras(mapOf("mass" to FloatValue(1f))))
        assertEquals(ModelExtras.EMPTY, ModelExtras(emptyMap()))
    }

    @Test
    fun `a value that is not a plain value is refused when the extras are made`() {
        assertFailsWith<IllegalArgumentException> {
            ModelExtras(mapOf("where" to AssetValue.PathValue(ResPath("a/b.png"))))
        }
        assertFailsWith<IllegalArgumentException> {
            ModelExtras(mapOf("names" to ListValue(listOf(TextValue("a")))))
        }
    }
}
