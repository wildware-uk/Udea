# BRIEF — `issue-244-committed-glb`: an `.fbx` model's `.glb` is committed, not converted by the build

SHA: `91f9dd90`

Every measurement in this brief was taken at `91f9dd90`. This file is committed on top of it and
changes no code, so the reviewer's subject is `91f9dd90` and the brief's own commit is the one after
it.

Branch `issue-244-committed-glb`, off `origin/master`, merged with `origin/master` at `483cb10e`.
Worktree `/srv/ssd1/workspace/Udea/.claude/worktrees/agent-a550cbab51126c358`.
Follow-up to #244, from the lead's "Found 2026-09-21" comment. Option 1 of that comment, as decided.

---

## 1. The evidence command

```
sh gradlew :udea-assets-compiler:test --tests '*CommittedModelsTest' --no-build-cache
```

Thirteen tests. They are about one claim: **the build reads the `.glb` committed beside an `.fbx`
and never converts.** The fixture is chosen so the two answers cannot be confused — the `.glb`
standing in as "committed" is the Khronos Fox, whose clips are `Survey`, `Walk`, `Run`, where the
FBX fixture beside it converts to `Bend` and `Twist`. A build that still converted would read
`Bend`; one that reads what is committed reads `Survey`.

Run just now on this tree — the marker (`evidence.marker`, light lane) and what it produced:

```
QUEUED 2026-09-22T19:10:00+00:00 evidence lane=light
START 2026-09-22T19:10:12+00:00 evidence lane=light other_lane=held mem_available_mb=8356
EXIT=0 2026-09-22T19:10:38+00:00 evidence lane=light other_lane=held
```

```
$ tail -2 evidence.log; then the <testsuite> line of the JUnit XML it wrote
BUILD SUCCESSFUL in 25s
29 actionable tasks: 4 executed, 25 up-to-date
<testsuite name="dev.wildware.udea.assets.compiler.model.CommittedModelsTest" tests="13" skipped="0" failures="0" errors="0" timestamp="2026-09-22T19:10:29.410Z" hostname="wild-home-server" time="4.993">
```

`skipped="0"`, and the timestamp **inside** the XML is 19:10:29 against a wall clock of 19:10:39, so
this is an executed run and not a result restored from the build cache.

Two things about that marker, because `lightrun.sh` has two outcomes that are not reds and not
passes. It says `START` and `EXIT=0`, so it neither skipped on the memory floor (`EXIT=75`, which
means *nothing ran*) nor hit the five-minute cap (`EXIT=124`). All three of my light-lane runs —
`shot2`, `uptodate`, `evidence` — say `START` then `EXIT=0`; none of my markers contains `SKIPPED`
or `TIMEOUT`. The fresh in-XML timestamp is the independent check on the same fact.

### It goes red when the feature is reverted

Five mutations. Each row carries the literal `git diff` taken in my worktree at the moment its test
ran, and each was reverted with `git checkout -- .` afterwards; the worktree was clean before the
next row started. Failing-test names come out of the JUnit XML by matching
`<testcase …><failure|error`, not from an unanchored `grep` of the console, which also matches
`BUILD FAILED`.

**Baseline first**, so that a row which merely failed to compile — which exits non-zero in
seconds and reads exactly like a mutation biting — can be told from a row that bit. `green1.marker`:
`:udea-assets-compiler:test :udea-gradle:test --no-build-cache`, `EXIT=0`, 302 + 64 tests, 0
failures, in-XML timestamps 18:22:08–18:24:17 against a wall clock of 18:24:39.
**Every row below carries its `N tests completed` line, so none is VOID, and every red names a
failing test together with the file and line of the assertion that failed.**

#### M1 — the pack converts again instead of reading the committed `.glb`

The mutation, as `git diff` printed it in my worktree while the test ran (`m1.diff`):

```diff
diff --git a/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/pipeline/AssetPipeline.kt b/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/pipeline/AssetPipeline.kt
index 1478cac0..ed51a320 100644
--- a/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/pipeline/AssetPipeline.kt
+++ b/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/pipeline/AssetPipeline.kt
@@ -7,6 +7,7 @@ import dev.wildware.udea.assets.compiler.ResFile
 import dev.wildware.udea.assets.compiler.atlas.AtlasPacker
 import dev.wildware.udea.assets.compiler.atlas.SheetInput
 import dev.wildware.udea.assets.compiler.model.CommittedModels
+import dev.wildware.udea.assets.compiler.model.FbxConverter
 import dev.wildware.udea.assets.compiler.model.ModelSources
 import dev.wildware.udea.assets.compiler.pack.BundleContent
 import dev.wildware.udea.assets.compiler.pack.BundleWriter
@@ -163,7 +164,7 @@ public object AssetPipeline {
         for (model in graph.assets.values.filter { it.kind == ModelFileValidator.KIND }.sortedBy { it.id }) {
             val path = model.fields[ModelFileValidator.FILE_FIELD] as? ResFile ?: continue
             if (path.isMalformed || !ModelSources.isConverted(path) || !assetRoot.resolve(path.value).isRegularFile()) continue
-            CommittedModels.read(assetRoot, path).fold(
+            FbxConverter.convert(assetRoot, path).fold(
                 onSuccess = { files[ModelSources.runtimeFile(path).value] = it },
                 onFailure = { reason ->
                     diagnostics += AssetValidationRules.MODEL_CONVERSION.diagnostic(
```

`sh gradlew :udea-assets-compiler:test --no-build-cache` — the marker (`m1.marker`):

```
START 2026-09-22T18:25:44+00:00 /srv/ssd1/workspace/Udea/.claude/worktrees/agent-a550cbab51126c358 m1
EXIT=1 2026-09-22T18:27:17+00:00 /srv/ssd1/workspace/Udea/.claude/worktrees/agent-a550cbab51126c358 m1
```

and what went red, filtered out of `m1.log` by the command in the first line (`m1.extract.txt`):

