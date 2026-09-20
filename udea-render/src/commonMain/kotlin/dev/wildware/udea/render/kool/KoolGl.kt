package dev.wildware.udea.render.kool

import de.fabmax.kool.KoolSystem
import de.fabmax.kool.pipeline.backend.gl.GlApi
import de.fabmax.kool.pipeline.backend.gl.RenderBackendGl

/**
 * Kool's OpenGL binding on this platform: LWJGL on the desktop, `android.opengl` on Android.
 *
 * Kool keeps its backend's own `GlApi` internal, and publishes one `GlImpl` object per platform
 * instead. Both are the same calls on the context Kool made current, so which one is a platform fact
 * and nothing more - which is all an `expect` is for. [PassBlit] and [GlScreenPasses] are its callers.
 */
internal expect fun koolGl(): GlApi

/**
 * The `#version` line this backend's shaders are compiled with, taken from Kool's own generator.
 *
 * This is the whole of "the engine prepends the right header per backend" (issue #266), and it is
 * a *reading* rather than a table: OpenGL and OpenGL ES want different version pragmas, Kool
 * decides which one from the context's actual version and flavour, and a second opinion of ours
 * would be wrong the first time the two disagreed - on somebody's Android device, with a
 * game-only compile error nobody here could reproduce.
 *
 * Render thread only: there is no context to ask before one exists.
 *
 * @throws IllegalStateException if the context is not on a GL backend. There is no other backend
 *   in this build - Kool's Vulkan and WebGPU backends are not reachable from `udea-render` - so
 *   this is a wiring fault worth naming rather than a case to fall back from.
 */
internal fun koolGlslVersion(): String {
    val backend = KoolSystem.requireContext().backend
    check(backend is RenderBackendGl) {
        "a screen shader is GLSL, and this context is on ${backend::class.simpleName}, which is " +
            "not a GL backend. udea-render builds for jvm and android, both of which are GL."
    }
    return backend.glslGeneratorHints.glslVersionStr
}
