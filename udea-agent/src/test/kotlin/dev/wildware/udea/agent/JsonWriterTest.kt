package dev.wildware.udea.agent

import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The JSON writer, against the four things that actually break a hand-written one.
 *
 * Every case here is a document an agent would silently misread rather than fail on, which is
 * why they are worth pinning: `NaN` produces JSON no parser accepts, a raw control character
 * produces a string that terminates early, and a locale-formatted float produces a *valid*
 * document with the wrong number of array elements in it.
 */
class JsonWriterTest {

    private val originalLocale: Locale = Locale.getDefault()

    @AfterTest
    fun restoreLocale() {
        Locale.setDefault(originalLocale)
    }

    @Test
    fun `writes a nested document`() {
        val json = Json.render {
            put("frame", 91422L)
            put("paused", false)
            obj("ui") { put("screen", "GameScreen") }
            arr("events") {
                value("merge:cherry")
                value("click:Restart")
            }
        }

        assertEquals(
            """{"frame":91422,"paused":false,"ui":{"screen":"GameScreen"},""" +
                """"events":["merge:cherry","click:Restart"]}""",
            json,
        )
    }

    @Test
    fun `floats round to four decimal places`() {
        assertEquals("""{"v":1.2346}""", Json.render { put("v", 1.23456f) })
        assertEquals("""{"v":1.2345}""", Json.render { put("v", 1.23449f) })
    }

    @Test
    fun `a whole float keeps no trailing zero`() {
        assertEquals("""{"v":38}""", Json.render { put("v", 38f) })
        assertEquals("""{"v":-38}""", Json.render { put("v", -38f) })
    }

    @Test
    fun `a fraction keeps no trailing zeros`() {
        assertEquals("""{"v":0.5}""", Json.render { put("v", 0.5f) })
        assertEquals("""{"v":0.0625}""", Json.render { put("v", 0.0625f) })
        assertEquals("""{"v":-0.25}""", Json.render { put("v", -0.25f) })
    }

    @Test
    fun `negative zero renders as zero`() {
        // JSON has one zero. "-0" is a token an agent would have to reason about for nothing.
        assertEquals("""{"v":0}""", Json.render { put("v", -0.0f) })
    }

    @Test
    fun `a float too large to scale falls back rather than wrapping`() {
        // 1e20 * 10 000 overflows a Long. The fallback must still produce a parseable number,
        // not a wrapped one.
        val rendered = Json.render { put("v", 1.0e20f) }
        val number = rendered.substringAfter(":").substringBefore("}").toDouble()
        assertTrue(number > 9.9e19 && number < 1.01e20, rendered)
    }

    @Test
    fun `NaN and both infinities render as null`() {
        assertEquals("""{"v":null}""", Json.render { put("v", Float.NaN) })
        assertEquals("""{"v":null}""", Json.render { put("v", Float.POSITIVE_INFINITY) })
        assertEquals("""{"v":null}""", Json.render { put("v", Float.NEGATIVE_INFINITY) })
    }

    @Test
    fun `control characters are escaped`() {
        val control = "a" + 0.toChar() + "b" + 31.toChar() + "c"
        val rendered = Json.render { put("v", control) }
        assertEquals("{\"v\":\"a\\u0000b\\u001fc\"}", rendered)
    }

    @Test
    fun `quotes backslashes and the named escapes are escaped`() {
        val rendered = Json.render { put("v", "he said \"hi\"\\\n\r\t") }
        assertEquals("""{"v":"he said \"hi\"\\\n\r\t"}""", rendered)
    }

    @Test
    fun `keys are escaped as well as values`() {
        val rendered = Json.render { put("a\"b", 1) }
        assertEquals("""{"a\"b":1}""", rendered)
    }

    @Test
    fun `float output is byte-identical under a comma-decimal locale`() {
        Locale.setDefault(Locale.ROOT)
        val underRoot = renderFloats()

        Locale.setDefault(Locale.GERMANY)
        val underGermany = renderFloats()

        // The trap this pins: String.format("%.4f") under GERMANY renders 46,0 - and
        // "pos":[46,0,-3.25] is not a failure, it is a valid array with three elements.
        assertEquals(underRoot, underGermany)
        assertEquals("""{"timeScale":1.5,"pos":[46,-3.25]}""", underGermany)
    }

    @Test
    fun `truncation marks the value it clipped`() {
        val json = Json()
        json.beginArray()
        json.value("0123456789", 6)
        json.value("01234", 6)
        json.endArray()

        assertEquals("""["01234~","01234"]""", json.toString())
    }

    @Test
    fun `reset keeps the buffer and empties the document`() {
        val json = Json()
        json.obj { put("a", 1) }
        assertEquals("""{"a":1}""", json.toString())

        json.reset().obj { put("b", 2) }
        assertEquals("""{"b":2}""", json.toString())
    }

    @Test
    fun `an unclosed container is refused rather than published`() {
        val json = Json()
        json.beginObject()
        json.put("a", 1)

        // Truncated JSON reaching an agent reads as a broken game; a caller error must not be
        // able to manufacture that.
        val failure = assertFailsWith<IllegalStateException> { json.toString() }
        assertTrue(failure.message!!.contains("unclosed"), failure.message!!)
    }

    @Test
    fun `null strings render as JSON null and not as text`() {
        assertEquals("""{"v":null}""", Json.render { put("v", null as String?) })
    }

    @Test
    fun `raw splices a rendered value without re-quoting it`() {
        val rendered = Json.render {
            key("result")
            raw("""{"netId":412}""")
            put("ok", true)
        }
        assertEquals("""{"result":{"netId":412},"ok":true}""", rendered)
    }

    private fun renderFloats(): String = Json.render {
        put("timeScale", 1.5f)
        arr("pos") {
            value(46.0f)
            value(-3.25f)
        }
    }
}
