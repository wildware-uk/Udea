package dev.wildware.udea.annotations

import kotlin.reflect.KClass

/*
 * The editor's gizmo handles (issue #233, epic #231).
 *
 * A handle is something a person drags in the editor's Scene tab to change a component. Each
 * annotation here is shorthand for a `Gizmo` a game could write by hand: `udea-codegen` turns it
 * into a generated `Gizmo` class with direct field access, in the game's `editor` source set, and
 * lists it in the game's `<Game>GizmoRegistry`. Nothing here is read at run time, and nothing a
 * handle generates reaches a release classpath (`UDEA-MG-012`).
 *
 * A handle that drives **several** fields marks the class and names the fields as strings, because
 * a Kotlin annotation cannot hold a property reference. `udea-codegen` checks every name at compile
 * time: a misspelled, missing, read-only or non-`Float` field fails the build under `UDEA0017`, with
 * a did-you-mean. A handle that drives **one** field marks that field.
 *
 * Positions are world-space floats. A 2D game's handles sit on the ground plane, z = 0, which is
 * why an empty string - "no such axis" - is the default for `z` and `depth`.
 */

/**
 * A handle that moves the entity: drags [x], [y] and, when named, [z].
 *
 * The handle sits at the point those fields hold. With [z] empty it is a 2D handle, dragged on the
 * ground plane; with [z] named it is dragged in the plane facing the view.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
@MustBeDocumented
public annotation class PositionHandle(
    val x: String = "x",
    val y: String = "y",
    /** Empty for a 2D position, which lives on the ground plane. */
    val z: String = "",
)

/**
 * A handle that resizes the entity: drags [width], [height] and, when named, [depth].
 *
 * The size is centred on the entity's position, and the handle is the box corner at `+width/2`,
 * `+height/2` (and `+depth/2`). Dragging it moves that corner, so the size stays centred.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
@MustBeDocumented
public annotation class SizeHandle(
    val width: String = "width",
    val height: String = "height",
    /** Empty for a 2D size. */
    val depth: String = "",
)

/**
 * A handle that turns the entity about the up axis: drags [rotation], in radians about Z.
 *
 * Radians about Z because that is `Transform3D.rotationZ`, the heading, and Z is up in this engine.
 * The handle is a ring around the entity's position, lying on the ground plane.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
@MustBeDocumented
public annotation class RotationHandle(
    val rotation: String = "rotation",
)

/**
 * A handle on the edge of a circle of this radius around the entity: dragging it sets the field
 * to the distance from the entity's position. For a body's own size - a collider, a hit radius.
 *
 * The field must be a `var` of type `Float`.
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.BINARY)
@MustBeDocumented
public annotation class RadiusHandle

/**
 * A handle on the edge of a circle of this range around the entity: dragging it sets the field to
 * the distance from the entity's position. For a reach - an attack range, an aggro radius.
 *
 * The same drag as [RadiusHandle], drawn differently, because a range is somewhere the entity
 * reaches rather than something it is. The field must be a `var` of type `Float`.
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.BINARY)
@MustBeDocumented
public annotation class RangeHandle

/**
 * Which of a module's components carry a handle annotation. **Written by `udea-codegen`, on the
 * module's generated `<Module>ModuleRegistry`; never write it by hand.**
 *
 * It is how a handle declared in one module becomes a gizmo generated in another. A game's
 * components live in its release module, and their gizmos must not: a `Gizmo` implements an
 * `udea-editor` type, which only the game's `editor` source set may see. So the release module's
 * KSP run validates its handles and lists the components here, and the editor source set's KSP run
 * reads this list off each module registry the build names - by exact class name, the same lookup
 * the launcher registry makes - and generates the gizmos there. Nothing scans a classpath.
 *
 * BINARY retention, like every annotation in this module: KSP reads it from the compiled registry,
 * and nothing reads it at run time.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
@MustBeDocumented
public annotation class HandleIndex(
    val components: Array<KClass<*>>,
)
