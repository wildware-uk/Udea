# BRIEF: `master`'s Windows reds — one defect, fixed; one runner flake, measured

5527545

The code commit. The branch tip is one commit later and adds this document and nothing else.
Branch `windows-green`, on `origin/master` at `00a2093`.

**Which runs were measured where**, because `master` moved twenty commits while this was in flight
and I rebased onto it:

- **At `5527545`, the final code:** the evidence command green (`R1b`) and red with the fix
  neutralised (`M1b`), and the full build (`build2`, then `build3`) - section 4.
- **At `c3103cd`, the same code on the previous base `67d9f99`:** the TDD red (`R0`), both
  mutations (`M1`, `M2`) and `M2-vrt`. The seven code and test files they exercise are
  byte-identical between the two commits: `git diff --quiet c3103cd 5527545` on exactly those seven
  answers `exit=0`, and the same command on a file `master` changed answers `exit=1`, so it can see
  a difference. Only unrelated base files differ.

**The deliverable sentence is not "master is green on Windows."** It is: *master's Windows reds are
one defect, fixed, and one runner flake, measured.* The ticket was written as two defects of one
class. Reading the logs, only the first is a defect at all; the second is a Tooling API connection
failure that happened once in twenty-nine Windows `build` jobs, and there is nothing in the test to
fix. Section 6 is that measurement, recorded so nobody pays for the diagnosis twice.

---

## 1. The evidence command

```
ANDROID_HOME=$HOME/Android/Sdk JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem \
  sh gradlew -p build-logic test \
    --tests 'dev.wildware.udea.build.GateLocationTest' \
    --tests 'dev.wildware.udea.build.EditorReleaseRulesTest' \
    --tests 'dev.wildware.udea.build.ReleaseRulesTest' \
    --tests 'dev.wildware.udea.build.VerifyEditorAbsentTest'
```

**At the final code, `5527545`, both ways.** Green as committed (`R1b`): `EXIT=0`,
`:compileKotlin` and `:compileTestKotlin` re-ran on the rebased sources, 26 tests, 0 failures,
0 skipped, every suite timestamped `03:37:30`. Red with the fix neutralised (`M1b`, the identity
mutation - `cmp` against `M1.diff` answers `exit=0`, so it is byte-for-byte the same mutation):
`M1b.log` lines 21-36 as one consecutive run:

```
EditorReleaseRulesTest > the report prints a Windows location forward-slashed, whatever platform found it() FAILED
    org.opentest4j.AssertionFailedError at EditorReleaseRulesTest.kt:145

GateLocationTest > a jar entry suffix survives, because that is how a gate names a class inside an archive() FAILED
    org.opentest4j.AssertionFailedError at GateLocationTest.kt:43

GateLocationTest > a UNC location keeps both of its leading separators, turned round() FAILED
    org.opentest4j.AssertionFailedError at GateLocationTest.kt:33

GateLocationTest > a Windows location is printed with forward slashes() FAILED
    org.opentest4j.AssertionFailedError at GateLocationTest.kt:28

ReleaseRulesTest > the report prints a Windows archive path forward-slashed, whatever platform found it() FAILED
    org.opentest4j.AssertionFailedError at ReleaseRulesTest.kt:92

26 tests completed, 5 failed
```

The same five as `M1` at `c3103cd`, and `VerifyEditorAbsentTest` green again.

**It goes red when the fix is reverted - on this Linux box.** A test-only fix could not have
offered that, because reverting a diff that only adds tests cannot go red.

Reverting the production change and keeping the tests (run `R0`, the TDD red, taken before the two
`gateLocation` calls existed) - lines 27-35 of `R0-red.log` as one consecutive run, then line 48:

```
> Task :test

EditorReleaseRulesTest > the report prints a Windows location forward-slashed, whatever platform found it() FAILED
    org.opentest4j.AssertionFailedError at EditorReleaseRulesTest.kt:145

ReleaseRulesTest > the report prints a Windows archive path forward-slashed, whatever platform found it() FAILED
    org.opentest4j.AssertionFailedError at ReleaseRulesTest.kt:92

26 tests completed, 2 failed

[... R0-red.log lines 36-47 elided: Gradle's failure banner, `> Task :test FAILED` through `> Run with --scan` ...]

BUILD FAILED in 1m 16s
```

And it failed **for the Windows reason**, not an incidental one. The assertion message, spliced from
that run's `TEST-dev.wildware.udea.build.EditorReleaseRulesTest.xml`, is the defect CI saw,
reproduced on Linux:

```
org.opentest4j.AssertionFailedError: UDEA-MG-012 :moba: 1 editor class on the release classpath.
    game/Ring is a Gizmo, in D:\a\Udea\Udea\moba\editor
[... message line 2 elided: the remedy text, `udea-editor and every Gizmo are debug-only` ...]
```

and from `TEST-dev.wildware.udea.build.ReleaseRulesTest.xml`:

