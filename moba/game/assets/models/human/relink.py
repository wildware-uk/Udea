# How `Human.fbx` was made from Quaternius' "Animated Human" (CC0), issue #244. Kept beside the
# file so the change to it can be repeated and checked rather than taken on trust.
#
#   blender -b -P relink.py -- <Animated Human.fbx> <ClothedLightSkin.png> <out/Human.fbx>
#
# Run with Blender 5.2.2. It imports the published FBX, keeps four of its nine takes (Idle, Walk,
# Run and Punch; the rest are two unnamed test actions and three the game has no use for), links
# the pack's `ClothedLightSkin.png` atlas to the one material (the published FBX has the UVs but
# names no texture), and exports an FBX that refers to the texture by a relative path.
import sys

import bpy
from io_scene_fbx import export_fbx_bin

# Blender's exporter writes each texture's absolute path beside its relative one, which would put
# the directory of whoever ran this into the committed file. Both are written as the relative path.
_gen_vid_path = export_fbx_bin._gen_vid_path


def _relative_only(img, scene_data):
    _, relative = _gen_vid_path(img, scene_data)
    return relative, relative


export_fbx_bin._gen_vid_path = _relative_only

args = sys.argv[sys.argv.index("--") + 1:]
source, texture, out = args
KEEP = {"Idle", "Walk", "Run", "Punch"}

bpy.ops.wm.read_factory_settings(use_empty=True)
bpy.ops.import_scene.fbx(filepath=source)

for action in list(bpy.data.actions):
    clip = action.name.split("|")[-1]
    if clip not in KEEP:
        bpy.data.actions.remove(action)
    else:
        action.name = clip
        action.use_fake_user = True

image = bpy.data.images.load(texture)
for material in bpy.data.materials:
    material.use_nodes = True
    nodes = material.node_tree.nodes
    bsdf = next(n for n in nodes if n.type == "BSDF_PRINCIPLED")
    tex = nodes.new("ShaderNodeTexImage")
    tex.image = image
    tex.interpolation = "Closest"
    material.node_tree.links.new(tex.outputs["Color"], bsdf.inputs["Base Color"])

for obj in bpy.data.objects:
    if obj.animation_data:
        obj.animation_data.action = bpy.data.actions.get("Idle")
        for track in list(obj.animation_data.nla_tracks):
            obj.animation_data.nla_tracks.remove(track)

bpy.ops.export_scene.fbx(
    filepath=out,
    path_mode="RELATIVE",
    embed_textures=False,
    add_leaf_bones=False,
    bake_anim=True,
    bake_anim_use_all_actions=True,
    bake_anim_use_nla_strips=False,
)
print("actions:", sorted(a.name for a in bpy.data.actions))
