0d5e620

# #215: vendor Fleks so udea-core and its dependents build for iOS

Branch `issue-215-vendor-fleks`, off `origin/kmp` at `9e85de3`. (`origin/kmp` has since gained
`aafe3f9`, which touches only `.claude/WAVE.md`.)

All logs named below are in
`/tmp/claude-1000/-srv-ssd1-workspace-Udea/b54cfb4b-7211-4c4f-8411-cf0e7076bda3/scratchpad/`.

## 1. The evidence command

```
JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem ANDROID_HOME=$HOME/Android/Sdk sh gradlew \
  udeaVerifyModuleGraph udeaVerifyNoLegacyDependencies udeaVerifyAgentsMd udeaVerifyDeterminism \
  :udea-fleks:allTests :udea-core:allTests \
  :udea-fleks:compileTestKotlinIosSimulatorArm64 :udea-core:compileTestKotlinIosSimulatorArm64 \
  :udea-gas:compileTestKotlinIosSimulatorArm64 :udea-audio:compileTestKotlinIosSimulatorArm64 \
  :udea-replay:compileTestKotlinIosSimulatorArm64
```

Green on `0d5e620`: `issue215-evidence-green.log` ends `BUILD SUCCESSFUL in 5s` / `EXIT=0` (warm daemon, tasks up to date from the full build).
The iOS compile tasks compile klibs on Linux; they do not link or run iOS tests. The iOS run is
CI, section 4b.

**It goes red when the feature is reverted.** Each row: the literal diff (from
`issue215-mut-<name>.diff`), then the lines from `issue215-mut-<name>.log`. Each file was restored
with `git checkout` after its run.

**ev-origin-kmp-core** - udea-core put back as it is on `origin/kmp` (no iOS, Maven Fleks):

```
-    id("dev.wildware.udea.kotlin-multiplatform")
+    id("dev.wildware.udea.kotlin-multiplatform-no-ios")
...
-                api(project(":udea-fleks"))
+                api(libs.fleks)
```
```
Cannot locate tasks that match ':udea-core:compileTestKotlinIosSimulatorArm64' as task 'compileTestKotlinIosSimulatorArm64' not found in project ':udea-core'.
BUILD FAILED in 6s
EXIT=1
```

**ev-maven-fleks** - iOS kept on, Fleks back to the Maven artifact (the fix alone reverted):

```
-                api(project(":udea-fleks"))
+                api(libs.fleks)
```
```
   > Could not resolve io.github.quillraven.fleks:Fleks:2.14.
BUILD FAILED in 5s
EXIT=1
```

**ev-edit-vendored** - one space added to vendored Fleks source:

```
-data class Entity(val id: Int, val version: UInt) {
+data class Entity(val id: Int, val version: UInt)  {
```
```
> Task :udeaVerifyDeterminism FAILED
  determinism-allowlist.txt: [ALLOW005] line 39: the audit was performed against fleks 2.14+sha256:220b74bf865c68a5e99f9f340855148d9a078b7a1e158eaba82d35f7230441bf, but the build resolves 2.14+sha256:1ebb8edec4f6bb233f3c997ee7b171665eddc4de8f33396ee67bea44bc47da5c. Re-rea
BUILD FAILED in 12s
EXIT=1
```
(The ALLOW005 line is cut at 300 characters by the grep that printed it.)

**ev-json-on-main** - upstream's JSON dependency put back on vendored Fleks' main classpath:

```
                 api(libs.kotlinx.serialization.core)
+                implementation(libs.kotlinx.serialization.json)
```
```
> Task :udea-fleks:udeaVerifyModuleGraph FAILED
  UDEA-MG-007 :udea-fleks androidCompileClasspath -> org.jetbrains.kotlinx:kotlinx-serialization-json
  UDEA-MG-007 :udea-fleks androidCompileClasspath -> org.jetbrains.kotlinx:kotlinx-serialization-json-jvm
[... one line per classpath and artifact; the log has them all ...]
```

## 2. Summary

