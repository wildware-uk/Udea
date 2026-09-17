ac6cc30

# BRIEF-206: udea-replay on Kotlin Multiplatform

`ac6cc30` is the last commit of the change on `issue-206-replay-kmp` (branched from `origin/kmp` at
`07eddef`). This file is committed after it and changes nothing else.

## 1. Evidence command

```
sh gradlew :udea-replay:jvmTest :udea-replay:testAndroidHostTest :udea-replay:wasmJsNodeTest --continue --console=plain
```

It runs the shared `.udearep` tests (`ReplayFormatTest`, `ReplayByteCompatibilityTest`) on all three
targets, and on the JVM also `Crc32ParityTest`, `MasterRecordingReplayTest` and the rest of the
module's existing suite. Green at `ac6cc30` (log `dev206/evidence-ac6cc30.log`, run with
`--rerun-tasks`):

```
BUILD SUCCESSFUL in 1m 4s
101 actionable tasks: 101 executed
```

Suites and counts from that run's reports (`dev206/testsuites-ac6cc30.txt`, a `grep -o` over
`udea-replay/build/test-results/*/*.xml`):

```
jvmTest/TEST-dev.wildware.udea.replay.Crc32ParityTest.xml:<testsuite name="dev.wildware.udea.replay.Crc32ParityTest" tests="1" skipped="0" failures="0" errors="0"
jvmTest/TEST-dev.wildware.udea.replay.equality.DriftPilotTest.xml:<testsuite name="dev.wildware.udea.replay.equality.DriftPilotTest" tests="1" skipped="0" failures="0" errors="0"
jvmTest/TEST-dev.wildware.udea.replay.equality.ReplayBisectGuideTest.xml:<testsuite name="dev.wildware.udea.replay.equality.ReplayBisectGuideTest" tests="9" skipped="0" failures="0" errors="0"
testAndroidHostTest/TEST-dev.wildware.udea.replay.ReplayFormatTest.xml:<testsuite name="dev.wildware.udea.replay.ReplayFormatTest" tests="12" skipped="0" failures="0" errors="0"
jvmTest/TEST-dev.wildware.udea.replay.ReplayEngineTest.xml:<testsuite name="dev.wildware.udea.replay.ReplayEngineTest" tests="7" skipped="0" failures="0" errors="0"
wasmJsNodeTest/TEST-dev.wildware.udea.replay.ReplayFormatTest.xml:<testsuite name="dev.wildware.udea.replay.ReplayFormatTest" tests="12" skipped="0" failures="0" errors="0"
jvmTest/TEST-dev.wildware.udea.replay.equality.CrossPlatformDivergenceTest.xml:<testsuite name="dev.wildware.udea.replay.equality.CrossPlatformDivergenceTest" tests="4" skipped="0" failures="0" errors="0"
jvmTest/TEST-dev.wildware.udea.replay.equality.DivergenceReportFormatTest.xml:<testsuite name="dev.wildware.udea.replay.equality.DivergenceReportFormatTest" tests="9" skipped="0" failures="0" errors="0"
testAndroidHostTest/TEST-dev.wildware.udea.replay.ReplayByteCompatibilityTest.xml:<testsuite name="dev.wildware.udea.replay.ReplayByteCompatibilityTest" tests="3" skipped="0" failures="0" errors="0"
jvmTest/TEST-dev.wildware.udea.replay.fixture.ReplayFixtureUpdateTest.xml:<testsuite name="dev.wildware.udea.replay.fixture.ReplayFixtureUpdateTest" tests="10" skipped="0" failures="0" errors="0"
jvmTest/TEST-dev.wildware.udea.replay.ReplayByteCompatibilityTest.xml:<testsuite name="dev.wildware.udea.replay.ReplayByteCompatibilityTest" tests="3" skipped="0" failures="0" errors="0"
jvmTest/TEST-dev.wildware.udea.replay.equality.ReplayDigestTest.xml:<testsuite name="dev.wildware.udea.replay.equality.ReplayDigestTest" tests="8" skipped="0" failures="0" errors="0"
jvmTest/TEST-dev.wildware.udea.replay.ReplayFormatTest.xml:<testsuite name="dev.wildware.udea.replay.ReplayFormatTest" tests="12" skipped="0" failures="0" errors="0"
wasmJsNodeTest/TEST-dev.wildware.udea.replay.ReplayByteCompatibilityTest.xml:<testsuite name="dev.wildware.udea.replay.ReplayByteCompatibilityTest" tests="3" skipped="0" failures="0" errors="0"
jvmTest/TEST-dev.wildware.udea.replay.MasterRecordingReplayTest.xml:<testsuite name="dev.wildware.udea.replay.MasterRecordingReplayTest" tests="1" skipped="0" failures="0" errors="0"
jvmTest/TEST-dev.wildware.udea.replay.equality.ReplayEqualityProofTest.xml:<testsuite name="dev.wildware.udea.replay.equality.ReplayEqualityProofTest" tests="20" skipped="0" failures="0" errors="0"
jvmTest/TEST-dev.wildware.udea.replay.fixture.ReplayFixturesCurrentTest.xml:<testsuite name="dev.wildware.udea.replay.fixture.ReplayFixturesCurrentTest" tests="2" skipped="0" failures="0" errors="0"
jvmTest/TEST-dev.wildware.udea.replay.ReplayToolTest.xml:<testsuite name="dev.wildware.udea.replay.ReplayToolTest" tests="8" skipped="0" failures="0" errors="0"
jvmTest/TEST-dev.wildware.udea.replay.equality.ReplayEqualityPathsTest.xml:<testsuite name="dev.wildware.udea.replay.equality.ReplayEqualityPathsTest" tests="9" skipped="0" failures="0" errors="0"
```

