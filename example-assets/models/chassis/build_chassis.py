"""Builds the socket fixture models for issue #260, headless, in Blender.

    blender -b --python build_chassis.py -- <out-dir>

Two files, both low-poly and untextured, written next to this script by default:

* ``chassis.glb`` - a hull with five ``socket_*`` Empties. Four are fixed to the hull; the fifth,
  ``socket_roof``, is parented to a ring that spins, so a part mounted on it has to follow an
  animated node rather than a fixed one. The two end sockets are scaled to 0.6, which is how a
  chassis says a socket takes a small module.
* ``turret.glb`` - a module whose origin is on its plug, with two sockets of its own for a part
  mounted on a part: ``socket_top`` at half scale on its roof, and ``socket_muzzle`` at the end of
  its barrel, pointing the way the barrel does.

Every Empty's +Z points out of the face it sits on, which is the convention the engine reads: a
socket's frame is its own, and `ModelNode` reports it in the world's Z-up frame.

Re-run this script rather than editing the `.glb`: it is the source, and it is checked in beside
what it makes so a reviewer can rebuild both and compare.
"""

import math
import os
import sys

import bpy

HULL_LENGTH = 2.4
HULL_WIDTH = 1.4
HULL_HEIGHT = 0.5
HULL_BASE = 0.25

QUARTER = math.pi / 2
SPIN_FRAMES = 96


def clear():
    bpy.ops.wm.read_factory_settings(use_empty=True)


def material(name, rgb):
    mat = bpy.data.materials.new(name)
    mat.use_nodes = True
    bsdf = mat.node_tree.nodes["Principled BSDF"]
    bsdf.inputs["Base Color"].default_value = (*rgb, 1.0)
    bsdf.inputs["Roughness"].default_value = 0.6
    bsdf.inputs["Metallic"].default_value = 0.1
    return mat


def box(name, size, location, mat):
    bpy.ops.mesh.primitive_cube_add(size=1.0, location=location)
    obj = bpy.context.object
    obj.name = name
    obj.scale = (size[0] / 2, size[1] / 2, size[2] / 2)
    bpy.ops.object.transform_apply(location=False, rotation=False, scale=True)
    obj.data.materials.append(mat)
    for face in obj.data.polygons:
        face.use_smooth = False
    return obj


def cylinder(name, radius, depth, location, rotation, mat):
    bpy.ops.mesh.primitive_cylinder_add(radius=radius, depth=depth, vertices=12, location=location)
    obj = bpy.context.object
    obj.name = name
    obj.rotation_euler = rotation
    obj.data.materials.append(mat)
    for face in obj.data.polygons:
        face.use_smooth = False
    return obj


def socket(name, location, rotation, scale, parent):
    empty = bpy.data.objects.new(name, None)
    empty.empty_display_type = "ARROWS"
    empty.empty_display_size = 0.3
    empty.location = location
    empty.rotation_euler = rotation
    empty.scale = (scale, scale, scale)
    bpy.context.collection.objects.link(empty)
    empty.parent = parent
    empty.matrix_parent_inverse = parent.matrix_world.inverted()
    return empty


