"""Builds the glTF `extras` fixture for issue #271, headless, in Blender.

    blender -b --python build_module.py -- <out-dir>

One file, ``module.glb``, written next to this script by default: a small module an artist has
described with Blender's own Custom Properties, which is how robot-game wants to author a
module's size, mass and power instead of smuggling them through node names.

* The ``module`` object carries ``module_size`` (a string), ``mass`` (a float), ``power`` (an
  int), ``armoured`` (a bool), ``offset`` (a float vector) and ``fitting``, a group holding
  ``slots``: every shape of value a Custom Property can take that the engine reads.
* The ``socket_top`` Empty carries ``accepts``, so a socket can say which size fits it.
* The scene carries ``tier``, a value about the whole file rather than one object in it.

Exported with ``export_extras=True``, the exporter's "Custom Properties" box: that is what puts a
Custom Property into the glTF ``extras`` of the node, or scene, it was set on.

Re-run this script rather than editing the `.glb`: it is the source, and it is checked in beside
what it makes so a reviewer can rebuild it and compare.
"""

import os
import sys

import bpy


def build():
    bpy.ops.wm.read_factory_settings(use_empty=True)
    scene = bpy.context.scene
    scene["tier"] = 2

    bpy.ops.mesh.primitive_cube_add(size=0.5, location=(0.0, 0.0, 0.25))
    module = bpy.context.object
    module.name = "module"
    module["module_size"] = "small"
    module["mass"] = 12.5
    module["power"] = 3
    module["armoured"] = True
    module["offset"] = [0.0, 0.0, 0.5]
    module["fitting"] = {"slots": 2}

    bpy.ops.object.empty_add(type="ARROWS", location=(0.0, 0.0, 0.5))
    socket = bpy.context.object
    socket.name = "socket_top"
    socket["accepts"] = "small"
    socket.parent = module


def export(out):
    os.makedirs(out, exist_ok=True)
    bpy.ops.export_scene.gltf(
        filepath=os.path.join(out, "module.glb"),
        export_format="GLB",
        export_extras=True,
        export_animations=False,
        export_cameras=False,
        export_lights=False,
    )


def main():
    args = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
    out = args[0] if args else os.path.dirname(os.path.abspath(__file__))
    build()
    export(out)


main()
