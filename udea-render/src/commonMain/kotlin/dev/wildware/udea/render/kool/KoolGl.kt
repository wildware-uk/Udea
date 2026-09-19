package dev.wildware.udea.render.kool

import de.fabmax.kool.pipeline.backend.gl.GlApi

/**
 * Kool's OpenGL binding on this platform: LWJGL on the desktop, `android.opengl` on Android.
 *
 * Kool keeps its backend's own `GlApi` internal, and publishes one `GlImpl` object per platform
 * instead. Both are the same calls on the context Kool made current, so which one is a platform fact
 * and nothing more - which is all an `expect` is for. [PassBlit] is its one caller.
 */
internal expect fun koolGl(): GlApi
