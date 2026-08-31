# BRIEF-168 — the atlas determinism tests run on every clone

**SHA `561cdda`**

That is the last commit that changes code, and every number below was measured at it. The branch
tip is one commit further on and carries only this file — a brief cannot state the SHA of the
commit that contains it. `git diff 561cdda HEAD` touches `BRIEF-168.md` and nothing else.

Branch `issue-168-atlas-determinism-corpus`, off `origin/example` (`1f6cddd`).
Worktree `/srv/ssd1/workspace/Udea/.claude/worktrees/agent-a8e84931037574687`.

---

## 1. The evidence command

One command. It runs the property **and** distinguishes "ran and passed" from "did not run",
which for this ticket is the whole point: on `origin/example` these tests skip, and Gradle reports
a skip as `BUILD SUCCESSFUL`.

```bash
JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem sh gradlew :udea-assets-compiler:udeaPackGate --rerun --console=plain \
  && R=udea-assets-compiler/build/test-results/udeaPackGate \
  && grep -qE 'tests="[1-9][0-9]*" skipped="0" failures="0" errors="0"' "$R/TEST-dev.wildware.udea.assets.compiler.atlas.AtlasPackerTest.xml" \
  && grep -qE 'tests="[1-9][0-9]*" skipped="0" failures="0" errors="0"' "$R/TEST-dev.wildware.udea.assets.compiler.pack.ReproducibilityTest.xml" \
  && echo "ATLAS DETERMINISM: both suites ran on the synthetic corpus, none skipped, none failed"
```

The regex is one assertion doing three jobs: `tests="[1-9]…"` fails a report with **zero** cases
in it (a renamed class, a filter that stopped matching), `skipped="0"` fails a skip, and
`failures="0" errors="0"` fails a broken property. A missing file makes `grep` exit 2, so the
`&&` chain fails rather than passing vacuously.

### It goes green here

Spliced from `scratchpad/dev-168/FINAL-evidence-green.log`, run at the SHA at the top of this
file:

```
> Task :udea-assets-compiler:udeaPackGate

GraphBudgetTest > deserialising a graph larger than the example tree stays inside the budget() STANDARD_OUT
    graph deserialisation: best=4.957737ms median=6.851635ms over 2000 assets (budget 15ms)

BUILD SUCCESSFUL in 15s
14 actionable tasks: 1 executed, 1 from cache, 12 up-to-date
Configuration cache entry reused.
ATLAS DETERMINISM: both suites ran on the synthetic corpus, none skipped, none failed
```

(`echo "EXIT=$?"` after it printed `EXIT=0`.)

### It goes red on `origin/example` — and Gradle does not

The same command, same box, run from a detached checkout of `origin/example` (`1f6cddd`). Spliced
from `scratchpad/dev-168/evidence-red-origin-example.log`, last lines, contiguous:

```
GraphBudgetTest > deserialising a graph larger than the example tree stays inside the budget() STANDARD_OUT
    graph deserialisation: best=4.295170ms median=5.230020ms over 2000 assets (budget 15ms)

[Incubating] Problems report is available at: file:///srv/ssd1/workspace/Udea/.claude/worktrees/agent-a8e84931037574687/build/reports/problems/problems-report.html

Deprecated Gradle features were used in this build, making it incompatible with Gradle 9.0.

You can use '--warning-mode all' to show the individual deprecation warnings and determine if they come from your own scripts or plugins.

For more on this, please refer to https://docs.gradle.org/8.13/userguide/command_line_interface.html#sec:command_line_warnings in the Gradle documentation.

BUILD SUCCESSFUL in 9s
23 actionable tasks: 1 executed, 1 from cache, 21 up-to-date
Configuration cache entry stored.
```

and the shell reported:

```
EXIT=1
```

The reports that run produced:

```
name="dev.wildware.udea.assets.compiler.pack.ReproducibilityTest" tests="4" skipped="2" failures="0" errors="0"
name="dev.wildware.udea.assets.compiler.pack.GraphBudgetTest" tests="1" skipped="0" failures="0" errors="0"
name="dev.wildware.udea.assets.compiler.atlas.AtlasPackerTest" tests="7" skipped="7" failures="0" errors="0"
```

**Seven tests, seven skipped, `BUILD SUCCESSFUL`, and the command exits 1.** Gradle was happy; the
tests had not run. That gap is issue #168 in one screen, and it is why the evidence command is not
just `sh gradlew udeaPackGate`.

