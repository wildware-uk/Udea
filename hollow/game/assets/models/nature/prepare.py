"""Copies the Kenney Nature Kit models the clearing uses into this folder, lit and recoloured.

Usage: python3 prepare.py <the kit's "Models/GLTF format" folder>

The kit's `.glb` files are the published ones except for their materials, and this script is the
whole of the change (see NOTICE.md):

- every material is marked `KHR_materials_unlit`, which draws a flat colour that no light, shadow
  or ambient reaches. The clearing is lit by a sun that casts shadows, so the extension is removed
  and each material becomes a rough, non-metallic PBR surface;
- the kit's mint-and-orange palette is swapped for forest colours, written below as sRGB and stored
  linear, which is what glTF's `baseColorFactor` is.

Geometry, node transforms and the binary buffer are copied byte for byte.
"""

import json
import os
import struct
import sys

# The models the clearing places, by the kit's own file names.
MODELS = [
    "tree_pineTallA_detailed.glb",
    "tree_pineTallB_detailed.glb",
    "tree_pineRoundC.glb",
    "tree_pineRoundE.glb",
    "tree_oak.glb",
    "tree_default.glb",
    "tree_detailed.glb",
    "tree_fat.glb",
    "stone_largeA.glb",
    "stone_largeC.glb",
    "stone_tallA.glb",
    "stone_tallE.glb",
    "stone_smallA.glb",
    "stone_smallC.glb",
    "stump_round.glb",
    "log_large.glb",
    "fence_simple.glb",
    "grass_large.glb",
    "grass.glb",
    "plant_bush.glb",
    "plant_bushLarge.glb",
    "mushroom_redGroup.glb",
    "flower_yellowA.glb",
    "flower_purpleA.glb",
]

# The kit's material names, and the sRGB colour each becomes. A name not here keeps its colour.
PALETTE = {
    "leafsDark": "2f5a36",
    "leafsGreen": "4d8636",
    "woodBark": "6b4a2f",
    "woodBarkDark": "4f3726",
    "woodInner": "c9a774",
    "stone": "8e8c86",
    "grass": "5b9435",
    "wood": "8a6440",
    "woodDark": "6a4a2e",
    "colorRed": "c0392b",
    "colorYellow": "f2c230",
    "colorPurple": "8e6ac8",
}

UNLIT = "KHR_materials_unlit"
ROUGHNESS = 0.9


def linear(channel):
    c = channel / 255.0
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4


def rgba(hex_colour):
    return [round(linear(int(hex_colour[i:i + 2], 16)), 6) for i in (0, 2, 4)] + [1.0]


def chunks(data):
    magic, version, _ = struct.unpack("<4sII", data[:12])
    if magic != b"glTF" or version != 2:
        raise ValueError("not a glTF 2 binary")
    at = 12
    out = []
    while at < len(data):
        length, kind = struct.unpack("<I4s", data[at:at + 8])
        out.append((kind, data[at + 8:at + 8 + length]))
        at += 8 + length
    return out


def relight(document):
    for material in document.get("materials", []):
        material.get("extensions", {}).pop(UNLIT, None)
        if not material.get("extensions"):
            material.pop("extensions", None)
        pbr = material.setdefault("pbrMetallicRoughness", {})
        colour = PALETTE.get(material.get("name"))
        if colour is not None:
            pbr["baseColorFactor"] = rgba(colour)
        pbr["metallicFactor"] = 0.0
        pbr["roughnessFactor"] = ROUGHNESS
    for key in ("extensionsUsed", "extensionsRequired"):
        if key in document:
            document[key] = [name for name in document[key] if name != UNLIT]
            if not document[key]:
                del document[key]


def pack(document, binary):
    text = json.dumps(document, separators=(",", ":")).encode("utf-8")
    text += b" " * (-len(text) % 4)
    body = struct.pack("<I4s", len(text), b"JSON") + text
    if binary is not None:
        binary += b"\0" * (-len(binary) % 4)
        body += struct.pack("<I4s", len(binary), b"BIN\0") + binary
    return struct.pack("<4sII", b"glTF", 2, 12 + len(body)) + body


def main(source):
    here = os.path.dirname(os.path.abspath(__file__))
    for name in MODELS:
        with open(os.path.join(source, name), "rb") as f:
            parts = dict(chunks(f.read()))
        document = json.loads(parts[b"JSON"])
        relight(document)
        with open(os.path.join(here, name), "wb") as f:
            f.write(pack(document, parts.get(b"BIN\0")))
        print("wrote", name)


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    main(sys.argv[1])
