package dev.wildware.udea.assets.compiler.daemon

import org.junit.jupiter.api.Test

/**
 * The half of `moba`'s warm-edit gate that is not a stopwatch, and so stays on `check`.
 *
 * An authored `scale` is changed in `character/orc.udea.kts`, and what has to come back is the
 * delta an `AssetHotReload` would push: the five sheets that share `orcScale` and nothing else,
 * with the graph then serving the new value. None of that depends on how busy the machine is, so
 * every `./gradlew build` runs it - which is the point of splitting it away from
 * [MobaWarmEditBudgetTest] (issue #182), because the budget half now runs on a CI job of its own
 * and would otherwise have taken this with it.
 *
 * Two edits rather than one. The first is the cold path - it pays for classloading the scripting
 * host - and the second is the warm one, which is the path an agent actually edits through and the
 * one whose delta had never been asserted separately from a benchmark.
 */
class MobaWarmEditTest {

    @Test
    fun `an edited scale reaches the graph as a delta naming every sheet that shares it`() {
        val harness = MobaWarmEdit("moba-warm-edit-correctness")
        harness.start()

        repeat(2) { iteration ->
            harness.verify(harness.edit(iteration))
        }
    }
}