```
org.opentest4j.AssertionFailedError: UDEA-REL-001 :moba: 1 banned entry in the packaged artifact. Banned prefixes: dev/wildware/udea/agent/, dev/wildware/udea/agenthost/.
    D:\a\Udea\Udea\moba\build\libs\moba.jar -> dev/wildware/udea/agent/AgentTools.class
[... message line 2 elided: the remedy text, `The agent surface is debug-only` ...]
```

That second line is the whole defect in one row: the archive path is backslashed, and the zip-entry
name beside it is not, because an entry name is forward-slashed by the format itself. That is why
entry names were never at risk and origins were.

Green (`R1`), with `gateLocation` wired in - `EXIT=0`, `BUILD SUCCESSFUL in 12s`. Because
`BUILD SUCCESSFUL` on a test task is compatible with zero tests having run, here are the counts from
each suite's XML, and the proof they executed in this run: `:compileKotlin` re-ran on the changed
`main`, `:test` ran (neither up to date nor from cache), and every suite is timestamped `03:18:03`
against a clock of `03:18:26`.

| Suite | tests | failures | skipped |
|---|---|---|---|
| `GateLocationTest` | 4 | 0 | 0 |
| `EditorReleaseRulesTest` | 8 | 0 | 0 |
| `ReleaseRulesTest` | 9 | 0 | 0 |
| `VerifyEditorAbsentTest` | 5 | 0 | 0 |

---

## 2. Summary

### What was wrong

`UDEA-MG-012` (`udeaVerifyEditorAbsent`) fails when a `Gizmo` reaches a release classpath, and its
message names the directory or jar the offending class came out of. That location was
`root.absolutePath` — the platform's own separators. On Windows it reads `...\moba\editor`.

`VerifyEditorAbsentTest` asked the gate to "say where the class was" by looking for the substring
`moba/editor`. True on Linux, false on Windows. **The gate was working; the assertion was a claim
about its author's operating system.**

### Where the accident actually lives

The ticket read this as an assertion encoding its platform. It is really the *message* encoding its
platform, and the proof is sitting in the same package. `ContractFreeze.digestsOf` forward-slashes
the keys it freezes. `DeterminismScan.span` forward-slashes the source location it reports a
finding at. The frozen diagnostics contract asks the same of every `SourceSpan`. That is exactly
why `ContractFreezeCheckTest` can assert `docs/contracts/replicator.md` and stay green on Windows,
while `VerifyEditorAbsentTest` asserting `moba/editor` goes red: one convention, and one gate that
had not been told about it.

### The fix

A new `internal fun gateLocation(path: String): String` in `dev.wildware.udea.build`, called by
`EditorReleaseRules.report` and by `ReleaseRules.report`.

**It takes a `String` rather than a `File`, and that is the sharpest point in this change.**
`File.invariantSeparatorsPath` rewrites only the separator of the platform it is running on, so on
Linux it returns a Windows path unchanged — which would make the helper untestable on the machine
this repository is developed on, and *untestable is how this defect survived in the first place*: a
`File`-based test would have passed while asserting nothing. A `String` can carry either spelling
on either platform, so the new cases feed it `D:\a\Udea\Udea\moba\editor` and it fails **on this
box, today**. A defect that could previously only be observed on a machine we do not have now reds
locally, which is also what gives this branch an evidence command that goes red when reverted.

It is `internal`, not `public`: nothing outside `build-logic` calls it, and section 8 rejects a
`public` declaration nobody outside the module uses. The test source set still sees it — Gradle's
Kotlin plugin makes `test` a friend of `main` — and that is precedent rather than belief:
`CleanBuildComparisonTest` already calls `internal object CleanBuildComparison` the same way. The
file and the name follow the pattern already in that package, where `GateFinding.kt` holds a
top-level `internal fun gateFailureReport`.

### The class, not the instance

`ReleaseRules.report` (`UDEA-REL-001`) names an absolute archive path the same way, and nothing
asserted a separator against it — so it was the same defect with nothing pointing at it. It is
fixed **and tested**, to the same standard: an unasserted change is an unreviewed change, and the
next refactor would undo it silently.

### Decisions taken, and the alternatives rejected

