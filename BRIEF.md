# Issue #154 — a LICENSE, and settling third-party art redistribution

**ad6eac3**

That is the commit under review: every change is in it. `BRIEF.md` itself lands in the one commit
on top, so `git rev-parse --short HEAD` will show that instead — `git diff ad6eac3 HEAD --stat`
names `BRIEF.md` and nothing else. A brief cannot contain its own hash.

Branch `issue-154-license-and-art`, worktree
`/srv/ssd1/workspace/Udea/.claude/worktrees/agent-ae07475ff2761864b`.

**Branch point `866ba0a`, and `origin/example` has moved since.** #152 merged while this was in
flight, so the integration branch is now `a1d5217` and this branch is 4 commits behind it.
`git merge-base HEAD origin/example` is `866ba0a`, so **`866ba0a` is the ref every diff in this
brief is taken against** — `git diff origin/example` would show #152's work as deletions and say
nothing about this change. Named rather than assumed: `git merge-base --is-ancestor 866ba0a
origin/example` succeeds, `git rev-list --count 866ba0a..origin/example` is 4, and the four are

```
a1d5217 Gate cross-OS replay equality in CI with field-level divergence
671a75a Add BRIEF.md for issue #152
2d4d2c8 Fold the two cell buffers into one, and tighten the public surface
2e168b9 Gate cross-OS replay equality in CI, naming the field that diverged
```

**It merges clean, checked rather than claimed.** `git merge-tree --write-tree HEAD
origin/example` exits 0 and writes tree `ad26fae` with no conflict; and the two changesets touch
disjoint files — #152's 24 files against this branch's 6, intersection empty
(`comm -12` over the two sorted `git diff --name-only` lists). Not rebased, deliberately: rebasing
would move the SHA and invalidate every transcript below for no benefit the lead cannot get by
merging. Two consequences worth stating: mutation **M2** is `git checkout origin/example --
LICENSE`, and `origin/example:LICENSE` is byte-identical to `866ba0a:LICENSE` (`cmp`), so M2 still
reverts exactly what it reverted when it was run; and `git diff --stat` in §6 is against `866ba0a`
for the same reason.

---

## 1. The evidence command

```
JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem python3 scripts/verify-art-staging.py
```

Self-contained: it makes a throwaway `git worktree` at `HEAD` — a genuinely clean tree, carrying
none of the gitignored art — and asserts six things in order, running
`:moba:udeaValidateAssets` in that tree twice. Nothing has to be staged or built first. **20
seconds** on this box (`3.46s user 0.67s system 20% cpu 20.448 total`), because the Gradle build
cache serves the fresh worktree.

```
repository: /srv/ssd1/workspace/Udea/.claude/worktrees/agent-ae07475ff2761864b
verifying commit: ad6eac3 (a fresh checkout of HEAD, not the working tree)
clean tree: /tmp/udea-art-verify-j6bwhzow/clean
documented step, from docs/art-assets.md:
    python3 scripts/stage-moba-art.py

[1/6] negative control: :moba:udeaValidateAssets must FAIL with no staged art
  FAILED as required, 25 x UDEA0032

[2/6] running the documented step in the clean tree
  staged 33 sheets into /tmp/udea-art-verify-j6bwhzow/clean/moba/assets/sprites
  33 new file(s)

[3/6] :moba:udeaValidateAssets must now PASS
  PASSED

[4/6] LICENSE must exclude wherever the step put the art
  LICENSE covers all 33 staged file(s)

[5/6] README.md's licence claim must match LICENSE
  README.md's licence section and LICENSE agree on 'MIT'

[6/6] README.md must not name a different staging script
  README.md names scripts/stage-moba-art.py, consistent with docs/art-assets.md

OK: a fresh clone plus the documented step builds, and the licence covers it.
```

**What `25 x UDEA0032` does not say.** It is not "25 files are missing". `moba/assets/character/*.udea.kts`
declare **33** `spritePath`s, which is exactly the 33 sheets step 2 stages; the diagnostic sink
ranks and caps at 25 (`AGENTS.md`'s Diagnostics contract row; `DiagnosticSink`'s
"Rank and cap"). The check counts occurrences rather
than asserting a number, so the cap moving would not turn it red for the wrong reason.

### It goes red when the feature is reverted

Six mutations, one per assertion, **all applied against the same `ad6eac3`** in a single pass. Every
diff below is `git show` output from the very commit the failure beside it was produced against —
not a description of one, and not re-typed. Each was applied, committed, run, and reverted with
`git reset --hard`.

**Each row is self-contained**: apply the diff shown, run the evidence command, revert. Nothing
depends on a scratch file. The mutation commits are reflog-only in this worktree, so they are not
offered as the source either — the diffs above are.

> **Provenance, and it needed re-establishing.** The first version of this table was spliced from
> `…/scratchpad/mut/M*.log`, and those files had been silently overwritten by another agent —
> the harness's "session-specific" scratchpad is shared per *project* on this box, so `mut/M1.log`
> was the same file for two of us. It is spelled out in §7 because it nearly put another branch's
> `RecipeTest` failures into this brief. Every transcript here was re-run afterwards into
> `/tmp/udea-issue154-agent-ae07475ff2761864b/`, and audited: six of six mutation logs name this
> worktree and none names another's.

#### M1 — fails at `[2/6]`

`docs/art-assets.md` documents `scripts/extract-art.py`. README moved with it so this reaches step 2 rather than stopping at step 6.

````diff
1892c15 MUTATION M1

diff --git a/README.md b/README.md
index 6c23c99..110a8d0 100644
--- a/README.md
+++ b/README.md
@@ -61,7 +61,7 @@ The **art and audio are not**. Third-party sprite art from a paid asset pack is
 taken. If you fork this repository, bring your own art.
 
 `moba`'s copy of that art is **not** committed, so a fresh clone cannot build `:moba` until you
-run `python3 scripts/stage-moba-art.py`. That step, and why the pixels are gitignored rather
+run `python3 scripts/extract-art.py`. That step, and why the pixels are gitignored rather
 than committed, are in [`docs/art-assets.md`](docs/art-assets.md).
 
 ## Contact
