package dev.wildware.udea.codegen.gizmo

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.MemberName

/**
 * The `udea-editor` gizmo API generated code implements, and the Fleks type it names a component by.
 *
 * [ClassName]s, never a dependency: `udea-codegen` runs inside the compiler and only ever names these
 * types, and the module that compiles a gizmo has `udea-editor` on its classpath because it is an
 * `editor` source set. The same reasoning as `CoreNames`.
 */
internal object EditorNames {
    private const val GIZMO = "dev.wildware.udea.editor.gizmo"

    val GIZMO_INTERFACE: ClassName = ClassName(GIZMO, "Gizmo")
    val GIZMO_SCOPE: ClassName = ClassName(GIZMO, "GizmoScope")
    val GIZMO_TARGET: ClassName = ClassName(GIZMO, "GizmoTarget")
    val GIZMO_REGISTRY: ClassName = ClassName(GIZMO, "GizmoRegistry")
    val WORLD_POINT: ClassName = ClassName(GIZMO, "WorldPoint")
    val AXIS: ClassName = ClassName(GIZMO, "Axis")
    val HANDLE_SHAPE: ClassName = ClassName(GIZMO, "HandleShape")
    val DRAG_CONSTRAINT: ClassName = ClassName(GIZMO, "DragConstraint")

    /** The built-in 2D gizmos (issue #236): what a generated gizmo calls, as a hand-written one would. */
    val MOVE_HANDLES: MemberName = MemberName(GIZMO, "moveHandles")
    val SIZE_HANDLES: MemberName = MemberName(GIZMO, "sizeHandles")
    val ROTATION_HANDLE: MemberName = MemberName(GIZMO, "rotationHandle")
    val RADIUS_HANDLE: MemberName = MemberName(GIZMO, "radiusHandle")
    val RANGE_HANDLE: MemberName = MemberName(GIZMO, "rangeHandle")

    /** Fleks' handle on a component type: what `Gizmo.component` answers. */
    val COMPONENT_TYPE: ClassName = ClassName("com.github.quillraven.fleks", "ComponentType")

    const val GIZMO_FQN: String = "$GIZMO.Gizmo"
    const val COMPONENT_TYPE_FQN: String = "com.github.quillraven.fleks.ComponentType"
}

/**
 * One handle annotation, validated: the component it is on, and the fields it drives.
 *
 * Each becomes one generated `Gizmo` object. Every field name here has been checked to be a mutable
 * `Float` property of [component], so the emitter names it without asking again.
 */
internal sealed interface HandleModel {

    /** The component class. */
    val component: ClassName

    /** The generated gizmo's name: the component's, then what distinguishes this handle, then `Gizmo`. */
    val gizmo: ClassName

    /** The annotation's simple name, for the generated KDoc. */
    val annotation: String

    /** `@PositionHandle`: the handle sits at ([x], [y], [z] or 0) and moves those fields. */
    data class Position(
        override val component: ClassName,
        val x: String,
        val y: String,
        /** Absent for a 2D position, on the ground plane. */
        val z: String?,
    ) : HandleModel {
        override val gizmo: ClassName = gizmoName(component, "Position")
        override val annotation: String get() = "PositionHandle"
    }

    /** `@SizeHandle`: the box corner of a size centred on the entity. */
    data class Size(
        override val component: ClassName,
        val width: String,
        val height: String,
        /** Absent for a 2D size. */
        val depth: String?,
    ) : HandleModel {
        override val gizmo: ClassName = gizmoName(component, "Size")
        override val annotation: String get() = "SizeHandle"
    }

    /** `@RotationHandle`: a ring about the up axis, turning [field], in radians. */
    data class Rotation(
        override val component: ClassName,
        val field: String,
    ) : HandleModel {
        override val gizmo: ClassName = gizmoName(component, "Rotation")
        override val annotation: String get() = "RotationHandle"
    }

    /**
     * `@RadiusHandle` or `@RangeHandle` on [field]: a handle on the rim of a circle of that size,
     * setting the distance from the entity. The two differ in what is drawn, which is [kind]'s.
     */
    data class Reach(
        override val component: ClassName,
        val field: String,
        val kind: ReachKind,
    ) : HandleModel {
        override val gizmo: ClassName =
            gizmoName(component, field.replaceFirstChar(Char::uppercaseChar) + kind.suffix)
        override val annotation: String get() = kind.annotation
    }
}

/** The two one-field handles, which drag identically and look different. */
internal enum class ReachKind(val annotationFqn: String, val annotation: String, val suffix: String, val builtin: MemberName) {
    /** A body's own size: a dot on its rim. */
    Radius(dev.wildware.udea.codegen.AnnotationNames.RADIUS_HANDLE, "RadiusHandle", "Radius", EditorNames.RADIUS_HANDLE),

    /** A reach: a spoke from its rim back to the entity. */
    Range(dev.wildware.udea.codegen.AnnotationNames.RANGE_HANDLE, "RangeHandle", "Range", EditorNames.RANGE_HANDLE),
}

/**
 * `Crate` + `Size` is `CrateSizeGizmo`, in the component's own package; a nested component's
 * enclosing names are joined on, so `Outer.Inner` gives `OuterInnerSizeGizmo`.
 */
private fun gizmoName(component: ClassName, what: String): ClassName =
    ClassName(component.packageName, component.simpleNames.joinToString("") + what + "Gizmo")
