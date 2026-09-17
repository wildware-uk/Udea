48c857b

# BRIEF - #205: udea-assets on Kotlin Multiplatform

Branch `issue-205-assets-kmp`, off `origin/kmp` 07eddef. Two commits:

- `0e968a7` - the golden pak and what the **pre-port** JVM reader makes of it, as a JVM test on the unported module.
- `48c857b` - the port.

This file is untracked on purpose, so the SHA above is `HEAD`. Every log named below is in
`/tmp/claude-1000/-srv-ssd1-workspace-Udea/6f6b1984-74b9-4a3a-add9-13e80864ffe9/scratchpad/issue205/`,
written `$S/` from here on.

## 1. The evidence command

```
ANDROID_HOME=$HOME/Android/Sdk JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem sh gradlew :udea-assets:jvmTest :udea-assets:wasmJsNodeTest :udea-assets:testAndroidHostTest --continue
```

This is the "named test classes" row of the evidence table: a module port has no scene to screenshot.
It runs the golden (`GoldenPakTest`), the file source (`FileBundleSourceTest`), the hash (`Sha256Test`)
and every model and reader test that moved to `commonTest`, on jvm, Android host and Node. On wasmJs
it also runs `BundleFetchTest`, which fetches over real HTTP. On the JVM it also runs
`Sha256JvmCrossCheckTest`, `ResPathTest` and `NoTextureRegionSplitTest`.

On the branch, results read from the XML of the full `build` (copied to `$S/results-build-1/`):

```
jvmTest: tests=87 failures=0 errors=0 skipped=0 classes=14
wasmJsNodeTest: tests=78 failures=0 errors=0 skipped=0 classes=12
testAndroidHostTest: tests=75 failures=0 errors=0 skipped=0 classes=11
```

On `origin/kmp` (a detached worktree at 07eddef, `$S/evidence-on-origin-kmp.log`) the command is red
because the module has no such targets:

```
Cannot locate tasks that match ':udea-assets:jvmTest' as task 'jvmTest' not found in project ':udea-assets'. Some candidates are: 'test'.
```

That only shows the targets are new. The proof that the tests catch a broken port is the mutation table.
Each row is one mutation, applied by `$S/mutate.py` on 48c857b, run with the three tasks above, then reverted.
The diff is `$S/<label>.diff`, the failures `$S/<label>.failures.txt`, and the Gradle output `$S/<label>.gradle.log`.

**m1-no-reopen-behind-head.** A backwards read reuses the reader instead of reopening the file.
```
-        val current = reader?.takeIf { offset >= position } ?: reopen()
+        val current = reader ?: reopen()
```
`jvmTest: 87 tests, 3 failed`, `wasmJsNodeTest: 78 tests, 3 failed`, `testAndroidHostTest: 75 tests, 3 failed`.
On each target the failures are: `FileBundleSourceTest` "a range behind the read head is the same bytes as the first time",
"adjacent and repeated ranges - and the last byte", and `GoldenPakTest` "a pak opened from a file reads as it did before the port".

**m2-sha256-schedule-rotations.** The message-schedule rotations SHA-512 uses. This was my own first bug in the port, caught by these tests.
```
-                (s1.rotateRight(7) xor s1.rotateRight(18) xor (s1 ushr 3)) +
+                (s1.rotateRight(1) xor s1.rotateRight(8) xor (s1 ushr 3)) +
```
`jvmTest: 87 tests, 7 failed` (both `GoldenPakTest`, `Sha256JvmCrossCheckTest`, all four `Sha256Test`).
`wasmJsNodeTest: 78 tests, 7 failed` (both `GoldenPakTest`, all four `Sha256Test`, and `BundleFetchTest` "a pak fetched over HTTP reads as it did before the port").
`testAndroidHostTest: 75 tests, 6 failed` (both `GoldenPakTest`, all four `Sha256Test`).

**m3-position-forgets-length.** The read head forgets the bytes it just read.
```
-        position = offset + length
+        position = offset
```
The same three tests fail as in m1, on each target: 3 of 87, 3 of 78, 3 of 75.

**m4-http-status-ignored.** Wasm only: an HTTP error status is not checked.
```
-    if (!response.ok) {
+    if (false && !response.ok) {
```
`wasmJsNodeTest: 78 tests, 1 failed`: `BundleFetchTest` "an HTTP error is an IOException naming the URL and the status". jvm and Android: 0 failed.