There is a third way the command goes red — break the property itself — and §3 is that one,
executed.

---

## 2. What I did, and what I decided

`AtlasPackerTest` (×7) and `ReproducibilityTest` (×2) opened with
`assumeTrue(MobaArt.available, …)` against a 327-sheet corpus that only two paid Tiny RPG
archives produce. On every machine but the owner's — CI included, this box included — nine tests
aborted their assumptions and reported green having packed nothing.

**`SyntheticArt`** now draws a corpus of the same *shape* at test time: 327 one-row sheets, 2269
frames, every frame 100×100, 40 characters plus a projectile set, out of no third-party pixels.
It is written once under `udea-assets-compiler/build/tmp/synthetic-art/<fingerprint>/` (about
1.5 MB) and reused; the fingerprint carries a `PIXELS_VERSION` so a change to the drawing cannot
silently reuse a stale tree. Generation is atomic — staging directory, marker file, rename — so an
interrupted run cannot leave a half-corpus that looks complete.

Nothing in it touches a clock, a random number generator or a locale (`padStart`, not
`String.format`, whose `%d` is locale-sensitive). A non-deterministic corpus for a determinism
test would be worse than none.

### Why the shape and not a smaller stand-in

`AtlasPacker` orders frames by `(height desc, width desc, id asc)`. With every frame the same
size the first two keys separate **nothing**, so the id tie-break alone decides all 2269
placements. Take the tie-break away and the remaining sort is stable, so the frames land in
arrival order — `Files.walk`'s, and therefore the filesystem's. That is the defect class #89 chose
the real corpus to catch, and it is why decision 3 from the lead (same shape, not smaller) is
right: a synthetic corpus of ten sheets would have recreated the very defect this ticket closes.

### The structure

| Class | Corpus | Skips? |
|---|---|---|
| `AtlasPackerContract` (abstract) | — | the 7 test bodies, written once |
| `AtlasPackerTest` | `SyntheticArt` | **never** — no assumption on its path at all |
| `RealArtAtlasPackerTest` | `MobaArt` | when the paid art is absent |
| `CorpusReproducibilityContract` (abstract) | — | the 2 corpus test bodies + the two-checkout helper |
| `ReproducibilityTest` | `SyntheticArt` | **never**; keeps its own 2 bundle tests |
| `RealArtReproducibilityTest` | `MobaArt` | when the paid art is absent |
| `SmallFixtureContrastTest` | its own 3-sheet fixture | never — the control |

The `requireCorpus()` hook is the mechanism: it is empty on the base class, so the synthetic
subclasses have no code path that *can* skip, and only the two `RealArt*` classes override it with
`assumeTrue`.

### Decisions, and what I rejected

Each is commented on the issue with the same text.

