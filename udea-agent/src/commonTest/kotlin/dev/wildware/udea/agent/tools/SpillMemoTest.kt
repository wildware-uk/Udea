package dev.wildware.udea.agent.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The spill memo both toolsets share: file once, remember recent, forget least recently used. */
class SpillMemoTest {

    private class Store {
        val filed = ArrayList<String>()
        fun file(text: String): String = "cap_${filed.size}".also { filed += text }
    }

    @Test
    fun `a text read twice is filed once`() {
        val store = Store()
        val memo = SpillMemo(capacity = 2)

        val first = memo.handleFor("a", store::file)
        val second = memo.handleFor("a", store::file)

        assertEquals(first, second)
        assertEquals(listOf("a"), store.filed)
    }

    @Test
    fun `the least recently used text is the one forgotten`() {
        val store = Store()
        val memo = SpillMemo(capacity = 2)
        memo.handleFor("a", store::file)
        memo.handleFor("b", store::file)
        // Reading `a` again makes `b` the older of the two, so `c` pushes `b` out and not `a`.
        memo.handleFor("a", store::file)
        memo.handleFor("c", store::file)

        memo.handleFor("a", store::file)
        memo.handleFor("b", store::file)

        assertEquals(listOf("a", "b", "c", "b"), store.filed)
    }

    @Test
    fun `a store that files nothing is asked again next time`() {
        var asked = 0
        val memo = SpillMemo(capacity = 2)

        assertNull(memo.handleFor("a") { asked++; null })
        assertNull(memo.handleFor("a") { asked++; null })

        assertEquals(2, asked)
    }
}
