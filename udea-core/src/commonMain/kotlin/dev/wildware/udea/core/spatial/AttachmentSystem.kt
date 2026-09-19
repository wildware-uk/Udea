package dev.wildware.udea.core.spatial

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.Family
import dev.wildware.udea.core.SimSystem
import dev.wildware.udea.core.identity.NetIdIndex

/**
 * Puts every mounted part where its socket is, once a tick (issue #260).
 *
 * A part carrying an [AttachedTo] has its [Transform3D] written from its parent's: the parent's
 * transform, times the socket's rest place inside the parent's model, times the part's own
 * offset. So a chassis drives and turns and its five modules go with it, and every reader of a
 * world position - gameplay, the renderer, an agent tool, a level save - sees a part where it is
 * drawn without knowing anything about mounting.
 *
 * Registered by `CoreModule` at [dev.wildware.udea.core.module.SimPhase.PostPhysics]: after
 * everything that moves a parent this tick - intent, movement, the solver - and before
 * `Gameplay`, so a system that spawns a shot from a muzzle socket reads this tick's muzzle
 * rather than last tick's.
 *
 * ## A part mounted on a part
 *
 * A module can carry sockets of its own: a turret with a gun on its roof. The chain is resolved
 * parent-first - a part whose parent is itself mounted resolves that parent before composing its
 * own transform - so a whole assembly is correct within the one tick, whatever order the family
 * happens to iterate in. Resolving a parent twice writes it the same both times: the composition
 * is a pure function of the components it reads, so the answer does not depend on how often it
 * is asked.
 *
 * ## What it does not do
 *
 * It never adds or removes a component, so it cannot fight replication. A parent that does not
 * resolve - destroyed, never relevant to this client, or carrying no [Transform3D] - leaves the
 * part exactly where it last stood, which is what an exploded part wants to do anyway; removing
 * the mount is the game's to do (`entity.configure { it -= AttachedTo }`), and the part is then
 * a free entity at the world transform this system last wrote.
 *
 * And it takes no clock and no random: everything it writes is a function of the components it
 * reads, so two machines stepping the same tick write the same floats.
 */
public class AttachmentSystem(private val netIds: NetIdIndex) : SimSystem() {

    /**
     * Resolved once, at construction: a fresh family definition per tick is a lookup on a
     * per-tick path, which the charter calls out.
     */
    private val mounted: Family = world.family { all(AttachedTo, Transform3D) }

    /** Reused: resolving a mount allocates nothing. See [MountFrame]. */
    private val world3d = MountFrame()
    private val socket = MountFrame()
    private val offset = MountFrame()

    override fun onTick() {
        mounted.forEach { part -> place(part, MAX_CHAIN) }
    }

    /**
     * Writes [part]'s world transform, having first written its parent's if that is mounted too.
     *
     * [budget] is how many more parents may be walked. It starts at [MAX_CHAIN] and is the cycle
     * guard: a part mounted on itself, or two parts mounted on each other, would otherwise
     * recurse for ever.
     */
    private fun place(part: Entity, budget: Int) {
        val mount = part[AttachedTo]
        val parent = netIds.resolveOrNull(mount.parent) ?: return
        val parentTransform = parent.getOrNull(Transform3D) ?: return
        if (parent has AttachedTo) {
            check(budget > 0) {
                "the mount of ${netIds.netIdOf(part)} runs through more than $MAX_CHAIN parents, " +
                    "which means a part is mounted on itself: ${mount.parent} is mounted on " +
                    "${parent[AttachedTo].parent}"
            }
            place(parent, budget - 1)
        }
        // Read after the parent is placed: that call is what puts this tick's numbers in it.
        world3d.set(parentTransform)
            .mul(socket.setNode(mount))
            .mul(offset.setOffset(mount))
            .writeTo(part[Transform3D])
    }

    private companion object {
        /**
         * How many parents deep a mount may go: a chassis, a turret and a gun is three, and this
         * is well past anything an assembly needs. A chain longer than this is a cycle.
         */
        const val MAX_CHAIN = 16
    }
}
