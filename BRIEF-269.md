# BRIEF — issue #269: the new-game template draws

**ad6e9daf** is the last commit that changes code, docs or evidence, and is what everything
below was measured on. Branch `issue-269-template-draws`, with `origin/master` merged at
b1801363 and ed07e820. This brief is committed on top of it; a brief cannot name its own SHA,
so `git log` is the tiebreak and `git diff ad6e9daf..HEAD` is `BRIEF.md` alone.

## The evidence command

```
UDEA_OUTSIDE_GAME_DIR=/srv/ssd1/workspace/udea-review/dev-269c/outside-game \
UDEA_M2_REPO=/srv/ssd1/workspace/udea-review/dev-269c/m2 \
JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem ANDROID_HOME=$HOME/Android/Sdk \
GRADLE_OPTS="-Dorg.gradle.workers.max=4" GALLIUM_DRIVER=llvmpipe \
sh scripts/outside-game-proof.sh
```

One command. It publishes this engine into a Maven repository of its own, copies
`templates/new-game` outside this checkout, builds and tests it against the published artifacts
alone, runs it headless, **opens its window on a virtual X display, leaves it drawing for fifteen
seconds and photographs the screen from outside the game with ffmpeg**, then breaks the copy once
per thing it claims.

### It goes red when the feature is reverted, and it does that to itself

Two of its legs **are** the reverted feature, on a copy, in the same run — which is stronger than
me reverting by hand once, because it happens on every run for ever.

| Leg | What it reverts | What the proof requires | Result |
|---|---|---|---|
| `window-red` | deletes `it += Drawn(GameAssets.models.rover, assets)` from `RoverSystem` | the window still draws its sky, and the photograph holds **no rover** | `rover pixels 0 (need 2000), sky pixels 921424` → refused, naming the missing model |
| `window-dead` | `sed`s the launcher's close to five seconds while the run asks for twenty-two | the fifteen-second soak must refuse it, naming the window that stopped | `the window opened and stopped drawing before 15s` |

`window-dead` is #275's shape exactly: a render loop that ends early while the game exits 0 with
nothing logged. Without it, a fifteen-second wait is a two-frame test in a long coat.

## `sh gradlew build --continue` — the real result

Off `scratchpad/dev-269c/after.marker` and `build.log`:

```
build EXIT=0 2026-09-22T19:27:52+00:00
BUILD SUCCESSFUL in 3m 51s
1122 actionable tasks: 877 executed, 175 from cache, 70 up-to-date
```

No `-x`, no exclusions. It includes `build-logic`'s own tests through `udeaBuildLogicCheck`, which
is where `NewGameGuideSnippetTest` runs.

## The proof: green, and how long the hold actually was

```
=== window: ./gradlew runWindow on a virtual display, drawing for 15s, then photographed
  rover pixels 15668 (need 2000), sky pixels 889405 (need 230400), of 921600
  PASS: after 15s of drawing, the screen holds the sky and the rover
=== PROOF GREEN
a game outside this repository resolved the engine from a repository, built, ran, and
inherited gates that fail when broken.
```

**The hold was about two minutes, not the 35-50 I declared.** `proof QUEUED 19:27:52` and
`proof EXIT=0 20:26:32` is **56 minutes of waiting for the lock plus the hold**, not the hold. The
hold itself is bounded by the first and last files the proof wrote: `checkout-references.txt` at
**20:24:29** (leg 0a, before the publish) and `after-write.log` at **20:26:32** — **2m 03s**. I do
not have the exact `flock` acquisition stamp, so that is a bound read off artefacts rather than a
measurement of the syscall.

**My estimate was wrong by more than an order of magnitude, and the reason is the thing I got
right.** I deliberately queued the full build before the proof so the worktree would be warm, then
estimated as if the publish were cold. It was not: the build four minutes earlier had compiled
everything, so `publishToMavenLocal` had almost nothing to do. The lesson for the next declared
hold is that the estimate has to account for what ran immediately before it.

## The mutation table

Each row is SCORED only if a test ran. Diffs are the literal `git diff` each run captured.

### Row `green` — the baseline — **SCORED, 4 tests, 0 failures**

```
<testsuite name="dev.wildware.udea.build.NewGameGuideSnippetTest" tests="4" skipped="0" failures="0" errors="0" timestamp="2026-09-22T21:03:39.466Z" ...>
```

