package dev.wildware.udea.render.ui.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.widget.Button
import dev.wildware.composegl.ui.widget.Text

/**
 * The one `@Composable` in `udea-render`'s **main** compilation, and the whole reason issue #186
 * exists as a ticket of its own.
 *
 * It is not a widget anybody draws. It is the load-bearing end of the toolchain move: a resolved
 * dependency is not a compiling one, and a compiling one is not a *running* one. Three separate
 * things have to hold before issue #187 can put `UiLayer` on ComposeGL, and this function plus
 * `ComposeGlLinkTest` is what says all three hold together rather than one at a time:
 *
 * 1. **ComposeGL's classes load.** Every published ComposeGL jar carries `@Metadata(mv = [2, 4, 0])`.
 *    Under the 2.2.10 compiler this file does not compile at all — the frontend rejects the jar
 *    before it reads a single call site.
 * 2. **`org.jetbrains.kotlin.plugin.compose` is applied to this module's `main` compilation.**
 *    Without it, [remember] and [mutableStateOf] still *compile* — `androidx.compose.runtime`
 *    declares `currentComposer` as an intrinsic whose Kotlin body throws — so nothing here would
 *    go red until something actually composed it. That is why the proof is a test that runs the
 *    function rather than a build that compiles it.
 * 3. **State survives recomposition.** [clicks] is `remember`ed, so the count the test reads back
 *    after a click is the count Compose's own slot table kept, not a fresh zero.
 *
 * ## Why it stays `internal`
 *
 * Nothing outside `udea-render` calls it, and `docs/engineering-standards.md` section 8 rejects a
 * `public` declaration nobody outside the module uses. `internal` is still visible to this
 * module's test compilation, which is the only caller there is meant to be. Issue #187 replaces
 * it with the real `UiLayer` content; it has no reason to outlive that.
 *
 * ## Why it is not in the test source set
 *
 * Because then it would prove nothing about `compileKotlin`. The Compose plugin is applied
 * per-compilation, and #187's work lands in `main`. A probe that only ever compiled under
 * `compileTestKotlin` would leave the compilation that matters unasserted.
 */
@Composable
internal fun ComposeGlProbe() {
    var clicks by remember { mutableStateOf(0) }
    Column {
        Text("HELLO FROM UDEA", Modifier.testTag(ComposeGlProbeTags.GREETING))
        Text("Clicked $clicks times", Modifier.testTag(ComposeGlProbeTags.COUNT))
        Button("CLICK ME", { clicks++ }, Modifier.testTag(ComposeGlProbeTags.BUTTON))
    }
}

/**
 * The `Modifier.testTag` values [ComposeGlProbe] hangs on its three nodes.
 *
 * Shared with the test rather than typed twice, so a renamed tag is a compile error instead of a
 * test that silently stops finding the node it was asserting about.
 */
internal object ComposeGlProbeTags {
    const val GREETING: String = "udea-probe-greeting"
    const val COUNT: String = "udea-probe-count"
    const val BUTTON: String = "udea-probe-button"
}
