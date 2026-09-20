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
    // names an asset type: the Asset panel (issue #195) reads and saves assets through `assets.*` tool
    // answers, so a direct arrow is added with the first type this module actually names.
    api(project(":udea-agent"))

    // Docked windows, the dividers between them and the tab strips: ComposeGL's `DebugWindowHost`.
    // The toolkit with no backend in it, like `composegl-ui`, so `UDEA-MG-011` allows it.
    implementation(libs.composegl.debug)

    // Tool answers arrive as rendered JSON (`AgentResult.Ok.json`); the history panel reads them.
    implementation(libs.kotlinx.serialization.json)

    // Test-only, for the GL tests (issue #234): they move the mouse through Kool's own GLFW callbacks
    // and read Kool's pointer buttons, as `udea-render`'s pointer test does. `UDEA-MG-011` governs
    // `compileClasspath`, what the editor ships against, and this is not on it.
    testImplementation(libs.kool.core)
}

// --- udeaEditorGlTest (issue #234) ----------------------------------------------------------------
//
// The editor tests that need a real Kool context: the Scene and Game tabs over a running backend, with
// the mouse driven through Kool's own GLFW callbacks. Each test class in a JVM of its own, for the
// reason `udea-render`'s `udeaGlTest` gives: Kool allows one context per process, for its lifetime.
val editorGlTestPackage = "dev.wildware.udea.editor.gl"

val udeaEditorGlTest = tasks.register<Test>("udeaEditorGlTest") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Runs the editor tests that drive a real Kool backend and a display."

    val testSourceSet = sourceSets.test.get()
    testClassesDirs = testSourceSet.output.classesDirs
    classpath = testSourceSet.runtimeClasspath
    useJUnitPlatform()
    filter { includeTestsMatching("$editorGlTestPackage.*") }
    forkEvery = 1

    // A machine with no display skips these and says so; a job that has one sets this, so a skip
    // cannot hide a backend that stopped booting. The same property the other GL suites read.
    systemProperty(
        "udea.render.requireGl",
        providers.gradleProperty("udea.render.requireGl").getOrElse("false"),
    )
    // Where the frames the tests read back are written, so a person can look at them.
    systemProperty(
        "udea.render.glReportDir",
        layout.buildDirectory.dir("reports/udea/gl").get().asFile.absolutePath,
    )
    readsTheFox()
}

tasks.test {
    filter { excludeTestsMatching("$editorGlTestPackage.*") }
    readsTheFox()
}

/**
 * The Khronos Fox, which the Animation panel's tests animate (issue #243): the same file
 * `udea-render`'s tests read, from the retired game's asset tree.
 */
fun Test.readsTheFox() {
    val exampleAssets = rootProject.layout.projectDirectory.dir("example-assets")
    systemProperty("udea.render.exampleAssets", exampleAssets.asFile.absolutePath)
    inputs.dir(exampleAssets.dir("models")).withPropertyName("exampleModels").withPathSensitivity(PathSensitivity.RELATIVE)
}

tasks.check {
    dependsOn(udeaEditorGlTest)
}

// The one-line description Maven Central requires of a published artifact (issue #265).
description =
    "Udea's editor window: docked panels over the same tool surface an agent calls, the world " +
    "in a Scene and a Game tab, and the gizmo API. A development tool: the build gates keep " +
    "it, and every gizmo, off a released classpath."
