package dev.wildware.moba.net

import com.github.quillraven.fleks.World
import dev.wildware.udea.core.GameContext
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.loop.BarrierAction
import dev.wildware.udea.core.loop.barrier
import dev.wildware.udea.core.module.CoreModule
import dev.wildware.udea.core.spatial.Animator
import dev.wildware.udea.core.spatial.Loop
import dev.wildware.udea.core.ticks
import dev.wildware.udea.generated.Fox

/**
 * The net proof's animated entity (issue #241): a fox that carries an [Animator] and nothing else,
 * directed on the server the way a game system directs one.
 *
 * It exists so the proof's hashes have animation state in them to disagree about. Every call it
 * makes is the public clip API against the generated clips - `Fox.Clips.Survey` played once at
 * double speed, a crossfade to `Walk` on the first look after Survey reports finished, and a
 * crossfade to `Run` at one and a half times speed a fixed number of ticks later - so a client
 * that holds the same `Animator` fields as the server has received every kind of change the
 * component can make: a clip, a start tick, a speed, a loop mode and a fade.
 *
 * Each direction is a [BarrierAction], so it lands at the top of a tick like any other mutation
 * from outside a system, and the proof calls [direct] between the chunks it steps in.
 */
internal class NetProofFox(private val session: MobaHostSession) {

    /** The fox, once the first [direct] has been applied; [NetId.NONE] before. */
    var fox: NetId = NetId.NONE
        private set

    /** Queues this tick's direction. The first one brings the fox into the world. */
    fun direct() {
        session.host.ctx.barrier.submit(Direction())
    }

    /** The fox's [Animator] as the server holds it now, or `null` before it exists. */
    fun animator(): Animator? = with(session.host.world) {
        session.host.ctx[CoreModule.NET_IDS].resolveOrNull(fox)?.getOrNull(Animator)
    }

    private inner class Direction : BarrierAction {

        override val label: String = "netproof.fox.direct"

        override fun apply(world: World, ctx: GameContext) {
            val now = ctx.tick
            val netIds = ctx[CoreModule.NET_IDS]
            val existing = netIds.resolveOrNull(fox)
            if (existing == null) {
                val animator = Animator().apply { play(Fox.Clips.Survey, now, loop = Loop.Once, speed = SURVEY_SPEED) }
                fox = netIds.allocate(world.entity { it += animator })
                return
            }
            val animator = with(world) { existing[Animator] }
            when {
                animator.isPlaying(Fox.Clips.Survey) && animator.isFinished(now) ->
                    animator.crossfade(Fox.Clips.Walk, now, over = FADE)
                // How long it has walked is read off the component, not kept here, so the
                // direction is a function of the world alone.
                animator.isPlaying(Fox.Clips.Walk) && now.ticksSince(animator.current.start) >= WALK_TICKS ->
                    animator.crossfade(Fox.Clips.Run, now, over = FADE, speed = RUN_SPEED)
            }
        }
    }

    private companion object {
        const val SURVEY_SPEED = 2f
        const val RUN_SPEED = 1.5f
        const val WALK_TICKS = 50L
        val FADE = 6.ticks
    }
}