```
$ grep -E 'tests completed|FAILED$|org\.opentest4j|java\.(lang|util)\.' /tmp/claude-1000/-srv-ssd1-workspace-Udea/1ad8c5e6-2def-4055-91d2-72acdfe77daf/scratchpad/dev-244glbc/m1.log | head -10
CommittedModelsTest > the pack publishes the committed glb byte for byte() FAILED
    org.opentest4j.AssertionFailedError at CommittedModelsTest.kt:77
FbxModelAssetTest > the validator does not convert, so a broken fbx beside a sound glb validates clean() FAILED
    org.opentest4j.AssertionFailedError at FbxModelAssetTest.kt:69
302 tests completed, 2 failed, 1 skipped
> Task :udea-assets-compiler:test FAILED
```

#### M2 — the clip and node reader converts again

The mutation, as `git diff` printed it in my worktree while the test ran (`m2.diff`):

```diff
diff --git a/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/gen/ModelFileSource.kt b/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/gen/ModelFileSource.kt
index c3f01bb0..2b512d7d 100644
--- a/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/gen/ModelFileSource.kt
+++ b/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/gen/ModelFileSource.kt
@@ -103,7 +103,7 @@ internal object ModelFileSource {
         // An `.fbx` is read as the `.glb` committed beside it (issue #244), never converted here;
         // one with no `.glb` is `UDEA0039`, the same defect the validator reports, and not this rule.
         val read = if (ModelSources.isConverted(path)) {
-            val glb = CommittedModels.read(assetRoot, path).getOrElse { reason ->
+            val glb = dev.wildware.udea.assets.compiler.model.FbxConverter.convert(assetRoot, path).getOrElse { reason ->
                 return Result.failure(ConversionFailure("names `$path`, which ${reason.message}"))
             }
             GltfClips.read(glb).andThen { clips -> GltfNodes.readModel(glb).map { Contents(clips, it.nodes, it.extras) } }
```

`sh gradlew :udea-assets-compiler:test --no-build-cache` — the marker (`m2.marker`):

```
START 2026-09-22T18:27:17+00:00 /srv/ssd1/workspace/Udea/.claude/worktrees/agent-a550cbab51126c358 m2
EXIT=1 2026-09-22T18:28:41+00:00 /srv/ssd1/workspace/Udea/.claude/worktrees/agent-a550cbab51126c358 m2
```

and what went red, filtered out of `m2.log` by the command in the first line (`m2.extract.txt`):

```
$ grep -E 'tests completed|FAILED$|org\.opentest4j|java\.(lang|util)\.' /tmp/claude-1000/-srv-ssd1-workspace-Udea/1ad8c5e6-2def-4055-91d2-72acdfe77daf/scratchpad/dev-244glbc/m2.log | head -10
ModelClipAccessorsTest > an fbx with no committed glb fails the accessors pass with the conversion rule, not this one() FAILED
    java.util.NoSuchElementException at ModelClipAccessorsTest.kt:151
CommittedModelsTest > an fbx model's clips are read from the glb committed beside it, not from a conversion() FAILED
    org.opentest4j.AssertionFailedError at CommittedModelsTest.kt:66
CommittedModelsTest > an fbx with no committed glb fails with UDEA0039, naming the file and the task that writes it() FAILED
    java.util.NoSuchElementException at CommittedModelsTest.kt:83
302 tests completed, 3 failed, 1 skipped
> Task :udea-assets-compiler:test FAILED
```

#### M3 — every platform claims to be the reference platform

The mutation, as `git diff` printed it in my worktree while the test ran (`m3.diff`):

```diff
diff --git a/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/model/CommittedModels.kt b/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/model/CommittedModels.kt
index 14c92b0f..84c28155 100644
--- a/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/model/CommittedModels.kt
+++ b/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/model/CommittedModels.kt
@@ -181,7 +181,7 @@ internal data class ConversionHost(val osName: String, val osArch: String) {
 
     /** True when this is the platform whose Assimp the committed `.glb` files came from. */
     val isReference: Boolean
-        get() = osName.startsWith("Linux") && osArch in X86_64
+        get() = true
 
     override fun toString(): String = "$osName $osArch"
 
```

`sh gradlew :udea-assets-compiler:test --no-build-cache` — the marker (`m3.marker`):

```
START 2026-09-22T18:28:41+00:00 /srv/ssd1/workspace/Udea/.claude/worktrees/agent-a550cbab51126c358 m3
EXIT=1 2026-09-22T18:30:03+00:00 /srv/ssd1/workspace/Udea/.claude/worktrees/agent-a550cbab51126c358 m3
```

and what went red, filtered out of `m3.log` by the command in the first line (`m3.extract.txt`):

```
$ grep -E 'tests completed|FAILED$|org\.opentest4j|java\.(lang|util)\.' /tmp/claude-1000/-srv-ssd1-workspace-Udea/1ad8c5e6-2def-4055-91d2-72acdfe77daf/scratchpad/dev-244glbc/m3.log | head -10
CommittedModelsTest > the check is skipped on every other platform, and says why() FAILED
    java.lang.ClassCastException at CommittedModelsTest.kt:205
302 tests completed, 1 failed, 1 skipped
> Task :udea-assets-compiler:test FAILED
```

#### M4 — the check stops comparing bytes

The mutation, as `git diff` printed it in my worktree while the test ran (`m4.diff`):

```diff
diff --git a/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/model/CommittedModels.kt b/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/model/CommittedModels.kt
index 14c92b0f..849cc7cd 100644
--- a/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/model/CommittedModels.kt
+++ b/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/model/CommittedModels.kt
@@ -145,7 +145,7 @@ internal object CommittedModels {
                 diagnostics += conversion(model, "which ${reason.message}")
                 continue
             }
-            if (committed.contentEquals(converted)) {
+            if (committed.isNotEmpty()) {
                 current += model.glb
             } else {
                 diagnostics += conversion(
```

`sh gradlew :udea-assets-compiler:test --no-build-cache` — the marker (`m4.marker`):

```
START 2026-09-22T18:30:03+00:00 /srv/ssd1/workspace/Udea/.claude/worktrees/agent-a550cbab51126c358 m4
EXIT=1 2026-09-22T18:31:43+00:00 /srv/ssd1/workspace/Udea/.claude/worktrees/agent-a550cbab51126c358 m4
```

and what went red, filtered out of `m4.log` by the command in the first line (`m4.extract.txt`):

