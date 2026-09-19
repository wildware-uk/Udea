# Makes `bender/Bender.fbx`, the asset compiler's FBX test fixture (issue #244), and the texture it
# names, `bender/checker.png`. Written for this repository and dedicated to the public domain
# (CC0 1.0), like the file it makes.
#
#   blender -b -P make_bender.py -- <output folder>
#
# A column of eight rings, skinned to a two-bone armature, with a 4x4 checker texture linked (not
# embedded) through its material, and two actions: `Bend` (24 frames) and `Twist` (15 frames) at
# 24 frames a second, so one clip is a whole second and the other a length that is not a whole
# number of 60Hz ticks. Blender's FBX exporter names each take `<armature>|<action>`, which is
# what the converter's clip-name rule exists for.
import os
import sys

import bpy

out = sys.argv[sys.argv.index("--") + 1]
os.makedirs(out, exist_ok=True)

bpy.ops.wm.read_factory_settings(use_empty=True)
scene = bpy.context.scene
scene.render.fps = 24

# The texture: 4x4 texels, alternating two colours.
size = 4
image = bpy.data.images.new("checker", width=size, height=size, alpha=False)
pixels = []
for y in range(size):
    for x in range(size):
        pixels += [0.9, 0.3, 0.1, 1.0] if (x + y) % 2 == 0 else [0.1, 0.4, 0.9, 1.0]
image.pixels = pixels
image.filepath_raw = os.path.join(out, "checker.png")
image.file_format = "PNG"
image.save()

bpy.ops.mesh.primitive_cylinder_add(vertices=8, radius=0.3, depth=2.0, location=(0, 0, 1.0))
body = bpy.context.active_object
body.name = "Body"
bpy.ops.object.mode_set(mode="EDIT")
bpy.ops.mesh.loopcut_slide(MESH_OT_loopcut={"number_cuts": 6, "edge_index": 1})
bpy.ops.uv.cylinder_project()
bpy.ops.object.mode_set(mode="OBJECT")

material = bpy.data.materials.new("Checker")
material.use_nodes = True
nodes = material.node_tree.nodes
texture = nodes.new("ShaderNodeTexImage")
texture.image = image
material.node_tree.links.new(texture.outputs["Color"], nodes["Principled BSDF"].inputs["Base Color"])
body.data.materials.append(material)

bpy.ops.object.armature_add(location=(0, 0, 0))
rig = bpy.context.active_object
rig.name = "Rig"
bpy.ops.object.mode_set(mode="EDIT")
lower = rig.data.edit_bones[0]
lower.name = "Lower"
lower.head = (0, 0, 0)
lower.tail = (0, 0, 1)
upper = rig.data.edit_bones.new("Upper")
upper.head = (0, 0, 1)
upper.tail = (0, 0, 2)
upper.parent = lower
bpy.ops.object.mode_set(mode="OBJECT")

body.select_set(True)
rig.select_set(True)
bpy.context.view_layer.objects.active = rig
bpy.ops.object.parent_set(type="ARMATURE_AUTO")


def action(name, frames, bone, axis, angle):
    act = bpy.data.actions.new(name)
    act.use_fake_user = True
    rig.animation_data_create()
    rig.animation_data.action = act
    pose = rig.pose.bones[bone]
    pose.rotation_mode = "XYZ"
    for frame, value in ((0, 0.0), (frames // 2, angle), (frames, 0.0)):
        pose.rotation_euler = (0, 0, 0)
        pose.rotation_euler[axis] = value
        pose.keyframe_insert("rotation_euler", frame=frame)
    return act


action("Bend", 24, "Upper", 0, 0.8)
action("Twist", 15, "Lower", 2, 1.2)
rig.animation_data.action = bpy.data.actions["Bend"]

bpy.ops.export_scene.fbx(
    filepath=os.path.join(out, "Bender.fbx"),
    path_mode="RELATIVE",
    embed_textures=False,
    add_leaf_bones=False,
    bake_anim=True,
    bake_anim_use_all_actions=True,
    bake_anim_use_nla_strips=False,
)
print("wrote", sorted(os.listdir(out)))
