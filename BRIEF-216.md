448c796

# Issue #216 - build-logic check red on kmp: OuterBuildInputsTest

Branch `issue-216-build-logic-inputs`, one commit on `origin/kmp` (8c733a4).
Every log quoted below is saved in `build/issue216/` of this worktree (gitignored), and each
block is a contiguous run of the named file; elisions are marked `[...]`.

## 1. Evidence command

    ANDROID_HOME=$HOME/Android/Sdk JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem sh gradlew -p build-logic check --continue

**Red on `origin/kmp` (8c733a4), before the change** - `build/issue216/red-origin-kmp.log`, its last lines:

```
OuterBuildInputsTest > every repository file a build-logic test names is a declared input of this task() FAILED
    org.opentest4j.AssertionFailedError at OuterBuildInputsTest.kt:130

311 tests completed, 1 failed

> Task :test FAILED
[...]
BUILD FAILED in 2m 5s
13 actionable tasks: 13 executed
```

The paths it named, from the saved test result `build/issue216/red-OuterBuildInputsTest.xml`
(XML-escaped as saved):

```
expected: &lt;{}&gt; but was: &lt;{udea-core/src/commonMain/kotlin=[DeterminismLayoutTest.kt], udea-core/src/jvmAndAndroidMain/kotlin=[DeterminismLayoutTest.kt], udea-core/src/jvmTest/kotlin=[DeterminismLayoutTest.kt], udea-core/src/wasmJsMain/kotlin=[DeterminismLayoutTest.kt]}&gt;
```

**Green on this branch** - `build/issue216/green-branch.log` (run on the working tree whose diff
became commit 448c796 unchanged; `compileTestKotlin` re-ran, so the edited test was the one run):

```
> Task :testClasses UP-TO-DATE
> Task :test
> Task :check

BUILD SUCCESSFUL in 55s
13 actionable tasks: 2 executed, 11 up-to-date
```

The JUnit XML for that run summed to 311 tests, 0 failures, 0 skipped (read at the time; those XML files were overwritten by the mutation runs, so this sentence is prose).

### Mutations (run as `sh gradlew -p build-logic test --tests "dev.wildware.udea.build.OuterBuildInputsTest"`, each reverted after)

**M1 - un-excuse one path.** `build/issue216/mutation1.diff`, from its hunk header on (file header lines elided):

```
@@ -236,7 +236,6 @@ class OuterBuildInputsTest {
             "udea-core/src/commonMain/kotlin" to DETERMINISM_LAYOUT_FIXTURE,
             "udea-core/src/jvmAndAndroidMain/kotlin" to DETERMINISM_LAYOUT_FIXTURE,
             "udea-core/src/wasmJsMain/kotlin" to DETERMINISM_LAYOUT_FIXTURE,
-            "udea-core/src/jvmTest/kotlin" to DETERMINISM_LAYOUT_FIXTURE,
         )
     }
 }
```

`build/issue216/mutation1.log`:

```
OuterBuildInputsTest > every repository file a build-logic test names is a declared input of this task() FAILED
    org.opentest4j.AssertionFailedError at OuterBuildInputsTest.kt:130

4 tests completed, 1 failed
```

and the assertion named only that path: `but was: <{udea-core/src/jvmTest/kotlin=[DeterminismLayoutTest.kt]}` (read from the JUnit XML at the time; that file was overwritten by M2's run and not kept, so treat this line as prose).

**M2 - the excuse outlives its reason** (the fixture stops naming the directory).
`build/issue216/mutation2.diff`, from its hunk header on (file header lines elided):

```
@@ -44,7 +44,6 @@ class DeterminismLayoutTest {
             "udea-core/src/commonMain/kotlin",
             "udea-core/src/jvmAndAndroidMain/kotlin",
             "udea-core/src/wasmJsMain/kotlin",
-            "udea-core/src/jvmTest/kotlin",
             "udea-core/build/classes/kotlin/main",
         )
```

`build/issue216/mutation2.log`:

```
OuterBuildInputsTest > no exemption has gone stale() FAILED
    org.opentest4j.AssertionFailedError at OuterBuildInputsTest.kt:145

4 tests completed, 1 failed
```

naming `[udea-core/src/jvmTest/kotlin]` (saved: `build/issue216/mutation2-OuterBuildInputsTest.xml`). So the four new exemptions cannot linger.

## 2. Summary

**What I found.** `DeterminismLayoutTest` does not read the real `udea-core` source directories.
It creates directories of those names with `mkdirs` under its own `@TempDir` fixture repository
and asserts `DeterminismLayout.scopeInput(repo, scope)` returns them. The gate only matches them
because the literal strings happen to name real directories in this repository. So there was no
FROM-CACHE hole; the gate was flagging a look-alike.

**What I did.** Added the four paths to `OuterBuildInputsTest.NOT_READ_FROM_THE_REPOSITORY`, all
pointing at one reason string, `DETERMINISM_LAYOUT_FIXTURE`. One file changed, test source only.

**Decision, and what I rejected.** The issue suggested declaring the paths in `outerBuildInputs`.
I did not, because:
- the gate's own failure message and KDoc name this route for "a fixture path that merely happens
  to name a real file", and call declaring such a path "a lie about why they are there";
- declaring them would make every edit to `udea-core`'s sources invalidate `:build-logic:test`
  for a test whose result cannot depend on those files.

Both routes turn the check green. Commented on the issue with how to switch if the owner disagrees:
https://github.com/wildware-uk/Udea/issues/216#issuecomment-5707289813

**Not touched:** the determinism scanner's forbidden set (#217), `build-logic/build.gradle.kts`,
and anything outside `build-logic`.

## 3. Builds

**Root build** - `ANDROID_HOME=$HOME/Android/Sdk JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem sh gradlew build --continue`,
`build/issue216/root-build.log`, last lines:

```

BUILD SUCCESSFUL in 1m 29s
539 actionable tasks: 367 executed, 163 from cache, 9 up-to-date
Configuration cache entry stored.
```

No `> Task ... FAILED` line in that log (`grep -cE "^> Task .* FAILED"` returned 0). Other
developers' Gradle clients were running on the box at the time; it was green anyway.

- Tasks this ticket turned green: `build-logic`'s `:test` / `:check` (run with `-p build-logic`;
  the root build does not run them, which is why the baseline read green).
- Baseline failures, unchanged: none. I did not run a root baseline myself; the lead's baseline (root `build --continue` green at dc6c708, `origin/kmp` since moved to 8c733a4 by docs-only commits) is what this compares against, and the root build is green here.

**GL:** not run. The change is one `build-logic` test source; nothing in `udea-render`,
`udea-agent-host` or any GL path moved.

**Gates outside `check`** (`runUdpProof`, `runLaneShot`, module-graph verifiers): not run; the
change cannot reach them.

## 4. Images

None. There is nothing visual in this ticket; the transcripts above are the evidence.

## 5. Acceptance criteria

- [x] `sh gradlew -p build-logic check` green on the branch - section 1, `green-branch.log`
  (`BUILD SUCCESSFUL in 55s`), red counterpart on `origin/kmp` in `red-origin-kmp.log`.
- [x] Root `sh gradlew build --continue` still green - section 3, `root-build.log`
  (`BUILD SUCCESSFUL in 1m 29s`, no failed task).

## 6. Regenerated files

None. `net-protocol.lock` and `expected-generated-hashes.txt` are untouched.