**m5-printable-unpadded.** The `String.format` replacement drops its zero padding.
```
-        return if (code in 0x20..0x7E) code.toChar().toString() else "\\x" + code.toString(HEX).padStart(2, '0')
+        return if (code in 0x20..0x7E) code.toChar().toString() else "\\x" + code.toString(HEX)
```
1 failed on each target: `BundleReaderTest` "bytes that are not a bundle are refused by magic - not by version".

Build-logic mutations, run with `:build-logic:test --tests '*ModuleGraphRulesTest*'`, plus `:udea-assets:udeaVerifyModuleGraph` for m6:

**m6-mg006-without-io-target-artifacts.** Drops `kotlinx-io-core-*` from the allow list.
```
-            CoordinatePattern("org.jetbrains.kotlinx:kotlinx-io-core-*"),
```
`UDEA-MG-006 passes what the asset model is allowed, kotlinx-io included() FAILED`, `28 tests completed, 1 failed`, and on the real module `udeaVerifyModuleGraph: 10 violations`, for example `UDEA-MG-006 :udea-assets jvmCompileClasspath -> org.jetbrains.kotlinx:kotlinx-io-core-jvm`.

**m7-mg006-io-prefix-wildcard.** Widens the pattern to all of kotlinx-io.
```
-            CoordinatePattern("org.jetbrains.kotlinx:kotlinx-io-core-*"),
+            CoordinatePattern("org.jetbrains.kotlinx:kotlinx-io-*"),
```
`UDEA-MG-006 allows kotlinx-io and not the rest of kotlinx() FAILED`, `28 tests completed, 1 failed`.

**The K2 plugin on iOS.** Commented out the one line that makes the native plugin classpath transitive (`$S/mutation-native-plugin-classpath.diff`):
```
-            withDependencies { classpath.isTransitive = true }
+            // withDependencies { classpath.isTransitive = true }
```
With the `checkers-fire` probe in `udea-assets/src/commonMain`, `$S/probe-mutated.log`:
```
> Task :udea-assets:compileKotlinIosArm64 FAILED
e: org.jetbrains.kotlin.util.FileAnalysisException: While analysing /srv/ssd1/workspace/Udea/.claude/worktrees/agent-a3cb3603dfb72ee9f/udea-assets/src/commonMain/kotlin/dev/wildware/udea/assets/CheckerProbe.kt:7:1: java.lang.NoClassDefFoundError: dev/wildware/udea/diagnostics/UdeaRules
```
With the line in place, `$S/probe-fixed.log`, the rules fire on all three compilers. Lines 66-76 of that log, with the blank lines between tasks left out:
```
> Task :udea-assets:compileKotlinWasmJs FAILED
e: file:///srv/ssd1/workspace/Udea/.claude/worktrees/agent-a3cb3603dfb72ee9f/udea-assets/src/commonMain/kotlin/dev/wildware/udea/assets/CheckerProbe.kt:9:14 UDEA0001: @Net annotates the val dev.wildware.udea.assets.CheckerProbe.health. A val can never change, so it can never replicate, and Replicator.apply could not restore it. Make it a var or drop the annotation.
e: file:///srv/ssd1/workspace/Udea/.claude/worktrees/agent-a3cb3603dfb72ee9f/udea-assets/src/commonMain/kotlin/dev/wildware/udea/assets/CheckerProbe.kt:10:42 UDEA0003: @Q annotates dev.wildware.udea.assets.CheckerProbe.slots, which is kotlin.Int, not Float. Quantization is only defined for floats.
> Task :udea-assets:compileKotlinJvm FAILED
[the same two lines]
> Task :udea-assets:compileKotlinIosArm64 FAILED
[the same two lines]
```

## 2. Summary

**What moved.** `udea-assets` applies `udea.kotlin-multiplatform`: jvm, android, wasmJs, iosArm64 and iosSimulatorArm64.
It does not depend on udea-core, so the Fleks limit behind #215 does not apply. `src/main` moved to `commonMain` unchanged,
except for what the JVM alone provided:

- `java.io.Closeable` became `AutoCloseable`.
- `java.nio.file.Path` + `RandomAccessFile` became a kotlinx-io `Path` read through `SystemFileSystem`, with a forward read head.
- `java.security.MessageDigest` became a common `Sha256`.
- `String(bytes, Charsets.UTF_8)` became `decodeToString()`.
- `"%02x".format` became `toString(16).padStart(2, '0')`.
- `@JvmInline`/`@JvmField` gained their `kotlin.jvm` imports.