**It goes red when the feature is reverted.** Paths below are under
`/tmp/claude-1000/-srv-ssd1-workspace-Udea/6f6b1984-74b9-4a3a-add9-13e80864ffe9/scratchpad/`.
Each excerpt is `grep -E "FAILED$|tests completed|^BUILD"` (M2: `"^e: |FAILED$|^BUILD"`) over the
saved log. Each mutation was reverted with `git checkout <file>` afterwards.

**M2 - put `java.util.zip` back in common code** (the revert of the ticket's decision). JVM and
Android still compile it; Wasm does not:

```diff
diff --git a/udea-replay/src/commonMain/kotlin/dev/wildware/udea/replay/ReplayFormat.kt b/udea-replay/src/commonMain/kotlin/dev/wildware/udea/replay/ReplayFormat.kt
index 2515fd6..da38e21 100644
--- a/udea-replay/src/commonMain/kotlin/dev/wildware/udea/replay/ReplayFormat.kt
+++ b/udea-replay/src/commonMain/kotlin/dev/wildware/udea/replay/ReplayFormat.kt
@@ -109,11 +109,9 @@ public object ReplayFormat {
      * no compression to replace, only this checksum. `Crc32ParityTest` holds it to the JDK's.
      */
     public fun crc32(bytes: ByteArray, end: Int): Long {
-        var crc = -1
-        for (index in 0 until end) {
-            crc = CRC_TABLE[(crc xor bytes[index].toInt()) and 0xFF] xor (crc ushr 8)
-        }
-        return crc.inv().toLong() and 0xFFFF_FFFFL
+        val crc = java.util.zip.CRC32()
+        crc.update(bytes, 0, end)
+        return crc.value
     }
 
     /** The byte-at-a-time table for [crc32]: entry `n` is the remainder of `n` shifted through eight bits. */
```

```
> Task :udea-replay:compileKotlinWasmJs FAILED
e: file:///srv/ssd1/workspace/Udea/.claude/worktrees/agent-a32c9ac76b37a1e7a/udea-replay/src/commonMain/kotlin/dev/wildware/udea/replay/ReplayFormat.kt:112:19 Unresolved reference 'java'.
e: file:///srv/ssd1/workspace/Udea/.claude/worktrees/agent-a32c9ac76b37a1e7a/udea-replay/src/commonMain/kotlin/dev/wildware/udea/replay/ReplayFormat.kt:112:33 Unresolved reference 'CRC32'.
BUILD FAILED in 10s
```

**M1 - the real shape of a hand-written CRC bug: the final complement dropped.** Every target fails
the byte-compatibility tests, and the JVM fails the parity test, the master-recording replay and
every test that decodes a checked-in fixture:

```diff
diff --git a/udea-replay/src/commonMain/kotlin/dev/wildware/udea/replay/ReplayFormat.kt b/udea-replay/src/commonMain/kotlin/dev/wildware/udea/replay/ReplayFormat.kt
index 2515fd6..3982cf7 100644
--- a/udea-replay/src/commonMain/kotlin/dev/wildware/udea/replay/ReplayFormat.kt
+++ b/udea-replay/src/commonMain/kotlin/dev/wildware/udea/replay/ReplayFormat.kt
@@ -113,7 +113,7 @@ public object ReplayFormat {
         for (index in 0 until end) {
             crc = CRC_TABLE[(crc xor bytes[index].toInt()) and 0xFF] xor (crc ushr 8)
         }
-        return crc.inv().toLong() and 0xFFFF_FFFFL
+        return crc.toLong() and 0xFFFF_FFFFL
     }
 
     /** The byte-at-a-time table for [crc32]: entry `n` is the remainder of `n` shifted through eight bits. */
```

```
dev.wildware.udea.replay.ReplayByteCompatibilityTest.the checksum is CRC-32 as zip and every existing recording computes it[wasmJs, node] FAILED
dev.wildware.udea.replay.ReplayByteCompatibilityTest.a recording encodes to the bytes master's build wrote[wasmJs, node] FAILED
dev.wildware.udea.replay.ReplayByteCompatibilityTest.a recording master's build wrote decodes, and every sample and hash is the one recorded[wasmJs, node] FAILED
> Task :udea-replay:wasmJsNodeTest FAILED
15 tests completed, 3 failed
> Task :udea-replay:testAndroidHostTest FAILED
ReplayByteCompatibilityTest > a recording encodes to the bytes master's build wrote FAILED
ReplayByteCompatibilityTest > the checksum is CRC-32 as zip and every existing recording computes it FAILED
ReplayByteCompatibilityTest > a recording master's build wrote decodes, and every sample and hash is the one recorded FAILED
15 tests completed, 3 failed
> Task :udea-replay:jvmTest FAILED
Crc32ParityTest[jvm] > the common checksum agrees with java util zip on every length and prefix()[jvm] FAILED
MasterRecordingReplayTest[jvm] > master's recording replays bit-exactly on this build()[jvm] FAILED
ReplayByteCompatibilityTest[jvm] > a recording encodes to the bytes master's build wrote()[jvm] FAILED
ReplayByteCompatibilityTest[jvm] > the checksum is CRC-32 as zip and every existing recording computes it()[jvm] FAILED
ReplayByteCompatibilityTest[jvm] > a recording master's build wrote decodes, and every sample and hash is the one recorded()[jvm] FAILED
CrossPlatformDivergenceTest[jvm] > with the plant off, two runs of the fixture are cell-for-cell identical()[jvm] FAILED
CrossPlatformDivergenceTest[jvm] > a planted one-ulp divergence fails the comparison and names tick, entity, component and field()[jvm] FAILED
CrossPlatformDivergenceTest[jvm] > the plant is the smallest change a float can carry, not a visible one()[jvm] FAILED
CrossPlatformDivergenceTest[jvm] > the checked-in fixture is the one this test claims to replay()[jvm] FAILED
ReplayEqualityPathsTest[jvm] > a real leg writes a readable stream at the resolved path and says where it went()[jvm] FAILED
ReplayEqualityProofTest[jvm] > the checked-in self-test fixture is regenerable, input for input()[jvm] FAILED
ReplayFixturesCurrentTest[jvm] > the nightly fixture is the length the nightly job asks for()[jvm] FAILED
ReplayFixturesCurrentTest[jvm] > every checked-in replay fixture can be replayed by this build()[jvm] FAILED
104 tests completed, 13 failed
BUILD FAILED in 11s
```

**M3 - a build that simulates differently from the one that recorded.** Run as
`:udea-replay:jvmTest --tests dev.wildware.udea.replay.MasterRecordingReplayTest`; it fails at
`MasterRecordingReplayTest.kt:36`, the bit-exact assertion, not at the SHA pin above it:

```diff
diff --git a/udea-replay/src/jvmTestFixtures/kotlin/dev/wildware/udea/replay/equality/fixture/DriftWorld.kt b/udea-replay/src/jvmTestFixtures/kotlin/dev/wildware/udea/replay/equality/fixture/DriftWorld.kt
index 119c514..ac67eed 100644
--- a/udea-replay/src/jvmTestFixtures/kotlin/dev/wildware/udea/replay/equality/fixture/DriftWorld.kt
+++ b/udea-replay/src/jvmTestFixtures/kotlin/dev/wildware/udea/replay/equality/fixture/DriftWorld.kt
@@ -353,7 +353,7 @@ public class DriftWorld(
         private const val ID_CAPACITY: Int = 1024
         private const val LEAD_X: Float = 1.5f
         private const val LEAD_Y: Float = -2.25f
-        private const val LEAD_ENERGY: Float = 3.5f
+        private const val LEAD_ENERGY: Float = 3.25f
         private const val FOLLOWER_SPACING: Float = 1.25f
         private const val FOLLOWER_HEADING: Float = 0.37f
         private const val FOLLOWER_ENERGY: Float = 0.4f
```

```
> Task :udea-replay:jvmTest FAILED
MasterRecordingReplayTest[jvm] > master's recording replays bit-exactly on this build()[jvm] FAILED
BUILD FAILED in 23s
```

## 2. Summary

**Layout.** `udea-replay` applies `udea.kotlin-multiplatform-no-ios` (jvm, android, wasmJs) plus
`udea.jvm-test-fixtures`, the way `udea-core` does after #203.

- `commonMain`: `ReplayFormat` (with `ByteSink`/`ByteSource`), `ReplayRecording` (encode/decode),
  `ReplayHeader`, `InputSchema`, `InputSample`, `ReplayRecorder`, `ReplayWorld`, `ReplaySession`,
  `ReplayVerifier`, and a new internal `Hex` for the refusal messages (`String.format` is JVM-only).
- `jvmMain`: `equality/` (cross-OS digest with gzip, the join, the `main`s), `fixture/`,
  `tools/` (the `replay.*` agent tools), and `ReplayRecordingFiles.kt`.
- `jvmTest` / `jvmTestFixtures`: the existing tests and the drift fixture world, moved unchanged
  apart from paths. `ReplayFormatTest` moved to `commonTest` byte-for-byte (`R100`).

**Decision 1 - `java.util.zip` (commented on #206).** `.udearep` was never compressed; the one thing
it took from `java.util.zip` is `CRC32` for the trailer. `ReplayFormat.crc32` is now a pure-Kotlin
table-driven CRC-32 (zip variant), so every target writes and checks the trailer the JVM always
wrote. Gzip exists only in the `.udeaeq` cross-OS digest, which is verification and stays JVM-only
(D3). Rejected: a compression/checksum library (a new dependency for a 20-line function), and an
`expect`/`actual` delegating to `java.util.zip` on the JVM (two implementations, the unproven one
on the non-JVM targets). To overturn: swap the function body; `Crc32ParityTest` and
`ReplayByteCompatibilityTest` say whether the replacement is still compatible.

**Decision 2 - what is common, and where KSP runs (commented on #206).** KSP runs on `kspJvm`, not
`kspCommonMainMetadata` as in `udea-core`. The one `@AgentTool` class is JVM-only, the processor has
no option to skip the module registry, and running it on both would put two
`UdeaReplayModuleRegistry` classes on the JVM classpath. So the (empty) module registry exists on
the JVM target only; every launcher that lists this module today is JVM. The generated output is
identical to the pre-port JVM build: `diff -r` of `build/generated/ksp/main` (the `origin/kmp`
build) against `build/generated/ksp/jvm/jvmMain` printed nothing (copies in
`dev206/ksp-baseline-main` and `dev206/ksp-port`). To overturn: after #208 makes `udea-agent`
multiplatform, move `tools/` to common and switch to `kspCommonMainMetadata`.

**`ReplayRecording.writeTo(Path)` / `ReplayRecording.readFrom(Path)`** became JVM extension
functions in `ReplayRecordingFiles.kt`, so callers outside the package import them: `tools`,
`fixture`, `ReplayFixtureUpdateTest`, and `moba`'s `MobaReplayProofTest` (import lines only).

**Paths that moved and the things that read them:** `build.gradle.kts` (fixture classpath now the
JVM test-fixtures compilation, `src/jvmMain`/`src/jvmTestFixtures` paths),
`ReplayFixturesCurrentTest`, `CrossPlatformDivergenceTest`, `ReplayEqualityPathsTest`,
`DriftFixtureRecorder` (`GRADLE_TASK` is now `:udea-replay:jvmTest`), `udea-replay/.gitattributes`,
and `udea-gradle`'s `WallClockBudgetCensusTest` (two `udea-replay/src/test` paths).

**Docs:** `AGENTS.md` (one sentence after the `udea-core` one), `docs/module-graph.md` (the no-iOS
row names `udea-replay`), and the `ios-tests` comment in `ci.yml` (why `udea-replay` is absent).
No workflow step changed: the replay-equality legs run `:moba:udeaReplayDigest` and the join runs
`:udea-replay:udeaReplayEquals`, and both task names and report paths are unchanged.

**One thing I broke and a test caught:** my first rewrite of `build.gradle.kts` truncated the file
after 300 lines, dropping the proof task. `ReplayEqualityProofTest` failed three tests on it before
anything was committed; the tail was restored from `HEAD`.

**"All four targets"** is read as `udea-core`'s targets (lead's decision on #206): jvm, android,
wasmJs. iOS is off with `udea-core` (#215). **iOS was not built or tested on this box.**

## 3. `sh gradlew build`

Baseline, `origin/kmp` `07eddef` checked out detached in this worktree
(`dev206/baseline-07eddef.log`, `grep -c FAILED` = 0):

```
BUILD SUCCESSFUL in 23s
364 actionable tasks: 20 executed, 8 from cache, 336 up-to-date
```

This branch at `ac6cc30`, `ANDROID_HOME=$HOME/Android/Sdk JAVA_HOME=.../21.0.11-tem sh gradlew build
--continue` (`dev206/build-ac6cc30.log`, `grep -c FAILED` = 0):

```
BUILD SUCCESSFUL in 25s
406 actionable tasks: 44 executed, 8 from cache, 354 up-to-date
```

Most tasks in both were up-to-date or cached from earlier runs of the same inputs on this box; the
`udea-replay` test tasks executed (`testAndroidHostTest`, `wasmJsNodeTest`, `jvmTest`, `allTests`,
`check` all appear as executed in the log).

**Tasks this ticket turned green:** `:udea-replay:compileKotlinWasmJs`, `:udea-replay:compileAndroidMain`,
`:udea-replay:wasmJsNodeTest`, `:udea-replay:testAndroidHostTest`, `:udea-replay:jvmTest`,
`:udea-replay:allTests`. None of them existed on the baseline, where the module was JVM-only.

**Baseline failures, unchanged:** none locally.

**GL:** not touched. No file in `udea-render` or `udea-agent-host` changed, so the xvfb GL run was
not needed and was not run.

## 4. Images

None. Nothing in this ticket draws: it is a source-set move and a checksum, and its evidence is
test reports and replay transcripts. No image was posted to the gallery.

## 5. Acceptance criteria

### [x] Builds for all four targets (read as jvm, android, wasmJs; see section 2)

The build above compiles and tests all three (`compileKotlinJvm`, `compileAndroidMain`,
`compileKotlinWasmJs`; tests on each). The suite list in section 1 shows the common tests reported
by `wasmJsNodeTest`, `testAndroidHostTest` and `jvmTest`. The red run that started it, before the
common code was fixed (`dev206/red-wasm.excerpt`):

```
e: file:///srv/ssd1/workspace/Udea/.claude/worktrees/agent-a32c9ac76b37a1e7a/udea-replay/src/commonMain/kotlin/dev/wildware/udea/replay/InputSchema.kt:11:2 Unresolved reference 'JvmInline'.
e: file:///srv/ssd1/workspace/Udea/.claude/worktrees/agent-a32c9ac76b37a1e7a/udea-replay/src/commonMain/kotlin/dev/wildware/udea/replay/ReplayFormat.kt:3:8 Unresolved reference 'java'.
e: file:///srv/ssd1/workspace/Udea/.claude/worktrees/agent-a32c9ac76b37a1e7a/udea-replay/src/commonMain/kotlin/dev/wildware/udea/replay/ReplayFormat.kt:106:19 Unresolved reference 'CRC32'.
e: file:///srv/ssd1/workspace/Udea/.claude/worktrees/agent-a32c9ac76b37a1e7a/udea-replay/src/commonMain/kotlin/dev/wildware/udea/replay/ReplayHeader.kt:111:65 Unresolved reference 'format' on receiver of type 'String'.
e: file:///srv/ssd1/workspace/Udea/.claude/worktrees/agent-a32c9ac76b37a1e7a/udea-replay/src/commonMain/kotlin/dev/wildware/udea/replay/ReplayHeader.kt:115:60 Unresolved reference 'format' on receiver of type 'String'.
e: file:///srv/ssd1/workspace/Udea/.claude/worktrees/agent-a32c9ac76b37a1e7a/udea-replay/src/commonMain/kotlin/dev/wildware/udea/replay/ReplayRecording.kt:4:8 Unresolved reference 'java'.
e: file:///srv/ssd1/workspace/Udea/.claude/worktrees/agent-a32c9ac76b37a1e7a/udea-replay/src/commonMain/kotlin/dev/wildware/udea/replay/ReplayRecording.kt:5:8 Unresolved reference 'java'.
e: file:///srv/ssd1/workspace/Udea/.claude/worktrees/agent-a32c9ac76b37a1e7a/udea-replay/src/commonMain/kotlin/dev/wildware/udea/replay/ReplayRecording.kt:136:30 Unresolved reference 'Path'.
e: file:///srv/ssd1/workspace/Udea/.claude/worktrees/agent-a32c9ac76b37a1e7a/udea-replay/src/commonMain/kotlin/dev/wildware/udea/replay/ReplayRecording.kt:137:14 Unresolved reference 'parent'.
e: file:///srv/ssd1/workspace/Udea/.claude/worktrees/agent-a32c9ac76b37a1e7a/udea-replay/src/commonMain/kotlin/dev/wildware/udea/replay/ReplayRecording.kt:137:22 Cannot infer type for type parameter 'T'. Specify it explicitly.
e: file:///srv/ssd1/workspace/Udea/.claude/worktrees/agent-a32c9ac76b37a1e7a/udea-replay/src/commonMain/kotlin/dev/wildware/udea/replay/ReplayRecording.kt:137:22 Cannot infer type for type parameter 'R'. Specify it explicitly.
e: file:///srv/ssd1/workspace/Udea/.claude/worktrees/agent-a32c9ac76b37a1e7a/udea-replay/src/commonMain/kotlin/dev/wildware/udea/replay/ReplayRecording.kt:137:26 Unresolved reference 'Files'.
e: file:///srv/ssd1/workspace/Udea/.claude/worktrees/agent-a32c9ac76b37a1e7a/udea-replay/src/commonMain/kotlin/dev/wildware/udea/replay/ReplayRecording.kt:138:9 Unresolved reference 'Files'.
e: file:///srv/ssd1/workspace/Udea/.claude/worktrees/agent-a32c9ac76b37a1e7a/udea-replay/src/commonMain/kotlin/dev/wildware/udea/replay/ReplayRecording.kt:173:35 Unresolved reference 'Path'.
e: file:///srv/ssd1/workspace/Udea/.claude/worktrees/agent-a32c9ac76b37a1e7a/udea-replay/src/commonMain/kotlin/dev/wildware/udea/replay/ReplayRecording.kt:173:67 Unresolved reference 'Files'.
e: file:///srv/ssd1/workspace/Udea/.claude/worktrees/agent-a32c9ac76b37a1e7a/udea-replay/src/commonMain/kotlin/dev/wildware/udea/replay/ReplayRecording.kt:192:57 Unresolved reference 'format' on receiver of type 'String'.
e: file:///srv/ssd1/workspace/Udea/.claude/worktrees/agent-a32c9ac76b37a1e7a/udea-replay/src/commonMain/kotlin/dev/wildware/udea/replay/ReplayRecording.kt:194:70 Unresolved reference 'format' on receiver of type 'String'.
e: file:///srv/ssd1/workspace/Udea/.claude/worktrees/agent-a32c9ac76b37a1e7a/udea-replay/src/commonMain/kotlin/dev/wildware/udea/replay/ReplayRecording.kt:253:59 Unresolved reference 'format' on receiver of type 'String'.
e: file:///srv/ssd1/workspace/Udea/.claude/worktrees/agent-a32c9ac76b37a1e7a/udea-replay/src/commonMain/kotlin/dev/wildware/udea/replay/ReplayRecording.kt:254:45 Unresolved reference 'format' on receiver of type 'String'.
BUILD FAILED in 8s
```

### [x] A `.udearep` recorded on `master` replays to the same hash on the new JVM build

Recorded on `master`: `git archive origin/master` (`409c044`) into `dev206/master-rec`, the
four checked-in `.udearep` files deleted, then `sh gradlew :udea-replay:udeaWriteReplayFixture
:moba:udeaWriteReplayFixture` in that tree (`dev206/master-record.log`):

```
> Task :udea-replay:udeaWriteReplayFixture
drift-3600.udearep: REGENERATED - rebuilt because it did not exist; now 3600 tick(s), 66413 bytes at /tmp/claude-1000/-srv-ssd1-workspace-Udea/6f6b1984-74b9-4a3a-add9-13e80864ffe9/scratchpad/master-rec/udea-replay/src/testFixtures/resources/fixtures/drift-3600.udearep
drift-36000.udearep: REGENERATED - rebuilt because it did not exist; now 36000 tick(s), 665794 bytes at /tmp/claude-1000/-srv-ssd1-workspace-Udea/6f6b1984-74b9-4a3a-add9-13e80864ffe9/scratchpad/master-rec/udea-replay/src/testFixtures/resources/fixtures/drift-36000.udearep

> Task :moba:udeaWriteReplayFixture
moba-3600.udearep: REGENERATED - rebuilt because it did not exist; now 3600 tick(s), 59572 bytes at /tmp/claude-1000/-srv-ssd1-workspace-Udea/6f6b1984-74b9-4a3a-add9-13e80864ffe9/scratchpad/master-rec/moba/src/test/resources/fixtures/moba-3600.udearep
moba-36000.udearep: REGENERATED - rebuilt because it did not exist; now 36000 tick(s), 590901 bytes at /tmp/claude-1000/-srv-ssd1-workspace-Udea/6f6b1984-74b9-4a3a-add9-13e80864ffe9/scratchpad/master-rec/moba/src/test/resources/fixtures/moba-36000.udearep
```

`sha256sum` before deleting (`dev206/master-fixtures-checked-in.sha256`) and after master re-recorded
them (`dev206/master-fixtures-rerecorded.sha256`):

```
040c15bd99157865bd85a9e946b8e2cf561bfb5a0a3eafc885672c99a9b7ebd1  udea-replay/src/testFixtures/resources/fixtures/drift-36000.udearep
57cc9c2fa3ca5348a6d04be00117fe89c3d26c6ff2a3bc2820cf29bed39d2a60  udea-replay/src/testFixtures/resources/fixtures/drift-3600.udearep
b150a70a916a1069f9d8eb0ec606d5b3806e2496f598d3b34c00a46232560d79  moba/src/test/resources/fixtures/moba-36000.udearep
6b8b6982664beef640f8a1114d53e6eefa45092926cd6d04909153e81d119c3d  moba/src/test/resources/fixtures/moba-3600.udearep
```

```
040c15bd99157865bd85a9e946b8e2cf561bfb5a0a3eafc885672c99a9b7ebd1  udea-replay/src/testFixtures/resources/fixtures/drift-36000.udearep
57cc9c2fa3ca5348a6d04be00117fe89c3d26c6ff2a3bc2820cf29bed39d2a60  udea-replay/src/testFixtures/resources/fixtures/drift-3600.udearep
b150a70a916a1069f9d8eb0ec606d5b3806e2496f598d3b34c00a46232560d79  moba/src/test/resources/fixtures/moba-36000.udearep
6b8b6982664beef640f8a1114d53e6eefa45092926cd6d04909153e81d119c3d  moba/src/test/resources/fixtures/moba-3600.udearep
```

Identical, and identical to the files on this branch (`git diff origin/kmp -M` lists both drift
fixtures as `R100`; the moba ones are untouched). So the checked-in files are master's recordings.

Replayed on this branch:

- **`drift-3600.udearep`**: `MasterRecordingReplayTest` pins its SHA-256 to master's recording and
  runs `ReplayVerifier` over all 3600 ticks against the recorded hash stream: bit-exact. M3 above
  shows it fail when the replaying build simulates differently.
- **`moba-3600.udearep`**, the game: the replay-equality leg below compares every tick's world hash
  with the one stored in the recording and prints a `note:` line when any differ
  (`ReplayDigestRun.describe`). Neither leg printed one: 3600 ticks, zero mismatches against
  master's hashes.

The byte format itself, on every target: `ReplayByteCompatibilityTest.MASTER_BYTES` is the hex of
what master's encoder wrote for a small recording, dumped by a scratch test run in
`dev206/master-rec` (never committed) into `dev206/master-golden.hex`; the constant was checked
against that file with `cmp` after it was written. The test encodes the same recording and decodes
master's bytes on jvm, Android host and wasmJs.

### [x] The cross-OS replay-equality CI job is green on JVM

CI triggers on every push. Run for the first commit of this branch (`63c8b77`, which holds all of
the code change; `ac6cc30` adds only the docs and a `ci.yml` comment):
https://github.com/wildware-uk/Udea/actions/runs/35161647640 - `replay-equality (ubuntu-latest,
temurin)`, `(ubuntu-latest, corretto)`, `(windows-latest, temurin)` and `replay-equality (join)`
all `success`. The run for the final push is named in my report to the lead.

The job's commands, run locally on this branch (`dev206/ci-leg-a.log`, `ci-leg-b.log`,
`ci-join.log`; only one JDK vendor exists here, so the second leg is the default toolchain rather
than Corretto):

```
> Task :moba:udeaReplayDigest
ubuntu-latest/temurin-17: replayed 3600 tick(s) in 1660ms into ubuntu-latest-temurin.udeaeq
  2326118 bytes at /srv/ssd1/workspace/Udea/.claude/worktrees/agent-a32c9ac76b37a1e7a/digests/ubuntu-latest-temurin.udeaeq
```

```
> Task :moba:udeaReplayDigest
ubuntu-latest/local-default-17: replayed 3600 tick(s) in 1770ms into ubuntu-latest-local.udeaeq
  2326119 bytes at /srv/ssd1/workspace/Udea/.claude/worktrees/agent-a32c9ac76b37a1e7a/digests/ubuntu-latest-local.udeaeq
```

```
> Task :udea-replay:udeaReplayEquals
replay-equality over 2 leg(s) of 'moba-3600.udearep', 3600 tick(s) from t1
  ubuntu-latest/local-default-17  [Linux amd64; Eclipse Adoptium OpenJDK 64-Bit Server VM 21.0.11]
  ubuntu-latest/temurin-17  [Linux amd64; Eclipse Adoptium OpenJDK 64-Bit Server VM 21.0.11]

replay equality holds: 3600 tick(s) of 'moba-3600.udearep' are cell-for-cell identical
```

Both five-process self-tests, which run on the moved fixture classpath (`dev206/proofs.log`):

```
replay-equality proof PASSED: two honest legs agree (exit 0); the planted leg fails (exit 1) naming Drifter.x at t1200, with five ticks of history.
moba replay-equality proof PASSED: two honest legs agree (exit 0); the planted leg fails (exit 1) naming Position.x at t1200, with five ticks of history.
```

Other jobs in that CI run that were not green, compared with `kmp`'s own run for `07eddef`
(https://github.com/wildware-uk/Udea/actions/runs/35160551639):

- `migration ledger` and the four `determinism` legs: red on both, the same test on both,
  `OuterBuildInputsTest > every repository file a build-logic test names is a declared input of this task()`
  (`dev206/ci-migration-*.log`, `dev206/ci-determinism-*.log`).
- `clean build under budget`: red on both. Here head/base 1.110 over the 1.100 tolerance; on `kmp`
  1.116. A port adds wasm and Android compilations to the clean build, as #203's did.
- `the FIR checkers fail a real build`: red here only, and not in `udea-replay`: the Kotlin daemon
  died starting to compile `:udea-assets` (`e: The daemon has terminated unexpectedly on startup
  attempt #1 with error code: 0. The daemon process output:`, then nothing; `dev206/ci-fir.log`). I requested a rerun of that job; its result
  is in my report.

## 6. Regenerated files

None. No replicated component was added or removed; `net-protocol.lock` and
`expected-generated-hashes.txt` are untouched.

## 7. Merge with origin/kmp

Merge commit `eece16e` brings in `origin/kmp` at `ca2d5f5` (#204, `udea-gas` on KMP). The code
under review at `310b5b9` is unchanged. Three files conflicted. Each conflict was the same
sentence, where each branch had added its own module to the no-iOS convention. Every resolution
keeps both modules:

- `.github/workflows/ci.yml`: the iOS-tests comment now says `udea-gas` (#204) and `udea-replay`
  (#206) are both left out for the same reason, through `udea-core`. It is a comment only; no step
  changed.
- `AGENTS.md`: the multiplatform paragraph names both modules on `udea.kotlin-multiplatform-no-ios`.
- `docs/module-graph.md`: the `udea.kotlin-multiplatform-no-ios` row lists `udea-core`, `udea-gas`
  and `udea-replay`, each with its reason.

Results on `eece16e`. Other developers' Gradle builds were running on the box at the same time.
Logs are in `dev206/merge-*.log`:

- `sh gradlew build --continue`: `BUILD SUCCESSFUL in 1m 24s`, `446 actionable tasks: 86 executed, 69 from cache, 291 up-to-date`.
  `:udea-replay:jvmTest`, `:udea-replay:testAndroidHostTest` and `:udea-replay:wasmJsNodeTest` all ran
  in this build; none came from the cache.
- `sh gradlew -p build-logic check --continue`: `310 tests completed, 1 failed`. The one failure is
  `OuterBuildInputsTest > every repository file a build-logic test names is a declared input of this task()`,
  the same test that fails on the baseline (#216).
- `sh gradlew udeaVerifyModuleGraph udeaVerifyNoLegacyDependencies udeaVerifyAgentsMd :udea-replay:jvmTest :udea-replay:testAndroidHostTest :udea-replay:wasmJsNodeTest`:
  `BUILD SUCCESSFUL in 29s`. The three test tasks were up to date from the build above. Their
  result files show 104 JVM, 15 Android host and 15 wasmJs tests, with 0 failures.

### Second merge: origin/kmp at `0a760f1` (#205, `udea-assets` on KMP)

Merge commit `c286c6c`. Git merged it with no conflicts. I read the three shared texts after the
merge, and each still names `udea-gas` and `udea-replay` correctly:

- the `ios-tests` comment in `ci.yml`;
- the `udea.kotlin-multiplatform-no-ios` row in `docs/module-graph.md`;
- the multiplatform paragraph in `AGENTS.md`.

`udea-assets` has iOS and joins the iOS job itself, so none of the three needed an edit.

Results on `c286c6c`, with logs in `dev206/merge2-*.log`:

- `sh gradlew build --continue`: `BUILD SUCCESSFUL in 2m 19s`, `501 actionable tasks: 286 executed, 151 from cache, 64 up-to-date`.
  The three `udea-replay` test tasks ran in this build.
- `sh gradlew -p build-logic check --continue`: `311 tests completed, 1 failed`. The one failure is
  `OuterBuildInputsTest`, the known baseline failure (#216).
- The verifiers plus the three `udea-replay` test tasks: `BUILD SUCCESSFUL in 11s`. The test result
  files show 104 JVM, 15 Android host and 15 wasmJs tests, with 0 failures.