Run with `--rerun-tasks --no-build-cache` after deleting the results directory; the in-file
timestamp matches the run's wall clock (started 21:03:16), so it is not a restored cache result.

**This row was VOID on its first attempt and the reason is a defect in my own classifier.** It
looked for Gradle's `N tests completed, M failed` line — which Gradle prints **only when something
fails**. A passing run can never print it, so a green baseline was unscorable by construction. The
XML is the artefact that speaks either way, and the row above reads it.

### Row `m1` — the exact defect #269 reported — **SCORED, 4 tests, 1 failed**

```diff
```diff
@@ -315,7 +315,7 @@ stands:
 world.entity {
     it += rover
     it += Transform3D(x = rover.x, y = rover.y)
-    it += Drawn(GameAssets.models.rover, assets)
+    it += Drawn(GameAssets.root.rover, assets)
 }
 ```
 
```

```
m1 SCORED exit=1: 4 tests completed, 1 failed
NewGameGuideSnippetTest > every quoted block in the new-game guides is in the template file it names() FAILED
```

### Row `m2` — the quote marker removed — **SCORED, 4 tests, 1 failed**

```diff
@@ -310,7 +310,6 @@ only then photographs the screen.
 engine's `Drawn` component, holding the model it is drawn with, and a `Transform3D`, where it
 stands:
 
-<!-- quoted from templates/new-game/game/src/main/kotlin/com/example/newgame/sim/RoverSystem.kt -->
 ```kotlin
 world.entity {
     it += rover
```

```
m2 SCORED exit=1: 4 tests completed, 1 failed
NewGameGuideSnippetTest > every block that names a generated accessor is quoted from compiled code() FAILED
```

### And two rows that were VOID, reported as rows rather than dropped

An earlier pair of `m1`/`m2` runs exited non-zero in about five seconds each and **looked exactly
like the two above**. Neither had run a test: the test file did not compile, because a Python
heredoc I used to add a control case turned `\n` inside a Kotlin string literal into real newlines.
`BUILD FAILED in 5s` is the only line either would have contributed to this table. Fixed at
`a8767351`; the classifier now names four ways a row can be VOID — did not compile, `lightrun`
skipped it for memory (exit 75, a skip and not a pass), the five-minute cap killed it (exit 124,
not a red), or it ran and printed no count.
## Summary

`templates/new-game` built a headless simulation and nothing else. The first game written outside
this repository therefore began by copying `moba`'s launcher out of the engine's tree, which is the
one dependency the template exists to remove, and it discovered the asset plugin's lines one failed
build at a time. Its guide showed `GameAssets.models.fox` to a reader whose model sits at an asset
root's top level, where the build generates `GameAssets.root.fox`.

The template now draws. It has an asset root with a model and a screen effect declared in
`.udea.kts` files, each rover carries the engine's `Drawn` and `Transform3D`, `NewGameScene`
declares a camera, a light, a sky and the scanline pass, and `NewGameWindow` opens a window over
them with `./gradlew runWindow`. Every line of it is written against published
`dev.wildware.udea:*` artifacts and published `dev.wildware.udea.*` plugin ids; none is copied from
inside this repository, which `scripts/outside-game-proof.sh` is what proves, because it builds the
copy from a directory outside this checkout against a Maven repository of its own.

**Two developers were cut off on this branch before me** (an account limit, then a model upgrade),
and their work was on disk uncommitted with no transcript. Most of what is above is theirs. What I
changed is below.

### What I decided

**The window is soaked for fifteen seconds before anything is concluded from it** (#275's rule, and
the reason it exists). The leg used to photograph the screen the moment the window reported itself
up, which is a two-frame test. `runWindow` now takes `-Pseconds=N` rather than `-Pframes=N`, prints
a line for each second it is still drawing, and **exits non-zero if the frame loop stopped before
reaching N** - a check that lives in the *template*, for every game copied from it, because the
failure it catches is silent by construction: in #275 the loop ended about eight seconds in and the
game exited 0 with nothing logged. The proof waits for fifteen seconds of drawing, then photographs.
Full reasoning, and the alternative I rejected (a second Offscreen launcher, to cover both render
modes as the rule asks), is commented on the issue.

**A guide's accessor example is not written any more - it is quoted**, from a template file that the
proof compiles, with the file named in an HTML comment on the line above the fence.
`NewGameGuideSnippetTest` (in `build-logic`, so a plain `sh gradlew build` runs it) holds every
quote to its file as a contiguous run of lines, and fails any block that names a `GameAssets.`
accessor and quotes nothing. Prose in a guide can say anything; a quote cannot, because the thing it
quotes has to compile. The alternative - fixing the one wrong line - is what left the line wrong for
a release in the first place.

**Its accessor rule reads `GameAssets.` only where it starts an identifier.** A guide that mentions
the game's own hand-written `NewGameAssets.registry` ends in those same eleven characters and is not
a generated accessor at all; without the lookbehind the rule fails on every guide that names it. The
control test runs both directions - an unquoted `GameAssets.models.rover` must be caught, and
`NewGameAssets.registry` must not be.

**`AGENTS.md`, the tutorial page and the CI job's comment were brought into step**, because each one
described a template that does not draw. The CI comment also said the proof "breaks it twice", which
was already stale before this branch; it names the list now instead of counting it.

### What the ticket left open, and how I read it

- **Extend the template rather than add a second one.** The lead had already settled this; the
  headless run path (`./gradlew run`, the agent instance) is untouched and still `Headless`.
- **One model, from nothing third-party.** `rover.glb` is six boxes written by `make_rover.py`
  beside it, out of Python's standard library alone. It is checked in because a template that needs
  Blender to build before it draws is not a template - and the script is checked in beside it so the
  binary is auditable. **Verified reproducible:** running the script fresh writes the same 6720
  bytes, same SHA-256.
- **No new convention plugin.** The template applies published ids only -
  `dev.wildware.udea.kotlin-library`, `.agent`, `.assets`, `.game-gates` - and depends on
  `dev.wildware.udea:udea-render` directly, because a JVM project draws by depending on it. Nothing
  needed publishing that was not published.

## The images

In `/srv/ssd1/workspace/Udea/build/debug-screenshots/`. The dashboard has been refusing
connections all evening (the lead's too), so these are on disk and named here rather than posted.

| File | What it shows | What it proves |
|---|---|---|
| `issue269-template-window.png` | three orange rovers against a blue gradient sky, scanlines banding the lower half | the copied game, built outside this checkout against published artifacts, opened a window and was still drawing in it fifteen seconds later. This is AC-1 |
| `issue269-template-window-no-model.png` | the same sky and the same scanlines, and nothing else | the `window-red` leg: take `Drawn` off the rovers and the count refuses the picture. It is what stops AC-1's pass being about the window rather than the model |

**I looked at them, and the pixel counts had missed two things**, both now fixed at `ad6e9daf`:

- **There is no ground.** The rovers cross an empty sky. Three documents called it "a field", which
  a reader takes as geometry the template draws. They now say what is actually there and name a
  floor as the obvious first thing to add.
- **`-nocursor` does not work.** The X server draws its root cursor, a black-on-white X, in the
  middle of both photographs. The proof's comment claimed the screen had no pointer on it. It now
  says the cursor is there and why neither count can see it: black and white is neither the rover's
  orange nor the sky's blue.

Neither changes a verdict. Both are the reason the rule is "look at the result" rather than "check
the measurement".

## The issue, criterion by criterion

### AC-1 — "Copying the template gives a game that opens a window with a model in it, without reading any file inside the engine's repository."

| Half of the claim | What proves it | Where |
|---|---|---|
| **copying the template** | the proof copies `templates/new-game` to a directory outside this checkout and builds only that copy | `outside-game/build.log` |
| **without reading any file inside the engine's repository** | the copy is built against published `dev.wildware.udea:*` artifacts in a Maven repository of this run's own, every plugin marker it applies is inside the verified `dev.wildware` namespace, and the new `no-checkout-path` leg searches the copy for a path into this checkout and finds none - with a control that makes the same search find the game's own package first | `publish.log`, `plugin-markers.txt`, `checkout-references.txt` |
| **opens a window** | `runWindow` is run on a virtual X display, and the screen is photographed by **ffmpeg reading the X server**, not by the game capturing itself. A game that opened no window leaves no sky on the screen, and the count says so by name | `window.png`, `window.txt` |
| **with a model in it** | the photograph is counted for the rover's orange paint, which nothing else on that screen is; and the `window-red` leg takes `Drawn` off the rovers and requires the same count to refuse the picture | `window.png`, `window-red.png` |
| **and it is a window a game can live in** | the photograph is taken after fifteen seconds of drawing, and the `window-dead` leg makes the window close itself after five and requires that wait to refuse it | `window-dead.txt` |

The one thing copied out of this checkout is the **Gradle wrapper**, which a real game writes for
itself with `gradle wrapper --gradle-version 8.13`; the proof copies it to stay off the network and
to pin the same Gradle. Its files name no path into the tree, which is what the `no-checkout-path`
leg is measuring.

### AC-2 — "The docs' accessor example compiles against what the compiler actually generates."

Three links, and each one is a real check rather than a reading:

1. **The example is not written, it is quoted.** Every accessor example in `docs/new-game.md`,
   `templates/new-game/README.md` and `docs/wiki/Tutorial-Make-a-Game.md` carries
   `<!-- quoted from templates/new-game/<path> -->` on the line before its fence.
2. **`NewGameGuideSnippetTest` holds each quote to its file**, as a contiguous run of lines with the
   indentation taken off, and fails any block that names a `GameAssets.` accessor and quotes
   nothing. It runs on `sh gradlew build` through `build-logic`'s `udeaBuildLogicCheck`.
3. **The file it quotes is compiled**, by the proof's `build` leg, in the copied game, against the
   published engine - so "what the compiler actually generates" is the thing the guide shows.

And the rule the guide now states was read out of the generator rather than out of another
document: `AssetScope.idPrefix` is *"the script's directory relative to the asset root"*
(`udea-assets-compiler/.../AssetScope.kt:250`) and `AccessorGenerator.groupOf` takes
`id.substringBefore('/')`, defaulting to `root`. So `assets/models/models.udea.kts` declaring
`rover` is id `models/rover` and accessor `GameAssets.models.rover`; the same line in a script at the
top of `assets/` is `root/rover` and `GameAssets.root.rover`. `moba/game/assets/models/fox.udea.kts`
is why `GameAssets.models.fox` was right in `docs/wiki/Models-and-Animation.md` and wrong in
`docs/new-game.md` - the accessor follows the **script's** folder, and the two documents were
describing different scripts.

## Two things I checked rather than assumed

**The model in the repository is reproducible from the script beside it.** `rover.glb` is 6720
bytes of checked-in binary, which is a thing a reviewer should want an account of. Running
`make_rover.py` fresh, in a directory of its own, writes the same bytes:

```
$ python3 make_rover.py
wrote .../rovercheck/rover.glb 6720 bytes
$ cmp rover.glb <the checked-in one>; echo $?
0
$ sha256sum both
cf6a878532aec76911c0ea1802749661469b18e9290659d79d51844e848dfa4b  rover.glb
cf6a878532aec76911c0ea1802749661469b18e9290659d79d51844e848dfa4b  .../templates/new-game/game/assets/models/rover.glb
```

Nothing in it is third-party and nothing is downloaded: every vertex is computed in the script,
out of Python's standard library. That matters here more than it usually would, because the one
other binary art path in this repository is licensed third-party work that is gitignored for
exactly that reason (`docs/art-assets.md`).

**The new `no-checkout-path` search can find something.** It is an absence claim, so the search was
made to return a hit before its silence was believed - and separately, the shipped leg makes the
same search find the game's own package every run:

```
$ grep -rIlF -- "$REPO" <copy>   # as shipped
exit=1

# now plant one file that does name the checkout
$ grep -rIlF -- "$REPO" <copy>   # with the plant
.../grepctl/settings-local.kts
exit=0
```

## The sweep, not the instance

The defect the issue reported is a guide naming `GameAssets.<folder>.<name>` where `<folder>` is
not the folder the declaring `.udea.kts` sits in. I searched every Markdown file in the repository
for `GameAssets.` and resolved each one rather than eyeballing it:

| Document | What it names | Verdict |
|---|---|---|
| `docs/new-game.md` | `GameAssets.models.rover` | the declaring script is `templates/new-game/game/assets/models/models.udea.kts`. Correct, and now quoted from it |
| `templates/new-game/README.md`, `docs/wiki/Tutorial-Make-a-Game.md` | the same | same, quoted |
| `docs/wiki/Models-and-Animation.md` | `GameAssets.models.fox`, `.chassis`, `.module` | the declaring script is `moba/game/assets/models/fox.udea.kts`. **Correct** - these were never the defect, and the issue's "which is right only because moba's file is in a `models/` subfolder" is right about moba and about the wrong half of the path: it is the *script's* folder that decides, not the model file's |
| `docs/wiki/Assets.md` | `GameAssets.character.orcElite` | the retired game's asset tree, `example-assets/character/`. Correct for what it describes |
| `AGENTS.md`, the specs, the archived briefs | `GameAssets.shaders.scanlines`, `.models.chassis` | descriptive prose about `moba`, all matching their declaring scripts |

**Nothing else carried the defect.** The rule `NewGameGuideSnippetTest` enforces is scoped to the
three documents a person copying the template reads, which is stated in the test rather than left
to be inferred; extending it to the moba-facing wiki pages would mean quoting from `moba` and is
not this ticket.

## What I did not exercise

- **Offscreen.** The template has one render launcher and it opens a window. The #275 rule asks for
  every mode a hook can run in; the decision to soak Windowed alone, and what it would cost to add
  the other, is commented on the issue.
- **Android and iOS.** The template is one JVM project by design, and `docs/new-game.md` says what
  splitting it costs. iOS cannot be tested from this box at all, and nothing here was.
- **A second run of the window on the same display.** Each leg gets its own `xvfb-run -a`.
- **A distribution.** `runWindow` hands the renderer the `assets/` directory, so a packaged game
  has to carry it beside the jar. That is written into the guide's "what this does not cover yet"
  rather than solved.

## Regenerated files

**None.** This branch adds no `@Replicated` component and moves no component id, so
`udea-codegen/net-protocol.lock`, `expected-generated-hashes.txt`, `net-components.lock`, the two
moba `.udearep` fixtures and `moba/desktop/src/test/resources/levels/test_level.roster.txt` are all
untouched. The rovers carry the engine's existing `Transform3D` and `Drawn`; declaring a model and a
shader in a game's own asset root changes that game's asset graph and nothing in the engine's id
space. `git diff origin/master..HEAD --name-only` names no file under `udea-codegen/`, no `.lock`
and no `.udearep`.

## The proof ran in one lock hold, on purpose

The box rule of 2026-09-22 is one hold of the shared lock per Gradle invocation. The evidence
command is the named exception, agreed with the lead before it was queued: it is a shipped script
whose steps must see one another's output. Its first leg publishes this engine into a Maven
repository of this run's own, and its later legs resolve the copied game out of that same
repository - so letting another agent's build land between them is the "measured the wrong subject"
failure this project keeps finding, not a scheduling nicety. Everything else on this branch - the
two red mutations of the snippet test, and `sh gradlew build --continue` - took one hold per
invocation.

The Maven repository is `/srv/ssd1/workspace/udea-review/dev-269c/m2`, passed to every Gradle run
as `-Dmaven.repo.local`, so the shared `~/.m2` is neither read nor written and nothing another
agent published can be what this game resolved.

The rule later became a length limit rather than a count, with this run as the named long hold.
Measured, it did not need to be: **2m 03s**, because the full build four minutes earlier had
warmed the tree. The declaration was still right to make — the length was not knowable in advance,
and a hold that turns out short costs nobody anything, while an undeclared long one does.

## GL

**No `udea-*` module changes on this branch**, so there is no engine GL code here to regression-test
and `udeaGlTest` / `udeaAgentGlTest` / `udeaEditorGlTest` cover exactly what they covered on
`origin/master`. They ran as part of `sh gradlew build --continue` and, with no `DISPLAY`, they
**skipped** — which on any other branch would be the silent-skip trap and is worth saying out loud
rather than leaving to be discovered.

What makes that acceptable here is that **the GL this ticket is about was exercised for real**, not
by those tasks but by the proof: three `runWindow` launches on a virtual X display through Mesa's
software rasteriser, each opening a real window with a real Kool context, one of them drawing for
twenty-two seconds, photographed from outside the process by ffmpeg reading the X server. That is a
stronger GL exercise than the skipped tasks would have been, and it is the only one that could have
caught what this branch could break.

## What a reviewer should push back on

- The **green baseline was VOID on its first attempt** and I only noticed because I read the marker
  rather than the exit code. The classifier is fixed; the habit that caught it is the point.
- **The proof's hold estimate was wrong by an order of magnitude.** Reported above rather than
  quietly dropped.
- **`-nocursor` is asked for and does not work.** Left in with the truth written next to it rather
  than removed, because removing the flag would make the next person wonder why there is a cursor.
- The soak is **Windowed only**. The #275 rule asks for every mode the hook can run in; the
  template has one render launcher, and the decision and its cost are commented on the issue.