JVM consumers (`udea-assets-compiler`, `udea-render`, `udea-agent`, `udea-audio`, `moba`) compile unchanged against the jvm variant.
`udea-assets-compiler` needed no change.

**Tests.** Every test that needs no JVM API moved to `commonTest` with `kotlin.test.Test`.
`BundleFixtures` lost `ByteArrayOutputStream` and `MessageDigest`, and no assertion changed.
`ResPathTest` (it reads source files) and `NoTextureRegionSplitTest` (it reads class files) stay in `jvmTest`.
`ResPathTest` now scans every `src/*Main` set; its own "has sources to scan" guard would have gone red on the old path.

**On Wasm** (`wasmJsMain/BundleFetch.kt`), `suspend fun BundleReader.fetch(url)` uses the global `fetch`, copies the `ArrayBuffer` into a `ByteArray`, and calls `BundleReader.open(bytes)`.
An HTTP error or a refused connection is a `kotlinx.io.IOException` naming the URL.

**Build and CI:**

- `UDEA-MG-006` allows kotlinx-io by name (`docs/module-graph.md` updated).
- The K2 plugin's Kotlin/Native classpath is made transitive. KGP creates it intransitive, and the iOS test compile of this module crashed with `NoClassDefFoundError` inside a checker.
- `ios-tests` runs `:udea-assets:iosSimulatorArm64Test`.
- `checkers-fire` writes its probe to `commonMain` and compiles `compileKotlinJvm`, because `compileKotlin` is ambiguous on a multiplatform module.

**Decisions**, each commented on #205 with the alternative and how to undo it:

1. kotlinx-io dependency and MG-006 widened: https://github.com/wildware-uk/Udea/issues/205#issuecomment-5706072355
2. SHA-256 written in common code rather than a library: #issuecomment-5706073653
3. File source: forward read head, reopen for a backwards read: #issuecomment-5706073768
4. Wasm `fetch` and the Node HTTP-server test: #issuecomment-5706076115
5. Golden captured pre-port, Base64 in `commonTest`, no update flag: #issuecomment-5706076272
6. Native plugin classpath made transitive (shared build-logic): #issuecomment-5706077781
7. CI changes and the test renames Kotlin/Native needs: #issuecomment-5706078022

**Rejected.**

- `expect`/`actual` `RandomAccessFile` on the JVM. Two implementations for no caller: nothing outside the module opens a pak by path, and moba reads bytes.
- `cryptography-kotlin`. It is async on Wasm.
- A `data:` URL for the Wasm test. That is not HTTP.
- Switching the K2 plugin off for native compilations. That silently leaves iOS-only source unchecked.