diff --git a/docs/art-assets.md b/docs/art-assets.md
index afa45e9..56fcbe0 100644
--- a/docs/art-assets.md
+++ b/docs/art-assets.md
@@ -18,7 +18,7 @@ One step, after a fresh clone and before the first build:
 
 <!-- verify-art-staging: the documented step begins -->
 ```
-python3 scripts/stage-moba-art.py
+python3 scripts/extract-art.py
 ```
 <!-- verify-art-staging: the documented step ends -->
 
````

```
FAILED: the documented step succeeded but created no files. docs/art-assets.md names a command that does not stage the art.
```

#### M2 — fails at `[4/6]`

`git checkout origin/example -- LICENSE` — a literal revert of the licence half of this branch.

````diff
5ed94b7 MUTATION M2

diff --git a/LICENSE b/LICENSE
index e654e01..f91aa16 100644
--- a/LICENSE
+++ b/LICENSE
@@ -39,24 +39,9 @@ Specifically excluded, and NOT redistributable under this licence:
     but is not limited to, the wizard/, priest/, skeleton/ and orc_elite/
     directories, which come from the PAID pack rather than the free demo.
 
-  * Any content under moba/assets/sprites/,
-    moba/src/main/resources/assets/sprites/ or moba/raw-assets/, with one
-    exception named below. .gitignore excludes these paths and the pack's pixels
-    are not committed, so a clone of this repository does not carry them;
-    docs/art-assets.md carries the manifest instead. moba/assets/sprites/ is
-    where scripts/stage-moba-art.py puts the pack's frames, so a developer's
-    working tree holds excluded art at that path even though a clone does not.
-
-    The one exception is moba/assets/sprites/champion_idle.png. It is committed,
-    it IS covered by the MIT licence above, and it is not third-party art: it is
-    six frames of a placeholder figure computed from arithmetic by
-    scripts/make-placeholder-strip.py, whose bytes are a function of that script
-    and nothing else.
-
-    moba/assets/sprites/arrow/arrow.png is committed but is NOT an exception. It
-    is byte-identical to example/src/main/resources/assets/sprites/arrow/arrow.png
-    and comes from the same free Soldier & Orc demo — usable under that demo's own
-    terms, and still not this project's to sublicense.
+  * Any content under moba/src/main/resources/assets/sprites/ or
+    moba/raw-assets/. These paths are excluded by .gitignore and the pixels are
+    not committed; docs/art-assets.md carries the manifest instead.
 
   * Audio under example/src/main/resources/assets/sounds/. The provenance of
     these files is not recorded anywhere in the repository, so no claim is made
````

```
FAILED: LICENSE names no directory covering 33 file(s) the documented step created, e.g. moba/assets/sprites/orc/Orc-Attack01.png, moba/assets/sprites/orc/Orc-Death.png, moba/assets/sprites/orc/Orc-Hurt.png. Third-party art landed at a path the licence exclusion does not mention.
```

#### M3 — fails at `[5/6]`

`README.md`'s licence section says Apache-2.0.

````diff
13e8a9d MUTATION M3

diff --git a/README.md b/README.md
index 6c23c99..873771d 100644
--- a/README.md
+++ b/README.md
@@ -53,7 +53,7 @@ Contributions are welcome! Please follow these steps:
 
 ## License
 
-The **code** is MIT. See [`LICENSE`](LICENSE).
+The **code** is Apache-2.0. See [`LICENSE`](LICENSE).
 
 The **art and audio are not**. Third-party sprite art from a paid asset pack is committed under
 `example/src/main/resources/assets/sprites/`; `LICENSE` names it and excludes it explicitly, and
````

```
FAILED: README.md's licence section does not name 'MIT', which is what LICENSE's first line says this project is: 'MIT License'.
```

#### M4 — fails at `[1/6]`

The 33 staged sheets force-committed, so the art is no longer absent from a clone. This is the negative control, and it is the one nobody watches fail.

````diff
bbdc79d MUTATION M4

 moba/assets/sprites/orc/Orc-Attack01.png             | Bin 0 -> 2333 bytes
 moba/assets/sprites/orc/Orc-Death.png                | Bin 0 -> 1757 bytes
 moba/assets/sprites/orc/Orc-Hurt.png                 | Bin 0 -> 2086 bytes
 moba/assets/sprites/orc/Orc-Idle.png                 | Bin 0 -> 1410 bytes
 moba/assets/sprites/orc/Orc-Walk.png                 | Bin 0 -> 1870 bytes
 moba/assets/sprites/orc_elite/orc_elite_attack01.png | Bin 0 -> 3209 bytes
 moba/assets/sprites/orc_elite/orc_elite_attack02.png | Bin 0 -> 4380 bytes
 moba/assets/sprites/orc_elite/orc_elite_death.png    | Bin 0 -> 2486 bytes
 moba/assets/sprites/orc_elite/orc_elite_hurt.png     | Bin 0 -> 3130 bytes
 moba/assets/sprites/orc_elite/orc_elite_idle.png     | Bin 0 -> 2073 bytes
 moba/assets/sprites/orc_elite/orc_elite_walk.png     | Bin 0 -> 2494 bytes
 moba/assets/sprites/priest/Priest-Attack.png         | Bin 0 -> 2094 bytes
 moba/assets/sprites/priest/Priest-Death.png          | Bin 0 -> 1541 bytes
 moba/assets/sprites/priest/Priest-Heal.png           | Bin 0 -> 1663 bytes
 moba/assets/sprites/priest/Priest-Hurt.png           | Bin 0 -> 2073 bytes
 moba/assets/sprites/priest/Priest-Idle.png           | Bin 0 -> 1606 bytes
 moba/assets/sprites/priest/Priest-Walk.png           | Bin 0 -> 1976 bytes
 moba/assets/sprites/skeleton/Skeleton-Attack01.png   | Bin 0 -> 2023 bytes
 moba/assets/sprites/skeleton/Skeleton-Death.png      | Bin 0 -> 1396 bytes
 moba/assets/sprites/skeleton/Skeleton-Hurt.png       | Bin 0 -> 1943 bytes
 moba/assets/sprites/skeleton/Skeleton-Idle.png       | Bin 0 -> 1292 bytes
 moba/assets/sprites/skeleton/Skeleton-Walk.png       | Bin 0 -> 1749 bytes
 moba/assets/sprites/soldier/Soldier-Attack01.png     | Bin 0 -> 1934 bytes
 moba/assets/sprites/soldier/Soldier-Attack03.png     | Bin 0 -> 2488 bytes
 moba/assets/sprites/soldier/Soldier-Death.png        | Bin 0 -> 1526 bytes
 moba/assets/sprites/soldier/Soldier-Hurt.png         | Bin 0 -> 1924 bytes
 moba/assets/sprites/soldier/Soldier-Idle.png         | Bin 0 -> 1367 bytes
 moba/assets/sprites/soldier/Soldier-Walk.png         | Bin 0 -> 1806 bytes
 moba/assets/sprites/wizard/Wizard-Attack01.png       | Bin 0 -> 2169 bytes
 moba/assets/sprites/wizard/Wizard-Death.png          | Bin 0 -> 1641 bytes
 moba/assets/sprites/wizard/Wizard-Hurt.png           | Bin 0 -> 2169 bytes
 moba/assets/sprites/wizard/Wizard-Idle.png           | Bin 0 -> 1658 bytes
 moba/assets/sprites/wizard/Wizard-Walk.png           | Bin 0 -> 2054 bytes
 33 files changed, 0 insertions(+), 0 deletions(-)
