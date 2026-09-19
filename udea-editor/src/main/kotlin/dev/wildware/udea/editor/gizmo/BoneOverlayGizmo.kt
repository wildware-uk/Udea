package dev.wildware.udea.editor.gizmo

import com.github.quillraven.fleks.ComponentType
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.spatial.Animator
import dev.wildware.udea.render.model.ModelRenderSystem
import dev.wildware.udea.render.model.ModelSkeleton
import dev.wildware.udea.render.view.WorldViewport

/**
 * The bone overlay (issue #243): the skeleton of a selected skinned entity, a dot on every joint and
 * a line from each joint to the one it hangs from, drawn where the joints are **as the view shows
 * them** - animated, and in the scrub preview's pose while one is on.
 *
 * Written against the public gizmo API and nothing else, as every built-in gizmo is (epic #231's
 * second rule): it is offered on entities with an [Animator], declares [GizmoScope.mark]s, and reads
 * the joints from a [SkeletonSource] a game could build as easily as the editor does. It is
 * read-only: it declares no handle, so there is nothing in it to grab.
 */
public class BoneOverlayGizmo(private val skeletons: SkeletonSource) : Gizmo<Animator> {

    override val component: ComponentType<Animator> = Animator

    override fun GizmoScope<Animator>.build(target: GizmoTarget<Animator>) {
        val joints = skeletons.skeletonOf(target.entity)
        for (joint in joints) {
            mark(joint.at, HandleShape.Point)
            if (joint.parent != SkeletonJoint.ROOT) mark(joint.at, HandleShape.Line(joints[joint.parent].at))
        }
    }

    override fun toString(): String = "BoneOverlayGizmo($skeletons)"
}

/** Where an entity's joints are, for a [BoneOverlayGizmo]. */
public fun interface SkeletonSource {

    /**
     * The joints of [entity]'s skinned model as they are drawn now, each naming the joint it hangs
     * from by its place in the list; empty when [entity] has no skinned model. Render thread.
     */
    public fun skeletonOf(entity: NetId): List<SkeletonJoint>
}

/**
 * One joint of a skeleton: where it is in the world, and the joint it hangs from.
 *
 * @property parent the index of that joint in the same list, or [ROOT].
 */
public data class SkeletonJoint(val at: WorldPoint, val parent: Int) {
    public companion object {
        /** [parent] of a joint that hangs from no other. */
        public const val ROOT: Int = ModelSkeleton.ROOT
    }
}

/**
 * The joints [models] draws, as [view] shows them: `ModelRenderSystem.skeletonOf` read into
 * [SkeletonJoint]s. The Scene tab passes itself, so its overlay follows its scrub preview; the Game
 * tab passes `null`, the capturable frame's simulated pose.
 */
public fun modelSkeletons(models: ModelRenderSystem, view: WorldViewport?): SkeletonSource = ModelSkeletons(models, view)

private class ModelSkeletons(private val models: ModelRenderSystem, private val view: WorldViewport?) : SkeletonSource {

    private val read = ModelSkeleton()

    override fun skeletonOf(entity: NetId): List<SkeletonJoint> {
        if (!models.skeletonOf(entity, view, read)) return emptyList()
        return List(read.size) { joint ->
            SkeletonJoint(WorldPoint(read.x(joint), read.y(joint), read.z(joint)), read.parent(joint))
        }
    }

    override fun toString(): String = "ModelSkeletons($models, $view)"
}
