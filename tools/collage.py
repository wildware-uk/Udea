#!/usr/bin/env python3
"""Tile a sequence of PNG frames into one collage.

    tools/collage.py <dir-or-glob> [-o out.png] [--cols N] [--rows N]
                     [--scale 0.25] [--width 320] [--every N] [--limit N]
                     [--label none|number|name] [--title "..."]

Why this exists: every visual defect found on this project so far was found by
recording an mp4, pulling frames out with ffmpeg, tiling them and looking. That
loop works, but a video codec softens exactly the edges being judged - a
particle's segment joints, a ring's rim against the visible quad - and it
cannot capture a chosen instant. `inspect:start_recording` writes frame-exact
PNGs; this turns them into the one image you actually look at.

It replaces:

    ffmpeg -i clip.mp4 -vf "fps=1,scale=320:-1,tile=5x2" -frames:v 1 tile.png

and unlike it, the tiles are labelled. "Which frame is this" is the first
question anybody asks of a tile sheet, and an unlabelled sheet cannot answer
it - which is how a sequence read backwards gets reported as a bug and a
sequence read forwards gets reported as fine.

Frames are ordered by filename, which is why the recorder zero-pads them: a
lexical sort of `beam-0001.png ... beam-0240.png` is a temporal sort, and a
sort of `beam-1.png ... beam-240.png` is not. Files that are not zero-padded
are still handled - any run of digits in the name is compared numerically.

Examples:

    tools/collage.py build/debug-screenshots/issue271-beam -o /tmp/beam.png
    tools/collage.py 'frames/*.png' --cols 6 --width 240 --every 2
    tools/collage.py frames --limit 400 --width 120 --label number
"""

import argparse
import glob
import math
import os
import re
import sys

try:
    from PIL import Image, ImageDraw, ImageFont
except ImportError:
    sys.exit("collage.py needs Pillow: pip install pillow")

BG = (20, 18, 26)
GRID = (50, 44, 66)
LABEL_BG = (0, 0, 0, 170)
LABEL_FG = (232, 228, 240)
TITLE_FG = (232, 184, 75)

PAD = 4


def natural_key(path):
    """Sort by digit runs numerically, everything else lexically.

    Zero-padded names sort correctly either way; this is only here so a
    directory of frames somebody produced by hand does not come out with 10
    between 1 and 2, which reads as a sequence that jumps.
    """
    name = os.path.basename(path)
    return [int(p) if p.isdigit() else p.lower() for p in re.split(r"(\d+)", name)]


def collect(target):
    if os.path.isdir(target):
        paths = glob.glob(os.path.join(target, "*.png")) + glob.glob(os.path.join(target, "*.PNG"))
    else:
        paths = glob.glob(target)
        paths = [p for p in paths if p.lower().endswith(".png")]
    return sorted(paths, key=natural_key)


def grid_for(count, cols, rows, tile_aspect=16 / 9):
    """Choose a grid that wastes few cells and reads at roughly 16:9.

    Two things make a sheet unreadable, and they pull against each other: a
    literally square grid of frames that are themselves 16:9 produces a sheet
    far taller than it is wide, and a grid chosen purely for the sheet's shape
    leaves a trailing row mostly empty. So every column count is scored on both
    and the best one wins - which lands 4 frames on 2x2 and 400 on 20x20.
    """
    if cols and rows:
        return cols, rows
    if cols:
        return cols, math.ceil(count / cols)
    if rows:
        return math.ceil(count / rows), rows

    best, best_score = (count, 1), None
    for c in range(1, count + 1):
        r = math.ceil(count / c)
        aspect = (c * tile_aspect) / r
        shape = abs(math.log(aspect / 1.6))
        waste = (c * r - count) / count
        # A sheet taller than it is wide has to be scrolled to be read, so it
        # loses a tie it would otherwise win: two frames go side by side.
        score = shape + waste * 1.5 + (0.5 if aspect < 1 else 0)
        if best_score is None or score < best_score:
            best, best_score = (c, r), score
    return best


def load_font(size):
    for name in (
        "DejaVuSans-Bold.ttf",
        "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
        "/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf",
    ):
        try:
            return ImageFont.truetype(name, size)
        except OSError:
            continue
    return ImageFont.load_default()


def label_for(mode, index, path):
    """The tile's caption.

    "number" prefers the frame number in the FILENAME over the tile's position
    in this collage, so a sheet built from a glob subset - the last thirty
    frames, every fourth frame - still labels each tile with the frame the game
    actually drew. A sheet whose labels restart at 1 cannot be lined up against
    the full recording, an event log or another sheet, which is most of what a
    tile number is for. Falls back to the position when the name carries no
    number at all.
    """
    if mode == "none":
        return None
    stem = os.path.splitext(os.path.basename(path))[0]
    if mode == "name":
        return stem
    runs = re.findall(r"\d+", stem)
    return str(int(runs[-1])) if runs else str(index)


