package dev.wildware.udea.compiler.fir

import dev.wildware.udea.compiler.testing.UdeaCheckerTest
import dev.wildware.udea.compiler.testing.source
import kotlin.test.Test

/**
 * The loop checker's scope (issue #192): it speaks only in a `.udea.kts`.
 *
 * The plugin reaches every `udea-*` and `moba` compilation, and in all of those a loop is simply
 * code. What the checker refuses inside an asset script is proven where asset scripts compile, by
 * `udea-assets-compiler`'s `AssetLoopResolutionTest`; this is the other half, that ordinary
 * source carrying every shape it refuses there still compiles clean.
 */
class UdeaAssetLoopCheckerTest : UdeaCheckerTest() {

    @Test
    fun `loops, repeat, forEach and recursion in ordinary source are not asset loops`() {
        assertCompilesClean(
            source(
                "Game.kt",
                """
                package udea.fixtures

                fun spawn(n: Int): Int = if (n == 0) 0 else spawn(n - 1)

                fun everything(): Int {
                    var total = 0
                    for (i in 0 until 3) total += i
                    while (total < 10) total++
                    do { total-- } while (total > 5)
                    repeat(2) { total++ }
                    kotlin.repeat(2) { total++ }
                    (1..3).forEach { total += it }
                    return total + spawn(3)
                }
                """,
            ),
        )
    }
}