1. **The CI step goes in the `build` job, not `gl-tests`**
   ([#168 comment](https://github.com/wildware-uk/Udea/issues/168#issuecomment-5481913612)).
   `gl-tests` never runs `udeaPackGate`, so a step there would assert on files that do not exist —
   the vacuous gate this ticket exists to close. The `build` job already runs `./gradlew build`,
   which runs `udeaPackGate` via `check`. The lead approved this after I raised it. Reversal is
   cheap: move the step verbatim and add the task to `gl-tests`' gradle invocation.

2. **The skip count stays at 34, and the nine skips move rather than vanish**
   ([#168 comment](https://github.com/wildware-uk/Udea/issues/168#issuecomment-5481919948)).
   Rejected: deleting the real-art tests (the lead ruled it out, rightly — the synthetic corpus is
   drawn by this repository's own PNG encoder and cannot stand in for decoding somebody else's
   PNGs); and making the corpus a `@ParameterizedTest` argument filtered to what is available,
   which would have taken the count to 25 but made the absence **invisible** — a run with no art
   would report identically to one with it.

3. **`ArtPresenceTest` does not exist** — a genuine surprise, filed as its own comment
   ([#168 comment](https://github.com/wildware-uk/Udea/issues/168#issuecomment-5481916169)). Both
   the issue and `MobaArt`'s KDoc said it covers the "directory exists but is empty" case. The only
   mention of the name anywhere in the repository was the KDoc sentence claiming it exists. That
   case is now genuinely covered, by `the corpus is the shape issue 89 chose` asserting 327 / 2269
   / one frame size against whichever corpus it is handed — including the real one, so on the
   owner's machine an empty art directory now fails rather than passing.

### Things I did not do

- **`docs/contracts/` is untouched.** Nothing here needed a contract to change.
  (`git diff --name-only 1f6cddd HEAD -- docs/contracts/` is empty.)
- **`udea-codegen/net-protocol.lock` and `expected-generated-hashes.txt` are untouched** — no
  replicated component was added or removed. dev-167 owns those this wave.
  (`git diff --name-only 1f6cddd HEAD -- udea-codegen/` is empty.)
- **No production code changed.** `git show HEAD --stat -- udea-assets-compiler/src/main` is
  empty. The only non-test changes are `ci.yml`, two documents and the module's `build.gradle.kts`.
- **`BRIEF.md` at the repo root** (the #154 developer's brief) still describes the old skipping
  state. I left it alone deliberately: it is a record of what was true at its own SHA, and
  rewriting a shipped brief to match today would make it a worse record, not a better one.

---

## 3. Proof the tests can fail — the mutation, and its control

Acceptance criterion 3 asks for the tie-break reverted to arrival order. Deleting
`.thenBy { it.name }` **is** that revert: the remaining sort is stable, so equal-size frames keep
whatever order they arrived in.

### The mutation, as a literal diff from the run

```diff
diff --git a/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/atlas/AtlasPacker.kt b/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/atlas/AtlasPacker.kt
index 7672f82..f08e8ee 100644
--- a/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/atlas/AtlasPacker.kt
+++ b/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/atlas/AtlasPacker.kt
@@ -87,8 +87,7 @@ public class AtlasPacker(
         }
         val ordered = frames.sortedWith(
             compareByDescending<Frame> { it.height }
-                .thenByDescending { it.width }
-                .thenBy { it.name },
+                .thenByDescending { it.width },
         )
 
         val placed = ArrayList<AtlasRegion>(ordered.size)
```

### What went red, and what stayed green

Per-class counts from `udea-assets-compiler/build/test-results/udeaPackGate/*.xml` on that run:

```
name="dev.wildware.udea.assets.compiler.atlas.RealArtAtlasPackerTest" tests="7" skipped="7" failures="0" errors="0"
name="dev.wildware.udea.assets.compiler.pack.GraphBudgetTest" tests="1" skipped="0" failures="0" errors="0"
name="dev.wildware.udea.assets.compiler.pack.RealArtReproducibilityTest" tests="2" skipped="2" failures="0" errors="0"
name="dev.wildware.udea.assets.compiler.atlas.SmallFixtureContrastTest" tests="2" skipped="0" failures="0" errors="0"
name="dev.wildware.udea.assets.compiler.pack.ReproducibilityTest" tests="4" skipped="0" failures="2" errors="0"
name="dev.wildware.udea.assets.compiler.atlas.AtlasPackerTest" tests="7" skipped="0" failures="2" errors="0"
```

and Gradle's own summary line, from `scratchpad/dev-168/FINAL-mutation.log`:

```
AtlasPackerTest > packing the same sheets twice produces the same pages() FAILED
AtlasPackerTest > reversing the input order changes nothing() FAILED
ReproducibilityTest > two packs of the whole art corpus produce identical atlas pages() FAILED
ReproducibilityTest > a bundle carrying atlas pages is byte-identical across two packs() FAILED
23 tests completed, 4 failed, 9 skipped
> Task :udea-assets-compiler:udeaPackGate FAILED
BUILD FAILED in 16s
```

The evidence command from §1, run unchanged against that same mutated tree, printed `EXIT=1`.
That is its third failure mode: the property broken, as opposed to the tests skipping or the
report being empty.

| Mutation | Red | Green (the control) |
|---|---|---|
| `.thenBy { it.name }` deleted (diff above) | `AtlasPackerTest > packing the same sheets twice produces the same pages`, `AtlasPackerTest > reversing the input order changes nothing`, `ReproducibilityTest > two packs of the whole art corpus produce identical atlas pages`, `ReproducibilityTest > a bundle carrying atlas pages is byte-identical across two packs` | `SmallFixtureContrastTest` — both tests, 0 failures |

The failure message, spliced from
`TEST-dev.wildware.udea.assets.compiler.atlas.AtlasPackerTest.xml` on that run:

```
packing the same sheets twice produces the same pages()
    org.opentest4j.AssertionFailedError: atlas page 0 differs between runs. Array sizes differ. Expected size is 22909, actual size is 22905.

reversing the input order changes nothing()
    org.opentest4j.AssertionFailedError: expected: <[AtlasRegion(name=sprites/champions/champion_00/attack01#0000, page=0, x=0, y=0, width=100, height=100), AtlasRegion(name=sprites/champions
```

(Truncated at 190 characters each by the extractor; `&#10;` and `&lt;` unescaped from the XML.)

The same run's atlas pages are `issue168-tiebreak-broken.png` — `cmp` on the forward and reversed
page 0 reports `differ: byte 36, line 3`, where on a good run it reports nothing.

The mutation was then reverted; `git status --short` showed no modified source, and the evidence
command re-run green with `EXIT=0`. Everything in this section was measured at `561cdda`, so a
reviewer applying that diff to the branch tip gets these rows back.

### Why the control matters

`SmallFixtureContrastTest` is three sheets whose frames are **three different sizes** — the fixture
somebody writes when they need "a couple of sheets to pack". `(height desc, width desc)` totally
orders it before the tie-break is ever consulted, so it is order-independent whether or not the
tie-break exists, and it sailed through the mutation that turned the corpus tests red.

**One precision the inherited KDoc did not have, and I am not going to repeat it uncorrected.**
`MobaArt`'s KDoc said "a three-sheet fixture would have passed a determinism test that this corpus
fails." That is true of a fixture of *differently sized* frames. Three sheets of 100×100 frames
would share the corpus's tie structure and would catch this same mutation. What the full-size
corpus buys beyond that is the 2269-way tie, the six-page rollover and the shelf reset — none of
which a 21-frame fixture reaches. The KDoc now says that instead, and
`SmallFixtureContrastTest`'s own KDoc says explicitly what it does *not* claim.

---

## 4. The CI step, and the three known negatives I ran against it

`.github/workflows/ci.yml`, `build` job, immediately after `Build and verify the module graph`.
It reads the suite counts rather than grepping for `<skipped`, because a grep passes a report with
**no test cases in it at all** — the same "green having checked nothing" one level up.

The block below is `run:`'s body with `results=` taken from `$1` so it can be pointed at saved
reports. `diff` of the two, from the run:

```
$ head -n -2 ci-extracted.txt | tail -n +3 > a.txt && tail -n +5 ci-step.sh > b.txt && diff a.txt b.txt
0a1
> results=$1
```

— identical apart from that one line.

### Positive: this branch's results

```
$ bash ci-step.sh udea-assets-compiler/build/test-results/udeaPackGate; echo "exit=$?"
TEST-dev.wildware.udea.assets.compiler.atlas.AtlasPackerTest.xml: 7 ran, 0 skipped
TEST-dev.wildware.udea.assets.compiler.pack.ReproducibilityTest.xml: 4 ran, 0 skipped
exit=0
```

### Negative 1 — `origin/example`'s saved reports (the nine skips)

```
### NEGATIVE 1: origin/example's saved results (the 9 skips)
::error::/tmp/.../TEST-dev.wildware.udea.assets.compiler.atlas.AtlasPackerTest.xml skipped 7 of 7 - the synthetic corpus was not packed
exit=1
```

### Negative 2 — a report with zero test cases

```
### NEGATIVE 2: a report with zero test cases
::error::/tmp/.../neg-empty/TEST-dev.wildware.udea.assets.compiler.atlas.AtlasPackerTest.xml reports 0 tests - the suite was filtered away or renamed
exit=1
```

### Negative 3 — the report absent entirely

```
### NEGATIVE 3: the report absent entirely
::error::/tmp/.../neg-missing/TEST-dev.wildware.udea.assets.compiler.atlas.AtlasPackerTest.xml was not written - udeaPackGate did not run the atlas tests
exit=1
```

Negative 2 is the one the lead asked for and it is the one a `<skipped>` grep would have let
through. (Paths elided to `/tmp/...` for width; the scratch directory is
`/tmp/claude-1000/-srv-ssd1-workspace-Udea/20843ffd-9b18-44a2-bc3a-b290e74d1509/scratchpad/dev-168`.)

`ci.yml` is otherwise unchanged; nothing below `replay-equality:` (~line 1025, dev-169's region)
was touched.

---

## 5. `sh gradlew build`

Command, verbatim, no exclusions:

```bash
JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem sh gradlew build --console=plain
```

**Result at the SHA at the top of this file** (`scratchpad/dev-168/LAST-build-green.log`):

```
BUILD SUCCESSFUL in 947ms
204 actionable tasks: 2 executed, 6 from cache, 196 up-to-date
Configuration cache entry reused.
```

I am not going to let that stand on its own, because **196 of 204 were up-to-date** and a green
build in which almost nothing executed is a weak claim. So the honest full picture, every row
measured on this box today:

| Run | Tasks executed | Result |
|---|---|---|
| First `sh gradlew build` on the branch, after the change (cold: 130 tasks recompiled) | 130 | **FAILED** — `udeaBenchCharacterMover`, `udeaDaemonBudget`, `udeaPhase2Exit` |
| Every test forced (`rm -rf */build/test-results` + `--no-build-cache`) | 27 | **FAILED** — `udeaBenchCharacterMover`, `udeaDaemonBudget` |
| Same, later in the session | 31 | **FAILED** — the two above plus `udeaPhase2Exit` and `GraphBudgetTest` |
| Same again, at the final SHA | 27 | **FAILED** — `udeaBenchCharacterMover`, `udeaDaemonBudget` |
| **The same forced procedure on `origin/example`, same box, minutes apart** | 33 | **FAILED** — all four, i.e. *more* than this branch |
| Each failing task re-run alone, after every one of those | — | **BUILD SUCCESSFUL**, every time, numbers in §7 |
| `sh gradlew build` at the final SHA | 2 | **BUILD SUCCESSFUL**, 34 skipped |

In the last forced run, `udea-assets-compiler`'s own reports were:

```
name="dev.wildware.udea.assets.compiler.atlas.SmallFixtureContrastTest" tests="2" skipped="0" failures="0" errors="0"
name="dev.wildware.udea.assets.compiler.atlas.RealArtAtlasPackerTest" tests="7" skipped="7" failures="0" errors="0"
name="dev.wildware.udea.assets.compiler.pack.GraphBudgetTest" tests="1" skipped="0" failures="0" errors="0"
name="dev.wildware.udea.assets.compiler.atlas.AtlasPackerTest" tests="7" skipped="0" failures="0" errors="0"
name="dev.wildware.udea.assets.compiler.pack.ReproducibilityTest" tests="4" skipped="0" failures="0" errors="0"
name="dev.wildware.udea.assets.compiler.pack.RealArtReproducibilityTest" tests="2" skipped="2" failures="0" errors="0"
```

Every failure in every one of those runs is one of the four wall-clock budget tasks the developer
contract names as failing under load and passing alone, and §7 has the matched control showing
`origin/example` failing more of them than this branch. **No test of mine failed in any run**, and
in the forced run where `GraphBudgetTest` went red beside them, `AtlasPackerTest` and
`ReproducibilityTest` were `skipped="0" failures="0"` in the same report.

The box was shared throughout: the neighbouring `melon-merge` project ran a scenario suite for most
of the session (measured load 7 to 31) and two other Udea developers are on this wave. I could not
obtain a window with the box idle and every test executing at once, and I would rather say that
than present the 3-task green as if it were a cold run.

### GL

This ticket touches no GL. `udea-render` and `udea-agent-host` have no file in the diff, and the
25 GL skips in §6 are byte-identical before and after. I did **not** run the `xvfb` suites, and
that is a statement about scope rather than an omission: nothing here can affect them. (`$DISPLAY`
is empty on this box, which is why those 25 skip; CI's `gl-tests` job supplies a display.)

### The three gates outside `check`

Not run and not affected — no file of theirs is in the diff. `:moba:runUdpProof` is red on
`origin/example` and stays red; `:moba:runNetProof`'s `perfect units DISAGREED` likewise. Neither
is mine.

---

## 6. Skip count, before and after

**34 → 34, and the nine that matter moved.** Both figures measured on this box by the same
procedure — delete every `build/test-results`, `sh gradlew build --no-build-cache --continue`, then
sum each suite's `skipped=` attribute
(`scratchpad/dev-168/count-skips.sh`; outputs saved as `skips-before.txt` / `skips-after.txt`).

**Before — `origin/example` at `1f6cddd`: 2471 tests, 34 skipped**

```
   7  dev.wildware.udea.agent.host.gl.OffscreenRenderToolsTest  [udeaAgentGlTest]
   1  dev.wildware.udea.agent.host.gl.OverlayCaptureIsolationTest  [udeaAgentGlTest]
   7  dev.wildware.udea.assets.compiler.atlas.AtlasPackerTest  [udeaPackGate]
   2  dev.wildware.udea.assets.compiler.pack.ReproducibilityTest  [udeaPackGate]
   4  dev.wildware.udea.render.gl.GlCaptureDeterminismTest  [udeaGlTest]
   5  dev.wildware.udea.render.gl.GlCaptureTest  [udeaGlTest]
   1  dev.wildware.udea.render.gl.GlOverlayIsolationTest  [udeaGlTest]
   7  dev.wildware.udea.render.gl.OffscreenBackendTest  [udeaGlTest]
----
total skipped: 34
```

**After — this branch: 2482 tests, 34 skipped**

```
   7  dev.wildware.udea.agent.host.gl.OffscreenRenderToolsTest  [udeaAgentGlTest]
   1  dev.wildware.udea.agent.host.gl.OverlayCaptureIsolationTest  [udeaAgentGlTest]
   7  dev.wildware.udea.assets.compiler.atlas.RealArtAtlasPackerTest  [udeaPackGate]
   2  dev.wildware.udea.assets.compiler.pack.RealArtReproducibilityTest  [udeaPackGate]
   4  dev.wildware.udea.render.gl.GlCaptureDeterminismTest  [udeaGlTest]
   5  dev.wildware.udea.render.gl.GlCaptureTest  [udeaGlTest]
   1  dev.wildware.udea.render.gl.GlOverlayIsolationTest  [udeaGlTest]
   7  dev.wildware.udea.render.gl.OffscreenBackendTest  [udeaGlTest]
----
total skipped: 34
```

The account, line by line:

| | Before | After |
|---|---|---|
| GL, no `$DISPLAY` on this box (CI's `gl-tests` job supplies one) | 25 | 25 — untouched, no file of theirs is in my diff |
| `AtlasPackerTest` | **7 skipped** | **0** |
| `ReproducibilityTest` | **2 skipped** | **0** |
| `RealArtAtlasPackerTest` | — | 7 skipped |
| `RealArtReproducibilityTest` | — | 2 skipped |
| **total** | **34** | **34** |

So the number does not fall, and I am not going to dress that up: nine tests still skip on a
machine with no paid art. What changed is *which* nine and what their skipping means. Before, the
determinism property went unchecked. Now the property is checked by the synthetic run in the same
task, and the skip says only that the real Tiny RPG pixels were not decoded here. The reasoning and
the two alternatives I rejected are on the issue
([comment](https://github.com/wildware-uk/Udea/issues/168#issuecomment-5481919948)).

**+11 tests** (2471 → 2482): the 7 + 2 real-art bodies, and the 2 in `SmallFixtureContrastTest`.
Note the 2447 figure in the developer contract is a *recorded* result from `8035374`, an older
SHA; 2471 is what `origin/example` measures today.

---

## 7. Budgets — `udeaPackGate` / `GraphBudgetTest` and `udeaDaemonBudget`

The contract's warning was the right one to raise: two of the four wall-clock budget tasks live in
my module and my ticket makes `udeaPackGate` do real work where it previously did none. **It does
not push a budget over.** The numbers, and then the control that settles it.

### Run alone — the condition the contract prescribes (no other Udea `gradlew`)

| Task / test | Budget | Before (`origin/example`) | After (this branch) |
|---|---|---|---|
| `udeaPackGate` wall time | — | 9s | 15–24s |
| `GraphBudgetTest` graph deserialisation, median | 15ms | 5.27ms | 6.85ms *(range across 8 solo runs: 4.78–10.72ms)* |
| `udeaDaemonBudget` warm reload, median | 300ms | not re-measured — untouched by this diff | 141ms, 229ms, 194ms |
| `udeaDaemonBudget` warm validate, median | 300ms | not re-measured — untouched by this diff | 97ms, 154ms, 131ms |
| `udeaBenchCharacterMover`, median | 4.0ms | not re-measured — untouched by this diff | 2.09ms, 2.45ms, 2.15ms |
| `udeaPhase2Exit` agent→world | 1000ms | not re-measured — untouched by this diff | 278ms, and 486ms |

`udeaPackGate` goes from 9s to 15–24s. That is the honest cost, and it is the cost of the tests
actually running: before, all nine aborted in 0.05s. The whole-corpus double pack — decode 327
sheets, blit 2269 frames, deflate 6 pages, twice — is issue #89's criterion and cannot be made
cheaper without shrinking the corpus, which is the one thing this ticket must not do. Generation
itself is paid once and cached on disk (§2).

**No budget was raised.** `git diff 1f6cddd HEAD` contains no change to any budget constant.

### Inside a full `sh gradlew build`, all four fail — on both branches

Three of the four went red inside `build`. Rather than assert "that is the box", I ran the
control: the identical procedure on `origin/example`, minutes apart, same machine — delete every
`build/test-results`, then `sh gradlew build --no-build-cache` so every test executes with
compiles cached.

| | `origin/example` | this branch |
|---|---|---|
| `udea-core:udeaBenchCharacterMover` | **FAILED**, median 27.230ms (budget 4.0ms) | **FAILED**, median 6.115ms |
| `udea-agent-host:udeaPhase2Exit` | **FAILED** | passed |
| `udea-assets-compiler:udeaDaemonBudget` | **FAILED**, reload 821ms / validate 461ms | **FAILED**, reload 884ms / validate 345ms |
| `udea-assets-compiler:udeaPackGate` (`GraphBudgetTest`) | **FAILED**, median **16.010502ms** (budget 15ms) | passed, median **10.724435ms** |
| tasks red | **4** | **2** |

`origin/example` failed **more** budgets than my branch, including the one in my own module. That
is the arithmetic answer to "did the corpus push a budget over": in the same conditions the branch
carrying the corpus was *faster* on `GraphBudgetTest` than the branch without it — and on
`origin/example` the atlas tests skip entirely, so that JVM did no image work at all and still blew
the 15ms budget. Whatever is inflating these numbers is not in this diff.

(A later forced run on this branch did fail `GraphBudgetTest` at median 21.438455ms, with
`AtlasPackerTest` and `ReproducibilityTest` both green in the same report — `tests="7" skipped="0"
failures="0"` and `tests="4" skipped="0" failures="0"`. It flips run to run on both branches. The
neighbouring `melon-merge` project was running a scenario suite throughout; measured load was
between 7 and 22.)

### Every one passes when re-run alone

At the final SHA, load 22, no other Udea build:

```
[CharacterMoverBudgetTest] 200 movers x 60 replays (12000 move calls) median 2.449ms, budget 4.0ms
warm reload decision: median 229ms over 4 samples [395, 229, 198, 203]
warm validate of one script: median 154ms over 4 samples [12, 151, 178, 154]
phase 2 exit: typo'd reference rejected in 21ms (median of [32, 21, 9])
phase 2 exit: agent request -> running world observed changed in 278ms
graph deserialisation: best=7.244765ms median=10.585142ms over 2000 assets (budget 15ms)
```

All four `BUILD SUCCESSFUL`.

---

## 8. Images

All four are in `/srv/ssd1/workspace/Udea/build/debug-screenshots/`. Every one was produced by the
gate itself — `CorpusReproducibilityContract` writes both packs to
`udea-assets-compiler/build/reports/udea/atlas/`, which CI's `build` job already uploads as
`udea-budgets-*` because that path is under `**/build/reports/udea/**`.

| File | What it shows | What it proves |
|---|---|---|
| `issue168-synthetic-atlas-page00.png` | Page 0 of the packed synthetic corpus: 400 distinct 100×100 frames, each with its own border. | The corpus is real art-shaped input the packer decodes and blits, not a stub. Every frame differs, which is what lets `a packed frame holds the pixels of the source frame it names` fail. |
| `issue168-corpus-all-pages.png` | All six atlas pages side by side, the sixth about two-thirds full. | The corpus is full size: 2269 frames spill past five 2048×2048 pages onto a sixth, so page rollover and the shelf reset are exercised — the part a 21-frame fixture never reaches. |
| `issue168-tiebreak-good.png` | Forward pack and reversed pack of page 0, with the tie-break intact. | They are the same picture. `cmp` on the two files reports no difference; the gate asserts it by SHA-256. |
| `issue168-tiebreak-broken.png` | The same two packs with `.thenBy { it.name }` deleted. | They are visibly different pictures — the same 400 frames in a different arrangement. This is the defect in a form you can see, and it is what four tests now catch. |

Look at the last two together: that pair is the entire property. `cmp` on the broken pair reports
`differ: byte 36, line 3`; on the good pair it reports nothing.

---

## 9. The acceptance criteria, one by one

### ☑ `AtlasPackerTest` and `ReproducibilityTest` run — not skip — on a checkout with no paid art

This box has no paid art anywhere: neither the worktree nor the main repository has
`moba/src/main/resources/assets/sprites`, so `find … -name "*.png" | wc -l` over it returns `0`.
`MobaArt.available` is false here, and `RealArtAtlasPackerTest`'s seven skips in every report above
are the proof of that rather than an inference.

Under exactly that condition, from `udea-assets-compiler/build/test-results/udeaPackGate/`:

```
name="dev.wildware.udea.assets.compiler.atlas.AtlasPackerTest" tests="7" skipped="0" failures="0" errors="0"
name="dev.wildware.udea.assets.compiler.pack.ReproducibilityTest" tests="4" skipped="0" failures="0" errors="0"
```

`ReproducibilityTest` is 4 rather than 2 because the two inherited corpus tests now run alongside
its own two bundle tests.

### ☑ A CI assertion that these specific tests did not skip

`.github/workflows/ci.yml`, `build` job: `Assert the atlas determinism tests ran and none skipped`.
Section 4 has the positive run and **three** executed known-negatives — the `origin/example`
reports, a zero-case report, and a missing file — each with its `::error::` line and `exit=1`, plus
the `diff` proving the script I ran is the workflow's block with one line changed.

It reads the counts rather than grepping for `<skipped`, on the lead's finding: a grep passes a
report with no test cases in it.

### ☑ The determinism property still fails when broken

Section 3: the literal `git diff` of the mutation, the per-class counts from that run
(`AtlasPackerTest` 2 failures, `ReproducibilityTest` 2 failures), the spliced assertion message,
and the control (`SmallFixtureContrastTest`, 2 tests, 0 failures) staying green through it.
`issue168-tiebreak-broken.png` is the same fact as a picture.

On *"a substitute that a three-sheet fixture could have satisfied has not replaced anything"*: the
substitute has the real corpus's shape, asserted every run by `the corpus is the shape issue 89
chose` — 327 sheets, 2269 frames, and **one frame size** across all of them. That last is the load-
bearing one, and §3 says precisely what the contrast does and does not establish rather than
repeating the inherited KDoc claim uncorrected.

### ☑ `MobaArt`'s KDoc updated

Rewritten. It no longer documents the hole as accepted; it says the hole was closed by #168, names
`SyntheticArt` and the two `RealArt*` classes, says what the remaining skip means, and names the CI
step that holds the arrangement in place. It also no longer references `ArtPresenceTest`, which
does not exist (§2, decision 3), and two inherited claims in it are now precise rather than
approximately right (commit `7f3b7a0`).

The same stale claim appears in two other places, and I grepped for the class rather than fixing
only the instance:

```
$ grep -rn "assumeTrue\|skip everywhere\|a real hole\|determinism tests" --include="*.md" --include="*.py" ... | grep -v "/build/"
docs/art-assets.md:54: ... and `assumeTrue` themselves away when it is absent. So those tests
scripts/extract-art.py:16: ... and `assumeTrue` themselves away when it is absent. Those tests
BRIEF.md:837: ... run on the owner's machine and skip everywhere else** — including CI.
```

Both `docs/art-assets.md` and `scripts/extract-art.py`'s docstring are updated in this branch. The
third, `BRIEF.md`, is the #154 developer's brief and I left it alone deliberately (§2). **Nothing
else** in the repository carries the claim.

---

## 10. What I did not exercise

- **The real corpus.** `moba/src/main/resources/assets/sprites` does not exist on this box — not
  in this worktree and not in the main repository (`find … -name "*.png" | wc -l` → `0`). So
  `RealArtAtlasPackerTest` and `RealArtReproducibilityTest` have never been *run* by me, only
  compiled and watched to skip for the stated reason. Two consequences the owner should know:
  the shared contract now derives frame size from the images rather than assuming 100 and asserts
  every frame is 100×100, so a real corpus that is **not** uniform would newly fail
  `the corpus is the shape issue 89 chose` on the owner's machine; and `sampleCharacter` for
  `MobaArt` is still `sprites/champions/archer/`, unchanged, failing loudly with "has no sheets
  under" if that ever stops matching.
- **Windows.** The CI step is `shell: bash` and runs on both matrix legs; I ran it only on Linux.
  It uses no Windows-sensitive construct beyond forward-slash paths, which Git Bash handles.
- **A second machine.** "Two different checkout directories" is exercised inside one JVM as
  before; nothing about that changed.
- **The empty case is now a *specific* state I test rather than the default one.** The corpus is
  generated before the first assertion, so no test here starts from an empty world; but a corpus
  directory that exists and is empty fails on `327 sheets` with a real count, which is the case
  the phantom `ArtPresenceTest` was supposed to cover.
- **Concurrent generation** by two test JVMs at once is handled by an atomic rename with a
  `FileAlreadyExistsException` fallback. I reasoned it through and did not stage a race to observe
  it; on this box `test` and `udeaPackGate` run sequentially.
