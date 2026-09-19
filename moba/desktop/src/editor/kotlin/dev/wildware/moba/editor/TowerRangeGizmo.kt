package dev.wildware.moba.editor

import com.github.quillraven.fleks.ComponentType
import dev.wildware.moba.lane.Tower
import dev.wildware.udea.editor.gizmo.Axis
import dev.wildware.udea.editor.gizmo.DragConstraint
import dev.wildware.udea.editor.gizmo.Gizmo
import dev.wildware.udea.editor.gizmo.GizmoScope
import dev.wildware.udea.editor.gizmo.GizmoTarget
import dev.wildware.udea.editor.gizmo.HandleShape
import dev.wildware.udea.editor.gizmo.Snap
import dev.wildware.udea.editor.gizmo.WorldPoint

/**
 * A tower's attack range in the editor (issue #236): the circle it shoots inside, and a grip on its
 * rim that drags the range in and out.
 *
 * **The dogfood proof.** This is a gizmo a game writes by hand, in its `editor` source set, against
 * the public gizmo API and nothing else - `handle`, `mark` and `write`, the three calls the editor's
 * own built-ins are made of. It declares no annotation: `MobaGizmoRegistry` lists it because it is a
 * `Gizmo` here, and `UDEA-MG-012` keeps it off the release classpath. `TowerRangeGizmoTest` holds it to
 * importing nothing else.
 *
 * The circle is a [HandleShape.Circle] mark, drawn at the range in world units, so what a designer sees is what the
 * tower reaches. The grip sits on the rim along the tower's X and is held to that line; dragged, the
 * range follows the grip's distance from the tower, never below zero, and snaps to the grid.
 */
internal object TowerRangeGizmo : Gizmo<Tower> {

    override val component: ComponentType<Tower> = Tower

    override fun GizmoScope<Tower>.build(target: GizmoTarget<Tower>) {
        val tower = target.origin
        val range = target.component.attackRange
        val across = target.axes.direction(Axis.X)
        mark(tower, HandleShape.Circle(range))
        handle(
            at = WorldPoint(tower.x + across.x * range, tower.y + across.y * range, tower.z + across.z * range),
            shape = HandleShape.Line(tower),
            constraint = DragConstraint.Along(Axis.X),
            axes = target.axes,
        ) { drag ->
            write(Tower::attackRange, maxOf(0f, range + drag.stretchFrom(tower)), Snap.Grid)
        }
    }
}