| Decision | Rejected alternative | Why |
|---|---|---|
| Normalise in the gate's **message** | Normalise at the assertion site, or branch the assertion on `File.separator` | A test-only fix is unfalsifiable by revert on Linux — reverting a diff that only adds tests cannot go red. Fixing the message makes the property testable on every platform, and aligns the gate with the convention its siblings already keep. |
| `gateLocation(String)` | `File.invariantSeparatorsPath` | See above: the `File` form is a no-op on the platform we can run, so a test over it asserts nothing here. |
| Normalise at render time, in `report()` | Normalise at `ScannedClass`/`ArchiveEntry` construction | Keeps the scanned data as found; only the display changes. It also puts the decision where unit tests already call it, which is what those objects' own KDoc says they are for. |
| Fix `ReleaseRules` too | Fix only the instance that was red | This project's rule: a finding is a sample, not a census. |
| Leave the two gates' *incidental* messages alone (`could not read X as a jar`, the `scanned:` lines of the report file, `logger.lifecycle` lines) | Normalise every `absolutePath` in `build-logic` | The rule report is the diagnostic; the rest is build plumbing. Normalising something no test covers is an unfalsifiable addition. Section 3 lists every one of them so the boundary is visible rather than implied. |
| Do not patch red 2 | Add a retry, or `@Ignore` it on Windows | Nothing in that test is wrong. A retry wrapper would convert a real signal about runner health into a green — a check that measures nothing, which is the defect this repository is fighting. Skipping it is the purest form of the same thing. |

### Not a contract change

Nothing under `docs/contracts/` is touched, and `docs/contracts.lock` is unchanged. The diagnostics
contract is cited as precedent for the convention, not altered by it.

---

## 3. The sweeps, each with its control

Every claim of absence below is printed beside the same search shape returning non-zero on
something known to be there. Exit statuses were read, not inferred from empty output.

### 3a. Does anything assert a native separator out of a gate message?

**The first version of this sweep was broken, and it reported success.** I ran
`git grep -nE '\\\\\\\\'`, believing it matched every escaped backslash. Inside single quotes the
shell passes those eight characters to git untouched, and as an ERE each `\\` is *one* literal
backslash - so it matched **four in a row**. It returned 8 hits, all Kotlin's `"\\\\"` idiom, and it
could never have found a test asserting `"moba\\editor"`, which is the one thing it existed to rule
out. A check that ran and answered a question nobody asked. It was caught because my own staged
`GateLocationTest` holds a literal `\\build` on line 33 and did not appear in the result.

**The positive control first**, because the corrected search has to be seen finding a backslash
before its silence means anything. A fixed string, so no regex escaping is involved at all:

```
$ git grep -nF '\' -- 'build-logic/src/test/kotlin/dev/wildware/udea/build/GateLocationTest.kt'
```