**What.** Fleks publishes no iOS variant at any version, and `udea-core` exposes it as `api`.
Fleks' source from upstream tag `2.14` (commit `c3b69c240dc23e6869d7e54d53da8f1cb0e73c95`) is now
the module `udea-fleks` on `dev.wildware.udea.kotlin-multiplatform` (all five targets). `src/commonMain`,
`src/commonTest` and `LICENSE` are byte-identical to the tag (`issue215-diff-commonMain.txt` is the
empty `diff -r` output; the same `diff -r` for `commonTest` and `LICENSE` exited 0 in the session).
`NOTICE.md` records origin, licence and the build differences. `udea-core` takes
`api(project(":udea-fleks"))` and is on the full convention.

**Before and after, on this box.** With iOS on and Fleks from Maven, `issue215-red-ios-on-maven-fleks.log`:
`Could not resolve all files for configuration ':udea-core:iosArm64CompilationDependenciesMetadata'.`
/ `Could not resolve io.github.quillraven.fleks:Fleks:2.14.` After: section 1.

**Build differences for the vendored module (no source edits):** in `udea-fleks/build.gradle.kts`,
explicit-API mode off; `kotlin.ExperimentalStdlibApi` opted in for every source set, as upstream's
build does; `kotlinx-serialization-json` a `commonTest` dependency only, where upstream puts it on
main (main never uses it). Compiled with this repo's Kotlin, which renames `internal` members
(`$Fleks` suffix becomes `$dev_wildware_udea_udea_fleks`). The K2 compiler plugin is applied as to
every other module; it compiles clean.

**Dependents.**
- Flipped to iOS: `udea-gas`, `udea-audio`, `udea-replay`. `udea-replay`'s `udea-agent` edge is
  `jvmMain`-only, so it does not reach iOS.
- Left on `no-ios`, not widened: `udea-net` (`expect val webSocketEngine` has no native `actual`)
  and `udea-agent` (`expect fun enumConstantsOf`, `expect fun heapFigures`). Seen in
  `issue215-ios-probe.log` when both were flipped:
  `The 'expect' declaration 'webSocketEngine' has no 'actual' declaration in module '<commonMain> for Native'.`
  Their build scripts now name those blockers instead of Fleks.
- Kotlin/Native rejects a comma in a function name (`Name contains illegal characters: ","`,
  `issue215-ios-probe.log`). Backticked test names containing `, ` now use ` - ` in udea-core,
  udea-gas and udea-replay. No test body changed.
- `moba` unchanged.

**CI.** `ios-tests` runs `iosSimulatorArm64Test` for `udea-fleks`, `udea-core`, `udea-gas`,
`udea-audio`, `udea-replay` in both steps. I also added `--continue` so one module's failure does
not hide the rest.

**Module graph.** `UDEA-MG-007`: `udea-fleks` resolves only the stdlib, `org.jetbrains:annotations`,
`kotlinx-serialization-core` (with per-target artifacts) and `kotlinx-serialization-bom`.
`:udea-fleks` is added to `HEADLESS_PROJECTS` (the existing test derives that set from
`settings.gradle.kts`). Documented in `docs/module-graph.md`; AGENTS.md table row and
multiplatform paragraph updated.

**Determinism.**
- *Pin.* The `@version fleks` pin is now `2.14+sha256:<digest of udea-fleks/src/commonMain>`
  (`VendoredFleks` in build-logic; the task appends the digest to the catalog's `fleks` version).
  Rejected: comparing the release only. Vendored source can change with no release, and that
  would let the audit go stale silently.
- *Re-read.* `determinism-audit.md` section 2.0 records it: `javap -p` of seven audited classes,
  Maven jar against vendored build, differs only in `internal` name suffixes
  (`issue215-fleks-reread.txt`).
- *Found.* A reference scan of all vendored JVM classes finds one rule-relevant use:
  `MutableEntityBag.random/randomOrNull` read `Random.Default`, and `Family.random/randomOrNull`
  delegate to them. Nothing calls them today, but a call would have been invisible. `DET002` now
  matches calls to those members on `Family`, `EntityBag` and `MutableEntityBag`, and the audit
  has a `banned` row.
- *Rejected: `udea-fleks` as a simulation scope.* The allowlist excuses a referenced member
  everywhere, so excusing Fleks' own `Random.nextInt` would excuse it in every scope.

**Catalog.** The `fleks` library entry stays, because old-tree `common` still names `libs.fleks`.
It is commented as old-tree only. The `[versions] fleks` entry is commented as the vendored
release the pin stamps. `kotlinx-serialization-json` was added as a library, for the vendored tests.

**udea-agent's boundary test.** JSON no longer reaches `udea-agent` through Fleks (its
`jvmTestRuntimeClasspath` in `udea-agent/build/reports/udea/module-graph.txt` lists only
serialization `-bom`, `-cbor`, `-core`). So `kotlinx-serialization-json` joins `BANNED_ARTIFACTS`,
and the KDoc paragraph that explained why it could not be banned is rewritten.

