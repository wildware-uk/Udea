#!/usr/bin/env python3
"""Regenerates the machine-readable block of docs/migration/ledger.md.

Run from the repository root:

    python scripts/gen-migration-ledger.py

The rows are seeded from `git ls-files`, so the file is reproducible: two people running this
on the same commit get byte-identical output. The judgement -- which module takes over which
old file, and when -- lives in RULES below rather than in 140 hand-typed rows, so that a
disposition change is a reviewable one-line edit to a rule instead of a find-and-replace.

Review columns (copiedTo, sourceHash, reviewedBy, reviewedIn, notes) are hand-authored and are
PRESERVED across regeneration, keyed by path. Regenerating never silently discards a review
record; see docs/migration/ledger.md for what those columns mean.
"""

from __future__ import annotations

import os
import subprocess
import sys

LEDGER = "docs/migration/ledger.md"
FENCE_OPEN = "```ledger"
HEADER = "path\tdisposition\tdestination\treplacedIn\tdeletedIn\tcopiedTo\tsourceHash\treviewedBy\treviewedIn\tnotes"

# Old-tree modules still in settings.gradle.kts. level-editor, idea-plugin and compose-ui are
# gone (D6) and are recorded in the prose, not here: a row naming a file that no longer exists
# is exactly what udeaLegacyReport rejects.
LEGACY_MODULES = ("common", "example", "gradle-plugin")

COMMON = "common/src/main/kotlin/dev/wildware/udea/"
EXAMPLE = "example/src/main/kotlin/dev/wildware/udea/example/"
PLUGIN = "gradle-plugin/src/main/kotlin/dev/wildware/udea/"