**Test renames.** `, ` became ` - ` in the moved and new test names that had one. Two apostrophe names were reworded.
Kotlin/Native rejects those characters in backticked names (found in #203). The name lines are in `$S/renamed-tests.txt`.

**What this does not cover.**

- A browser. `BundleFetchTest` runs on Node, whose `fetch` is not a browser's (no CORS, no page origin). `wasmJsBrowserTest` is not configured for this module.
- iOS execution on this box. The iOS klibs, main and test, compile here in `build`; the tests ran in CI only (see 5).
- A pak's graph-string decoding with non-ASCII text. The golden's strings are ASCII; its non-ASCII blob is compared by checksum, not decoded.
- Speed of the Wasm byte copy. It is one JS call per byte, and no benchmark was taken.

**One `public` declaration with no caller outside the module yet: `BundleReader.fetch`.** The acceptance criterion requires it, and its consumer is the web build that does not exist yet.

**Baseline.** My first baseline run exited 0, but its log was overwritten by a concurrent agent's file of the same name in the shared scratchpad.
So it was re-run on a detached `origin/kmp` 07eddef worktree: `$S/baseline-origin-kmp-07eddef.log`, `BUILD SUCCESSFUL in 1m 52s`, no failed task.

- **Tasks this ticket turned green** (new with the port, all in `build`): `:udea-assets:jvmTest`, `:udea-assets:wasmJsNodeTest`, `:udea-assets:testAndroidHostTest`, `:udea-assets:compileKotlinJvm`, `compileKotlinWasmJs`, `compileKotlinIosArm64`, `compileKotlinIosSimulatorArm64`, `compileAndroidMain`, `compileTestKotlinIosArm64`, `compileTestKotlinIosSimulatorArm64`, `allTests`. `:udea-assets:iosSimulatorArm64Test` is SKIPPED on Linux and runs in CI.
- **Baseline failures, unchanged:** none in `build`. Outside it, `sh gradlew -p build-logic check` fails `OuterBuildInputsTest` on udea-core paths from `DeterminismLayoutTest`. The lead confirmed it on detached `origin/kmp` (1 of 310) and filed it as #216. On this branch it is still the only failure, 1 of 311 (`$S/build-logic-check.log`). The extra test is the new MG-006 rule test.

## 3. `sh gradlew build`

`ANDROID_HOME=/home/shaun/Android/Sdk JAVA_HOME=/home/shaun/.sdkman/candidates/java/21.0.11-tem sh gradlew build --continue --console=plain` on 48c857b, `$S/build-branch-1.log`, last lines:

```
BUILD SUCCESSFUL in 2m 6s
419 actionable tasks: 309 executed, 3 from cache, 107 up-to-date
Configuration cache entry stored.
```

`grep -E "^> Task .* FAILED"` over that log returns nothing. `udeaDaemonBudget` is not part of `build` (it hangs off `udeaLatencyBudgets` since #175), so this run says nothing about it, and this ticket does not touch the asset compiler it measures.

`sh gradlew -p build-logic check --continue`, `$S/build-logic-check.log`, lines 20-23 and 37 (22 is blank):
```
OuterBuildInputsTest > every repository file a build-logic test names is a declared input of this task() FAILED
    org.opentest4j.AssertionFailedError at OuterBuildInputsTest.kt:130
[blank line]
311 tests completed, 1 failed
BUILD FAILED in 55s
```
That is the baseline failure #216. `ModuleGraphRulesTest` is 28 tests with 0 failures in the XML of that run.

`sh gradlew udeaVerifyModuleGraph udeaVerifyNoLegacyDependencies udeaVerifyAgentsMd udeaVerifyMigration udeaLegacyReport`, `$S/verifiers.log`: `BUILD SUCCESSFUL in 15s`.
Every task was UP-TO-DATE from the build before it, which had run them.

**GL.** This ticket changes no GL code, so `udeaGlTest`/`udeaAgentGlTest` were not run under xvfb.
What was run under xvfb is the game reading its pak through the ported reader: `:moba:runShot`. See the images.

**The `checkers-fire` CI step, run locally.** `$S/checkers-fire-step.sh` is the step's `run:` block extracted from `ci.yml`, with `./gradlew` changed to `sh gradlew`.
`$S/checkers-fire-step.log` has both rules at 9:14 and 10:42, then `BUILD SUCCESSFUL` with `-Pudea.compilerPlugin.enabled=false`.
Its last line, `kotlin-upgrade-probe:: command not found`, comes from my extraction running one line past the step into the next job's name. It is not part of the step.

## 3a. CI on the pushed branch

Run https://github.com/wildware-uk/Udea/actions/runs/35163397913 on 48c857b. The job list, as `gh run view` printed it when the run completed, is in `$S/ci-run-jobs.txt`.
It is compared with the `kmp` run on 07eddef, https://github.com/wildware-uk/Udea/actions/runs/35160551639.

- **Green here:** `iOS simulator tests` (see 5), `the FIR checkers fail a real build` (the moved probe), `build (ubuntu-latest)`, `build (windows-latest)`, `gl tests (xvfb)`, `build with the K2 plugin disabled`, `agent brief matches the tree`, `KSP stays incremental`, `game-bridge-mcp conformance`, `latency budgets (ubuntu-latest)`, every `replay-equality` leg.
- **Red here and red on 07eddef, same cause:**
  - `migration ledger`, and all four `determinism` legs. Each runs `build-logic check` and fails `OuterBuildInputsTest`, which is #216 (`$S/ci-branch-determinism.log` and `$S/ci-kmp-determinism.log`).
  - `clean build under budget`. Same cause, but **this branch makes it worse**. 07eddef measured `42330 ms against 37932 ms`, ratio 1.116. This branch measured `52465 ms against 40466 ms`, ratio 1.297 (`$S/ci-branch-clean.log`, `$S/ci-kmp-clean.log`). A clean build now compiles `udea-assets` for four more targets, including two Kotlin/Native iOS klibs, main and test. The acceptance criterion asks for that, and this gate compares each commit with its parent, so every port ticket pays it. I have not tried to make the gate pass, and I have not changed it.
- **Red here, green on 07eddef:** `latency budgets (windows-latest)`. `:udea-assets-compiler:udeaDaemonBudget`: `warm validate of one script: median 354ms over 4 samples [24, 404, 275, 354]` against a 300ms budget (`$S/ci-latency-windows.log`). The two most recent `kmp` Windows runs measured medians of 211ms and 248ms, with single samples of 309 and 360 (`$S/ci-latency-windows-kmp-07eddef.log`, `$S/ci-latency-windows-kmp-prev.log`). **Open:** whether this is Windows noise or a real slowdown. One thing the branch does change on that path: the asset compiler's script classpath now carries the kotlinx-io jars. A local A/B of the task between `origin/kmp` and this branch is recorded in `$S/daemon-ab.txt`. It waits for the box to fall under load 4, and I report its numbers to the lead rather than here.

## 4. Images

- `/srv/ssd1/workspace/Udea/build/debug-screenshots/issue205-roster-1-before-origin-kmp.png`: `:moba:runShot` on detached `origin/kmp` 07eddef, under xvfb. The roster drawn from moba's `.udeapak` by the pre-port reader.
- `/srv/ssd1/workspace/Udea/build/debug-screenshots/issue205-roster-2-after-ported-reader.png`: the same task on 48c857b. The game reads its pak through the multiplatform reader and draws the same frame. `cmp` of the two PNGs reports no difference.
- `/srv/ssd1/workspace/Udea/build/debug-screenshots/issue205-roster-before-after.png`: the two stacked, before on top. The fifth figure lying down is in both, so it predates this change.

## 5. The acceptance criteria

**"Builds for all four targets."**
- jvm, android and wasmJs build and test in `build` (section 3).
- iosArm64 and iosSimulatorArm64: `compileKotlinIos*` and `compileTestKotlinIos*` compile in `build` on this box. Linking and running need macOS.
- CI's `ios-tests` job now runs `:udea-assets:iosSimulatorArm64Test` and fails if no report is written or any test is skipped. Push run: https://github.com/wildware-uk/Udea/actions/runs/35163397913. That job finished `success`. Its log, fetched with `gh api repos/wildware-uk/Udea/actions/jobs/105019007462/logs` into `$S/ci-ios-job.log`, says:
  ```
  2026-09-16T23:46:21.2714810Z > Task :udea-assets:iosSimulatorArm64Test
  2026-09-16T23:46:21.7706090Z BUILD SUCCESSFUL in 4m 41s
  ```
  and, from the step that checks the reports:
  ```
  2026-09-16T23:46:23.7859020Z udea-assets: 75 iOS tests ran, 0 skipped
  ```
  75 is the whole `commonTest` set, the same count as on the Android host, including the golden and the file-source tests on the simulator's file system.

**"The `.udeapak` reader reads the same bytes as before (golden test)."**
- `0e968a7` captured the golden on the unported reader. `GoldenPakTest` passed there as a JVM test, from bytes and from a file.
- 48c857b runs the same test and the same `GOLDEN_PAK_EXPECTED` from `commonTest`: jvmTest, wasmJsNodeTest and testAndroidHostTest all green (section 1).
- m1, m2 and m3 turn it red on every target.
- In the running game, the before and after roster frames are byte-identical PNGs.

**"On Wasm a pak loads from a fetched buffer."**
- `BundleFetchTest` (wasmJsNodeTest, 3 tests, green) serves the golden pak from a Node `http` server on 127.0.0.1, loads it with `BundleReader.fetch`, and asserts `GOLDEN_PAK_EXPECTED`. It also asserts that a 404 and a refused connection are `IOException`s naming the URL.
- m2 reddens the fetch golden and m4 reddens the 404 case.

**"No `java.io`/`java.nio`/`java.security` in `commonMain`."**
- `compileKotlinWasmJs` and `compileKotlinIosArm64` compile all of `commonMain`, and neither target has any `java.*`. That also catches a fully qualified use that an import grep would miss.
- A grep over `udea-assets/src/commonMain` for `java\.` finds only a KDoc sentence in `Sha256.kt` naming what it replaced (`java.security.MessageDigest`).

## 6. Regenerated files

None. No replicated component was added or removed, so `udea-codegen/net-protocol.lock` and `expected-generated-hashes.txt` are untouched.
No contract file changed (`udeaVerifyContracts` is in the green `build`).