**Comments on #215:** four decision comments (pin, DET002, which dependents flip, vendoring and
catalog).

**Not touched, noted.** `dev.wildware.udea.kotlin-base.gradle.kts` still says removing the stdlib pin "makes
this task red on `udea-core` (Fleks requests 2.3.21)". `UdeaStdlibPin`/`UdeaKotlinPin` KDoc and
`docs/module-graph.md` "Why the resolved stdlib had to be pinned" tell the same history. That was
already not true on `origin/kmp`, where the compiler is 2.4.20. Left alone as out of scope.

## 3. Build output

**Baseline** (`origin/kmp` `9e85de3`, before any change): `issue215-baseline.log`
`BUILD SUCCESSFUL in 1m 35s` / `620 actionable tasks: 429 executed, 191 from cache` / `EXIT=0`;
`sh gradlew -p build-logic check` `issue215-baseline-buildlogic.log` `BUILD SUCCESSFUL in 1m 41s`.
No baseline failures.

**Tasks this ticket turned green:** there were none red. New tasks:
`:udea-fleks:*` (including `jvmTest`, `testAndroidHostTest`, `wasmJsNodeTest`, `udeaVerifyModuleGraph`),
and the iOS compile/test tasks of `udea-core`, `udea-gas`, `udea-audio`, `udea-replay`.
**Baseline failures, unchanged:** none.

**Branch, full build, no exclusions:**
- `issue215-build-1.log` (first run): one failure, `:udea-fleks:udeaVerifyModuleGraph`, because
  MG-007 lacked `kotlinx-serialization-bom`. Fixed.
- `issue215-build-2.log` (on `aa69f37`): `BUILD SUCCESSFUL in 2m 29s` /
  `749 actionable tasks: 642 executed, 2 from cache, 105 up-to-date` / `EXIT=0`.
- `issue215-build-final.log` (on `0d5e620`; the two commits after `aa69f37` change only comments
  in `gradle/libs.versions.toml` and a line wrap in AGENTS.md): `BUILD SUCCESSFUL in 6s` /
  `749 actionable tasks: 13 executed, 736 up-to-date` / `EXIT=0`.

**build-logic:** `issue215-buildlogic-final.log` `BUILD SUCCESSFUL in 51s` / `EXIT=0`. The last
full run's XML reports hold 328 tests, 0 skipped, 0 failed.

**GL, run for real** (the ticket does not touch GL code, but `udea-render` and `udea-agent-host`
now compile against the vendored Fleks):
```
xvfb-run -a -s "-screen 0 1280x720x24" env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe \
  sh gradlew udeaGlTest udeaAgentGlTest -Pudea.render.requireGl=true
```
`issue215-gl.log`: `> Task :udea-agent-host:udeaAgentGlTest`, `> Task :udea-render:udeaGlTest`,
`BUILD SUCCESSFUL in 12s`, `EXIT=0`. XML: udeaGlTest 21 tests, udeaAgentGlTest 8, 0 skipped,
0 failed.

**Per-target test counts on this box** (`issue215-test-counts.txt`, from XML):
`udea-fleks` 298 on each of `jvmTest`, `testAndroidHostTest`, `wasmJsNodeTest`; `udea-core`
jvmTest 454, androidHost 146, wasm 146; all 0 failed. (That file shows `udea-agent jvmTest
tests=6 failed=1`. That is the filtered mutation run in section 6 overwriting the results.
`:udea-agent:jvmTest` was re-run afterwards: 294 tests, 0 failed, `issue215-agent-jvmtest.log`.)

## 4. CI

### 4a. The run
Run **35183846785** on `0d5e620`, the SHA at the top. Earlier runs: 35182214512 (`aa69f37`) was
cancelled by the next push; 35182545169 (`5882d5e`) was superseded, and its iOS job also passed
with the same counts as below.

