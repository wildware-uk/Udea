package dev.wildware.udea.agent.query

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * An enum slot written by constant name, on the JVM, where the constants can be listed.
 *
 * The Wasm side of the same `expect` refuses instead - `WasmPlatformLimitsTest`.
 */
class FieldValuesEnumTest {

    private enum class Stance { Guard, Charge }

    /** A constant with a body compiles to a subclass of the enum, whose class has no constants. */
    private enum class Order {
        Hold,
        Advance { override fun toString(): String = "forward" },
    }

    @Test
    fun `a constant is found by its name, never by its ordinal`() {
        assertEquals(Stance.Charge, FieldValues.parse(Stance.Guard, "Charge"))
        assertNull(FieldValues.parse(Stance.Guard, "1"))
        assertNull(FieldValues.parse(Stance.Guard, "Retreat"))
    }

    @Test
    fun `a slot holding a constant with a body still lists its siblings`() {
        assertEquals(Order.Hold, FieldValues.parse(Order.Advance, "Hold"))
        assertEquals("one of Hold, Advance", FieldValues.typeNameOf(Order.Advance))
    }
}
