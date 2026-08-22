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
