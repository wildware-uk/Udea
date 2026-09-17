package dev.wildware.udea.spike.koolwasm

import de.fabmax.kool.KoolApplication
import de.fabmax.kool.addScene
import de.fabmax.kool.math.Vec3f
import de.fabmax.kool.math.deg
import de.fabmax.kool.modules.ksl.KslUnlitShader
import de.fabmax.kool.pipeline.ClearColorFill
import de.fabmax.kool.scene.addColorMesh
import de.fabmax.kool.scene.defaultOrbitCamera
import de.fabmax.kool.util.Color

/** Frames drawn before the page announces itself ready to be screenshotted. */
const val FRAMES_BEFORE_READY = 60

/**
 * The line `draw-check.mjs` waits for on the browser console. It carries the backend Kool chose,
 * so the transcript shows what Kool itself reports alongside what the page's WebGL context says.
 */
fun readyLine(frames: Int, backend: String, api: String) =
    "KOOL_SPIKE_READY frames=$frames backend=$backend api=$api"

/**
 * A still scene: a vertex-coloured cube, tilted so three faces show, on a clear colour that is
 * nothing Kool defaults to. It is common code, so the consumer's `jvm` compilation type-checks it
 * against Kool's desktop variant and `wasmJs` against the Wasm klib.
 */
fun KoolApplication.spikeScene(onReady: (String) -> Unit) {
    addScene {
        clearColor = ClearColorFill(SPIKE_CLEAR)
        defaultOrbitCamera(yaw = 30f, pitch = -25f)
        addColorMesh {
            generate {
                cube {
                    colored()
                }
            }
            shader = KslUnlitShader {
                color { vertexColor() }
            }
            transform.rotate(15f.deg, Vec3f.Y_AXIS)
            transform.scale(3f)
        }
    }

    var frames = 0
    ctx.onRender += {
        frames++
        if (frames == FRAMES_BEFORE_READY) {
            onReady(readyLine(frames, it.backend.name, it.backend.apiName))
        }
    }
}

/** Dark teal. `draw-check.mjs` samples the corners for it and the centre for anything else. */
val SPIKE_CLEAR = Color(0.05f, 0.25f, 0.3f, 1f)