def main():
    ap = argparse.ArgumentParser(
        description="Tile PNG frames into one collage.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__.split("Examples:", 1)[1] if "Examples:" in __doc__ else None,
    )
    ap.add_argument("target", help="Directory of PNGs, or a glob (quote it)")
    ap.add_argument("-o", "--out", help="Output PNG (default <target>-collage.png)")
    ap.add_argument("--cols", type=int, help="Columns; derived roughly square if unset")
    ap.add_argument("--rows", type=int, help="Rows; derived from the column count if unset")
    ap.add_argument("--scale", type=float, help="Scale each frame by this factor")
    ap.add_argument("--width", type=int, help="Scale each frame to this width instead")
    ap.add_argument("--every", type=int, default=1, help="Take every Nth frame (default 1)")
    ap.add_argument("--limit", type=int, help="Stop after this many frames, after --every")
    ap.add_argument(
        "--label",
        choices=("none", "number", "name"),
        default="number",
        help="Per-tile label (default number: the frame number in the filename, "
             "falling back to position in the sheet)",
    )
    ap.add_argument("--title", help="Caption strip across the top")
    args = ap.parse_args()

    paths = collect(args.target)
    if not paths:
        sys.exit(f"no PNGs at {args.target}")

    if args.every < 1:
        sys.exit("--every must be at least 1")

    # Position within this collage, used only as the label of last resort - see
    # label_for, which prefers the frame number carried by the filename.
    picked = [(i + 1, p) for i, p in enumerate(paths)][:: args.every]
    if args.limit:
        picked = picked[: args.limit]

    with Image.open(picked[0][1]) as probe:
        src_w, src_h = probe.size

    cols, rows = grid_for(len(picked), args.cols, args.rows, src_w / max(1, src_h))

    if args.width:
        tile_w = max(1, args.width)
        tile_h = max(1, round(src_h * tile_w / src_w))
    elif args.scale:
        tile_w = max(1, round(src_w * args.scale))
        tile_h = max(1, round(src_h * args.scale))
    else:
        # A 5x10 grid of untouched 1280x720 frames is 6400x7200 and unusable, so
        # unasked the sheet targets a width a person can look at whole - about
        # 2400px - with a floor so a 400-frame sheet is still made of pictures
        # rather than of swatches, and a ceiling so a 4-frame sheet is a sheet.
        tile_w = min(max(2400 // max(1, cols), 96), src_w, 640)
        tile_h = max(1, round(src_h * tile_w / src_w))

    font_size = max(9, min(20, tile_h // 9))
    font = load_font(font_size)

    title_h = font_size * 2 + PAD * 2 if args.title else 0
    sheet_w = cols * tile_w + (cols + 1) * PAD
    sheet_h = title_h + rows * tile_h + (rows + 1) * PAD

    sheet = Image.new("RGB", (sheet_w, sheet_h), BG)
    draw = ImageDraw.Draw(sheet, "RGBA")

    if args.title:
        draw.text((PAD * 2, PAD), args.title, fill=TITLE_FG, font=load_font(font_size + 4))

    for slot, (number, path) in enumerate(picked):
        col, row = slot % cols, slot // cols
        x = PAD + col * (tile_w + PAD)
        y = title_h + PAD + row * (tile_h + PAD)
        try:
            with Image.open(path) as im:
                sheet.paste(im.convert("RGB").resize((tile_w, tile_h), Image.LANCZOS), (x, y))
        except Exception as e:  # a truncated frame must not lose the other 399
            draw.rectangle([x, y, x + tile_w - 1, y + tile_h - 1], fill=(60, 20, 20))
            draw.text((x + PAD, y + PAD), f"unreadable\n{e}", fill=LABEL_FG, font=font)

        draw.rectangle([x, y, x + tile_w - 1, y + tile_h - 1], outline=GRID)

        text = label_for(args.label, number, path)
        if text:
            box = draw.textbbox((0, 0), text, font=font)
            tw, th = box[2] - box[0], box[3] - box[1]
            draw.rectangle([x, y, x + tw + PAD * 2, y + th + PAD * 2], fill=LABEL_BG)
            draw.text((x + PAD, y + PAD - box[1]), text, fill=LABEL_FG, font=font)

    out = args.out
    if not out:
        stem = os.path.basename(os.path.normpath(args.target)).replace("*", "").replace(".png", "")
        out = f"{stem or 'frames'}-collage.png"
    os.makedirs(os.path.dirname(os.path.abspath(out)), exist_ok=True)
    sheet.save(out)

    skipped = len(paths) - len(picked)
    note = f", {skipped} not shown" if skipped else ""
    print(f"{out}  {cols}x{rows}, {len(picked)} of {len(paths)} frames{note}, {sheet_w}x{sheet_h}")


if __name__ == "__main__":
    main()