```
$ grep -E 'tests completed|FAILED$|org\.opentest4j|java\.(lang|util)\.' /tmp/claude-1000/-srv-ssd1-workspace-Udea/1ad8c5e6-2def-4055-91d2-72acdfe77daf/scratchpad/dev-244glbc/m4.log | head -10
CommittedModelsTest > a source changed after the glb was written fails the check with UDEA0039 naming the writer() FAILED
    java.util.NoSuchElementException at CommittedModelsTest.kt:146
302 tests completed, 1 failed, 1 skipped
> Task :udea-assets-compiler:test FAILED
```

#### M5 — `check` stops depending on the gate

The mutation, as `git diff` printed it in my worktree while the test ran (`m5.diff`):

```diff
diff --git a/udea-gradle/src/main/kotlin/dev/wildware/udea/gradle/UdeaAssetsPlugin.kt b/udea-gradle/src/main/kotlin/dev/wildware/udea/gradle/UdeaAssetsPlugin.kt
index 330d94ad..2452f9c1 100644
--- a/udea-gradle/src/main/kotlin/dev/wildware/udea/gradle/UdeaAssetsPlugin.kt
+++ b/udea-gradle/src/main/kotlin/dev/wildware/udea/gradle/UdeaAssetsPlugin.kt
@@ -728,7 +728,7 @@ public class UdeaAssetsPlugin : Plugin<Project> {
 
         project.tasks.named(
             CHECK_TASK,
-            gradleAction { task: Task -> task.dependsOn(validate, relocatable, verifyModels) },
+            gradleAction { task: Task -> task.dependsOn(validate, relocatable) },
         )
     }
 
```

`sh gradlew :udea-gradle:test --no-build-cache` — the marker (`m5.marker`):

```
START 2026-09-22T18:31:43+00:00 /srv/ssd1/workspace/Udea/.claude/worktrees/agent-a550cbab51126c358 m5
EXIT=1 2026-09-22T18:32:35+00:00 /srv/ssd1/workspace/Udea/.claude/worktrees/agent-a550cbab51126c358 m5
```

and what went red, filtered out of `m5.log` by the command in the first line (`m5.extract.txt`):

```
$ grep -E 'tests completed|FAILED$|org\.opentest4j|java\.(lang|util)\.' /tmp/claude-1000/-srv-ssd1-workspace-Udea/1ad8c5e6-2def-4055-91d2-72acdfe77daf/scratchpad/dev-244glbc/m5.log | head -10
UdeaAssetsPluginTest > check verifies the committed glb files and never writes them() FAILED
    org.opentest4j.AssertionFailedError at UdeaAssetsPluginTest.kt:145
64 tests completed, 1 failed
> Task :udea-gradle:test FAILED
```
Every mutation also restores a shape the code really had: M1 and M2 are the two call sites as they
stood on `master` before this branch, M3 and M4 remove a comparison rather than invert one, and M5
is the `check` wiring without the new dependency.

**The control, for the fence that reads source.** `CommittedModelsTest`'s fixture check
(`the fixture's committed glb is the conversion of the fbx beside it`) is the one assertion that
could pass vacuously on a machine that is not the reference platform, because it opens with
`Assumptions.assumeTrue(ConversionHost.current().isReference)`. On this box it does **not** skip:
the run below reports `skipped="0"` for that suite, and M3 — which makes every platform claim to be
the reference — turns a *different* test red rather than this one, which is what says the assumption
was live.

---

## 2. Summary: what I did, and what I decided

`Human.fbx` converted to different bytes under LWJGL 3.3.6's Linux and Windows Assimp natives
(`assimp v5.4.0`, git revision 0, against `assimp v5.4.90d2a697`). Since #271 packs a model's node
transforms into the asset graph, the Windows build packed a different graph hash, and
`MobaReplayEqualityTest` and `MobaReplayFixturesCurrentTest` were red there.

So Assimp runs **once, on purpose**, and its output is committed. Every pass that reads a model —
the validator, the clip and node accessors, the pack — reads the `.glb` committed beside the `.fbx`.
Nothing in an ordinary build runs Assimp.

Two tasks keep the committed file true. Both are registered by `dev.wildware.udea.assets`, so a
game outside this repository gets them exactly as `moba` does:

| Task | What it does |
|---|---|
| `udeaWriteConvertedModels` | Converts each `.fbx` a `model(...)` names and writes its `.glb` beside it. Run by hand on Linux x86_64; declares no outputs, because its output is source. |
| `udeaVerifyConvertedModels` | On `check`. Converts again and fails with `UDEA0039` when the result is not the committed file, naming the writer. Converts on Linux x86_64 alone; anywhere else it prints one line saying it skipped and why. |

**Three decisions, all also on #244 (comment 5782046797), with what to change if the owner
disagrees:** the reference platform is Linux x86_64 and no other Linux; the check is on `check` and
the writer is on nothing; and a committed `.glb` that is not glTF is `UDEA0038`, while a *missing*
one stays `UDEA0039`.

**What Windows CI then showed, which I did not predict.** The two moba replay tests this ticket
names now **pass** on `windows-latest` — `MobaReplayEqualityTest` nine tests, all passed, and
`MobaReplayFixturesCurrentTest` passed — and `replay-equality (windows-latest)` is green. But the
`build (windows-latest)` job is **still red**, on a third and separate Windows defect that is red on
`master` as well: `MobaShaderAssetTest` compares the packed GLSL against the `.frag` file as the
test itself reads it, and only the packing side was normalised by `windows-crlf-shaders`. §7 names
the two lines. It is outside this ticket, I have not touched it, and it is **already fixed on
`windows-launch`, commit `1d42304b`** — reached independently by that branch's developer — so there
is nothing here for a reviewer to chase.

**One defect of my own, found by CI and fixed.** I wrote `models/human/Human.fbx` in the wiki — how
a `.udea.kts` names it, relative to the asset root — and `udeaVerifyWiki` reads a backticked path as
a *repository* path, so it was `UDEA-DOC-005` twice. That failed `:build-logic:test` and through it
four jobs of run 35766973519. Commit `91f9dd90` names both files by their repository path. I then
swept the class rather than the instance: every backticked slash-token my whole change adds to
`AGENTS.md` or `docs/` (`scratchpad/dev-244glbc/doc-path-sweep.txt`), with a positive control.

