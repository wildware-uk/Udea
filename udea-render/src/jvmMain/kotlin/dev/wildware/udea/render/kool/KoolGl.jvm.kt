package dev.wildware.udea.render.kool

import de.fabmax.kool.pipeline.backend.gl.GlApi
import de.fabmax.kool.pipeline.backend.gl.GlImpl

/** Kool's desktop binding, LWJGL's OpenGL. */
internal actual fun koolGl(): GlApi = GlImpl
