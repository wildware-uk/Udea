package dev.wildware.udea.render.kool

import de.fabmax.kool.pipeline.backend.gl.GlApi
import de.fabmax.kool.pipeline.backend.gl.GlImpl

/** Kool's Android binding, OpenGL ES 3 through `android.opengl`. */
internal actual fun koolGl(): GlApi = GlImpl
