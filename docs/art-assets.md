# Character art manifest

Source: **Tiny RPG Character Asset Pack**, parts 1 and 2 (40 characters).

The pixels are **not committed** - they are third-party licensed art and this repo is public.
`.gitignore` excludes `moba/src/main/resources/assets/sprites/`, `moba/assets/sprites/` and
`moba/raw-assets/`.

`moba/assets/sprites/` is the path `moba/assets/character/*.udea.kts` actually names and the one
`:moba:udeaPackBundle` reads, so **a fresh clone cannot build `:moba` until it is populated**.
`:moba:udeaValidateAssets` fails on an unpopulated clone with a page of `UDEA0032`, each naming
one `spritePath` that is not under the asset root. That is this step not having been run; it is
not a fault in whatever you were changing.

## Getting the art

One step, after a fresh clone and before the first build:

<!-- verify-art-staging: the documented step begins -->
```
python3 scripts/stage-moba-art.py
```
<!-- verify-art-staging: the documented step ends -->

It copies 33 sheets for six characters out of `example/src/main/resources/assets/sprites/`,
where this repository already holds them, into the six directories `moba/assets/character/*.udea.kts`
name. It is idempotent: it overwrites what it copies and deletes nothing. The copies stay
gitignored, which is the point of it — the alternative was committing a second set of the same
paid-pack frames under a second path, doubling an exposure this file already documents.

`scripts/verify-art-staging.py` proves that claim end to end against a fresh checkout, and it
reads the command out of the marked block above rather than carrying its own copy, so this
document naming the wrong script fails a check instead of costing somebody an afternoon. Those
markers are load-bearing: if you move the command, move them with it.

### `scripts/extract-art.py` is not that step

The manifest used to offer `scripts/extract-art.py` as an equivalent alternative. It is not one,
and it will not produce a tree `:moba` can build. It is the one-off the owner ran on a Windows
machine to unpack the two purchased `.zip` files into the tree in the first place, and three
separate things make it useless as a fresh-clone step:

- It reads the two paid archives by exact filename from a hardcoded Windows `~\Downloads`, so it
  needs files nobody who clones this repository has.
- Its `MOBA` destination is an absolute path on the author's own machine.
- It writes `sprites/<char>/idle.png`, lowercased and un-hyphenated, under
  `moba/src/main/resources/assets/sprites/` — the old asset root. `moba/assets/character/*.udea.kts`
  name `sprites/wizard/Wizard-Idle.png` under `moba/assets/`. Neither the root nor the filenames
  match.

It is kept because it records how the committed frames were derived from the packs, which is
provenance worth keeping. It is not a build step, and its own docstring now says so.

This manifest IS committed so blueprints, issues and champion designs can name real
characters and animation frame counts without shipping the art.

> **The `moba` half of that is true. The `example` half is not.** Third-party art from the same
> pack is already committed under `example/src/main/resources/assets/sprites/`, and this
> repository is public. See [Committed art in `example`](#committed-art-in-example) at the
> bottom of this file for what is there, the options, and **the decision** — which is to leave
> it, because `scripts/stage-moba-art.py` now stages `moba`'s art out of it and removing it
> would red-build the repository.

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

### The earlier recommendation, and why it no longer holds

This file recommended **option 2** — delete the four paid directories from `HEAD`, keep the free
demo art, swap the affected `.udea.kts` characters for placeholders. That recommendation was
written in commit `3f962bb`, and `git show 3f962bb --name-only` does not list
`scripts/stage-moba-art.py`, because that script did not exist yet. It arrived two commits later
in `531bec1`, and it copies out of exactly the directories option 2 deletes:

```
$ git log --oneline -- scripts/stage-moba-art.py
531bec1 The game is playable again: 27 units fight with abilities, animation and art
```

Four of the six characters `scripts/stage-moba-art.py` stages — `wizard`, `priest`, `skeleton`,
`orc_elite` — are the four paid directories, and they are the only copy in the repository.
Deleting them makes `:moba:udeaValidateAssets` fail on every fresh clone with nothing to replace
it. Option 2 stopped being "a placeholder swap in a module being deleted anyway" the moment the
new game started reading from the old module's art, and nobody noticed because the two changes
landed in the same wave.

### The decision

**Option 1: leave the committed art where it is. Do not delete it and do not rewrite history.**
Taken 2026-08-31 under issue #154. Recorded here so it is not re-litigated.

Three reasons, in the order they carry weight:

1. **Deleting it red-builds the repository today.** The dependency above is not hypothetical and
   there is no replacement mechanism to switch to. Option 2 is now a two-part change — remove the
   art *and* re-source `moba`'s six characters — and the second part is a different ticket about
   choosing art, which #154 puts out of scope.
2. **Where a reversible option exists, take the reversible one.** Leaving the files is
   reversible: options 2, 3 and 4 all remain open, and the exposure is unchanged from what it has
   been since `2ffb932`. Option 3 is not reversible in any sense — `git filter-repo` rewrites
   500+ published commits, breaks every clone and every open PR, and the repository is already
   public, so it removes the files from *this* copy and from no fork or cache that already has
   them. It pays the whole cost for a partial guarantee.
3. **It is the owner's call, and the issue says so.** Issue #154's own Notes: *"Flagged rather
   than actioned because the call on already-published history is the owner's, not an agent's."*
   An agent choosing option 2 or 3 unprompted would be making that call in the direction that
   cannot be undone.

What this decision does *not* claim: it does not claim the exposure is acceptable, only that
removing it is a decision above an agent's pay grade and that the reversible half of the work —
saying plainly what is not covered — is the half that could be done now. `LICENSE` names the
four paid directories explicitly and excludes them, `README.md` points at it, and the position is
now stated rather than implied, which is what option 1 requires to be defensible at all.

**Still open, for the owner.** Whether to act on options 2, 3 or 4 later. Nothing here forecloses
any of them. **If you disagree with this decision**, the change is: pick option 2, re-source
`moba`'s six characters from art the project may redistribute, update
`scripts/stage-moba-art.py`'s `SHEETS` to the new sources, and delete
`example/src/main/resources/assets/sprites/{wizard,priest,skeleton,orc_elite}/`. In that order —
the last step alone breaks the build. `scripts/verify-art-staging.py` will tell you if it does.

### The ongoing mechanism for `moba`'s art

**Unchanged, and deliberately so:** `.gitignore` excludes `moba/assets/sprites/`,
`scripts/stage-moba-art.py` populates it from art the repository already holds, and this file is
the committed manifest. Three alternatives were considered for the same decision and rejected:

| Alternative | Why not |
|---|---|
| A private submodule | Needs a second repository and per-developer credentials. A public clone gets a broken submodule pointer rather than a clear instruction, and CI would need a deploy key this project has no way to test |
| A release asset, fetched at build time | Adds a network dependency to a build that has none, and a release asset on a public repository is a public download — the art would be no less redistributed, only less obviously so |
| Git LFS with restricted access | Same access-control problem as the submodule, plus LFS on a public repo serves the objects to anyone who clones. It solves size, which is not the problem here |

All three trade a mechanism that demonstrably works, and that
`scripts/verify-art-staging.py` proves on a fresh checkout, for infrastructure this repository
has no way to exercise. If the art is ever re-sourced to something redistributable, the right
answer is to commit it and delete the staging script, not to build a private channel.