````

```
FAILED: :moba:udeaValidateAssets PASSED on a fresh checkout with nothing staged. The art is no longer absent from a clone, so this whole check would pass for the wrong reason. Investigate before trusting anything below.
```

#### M5 — fails at `[6/6]`

`README.md` alone names the wrong staging script, while the manifest still names the right one — the two front doors drift apart.

````diff
20a5a45 MUTATION M5

diff --git a/README.md b/README.md
index 6c23c99..110a8d0 100644
--- a/README.md
+++ b/README.md
@@ -61,7 +61,7 @@ The **art and audio are not**. Third-party sprite art from a paid asset pack is
 taken. If you fork this repository, bring your own art.
 
 `moba`'s copy of that art is **not** committed, so a fresh clone cannot build `:moba` until you
-run `python3 scripts/stage-moba-art.py`. That step, and why the pixels are gitignored rather
+run `python3 scripts/extract-art.py`. That step, and why the pixels are gitignored rather
 than committed, are in [`docs/art-assets.md`](docs/art-assets.md).
 
 ## Contact
````

```
FAILED: README.md's licence section tells a reader to run scripts/extract-art.py, which is not what docs/art-assets.md documents (scripts/stage-moba-art.py). Two front doors, two different instructions.
```

#### M6 — fails at `[3/6]`

`scripts/stage-moba-art.py` stops staging one sheet the build needs. The step succeeds and creates files; the build still rejects the tree.

````diff
0ee770b MUTATION M6