**On the soak-test rule** (`.claude/agents/reviewer.md`, 483cb10e): I judge it does not apply here,
and say so rather than assume. It asks a public hook a game calls to be run for 15 s of real frames.
This branch adds no runtime hook — two build-time Gradle tasks and a change to which bytes the asset
build reads. There are no frames and no pipeline to close. The equivalent for a build-time hook is a
real build living with it, and §4 below is that: both tasks driven through the plugin on both
example games, the check failing loudly twice, `a game outside this repository` green in CI, and the
one runtime-visible consequence — the model a game draws — in §6.

---

## 3. Predicted, then measured

Frozen in `scratchpad/dev-244glbc/predictions.md` before any measurement of my own.

| # | Predicted | Measured |
|---|---|---|
| P1 | No generated file moves on Linux | **Met.** `git diff --stat 483cb10e..HEAD` over `net-protocol.lock`, `expected-generated-hashes.txt`, `net-components.lock`, both moba `.udearep` fixtures and `test_level.roster.txt` is empty. The committed `.glb` *is* today's Linux conversion, so the asset graph hash never moved. |
| P2 | The Linux conversion is deterministic across runs | **Met, three times.** See §4. |
| P3 | `sh gradlew build` green | **Met.** §5: `BUILD SUCCESSFUL in 10m 5s`, 1124 tasks, `git status` clean. |
| P4 | Windows CI replay green | **Met, and it found a third Windows defect I did not predict.** `replay-equality (windows-latest)` is green and `MobaReplayEqualityTest` and `MobaReplayFixturesCurrentTest` now pass on Windows — but `build (windows-latest)` is still red, on `MobaShaderAssetTest`, which is red on `master` too. §7 has the before-and-after out of both runs' own test reports. I predicted the job would go green; it went from three failures to one. |
| P5 | Five mutations go red, with the named tests | **Met, with two extras I did not predict.** M1 also reddened `FbxModelAssetTest > the validator does not convert, so a broken fbx beside a sound glb validates clean`, and M2 also reddened `CommittedModelsTest > an fbx with no committed glb fails with UDEA0039…` — both correct: with conversion restored, a sound `.fbx` with no `.glb` no longer fails at all. M3, M4, M5 landed exactly as predicted, one test each. |

---

## 4. The tasks driven for real, on both example games

Every block below is spliced whole from a file in `scratchpad/dev-244glbc/`. Where a block is a
filtered extract rather than a whole log, **the filter is its first line**, so the same bytes can be
reproduced from the log beside it. Each marker carries its start time and the worktree that made it.

**The gate, as `check` runs it:**

```
START 2026-09-22T18:40:37+00:00 /srv/ssd1/workspace/Udea/.claude/worktrees/agent-a550cbab51126c358 task-verify :moba:game:udeaVerifyConvertedModels :hollow:game:udeaVerifyConvertedModels --rerun-tasks
EXIT=0 2026-09-22T18:41:27+00:00 /srv/ssd1/workspace/Udea/.claude/worktrees/agent-a550cbab51126c358 task-verify
```

```
$ grep -E 'udeaVerifyConvertedModels|BUILD' /tmp/claude-1000/-srv-ssd1-workspace-Udea/1ad8c5e6-2def-4055-91d2-72acdfe77daf/scratchpad/dev-244glbc/task-verify.log
> Task :moba:game:udeaVerifyConvertedModels
[udeaVerifyConvertedModels] 1 committed .glb model(s) current: models/human/Human.glb
> Task :hollow:game:udeaVerifyConvertedModels
[udeaVerifyConvertedModels] 1 committed .glb model(s) current: models/human/Human.glb
BUILD SUCCESSFUL in 49s
```

**The writer:**

```
START 2026-09-22T18:41:27+00:00 /srv/ssd1/workspace/Udea/.claude/worktrees/agent-a550cbab51126c358 task-write :moba:game:udeaWriteConvertedModels :hollow:game:udeaWriteConvertedModels
EXIT=0 2026-09-22T18:41:36+00:00 /srv/ssd1/workspace/Udea/.claude/worktrees/agent-a550cbab51126c358 task-write
```

```
$ grep -E 'udeaWriteConvertedModels|BUILD' /tmp/claude-1000/-srv-ssd1-workspace-Udea/1ad8c5e6-2def-4055-91d2-72acdfe77daf/scratchpad/dev-244glbc/task-write.log
> Task :moba:game:udeaWriteConvertedModels
[udeaWriteConvertedModels] wrote models/human/Human.glb
[udeaWriteConvertedModels] 1 model(s) converted
> Task :hollow:game:udeaWriteConvertedModels
[udeaWriteConvertedModels] wrote models/human/Human.glb
[udeaWriteConvertedModels] 1 model(s) converted
BUILD SUCCESSFUL in 9s
```

`git status --short` over the two asset roots straight afterwards was **empty**
(`task-write-gitstatus.txt`, a zero-byte file) — the writer's fresh conversion reproduced the
committed bytes exactly, which is the first of the determinism measurements.

**Determinism, deliberately harder than a re-run** (`determinism-mine.txt`, `det1..3.marker`, each
`EXIT=0`): the two committed `.glb` files were **deleted** and rewritten by the writer three times,
each run with `--rerun-tasks --no-build-cache`.

```
committed, before anything ran:
c32a968828156ecd19b1898bf13ede2f628037327aabbbf688af9861cc9c4da1  moba/game/assets/models/human/Human.glb
c32a968828156ecd19b1898bf13ede2f628037327aabbbf688af9861cc9c4da1  hollow/game/assets/models/human/Human.glb
run 1, deleted then rewritten by udeaWriteConvertedModels:
c32a968828156ecd19b1898bf13ede2f628037327aabbbf688af9861cc9c4da1  moba/game/assets/models/human/Human.glb
c32a968828156ecd19b1898bf13ede2f628037327aabbbf688af9861cc9c4da1  hollow/game/assets/models/human/Human.glb
run 2, deleted then rewritten by udeaWriteConvertedModels:
c32a968828156ecd19b1898bf13ede2f628037327aabbbf688af9861cc9c4da1  moba/game/assets/models/human/Human.glb
c32a968828156ecd19b1898bf13ede2f628037327aabbbf688af9861cc9c4da1  hollow/game/assets/models/human/Human.glb
run 3, deleted then rewritten by udeaWriteConvertedModels:
c32a968828156ecd19b1898bf13ede2f628037327aabbbf688af9861cc9c4da1  moba/game/assets/models/human/Human.glb
c32a968828156ecd19b1898bf13ede2f628037327aabbbf688af9861cc9c4da1  hollow/game/assets/models/human/Human.glb
git status of the asset roots after three rewrites:
(nothing above means all three runs reproduced the committed bytes)
```

