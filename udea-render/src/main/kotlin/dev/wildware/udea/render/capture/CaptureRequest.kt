package dev.wildware.udea.render.capture

import dev.wildware.udea.core.Tick

/**
 * A rectangle of the frame, in pixels, with the origin at the bottom-left.
 *
 * Bottom-left rather than top-left because that is where GL's origin is and this rectangle is
 * handed straight to `glReadPixels`. Converting here would mean converting back one call
 * later, and a coordinate system that flips twice on the way to the driver is a coordinate
 * system somebody eventually flips once.
 */
public class CaptureRegion(
    public val x: Int,
    public val y: Int,
    public val width: Int,
    public val height: Int,
) {

    init {
        require(x >= 0 && y >= 0) { "region origin must be non-negative, was ($x, $y)" }
        require(width > 0 && height > 0) {
            "region must have positive extent, was ${width}x$height"
        }
    }

    override fun toString(): String = "CaptureRegion(${width}x$height at ($x, $y))"
}

/**
 * What a caller wants captured, and when.
 *
 * ## Why `afterTick` is part of the request rather than the caller's problem
 *
 * An agent's verification loop is `step(200)` then `screenshot()`, and the two run on
 * different threads: the simulation advances on the render thread, the tool call arrives on
 * the agent host's. "Sleep a bit and hope the right frame was drawn" is the version that
 * produces a picture of tick 199 once in fifty runs and makes the agent chase a bug that is
 * not there. Naming the tick makes the answer deterministic: the capture is fulfilled by the
 * first frame drawn once that tick has been simulated, and by no earlier one.
 */
public class CaptureRequest(
    /** The region to read, or `null` for the whole frame. */
    public val region: CaptureRegion? = null,
    /**
     * Fulfil only once this tick has finished simulating, or immediately when `null`.
     *
     * "Finished" means the clock has moved past it: `SimClock.tick` is the tick *about to be*
     * simulated, so tick `t` is done exactly when `clock.tick > t`.
     */
    public val afterTick: Tick? = null,
) {

    override fun toString(): String =
        "CaptureRequest(region=${region ?: "full"}, afterTick=${afterTick?.value ?: "now"})"
}

/**
 * One captured frame.
 *
 * Deliberately **not** a file path, a `lastPath` field or an event-log line. The old
 * `ScreenCapture` wrote a PNG under a name it invented, kept the path in a mutable field and
 * announced it through `DebugBridge.event("screenshot:...")`; a caller then had to parse a log
 * message to find out where its own screenshot went, and two overlapping requests raced for
 * the field. Here the bytes come back to the caller that asked for them, and where they are
 * stored is the agent host's decision (its artifact store owns ids and eviction).
 */
public class CaptureResult(
    /** Width of [bytes] in pixels. */
    public val width: Int,
    /** Height of [bytes] in pixels. */
    public val height: Int,
    /** The tick the world stood at when this frame was drawn. */
    public val tick: Tick,
    /** PNG-encoded RGBA8888, every alpha byte 255. See `ForceOpaque`. */
    public val bytes: ByteArray,
) {

    override fun toString(): String =
        "CaptureResult(${width}x$height at tick ${tick.value}, ${bytes.size} bytes)"
}
