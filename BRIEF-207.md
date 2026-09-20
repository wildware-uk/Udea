e37fb99

# Issue #207 - `udea-audio` on Kotlin Multiplatform

Branch `issue-207-audio-kmp`, off `origin/kmp` at `8c733a4`. Worktree
`/srv/ssd1/workspace/Udea/.claude/worktrees/agent-ad8fa4bba8720b520`. This file is uncommitted, as asked.

Every log quoted below is saved in `build/reports/issue207/` in this worktree (gitignored). Quoted blocks are
copied from those files; `[...]` marks a gap.

## 1. Evidence command

    JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem ANDROID_HOME=$HOME/Android/Sdk sh gradlew :udea-audio:allTests

It runs the module's tests on `jvm`, `android` (host tests) and `wasmJs` (Node). On this branch
(`build/reports/issue207/evidence-green.log`, run with `--rerun` on each test task so nothing came from cache):

    > Task :udea-audio:wasmJsNodeTest
    [...]
    > Task :udea-audio:jvmTest
    > Task :udea-audio:testAndroidHostTest
    > Task :udea-audio:allTests
    [...]
    BUILD SUCCESSFUL in 47s

The test reports from that run: `jvmTest` 15 tests (`AudioBindingsTest` 6, `CueAudioTest` 8,
`CueAudioAllocationTest` 1), `testAndroidHostTest` 14, `wasmJsNodeTest` 14. All 0 failed, 0 skipped.

**It fails when the feature is reverted.** I checked out `origin/kmp`'s `udea-audio/` into the tree and ran the
same command (`build/reports/issue207/evidence-reverted.log`):

    * What went wrong:
    Cannot locate tasks that match ':udea-audio:allTests' as task 'allTests' not found in project ':udea-audio'.

That red only says "the module is not multiplatform". Two more runs show the tests themselves bite:

**Red for a real reason before the fix.** With the sources moved into `commonMain` but not yet fixed
(`build/reports/issue207/red-2-common-compile.log`, lines 169-171):

    > Task :udea-audio:compileKotlinWasmJs FAILED
    e: file:///srv/ssd1/workspace/Udea/.claude/worktrees/agent-ad8fa4bba8720b520/udea-audio/src/commonMain/kotlin/dev/wildware/udea/audio/AudioDevice.kt:90:2 Unresolved reference 'JvmInline'.
    e: file:///srv/ssd1/workspace/Udea/.claude/worktrees/agent-ad8fa4bba8720b520/udea-audio/src/commonMain/kotlin/dev/wildware/udea/audio/CueAudio.kt:58:18 Unresolved reference 'System'.

JVM and Android compiled that same code, because both can see `java.lang.System`. Only the Wasm build caught it.

**A mutation fails the same test on all three targets.** Stereo pan flipped (`build/reports/issue207/mutation-1-pan.diff`):

    @@ -163,7 +163,7 @@ public class CueAudio(
                 sound.handleAt(if (sound.size == 1) 0 else random.nextInt(sound.size)),
                 volume.coerceAtMost(1F),
                 pitchFor(sound),
    -            listener.panAt(dx),
    +            listener.panAt(-dx),
             )
             played++
             playsByCue[cue.id.raw]++

Result (`build/reports/issue207/mutation-1-pan.log`, lines 173-200):

    dev.wildware.udea.audio.CueAudioTest.pan is negative for a source left of the ear and positive for one to its right[wasmJs, node] FAILED
    [...]
    14 tests completed, 1 failed
    [...]
    CueAudioTest > pan is negative for a source left of the ear and positive for one to its right FAILED
        java.lang.AssertionError at CueAudioTest.kt:163

    14 tests completed, 1 failed
    [...]
    CueAudioTest[jvm] > pan is negative for a source left of the ear and positive for one to its right()[jvm] FAILED
        org.opentest4j.AssertionFailedError at CueAudioTest.kt:163

    15 tests completed, 1 failed
    [...]
    > Task :udea-audio:allTests FAILED