`1068752` bytes — the size the lead's probe named for the Linux conversion.

**The check failing loudly, twice.** The two cases it exists for, driven through the real Gradle
task on the real game rather than through a unit fixture.

*A texture edited without re-running the writer* — one byte of `ClothedLightSkin.png` flipped
(`mut-texture.diff`; `task-verify-stale-texture.marker` reads `EXIT=1`):

```
 moba/game/assets/models/human/ClothedLightSkin.png | Bin 189 -> 189 bytes
 1 file changed, 0 insertions(+), 0 deletions(-)
```

```
$ grep -E 'udeaVerifyConvertedModels\]|BUILD' /tmp/claude-1000/-srv-ssd1-workspace-Udea/1ad8c5e6-2def-4055-91d2-72acdfe77daf/scratchpad/dev-244glbc/task-verify-stale-texture.log
[udeaVerifyConvertedModels] 1 committed .glb model(s) not current
[udeaVerifyConvertedModels] error UDEA0039 moba/game/assets/models/human.udea.kts:5:1 model `models/human` names `models/human/Human.fbx`, which no longer converts to the `models/human/Human.glb` committed beside it (1068752 bytes converted, 1068752 committed), so the .fbx or a texture it names changed after the .glb was written: run `./gradlew udeaWriteConvertedModels` on Linux x86_64 and commit the .glb it writes
BUILD FAILED in 34s
```

Note the two sizes in that message: **same length, different bytes.** A check that compared lengths
would have passed this.

*A broken `.fbx`* — truncated to 400000 of 1319948 bytes (`mut-fbx.diff`;
`task-verify-broken-fbx.marker` reads `EXIT=1`):

```
 moba/game/assets/models/human/Human.fbx | Bin 1319948 -> 400000 bytes
 1 file changed, 0 insertions(+), 0 deletions(-)
```

```
$ grep -E 'udeaVerifyConvertedModels\]|BUILD' /tmp/claude-1000/-srv-ssd1-workspace-Udea/1ad8c5e6-2def-4055-91d2-72acdfe77daf/scratchpad/dev-244glbc/task-verify-broken-fbx.log
[udeaVerifyConvertedModels] 1 committed .glb model(s) not current
[udeaVerifyConvertedModels] error UDEA0039 moba/game/assets/models/human.udea.kts:5:1 model `models/human` names `models/human/Human.fbx`, which could not be read as FBX: FBX-Tokenize (offset 0x3bd1) block offset is out of range
BUILD FAILED in 30s
```

Both source files were restored and `git status --short -uall` was empty afterwards
(`tasks-final-gitstatus.txt`, zero bytes).

**Every `.fbx` the build can convert is covered, with controls** (spliced whole from `fbx-census.txt`):

```
### Every .fbx git tracks (the only files the build can convert), 2026-09-22T18:22:15+00:00
hollow/game/assets/models/human/Human.fbx
moba/game/assets/models/human/Human.fbx
udea-assets-compiler/src/test/resources/fbx/bender/Bender.fbx
exit=0

### Positive control: the same search, narrowed to a file I know is tracked
hollow/game/assets/models/human/Human.fbx
moba/game/assets/models/human/Human.fbx
exit=0

### Negative control: a pattern that must find nothing
(above is empty; exit=0)

### Every model(...) declaration naming an .fbx, in every .udea.kts the build scans
hollow/game/assets/models/human.udea.kts:5:model(name = "human", file = "models/human/Human.fbx")
moba/game/assets/models/human.udea.kts:5:model(name = "human", file = "models/human/Human.fbx")
exit=0

### Positive control for that grep: a .glb declaration exists too
hollow/game/assets/models/fox.udea.kts
hollow/game/assets/models/nature.udea.kts
moba/game/assets/models/fox.udea.kts

### Each .fbx and the .glb committed beside it
hollow/game/assets/models/human/Human.fbx -> hollow/game/assets/models/human/Human.glb : tracked, 1068752 bytes
moba/game/assets/models/human/Human.fbx -> moba/game/assets/models/human/Human.glb : tracked, 1068752 bytes
udea-assets-compiler/src/test/resources/fbx/bender/Bender.fbx -> udea-assets-compiler/src/test/resources/fbx/bender/Bender.glb : tracked, 19844 bytes
```

The `.fbx` fixture in `udea-assets-compiler` is the third, and it carries its own committed `.glb`
too — `CommittedModelsTest > the fixture's committed glb is the conversion of the fbx beside it` is
what keeps it current.

---

## 4b. The gate is not fooled by Gradle's up-to-date check

The trap this project punishes: a gate that goes `UP-TO-DATE` and reports a pass about a tree it
never looked at. `sources` on `UdeaAssetTask` is `project.files(extension.assetRoots).asFileTree`,
declared `@InputFiles`, so the `.fbx`, its texture and the committed `.glb` are all inputs. Measured
rather than reasoned — a known negative (nothing changed, so it must not run) followed by a known
positive (one byte flipped, so it must run **and** fail), with **no `--rerun-tasks` anywhere**
(`uptodate.txt`, `uptodate.marker` `EXIT=0` at 19:01:50):

```
### pass 1: clean tree, the gate runs
> Task :moba:game:udeaVerifyConvertedModels UP-TO-DATE
BUILD SUCCESSFUL in 6s

### pass 2: same command again, nothing changed - Gradle should call it UP-TO-DATE
> Task :moba:game:udeaVerifyConvertedModels UP-TO-DATE
BUILD SUCCESSFUL in 5s

### now flip one byte of the texture, and run the SAME command with no --rerun-tasks
### pass 3
> Task :moba:game:udeaVerifyConvertedModels FAILED
[udeaVerifyConvertedModels] 1 committed .glb model(s) not current
[udeaVerifyConvertedModels] error UDEA0039 moba/game/assets/models/human.udea.kts:5:1 model `models/human` names `models/human/Human.fbx`, which no longer converts to the `models/human/Human.glb` committed beside it (1068752 bytes converted, 1068752 committed), so the .fbx or a texture it names changed after the .glb was written: run `./gradlew udeaWriteConvertedModels` on Linux x86_64 and commit the .glb it writes
Execution failed for task ':moba:game:udeaVerifyConvertedModels'.
> udeaVerifyConvertedModels failed: the asset pipeline exited 1. The diagnostics above name the file, the line and the column.
BUILD FAILED in 6s

### tree restored:
(empty = restored)
```

