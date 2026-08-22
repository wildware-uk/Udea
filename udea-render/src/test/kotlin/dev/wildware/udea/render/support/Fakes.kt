package dev.wildware.udea.render.support

import com.badlogic.gdx.utils.Disposable
import com.github.quillraven.fleks.World
import dev.wildware.udea.core.GameContext
import dev.wildware.udea.render.FrameClock
import dev.wildware.udea.render.OffscreenTarget
import dev.wildware.udea.render.OverlaySystem
import dev.wildware.udea.render.RenderConstraints
import dev.wildware.udea.render.RenderHandle
import dev.wildware.udea.render.RenderPhase
import dev.wildware.udea.render.RenderRegistry
import dev.wildware.udea.render.RenderSystem
import dev.wildware.udea.render.RenderTargets
import dev.wildware.udea.render.ScreenTarget

/**
 * Observable stand-ins for the things a pipeline drives.
 *
 * None of them touch GL, which is the point: every ordering, binding, timing and disposal
 * claim this module makes is checkable in a plain JVM with no window and no context. A test
 * that needed a real `SpriteBatch` could not run in CI, and a rule nobody can run in CI is
 * a comment.
 */

/** What happened this frame, in order, across every fake. */
internal class FrameLog {
    private val entries = ArrayList<String>()

    val calls: List<String> get() = entries

    fun record(entry: String) {
        entries += entry
    }

    fun clear() {
        entries.clear()
    }
}

/** A [RenderSystem] that records its bind and every draw, with the alpha it was given. */
internal class RecordingRenderSystem(
    private val name: String,
    private val log: FrameLog,
) : RenderSystem {

    var boundWorld: World? = null
        private set

    var boundContext: GameContext? = null
        private set

    /** Targets this system was handed, so a test can assert it only ever saw offscreen ones. */
    val targets = ArrayList<OffscreenTarget>()

    override fun onBind(world: World, ctx: GameContext) {
        boundWorld = world
        boundContext = ctx
        log.record("bind:$name")
    }

    override fun render(target: OffscreenTarget, alpha: Float) {
        targets += target
        log.record("draw:$name@$alpha")
    }
}

/** An [OverlaySystem] that records every draw with the wall delta it was given. */
internal class RecordingOverlaySystem(
    private val name: String,
    private val log: FrameLog,
) : OverlaySystem {

    val deltas = ArrayList<Float>()

    val targets = ArrayList<ScreenTarget>()

    override fun render(target: ScreenTarget, dtSeconds: Float) {
        deltas += dtSeconds
        targets += target
        log.record("overlay:$name@$dtSeconds")
    }
}

/** A GL resource stand-in that counts its own disposal. */
internal class CountingDisposable(
    private val name: String,
    private val log: FrameLog,
) : Disposable {

    var disposeCount: Int = 0
        private set

    override fun dispose() {
        disposeCount++
        log.record("dispose:$name")
    }
}

/** A [FrameClock] a test drives by hand. Time in tests is never the wall clock. */
internal class ManualFrameClock(private var nanos: Long = 0L) : FrameClock {

    override fun nanoTime(): Long = nanos

    fun advanceSeconds(seconds: Float) {
        nanos += (seconds.toDouble() * 1_000_000_000.0).toLong()
    }

    fun advanceNanos(delta: Long) {
        nanos += delta
    }
}

/**
 * Registers a [RecordingRenderSystem] in [phase].
 *
 * The factory is the *second* parameter of [RenderRegistry.register] so that production code
 * can pass a constructor reference (`register(World, ::SpriteRenderer)`); these helpers keep
 * the test call sites from repeating the resulting parentheses twenty times.
 */
internal fun RenderRegistry.scene(
    phase: RenderPhase,
    name: String,
    log: FrameLog,
    constrain: RenderConstraints.() -> Unit = {},
): RenderHandle = register(phase, { RecordingRenderSystem(name, log) }, constrain)

/** Registers a [RecordingOverlaySystem]; overlays are always in [RenderPhase.Overlay]. */
internal fun RenderRegistry.overlayScene(
    name: String,
    log: FrameLog,
    constrain: RenderConstraints.() -> Unit = {},
): RenderHandle = overlay({ RecordingOverlaySystem(name, log) }, constrain)

/** Targets of a plausible window size, built without a GL context. */
internal fun testTargets(owned: List<Disposable> = emptyList()): RenderTargets = RenderTargets(
    offscreen = OffscreenTarget(WIDTH, HEIGHT),
    screen = ScreenTarget(WIDTH, HEIGHT),
    owned = owned,
)

private const val WIDTH = 1280
private const val HEIGHT = 720