# (path prefix, disposition, destination, replacedIn, deletedIn, notes).
#
# Matched in order, most specific first. `deletedIn` is usually later than `replacedIn` and
# that is not an oversight: `common` compiles as one unit and `example` consumes it, so a file
# cannot leave before its consumers do. The two exceptions are written down by spec section 6
# itself -- the Phase 3 exit deletes KryoNet and the old Network*System files.
RULES: list[tuple[str, str, str, str, str, str]] = [
    # --- gradle-plugin -------------------------------------------------------------------
    (PLUGIN + "network/", "rewrite", "udea-codegen", "0", "6",
     "String-concatenated codegen (standards section 1); replaced by the KotlinPoet emitter"),
    (PLUGIN + "dsl/", "rewrite", "udea-codegen", "0", "6", "@CreateDsl and UdeaDslProcessor"),
    (PLUGIN + "assets/", "rewrite", "udea-assets-compiler", "2", "6", "build-time asset scan"),
    (PLUGIN + "UdeaPlugin.kt", "rewrite", "udea-gradle", "0", "6",
     "the old plugin leaked gradleApi onto the game runtime (spec section 4)"),

    # --- common: networking --------------------------------------------------------------
    (COMMON + "network/NetworkGenerator.kt", "rewrite", "udea-codegen", "0", "6",
     "String-concatenated codegen; swallows per-symbol exceptions (standards section 1)"),
    (COMMON + "network/", "rewrite", "udea-net", "3", "3",
     "Phase 3 exit deletes KryoNet and the old network stack outright"),
    (COMMON + "ecs/system/NetworkClientSystem.kt", "rewrite", "udea-net", "3", "3",
     "Phase 3 exit names this file; carries the TODO() on a reachable path"),
    (COMMON + "ecs/system/NetworkServerSystem.kt", "rewrite", "udea-net", "3", "3",
     "Phase 3 exit names this file"),

    # --- common: assets ------------------------------------------------------------------
    (COMMON + "assets/dsl/script/", "rewrite", "udea-assets-compiler", "2", "6",
     "D4 keeps .udea.kts and kills the runtime script host"),
    (COMMON + "assets/dsl/", "rewrite", "udea-assets-compiler", "2", "6", "-"),
    (COMMON + "assets/AssetScanner.kt", "rewrite", "udea-assets-compiler", "2", "6", "-"),
    (COMMON + "assets/serializers.kt", "rewrite", "udea-assets", "2", "6", "-"),
    (COMMON + "assets/", "rewrite", "udea-assets", "2", "6",
     "the object Assets global goes with it (standards section 1)"),

    # --- common: GAS ---------------------------------------------------------------------
    (COMMON + "ability/", "rewrite", "udea-gas", "3", "6", "re-denominated in Tick (spec section 5)"),
    (COMMON + "ecs/component/ability/", "rewrite", "udea-gas", "3", "6", "-"),
    (COMMON + "ecs/system/AbilitySystem.kt", "rewrite", "udea-gas", "3", "6", "-"),
    (COMMON + "ecs/system/AttributeSystem.kt", "rewrite", "udea-gas", "3", "6", "-"),

    # --- common: lights, which nothing replaces ------------------------------------------
    (COMMON + "ecs/component/lights/", "delete", "-", "-", "6",
     "box2dlights; no lighting is planned in Phases 0-7"),
    (COMMON + "ecs/system/Box2DLightsSystem.kt", "delete", "-", "-", "6", "box2dlights"),

    # --- common: presentation ------------------------------------------------------------
    (COMMON + "ecs/component/render/", "rewrite", "udea-render", "3", "6", "-"),
    (COMMON + "ecs/component/animation/", "rewrite", "udea-render", "3", "6", "-"),
    (COMMON + "ecs/component/audio/", "rewrite", "udea-render", "3", "6", "-"),
    (COMMON + "ecs/system/AnimationSetSystem.kt", "rewrite", "udea-render", "3", "6", "-"),
    (COMMON + "ecs/system/AnimationSystem.kt", "rewrite", "udea-render", "3", "6", "-"),
    (COMMON + "ecs/system/BackgroundDrawSystem.kt", "rewrite", "udea-render", "3", "6", "-"),
    (COMMON + "ecs/system/CameraTrackSystem.kt", "rewrite", "udea-render", "3", "6", "-"),
    (COMMON + "ecs/system/CharacterAnimationControllerSystem.kt", "rewrite", "udea-render", "3", "6", "-"),
    (COMMON + "ecs/system/ParticleSystemSystem.kt", "rewrite", "udea-render", "3", "6", "-"),
    (COMMON + "ecs/system/SoundSystem.kt", "rewrite", "udea-render", "3", "6", "-"),
    (COMMON + "ecs/system/SpriteBatchSystem.kt", "rewrite", "udea-render", "3", "6",
     "becomes a RenderSystem, not a Fleks system (spec section 3.3)"),
    (COMMON + "ecs/system/DebugDrawSystem.kt", "rewrite", "udea-agent", "1", "6",
     "the agent activity overlay (spec section 3.7)"),

    # --- common: simulation kernel -------------------------------------------------------
    (COMMON + "ecs/component/physics/", "rewrite", "udea-core", "3", "6",
     "Box2D is demoted behind PhysicsWorld; CharacterMover is authoritative (spec section 3.4)"),
    (COMMON + "ecs/system/Box2DSystem.kt", "rewrite", "udea-core", "3", "6", "spec section 3.4"),
    (COMMON + "ecs/component/control/", "rewrite", "udea-core", "3", "6", "-"),
    (COMMON + "ecs/system/CharacterControllerSystem.kt", "rewrite", "udea-core", "3", "6", "-"),
    (COMMON + "ecs/system/ControllerSystem.kt", "rewrite", "udea-core", "3", "6", "-"),
    (COMMON + "ecs/system/CleanupSystem.kt", "rewrite", "udea-core", "0", "6", "-"),
    (COMMON + "ecs/system/TransformSystem.kt", "rewrite", "udea-core", "0", "6", "-"),
    (COMMON + "ecs/component/base/", "rewrite", "udea-core", "0", "6", "-"),
    (COMMON + "ecs/component/ai/", "rewrite", "moba", "5", "6", "bots are Phase 5"),
    (COMMON + "ecs/component/", "rewrite", "udea-codegen", "0", "6",
     "component identity is generated now: ComponentTypeId and the Replicator"),
    (COMMON + "ecs/UdeaSystem.kt", "rewrite", "udea-core", "0", "6", "SimSystem"),

    # --- common: UI ----------------------------------------------------------------------
    (COMMON + "screen/", "rewrite", "moba", "5", "6",
     "Trello #22 loading screen and #27 in-game UI are both scheduled Phase 5"),
    (COMMON + "command/", "delete", "-", "-", "6",
     "the in-game console; the MCP tool surface replaces it (spec section 1, D6)"),

    # --- common: loose top-level files ---------------------------------------------------
    (COMMON + "UdeaGame.kt", "rewrite", "udea-core", "0", "6", "-"),
    (COMMON + "UdeaGameManager.kt", "rewrite", "udea-core", "0", "6",
     "the lateinit gameManager global (standards section 1); GameContext replaces it"),
    (COMMON + "properties.kt", "rewrite", "udea-core", "0", "6", "named in the spec section 4 table"),
    (COMMON + "udeaTypes.kt", "rewrite", "udea-core", "0", "6", "-"),
    (COMMON + "Mouse.kt", "rewrite", "udea-render", "3", "6", "input capture is presentation-side"),
    (COMMON + "input.kt", "rewrite", "udea-render", "3", "6", "input capture is presentation-side"),
    (COMMON + "reflection.kt", "delete", "-", "-", "6",
     "UdeaReflections; no reflection on a per-tick path (standards section 1)"),
    (COMMON + "json.kt", "delete", "-", "-", "6",
     "Jackson; one Replicator serves all five consumers (spec section 3.1)"),
    (COMMON + "contextReceivers.kt", "delete", "-", "-", "6", "the language feature it wraps is gone"),
    (COMMON + "util.kt", "delete", "-", "-", "6", "no Util grab-bag types (standards section 3)"),
    (COMMON + "utils.kt", "delete", "-", "-", "6",
     "linear family scan per inbound packet (standards section 1)"),
    ("common/src/main/kotlin/dev/wildware/builders.kt", "rewrite", "udea-core", "2", "6", "-"),

    # --- example -------------------------------------------------------------------------
    (EXAMPLE + "ability/", "rewrite", "moba", "3", "6", "champion kits are moba content"),
    (EXAMPLE + "assets/", "rewrite", "moba", "2", "6", "-"),
    (EXAMPLE + "character/", "rewrite", "moba", "3", "6", "-"),
    (EXAMPLE + "component/", "rewrite", "moba", "3", "6", "-"),
    (EXAMPLE + "system/", "rewrite", "moba", "3", "6", "-"),
    (EXAMPLE + "util.kt", "delete", "-", "-", "6", "no Util grab-bag types (standards section 3)"),
    (EXAMPLE, "rewrite", "moba", "3", "6", "D7: the example game becomes the 5v5 MOBA"),
]