---

## 5. `sh gradlew build`, no exclusions

Run through the shared full-lane lock, `--continue --no-daemon --max-workers=4
--no-configuration-cache --no-build-cache`. Marker `fullbuild1.marker`:

```
QUEUED 2026-09-22T18:37:39+00:00 /srv/ssd1/workspace/Udea/.claude/worktrees/agent-a550cbab51126c358 fullbuild1
START 2026-09-22T18:48:40+00:00 /srv/ssd1/workspace/Udea/.claude/worktrees/agent-a550cbab51126c358 fullbuild1
EXIT=0 2026-09-22T18:58:46+00:00 /srv/ssd1/workspace/Udea/.claude/worktrees/agent-a550cbab51126c358 fullbuild1
```

Tail of `fullbuild1.log`:

```
$ tail -2 /tmp/claude-1000/-srv-ssd1-workspace-Udea/1ad8c5e6-2def-4055-91d2-72acdfe77daf/scratchpad/dev-244glbc/fullbuild1.log
BUILD SUCCESSFUL in 10m 5s
1124 actionable tasks: 1092 executed, 32 up-to-date
```

`grep -cE FAILED fullbuild1.log`:

```
$ grep -cE FAILED /tmp/claude-1000/-srv-ssd1-workspace-Udea/1ad8c5e6-2def-4055-91d2-72acdfe77daf/scratchpad/dev-244glbc/fullbuild1.log
0
```

The lines in that log that this branch is about:

```
$ grep -E 'udeaVerifyConvertedModels\]|udeaPackBundle\]|Task :(moba|hollow):game:udeaVerifyConvertedModels' /tmp/claude-1000/-srv-ssd1-workspace-Udea/1ad8c5e6-2def-4055-91d2-72acdfe77daf/scratchpad/dev-244glbc/fullbuild1.log
[udeaPackBundle] assets.udeapak: 163 asset(s), 38 sheet(s), 1 atlas page(s), 114753 bytes; 1 committed .glb model(s) published
[udeaPackBundle] assets.udeapak: 27 asset(s), 0 sheet(s), 0 atlas page(s), 20613 bytes; 1 committed .glb model(s) published
> Task :hollow:game:udeaVerifyConvertedModels UP-TO-DATE
> Task :moba:game:udeaVerifyConvertedModels
[udeaVerifyConvertedModels] 1 committed .glb model(s) current: models/human/Human.glb
```

`:hollow:game:udeaVerifyConvertedModels` reads `UP-TO-DATE` in that log — correctly, because it had
already run against these exact inputs at 18:41; §4b is the measurement that up-to-date means what
it says here. `git status --short -uall` after the build is **empty**.

**GL.** This ticket touches no GL: nothing in the diff is in `udea-render`, `udea-editor` or the
render half of `udea-agent-host`. A GL run is therefore not evidence about this change and I do not
claim one. CI's `gl tests (xvfb)` job is green on this branch, and `:moba:desktop:runModelShot`
(§6) did open a real Kool context under Xvfb with llvmpipe, which is where the pictures come from.

**`udeaDaemonBudget`** passed inside the full build; it was not re-run alone because it did not
fail.

---

## 6. The images

In `/srv/ssd1/workspace/Udea/build/debug-screenshots/`, from `:moba:desktop:runModelShot`
(`shot2.marker`, light lane, `EXIT=0` at 19:00:44, 24s), which draws `GameAssets.models.human` —
the model the *bundle* names — from the committed `.glb`, and the fox beside it from the asset root.

| File | What it shows, and what it proves |
|---|---|
| `issue244-human-clips-from-committed-glb.png` | Fifteen tiles, each a labelled tick: idle at t0, the blend into walk at t30, walk, the walk-to-run blend at t90/94/98, run, punch at t120–155, and back to idle at t192. It proves the character still draws, is still skinned, still textured, and still has all four clips — now out of a file the build never converted. |
| `issue244-human-turn-from-committed-glb.png` | The same model through four yaws, so the mesh is checked from more than one side. |
| `issue244-human-from-committed-glb.png` | One frame, the human beside the fox for scale. |

The dashboard service was refusing connections while I was finishing (the lead confirmed it was
down for it too), so these are on disk and in the gallery; I will post them when it returns.

---

## 7. Windows CI — the proof this ticket exists for

Two runs on this branch, both on GitHub's real `windows-latest` runner.

**Run 35766973519** (`72751d0e`) is where the Windows replay legs first came back green. The
`replay-equality (join)` step, filtered by the command in the block's first line:

```
$ sed 's/^.*Z //' ci-35766973519-replay-equality--join-.log | grep -E 'replay-equality over 3|replay equality holds|^  (fixture|A = |B = |ubuntu-latest|windows-latest)'
replay-equality over 3 leg(s) of 'moba-3600.udearep', 3600 tick(s) from t1
  ubuntu-latest/corretto-17  [Linux amd64; Amazon.com Inc. OpenJDK 64-Bit Server VM 21.0.12.1]
  ubuntu-latest/temurin-17  [Linux amd64; Eclipse Adoptium OpenJDK 64-Bit Server VM 21.0.12.1]
  windows-latest/temurin-17  [Windows Server 2025 amd64; Eclipse Adoptium OpenJDK 64-Bit Server VM 21.0.12.1]
replay equality holds: 3600 tick(s) of 'moba-3600.udearep' are cell-for-cell identical
  fixture moba-3600.udearep
  A = 'ubuntu-latest/corretto-17'  [Linux amd64; Amazon.com Inc. OpenJDK 64-Bit Server VM 21.0.12.1]
  B = 'ubuntu-latest/temurin-17'  [Linux amd64; Eclipse Adoptium OpenJDK 64-Bit Server VM 21.0.12.1]
replay equality holds: 3600 tick(s) of 'moba-3600.udearep' are cell-for-cell identical
  fixture moba-3600.udearep
  A = 'ubuntu-latest/corretto-17'  [Linux amd64; Amazon.com Inc. OpenJDK 64-Bit Server VM 21.0.12.1]
  B = 'windows-latest/temurin-17'  [Windows Server 2025 amd64; Eclipse Adoptium OpenJDK 64-Bit Server VM 21.0.12.1]
```

