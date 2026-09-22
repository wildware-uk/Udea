"""Writes rover.glb: the template's one model, a small six-part rover built out of boxes.

Usage: python3 make_rover.py   (the standard library only; writes rover.glb beside this file)

Nothing in it is third-party and nothing is downloaded: every vertex is computed below, so running
this again writes the same bytes. Replace the model with your own - export a `.glb` from Blender,
drop it here and point `models.udea.kts` at it - and delete this script.

The axes are glTF's own: +Y is up and the rover faces +X, its length along X and its width along Z.
The engine draws a glTF file's +Y along the world's +Z, so it stands on the ground and drives
east, the way `RoverSystem` moves it.
"""

import json
import os
import struct

# name, centre (x, y, z), size (x, y, z), material
BOXES = [
    ("body", (0.0, 0.45, 0.0), (1.6, 0.4, 1.0), "paint"),
    ("cab", (0.35, 0.8, 0.0), (0.55, 0.3, 0.7), "shell"),
    ("mast", (-0.55, 0.95, 0.3), (0.06, 0.6, 0.06), "tyre"),
    ("wheel_front_left", (0.55, 0.22, 0.58), (0.44, 0.44, 0.2), "tyre"),
    ("wheel_front_right", (0.55, 0.22, -0.58), (0.44, 0.44, 0.2), "tyre"),
    ("wheel_back_left", (-0.55, 0.22, 0.58), (0.44, 0.44, 0.2), "tyre"),
    ("wheel_back_right", (-0.55, 0.22, -0.58), (0.44, 0.44, 0.2), "tyre"),
]

# Linear base colours: a bright orange that no sky colour is near, so a picture of the rover can be
# told from a picture of nothing by colour alone.
MATERIALS = {
    "paint": (0.95, 0.35, 0.05, 1.0),
    "shell": (0.85, 0.85, 0.8, 1.0),
    "tyre": (0.05, 0.05, 0.06, 1.0),
}

# The six faces of a unit box: the outward normal, then four corners counter-clockwise seen from
# outside, each corner a sign per axis.
FACES = [
    ((1, 0, 0), [(1, -1, -1), (1, 1, -1), (1, 1, 1), (1, -1, 1)]),
    ((-1, 0, 0), [(-1, -1, 1), (-1, 1, 1), (-1, 1, -1), (-1, -1, -1)]),
    ((0, 1, 0), [(-1, 1, -1), (-1, 1, 1), (1, 1, 1), (1, 1, -1)]),
    ((0, -1, 0), [(-1, -1, 1), (-1, -1, -1), (1, -1, -1), (1, -1, 1)]),
    ((0, 0, 1), [(-1, -1, 1), (1, -1, 1), (1, 1, 1), (-1, 1, 1)]),
    ((0, 0, -1), [(1, -1, -1), (-1, -1, -1), (-1, 1, -1), (1, 1, -1)]),
]


def box(centre, size):
    """Positions, normals and triangle indices of one box: flat-shaded, 24 vertices."""
    positions, normals, indices = [], [], []
    for normal, corners in FACES:
        base = len(positions)
        for corner in corners:
            positions.append(tuple(c + s * h / 2 for c, s, h in zip(centre, corner, size)))
            normals.append(normal)
        indices += [base, base + 1, base + 2, base, base + 2, base + 3]
    return positions, normals, indices


def glb():
    names = list(MATERIALS)
    # One primitive per material: every box of that colour, in one vertex buffer.
    parts = {name: ([], [], []) for name in names}
    for _, centre, size, material in BOXES:
        positions, normals, indices = parts[material]
        more_positions, more_normals, more_indices = box(centre, size)
        offset = len(positions)
        positions += more_positions
        normals += more_normals
        indices += [offset + i for i in more_indices]

    binary = b""
    views, accessors, primitives = [], [], []

    def add(blob, target):
        nonlocal binary
        views.append({"buffer": 0, "byteOffset": len(binary), "byteLength": len(blob), "target": target})
        binary += blob + b"\0" * (-len(blob) % 4)
        return len(views) - 1

    for index, name in enumerate(names):
        positions, normals, indices = parts[name]
        flat_positions = [v for p in positions for v in p]
        flat_normals = [float(v) for n in normals for v in n]
        position_view = add(struct.pack("<%df" % len(flat_positions), *flat_positions), 34962)
        normal_view = add(struct.pack("<%df" % len(flat_normals), *flat_normals), 34962)
        index_view = add(struct.pack("<%dH" % len(indices), *indices), 34963)
        accessors.append({
            "bufferView": position_view, "componentType": 5126, "count": len(positions), "type": "VEC3",
            "min": [min(p[axis] for p in positions) for axis in range(3)],
            "max": [max(p[axis] for p in positions) for axis in range(3)],
        })
        accessors.append({"bufferView": normal_view, "componentType": 5126, "count": len(normals), "type": "VEC3"})
        accessors.append({"bufferView": index_view, "componentType": 5123, "count": len(indices), "type": "SCALAR"})
        primitives.append({
            "attributes": {"POSITION": len(accessors) - 3, "NORMAL": len(accessors) - 2},
            "indices": len(accessors) - 1,
            "material": index,
        })

    document = {
        "asset": {"version": "2.0", "generator": "new-game make_rover.py"},
        "scene": 0,
        "scenes": [{"nodes": [0]}],
        "nodes": [{"name": "rover", "mesh": 0}],
        "meshes": [{"name": "rover", "primitives": primitives}],
        "materials": [
            {
                "name": name,
                "pbrMetallicRoughness": {
                    "baseColorFactor": list(MATERIALS[name]),
                    "metallicFactor": 0.0,
                    "roughnessFactor": 0.7,
                },
            }
            for name in names
        ],
        "accessors": accessors,
        "bufferViews": views,
        "buffers": [{"byteLength": len(binary)}],
    }
    text = json.dumps(document, separators=(",", ":")).encode("utf-8")
    text += b" " * (-len(text) % 4)
    body = struct.pack("<I4s", len(text), b"JSON") + text + struct.pack("<I4s", len(binary), b"BIN\0") + binary
    return struct.pack("<4sII", b"glTF", 2, 12 + len(body)) + body


def main():
    out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "rover.glb")
    data = glb()
    with open(out, "wb") as f:
        f.write(data)
    print("wrote", out, len(data), "bytes")


if __name__ == "__main__":
    main()