def build_chassis(out):
    clear()
    steel = material("chassis_steel", (0.32, 0.36, 0.42))
    rubber = material("chassis_rubber", (0.08, 0.08, 0.09))

    hull = box(
        "hull",
        (HULL_LENGTH, HULL_WIDTH, HULL_HEIGHT),
        (0.0, 0.0, HULL_BASE + HULL_HEIGHT / 2),
        steel,
    )
    for at, (x, y) in enumerate([(0.8, 0.8), (0.8, -0.8), (-0.8, 0.8), (-0.8, -0.8)]):
        wheel = cylinder(
            "wheel_%d" % at, 0.28, 0.2, (x, y, 0.28), (QUARTER, 0.0, 0.0), rubber
        )
        wheel.parent = hull
        wheel.matrix_parent_inverse = hull.matrix_world.inverted()

    # The turning mount. A part on its socket has to follow the animation, not the rest pose.
    ring = cylinder("mount_ring", 0.42, 0.12, (0.0, 0.0, HULL_BASE + HULL_HEIGHT + 0.06), (0, 0, 0), steel)
    ring.parent = hull
    ring.matrix_parent_inverse = hull.matrix_world.inverted()
    # A notch, so which way the ring is facing is visible in a picture.
    notch = box("mount_notch", (0.5, 0.12, 0.1), (0.3, 0.0, HULL_BASE + HULL_HEIGHT + 0.06), steel)
    notch.parent = ring
    notch.matrix_parent_inverse = ring.matrix_world.inverted()

    top = HULL_BASE + HULL_HEIGHT
    socket("socket_roof", (0.0, 0.0, top + 0.12), (0.0, 0.0, 0.0), 1.0, ring)
    socket("socket_left", (0.35, HULL_WIDTH / 2, top - 0.15), (-QUARTER, 0.0, 0.0), 1.0, hull)
    socket("socket_right", (0.35, -HULL_WIDTH / 2, top - 0.15), (QUARTER, 0.0, 0.0), 1.0, hull)
    socket("socket_front", (HULL_LENGTH / 2, 0.0, top - 0.2), (0.0, QUARTER, 0.0), 0.6, hull)
    socket("socket_rear", (-HULL_LENGTH / 2, 0.0, top - 0.2), (0.0, -QUARTER, 0.0), 0.6, hull)

    spin(ring)
    export(out, "chassis.glb")


def spin(obj):
    """A full turn about Z over [1, SPIN_FRAMES + 1], linear and looping."""
    scene = bpy.context.scene
    scene.render.fps = 24
    scene.frame_start = 1
    scene.frame_end = SPIN_FRAMES
    obj.rotation_euler = (0.0, 0.0, 0.0)
    obj.keyframe_insert("rotation_euler", index=2, frame=1)
    obj.rotation_euler = (0.0, 0.0, 2 * math.pi)
    obj.keyframe_insert("rotation_euler", index=2, frame=SPIN_FRAMES + 1)
    for curve in fcurves(obj):
        for point in curve.keyframe_points:
            point.interpolation = "LINEAR"
    obj.animation_data.action.name = "Spin"


def fcurves(obj):
    action = obj.animation_data.action
    if hasattr(action, "fcurves") and len(action.fcurves) > 0:
        return list(action.fcurves)
    out = []
    for layer in action.layers:
        for strip in layer.strips:
            for bag in strip.channelbags:
                out.extend(bag.fcurves)
    return out


def build_turret(out):
    clear()
    paint = material("turret_paint", (0.75, 0.42, 0.12))
    steel = material("turret_steel", (0.3, 0.3, 0.33))

    body = box("turret_body", (0.6, 0.6, 0.42), (0.0, 0.0, 0.21), paint)
    barrel = cylinder("turret_barrel", 0.07, 0.8, (0.45, 0.0, 0.3), (0.0, QUARTER, 0.0), steel)
    barrel.parent = body
    barrel.matrix_parent_inverse = body.matrix_world.inverted()

    # Half scale, because what fits on a turret's roof is smaller than the turret.
    socket("socket_top", (0.0, 0.0, 0.42), (0.0, 0.0, 0.0), 0.5, body)
    # The muzzle: +Z points the way the barrel does, which is what a shot is spawned along. Far
    # enough from the turret's own axis that a part on it sweeps when the mount turns.
    socket("socket_muzzle", (0.85, 0.0, 0.3), (0.0, QUARTER, 0.0), 0.35, body)
    export(out, "turret.glb")


def export(out, name):
    path = os.path.join(out, name)
    bpy.ops.export_scene.gltf(
        filepath=path,
        export_format="GLB",
        export_apply=True,
        export_animations=True,
        export_cameras=False,
        export_lights=False,
    )
    print("wrote %s" % path)


def main():
    args = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
    out = args[0] if args else os.path.dirname(os.path.abspath(__file__))
    build_chassis(out)
    build_turret(out)


main()