That run also had **four red jobs, and all four were one defect of mine** — the wiki paths, §2 —
which failed `udeaVerifyWiki` and `:build-logic:test` identically on both operating systems:

```
$ grep -E 'udeaVerifyWiki FAILED|WikiCheckTest|tests completed|build-logic:test FAILED' /tmp/claude-1000/-srv-ssd1-workspace-Udea/1ad8c5e6-2def-4055-91d2-72acdfe77daf/scratchpad/dev-244glbc/ci-35766973519-build-windows.log | sed 's/^.*Z //'
> Task :udeaVerifyWiki FAILED
WikiCheckTest > the committed wiki points only at things that exist() FAILED
    org.opentest4j.AssertionFailedError at WikiCheckTest.kt:293
398 tests completed, 1 failed
> Task :build-logic:test FAILED
```

That leg never reached the moba replay tests, so run 35766973519 says **nothing** about
`MobaReplayEqualityTest` on Windows. The absence, with a positive control beside it so an empty
result cannot be mistaken for a broken search:

```
$ grep -cE 'Task :moba:desktop:test|MobaReplay' ci-35766973519-build-windows.log
0
$ grep -cE 'Task :hollow:game:' ci-35766973519-build-windows.log   # positive control: the same grep finds a task line that IS there
2
```

Run 35768488160, on `91f9dd90`, is the one that does say something, because those tests run inside
`build`.

**Run 35768488160** (`91f9dd90`) is the complete picture. Green on it, among others:
`replay-equality (windows-latest, temurin)`, `replay-equality (join)`, both Windows `determinism`
legs, both `latency budgets` legs, `gl tests (xvfb)`, `a game outside this repository`,
`agent brief matches the tree`, `build (ubuntu-latest)` and `build with the K2 plugin disabled`.
Exactly one job is red, and it is the next paragraph.

**`build (windows-latest)` is still red, and it is not this change.** It is red on `master` too, at
`483cb10e`, which is this branch's merge base — CI run 35760192642. What this change did is take
three failures down to one, and the two it removed are exactly the two the ticket names. Both runs'
own uploaded test reports, read the same way:

```
$ gh run download <run> -n test-reports-windows-latest
$ then, for every suite index.html whose failure counter is non-zero, its 'Failed tests' list

--- build (windows-latest), master 483cb10e, CI run 35760192642
  MobaReplayEqualityTest > the checked-in gate fixture is regenerable, input for input()
  MobaReplayFixturesCurrentTest > every checked-in moba replay fixture can be replayed by this build()
  MobaShaderAssetTest > the packed bundle holds the GLSL of the real frag file, byte for byte()[jvm]
  (3 failing test(s); the jvmTest suite is skipped here because allTests carries the same ones)

--- build (windows-latest), branch 91f9dd90, CI run 35768488160
  MobaShaderAssetTest > the packed bundle holds the GLSL of the real frag file, byte for byte()[jvm]
  (1 failing test(s); the jvmTest suite is skipped here because allTests carries the same ones)
```

`replay-equality (windows-latest, temurin)` tells the same story from the other side: **failure** on
master's run 35760192642, **success** on both of this branch's runs.

And the two tests are proved **passed**, not merely absent from a list of failures — the whole
`moba:desktop` suite ran on that Windows runner:

```
$ from the branch's own test-reports-windows-latest artefact (CI run 35768488160), the moba:desktop
$ suite index and the two replay classes' own report pages
moba:desktop suite: tests=114 failures=0 ignored=0
  MobaReplayEqualityTest:
    passed   0.004s   a comment naming a fixture is not a job running one()
    passed   15.273s  a planted one-ulp divergence is caught and names a real moba component and field()
    passed   0.068s   every fixture the workflow names is one this game has()
    passed   0.022s   the build script fence reads what the compiler reads, not what is switched off()
    passed   1.506s   the checked-in gate fixture is regenerable, input for input()
    passed   0.016s   the digest task tells its entry point which directory the workspace is()
    passed   0.040s   the gate replays the short recording and the nightly the long one()
    passed   0.019s   the proof task plants at the tick this game declares()
    passed   14.855s  two honest legs of the gate fixture agree cell for cell()
  MobaReplayFixturesCurrentTest:
    passed   0.087s   every checked-in moba replay fixture can be replayed by this build()
```

**The one that is left is a third Windows defect, and I can name its line.** It is not the asset
hash and not a replay: `MobaShaderAssetTest` compares the packed GLSL against the `.frag` file **as
the test itself reads it**, and the `windows-crlf-shaders` fix normalised only the first of those
two. `ShaderSources.kt:131` reads
`Read(UdeaDeclarationScanner.normalizeLineEndings(file.readText()), failure = null)`, while
`MobaShaderAssetTest.kt:38` passes a bare `shaderFile().readText()` to `assertEquals`. On a Windows
checkout that file has CRLF and the packed text has LF, so the two cannot be equal. The remedy is
one line — put the same `normalizeLineEndings` round the test's read.

I have **not** made that change, and it is not mine to make: it is **already fixed on
`windows-launch`, commit `1d42304b`** - "MobaShaderAssetTest: compare the frag with its line endings
made LF, as the pack makes them", which turns `shaderFile().readText()` into
`shaderFile().readText().replace("\r\n", "\n").replace('\r', '\n')`. That developer and I reached
the same line by different routes, on different days, with no contact between us. **So
`MobaShaderAssetTest` is a known Windows red belonging to `windows-launch`, not to this branch, and
there is nothing here for a reviewer to chase.**

Note what this section cannot say: the HTML test report has already collapsed the expected and
actual strings to the same 1358 characters, 23 LF and 0 CR each (`shader-crlf.txt`), so **the CRLF
diagnosis is read off those two source lines, not measured from that artefact.**