Reverted with `git checkout -- udea-audio`. The tree matches commit `c59f403`.

## 2. Summary

`udea-audio` moves from `dev.wildware.udea.kotlin-library` to `dev.wildware.udea.kotlin-multiplatform-no-ios`: `jvm`, `android`, `wasmJs`.

- **Sources.** Everything in `src/main` goes to `commonMain` unchanged: the `AudioDevice` interface,
  `AudioDevice.Silent`, `AudioBindings`, `AudioListener` and `CueAudio`. `CueAudioTest`, `AudioBindingsTest` and
  `RecordingDevice` go to `commonTest` unchanged, so they now run on every target. `CueAudioAllocationTest` goes to
  `jvmTest`, because it reads HotSpot's per-thread allocation counter (`com.sun.management.ThreadMXBean`), which
  only the JVM has.
- **Two code changes, both forced by `commonMain`.**
  1. `import kotlin.jvm.JvmInline` in `AudioDevice.kt`. On the JVM this was imported by default; common code
     has to import it.
  2. `CueAudio`'s default `seed` was `System.nanoTime()`, which does not exist in common code. It is now
     `Clock.System.now()`, folded into a `Long` (epoch seconds times 1e9, plus nanoseconds).
- **Dependencies unchanged:** `udea-core` as `api`, `udea-assets` as `implementation`, now in `commonMain`.
- **Docs.** `docs/module-graph.md`: the `udea-audio` row now names the new convention, and `udea-audio` is added to
  the list of modules without iOS. The stale `udea-assets` row (said `dev.wildware.udea.kotlin-library`, was already
  `dev.wildware.udea.kotlin-multiplatform` after #205) is fixed. `AGENTS.md`: the multiplatform paragraph now covers
  `udea-audio`. `.github/workflows/ci.yml`: one comment in the `ios-tests` job now lists `udea-audio` among the
  modules left out through `udea-core`. Only the comment changed.

**Decisions** (the seed one is commented on #207: https://github.com/wildware-uk/Udea/issues/207#issuecomment-5707296704):

- *Seed source.* I chose `kotlin.time.Clock.System`. I rejected `Random.Default.nextLong()` because AGENTS.md
  bans `Random.Default` by name, and a reviewer should not have to decide whether audio counts as simulation.
  I rejected `expect`/`actual` per target because it adds three files for a default no test checks. The cost:
  clock resolution depends on the platform (coarser in a browser), so two mixers built at the same instant
  could get the same seed. Every test passes `seed` itself.
- *No test for the default seed.* Asserting that two default-seeded mixers differ would depend on timing and
  could fail at random. The default is still wall-seeded, as before.
- *Scope* (the lead ruled this, and it is on the issue): criterion 2, the Kool-backed device, moved to #221. No
  Kool dependency was added here.
- *No KSP registry* for `udea-audio`. It had none before, and this ticket does not add one.

**Not exercised:** iOS (not a target; `udea-core` has none). A browser test run (the convention uses Node). The
Android device run (host tests only). The `moba` desktop game still compiles against the JVM variant
(`:moba:compileKotlin` ran in the build below), but I did not launch it. The ticket changes nothing a player sees
or an agent calls, and the owner ruled LibGDX evidence out.

## 3. `sh gradlew build`

**Baseline.** I ran `build --continue` on the fresh branch before changing anything, and it passed
(`BUILD SUCCESSFUL in 1m 22s`, 539 actionable tasks). **I no longer have that log.** I saved it in my session
scratchpad, which other agents in this wave also write to, and a later run from worktree `agent-a9f0019cbc1541a12`
overwrote the file with the same name (its contents name that worktree, not mine). So this baseline figure is my
note, not a saved artefact. It does not change the comparison below, because this branch has no failing task.

**This branch**, at `e37fb99` (`build/reports/issue207/branch-build.log`), with
`JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem ANDROID_HOME=$HOME/Android/Sdk sh gradlew build --continue`:

    > Task :udea-audio:wasmJsNodeTest
    > Task :udea-audio:allTests
    > Task :udea-audio:check
    > Task :udea-audio:build
    [...]
    BUILD SUCCESSFUL in 31s
    578 actionable tasks: 62 executed, 10 from cache, 506 up-to-date
    Configuration cache entry stored.

`grep -c FAILED` on that log gives 0. Most tasks were up to date or came from the build cache, from my own
earlier runs of the same inputs. The same log shows `:udea-render:udeaVerifyHeadless`, `:moba:compileKotlin`,
`:udeaVerifyAgentsMd` and `:udea-audio:udeaVerifyModuleGraph` running.

- **Tasks this ticket turned green:** `:udea-audio:compileKotlinWasmJs`, `:udea-audio:compileAndroidMain`,
  `:udea-audio:compileKotlinJvm`, `:udea-audio:jvmTest`, `:udea-audio:testAndroidHostTest`,
  `:udea-audio:wasmJsNodeTest`, `:udea-audio:allTests`. None of them existed on `origin/kmp`.
- **Baseline failures, unchanged:** none in the root build.

**Gates** (`build/reports/issue207/gates.log`), `sh gradlew udeaVerifyModuleGraph udeaVerifyNoLegacyDependencies udeaVerifyAgentsMd`:

    > Task :udeaVerifyAgentsMd UP-TO-DATE
    [...]
    > Task :udea-audio:udeaVerifyNoLegacyDependencies UP-TO-DATE
    > Task :udea-audio:udeaVerifyModuleGraph UP-TO-DATE
    [...]
    BUILD SUCCESSFUL in 23s

**`sh gradlew -p build-logic check --continue`** (`build/reports/issue207/build-logic-check.log`):

    OuterBuildInputsTest > every repository file a build-logic test names is a declared input of this task() FAILED
    [...]
    311 tests completed, 1 failed

This is the known #216 failure. The paths it flags
(`build/reports/issue207/TEST-dev.wildware.udea.build.OuterBuildInputsTest.xml`) are all `udea-core` paths:
`udea-core/src/commonMain/kotlin`, `udea-core/src/jvmAndAndroidMain/kotlin`, `udea-core/src/jvmTest/kotlin`,
`udea-core/src/wasmJsMain/kotlin`. None is a `udea-audio` path. I did not run `build-logic` check on the baseline
myself; the lead measured it.

**GL:** not touched. No change to `udea-render`, `udea-agent-host`, or anything that opens a GL context, so the
xvfb run does not apply.

## 4. Images

None. Nothing here can be seen: the module holds no device that makes sound on any target, and the owner ruled
this ticket's evidence is a test report and a transcript.

## 5. Acceptance criteria

- **SPI and Silent build for all four targets.** "Four" means `jvm`, `android`, `wasmJs`. iOS is out because
  `udea-core` has no iOS target (#215). This was the lead's ruling and is on the issue. Proof: the branch build
  ran `:udea-audio:compileKotlinJvm`, `:udea-audio:compileAndroidMain`, `:udea-audio:compileKotlinWasmJs` and
  `:udea-audio:compileCommonMainKotlinMetadata`, all green (`branch-build.log`). iOS is **not** built or tested
  here, and cannot be on this box.
- **Kool device plays a cue on desktop and in a browser.** **Moved to #221** by the lead's scope split. Not done
  here, and not claimed.
- **Cue draining from `GameContext.cues` unchanged (existing tests pass).** `CueAudioTest` (8) and
  `AudioBindingsTest` (6) are the same bytes as on `origin/kmp` (`git diff -M origin/kmp` shows
  `similarity index 100%` for both and for `RecordingDevice.kt`). They pass on `jvm`, `android` and `wasmJs`, and
  the pan mutation above shows they can fail on each. `CueAudioAllocationTest` is also byte-identical and passes
  on `jvmTest`.

## 6. Regenerated files

None. No replicated component was added or removed. `net-protocol.lock` and `expected-generated-hashes.txt` are
untouched.
