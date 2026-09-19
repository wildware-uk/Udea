package com.badlogic.gdx.scenes.scene2d

/**
 * A stand-in for LibGDX's scene2d `Stage`, compiled from source in this repository.
 *
 * It exists for `LibGdxScanTest`, and its shape is the point: a class in LibGDX's own package that
 * no LibGDX artifact supplied. That is how a scene2d reference would come back without tripping
 * `UDEA-MG-009`, which matches artifact coordinates - vendored source has none. The bytecode gate
 * has to catch it anyway.
 *
 * It lives in `udea-render`'s test sources, which no gate scans; `LibGdxScan` reads each module's
 * main bytecode only.
 */
internal class Stage {
    fun act(delta: Float): Float = delta
}
