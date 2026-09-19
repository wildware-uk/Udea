/**
 * The editor window (issue #194): docked ComposeGL panels, the world in a `SceneView`, and buttons
 * that call `editor.*` tools in-process.
 *
 * ## The two rules this module lives under
 *
 * - **It is a screen over the tool surface.** Every action is an `editor.*` tool call submitted to
 *   the same `AgentBridge` an agent's HTTP call reaches, filed under the editor's author. There is
 *   no second implementation of "spawn a unit" here to drift from the one agents are tested through.
 * - **It never ships.** `UDEA-MG-010` fails the build if any scanned classpath resolves this module;
 *   a game reaches it only from an `editor` source set (`:moba:desktop`'s, which holds `runEditor`).
 *
 * JVM only for now, as the issue says: the window is a desktop thing, and it needs `DesktopFonts`.
 */
plugins {
    id("udea.kotlin-library")

    // The panels are `@Composable`. `udea-render` is the only other module with this plugin, and for
    // the same reason: this is a module that writes composables.
    alias(libs.plugins.composeCompiler)
}

dependencies {
    // `api`: `EditorSession.window` is a `UiScreen` and its viewport block draws into a
    // `SceneDrawScope` - a launcher's is `WorldView.drawInto` - so `composegl-ui` comes with it. The
    // Kool frontend and the world draw stay inside `udea-render` (spec section 3); `UDEA-MG-011` keeps
    // every renderer artifact off this module's compile classpath.
    api(project(":udea-render"))

    // `api`: an `EditorTools` is built over an `AgentBridge` and an `AgentSessionId`.
    //
    // Issue #194 also names `udea-assets`. It arrives through `udea-render`'s `api`, and nothing here
    // names an asset type yet; the asset panels (issue #195) add the direct arrow with the first use.
    api(project(":udea-agent"))

    // Docked windows, the dividers between them and the tab strips: ComposeGL's `DebugWindowHost`.
    // The toolkit with no backend in it, like `composegl-ui`, so `UDEA-MG-011` allows it.
    implementation(libs.composegl.debug)

    // Tool answers arrive as rendered JSON (`AgentResult.Ok.json`); the history panel reads them.
    implementation(libs.kotlinx.serialization.json)
}
