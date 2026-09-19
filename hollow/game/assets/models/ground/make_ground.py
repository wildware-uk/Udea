"""Writes ground.glb: the clearing's floor, one textured square with the texture painted here.

Usage: python3 make_ground.py   (needs numpy and Pillow; writes ground.glb beside this file)

Nothing in it is third-party: the texture is computed from a fixed seed, so running this again
writes the same picture. It is grass, mottled at two scales, darker under the ring of trees, and
worn down to earth in the middle, where the fighting happens.

The square is SIZE metres a side, centred on the origin, in glTF's own axes: it lies in the X-Z
plane facing +Y, which the engine draws facing up (+Z), because glTF is Y-up and the world Z-up.
"""

import io
import json
import os
import struct

import numpy as np
from PIL import Image

SIZE = 80.0  # metres a side
PIXELS = 1024  # texture resolution: about 8 cm a pixel
SEED = 249

CLEARING = 8.0  # metres: the earth patch's radius
FOREST = 17.0  # metres: where the ground turns to forest floor

GRASS_DARK = np.array([54, 88, 34], dtype=np.float64)
GRASS_LIGHT = np.array([98, 136, 52], dtype=np.float64)
EARTH_DARK = np.array([96, 74, 50], dtype=np.float64)
EARTH_LIGHT = np.array([142, 114, 80], dtype=np.float64)
FOREST_FLOOR = np.array([58, 66, 34], dtype=np.float64)


def noise(rng, cells):
    """Smooth value noise in [0, 1]: a cells x cells random grid, scaled up bicubically."""
    grid = (rng.random((cells, cells)) * 255).astype(np.uint8)
    image = Image.fromarray(grid, mode="L").resize((PIXELS, PIXELS), Image.BICUBIC)
    return np.asarray(image, dtype=np.float64) / 255.0


def fbm(rng, octaves):
    total = np.zeros((PIXELS, PIXELS))
    weight = 0.0
    for cells, amplitude in octaves:
        total += noise(rng, cells) * amplitude
        weight += amplitude
    return total / weight


def smoothstep(edge0, edge1, x):
    t = np.clip((x - edge0) / (edge1 - edge0), 0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)


def paint():
    rng = np.random.default_rng(SEED)
    metres = (np.arange(PIXELS) + 0.5) / PIXELS * SIZE - SIZE / 2
    x, y = np.meshgrid(metres, metres)
    radius = np.hypot(x, y)

    mottle = fbm(rng, [(10, 1.0), (40, 0.6), (160, 0.35)])
    speckle = rng.random((PIXELS, PIXELS))
    grass = GRASS_DARK + (GRASS_LIGHT - GRASS_DARK) * mottle[..., None]
    grass *= (0.9 + 0.2 * speckle)[..., None]

    ragged = fbm(rng, [(24, 1.0), (96, 0.5)])
    earth_mask = 1.0 - smoothstep(CLEARING - 2.5, CLEARING + 1.0, radius + (ragged - 0.5) * 5.0)
    grit = fbm(rng, [(60, 1.0), (240, 0.8)])
    earth = EARTH_DARK + (EARTH_LIGHT - EARTH_DARK) * grit[..., None]
    pebbles = (rng.random((PIXELS, PIXELS)) > 0.985)[..., None]
    earth = np.where(pebbles, earth * 0.7, earth)

    forest_mask = smoothstep(FOREST - 3.0, FOREST + 6.0, radius + (mottle - 0.5) * 6.0)
    floor = FOREST_FLOOR * (0.85 + 0.3 * speckle)[..., None]

    colour = grass
    colour = colour + (floor - colour) * forest_mask[..., None]
    colour = colour + (earth - colour) * earth_mask[..., None]
    return Image.fromarray(np.clip(colour, 0, 255).astype(np.uint8), mode="RGB")


def png_bytes(image):
    out = io.BytesIO()
    image.save(out, format="PNG", optimize=True)
    return out.getvalue()


def glb(png):
    half = SIZE / 2
    positions = struct.pack("<12f", -half, 0, -half, half, 0, -half, half, 0, half, -half, 0, half)
    normals = struct.pack("<12f", *([0, 1, 0] * 4))
    uvs = struct.pack("<8f", 0, 0, 1, 0, 1, 1, 0, 1)
    # Counter-clockwise seen from +Y, so the face points up.
    indices = struct.pack("<6H", 0, 2, 1, 0, 3, 2) + b"\0\0"

    views = []
    binary = b""
    for blob in (positions, normals, uvs, indices, png):
        views.append({"buffer": 0, "byteOffset": len(binary), "byteLength": len(blob)})
        binary += blob + b"\0" * (-len(blob) % 4)
    views[3]["byteLength"] = 12
    for index, target in ((0, 34962), (1, 34962), (2, 34962), (3, 34963)):
        views[index]["target"] = target

    document = {
        "asset": {"version": "2.0", "generator": "hollow make_ground.py"},
        "scene": 0,
        "scenes": [{"nodes": [0]}],
        "nodes": [{"name": "ground", "mesh": 0}],
        "meshes": [{"name": "ground", "primitives": [{
            "attributes": {"POSITION": 0, "NORMAL": 1, "TEXCOORD_0": 2},
            "indices": 3,
            "material": 0,
        }]}],
        "materials": [{
            "name": "ground",
            "pbrMetallicRoughness": {
                "baseColorTexture": {"index": 0},
                "metallicFactor": 0.0,
                "roughnessFactor": 0.95,
            },
        }],
        "textures": [{"sampler": 0, "source": 0}],
        "samplers": [{"magFilter": 9729, "minFilter": 9987, "wrapS": 33071, "wrapT": 33071}],
        "images": [{"bufferView": 4, "mimeType": "image/png"}],
        "accessors": [
            {"bufferView": 0, "componentType": 5126, "count": 4, "type": "VEC3",
             "min": [-half, 0, -half], "max": [half, 0, half]},
            {"bufferView": 1, "componentType": 5126, "count": 4, "type": "VEC3"},
            {"bufferView": 2, "componentType": 5126, "count": 4, "type": "VEC2"},
            {"bufferView": 3, "componentType": 5123, "count": 6, "type": "SCALAR"},
        ],
        "bufferViews": views,
        "buffers": [{"byteLength": len(binary)}],
    }
    text = json.dumps(document, separators=(",", ":")).encode("utf-8")
    text += b" " * (-len(text) % 4)
    body = struct.pack("<I4s", len(text), b"JSON") + text + struct.pack("<I4s", len(binary), b"BIN\0") + binary
    return struct.pack("<4sII", b"glTF", 2, 12 + len(body)) + body


def main():
    out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "ground.glb")
    data = glb(png_bytes(paint()))
    with open(out, "wb") as f:
        f.write(data)
    print("wrote", out, len(data), "bytes")


if __name__ == "__main__":
    main()
