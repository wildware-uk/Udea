package dev.wildware.udea.spike.koolwasm

import de.fabmax.kool.KoolApplication
import de.fabmax.kool.KoolConfigWasm
import de.fabmax.kool.pipeline.backend.gl.RenderBackendGl

// WebGL 2 is asked for by name rather than left to Kool's default, so a later Kool that defaults
// to WebGPU cannot quietly turn this into a WebGPU check.
fun main() = KoolApplication(
    KoolConfigWasm(renderBackend = RenderBackendGl, deviceScaleLimit = 1.0),
) {
    spikeScene { println(it) }
}
