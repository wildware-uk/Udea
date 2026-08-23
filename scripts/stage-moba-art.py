#!/usr/bin/env python3
"""Put the character art where `:moba` expects it, without committing a second copy of it.

`moba/assets/character/*.udea.kts` name `sprites/<character>/<Sheet>.png`, and those pixels are
third-party licensed art from the **Tiny RPG Character Asset Pack** - see `docs/art-assets.md`.
This repository is public and has no right to sublicense that pack, so `.gitignore` excludes the
whole of `moba/assets/sprites/` bar the one placeholder that predates the rule.

Every file this copies is **already in the repository**, under
`example/src/main/resources/assets/sprites/`. That is a pre-existing exposure the art manifest
documents and recommends fixing; copying out of it costs nothing new, while committing thirty-four
more copies of the same paid-pack frames under a second path would have doubled it.

Run it after a fresh clone, before `gradlew :moba:build`:

    python scripts/stage-moba-art.py

Idempotent: it overwrites what it copies and never deletes anything else.
"""
import os
import shutil
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SOURCE = os.path.join(ROOT, "example", "src", "main", "resources", "assets", "sprites")
DEST = os.path.join(ROOT, "moba", "assets", "sprites")

# Every sheet `moba/assets/character/*.udea.kts` declares, by the path it declares it at.
# `wizard` is flattened: the committed tree nests it one level deeper than the other five.
SHEETS = {
    "orc": ["Orc-Attack01.png", "Orc-Death.png", "Orc-Hurt.png", "Orc-Idle.png", "Orc-Walk.png"],
    "orc_elite": [
        "orc_elite_attack01.png",
        "orc_elite_attack02.png",
        "orc_elite_death.png",
        "orc_elite_hurt.png",
        "orc_elite_idle.png",
        "orc_elite_walk.png",
    ],
    "priest": [
        "Priest-Attack.png",
        "Priest-Death.png",
        "Priest-Heal.png",
        "Priest-Hurt.png",
        "Priest-Idle.png",
        "Priest-Walk.png",
    ],
    "skeleton": [
        "Skeleton-Attack01.png",
        "Skeleton-Death.png",
        "Skeleton-Hurt.png",
        "Skeleton-Idle.png",
        "Skeleton-Walk.png",
    ],
    "soldier": [
        "Soldier-Attack01.png",
        "Soldier-Attack03.png",
        "Soldier-Death.png",
        "Soldier-Hurt.png",
        "Soldier-Idle.png",
        "Soldier-Walk.png",
    ],
    "wizard": [
        "Wizard-Attack01.png",
        "Wizard-Death.png",
        "Wizard-Hurt.png",
        "Wizard-Idle.png",
        "Wizard-Walk.png",
    ],
}


def find(character, sheet):
    """The committed path for one sheet, searching the one level `wizard` is nested by."""
    direct = os.path.join(SOURCE, character, sheet)
    if os.path.isfile(direct):
        return direct
    nested = os.path.join(SOURCE, character, character.capitalize(), sheet)
    if os.path.isfile(nested):
        return nested
    return None


def main():
    if not os.path.isdir(SOURCE):
        print(f"no committed art at {SOURCE}", file=sys.stderr)
        return 1
    copied = 0
    missing = []
    for character, sheets in SHEETS.items():
        out = os.path.join(DEST, character)
        os.makedirs(out, exist_ok=True)
        for sheet in sheets:
            src = find(character, sheet)
            if src is None:
                missing.append(f"{character}/{sheet}")
                continue
            shutil.copyfile(src, os.path.join(out, sheet))
            copied += 1
    print(f"staged {copied} sheets into {DEST}")
    if missing:
        print("MISSING - ':moba:udeaPackBundle' will fail on these:", file=sys.stderr)
        for m in missing:
            print(f"  {m}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
