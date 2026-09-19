package dev.wildware.udea.codegen.fixtures

import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.ComponentType
import dev.wildware.udea.annotations.PositionHandle
import dev.wildware.udea.annotations.RadiusHandle
import dev.wildware.udea.annotations.RangeHandle
import dev.wildware.udea.annotations.RotationHandle
import dev.wildware.udea.annotations.ScaleHandle
import dev.wildware.udea.annotations.SizeHandle
import dev.wildware.udea.editor.gizmo.Gizmo
import dev.wildware.udea.editor.gizmo.GizmoScope
import dev.wildware.udea.editor.gizmo.GizmoTarget
import dev.wildware.udea.editor.gizmo.moveHandles
import dev.wildware.udea.editor.gizmo.radiusHandle
import dev.wildware.udea.editor.gizmo.rotationHandle
import dev.wildware.udea.editor.gizmo.rotationRings
import dev.wildware.udea.editor.gizmo.scaleHandles
import dev.wildware.udea.editor.gizmo.translateHandles

/*
 * The gizmo fixtures (issue #233): components carrying every handle annotation, and hand-written
 * gizmos that are twins of three of the gizmos `kspTest` generates from them. Each twin calls the
 * editor's public built-in for its handle (issue #236), as a person writing that gizmo by hand would.
 *
 * This test source set plays both halves a game is split into. Its KSP run is a module's
 * (`udea.moduleName`), so it lists these components on `CodegenFixturesModuleRegistry`'s
 * `@HandleIndex`, and an editor source set's (`udea.gizmoRegistry`), so it generates the gizmos and
 * `CodegenFixturesGizmoRegistry` - which lists the hand-written ones below too, found by supertype.
 */

/** A 2D component: a position on the ground plane, a radius and a range. */
@PositionHandle
public class Beacon(
    public var x: Float = 0f,
    public var y: Float = 0f,
    @RadiusHandle public var reach: Float = 1f,
    @RangeHandle public var sight: Float = 5f,
    /** Not a float: a handle may not name it, and none does. */
    public var charges: Int = 0,
) : Component<Beacon> {
    override fun type(): ComponentType<Beacon> = Beacon

    public companion object : ComponentType<Beacon>()
}

/** A 3D component whose fields are not called what the annotations default to. */
@PositionHandle(x = "px", y = "py", z = "pz")
@SizeHandle(width = "sizeX", height = "sizeY", depth = "sizeZ")
@RotationHandle(rotation = "heading")
public class Crate(
    public var px: Float = 0f,
    public var py: Float = 0f,
    public var pz: Float = 0f,
    public var sizeX: Float = 1f,
    public var sizeY: Float = 1f,
    public var sizeZ: Float = 1f,
    public var heading: Float = 0f,
) : Component<Crate> {
    override fun type(): ComponentType<Crate> = Crate

    public companion object : ComponentType<Crate>()
}

/**
 * A 3D component turned about all three axes and scaled (issue #237), with its own field names: the
 * shape of `Transform3D`, which carries the same three annotations.
 */
@PositionHandle(x = "px", y = "py", z = "pz")
@RotationHandle(rotation = "yaw", aboutX = "roll", aboutY = "pitch")
@ScaleHandle(x = "wide", y = "deep", z = "tall")
public class Drone(
    public var px: Float = 0f,
    public var py: Float = 0f,
    public var pz: Float = 0f,
    public var roll: Float = 0f,
    public var pitch: Float = 0f,
    public var yaw: Float = 0f,
    public var wide: Float = 1f,
    public var deep: Float = 1f,
    public var tall: Float = 1f,
) : Component<Drone> {
    override fun type(): ComponentType<Drone> = Drone

    public companion object : ComponentType<Drone>()
}

/** The hand-written twin of the gizmo `@PositionHandle(z = ...)` generates on [Drone]. */
public object DronePositionTwin : Gizmo<Drone> {
    override val component: ComponentType<Drone> = Drone

    override fun GizmoScope<Drone>.build(target: GizmoTarget<Drone>) {
        val drone = target.component
        translateHandles(target, Drone::px, drone.px, Drone::py, drone.py, Drone::pz, drone.pz)
    }
}

/** The hand-written twin of the gizmo the three-angle `@RotationHandle` generates on [Drone]. */
public object DroneRotationTwin : Gizmo<Drone> {
    override val component: ComponentType<Drone> = Drone

    override fun GizmoScope<Drone>.build(target: GizmoTarget<Drone>) {
        val drone = target.component
        rotationRings(target, Drone::roll, drone.roll, Drone::pitch, drone.pitch, Drone::yaw, drone.yaw)
    }
}

/** The hand-written twin of the gizmo `@ScaleHandle` generates on [Drone]. */
public object DroneScaleTwin : Gizmo<Drone> {
    override val component: ComponentType<Drone> = Drone

    override fun GizmoScope<Drone>.build(target: GizmoTarget<Drone>) {
        val drone = target.component
        scaleHandles(target, Drone::wide, drone.wide, Drone::deep, drone.deep, Drone::tall, drone.tall)
    }
}

/**
 * The hand-written twin of the gizmo `@PositionHandle` generates on [Beacon]: a class, so the
 * registry has to construct it.
 */
public class BeaconPositionTwin : Gizmo<Beacon> {
    override val component: ComponentType<Beacon> = Beacon

    override fun GizmoScope<Beacon>.build(target: GizmoTarget<Beacon>) {
        moveHandles(target, Beacon::x, target.component.x, Beacon::y, target.component.y)
    }
}

/** The hand-written twin of the gizmo `@RadiusHandle` generates on [Beacon.reach]: an object. */
public object BeaconReachTwin : Gizmo<Beacon> {
    override val component: ComponentType<Beacon> = Beacon

    override fun GizmoScope<Beacon>.build(target: GizmoTarget<Beacon>) {
        radiusHandle(target, Beacon::reach, target.component.reach)
    }
}

/** The hand-written twin of the gizmo `@RotationHandle` generates on [Crate]. */
public object CrateHeadingTwin : Gizmo<Crate> {
    override val component: ComponentType<Crate> = Crate

    override fun GizmoScope<Crate>.build(target: GizmoTarget<Crate>) {
        rotationHandle(target, Crate::heading, target.component.heading)
    }
}