### 4b. iOS simulator tests: success
Job 105081620290, log saved as `issue215-ci-ios-final.log`. Its checkout fetches
`0d5e620c73efb13a5363d950ca1e76c13051d44c`. The assert step printed:
```
udea-annotations: 2 iOS tests ran, 0 skipped
udea-diagnostics: 63 iOS tests ran, 0 skipped
udea-assets: 75 iOS tests ran, 0 skipped
udea-fleks: 298 iOS tests ran, 0 skipped
udea-core: 146 iOS tests ran, 0 skipped
udea-gas: 97 iOS tests ran, 0 skipped
udea-audio: 14 iOS tests ran, 0 skipped
udea-replay: 15 iOS tests ran, 0 skipped
```

### 4c. The other jobs: one red, and it is this ticket's cost
`issue215-ci-final-jobs.txt` (from `gh run view`): conclusion `failure`. Every job is `success`
(or `skipped`: the kotlin upgrade probe and the two nightly replay jobs), **except
`clean build under budget: failure`**.

Job 105081620217, `issue215-ci-cleanbuild.log`:
```
> a clean build of this commit took 99776 ms against 60998 ms for its base on the same runner, a ratio of 1.636 over the 1.1 tolerance. Both are the fastest of their samples, so this is the commit and not the machine: find what it added to udeaAssemble.
```

**What it added**, measured locally with `clean udeaAssemble --no-build-cache --profile`
(`issue215-clean-assemble-profile.log`, summaries `issue215-profile-summary.txt` and
`issue215-profile-new-work.txt`):
```
all task time 197.0s; udea-fleks (all targets) 26.3s; iOS tasks of core/gas/audio/replay 31.5s; added work 57.8s = 29% of task time
```
`assemble` on Linux compiles iOS klibs for main and test code
(`:udea-core:compileKotlinIosArm64` 6.28s, `:udea-fleks:compileTestKotlinIosArm64` 5.07s). By task
time, that work alone makes the head about 1.4x its base. The rest of the CI gap (1.636) is not
measured.

**Decided: the gate was not changed and scope was not cut.** Rejected: widening the tolerance
(`docs/budgets.md` refuses it); `kotlin.native.enableKlibsCrossCompilation=false` (removes the
Linux iOS compile that caught the comma test names); moving modules back to no-iOS (against the
decided scope). It is commented on #215 with the options. **This needs the lead's call before
merge.**

An earlier run (35182545169) also had `latency budgets (windows-latest)` red on
`udeaDaemonBudget` ("warm validate of one script: median 305ms", budget 300ms,
`issue215-ci-latency-windows-2.log`). The run before that failed the same job while resolving
build-logic's plugins (`Could not find org.jetbrains.kotlin:kotlin-stdlib:2.0.21.`,
`issue215-ci-latency-windows.log`). On the final run it is green. `udea-assets-compiler` does not
depend on `udea-core` or `udea-fleks`.

## 5. Images

None. Nothing in this ticket is visible: it is a build, dependency and CI change. No screenshots
were taken or posted to the gallery.

## 6. The issue, criterion by criterion

The issue's "re-enabling iOS is" list, plus the lead's decided scope:

| Criterion | Proof |
|---|---|
| Vendor Fleks 2.14 common source, MIT licence and notice kept, package names kept | `udea-fleks/{LICENSE,NOTICE.md}`; `diff -r` against tag `2.14` empty (section 2); udea-core main source unchanged apart from one KDoc paragraph in `Threads.native.kt` that said iOS was off; one test renamed |
| New module on `dev.wildware.udea.kotlin-multiplatform`, all five targets | `udea-fleks/build.gradle.kts`; iOS tests ran in CI (4b); jvm/android/wasm 298 each locally |
| udea-core swaps Maven for `project(":udea-fleks")` as `api`, flips iOS | Section 1 `ev-maven-fleks` and `ev-origin-kmp-core` |
| Dependents flip where nothing else blocks | gas/audio/replay: iOS CI 4b. net/agent blocked: `issue215-ios-probe.log` lines in section 2 |
| Add udea-core and each flipped module to `ios-tests` | `.github/workflows/ci.yml`; 4b lists all five |
| Read the macOS run, id + result | Run 35183846785, job 105081620290, success (4b); the run's one red job is 4c |
| AGENTS.md table row + multiplatform paragraph | `udeaVerifyAgentsMd` green in the build; removing the row is the existing UDEA-DOC-001 gate |
| `docs/module-graph.md` rule for udea-fleks | UDEA-MG-007; `ModuleGraphRulesTest` "every rule id ... documented" green; mutation `mg007-json-allowed` below |
| Determinism pin still checks Fleks, not vacuous | `ev-edit-vendored` (section 1); `VendoredFleksTest` mutations below |
| The scanner must still see it | DET002 Fleks clause; mutations `det002-fleks-clause-off`, `det002-owner-only` below |
| `libs.versions.toml` fleks entry removed if unused; serialization comment | Library entry kept: `common` uses it. Removing it failed configuration with `Unresolved reference: fleks` at `common/build.gradle.kts:21`; that log was overwritten by the next run, and a grep of the scratchpad and `build/reports` for the line found nothing, so this is prose. The `kotlinxSerialization` comment claimed Fleks 2.14 forces 1.11.0 through resolution. That is no longer true of vendored source, so it was rewritten (commit `0d5e620`). |
| Failing test first | build-logic: `issue215-red-buildlogic.log` shows the three behavioural failures before the rules existed (`the headless set ...`, `UDEA-MG-007 fails ...`, `DET002 fires on Fleks' random entity picks ...`). The run before that failed to compile `VendoredFleksTest` (`Unresolved reference: VendoredFleks`); that log was overwritten by the next run, so this is prose. The iOS red is `issue215-red-ios-on-maven-fleks.log`. |