diff --git a/scripts/stage-moba-art.py b/scripts/stage-moba-art.py
index c6ea6d6..b2b1ee8 100644
--- a/scripts/stage-moba-art.py
+++ b/scripts/stage-moba-art.py
@@ -28,7 +28,7 @@ DEST = os.path.join(ROOT, "moba", "assets", "sprites")
 # Every sheet `moba/assets/character/*.udea.kts` declares, by the path it declares it at.
 # `wizard` is flattened: the committed tree nests it one level deeper than the other five.
 SHEETS = {
-    "orc": ["Orc-Attack01.png", "Orc-Death.png", "Orc-Hurt.png", "Orc-Idle.png", "Orc-Walk.png"],
+    "orc": ["Orc-Attack01.png", "Orc-Death.png", "Orc-Hurt.png", "Orc-Walk.png"],
     "orc_elite": [
         "orc_elite_attack01.png",
         "orc_elite_attack02.png",
````

```
FAILED: :moba:udeaValidateAssets still fails after the documented step:
```

**Why M1 is the whole ticket in one row.** `scripts/extract-art.py` **exits 0** in a clean tree —
it prints `MISSING` for both archives and returns success. A check that asked only "did the
documented command succeed?" would have gone green on a document naming a script that stages
nothing. The assertion that bites is *"…and created files"*.

**Why M4 matters most.** Step 1 is a negative control, and a control nobody has watched fail is
worth nothing — it would silently turn the whole check into a tautology the day the art got
committed. M4 makes it fail. That commit was local only, was never pushed, and is accounted for
in §6.

### One mutation that did *not* bite, and what it changed

An earlier M2 — deleting `moba/assets/sprites/` from the exclusion bullet but leaving the
explanatory sentence two lines below — left the check **green**. A path named *anywhere* in
`LICENSE` was reading as excluded. Fixed in `bcbc010`: the token scan now runs only over the
indented block after `Specifically excluded, and NOT redistributable under this licence:`, and
stops at the first line in column 0. M2 above is now the stronger form — a literal
`git checkout origin/example -- LICENSE`.

Control for that fix, run before trusting it. Moving the path out of the list and into the closing
paragraph must stop it counting, and does:

```
real LICENSE, exclusion-list tokens: ['example/src/main/resources/assets/sprites/', 'moba/assets/sprites/', 'moba/raw-assets/', 'moba/src/main/resources/assets/sprites/', 'orc_elite/', 'priest/', 'skeleton/', 'wizard/']
covers moba/assets/sprites/orc/Orc-Idle.png: True

CONTROL LICENSE, exclusion-list tokens: ['example/src/main/resources/assets/sprites/', 'moba/raw-assets/', 'moba/src/main/resources/assets/sprites/', 'orc_elite/', 'priest/', 'skeleton/', 'wizard/']
covers moba/assets/sprites/orc/Orc-Idle.png: False
```

A second false pass was caught the same way, before it ever ran (`f7d4388`):
`moba/assets/sprites/` appears in `LICENSE` in order to *exempt* `champion_idle.png`, and without
an end-of-path lookahead that exemption would have satisfied the exclusion rule it is an exemption
from. Its known negative — the exemption line alone — yields no directory token and covers
nothing.

**What the check still cannot do**, said here rather than left for a reader to assume: it does not
parse English. Its assertion is *"the exclusion list names a directory containing this file"*, not
*"the exclusion list excludes it"*. The docstring says the same.

---

## 2. Summary

The issue asked for three judgement calls and one proof. The calls were made and commented on the
issue ([#issuecomment-5479874268](https://github.com/wildware-uk/Udea/issues/154#issuecomment-5479874268),
[#issuecomment-5479877730](https://github.com/wildware-uk/Udea/issues/154#issuecomment-5479877730));
the proof is §1.

### What was already true on `origin/example`

The issue body quotes a README saying *"This project is licensed under the MIT License. See the
LICENSE file for details."* and reports that the file does not exist. Both have moved on since it
was filed: `LICENSE` landed in `3f962bb`, and `README.md`'s licence section was rewritten in the
same commit. **Acceptance criteria 1 and 2 were therefore already partly satisfied before this
branch**, and saying so is more useful than claiming credit for them. What was *not* satisfied is
in §5.

### The three decisions

**1. MIT, matching the README.** Nothing to change: `LICENSE` already reads `MIT License`,
`Copyright (c) 2025-2026 Shaun Wild`. Step 5 of the evidence command now *asserts* the two agree
rather than leaving it to a reader to notice when they stop.

**2. The committed `example/` art stays. No deletion from `HEAD`, no history rewrite.** This
reverses the recommendation `docs/art-assets.md` carried, and the reason is a fact that arrived
after the recommendation was written:

```
$ git log --oneline -- scripts/stage-moba-art.py
531bec1 The game is playable again: 27 units fight with abilities, animation and art
```

The Option 2 recommendation was written in `3f962bb`, which does not contain that script.
`scripts/stage-moba-art.py` copies `moba`'s art *out of* the four paid directories Option 2
deletes — `wizard`, `priest`, `skeleton` and `orc_elite` are four of its six characters and the
only copy in the tree. So Option 2 today red-builds the repository, and its second half
(re-sourcing the art) is out of scope by the issue's own words. Beyond that: leaving the files is
reversible and `git filter-repo` is not, and the issue says the call on published history is the
owner's. Recorded in `docs/art-assets.md` under **The decision**, with what to change to disagree
and the order that does not break the build.

**3. The ongoing mechanism is unchanged** — `.gitignore`, plus `scripts/stage-moba-art.py`, plus
the committed manifest. A private submodule, a release asset and Git LFS were considered and
rejected; the table of reasons is in `docs/art-assets.md` under **The ongoing mechanism for
`moba`'s art**. All three need access control or a network dependency this repository has no way
to exercise, and a release asset on a public repository is a public download — the art would be no
less redistributed, only less obviously so.

### Two defects found while doing it, neither of which the issue names

**`LICENSE` excluded the wrong `moba` path.** It named
`moba/src/main/resources/assets/sprites/` and `moba/raw-assets/`, but the art actually lands at
`moba/assets/sprites/`, where `scripts/stage-moba-art.py` writes it. Every developer's working
tree held paid-pack art at a path the exclusion did not mention. Now named — and step 4 of the
evidence command derives the destination from a real staging run rather than from a list written
inside the check, so moving it again without extending `LICENSE` fails.

Two sub-cases went with it. `moba/assets/sprites/champion_idle.png` is carved out because it
genuinely is this project's own — six frames computed from arithmetic by
`scripts/make-placeholder-strip.py`, whose docstring says the bytes are a function of that file
and nothing else. `moba/assets/sprites/arrow/arrow.png` is explicitly **not** carved out: `cmp`
reports it byte-identical to `example/src/main/resources/assets/sprites/arrow/arrow.png`, which
`docs/art-assets.md` classes under the free Soldier & Orc demo, *"still not this repo's to
relicense"*. My first draft claimed both were the project's own; checking the second one against
the manifest is what stopped a false statement going into a licence.

**The issue's own Notes name the wrong script, and so did the manifest.** `scripts/extract-art.py`
cannot give a fresh clone a buildable tree, for three independent reasons, each readable in its
source: it reads the two **paid** archives by exact filename from
`os.path.expanduser(r"~\Downloads")`; its `MOBA` destination is
`r"C:\Users\shaun\Workspace\udea\moba"`; and it writes `snake(char)` names —
`sprites/wizard/idle.png` — under `moba/src/main/resources/assets/sprites/`, while
`moba/assets/character/wizard.udea.kts:56` names `sprites/wizard/Wizard-Idle.png` under
`moba/assets/`. Neither the asset root nor the filename convention matches. Its docstring claimed
it extracted the packs "into the moba module".

### Sweeping the class, and where my first answer was wrong

The class is *a document telling a reader to run a script that will not do what the document
says*. I wrote "nothing else of the class" into a draft of this brief, then ran the grep instead
of trusting it, and there were more:

```
$ grep -rn "extract-art" . --exclude-dir=.git --exclude-dir=build --exclude-dir=.gradle --exclude=BRIEF.md
docs/art-assets.md:36:### `scripts/extract-art.py` is not that step
docs/art-assets.md:38:The manifest used to offer `scripts/extract-art.py` as an equivalent alternative. It is not one,
.gitignore:65:# Extract locally with scripts/extract-art.py; the manifest is committed, the pixels are not.
scripts/verify-art-staging.py:24:   `scripts/extract-art.py` as an equivalent, and that script unpacks two paid ZIPs from a
udea-assets-compiler/src/test/kotlin/dev/wildware/udea/assets/compiler/atlas/AtlasPackerTest.kt:20:        assumeTrue(MobaArt.available, "moba sprite art is absent; run python scripts/extract-art.py")
udea-assets-compiler/src/test/kotlin/dev/wildware/udea/assets/compiler/pack/ReproducibilityTest.kt:101:        assumeTrue(MobaArt.available, "moba sprite art is absent; run python scripts/extract-art.py")
udea-assets-compiler/src/test/kotlin/dev/wildware/udea/assets/compiler/pack/ReproducibilityTest.kt:119:        assumeTrue(MobaArt.available, "moba sprite art is absent; run python scripts/extract-art.py")
udea-assets-compiler/src/test/kotlin/dev/wildware/udea/assets/compiler/atlas/MobaArt.kt:21: * The tree is gitignored (`scripts/extract-art.py` reproduces it), so tests that need it check
```

**The four in `udea-assets-compiler` are correct and were left alone**, which is the more useful
finding. `MobaArt.root` is `TestPaths.repoRoot.resolve("moba/src/main/resources/assets/sprites")`
— the full 327-sheet corpus — and `scripts/extract-art.py` is the only thing that writes it.
`scripts/stage-moba-art.py` stages 33 sheets for six characters at a *different* root and would
not satisfy them; repointing those four messages at it would have been a wrong fix that read as a
right one. That corrected my own `extract-art.py` docstring, which had said "kept for provenance"
and undersold it (`442b9b2`). See §7 for the real thing sitting underneath them.

The rest of the sweep:

| Searched | Found |
|---|---|
| `grep -rln "stage-moba-art"` | `README.md`, `.gitignore`, `LICENSE`, `docs/art-assets.md`, both scripts, and `.claude/{WAVE.md,agents/engineer.md,agents/team-lead.md,skills/dev-team/SKILL.md}` — every one names the working script. The `.claude/` agent instructions were already correct and were left alone |
| `grep -rn "moba/src/main/resources/assets/sprites"` | `.gitignore`, `LICENSE`, `docs/art-assets.md`, both scripts, `MobaArt.kt` — real, still gitignored, and named as an *excluded* or *test-corpus* path, never as a `:moba` build input |
| Paths named in `LICENSE` | machine-checked, step 4, against a real staging run |

### What the issue left open that I ruled on

- *Whether to correct `README.md` instead of adding a `LICENSE`.* Neither: both existed and
  already agreed. Corrected one stale phrase (`the options and the recommendation` → `the options
  and the decision taken`) and added the missing staging step, because the README is the front
  door and it did not say the game cannot build until the art is staged.
- *Whether `scripts/extract-art.py` should be deleted.* Kept. It is the only producer of the
  assets-compiler's test corpus (above), and deleting a file is less reversible than correcting
  its docstring.
- *Whether to touch `.gitignore`.* One comment line. It said the wave copied "thirty-four sheets";
  the real number is 33, which the evidence command prints from a real run. Following the
  standards on counts in comments, the number is **deleted** rather than corrected — the property
  it was carrying does not go stale, and a corrected number would have gone stale again on the
  next character added. No rule changed.
- *Whether to add a second copy of the staging command to `README.md`.* Yes, because that is where
  a fresh cloner looks — and step 6 was added in the same commit so the two copies cannot drift
  (M5).

### Deliberately not in this diff

No `docs/contracts/` change: nothing here needed one. No asset-pipeline change (#88/#89, out of
scope). No change to `moba/`, `udea-gas/`, `udea-replay/`, `udea-gradle/`, `.github/workflows/`
or the two generated protocol files — those belong to #132 and #152 this wave. Nothing in the diff
is Kotlin.

---

## 3. `sh gradlew build`

Run at the reviewed commit `ad6eac3`, as `clean` with the **build cache off**, so every task really
executes rather than being served a previous verdict. Launched only after a waiter confirmed no
*Udea* `gradlew` client had been running for three consecutive 20-second samples.
(`build-tip.log`.)

```
sh gradlew clean build --no-build-cache --console=plain
...
FAILURE: Build completed with 2 failures.
...
BUILD FAILED in 1m 31s
185 actionable tasks: 183 executed, 2 up-to-date
```

**183 tasks executed and exactly two failed. Both are latency budgets, and nothing else failed.**

| Task | Test | In the full build | Alone | Budget |
|---|---|---|---|---|
| `:udea-assets-compiler:udeaDaemonBudget` | warm reload decision | median **1190ms** `[1190, 1391, 967, 753]` | median **166ms** `[196, 148, 166, 139]` | — |
| `:udea-assets-compiler:udeaDaemonBudget` | warm validate | median **733ms** `[88, 860, 667, 733]` | median **110ms** `[9, 125, 110, 104]` | 300ms |
| `:udea-assets-compiler:udeaPackGate` | graph deserialisation | median **22.96ms**, best 13.93ms | median **4.81ms**, best 4.23ms | 15ms |

Re-run alone exactly as the contract asks — `--rerun --no-build-cache` on each, so neither
replayed a cached verdict (`daemonbudget-solo.log`, `packgate-solo.log`):

```
DaemonLatencyBudgetTest > a warm reload of one script decides inside the edit-to-observe budget() STANDARD_OUT
    warm reload decision: median 166ms over 4 samples [196, 148, 166, 139]
DaemonLatencyBudgetTest > a warm validate of one edited script is under 300ms() STANDARD_OUT
    warm validate of one script: median 110ms over 4 samples [9, 125, 110, 104]

BUILD SUCCESSFUL in 6s
```

```
GraphBudgetTest > deserialising a graph larger than the example tree stays inside the budget() STANDARD_OUT
    graph deserialisation: best=4.226689ms median=4.806348ms over 2000 assets (budget 15ms)

BUILD SUCCESSFUL in 7s
```

**That is the box, and `--no-build-cache` is its worst case by construction** — a 183-task
parallel build *is* the load these tests are being asked to keep a deadline under. 166ms alone
against 1190ms in-build is seven-fold, and 166ms matches the contract's documented "median 170ms
over 4 samples" for a solo run almost exactly. `udeaPackGate` is the same species, in a different
task and not named in the contract, so it is worth recording that it fails the same way and
recovers the same way: 4.81ms alone against a 15ms budget it missed at 22.96ms under the build.

Worth one line because it corrects the obvious reading: **it is not the load average.** `uptime`
said 14.60 when the failing build launched and 16.87 when the passing solo run did. What differs
is contention from the build's own concurrent tasks at that instant, not the machine's one-minute
average.

Then `sh gradlew build`, which re-executes everything the failed run left not up-to-date
(`build-tip-followup.log`):

```
BUILD SUCCESSFUL in 9s
198 actionable tasks: 19 executed, 12 from cache, 167 up-to-date
```

**Test results, and one number deliberately not quoted.** Counting every `<testsuite>` in
`**/build/test-results/` inside this worktree afterwards (`count-tests.py`, `test-count.txt`):

```
worktree=/srv/ssd1/workspace/Udea/.claude/worktrees/agent-ae07475ff2761864b
suites=333 tests=2181 failures=0 errors=0 skipped=34
```

**`failures=0 errors=0` is the claim; `tests=2181` is not.** That total counts result files that
happen to be on disk, and which ones are depends on which test tasks executed rather than being
served from cache — the same census after an earlier sequence at `4046061` read
`suites=362 tests=2420`. Neither is "the number of tests in this repository", so neither is
offered as one. `skipped=34` *is* stable across both censuses, and §7 says why it is not
incidental.

### GL

**This ticket does not touch GL.** The diff is a licence, a README, an asset manifest, one
`.gitignore` comment and two Python scripts; nothing in it is on a compile classpath and nothing
in it opens a context, so an `xvfb` run is not evidence about anything this branch changed. It was
run anyway, because it is cheap and because a green `sh gradlew build` is explicitly not evidence
about GL. The task outputs were deleted first and `--no-build-cache` passed, so both tasks really
executed rather than replaying a cached verdict:

```
rm -rf udea-render/build/test-results/udeaGlTest udea-render/build/reports/tests/udeaGlTest \
       udea-agent-host/build/test-results/udeaAgentGlTest udea-agent-host/build/reports/tests/udeaAgentGlTest

xvfb-run -a -s "-screen 0 1280x720x24" \
  env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe \
  sh gradlew udeaGlTest udeaAgentGlTest -Pudea.render.requireGl=true --no-build-cache
```

```
> Task :udea-agent-host:udeaAgentGlTest
> Task :udea-render:udeaGlTest

BUILD SUCCESSFUL in 5s
32 actionable tasks: 2 executed, 30 up-to-date
```

```
udeaGlTest + udeaAgentGlTest under xvfb, -Pudea.render.requireGl=true: tests=26 failures=0 skipped=0
```

The first attempt came back `FROM-CACHE` in 939ms and would have been worthless as evidence; the
deletion above is why the second one is not.

In the default build 25 of those 26 skip. The one that does not is `OffscreenBackendTest >
Headless is refused rather than quietly opening a window()`, which asserts a refusal and needs no
context — that is the whole of the 25-versus-26 difference, established by diffing the two name
lists rather than waved at.

### An earlier, loaded run — where the replay flake showed up

Before any of the above, `sh gradlew build` was run at load ~8 with `melon-merge` mid-scenario and
**four** tasks failed (`build-loaded-firstrun.log`, kept because it is the only record of the
flake): `CharacterMoverBudgetTest` at median 10.611ms against 4.0ms, `DaemonLatencyBudgetTest` at
704ms and 481ms, `Phase2ExitTest` at 1169ms against 1000ms — all load — and `MobaReplayProofTest`,
which is **not** a timing test and is analysed in §7. All four passed on the next run.

Provenance note: that log predates the move out of the shared scratchpad (§7), so it is
corroborated rather than sole-sourced — its contents match what the run printed at the time, it
names this worktree seven times and no other worktree once, and every number quoted above is in it
at lines 426, 505, 517, 527 and 538.

---

## 4. Images

`/srv/ssd1/workspace/Udea/build/debug-screenshots/issue154-fresh-clone-renders.png`

**Rendered from a genuinely fresh checkout**, not from this worktree. A first draft of this
caption said "a fresh checkout" while the pixels had actually come from my own tree with art
already staged — nearly true, and not the claim criterion 5 makes — so it was redone properly:
`git worktree add --detach … HEAD`, then the documented step, then the renderer, all inside that
tree. The transcript (`fresh-shot.log`):

```
=== before the documented step ===
moba/assets/sprites/arrow/arrow.png
moba/assets/sprites/arrow/arrow.udea.kts
moba/assets/sprites/champion_idle.png
=== the documented step ===
staged 33 sheets into /tmp/udea-issue154-agent-ae07475ff2761864b/freshshot/moba/assets/sprites
=== after ===
36
=== render ===
...
[moba.shot] /tmp/udea-issue154-agent-ae07475ff2761864b/freshshot/moba/build/reports/udea/roster.png 1280x720 at tick 20, 6 characters
BUILD SUCCESSFUL in 17s
```

Three files before — the arrow and the placeholder that `.gitignore` excepts, which is exactly
what a clone carries — and 36 after, being those three plus the 33 the step staged.

**What it shows.** Six characters, drawn from the staged sheets on a real GL context: orc,
orc_elite, priest, skeleton, soldier, wizard, left to right. **What it proves:** criterion 5 past
the validator and into pixels. "A tree the `moba` build can consume" is a weaker claim than a tree
it actually draws from, and this is the stronger one. **What it also shows:** four of those six —
orc_elite, priest, skeleton, wizard — are the paid-pack directories `LICENSE` now names and
excludes by name.

**What it does not show, before anyone reads it as a fault:** the fifth figure is lying down. That
is the soldier mid-animation at tick 20 in a scene that cycles each character's clips; it is not
clipping, a broken sheet, or anything this branch changed. Nothing in the frame is cut off at an
edge or overlapping a neighbour.

**There is one image, and that is honest rather than lazy.** The "before" state of this ticket is
a build failure — a page of `UDEA0032` — which is text, and it is spliced in §1 at step 1 of the
evidence command. A second picture of a documentation change would be manufactured.

---

## 5. The issue, criterion by criterion

**☑ 1. A `LICENSE` file exists and the README's claim matches it.**
Both were already true on `origin/example` (`3f962bb`) — see §2. What this branch adds is that it
is now *checked*: `check_readme_matches_licence` reads `LICENSE`'s first line, finds `README.md`'s
own licence section, and requires the name to appear there. Proved green at step `[5/6]`
(`README.md's licence section and LICENSE agree on 'MIT'`), and red by **M3**. That `LICENSE`
exists at all is asserted by `read()` — a checkout without it fails with *"a fresh checkout of
HEAD has no LICENSE"* rather than an `IndexError` (`9281c6f`).

**☑ 2. The licence text explicitly excludes third-party assets and names them.**
`LICENSE`'s exclusion list names `example/src/main/resources/assets/sprites/` (calling out
`wizard/`, `priest/`, `skeleton/`, `orc_elite/` as the paid pack), `moba/assets/sprites/` — **new
on this branch, and the one that mattered** — `moba/src/main/resources/assets/sprites/`,
`moba/raw-assets/`, and `example/src/main/resources/assets/sounds/`. Proved green at step `[4/6]`
(`LICENSE covers all 33 staged file(s)`), derived from the destinations a real staging run
created; proved red by **M2**, which is a literal revert to `origin/example`'s `LICENSE`.

**☑ 3. A decision on the existing `example/` art is recorded with its reasoning, and acted on.**
`docs/art-assets.md` → **The earlier recommendation, and why it no longer holds** → **The
decision**. Option 1, with three reasons in the order they carry weight, what is still open for
the owner, and the exact change to make if the owner disagrees. Commented on the issue at
[#issuecomment-5479874268](https://github.com/wildware-uk/Udea/issues/154#issuecomment-5479874268).
*Acted on* means the bytes were deliberately left where they are and the `Status: undecided` line
is gone; the reversible half of the work — stating the position rather than implying it — is done,
and §7 shows the tree carries nothing new.

**☑ 4. `docs/art-assets.md` states the mechanism for obtaining the art and why it is not in the
repo.** New **Getting the art** section, with the command in a marked block, what it copies and
from where, why the copies stay gitignored, and a subsection saying flatly that
`scripts/extract-art.py` is not that step and why not. The mechanism decision and its three
rejected alternatives are under **The ongoing mechanism for `moba`'s art**; commented at
[#issuecomment-5479877730](https://github.com/wildware-uk/Udea/issues/154#issuecomment-5479877730).
Proved by **M1** (the manifest naming the wrong script goes red) and **M5** (the README drifting
from the manifest goes red).

**☑ 5. A fresh clone plus the documented extraction step produces a tree the `moba` build can
consume — proven by running it.** This is the evidence command, and it is the whole of §1. It
takes a genuinely clean checkout of `HEAD`, proves `:moba:udeaValidateAssets` **fails** first (25
× `UDEA0032`), runs the step the *document* names rather than one hardcoded in the check, and
proves validation then passes. Red under **M4** (the negative control), **M1** (wrong script) and
**M6** (a step that stages but leaves the build broken). Rendered as pixels in §4.

---

## 6. Regenerated files, and the state of the branch

**Nothing was regenerated.** `udea-codegen/net-protocol.lock` and
`udea-codegen/src/test/resources/expected-generated-hashes.txt` are untouched — this branch adds
and removes no replicated component, so no id moved by anything. `:udea-codegen:udeaCheckProtocolLock`
and `:moba:udeaCheckProtocolLock` both ran green in every build above. #132 is regenerating both
this wave; this branch will not conflict with it.

```
$ git diff --stat 866ba0a
 .gitignore                    |   2 +-
 LICENSE                       |  21 ++-
 README.md                     |  13 +-
 docs/art-assets.md            | 136 ++++++++++++++++----
 scripts/extract-art.py        |  21 ++-
 scripts/verify-art-staging.py | 291 ++++++++++++++++++++++++++++++++++++++++++
 6 files changed, 454 insertions(+), 30 deletions(-)
```

**On mutation M4**, which force-committed the 33 gitignored sheets to make the negative control
fail. It was a local commit on this branch, never pushed, and removed with `git reset --hard`. The
blobs it referenced already existed in the object database — the staged files are byte-identical
copies of `example/src/main/resources/assets/sprites/` — so no new paid-pack content entered the
repository at any point. After the reset:

```
$ git ls-files moba/assets/sprites/
moba/assets/sprites/arrow/arrow.png
moba/assets/sprites/arrow/arrow.udea.kts
moba/assets/sprites/champion_idle.png
```

Three tracked files: exactly what `origin/example` has — the `.gitignore` exceptions for the arrow
and the placeholder, and nothing from the pack.

**`gradlew`'s mode bit is untouched.** `chmod +x` was applied and reverted, and `git status` is
clean of it. Every Gradle invocation in this brief is `sh gradlew`.

---

## 7. Things found and deliberately left

**1. `MobaReplayProofTest > a corrupted recording is caught at the tick it was corrupted()` is a
~1-in-9 flake.** It failed in the earlier loaded run (§3) and has not recurred in any run since,
including the definitive one. It is **not** a timing test and it is **not** mine — this diff
contains no Kotlin — and `moba/` is #132's this wave, so it was sent to `dev-132` with the
numbers rather than fixed here. `dev-132` confirmed the analysis and has since **fixed it on
`issue-132-shop-and-items`** (`72aae75`) after the flake fired on its own build: the mutation now
negates the recorded axis instead of writing a constant, sends the one idle case `(0, 0)`
somewhere definite so the rule is total, and `check`s that what it wrote differs from what it
read, so a future rule that can land on the recorded value fails at the mutation site rather than
forty lines later. Soak: 20 runs with `--rerun-tasks`, 20 green.

**Which does not make it fixed for this branch, and the distinction matters.** That fix is on
#132, unmerged. The defect is present on `origin/example` and therefore on the tree a reviewer
diffs this branch against, so `sh gradlew build` here can still hit it — as §3's loaded run did.
If it goes red on a reviewer's machine on that test, it is this, not this branch.

**Provenance, stated because it matters.** The failing run's own `[replay] mutation at t301 ->
bit-exact: …` line is **gone**: `clean` removed the result XML and the next run overwrote it with
a pass. I searched for it before writing that sentence rather than assuming —
`grep -rl "mutation at t301 -> bit-exact" /tmp /srv/ssd1/workspace/Udea` returns only this brief,
its template and the copy of the template, i.e. no surviving program output. So the two lines
below are **prose quoting what I read at the time**, not a transcript, and should be treated as
such. What *is* still on disk is the failure itself, in
`build-loaded-firstrun.log` at lines 538–539:

```
MobaReplayProofTest > a corrupted recording is caught at the tick it was corrupted() FAILED
    org.opentest4j.AssertionFailedError at MobaReplayProofTest.kt:279
```

and the assertion at line 279 is `assertFalse(verification.isBitExact, "a recording whose input
was altered at $corruptedAt replayed to the ORIGINAL hash stream, which means the replay is not
reading the recorded input at all")`, which is reached only when the corrupted recording replayed
identically. The two lines I read from the XML at the time were `[replay] PASS RATE 5/5 over 2000
ticks each` and `[replay] mutation at t301 -> bit-exact: 600 tick(s) from t1 replayed to the
recorded hash stream, every tick`.

**The healthy case, which is a live artefact** — `replay-passing.xml`, the same file after the
follow-up run, showing what the assertion normally sees:

```
[replay] PASS RATE 5/5 over 2000 ticks each
[replay] mutation at t301 -> replay diverged at t301 (300 tick(s) matched first): recorded hash 1712918382653841550, replayed -3308067780182703200
```

**The cause, and the arithmetic of the explanation matches the size of the effect.** The message's
own conclusion — "the replay is not reading the recorded input at all" — is wrong.
`corruptAxisAt` writes a **constant**:

```
MobaReplayProofTest.kt:324:                slots[0].setAxis(MobaControls.MOVE_AXIS.value, -1f, 0f)
```

and the pilot draws each axis independently from three values:

```
MobaReplayProofTest.kt:94:                moveX = rng.nextInt(3) - 1f
MobaReplayProofTest.kt:95:                moveY = rng.nextInt(3) - 1f
```

`(-1, 0)` is one of those nine equiprobable pairs. When the pilot happens to be holding exactly
`(-1, 0)` at t301, the "corruption" writes the value that is already there, the replay is
legitimately bit-exact, and the test fails while the machinery is working perfectly. The pilot is
seeded `Random(System.nanoTime())`, so it is a fresh draw every build.

**1/9 ≈ 11% per build is derived from the source, not measured** — I did not sample it, and say so
rather than presenting a read as a rate. It is consistent with `HANDOFF.md`'s recorded *"the
in-suite mutation at t301 diverged at t301 in 5/5"*: five clean runs has probability (8/9)⁵ ≈ 55%,
so that 5/5 was never evidence against this.

**2. 34 tests skip in a default `sh gradlew build`, in exactly two families.** Enumerated from the
result XML after the definitive run, and both are the shape the standards warn about — a green build that
tested nothing:

- **25 GL tests** (`GlCaptureTest`, `GlCaptureDeterminismTest`, `GlOverlayIsolationTest`,
  `OffscreenBackendTest`, `OffscreenRenderToolsTest`, `OverlayCaptureIsolationTest`), because
  `$DISPLAY` is empty and `-Pudea.render.requireGl` defaults to `false`. Run for real under
  `xvfb` in §3: 26 tests, 0 failures, 0 skipped.
- **9 art-corpus tests** — `AtlasPackerTest` ×7 and `ReproducibilityTest` ×2 — because
  `MobaArt.available` is false. `moba/src/main/resources/assets/sprites` does not exist in any
  checkout here (`ls`: `No such file or directory`), and only `scripts/extract-art.py` can create
  it, which needs the two paid archives. **So the atlas determinism and pack reproducibility tests
  run on the owner's machine and skip everywhere else** — including CI. This is pre-existing and
  already named in `MobaArt`'s own KDoc (*"That is a real hole and it is named here rather than
  hidden"*), and it is out of scope for #154. It is the most valuable thing this ticket turned up
  that this ticket is not allowed to fix, and it deserves an issue: `scripts/stage-moba-art.py`
  cannot substitute, because the corpus size *is* the point of those tests.

**3. The scratchpad the harness calls "session-specific" is shared between every agent on this
repository, and a collision in it is silent.** Not a Udea defect, but it is the reason every
transcript above was re-run, and it is the most dangerous thing this ticket touched.

`/tmp/claude-1000/-srv-ssd1-workspace-Udea/<uuid>/scratchpad/` is the same directory for #154,
#132 and #152. I wrote a six-mutation pass to `mut/M1.log` … `mut/M6.log`; #132's mutation runner
writes the same names. When I went to splice `M1.log` it began `Reusing configuration cache.` and
ended with a `:moba:test` failure — `RecipeTest`, `ShopProofTest` — whose report path was
`…/agent-a5b3c68bd564f1fda/`, which is #132's worktree, not mine.

**Nothing errored.** The file existed, was the right size, was recently modified, and was about a
different branch. What caught it was that my runner had printed `exit 1` and
`repository: …/agent-ae07475ff2761864b` for each mutation, and the saved bytes disagreed with what
the run had said — the check ran correctly and the artefact was replaced afterwards. Reading only
the first line of the file would still have looked right.

An ownership audit of that directory, matching each file's contents against the three worktree
paths, found files from all three agents interleaved — and, worse, that `mut/M*.diff` and
`mut/M*.failing` contain no absolute path at all, so ownership cannot be established from their
contents in either direction. Warned #132 and #152; #152 confirmed two files of its own in there
and re-verified both against their destinations rather than inspecting them, which is the right
test — an absolute path inside a file proves who wrote it *at some point*, not who wrote it last.

**Rule for whoever writes the wave handoff:** a generic filename in that directory is a silent
cross-branch overwrite. Key the path to the worktree.

**4. `common/build.gradle.kts` and `gradle-plugin/build.gradle.kts` publish POMs declaring
Apache-2.0** while `README.md` and `LICENSE` say MIT. `docs/art-assets.md` already records this
and the reasoning for leaving it: both modules are deleted in Phase 6, so letting them go with the
modules is the cheapest correct fix. A `mavenLocal` publish made before then carries the wrong
licence. Not touched.

**5. The provenance of `example/src/main/resources/assets/sounds/` is recorded nowhere.**
`LICENSE` excludes it until somebody establishes it, which is the right conservative default.
Establishing it is not something an agent can do.

**6. `docs/decisions/phase-log.md` still has no entries**, through seven phases; `HANDOFF.md`
records that. This decision is recorded in `docs/art-assets.md` because that is where the issue
puts it, not in the phase log.
