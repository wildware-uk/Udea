package dev.wildware.udea.render.draw

import com.github.quillraven.fleks.Family
import com.github.quillraven.fleks.World
import com.github.quillraven.fleks.World.Companion.family
import dev.wildware.udea.core.GameContext
import dev.wildware.udea.core.SimClock
import dev.wildware.udea.core.identity.NetIdIndex
import dev.wildware.udea.core.physics.PhysicsBody
import dev.wildware.udea.render.OffscreenTarget
import dev.wildware.udea.render.RenderPhase
import dev.wildware.udea.render.RenderResources
import dev.wildware.udea.render.RenderSystem
import dev.wildware.udea.render.camera.CameraRig
import dev.wildware.udea.render.interp.Interpolator
import dev.wildware.udea.render.interp.Pose

/**
 * Draws each entity's [DebugLabels] above it, in screen space, and expires them by tick.
 *
 * ## Capturable on purpose
 *
 * Registered at [RenderPhase.Debug], which is *before* the capture point. Debug shapes are
 * information about the **game world**, and an agent asking for a screenshot to work out why an
 * entity is not moving should get them. The agent activity overlay is information about the
 * **agent**, is an `OverlaySystem`, and is the thing that must never be capturable (spec 3.7).
 * The two look similar and are opposites.
 *
 * ## What it replaces
 *
 * `DebugDrawSystem` (`common/ecs/system/DebugDrawSystem.kt`), and every line of it had a
 * problem:
 *
 * - `VisUI.getSkin().getFont("default-font")` at **field-initialiser time** (`:25`), which
 *   crashes the moment the class is loaded before a skin exists. The font is injected here, and
 *   the caller can hand it [BitmapFont2D.builtIn], which needs no asset pipeline.
 * - its own private `SpriteBatch` (`:26`) — the third one in a frame.
 * - `gameScreen.isServer` printed into the corner (`:32`). Role is `GameContext.role` and the
 *   agent reads it from `/health`; burning it into the picture makes every screenshot diff
 *   between a server and a client.
 * - `"local: ${entity.id}v${entity.version}"` plus a `Networkable.remoteEntity` lookup for the
 *   remote pair (`:44-48`). Identity is a [dev.wildware.udea.core.identity.NetId] now — the
 *   same value on both machines — so there is one label and no second lookup.
 * - messages expiring against `gameScreen.time`, a wall clock read inside the tick (`:55`).
 *   They expire against the [SimClock]'s tick here, so a rewind takes the labels back with it.
 */
