# Character art manifest

Source: **Tiny RPG Character Asset Pack**, parts 1 and 2 (40 characters).

The pixels are **not committed** - they are third-party licensed art and this repo is public.
`.gitignore` excludes `moba/src/main/resources/assets/sprites/` and `moba/raw-assets/`.
Re-extract locally from the two zips with:

```
python scripts/extract-art.py
```

This manifest IS committed so blueprints, issues and champion designs can name real
characters and animation frame counts without shipping the art.

> **The `moba` half of that is true. The `example` half is not.** Third-party art from the same
> pack is already committed under `example/src/main/resources/assets/sprites/`, and this
> repository is public. See [Committed art in `example`](#committed-art-in-example) at the
> bottom of this file for what is there, the options, and the recommendation.

## Format

Every sheet is a horizontal strip of 100x100 frames, so `columns = width / 100` and
`rows = 1`. That is directly checkable by the build-time validator (#88).
`*_effect` sheets are the separated effect layer for the matching attack - useful for
tinting an ability by team colour without recolouring the character.

## Roster

| character | idle | walk | attacks (frames) | block | beam | heal | death | hurt |
|---|---|---|---|---|---|---|---|---|
| `archer` | 6 | 8 | A01:9, A02:12 | - | - | - | 4 | 4 |
| `armored_axeman` | 6 | 8 | A01:9, A02:9, A03:12 | - | - | - | 4 | 4 |
| `armored_orc` | 6 | 8 | A01:7, A02:8, A03:9 | 4 | - | - | 4 | 4 |
| `armored_skeleton` | 6 | 8 | A01:8, A02:9 | - | - | - | 4 | 4 |
| `black_knight_a` | 6 | 8 | A01:16, A02:15, A03:15 | - | - | - | 4 | 4 |
| `black_knight_b` | 6 | 8 | A01:7, A02:8, A03:9 | 4 | - | - | 4 | 4 |
| `black_knight_c` | 6 | 8 | A01:8, A02:9, A03:13 | - | 7 | - | 10 | 4 |
| `blood_monster_a` | 6 | 8 | A01:8, A02:8 | - | - | - | 4 | 4 |
| `blood_monster_b` | - | - | A01:8, A02:9 | - | - | - | 6 | 4 |
| `demon_a` | 6 | 8 | A01:7, A02:7 | - | - | - | 4 | 4 |
| `demon_b` | 6 | 8 | A01:9, A02:6 | - | - | - | 4 | 4 |
| `demon_c` | 6 | 6 | A01:6, A02:6 | - | - | - | 4 | 4 |
| `demon_d` | 6 | 8 | A01:11, A02:18, A03:12 | - | - | - | 4 | 4 |
| `demon_e` | 6 | 8 | A01:8, A02:13, A03:8 | - | - | - | 4 | 4 |
| `demoness_a` | 6 | 8 | A01:9, A02:15, A03:16 | - | - | - | 4 | 4 |
| `demoness_b` | 6 | 6 | A01:8, A02:8 | - | - | - | 4 | 4 |
| `elite_orc` | 6 | 8 | A01:7, A02:11, A03:9 | - | - | - | 4 | 4 |
| `eyeball_monster` | 6 | 6 | A01:8, A02:8, A03:6 | - | 3 | - | 4 | 4 |
| `flame_golem` | 6 | 12 | A01:9, A02:9, A03:9 | - | - | - | 6 | 4 |
| `ghostfire` | - | - | A01:6, A02:7 | - | 3 | - | 6 | 4 |
| `greatsword_skeleton` | 6 | 9 | A01:9, A02:12, A03:8 | - | - | - | 4 | 4 |
| `hellbat` | - | - | A01:6, A02:7 | - | - | - | 4 | 4 |
| `hellhound` | 6 | 8 | A01:8, A02:8 | - | - | - | 4 | 4 |
| `knight` | 6 | 8 | A01:7, A02:10, A03:11 | 4 | - | - | 4 | 4 |
| `knight_templar` | 6 | 8 | A01:7, A02:8, A03:11 | 4 | - | - | 4 | 4 |
| `lancer` | 6 | 8 | A01:6, A02:9, A03:8 | - | - | - | 4 | 4 |
| `lava_slime` | 6 | 6 | A01:6, A02:9 | - | - | - | 4 | 4 |
| `minotaur` | 6 | 8 | A01:8, A02:7, A03:7 | - | - | - | 4 | 4 |
| `orc` | 6 | 8 | A01:6, A02:6 | - | - | - | 4 | 4 |
| `orc_rider` | 6 | 8 | A01:8, A02:9, A03:11 | 4 | - | - | 4 | 4 |
| `priest` | 6 | 8 | A01:9 | - | - | 6 | 4 | 4 |
| `skeleton` | 6 | 8 | A01:6, A02:7 | 4 | - | - | 4 | 4 |
| `skeleton_archer` | 6 | 8 | A01:9 | - | - | - | 4 | 4 |
| `slime` | 6 | 6 | A01:6, A02:12 | - | - | - | 4 | 4 |
| `soldier` | 6 | 8 | A01:6, A02:6, A03:9 | - | - | - | 4 | 4 |
| `swordsman` | 6 | 8 | A01:7, A02:15 | - | - | - | 4 | 5 |
| `warlock` | 6 | 8 | A01:7, A02:6 | - | - | - | 11 | 4 |
| `werebear` | 6 | 8 | A01:9, A02:13, A03:9 | - | - | - | 4 | 4 |
| `werewolf` | 6 | 8 | A01:9, A02:13 | - | - | - | 4 | 4 |
| `wizard` | 6 | 8 | A01:6, A02:6 | - | - | - | 4 | 4 |

## Picking champions

A MOBA champion wants **three distinct attack animations** (Q/W/E) plus idle, walk, death
and hurt. 19 of the 40 have three attacks and are the natural champion pool:

> `armored_axeman`, `armored_orc`, `black_knight_a`, `black_knight_b`, `black_knight_c`, `demon_d`, `demon_e`, `demoness_a`, `elite_orc`, `eyeball_monster`, `flame_golem`, `greatsword_skeleton`, `knight`, `knight_templar`, `lancer`, `minotaur`, `orc_rider`, `soldier`, `werebear`

Special kit animations, which map to ability archetypes:

- **beam** (channelled / sustained damage): `black_knight_c`, `eyeball_monster`, `ghostfire`
- **heal** (support): `priest`
- **block** (defensive cooldown): `armored_orc`, `black_knight_b`, `knight`, `knight_templar`, `orc_rider`, `skeleton`

The two parts also split thematically - part 1 is knights, orcs, skeletons and casters;
part 2 is demons, black knights and hell creatures - so they can serve as the two teams
if a visual faction split is wanted. That is a gameplay decision, not baked into the layout.

## Projectiles

- `arrow01.png` (1 frames)
- `arrow02.png` (1 frames)
- `arrow03.png` (1 frames)
- `arrow_demon_b_attack01.png` (1 frames)
- `black_knight_c_beam.png` (7 frames)
- `cannonball_black_knight_b_attack03.png` (1 frames)
- `eyeball_monster_beam.png` (3 frames)
- `ghostfire_beam.png` (3 frames)
- `lava_slime_spike.png` (5 frames)
- `priest_attack_effect.png` (5 frames)
- `priest_heal_effect.png` (4 frames)
- `warlock_attack02_effect.png` (9 frames)
- `wizard_attack01_effect.png` (10 frames)
- `wizard_attack02_effect.png` (7 frames)


---

## Committed art in `example`

`example/src/main/resources/assets/sprites/` holds 64 committed image files from the same **Tiny
RPG Character Asset Pack**, 42 of them in the four paid-pack directories. They landed in commit `2ffb932`, long before the `moba` rule above
existed, and the repository is **public**.

### What is actually there

| Directory | Pack | Redistributable? |
|---|---|---|
| `soldier/`, `orc/`, `arrow/` | the free **Soldier & Orc** demo | Under the demo's own terms — still not this repo's to relicense |
| `wizard/`, `priest/`, `skeleton/`, `orc_elite/` | the **paid** pack | **No.** This is the part that matters |

The four paid-pack directories are the exposure. A public MIT repository reads, to anyone who
does not open this file, as an offer of everything in it under MIT — including art the author
has a licence to *use* and no right to *sublicense*.

Two smaller inconsistencies found alongside it, both worth fixing when their module is next
touched:

- `common/build.gradle.kts` and `gradle-plugin/build.gradle.kts` both publish a POM declaring
  **Apache-2.0**, while `README.md` and the new `LICENSE` say MIT. Three claims, one project.
  Those two POMs belong to old-tree modules deleted in Phase 6, so the cheapest correct fix is
  to let them go with the modules rather than edit them now — but a `mavenLocal` publish made
  before then carries the wrong licence.
- The provenance of `example/src/main/resources/assets/sounds/` is recorded nowhere. It may be
  the author's, it may not. Until somebody says which, `LICENSE` excludes it.

### The options

Deciding this is the owner's call, not an agent's, because only the owner knows what the pack
licence actually permits and what the risk appetite is. All four options are real.

| # | Option | Cost | What it does not fix |
|---|---|---|---|
| 1 | **Leave it. Rely on the `LICENSE` exclusion.** | None | The files stay in the published tree and in every clone. A reader who does not open `LICENSE` still sees art under an MIT repo |
| 2 | **Delete the four paid directories from `HEAD`**, keep the free demo art, and replace the affected `.udea.kts` characters with placeholders | Half a day; `example` is deleted in Phase 6 anyway | History still carries them. Anyone can `git checkout` an old commit |
| 3 | **Option 2, plus rewrite history** (`git filter-repo`) and force-push | Breaks every clone and every open PR; rewrites 500+ commits | Nothing, but it is the most expensive option and the tree is public, so copies may already exist |
| 4 | **Make the repository private** until the art is out | Loses the public project | Reversible, but it is a bigger decision than the art |

### Recommendation

**Option 2, now; not option 3.**

- The exposure that matters is what a visitor sees at `HEAD`, and option 2 removes it for the
  cost of a placeholder swap in a module that is being deleted in Phase 6 regardless.
- Option 3 buys very little on top. The repository is already public: history rewriting removes
  the files from *this* copy, not from any clone, fork or cache that already has them, so it
  pays the full cost of breaking every clone for a partial guarantee. **This is explicitly the
  owner's call and nothing here should rewrite published history to force it.**
- Option 1 is defensible only as a deliberate, recorded choice. It is not defensible as the
  thing that happens because nobody decided.
- Option 4 is a bigger lever than the problem needs.

Whichever is chosen, the `LICENSE` exclusion stays: it is what makes the position explicit
rather than implied, and it is needed for the free-demo art in any case.

**Status:** undecided. This is a Phase 0 checkpoint item — `docs/decisions/phase-log.md`.
