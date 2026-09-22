/**
 * The Compose compiler, for a project that writes interface: a `UiScreen`, a `CapturedUi`'s
 * content, an editor panel (issue #275).
 *
 * Applied **beside** a Kotlin convention, never instead of one - `dev.wildware.udea.kotlin-library`
 * for a JVM game, `dev.wildware.udea.kotlin-multiplatform-render` for one that draws on more than
 * one platform - and in either order. It brings the compiler and nothing else: the toolkit a screen
 * is written in, `composegl-ui`, arrives with `udea-render`, which a project that shows a screen
 * already depends on.
 *
 * ## Why this is a plugin of its own
 *
 * `UiScreen.content` is `@Composable`. Without the Compose compiler a game's screen still
 * compiles - as a plain function - and fails only at run time, where it meets the toolkit: a
 * composable compiled by the Compose compiler takes a `Composer` its caller did not pass, so the
 * call links to a method that does not exist (`NoSuchMethodError`). Before this plugin none of
 * the published conventions applied the compiler, so a game in its own repository could not write
 * interface at all without knowing that and wiring the Kotlin plugin's Compose subplugin by hand.
 *
 * It is not folded into `dev.wildware.udea.kotlin-multiplatform-render`, because what needs Compose
 * is writing a composable, not drawing: `moba:game` draws and has a HUD, `hollow:game` draws and
 * has none, and a JVM game on `dev.wildware.udea.kotlin-library` can have one. The compiler also
 * stamps `$stable` into every class of a project it is applied to, so it belongs only where a
 * composable is.
 *
 * ## The version
 *
 * The Compose compiler ships from the Kotlin repository in lockstep with the compiler, so the only
 * version that loads is the Kotlin version; `build-logic` takes it from the catalog's `kotlin`, the
 * same entry every other Kotlin artifact here does. The engine's own `udea-render`, `udea-editor`
 * and `moba:game` apply this plugin too, so the path a game takes is the path the engine is built
 * on. `ComposeUiConventionTest` is the fence: a game on it composes a screen, and the same game
 * without it compiles and then cannot.
 */

plugins {
    id("org.jetbrains.kotlin.plugin.compose")
}
