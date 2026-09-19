package dev.wildware.udea.physics2d

import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.physics.Box
import dev.wildware.udea.core.physics.Circle
import dev.wildware.udea.core.physics.PhysicsBody
import dev.wildware.udea.core.spatial.Transform3D
import java.io.File

/**
 * Where a test scene's entities were at every tick, written as a CSV under
 * `build/reports/udea/transform3d/` so the run can be looked at afterwards.
 *
 * One row per entity per tick: the tick, the entity, its body kind, its shape and size, and its
 * pose - from `Transform3D` where it has one and `PhysicsBody` where it does not, so a row is
 * always what a renderer drawing that entity would have placed. Nothing reads it back; it is the
 * artefact a picture of the scene is drawn from.
 */
internal class SceneTrace(private val name: String) : AutoCloseable {

    private val rows = StringBuilder("tick,netId,kind,shape,sizeA,sizeB,x,y,rotationZ,source\n")

    fun record(scene: Box2DScene, tick: Int, ids: List<NetId>) {
        for (id in ids) {
            val entity = scene.entityOf(id)
            with(scene.world) {
                val body = entity[PhysicsBody]
                val box = entity.getOrNull(Box)
                val circle = entity.getOrNull(Circle)
                val transform = entity.getOrNull(Transform3D)
                val shape = when {
                    box != null -> "box,${box.halfWidth},${box.halfHeight}"
                    circle != null -> "circle,${circle.radius},${circle.radius}"
                    else -> "none,0,0"
                }
                val pose = if (transform != null) {
                    "${transform.x},${transform.y},${transform.rotationZ},Transform3D"
                } else {
                    "${body.x},${body.y},${body.angle},PhysicsBody"
                }
                rows.append("$tick,${id.raw},${body.kind},$shape,$pose\n")
            }
        }
    }

    /** Writes the file - on a failing run too, so a red run leaves its picture behind. */
    override fun close() {
        val dir = File(checkNotNull(System.getProperty("udea.physics2d.projectDir")), "build/reports/udea/transform3d")
        dir.mkdirs()
        File(dir, "$name.csv").writeText(rows.toString())
    }
}