It hits lines 20, 28, 33 and 43 - line 33 being the UNC case the broken sweep missed, 28 and 43 the
other Windows cases, and 20 the KDoc naming `\`.

**Then the sweep**, over every test source set in the repository, saved as
`scratchpad/win/sweep-3a-all-backslash.txt`: **1098** lines hold a backslash, `exit=0`. Narrowed to
lines that also assert on build output or a message (`in result.output`, `in report`, `in message`,
`in failure`, `assertContains(`, `.output`): **37** lines in 17 files, saved as
`sweep-3a-asserting.txt`. Of those 17 files, only two read the gates this branch changes (section
3b), and these are their five lines, spliced from that file:

```
VerifyEditorAbsentTest.kt:66:        assertTrue("moba/editor" in result.output, "the failure must say where the class was:\n${result.output}")
VerifyEditorAbsentTest.kt:86:        assertTrue("moba.jar" in result.output, "the failure must name the jar:\n${result.output}")
VerifyReleaseTest.kt:44:        assertTrue("moba.jar" in result.output, "the failure must name the jar:\n${result.output}")
VerifyReleaseTest.kt:61:            "no configuration references the agent, so only the artifact rule may fire:\n${result.output}",
VerifyReleaseTest.kt:140:            "an overridden prefix list replaces the default rather than adding to it:\n${result.output}",
```

Every one of the five backslashes is `\n` - a newline inside the assertion's *failure message*,
never in the value asserted. No test asserts a native separator out of either gate's message.

### 3b. Who reads the two gates at all?

`git grep -rln 'udeaVerifyEditorAbsent\|udeaVerifyRelease\|EditorReleaseRules\|ReleaseRules\.report'`
returns 33 files, `exit=0`. The only *message readers* among them are four test classes:
`VerifyEditorAbsentTest`, `EditorReleaseRulesTest`, `VerifyReleaseTest`, `ReleaseRulesTest`.

### 3c. What does every assertion on those four classes' messages actually assert?

Enumerated rather than counted, because a number is not checkable:

- **Rule ids** (`UDEA-MG-012`, `UDEA-REL-001`, `UDEA-REL-002`) and **Gradle project paths**
  (`:moba`, `:moba:desktop`, `:udea-agent-host`) — not filesystem paths, unaffected.
- **Zip-entry and internal class names** (`dev/wildware/moba/PositionPositionGizmo`,
  `game/RangeRing`, `dev/wildware/udea/editor/EditorSession`,
  `dev/wildware/udea/agent/AgentTools.class`, `dev/wildware/udea/leveleditor/Editor.class`) —
  forward-slashed by definition of the class-file format, never touched by `gateLocation`.
- **`"moba.jar"`**, twice (`VerifyEditorAbsentTest`, `VerifyReleaseTest`) — a bare filename with no
  separator in it; unaffected either way.
- **`"moba/editor"`** (`VerifyEditorAbsentTest`) — the one that was red on Windows, now green
  because the message normalises.
- **`"/x/editor/classes"`** (`EditorReleaseRulesTest:116`) and **`"/build/libs/moba.jar"`**
  (`ReleaseRulesTest:69`) — already forward-slashed, passed through unchanged. See section 5: these
  two are a control nobody wrote for this occasion.

Nothing anywhere asserts a native separator or a Windows-shaped absolute path out of either message.

### 3d. The negative that most looked like it would bite, with its positive beside it

Both searches redirect to a file, so each exit status printed is `grep`'s own. (An earlier version of
this block piped the positive through `| head -5`, which made the printed exit status `head`'s -
always 0, and so measuring nothing. The lines it showed were right; the status beside them was not.)

```
$ grep -n 'UDEA-MG-012\|UDEA-REL-001\|editor-absent' scripts/outside-game-proof.sh > sweep-3d-neg.txt
negative-exit=1
$ grep -n 'UDEA-MG-0' scripts/outside-game-proof.sh > sweep-3d-pos.txt
positive-exit=0
```

`sweep-3d-pos.txt`, in full:

```
29:#               udeaVerifyModuleGraph must fail with UDEA-MG-005.
206:// Planted by scripts/outside-game-proof.sh: UDEA-MG-005 must refuse a scripting host on a
213:grep -q "UDEA-MG-005" "$REPORT/graph-red.log" ||
214:    fail "udeaVerifyModuleGraph failed for some other reason than UDEA-MG-005; see $REPORT/graph-red.log"
```

Same file, same grep shape: the zero came from a search that ran.

### 3e. Every `absolutePath` in `build-logic/src/main`, so the boundary of this change is visible

`CharacterArtStaging` (lifecycle log, and a failure whose asserted content is the caller's own plan
keys, not a path), `ForkedJvmTmpdir` (a system property), `UdeaContractTasks` and `UdeaProtocolLock`
(lifecycle logs), `UdeaVerifyEditorAbsentTask` (the `scanned:` report lines, a jar-read failure, and
the two `ScannedClass` origins this change now renders through `gateLocation`),
`UdeaVerifyReleaseTask` (the `scanned archive:` report lines, an archive-read failure, and the
`ArchiveEntry` origins this change now renders through `gateLocation`),
`UdeaVerifyDeterminismTask` (an `allowlist:` report line), and `udea.determinism-check.gradle.kts`
(task inputs). Only the two rule reports are changed.

---

## 4. `sh gradlew build`

```
ANDROID_HOME=$HOME/Android/Sdk JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem \
  sh gradlew build --continue --no-configuration-cache --max-workers=4
```

Each run was detached, and each exit status below is read off the marker its script writes as its
last act, never inferred from scrollback. There were three, and the red one is here too.

### `build1` - green, on the old base, and superseded

On `c3103cd` (base `67d9f99`): `EXIT=0`, `BUILD SUCCESSFUL in 10m 49s`, 1122 tasks, and
`build-logic`'s 44 suites and 380 tests all passing. **It is not the evidence for this branch.** While
it ran, `master` moved twenty commits, including `build-logic` sources this change is built against,
so it measured a tree nobody will merge. I rebased and measured again.

### `build2` - red, on the final code, and every cause is Metaspace

```
LAUNCH 2026-09-21T03:38:14Z 2.06 6.26 9.12
EXIT=1
END 2026-09-21T03:47:59Z 9.31 8.12 8.66
DONE
```

The last two lines of `build2.log` (lines 2304-2305 of 2305):

```
BUILD FAILED in 9m 44s
1085 actionable tasks: 970 executed, 13 from cache, 102 up-to-date
```

Five tasks failed, and **not one of them is a compile error or a test failure, and none is in
`build-logic`**:

| Task | Cause, as the log states it |
|---|---|
| `:moba:android:parseReleaseLocalResources` | `> Metaspace` |
| `:moba:android:mergeReleaseResources` | `> Metaspace` |
| `:moba:android:processReleaseMainManifest` | `> Metaspace` |
| `:moba:android:parseDebugLocalResources` | `Could not initialize class com.android.ide.common.symbols.Symbol` - the echo of an earlier metaspace OOM leaving a class half-initialised, not a failure of its own |
| `:udea-gas:compileTestKotlinIosArm64` | its summary says only `Compilation finished with errors`, which reads as a real compile error; the compiler's own line, `build2.log` line 1258, is `e: org.jetbrains.kotlin.fir.pipeline.IrGenerationExtensionException: Metaspace` |

Neither this diff nor the twenty new `master` commits touch `udea-gas`. The shared daemon had served
eight builds of mine by then, and `AGENTS.md` documents exactly this: KSP2 does not give metaspace
back to a long-lived daemon, and the failure does not reproduce on a fresh one.

**It was `--no-daemon`, not `--stop`, and that is a change to the command line above.** `--stop`
stops every Gradle 8.13 daemon this user owns; there were five in the pool, one of them serving the
lead's own post-merge build of `master`. A `pgrep` reading showed one live client whose pid was gone
by the time its `/proc/<pid>/cmdline` was read, so any "nobody else is building" reading was a
snapshot. `--no-daemon` gives this build a fresh single-use JVM with fresh metaspace and touches no one
else's.

### `build3` - the same tree, a fresh JVM: green

```
LAUNCH 2026-09-21T03:49:16Z 5.27 7.12 8.27
EXIT=0
END 2026-09-21T03:50:14Z 6.53 7.10 8.19
DONE
```

The last two lines of `build3.log` (lines 1873-1874 of 1874):

```
BUILD SUCCESSFUL in 57s
1122 actionable tasks: 64 executed, 1 from cache, 1057 up-to-date
```

**Read that task line before reading the verdict.** This was not a fresh full build: 1057 of 1122
tasks were up to date. That is still a complete result, and it is worth saying exactly why. An
up-to-date task is one Gradle has recorded as having *succeeded* with these exact inputs, and
`build2` ran on this same tree. So:

- **Every task that passed in `build2` passed on this tree.** `build-logic`'s tests among them:
  `:build-logic:test` *executed* in `build2` (no suffix, not failed) and is up to date here, and its
  results are timestamped `03:38:40`-`03:40:05`, inside `build2`'s window: **45 suites, 398 tests,
  0 skipped, 0 failures, 0 errors.** The rise from `build1`'s 44 and 380 is `master`'s new
  net-components tests, which is further evidence the new base was the one measured. The five this
  branch touches or reads - `GateLocationTest` 4, `EditorReleaseRulesTest` 8, `ReleaseRulesTest` 9,
  `VerifyEditorAbsentTest` 5, `VerifyReleaseTest` 8 - all pass, none skipped.
- **Four of the five metaspace tasks re-executed on the fresh JVM and passed** - no suffix on their
  task lines, so they genuinely ran.
- **The fifth, `:moba:android:parseDebugLocalResources`, was restored from the build cache rather
  than re-run.** That is the "1 from cache". It is a resource-parsing task, not a test, so no test
  report carries a borrowed timestamp, and a cache hit means identical inputs produced that output
  before. But the true sentence is "four re-ran, one came from cache", not "all five re-ran".
- **`Metaspace` does not appear in `build3.log` at all** - zero lines, against 1841 `> Task` lines as
  the control, so the search ran over a real, full log.

**Verdict: the metaspace did not reproduce on a fresh JVM. It was the daemon's, not the branch's.**
The lead's post-merge build of `master` at `00a2093` - this branch's base - was green with zero
`Metaspace` lines too, but that is evidence about `master`, not about `master` plus these nine files.
`build3` is the run that settles it for this branch, and it is the only one cited as proof.

### What this green does not say

- **The GL surface was not tested, and this change does not need it to be.** `DISPLAY` is empty
  here, so the GL suites ran as tasks and skipped their tests: `udeaGlTest` 28 of 29 skipped,
  `udeaAgentGlTest` 2 of 2, `udeaEditorGlTest` 6 of 6. This branch touches only `build-logic` and
  `docs/`, nothing in `udea-render`, `udea-agent-host` or `udea-editor`, so the xvfb run is not
  required - but this green is not evidence about GL, and should not be read as it.
- **No latency budget ran.** `udeaDaemonBudget` does not appear in any of the three logs: the
  wall-clock budgets hang off the separate `udeaLatencyBudgets` aggregate, as `ci.yml` says.

## 4a. Images

**None, because nothing in this change is drawn.** It alters the text of two build gates' failure
messages and adds tests over them; no frame, HUD or scene is touched, so a screenshot would show an
unchanged game and prove nothing. The evidence is the failure text itself, spliced in sections 1
and 5. The dashboard posts for this ticket were text for that reason.


---

## 5. How I know the new tests can fail

Every figure below was predicted in writing **before the box hold lifted**
(`scratchpad/win/predictions.md`), then measured. Each diff is the literal diff of the mutation
against the correct fix, written to `M<n>.diff` by the run itself and spliced here, not retyped.
Counts are the Gradle summary line and each suite's JUnit XML. The failing-test grep is anchored on
`() FAILED` and `(File) FAILED`, so it cannot count `BUILD FAILED` or `Task :test FAILED`.

### M1 - `gateLocation` becomes the identity function

The faithful one: it restores exactly the shape `master` ships today.

```diff
diff --git a/build-logic/src/main/kotlin/dev/wildware/udea/build/GateLocation.kt b/build-logic/src/main/kotlin/dev/wildware/udea/build/GateLocation.kt
index 3240b63..52d2019 100644
--- a/build-logic/src/main/kotlin/dev/wildware/udea/build/GateLocation.kt
+++ b/build-logic/src/main/kotlin/dev/wildware/udea/build/GateLocation.kt
@@ -28,4 +28,4 @@ package dev.wildware.udea.build
  * slash instead. That is a cosmetic error in a failure message, against a real one on every
  * Windows build, and `ContractFreeze` already took the same trade.
  */
-internal fun gateLocation(path: String): String = path.replace('\\', '/')
+internal fun gateLocation(path: String): String = path
```

**Predicted 5 failed, across 3 classes. Measured: `26 tests completed, 5 failed`.**

| Suite | failures | Which |
|---|---|---|
| `GateLocationTest` | 3 of 4 | the Windows, UNC and jar-entry cases |
| `EditorReleaseRulesTest` | 1 of 8 | the Windows case |
| `ReleaseRulesTest` | 1 of 9 | the Windows case |
| `VerifyEditorAbsentTest` | **0 of 5** | none |

`GateLocationTest > a POSIX location is passed through unchanged` passed, as predicted: the identity
function satisfies it, which is why it cannot be the only case.

**The zero in the last row is the ticket in miniature.** `VerifyEditorAbsentTest` ran fresh in this
run (timestamped `03:18:52`) and passed every case under the exact behaviour that has been failing on
Windows. The integration test that was red on Windows is **blind** to this defect on the machine we
develop on. That is now measured rather than argued, and it is why the fix had to be a `String`
helper with its own test.

### M2 - `gateLocation` over-reaches the other way

```diff
diff --git a/build-logic/src/main/kotlin/dev/wildware/udea/build/GateLocation.kt b/build-logic/src/main/kotlin/dev/wildware/udea/build/GateLocation.kt
index 3240b63..4bbdcd3 100644
--- a/build-logic/src/main/kotlin/dev/wildware/udea/build/GateLocation.kt
+++ b/build-logic/src/main/kotlin/dev/wildware/udea/build/GateLocation.kt
@@ -28,4 +28,4 @@ package dev.wildware.udea.build
  * slash instead. That is a cosmetic error in a failure message, against a real one on every
  * Windows build, and `ContractFreeze` already took the same trade.
  */
-internal fun gateLocation(path: String): String = path.replace('\\', '/')
+internal fun gateLocation(path: String): String = path.replace('/', '\\')
```

**Predicted 9 failed, across 4 classes. Measured: `26 tests completed, 9 failed`.**

| Suite | failures | Which |
|---|---|---|
| `GateLocationTest` | 4 of 4 | every case |
| `EditorReleaseRulesTest` | 2 of 8 | the Windows case, and **`the report names the rule, the project, each class and where it was`** |
| `ReleaseRulesTest` | 2 of 9 | the Windows case, and **`the report names the rule id, the jar path and the offending entry`** |
| `VerifyEditorAbsentTest` | 1 of 5 | `a gizmo on the release runtime classpath fails the gate, naming the class and where it was` |

The two in bold are **not mine**. They were already in the tree - `EditorReleaseRulesTest:116`
asserts `"/x/editor/classes"` and `ReleaseRulesTest:69` asserts `"/build/libs/moba.jar"` - written by
whoever added those gates. A control nobody wrote for this occasion cannot have been shaped to pass.

### One prediction I got wrong in form, and corrected

I predicted `VerifyReleaseTest > an agent class in the packaged jar fails a release build` would
**pass** under M2. The M2 run said nothing about it: that class is not in the evidence command's
filter, and its result file was **absent** - so the row was UNKNOWN, not a pass. An absent result
reads exactly like a clean one, which is the trap.

I measured it instead, alone, with M2 still applied (`M2-vrt`): `EXIT=0`, 8 tests, 0 failures,
timestamped `03:21:06`. The prediction held, and it is also a finding.

**`UDEA-REL-001`'s integration test asserts only the bare filename `moba.jar`, so it cannot see a
wrong separator in either direction.** The new `ReleaseRulesTest` case is now the *only* thing
guarding that gate's spelling. That is the data under the requirement that the sibling gate get its
own test, and it means that requirement was not tidiness: without it, a regression in the release
gate's message would be invisible to every test in the repository.

### Restored after each

The mutated file was checked out from the index, and the unstaged diff was then confirmed empty
(`--quiet`, `exit=0`) both times, so the tree matched the staged fix before the next run.


The control is designed as a **pair**, and each half is there because the other cannot catch its
mutation:

- the Windows cases die if `gateLocation` becomes the identity function — which is the shape the
  repository ships today, and the shape a careless refactor restores;
- the POSIX case dies if it over-reaches the other way and rewrites `/` into `\`.

Neither mutation is reachable from the other half.

**And two of the controls are not mine.** `EditorReleaseRulesTest:116` (`"/x/editor/classes"`) and
`ReleaseRulesTest:69` (`"/build/libs/moba.jar"`) were already in the tree, written by whoever added
those gates, and both go red under the over-reaching mutation. A control nobody wrote for the
occasion is stronger evidence than one that was, because it cannot have been shaped to pass.

Predictions for every run below were written down **before** the box hold lifted, in
`scratchpad/win/predictions.md`, so a figure that arrives is a figure that was predicted rather than
one explained afterwards.

---

## 6. Red 2: the measurement, not a patch

**There is no defect here to fix, and the ticket's premise for it does not hold.**

`UdeaAgentPluginTest > a release build generates a flag that refuses to bind` failed **once**, in
run **35529751827** (`master`, `d1bf2453`),
<https://github.com/wildware-uk/Udea/actions/runs/35529751827>. Spliced from
`udea-gradle/build/reports/tests/test/classes/dev.wildware.udea.gradle.UdeaAgentPluginTest.html`,
inside the `test-reports-windows-latest` artifact of that run:

```
java.lang.IllegalStateException: An error occurred executing build with args '-Dorg.gradle.vfs.watch=false udeaGenerateAgentBuildFlags -Pudea.release=true --stacktrace' in directory 'D:\a\Udea\Udea\udea-gradle\build\tmp\test\junit-10674327694229532131'
	at org.gradle.testkit.runner.internal.ToolingApiGradleExecutor.run(ToolingApiGradleExecutor.java:170)
	at org.gradle.testkit.runner.internal.DefaultGradleRunner.run(DefaultGradleRunner.java:353)
	at org.gradle.testkit.runner.internal.DefaultGradleRunner.build(DefaultGradleRunner.java:272)
	at dev.wildware.udea.gradle.UdeaAgentPluginTest.a release build generates a flag that refuses to bind(UdeaAgentPluginTest.kt:236)
	at java.base/java.lang.reflect.Method.invoke(Method.java:580)
	at java.base/java.util.ArrayList.forEach(ArrayList.java:1596)
	at java.base/java.util.ArrayList.forEach(ArrayList.java:1596)
Caused by: org.gradle.tooling.GradleConnectionException: Could not execute build using connection to Gradle installation 'D:\a\.gradle\wrapper\dists\gradle-8.13-bin\5xuhj0ry160q40clulazy9h7d\gradle-8.13'.
```

**Not one assertion in that test was ever evaluated.** The build under test never ran.

### Frequency

**1 failure in 29 `build (windows-latest)` jobs**, enumerated from
`gh run view <id> --json jobs`. The other three failures of that job in the same window were:
`:build-logic:test` / `VerifyEditorAbsentTest` (run 35537176801 — red 1), and
`WebSocketWasmClientTest[wasmJs, node]` twice on feature branches (runs 35521453847, 35460243106) —
an unrelated flake.

### The timing pair

- `the agent source set exists and carries the generated flag` — the test immediately before it,
  and the one that starts the TestKit daemon cold: **1m1.25s**, `BUILD SUCCESSFUL in 1m`.
- `a release build generates a flag that refuses to bind` — **23.493s**, and **no captured build
  output at all**, while every other case in that class has an output block.

A daemon that had just taken a minute to start, and a connection to it that did not survive the next
build.

### The four causes eliminated, and what eliminated each

| Suspect | Eliminated by |
|---|---|
| **Line endings** — `\r\n` inside the generated file breaking the match | The asserted string, `AGENT_ALLOWED: Boolean = false`, contains no newline, so no line break can fall inside it. And the assertion never ran: the failure is at line 236, the `.build()` call, two lines above `assertContains`. |
| **A path built with `/` in `generatedFlagsFile()`** | It is `projectDir.resolve("build/generated/sources/udea-agent-flags/…")`. `File.resolve` hands the string to `java.io.File`, which normalises separators on construction, so it is correct on Windows. And again: the assertion never ran. |
| **`-P` quoted differently by the Windows shell** | There is no shell. `GradleRunner` passes its arguments to the Tooling API as a `List<String>`; the args in the exception message are exactly the ones the test supplied, unmangled, `-Pudea.release=true` included. |
| **File locking on a generated file still open** | That would surface as a build failure (`UnexpectedBuildFailure`, or a task failure inside the build), not as `GradleConnectionException` from `ToolingApiGradleExecutor`. The exception class says the *connection* failed, not the build. |

### What that leaves

An infrastructure failure on a loaded `windows-latest` runner. Nothing in the test to fix, and a
retry wrapper would turn a real signal about runner health into a green.

---

## 7. Out of scope, found and deliberately not fixed

**`udea-agent`'s `AssetsToolset` and `udea-editor`'s `EditorAssets`.**
`udea-agent/src/jvmMain/kotlin/dev/wildware/udea/agent/assets/AssetsToolset.kt:375` puts
`created.path` — a native-separator path — into a tool result's `path` field, and
`udea-editor/src/main/kotlin/dev/wildware/udea/editor/EditorAssets.kt:204-205` prints it verbatim
into the message `moba/desktop/src/editorTest/kotlin/dev/wildware/moba/editor/MobaEditorSaveTest.kt`
asserts at lines 90 and 131. Same class of defect. Not touched, for three reasons:

1. It is a **shipped runtime module**, and what an agent tool returns in `path` is a public answer,
   not a build gate's failure text. Different blast radius, different decision.
2. The frozen diagnostics contract requires a repo-relative forward-slashed `SourceSpan`. A tool
   result's `path` is **not** a `SourceSpan`, so the convention this branch enforces does not
   automatically extend to it. Whether it should is a design question with a defensible answer
   either way, and the owner may have a view.
3. Those tests are `editorTest`/GL-gated and **skip** on Windows, so they are *untested* rather than
   correct — which is a reason to record it, not a reason to fix it inside a branch reviewed for
   something else.

Recorded here and relayed to the lead for `WAVE.md`, so it outlives the branch.

---

## 8. The issue, criterion by criterion

There is no GitHub issue (the owner's rule), so these are the requirements from the lead's task
and its follow-ups, each against what proves it.

| Requirement | Proof |
|---|---|
| Confirm red 1's diagnosis against the real failure before fixing | Section 2. The gate prints `root.absolutePath`; `R0` reproduces the exact Windows text on Linux: `game/Ring is a Gizmo, in D:\a\Udea\Udea\moba\editor` (section 1). |
| Red 2: read the log, do not guess | Section 6: run `35529751827`, the spliced trace, 1 in 29, the timing pair, four causes eliminated with what eliminated each. |
| Assert the property, not the accident - no Windows branch, not assertion-site normalisation alone | The fix is in the gate's message (`gateLocation`), not the assertion. Section 2's decisions table. |
| Fix the class, not the instance; sweep with a positive control | Sections 3a-3e, each beside a control. `ReleaseRules` fixed as the sibling instance. |
| The sibling gets a test, not just the fix | `ReleaseRulesTest > the report prints a Windows archive path forward-slashed...`: red in `R0`, green in `R1`. And `M2-vrt` shows it is the **only** test that can see that gate's spelling (section 5). |
| A positive control on the helper | `GateLocationTest`'s pair, and `M1`/`M2` in section 5: identity kills the Windows cases, over-reach kills the POSIX case, neither reachable from the other. |
| Nothing depends on the old message | Sections 3b-3d: every reader enumerated, every assertion on the four message-reading classes enumerated, `outside-game-proof.sh` checked with a positive beside it. |
| Do not disable, skip or `@Ignore` either test | The staged Kotlin diff (200 lines) has no added `@Ignore`, `@Disabled`, `DisabledOnOs`, `EnabledOnOs`, assumption, `isWindows` or `os.name`: `exit=1`. The same pattern finds `Assumptions.abort` and `assumeFalse` in other tests, so it can hit. Over the whole diff it hits once - the sentence in section 2 saying `@Ignore` was *rejected*: prose mentioning the thing, not code doing it. |
| Do not change the CI `concurrency` block | No file under `.github/` in the change: the nine files are listed in full by `git diff --name-only`, and a grep of that list for `^.github/` answers `exit=1`. |
| Frozen contracts untouched | Nothing under `docs/contracts/`, same listing and grep, `exit=1`. `docs/contracts.lock` untouched. |
| Do not touch the locks | Section 9: `net-protocol.lock`, `expected-generated-hashes.txt` and `contracts.lock` untouched; 0 of the nine files. |
| TDD: see it fail for the Windows reason on Linux first | `R0`, section 1: `26 tests completed, 2 failed`, the two new cases, with the backslashed Windows path in the failure text. |
| One evidence command, red on revert | Section 1. At the final code, green as committed (`R1b`) and red with the helper neutralised (`M1b`, 5 failures). At `c3103cd`, reverting the production change reds it too (`R0`). |
| Local `sh gradlew build`, exit code off the marker | Section 4: on the final code, `build2` red on Metaspace alone, `build3` on a fresh JVM `EXIT=0`, 1122 tasks, with what that green does and does not cover. |
| Deliverable reframed | The top of this document: one defect fixed, one runner flake measured. |
| Out-of-scope `AssetsToolset` recorded with reasons | Section 7, the three reasons. |
| **A green Windows job on the pushed branch, run id and URL, completed not cancelled, with jobs and test counts** | _Filled in once that run has completed - pushing anything while it runs would cancel it._ |


---

## 9. Regenerated files

**None.** `udea-codegen/net-protocol.lock` and
`udea-codegen/src/test/resources/expected-generated-hashes.txt` are untouched, and no id moved: this
change adds no replicated component and generates nothing. `docs/contracts.lock` is untouched too.
