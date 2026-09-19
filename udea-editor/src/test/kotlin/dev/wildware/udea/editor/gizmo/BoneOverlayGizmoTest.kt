package dev.wildware.udea.editor.gizmo

import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.spatial.Animator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The bone overlay as values, with no window and no GL (issue #243): what it declares for a skeleton.
 *
 * Where the joints are is the renderer's to say (`ModelRenderSystem.skeletonOf`, exercised against
 * the real Fox in `GlAnimationPreviewTest`); here the skeleton is handed in, so what is checked is
 * the gizmo's own half - a dot on every joint, a line from each joint to the one it hangs from, and
 * nothing a person could grab.
 */
class BoneOverlayGizmoTest {

    private val fox = NetId.of(index = 4, generation = 1)

    /** A hip, a knee below it and a foot below that, plus a tail hanging from the hip. */
    private val leg = FakeSkeletons(
        fox to listOf(
            SkeletonJoint(WorldPoint(0f, 0f, 10f), SkeletonJoint.ROOT),
            SkeletonJoint(WorldPoint(0f, 1f, 5f), 0),
            SkeletonJoint(WorldPoint(0f, 2f, 0f), 1),
            SkeletonJoint(WorldPoint(-4f, 0f, 9f), 0),
        ),
    )

    @Test
    fun `every joint gets a dot and every joint below another a line up to it`() {
        val marks = BoneOverlayGizmo(leg).marks(GizmoTarget(fox, Animator(), WorldPoint(0f, 0f)))

        val dots = marks.filter { it.shape == HandleShape.Point }.map { it.at }
        assertEquals(
            listOf(WorldPoint(0f, 0f, 10f), WorldPoint(0f, 1f, 5f), WorldPoint(0f, 2f, 0f), WorldPoint(-4f, 0f, 9f)),
            dots,
            "a dot on each joint, where the renderer says it is",
        )
        val bones = marks.mapNotNull { mark -> (mark.shape as? HandleShape.Line)?.let { mark.at to it.to } }
        assertEquals(
            listOf(
                WorldPoint(0f, 1f, 5f) to WorldPoint(0f, 0f, 10f),
                WorldPoint(0f, 2f, 0f) to WorldPoint(0f, 1f, 5f),
                WorldPoint(-4f, 0f, 9f) to WorldPoint(0f, 0f, 10f),
            ),
            bones,
            "a bone from each joint to its parent, and none from the root",
        )
    }

    @Test
    fun `it is read-only - it declares no handle`() {
        val target = GizmoTarget(fox, Animator(), WorldPoint(0f, 0f))
        assertEquals(emptyList(), BoneOverlayGizmo(leg).handles(target))
        assertTrue(BoneOverlayGizmo(leg).marks(target).isNotEmpty())
    }

    @Test
    fun `an entity with no skeleton draws nothing`() {
        val other = NetId.of(index = 9, generation = 1)
        assertEquals(emptyList(), BoneOverlayGizmo(leg).marks(GizmoTarget(other, Animator(), WorldPoint(0f, 0f))))
    }

    /** Skeletons by entity, as a renderer would report them. */
    private class FakeSkeletons(vararg entries: Pair<NetId, List<SkeletonJoint>>) : SkeletonSource {
        private val skeletons = entries.toMap()

        override fun skeletonOf(entity: NetId): List<SkeletonJoint> = skeletons[entity].orEmpty()
    }
}