def tracked_kotlin_files() -> list[str]:
    """Every tracked .kt file in the legacy modules that is still on disk.

    `git ls-files` still lists a file deleted from the working tree but not yet committed,
    which would seed a row for a file that is already gone; the existence check keeps the
    generator's view and udeaLegacyReport's view of the tree identical.
    """
    listed = subprocess.run(
        ["git", "ls-files", *LEGACY_MODULES],
        check=True, capture_output=True, text=True,
    ).stdout.splitlines()
    return sorted(p for p in listed if p.endswith(".kt") and os.path.exists(p))


def classify(path: str) -> tuple[str, str, str, str, str]:
    for prefix, disposition, destination, replaced, deleted, notes in RULES:
        if path.startswith(prefix):
            return disposition, destination, replaced, deleted, notes
    raise SystemExit(
        f"{path} matches no rule in gen-migration-ledger.py. Add one: an unclassified old-tree\n"
        f"file is exactly what the ledger exists to make impossible."
    )


def existing_review_columns(text: str) -> dict[str, list[str]]:
    """The five hand-authored columns of the current ledger, keyed by path."""
    if FENCE_OPEN not in text:
        return {}
    body = text.split(FENCE_OPEN, 1)[1].split("```", 1)[0]
    preserved = {}
    for line in body.splitlines():
        cells = line.split("\t")
        if len(cells) == 10 and cells[0] != "path":
            preserved[cells[0]] = cells[5:]
    return preserved


def main() -> int:
    if not os.path.exists(LEDGER):
        raise SystemExit(f"{LEDGER} does not exist; run this from the repository root")

    text = open(LEDGER, encoding="utf-8").read()
    preserved = existing_review_columns(text)

    rows = [HEADER]
    for path in tracked_kotlin_files():
        disposition, destination, replaced, deleted, notes = classify(path)
        review = preserved.get(path, ["-", "-", "-", "-", "-"])
        # A generated note never overwrites a hand-written one.
        if review[4] not in ("-", ""):
            notes = review[4]
        rows.append("\t".join([path, disposition, destination, replaced, deleted, *review[:4], notes]))

    before, rest = text.split(FENCE_OPEN, 1)
    after = rest.split("```", 1)[1]
    open(LEDGER, "w", encoding="utf-8", newline="\n").write(
        before + FENCE_OPEN + "\n" + "\n".join(rows) + "\n```" + after
    )
    print(f"wrote {len(rows) - 1} rows to {LEDGER}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
