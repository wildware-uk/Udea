/**
 * `udea.kotlin-multiplatform` without iOS, for `udea-render` (spec section 3, issue #201).
 *
 * Kool, which `udea-render` draws with, has no iOS backend (spec D2): every other module builds
 * and tests for iOS, and the renderer waits for Kool. The target set is
 * `udea.kotlin-multiplatform-no-ios`'s, applied rather than copied, so the rest - Kotlin, Android,
 * Wasm, the stdlib pin and the compiler-plugin gates - is identical by construction.
 *
 * A convention of its own rather than `udea-render` applying the no-iOS one directly, because the
 * reason is different: `udea-render`'s iOS waits for Kool, and a module applying
 * `udea.kotlin-multiplatform-no-ios` waits for whatever its own build script names.
 */

plugins {
    id("udea.kotlin-multiplatform-no-ios")
}