**Unit-level mutations** (literal diffs in `issue215-mut-<name>.diff`, output in `.log`):

`det002-fleks-clause-off` -> `DeterminismScannerTest > DET002 fires on Fleks' random entity picks, which draw from the default Random() FAILED`, `18 tests completed, 1 failed`
```
-                (ref.owner in FLEKS_RANDOM_PICK_OWNERS && ref.member in FLEKS_RANDOM_PICKS)
+                (false && ref.owner in FLEKS_RANDOM_PICK_OWNERS && ref.member in FLEKS_RANDOM_PICKS)
```

`det002-owner-only` -> `DeterminismScannerTest > DET002 does not fire on Fleks' ordered entity reads() FAILED`, `18 tests completed, 1 failed`
```
-                (ref.owner in FLEKS_RANDOM_PICK_OWNERS && ref.member in FLEKS_RANDOM_PICKS)
+                (ref.owner in FLEKS_RANDOM_PICK_OWNERS)
```

`digest-no-path` -> `VendoredFleksTest > a renamed file moves the digest() FAILED`, `7 tests completed, 1 failed`
```
-            .joinToString(separator = "") { (path, hash) -> "$path  $hash\n" }
+            .joinToString(separator = "") { (_, hash) -> "$hash\n" }
```

`digest-no-eol-normalise` -> `VendoredFleksTest > line endings do not move the digest() FAILED`, `7 tests completed, 1 failed`
```
-            .map { it.relativeTo(sourceDirectory).invariantSeparatorsPath to ContractFreeze.digest(it.readText()) }
+            .map { it.relativeTo(sourceDirectory).invariantSeparatorsPath to it.readText().hashCode().toString() }
```

`mg007-json-allowed` -> `ModuleGraphRulesTest > UDEA-MG-007 fails any Udea module and any library but serialization on vendored Fleks() FAILED`, `31 tests completed, 1 failed`
```
             CoordinatePattern("org.jetbrains.kotlinx:kotlinx-serialization-bom"),
+            CoordinatePattern("org.jetbrains.kotlinx:kotlinx-serialization-json"),
```

`agent-json-on-classpath` (the `ev-json-on-main` diff, run as `:udea-agent:jvmTest --tests dev.wildware.udea.agent.AgentModuleBoundaryTest`) -> `AgentModuleBoundaryTest[jvm] > no banned artifact is on this module's classpath()[jvm] FAILED`

Not exercised: iOS tests on `iosArm64` hardware (CI runs the simulator only, as before). The iOS
klib compile for `iosArm64` of udea-fleks/core/gas/audio/replay ran here
(`issue215-ios-probe2.log`, `BUILD SUCCESSFUL`).

## 7. Regenerated files

None. No replicated component was added or removed; `net-protocol.lock` and
`expected-generated-hashes.txt` are untouched, and `udeaCheckProtocolLock` is green in the build.
`determinism-allowlist.txt`'s pin was moved by hand, as that file's own header prescribes, after
the re-read in `determinism-audit.md` section 2.0.
