package dev.wildware.udea.core.host

/**
 * Why a capture could not be produced.
 *
 * An enum carrying a **stable string code** because the value crosses the agent boundary: the
 * MCP surface reports it verbatim, and a model that has learned `no_render_context` must keep
 * getting that exact token when the reason has not changed. Renaming a constant here is a
 * protocol change.
 */
public enum class RenderUnavailable(
    /** The token an agent sees. Stable across renames of the enum constant. */
    public val code: String,
) {

    /**
     * The host is running in [RenderMode.Headless]: there is no GL context to read pixels from.
     *
     * The whole reason this is a *typed value* rather than an exception or a blank image: an
     * agent doing visual verification — capture, act, capture, diff — would read a blank
     * image as "the screen is black" and a thrown exception as "the tool is broken". Neither
     * is true, and both cost a debugging round trip. `no_render_context` says the toolset is
     * not live in this mode, which is actionable.
     */
    NoRenderContext("no_render_context"),

    /**
     * The host has a render context but no capture backend was supplied.
     *
     * Distinct from [NoRenderContext] because the remedies differ: this one is a wiring
     * mistake in the host, that one is a property of the mode.
     */
    NoCaptureBackend("no_capture_backend"),
}

/**
 * The result of asking a host for a frame.
 *
 * Sealed so a caller's `when` is exhaustive: adding a third outcome fails the build at every
 * site that has to handle it, rather than falling into an `else` that reports success.
 */
public sealed interface CaptureOutcome {

    /** Encoded image bytes, in whatever format the capture backend documents. */
    public class Captured(public val image: ByteArray) : CaptureOutcome {
        override fun toString(): String = "Captured(${image.size} bytes)"
    }

    /** No image, and the typed reason why. */
    public class Unavailable(public val reason: RenderUnavailable) : CaptureOutcome {
        override fun toString(): String = "Unavailable(${reason.code})"
    }
}

/**
 * Reads the current frame back as bytes.
 *
 * Implemented in `udea-render` against a real GL context; named here so [GameHost] can hold a
 * nullable one without GL reaching the kernel's compile classpath — the same arrangement that
 * lets `GameLoop` hold a nullable `Presentation`.
 */
public fun interface FrameCapture {

    /** Captures the most recently presented frame. */
    public fun capture(): CaptureOutcome
}