public class DebugOverlayRenderSystem(
    private val resources: RenderResources,
    private val camera: CameraRig,
    private val interpolator: Interpolator,
    private val netIds: NetIdIndex,
    /**
     * The font labels are drawn in.
     *
     * Injected rather than loaded here: a renderer that loads its own asset is a renderer that
     * cannot be constructed in a test, which is how the original ended up untestable.
     * [BitmapFont2D.builtIn] is a fine default for a caller with no skin, and
     * `resources.own(...)` gives it the pipeline's lifetime.
     */
    private val font: BitmapFont2D,
    /**
     * The shared switch this system draws under.
     *
     * Defaulted so every existing registration still compiles, and shared so that a host which
     * passes the same instance to several debug renderers gets one answer out of
     * `render.toggle_debug_draw` rather than one per renderer. See [DebugDraw].
     */
    private val debug: DebugDraw = DebugDraw(),
) : RenderSystem {

    private var bound: Bound? = null

    private val pose = Pose()

    /** Labels drawn by the most recent frame. What `DrawSystemPortTest` counts. */
    public var drawnCount: Int = 0
        private set

    /**
     * While false the labels still expire but nothing is drawn. The old F1 toggle, typed.
     *
     * Backed by the shared [DebugDraw] rather than by a field of its own, so that flipping it
     * here and flipping it through the agent's `render.toggle_debug_draw` are the same act.
     */
    public var enabled: Boolean
        get() = debug.enabled
        set(value) {
            debug.enabled = value
        }

    override fun onBind(world: World, ctx: GameContext) {
        bound = Bound(world, world.family { all(PhysicsBody, DebugLabels) }, ctx.clock)
    }

    override fun render(target: OffscreenTarget, alpha: Float) {
        val bound = this.bound ?: return
        drawnCount = 0
        if (bound.labelled.numEntities == 0) return

        // Screen space: text drawn in world units would be a few thousandths of a unit tall and
        // would scale with the camera, which is what made the original unreadable when zoomed.
        val batch = resources.batch
        batch.beginPixels()
        try {
            with(bound.world) {
                bound.labelled.forEach { entity -> drawLabels(entity, alpha, bound.clock, target) }
            }
        } finally {
            batch.end()
        }
    }

    private fun World.drawLabels(
        entity: com.github.quillraven.fleks.Entity,
        alpha: Float,
        clock: SimClock,
        target: OffscreenTarget,
    ) {
        val labels = entity[DebugLabels]
        // Expiry runs whether or not anything is drawn: a disabled overlay that stopped
        // expiring would hand back a thousand stale lines the moment it was switched on.
        expire(labels.messages, clock.tick)
        if (!enabled) return
        if (!interpolator.interpolate(this, entity, alpha, pose)) return

        // Through the rig's projection, which the rig fitted to the *target* this frame. The
        // LibGDX version once projected through the window's size instead - a 320x240 window over
        // a 64x32 framebuffer put every label off the captured frame - and `DrawSystemPortTest`
        // keeps a target whose size differs from any window so that cannot come back green.
        val x = camera.projection.pixelX(pose.x) + LABEL_OFFSET_X
        var y = camera.projection.pixelY(pose.y) + LABEL_OFFSET_Y

        // Built into a reused StringBuilder rather than `netIds.netIdOf(entity).toString()`.
        // `NetId.toString` is a string template, so the shipped form allocated a StringBuilder
        // *and* a String per labelled entity per frame -- per-entity garbage on the per-frame
        // path, which is what `RenderAllocationTest` measures. `BitmapFont2D.draw` takes a
        // `CharSequence`, so the builder goes straight in. The text loses the `NetId(...)`
        // wrapper and keeps the part that identifies the entity.
        val netId = netIds.netIdOf(entity)
        label.clear()
        label.append('#').append(netId.index).append('@').append(netId.generation)
        font.draw(resources.batch, label, x, y, Rgba.WHITE)
        for (index in labels.messages.indices) {
            y += LINE_HEIGHT
            font.draw(resources.batch, labels.messages[index].text, x, y, Rgba.WHITE)
            drawnCount++
        }
    }

    /**
     * Reused, and sized once for the longest id it can hold (`#65535@255`).
     *
     * See the call site: the point is that neither the builder nor a `String` is allocated per
     * labelled entity per frame.
     */
    private val label = StringBuilder(16)

    /**
     * Drops every message whose tick has passed, in place and without allocating.
     *
     * `messages.removeAll { it.expiresAt <= tick }` is the obvious form and was what shipped:
     * it allocates a capturing lambda **and** an iterator per labelled entity per frame, on the
     * per-frame path, which is exactly what `RenderAllocationTest` measures. A compacting index
     * loop allocates neither: `for (i in list.indices)` compiles to a plain `int` loop.
     */
    private fun expire(messages: MutableList<DebugLabel>, tick: dev.wildware.udea.core.Tick) {
        var survivors = 0
        for (index in messages.indices) {
            val message = messages[index]
            if (message.expiresAt > tick) {
                if (survivors != index) messages[survivors] = message
                survivors++
            }
        }
        while (messages.size > survivors) messages.removeAt(messages.size - 1)
    }

    private class Bound(val world: World, val labelled: Family, val clock: SimClock)

    private companion object {
        const val LABEL_OFFSET_X: Float = 40f
        const val LABEL_OFFSET_Y: Float = -20f
        const val LINE_HEIGHT: Float = 25f
    }
}
