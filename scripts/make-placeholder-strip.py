"""Writes moba/assets/sprites/champion_idle.png: the committed placeholder the atlas is packed from.

Why this exists
---------------
`moba/src/main/resources/assets/sprites/` holds the Tiny RPG character pack, which is licensed
third-party art and gitignored (docs/art-assets.md). The build-time asset pipeline packs the
sheets a `.udea.kts` declares, so on a fresh clone with no art extracted it would pack nothing
and `:moba:run` would draw nothing - a green build proving nothing about the sprite path.

So `moba/assets` declares its own sheet over art this repository may actually ship: six 64x64
frames of a bobbing figure, drawn from arithmetic so the bytes are a function of this file and
nothing else. It is deliberately ugly. It is not concept art; it is the smallest thing that
makes "the game draws packed atlas regions" a claim a screenshot can settle, and it animates so
that a frame index is visible in a capture rather than inferred.

Deterministic in every dimension a PNG can vary: no timestamp chunk, fixed filter byte 0 on
every row, one IDAT, zlib level 9. Rerunning it on any machine produces identical bytes.

Usage: python scripts/make-placeholder-strip.py
"""

import struct
import zlib
from pathlib import Path

FRAMES = 6
SIZE = 64
WIDTH = FRAMES * SIZE
HEIGHT = SIZE

# A palette chosen so the figure reads at a glance in a 1280x720 capture and so the frames are
# distinguishable from each other: the body colour walks through the ramp as the animation runs.
BODY = [(232, 196, 120), (226, 184, 108), (219, 172, 98), (226, 184, 108), (232, 196, 120), (238, 208, 132)]
OUTLINE = (36, 30, 46)
ACCENT = (94, 148, 208)


def figure(frame, x, y):
    """RGBA of pixel (x, y) inside frame `frame`, or None for transparent."""
    bob = (0, 1, 2, 2, 1, 0)[frame]
    cx = SIZE // 2
    head_cy = 20 + bob
    # Head: a filled circle with a one-pixel outline.
    dx, dy = x - cx, y - head_cy
    head = dx * dx + dy * dy
    if head <= 8 * 8:
        return OUTLINE if head > 7 * 7 else BODY[frame]
    # Body: a tapering trunk from the shoulders to the feet.
    top, bottom = head_cy + 8, SIZE - 6 + bob
    if top <= y < bottom:
        half = 6 + (y - top) // 6
        if abs(x - cx) <= half:
            return OUTLINE if abs(x - cx) == half else BODY[frame]
    # A belt, so a scaled-down frame still has an internal edge to look at.
    if top + 12 <= y < top + 15 and abs(x - cx) <= 6:
        return ACCENT
    return None


def main():
    rows = bytearray()
    for y in range(HEIGHT):
        rows.append(0)  # filter type 0 (None) on every row, so the bytes never depend on a heuristic
        for x in range(WIDTH):
            frame = x // SIZE
            colour = figure(frame, x % SIZE, y)
            if colour is None:
                rows += bytes((0, 0, 0, 0))
            else:
                rows += bytes((colour[0], colour[1], colour[2], 255))

    def chunk(kind, payload):
        return (
            struct.pack(">I", len(payload))
            + kind
            + payload
            + struct.pack(">I", zlib.crc32(kind + payload) & 0xFFFFFFFF)
        )

    png = (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", struct.pack(">IIBBBBB", WIDTH, HEIGHT, 8, 6, 0, 0, 0))
        + chunk(b"IDAT", zlib.compress(bytes(rows), 9))
        + chunk(b"IEND", b"")
    )

    out = Path(__file__).resolve().parent.parent / "moba" / "assets" / "sprites" / "champion_idle.png"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_bytes(png)
    print(f"{out} ({len(png)} bytes, {WIDTH}x{HEIGHT}, {FRAMES} frames)")


if __name__ == "__main__":
    main()
