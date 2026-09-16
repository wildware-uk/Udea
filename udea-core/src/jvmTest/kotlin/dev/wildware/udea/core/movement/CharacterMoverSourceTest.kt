package dev.wildware.udea.core.movement

import dev.wildware.udea.core.KotlinSource
import dev.wildware.udea.core.ModuleFiles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `CharacterMover` names no Box2D type, and no simulation-forbidden source of numbers.
 *
 * `NoBox2DInCoreTest` already scans the whole module for LibGDX, and this does not duplicate it:
 * the check here is narrower and has a second half `NoBox2DInCoreTest` does not make. Spec 3.4
 * turns on movement being a pure function of `(state, intent, geometry, dt)`, and the ways to
 * break that are not all LibGDX types. A wall-clock read, an unseeded `Random`, or a
 * transcendental whose last bit `java.lang.Math` declines to specify would each make two machines
 * disagree while compiling perfectly and passing every functional test on one machine.
 *
 * Source, not reflection: what is being forbidden is a *call*, and a call is not visible on a
 * class object. Comments and string literals are stripped first, so the KDoc in this package -
 * which discusses `sin`, `cos` and `System.nanoTime` at length in order to forbid them - does not
 * trip the rule it documents.
 */
class CharacterMoverSourceTest {

    private val movementSources = ModuleFiles.mainSources.filter {
        ModuleFiles.relativePath(it).contains("/core/movement/")
    }

    @Test
    fun `the scan is looking at the movement sources`() {
        // A scan over an empty list passes every rule below. This is the test that cannot.
        val names = movementSources.map { it.name }.sorted()
        assertEquals(
            listOf("CharacterMover.kt", "CharacterMoverSystem.kt", "MoverComponents.kt", "StaticCollision.kt"),
            names,
            "the movement package's files changed; update this list deliberately",
        )
    }

    @Test
    fun `no movement source names a LibGDX type`() {
        assertNoMatch(
            Regex("""\bcom\.badlogic\.gdx\b"""),
            "the mover owns movement precisely so the solver does not (spec 3.4); a Box2D type " +
                "here would put solver state back on the path a snapshot has to reproduce",
        )
    }

    @Test
    fun `no movement source reads a clock`() {
        assertNoMatch(
            Regex("""\b(?:System\.(?:nanoTime|currentTimeMillis)|Instant\.now|LocalDate)\b"""),
            "movement is a function of its parameters; a clock read is a value a replay cannot " +
                "reproduce (standards section 8)",
        )
    }

    @Test
    fun `no movement source draws a random number`() {
        assertNoMatch(
            Regex("""\b(?:java\.util\.Random|kotlin\.random|Random\(|Math\.random)"""),
            "unseeded randomness in simulation code is what standards section 8 rejects; the " +
                "engine's seeded RngService is the only sanctioned source, and movement needs none",
        )
    }

    @Test
    fun `no movement source calls a transcendental or a fused multiply-add`() {
        // `java.lang.Math` specifies sin/cos/pow to within 1-2 ulp and explicitly permits an
        // implementation to differ between platforms; only `StrictMath` is bit-exact. `Math.fma`
        // reassociates by design. Either would break the parity claim on a machine nobody tested.
        assertNoMatch(
            Regex("""\b(?:sin|cos|tan|atan2?|asin|acos|pow|exp|ln|log|hypot|cbrt|fma)\s*\("""),
            "these are not specified to the last bit across platforms, and Phase 7 replays this " +
                "run on Windows and Linux; sqrt is the one JDK call the mover is allowed",
        )
    }

    @Test
    fun `no movement source widens to Double`() {
        // The determinism argument in `CharacterMover`'s KDoc rests on every value being a Float.
        // A `toDouble()` in the middle of the sweep would silently change the rounding of every
        // expression downstream of it.
        assertNoMatch(
            Regex("""\b(?:toDouble\(\)|:\s*Double\b|\bDouble\.)"""),
            "the mover is Float throughout; see the operation-order contract in its KDoc",
        )
    }

    private fun assertNoMatch(pattern: Regex, why: String) {
        assertTrue(movementSources.isNotEmpty(), "no movement sources were found to scan")
        val offenders = ArrayList<String>()
        for (file in movementSources) {
            val code = KotlinSource.stripCommentsAndStrings(file.readText())
            val path = ModuleFiles.relativePath(file)
            code.lineSequence().forEachIndexed { index, text ->
                if (pattern.containsMatchIn(text)) offenders += "$path:${index + 1}  ${text.trim()}"
            }
        }
        assertEquals(emptyList(), offenders, why)
    }
}
