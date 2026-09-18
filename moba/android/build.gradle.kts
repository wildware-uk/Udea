/**
 * The phone build of `moba` (spec section 3, D12, issue #212).
 *
 * ## What this assembles today, stated plainly
 *
 * An APK that boots the **simulation** and reports what it built. It does not draw. `udea-render`
 * has a Kool backend for the JVM (`KoolBackend`, in its `jvmMain`) and none for Android yet, so
 * there is no `PresentationBackend` an `Activity` could hand a `GameHost` - and inventing one here
 * would put a renderer outside `udea-render`, which `UDEA-MG-002` forbids and spec section 3 names
 * as the rule the port keeps.
 *
 * That is worth having anyway, and it is not a placeholder: `MobaBoot` runs the real
 * `MobaGame.definition()`, the real scene swap and the real ticks, over the `.udeapak` packed into
 * this APK's resources. A `:moba:game` that compiled for Android and could not seed a level on one
 * is exactly the failure this module exists to catch, and nothing else in the build would.
 */

plugins {
    id("udea.android-application")
}

dependencies {
    // The game, Android variant. Everything the simulation needs - the kernel, abilities, assets,
    // networking, replay - arrives through it.
    implementation(project(":moba:game"))
}
