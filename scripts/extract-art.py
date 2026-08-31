"""How the committed Tiny RPG frames were unpacked from the two purchased archives.

**This is not a build step and it will not give a fresh clone a tree `:moba` can build.** The
build does that by itself now, in `:moba:udeaStageCharacterArt`; `docs/art-assets.md` is the
record of why.

Three things stop it being a fresh-clone step. It reads the two **paid** archives by exact
filename from a hardcoded Windows `~\\Downloads`; its destination is an absolute path on the
author's own machine; and it writes lowercased, un-hyphenated names (`sprites/wizard/idle.png`)
under `moba/src/main/resources/assets/sprites/`, while `moba/assets/character/*.udea.kts` name
`sprites/wizard/Wizard-Idle.png` under `moba/assets/`. Neither the asset root nor the filenames
match what the packed bundle reads.

**It is still the only thing that produces its output**, which is why it is kept rather than
deleted. `moba/src/main/resources/assets/sprites/` is the full 327-sheet corpus, and
`udea-assets-compiler`'s `MobaArt` resolves exactly that path.

That used to leave the atlas determinism and pack reproducibility tests running on one machine
and skipping everywhere else. Issue #168 closed it: `SyntheticArt` draws a corpus of the same
shape - 327 one-row sheets, 2269 frames, every frame 100x100 - at test time, `AtlasPackerTest`
and `ReproducibilityTest` run against that on every clone, and `RealArtAtlasPackerTest` /
`RealArtReproducibilityTest` are the same bodies pointed at whatever this script produced. Those
two still skip when the archives are absent, which is now a statement about the real pixels
rather than a hole in the property.

Do not repoint the real-art tests at the art `:moba:udeaStageCharacterArt` puts in place: that
stages 33 sheets for six characters, and the corpus shape is the point of those tests.
"""
import os, re, zipfile, collections

DL = os.path.expanduser(r"~\Downloads")
MOBA = r"C:\Users\shaun\Workspace\udea\moba"
SPRITES = os.path.join(MOBA, "src", "main", "resources", "assets", "sprites")
RAW = os.path.join(MOBA, "raw-assets")

PACKS = [
    ("pack02", "Tiny RPG Character Asset Pack 02 v1.01-Full 20 Characters.zip", "part2"),
    ("pack01", "Tiny RPG Character Asset Pack v1.03b -Full 20 Characters.zip", "part1"),
]

ANIM = re.compile(
    r"[-_](Attack0[123]|Attack|Heal|Idle|Walk0[12]|Walk|Death|Hurt|Block|Beam)"
    r"(_Effect)?(\(.*?\))?\.png$",
    re.I,
)

# The two packs are not uniformly named: some characters have a single "Attack",
# others "Attack01..03"; some have "Walk", others "Walk01"/"Walk02".
NORMALISE = {"attack": "attack01", "walk01": "walk", "walk02": "walk_alt"}


def snake(s):
    s = s.replace("&", "and")
    s = re.sub(r"[^A-Za-z0-9]+", "_", s).strip("_").lower()
    return re.sub(r"_+", "_", s)


def main():
    manifest = []
    for tag, zn, faction in PACKS:
        path = os.path.join(DL, zn)
        if not os.path.exists(path):
            print("MISSING", zn)
            continue
        z = zipfile.ZipFile(path)
        names = [n for n in z.namelist() if not n.endswith("/")]
        chars = collections.defaultdict(dict)
        projectiles = {}
        aseprites = {}

        for n in names:
            parts = n.split("/")
            low = n.lower()
            fname = parts[-1]

            if fname.lower().endswith(".aseprite"):
                aseprites[snake(fname[:-9]) + ".aseprite"] = n
                continue
            if not fname.lower().endswith(".png"):
                continue
            # skip the shadow-baked variants: team tinting wants the plain sprite
            if "with shadows" in low or "shadow" in low:
                continue
            if "projectile" in low or parts[-2].lower() == "projectile":
                projectiles[snake(fname[:-4]) + ".png"] = n
                continue
            # character sheets live at .../Characters(...)/<Char>/<Char>/<Char>_Anim.png
            if len(parts) < 4:
                continue
            char = parts[2]
            m = ANIM.search(fname)
            if not m:
                continue
            anim = m.group(1).lower()
            anim = NORMALISE.get(anim, anim)
            if m.group(2):
                anim += "_effect"
            # prefer the plain take over "(No beam effects)" style variants
            if m.group(3) and anim in chars[char]:
                continue
            chars[char][anim] = n

        for char, anims in sorted(chars.items()):
            cdir = os.path.join(SPRITES, "champions", snake(char))
            os.makedirs(cdir, exist_ok=True)
            for anim, src in sorted(anims.items()):
                with z.open(src) as f, open(os.path.join(cdir, anim + ".png"), "wb") as o:
                    o.write(f.read())
            manifest.append((faction, snake(char), sorted(anims)))

        pdir = os.path.join(SPRITES, "projectiles")
        os.makedirs(pdir, exist_ok=True)
        for out, src in sorted(projectiles.items()):
            if "32x32" in out:
                continue
            with z.open(src) as f, open(os.path.join(pdir, out.replace("_100x100", "")), "wb") as o:
                o.write(f.read())

        adir = os.path.join(RAW, "aseprite", tag)
        os.makedirs(adir, exist_ok=True)
        for out, src in sorted(aseprites.items()):
            with z.open(src) as f, open(os.path.join(adir, out), "wb") as o:
                o.write(f.read())

        print("%s -> %d characters, %d projectiles, %d aseprite"
              % (tag, len(chars), len(projectiles), len(aseprites)))

    print()
    for faction in ("part1", "part2"):
        rows = [m for m in manifest if m[0] == faction]
        print("%s (%d):" % (faction.upper(), len(rows)))
        for _, name, anims in rows:
            atk = sum(1 for a in anims if a.startswith("attack"))
            print("   %-22s %d attacks  %s" % (name, atk, ",".join(anims)))
    total = sum(len(f) for _, _, f in manifest)
    print("\ntotal characters: %d, total frames-sheets: %d" % (len(manifest), total))


main()