---

## 6. The issue, criterion by criterion

The criteria are the lead's "Found 2026-09-21" decision on #244 plus the ticket text given to me.

| Criterion | Proof |
|---|---|
| Commit the converted `.glb` beside its `.fbx` | `moba/game/assets/models/human/Human.glb` and `hollow/game/assets/models/human/Human.glb`, both tracked, both `1068752` bytes, both `c32a968…` — the census in §4 |
| A writer task regenerates it on purpose | `udeaWriteConvertedModels`, §4's transcript on both games; `CommittedModelsTest > the writer puts the fbx's conversion beside it…` and `> the writer replaces a stale glb` |
| The asset build packs the committed `.glb` and does not convert | `CommittedModelsTest > the pack publishes the committed glb byte for byte` (red under M1); `> an fbx model's clips are read from the glb committed beside it, not from a conversion` (red under M2); `FbxModelAssetTest > the validator does not convert, so a broken fbx beside a sound glb validates clean` |
| A check that the committed `.glb` is current, on Linux only, skipped elsewhere with a stated reason | `udeaVerifyConvertedModels`, §4; `CommittedModelsTest > the check is skipped on every other platform, and says why` (red under M3); the real skip line on Windows in §7 |
| When the `.fbx` changed and the `.glb` was not regenerated, it fails, naming the writer | §4's two failing transcripts, both `EXIT=1`, both naming `./gradlew udeaWriteConvertedModels`; `CommittedModelsTest > a source changed after the glb was written…` (red under M4); `> the check fails a missing glb rather than passing it` |
| A broken `.fbx` still fails with `UDEA0039` | §4's broken-`.fbx` transcript; `CommittedModelsTest > a broken fbx fails the writer and the check with UDEA0039, and the writer leaves the glb alone` |
| It works the same for a game outside this repo, through `dev.wildware.udea.assets` | `UdeaAssetsPluginTest > the pipeline registers every task it promises` and `> check verifies the committed glb files and never writes them` — both drive a **separate real Gradle build** that applies the plugin by id (red under M5); and CI's `a game outside this repository` job, green |
| Update `docs/new-game.md` (FBX paragraph only), the wiki, and AGENTS.md's FBX sentence | `docs/new-game.md` gains one bullet in "Stated rather than implied" and nothing else; `docs/wiki/Assets.md`, `docs/wiki/Models-and-Animation.md`, `docs/wiki/Example-Games.md`; AGENTS.md's FBX sentence. `udeaVerifyAgentsMd udeaVerifyTrelloMap udeaVerifyWiki` green locally and in CI's `agent brief matches the tree` |
| Cover every `.fbx` the build converts, with a positive control finding `Human.fbx` | The census in §4: three tracked `.fbx`, each with its `.glb`, plus a positive and a negative control |
| Predictions frozen, predicted against measured | §3, against `scratchpad/dev-244glbc/predictions.md` |
| Push and read a Windows CI run | §7 — two runs read, and the before/after taken out of master's run as well, so the claim is a comparison rather than an assertion |

---

## 8. Regenerated files

**None moved, and that is the point.** The committed `.glb` *is* today's Linux conversion, so the
asset graph hash is what it always was on Linux. `git diff --stat 483cb10e..HEAD` over
`udea-codegen/net-protocol.lock`, `udea-codegen/src/test/resources/expected-generated-hashes.txt`,
`net-components.lock`, `moba/**/*.udearep` and
`moba/desktop/src/test/resources/levels/test_level.roster.txt` is empty. No `@Replicated` component
was added or removed, so no component id moved.

---

## 8b. Out of scope, for the lead to place

`MobaShaderAssetTest > the packed bundle holds the GLSL of the real frag file, byte for byte` fails
on `windows-latest` on this branch **and on `master`**. §7 names the two lines and the one-line
remedy. It is in `moba:game`'s shader tests, nothing this branch touches. **It is already fixed on
`windows-launch`, commit `1d42304b`**, reached independently by that branch's developer; it merges
with that branch and there is nothing here for a reviewer to chase.

---

## 9. What I did not exercise, and what this evidence does not say

- **The skip branch on macOS.** `CommittedModelsTest` covers `Mac OS X aarch64` and `Linux aarch64`
  as data; only the Windows skip has been seen on a real runner (§7). No macOS runner in this
  repository runs an asset build.
- **The writer on Windows.** `AssetPipelineCli.writeModels` prints a warning when run off the
  reference platform. That branch is covered by no test and has not been run: it is a message, and
  running it would mean committing a Windows conversion, which is the thing this branch exists to
  prevent.
- **A second `.fbx` in one game.** Every game here has exactly one. `CommittedModels.fbxModels`
  sorts by id and `write`/`verify` loop, and `CommittedModelsTest > a model that names a glb is
  none of the writer's business` pins the non-`.fbx` case, but no test has two `.fbx` models in one
  root.
- **`git status` staying clean is a claim about this box's git, not about every checkout.** A
  `.glb` holds NUL bytes, so git treats it as binary and never translates line endings; the
  existing `.glb` models (the Fox, Kenney's props) already cross to Windows CI unchanged, which is
  why the lead's cross-platform probe found the 69 differing bytes only in the human's node region.
  §7 is the direct measurement for `Human.glb` itself.
- **iOS** is not claimed: `compileTestKotlinIosArm64` runs here, but no iOS test was run on this
  Linux box. CI's `iOS simulator tests` job is green on this branch.

---

## 10. How to check this brief

Every fenced block in this file is spliced from a file that is on disk right now, under
`scratchpad/dev-244glbc/` (and its `sources/` copy). Where a block is a filtered extract rather
than a whole file, its **first line is the command that produced it**, so the same bytes can be
made again from the log beside it.

I checked that mechanically rather than by eye: a script reads every fenced block out of this file
and requires it to appear as a **consecutive, in-order run** in one of those artefacts. Every block but one is placed; the one it cannot place is §1's evidence command,
which is a command to run rather than a transcript. The check is only worth anything if it can fail, so I ran the known negative too —
changing one character of `BUILD SUCCESSFUL in 10m 5s` to `9m 59s` moved the count from one
unplaceable block to two.
