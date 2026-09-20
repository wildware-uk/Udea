85e7794

# BRIEF-201: KMP convention plugin for runtime modules

Branch `issue-201-kmp-convention-plugin`, off `origin/kmp`, with `origin/kmp` at `6d5c8e1` merged in
(`8f11426`). `85e7794` is the last commit of the change; this brief is committed after it.

## 1. Evidence command

```
JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem sh gradlew :udea-annotations:allTests :udea-diagnostics:allTests
```

On `85e7794` (`--rerun-tasks` added so nothing came from cache), from
`evidence-85e7794-run2.log` (`...` marks skipped lines):

```
> Task :udea-annotations:wasmJsNodeTest
...
> Task :udea-diagnostics:wasmJsNodeTest
...
> Task :udea-annotations:testAndroidHostTest
...
> Task :udea-annotations:jvmTest
> Task :udea-diagnostics:testAndroidHostTest
> Task :udea-diagnostics:jvmTest
...
> Task :udea-annotations:iosSimulatorArm64Test SKIPPED
> Task :udea-annotations:allTests
...
> Task :udea-diagnostics:iosSimulatorArm64Test SKIPPED
> Task :udea-diagnostics:allTests
...
BUILD SUCCESSFUL in 12s
66 actionable tasks: 66 executed
```

Test counts read from the JUnit XML that run wrote (tests / skipped / failed):

| Module | `jvmTest` | `testAndroidHostTest` | `wasmJsNodeTest` | `iosSimulatorArm64Test` |
|---|---|---|---|---|
| `udea-annotations` | 12 / 0 / 0 | 2 / 0 / 0 | 2 / 0 / 0 | SKIPPED on Linux; **2 / 0 on CI macOS** |
| `udea-diagnostics` | 87 / 0 / 0 | 63 / 0 / 0 | 63 / 0 / 0 | SKIPPED on Linux; **63 / 0 on CI macOS** |

**iOS was not tested on this box.** Kotlin/Native compiles the iOS klibs here
(`compileKotlinIosSimulatorArm64` runs) but skips the simulator tests. They ran on CI; see section 5.

The first attempt at that run was killed: `Gradle build daemon disappeared unexpectedly (it may have
been killed or may have crashed)` (`evidence-85e7794.log`) while the shared box was short of memory.
The re-run straight after is the one quoted.

**It goes red when the feature is reverted.**

- On `origin/kmp` itself (a detached worktree at `6d5c8e1`), `evidence-on-origin-kmp-6d5c8e1.log`:
  ```
  * What went wrong:
  Cannot locate tasks that match ':udea-annotations:allTests' as task 'allTests' not found in project ':udea-annotations'.
  ```
- A red that is about the platforms and not about the task existing: putting back the one JVM-only
  call the conversion removed (mutation m1 below) fails the Wasm and iOS compilations:
  ```
  e: file:///srv/ssd1/workspace/Udea/.claude/worktrees/agent-a5665df735709efec/udea-diagnostics/src/commonMain/kotlin/dev/wildware/udea/diagnostics/assets/AssetCatalog.kt:104:17 Unresolved reference 'putIfAbsent' on receiver of type 'HashMap<String, AssetCatalogEntry>'.
  ```

## 2. Summary

**What.** Two convention plugins in `build-logic` for runtime modules, and the two leaf modules moved
onto one of them.

- `dev.wildware.udea.kotlin-multiplatform`: Kotlin Multiplatform on `jvm`, `android`, `wasmJs` (Node),
  `iosArm64`, `iosSimulatorArm64`. `dev.wildware.udea.kotlin-multiplatform-render`: the same without iOS, no
  consumer yet (#211). Both call one `UdeaMultiplatform.configure(project, ios)`.
- `dev.wildware.udea.kotlin-base`: the policy `dev.wildware.udea.kotlin-library` used to hold (explicit API, JDK 21 toolchain,
  stdlib pin + `udeaVerifyKotlinPin`, K2 plugin + `udeaVerifyCompilerPlugin`), now applied by the JVM
  convention and both multiplatform ones, so a module moving to KMP keeps every gate.
- `dev.wildware.udea.jvm-test-fixtures`: Gradle's `java-test-fixtures` cannot sit beside the KMP plugin, and JVM modules
  (`udea-agent`, `udea-agent-host`, `udea-assets-compiler`, `udea-core`) consume
  `testFixtures(project(":udea-diagnostics"))`. This publishes a `jvmTestFixtures`
  compilation under the same capability, so those consumers are unchanged.
- `udea-annotations`, `udea-diagnostics`: `src/main` -> `src/commonMain`; tests using a JVM API ->
  `src/jvmTest`, the rest -> `src/commonTest`. No API change.
- CI: an `ios-tests` job on `macos-latest` runs both modules' `iosSimulatorArm64Test` and then fails
  unless each wrote a report with tests in it and none skipped.
- Build-time modules (`udea-codegen`, `udea-compiler-plugin`, `udea-assets-compiler`, `udea-gradle`)
  keep their JVM conventions; two of them get the consumer fixes listed next.

**What the change broke, and how each was fixed.** All but the last item went red in a build or a
test run first; the last was found by grepping for the same kind of scan.

- Root `build.gradle.kts` applied `java` to every project; KGP refuses it beside KMP. It now configures
  Java 21 compatibility wherever `java` is present instead of forcing it on.
- The three classpath gates found no `runtimeClasspath` on a KMP module and failed ("matched none of
  the configurations"), and the stdlib pin failed on the unclassified KMP and AGP
  configurations of `udea-diagnostics`. `UdeaMultiplatform.jvmRole` maps each target's classpath to the JVM classpath it
  stands for (`wasmJsRuntimeClasspath` -> `runtimeClasspath`, `iosArm64CompileKlibraries` ->
  `compileClasspath`); rules match on that, failures still name the real classpath. AGP/KGP tool
  configurations got a `ToolClasspath` entry with a reason each.
- Once the gates could see Wasm, `UDEA-MG-001` flagged `org.jetbrains.kotlin:kotlin-stdlib-wasm-js`,
  which is the stdlib as Wasm resolves it. Allowed by name; `docs/module-graph.md` updated.
- `udea-render`'s `udeaVerifyHeadless` depended on `:<module>:classes`, which a KMP module does not
  have. Every Kotlin convention now registers `udeaMainBytecode` (`classes` on JVM; `jvmMainClasses` +
  `compileAndroidMain` on KMP), and `RepoLayout.classFiles` reads `build/classes/<lang>/{jvm,android}/main`
  for a KMP module. It picks **one** layout from the module's sources: the first version read both,
  and mutation m6 passed on the stale pre-conversion `build/classes/kotlin/main` (section 7).
- `moba:udeaValidateAssets` failed with UDEA0022 / `ExceptionInInitializerError`:
  `UdeaAssetScript` names its dependency jars, and the JVM jar is now `udea-diagnostics-jvm-<v>.jar`.
- `WallClockBudgetCensusTest` walks only `src/test` and `src/testFixtures`, so `udea-diagnostics`'
  budget test vanished from it. It now matches `src/<name>Test` and `src/<name>TestFixtures` too.
- Same class, found by grepping for it: `PluginOptionalRule` (production = `src/main`) and
  `ReplicatorApiShapeTest`'s FieldMask storage rule (`src/main`, `src/testFixtures`) are repository-wide
  source scans that silently stopped covering the two KMP modules. Both extended; m8 and m9 show each
  was blind before and sees now. Module-local scans (`udea-agent`'s `ModuleSources`, `udea-gas`,
  `udea-net`, `udea-assets`' `ResPathTest`) read only their own module and are left for that module's
  port ticket.

**Decisions** (each commented on #201 with the alternative and the way back):

- Android via AGP's `com.android.kotlin.multiplatform.library` (AGP 8.13.2, which runs on
  Gradle 8.13): target `android`, tests `testAndroidHostTest`. Not `androidTarget()` / `testDebugUnitTest`.
- `dev.wildware.udea.jvm-test-fixtures` rather than moving `LatencyBudget` into `jvmMain` (it would ship) or into a
  new module.
- Root stops forcing `java` rather than keeping a list of KMP modules to skip.
- Every target's classpath is governed, not only `jvm`.
- `AnnotationVocabularyTest` split: enum constants and the `@Target` compile fixture moved to
  `AuthorityVocabularyTest` in `commonTest`, so iOS has tests to run for `udea-annotations`. Four
  diagnostics test names lost their commas, which Kotlin/Native rejects.
- Catalog: `agp = 8.13.2`, `androidCompileSdk = 36` (the newest platform in this box's SDK),
  `androidMinSdk = 24` (a chosen floor, not a forced one).
- `local.properties` added to `.gitignore`: AGP reads `sdk.dir` from it and it was not ignored.

**Open, not fixed here:**

- `:udea-compiler-plugin:udeaVerifyPluginOptional` declares no repository sources as inputs, so it
  stays `UP-TO-DATE` across an edit to the code it scans: the first m8a run, without
  `--rerun` and with the plant in place, was `UP-TO-DATE` (section 7). Pre-existing,
  not KMP-related; m8 is quoted from runs with `--rerun`.
- `udeaVerifyDeterminism` reads `build/classes/<lang>/<sourceSet>` of `udea-core`, `udea-gas`,
  `udea-net`. None is converted here, so it is untouched; #203 (udea-core to KMP) and the tickets
  for the other two will meet the same layout change.
- The first CI run (`17e423d`, run 35148494618) failed "the FIR checkers fail a real build" with
  `e: The daemon has terminated unexpectedly on startup attempt #1`. The same probe compiled locally on
  this branch produced only the two UDEA0001/UDEA0003 lines, and the job passed on the next run
  (`85e7794`). Both latency-budget legs of that first run failed too. The Ubuntu leg's log
  (`ci-latency-ubuntu-job104970508809.log`) has `##[error]Failed to FinalizeArtifact: Received
  non-retryable error: Failed request: (403) Forbidden`, an artifact upload; the Windows leg's log was
  not saved. Both passed on the next run.

## 3. `sh gradlew build`

Baseline, `origin/kmp` at `6d5c8e1`, before any change (`baseline-build-6d5c8e1.log`, zero `FAILED`
lines). An earlier baseline at `6097ae7`, where the branch was cut, was also green.

```
BUILD SUCCESSFUL in 1m 19s
220 actionable tasks: 136 executed, 75 from cache, 9 up-to-date
```

This branch at `85e7794`, `build --continue --rerun-tasks` (`build-final-85e7794.log`, zero `FAILED`
lines):

```
BUILD SUCCESSFUL in 2m 2s
324 actionable tasks: 324 executed
```

- **Tasks this ticket turned green:** `:udea-annotations:allTests`, `:udea-diagnostics:allTests` (they did
  not exist on the baseline).
- **Baseline failures, unchanged:** none; the baseline had none.

KSP still runs against both modules' consumers on the JVM, in that same run:

```
> Task :udea-agent:kspKotlin
...
> Task :moba:kspKotlin
...
> Task :udea-codegen:testClasses
...
> Task :udea-codegen:test
```

`build-logic` is an included build that `build` does not test (`-p build-logic check --rerun-tasks`,
`buildlogic-final-85e7794.log`):

```
BUILD SUCCESSFUL in 1m 1s
13 actionable tasks: 13 executed
```

including `KotlinMultiplatformConventionTest` 6 tests and `MultiplatformClasspathsTest` 4, 0 failed.

Gates outside `check` (`udeaVerifyModuleGraph udeaVerifyMigration udeaLegacyReport udeaVerifyAgentsMd
--rerun-tasks`, `gates-85e7794.log`):

```
> Task :udeaVerifyAgentsMd
...
> Task :udeaLegacyReport
...
> Task :udea-annotations:udeaVerifyModuleGraph
...
> Task :udea-diagnostics:udeaVerifyModuleGraph
> Task :udeaVerifyModuleGraph
> Task :udeaVerifyMigration
...
BUILD SUCCESSFUL in 30s
36 actionable tasks: 36 executed
```

**GL.** The change touches `udea-render`'s build script and a test helper, not GL code; the GL tests
were run for real anyway:

```
xvfb-run -a -s "-screen 0 1280x720x24" \
  env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe \
  sh gradlew udeaGlTest udeaAgentGlTest -Pudea.render.requireGl=true
```

(with `JAVA_HOME` at 21.0.11), from `gl-85e7794.log`:

```
> Task :udea-agent-host:udeaAgentGlTest
> Task :udea-render:udeaGlTest
...
BUILD SUCCESSFUL in 11s
```

JUnit XML: `udeaGlTest` 21 tests in 6 classes, `udeaAgentGlTest` 8 in 2, 0 skipped, 0 failed.

`:moba:runUdpProof` and `:moba:runLaneShot` were not run: nothing here touches networking or
rendering output.

## 4. Images

None. This is build logic; there is nothing a person would see. None posted to the gallery.

## 5. Acceptance criteria

| Criterion | Proof |
|---|---|
| `allTests` green for both modules on this box, iOS skipped on Linux and said so | Section 1: `BUILD SUCCESSFUL`, both `iosSimulatorArm64Test SKIPPED`, counts per target from the XML |
| KSP still runs against both modules' consumers on the JVM | Section 3: `:udea-agent:kspKotlin`, `:moba:kspKotlin`, `:udea-codegen:test` executed in the `--rerun-tasks` build, as did `:moba:udeaValidateAssets` (red before the `UdeaAssetScript` fix) |
| CI runs the iOS simulator tests for both modules on a macOS runner | Run 35149245665, head `85e7794`, job "iOS simulator tests" (104974074069), `macos-latest`: conclusion `success`. From its log: `> Task :udea-annotations:iosSimulatorArm64Test`, `> Task :udea-diagnostics:iosSimulatorArm64Test`, `udea-annotations: 2 iOS tests ran, 0 skipped`, `udea-diagnostics: 63 iOS tests ran, 0 skipped` |
| (ticket) render variant without iOS | `KotlinMultiplatformConventionTest`: render convention targets are exactly `android, jvm, wasmJs` |
| (ticket) verifiers understand KMP modules rather than excluding them | `KotlinMultiplatformConventionTest`: the gates scan `jvmRuntimeClasspath`, `androidRuntimeClasspath`, `wasmJsRuntimeClasspath`, `iosArm64CompileKlibraries`, and a kotlinpoet dependency on `jvmMain` fails `UDEA-MG-001` naming `jvmRuntimeClasspath`; m3, m6, m7, m8, m9 |

The iOS job's assertion step can fail: run on Linux against this worktree, where the simulator tests
skip, it prints `::error::udea-annotations wrote no iOS test report - iosSimulatorArm64Test did not run`
and exits 1 (`ios-assert-step-linux.log`).

Run 35149245665 as a whole finished `success`: every job succeeded except the nightly and
non-blocking jobs, which were skipped.

## 6. Regenerated files

None. No replicated component was added or removed; `net-protocol.lock` and
`expected-generated-hashes.txt` are unchanged.

## 7. Mutations

Each entry: the literal diff from `mutations/<name>.diff` (the unstaged `gradlew` mode change
filtered out), the command, and result lines from `mutations/<name>.log`. Reverted after each run.
The runner added `--console=plain --continue` to each command.
Both files per mutation are in this session's scratchpad,
`/tmp/claude-1000/-srv-ssd1-workspace-Udea/f8817364-9f82-412e-8adb-dc60d9a1c7bd/scratchpad/mutations/`.

### m1-putIfAbsent: put back the JVM-only map call (on `8f11426`)

`sh gradlew :udea-annotations:allTests :udea-diagnostics:allTests`

```diff
diff --git a/udea-diagnostics/src/commonMain/kotlin/dev/wildware/udea/diagnostics/assets/AssetCatalog.kt b/udea-diagnostics/src/commonMain/kotlin/dev/wildware/udea/diagnostics/assets/AssetCatalog.kt
index 99399c4..07a50a1 100644
--- a/udea-diagnostics/src/commonMain/kotlin/dev/wildware/udea/diagnostics/assets/AssetCatalog.kt
+++ b/udea-diagnostics/src/commonMain/kotlin/dev/wildware/udea/diagnostics/assets/AssetCatalog.kt
@@ -101,7 +101,7 @@ public class AssetCatalog private constructor(
         entries.fold(LinkedHashMap<String, AssetCatalogEntry>(entries.size)) { map, entry ->
             // Not `putIfAbsent`, which is a JVM `Map` method and absent from common Kotlin; the
             // two agree here because an entry is never null.
-            if (entry.id !in map) map[entry.id] = entry
+            map.putIfAbsent(entry.id, entry)
             map
         }
```

```
> Task :udea-diagnostics:compileKotlinWasmJs FAILED
> Task :udea-diagnostics:compileKotlinIosSimulatorArm64 FAILED
```

### m2-no-ios: no iOS targets on the runtime convention (on `8f11426`)

`sh gradlew -p build-logic test --tests dev.wildware.udea.build.KotlinMultiplatformConventionTest`

```diff
diff --git a/build-logic/src/main/kotlin/dev/wildware/udea/build/UdeaMultiplatform.kt b/build-logic/src/main/kotlin/dev/wildware/udea/build/UdeaMultiplatform.kt
index bb63616..74a238a 100644
--- a/build-logic/src/main/kotlin/dev/wildware/udea/build/UdeaMultiplatform.kt
+++ b/build-logic/src/main/kotlin/dev/wildware/udea/build/UdeaMultiplatform.kt
@@ -73,7 +73,7 @@ internal object UdeaMultiplatform {
         // that needs a browser for its tests adds `browser()` itself.
         kotlin.wasmJs { nodejs() }
 
-        if (ios) {
+        if (false) {
             // No `iosX64`: spec section 3 names the two ARM targets.
             kotlin.iosArm64()
             kotlin.iosSimulatorArm64()
```

```
KotlinMultiplatformConventionTest > the classpath gates inspect a multiplatform module rather than finding nothing(File) FAILED
KotlinMultiplatformConventionTest > the runtime convention targets JVM, Android, Wasm and both iOS ARM targets(File) FAILED
```

### m3-no-roles: no role mapping, so the gates know only JVM names (on `8f11426`)

`sh gradlew -p build-logic test --tests dev.wildware.udea.build.KotlinMultiplatformConventionTest --tests dev.wildware.udea.build.MultiplatformClasspathsTest`

```diff
diff --git a/build-logic/src/main/kotlin/dev/wildware/udea/build/UdeaMultiplatform.kt b/build-logic/src/main/kotlin/dev/wildware/udea/build/UdeaMultiplatform.kt
index bb63616..3c4ab2e 100644
--- a/build-logic/src/main/kotlin/dev/wildware/udea/build/UdeaMultiplatform.kt
+++ b/build-logic/src/main/kotlin/dev/wildware/udea/build/UdeaMultiplatform.kt
@@ -47,6 +47,7 @@ internal object UdeaMultiplatform {
      */
     fun jvmRole(configurationName: String): String? {
         val match = TARGET_CLASSPATH.matchEntire(configurationName) ?: return null
+        if (match.value.isNotEmpty()) return null
         val (compilation, kind) = match.destructured
         val classpath = if (kind == "RuntimeClasspath") "RuntimeClasspath" else "CompileClasspath"
         return when (compilation) {
```

```
KotlinMultiplatformConventionTest > a leaf budget broken on the JVM target fails and names that target's classpath(File) FAILED
KotlinMultiplatformConventionTest > the classpath gates inspect a multiplatform module rather than finding nothing(File) FAILED
MultiplatformClasspathsTest > a target's compile and runtime classpaths are pinned, not excused as tools() FAILED
MultiplatformClasspathsTest > each target's main, test and fixture classpaths stand for their JVM counterparts() FAILED
MultiplatformClasspathsTest > every resolvable classpath of a converted module is classified by the stdlib pin() FAILED
```

### m4-mg001-no-wasm-stdlib: UDEA-MG-001 without the Wasm stdlib (on `8f11426`)

`sh gradlew :udea-annotations:udeaVerifyModuleGraph`

```diff
diff --git a/build-logic/src/main/kotlin/dev/wildware/udea/build/ModuleGraphRules.kt b/build-logic/src/main/kotlin/dev/wildware/udea/build/ModuleGraphRules.kt
index 7f98967..d55fd51 100644
--- a/build-logic/src/main/kotlin/dev/wildware/udea/build/ModuleGraphRules.kt
+++ b/build-logic/src/main/kotlin/dev/wildware/udea/build/ModuleGraphRules.kt
@@ -144,7 +144,6 @@ public object ModuleGraphRules {
             CoordinatePattern("org.jetbrains:annotations"),
             // The same stdlib, as Wasm resolves it (issue #201): `kotlin-stdlib`'s Wasm variant
             // is published as this module and reached only through `kotlin-stdlib` itself.
-            CoordinatePattern("org.jetbrains.kotlin:kotlin-stdlib-wasm-js"),
         ),
     )
```

```
> Task :udea-annotations:udeaVerifyModuleGraph FAILED
  UDEA-MG-001 :udea-annotations wasmJsRuntimeClasspath -> org.jetbrains.kotlin:kotlin-stdlib-wasm-js
```

### m5-no-fixture-capability: fixtures published without the capability (on `8f11426`)

`sh gradlew -p build-logic test --tests dev.wildware.udea.build.KotlinMultiplatformConventionTest`

```diff
diff --git a/build-logic/src/main/kotlin/dev.wildware.udea.jvm-test-fixtures.gradle.kts b/build-logic/src/main/kotlin/dev.wildware.udea.jvm-test-fixtures.gradle.kts
index 7dff672..3346c9a 100644
--- a/build-logic/src/main/kotlin/dev.wildware.udea.jvm-test-fixtures.gradle.kts
+++ b/build-logic/src/main/kotlin/dev.wildware.udea.jvm-test-fixtures.gradle.kts
@@ -75,7 +75,6 @@ fun fixturesVariant(name: String, usage: String, dependencyBuckets: List<String>
             attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, UdeaVersions.JVM_TOOLCHAIN)
             attribute(KotlinPlatformType.attribute, KotlinPlatformType.jvm)
         }
-        outgoing.capability(capability)
         outgoing.artifact(fixturesJar)
     }
 }
```

```
KotlinMultiplatformConventionTest > a JVM module consumes a multiplatform module's test fixtures through testFixtures()(File) FAILED
```

### m6-headless-scan-jvm-layout-only: headless scan reads no multiplatform layout (on `85e7794`)

`sh gradlew :udea-render:udeaVerifyHeadless`

```diff
diff --git a/udea-render/src/test/kotlin/dev/wildware/udea/render/support/RepoLayout.kt b/udea-render/src/test/kotlin/dev/wildware/udea/render/support/RepoLayout.kt
index 5e5e05b..8db2e99 100644
--- a/udea-render/src/test/kotlin/dev/wildware/udea/render/support/RepoLayout.kt
+++ b/udea-render/src/test/kotlin/dev/wildware/udea/render/support/RepoLayout.kt
@@ -35,7 +35,7 @@ internal object RepoLayout {
      * Wasm and iOS output is a klib rather than class files, so there is nothing there for a
      * bytecode scan to read.
      */
-    private val BYTECODE_TARGETS = listOf("jvm", "android")
+    private val BYTECODE_TARGETS = emptyList<String>()
 
     /**
      * Every `.class` file [module] compiled for [sourceSet], across every language directory
```

```
UdeaVerifyHeadlessTest > every designated module was actually scanned() FAILED
UdeaVerifyHeadlessTest > no headless module references a GL type() FAILED
> Task :udea-render:udeaVerifyHeadless FAILED
```

The first version of `classFiles` read the JVM layout *and* the target layout. Under the same
mutation it passed (`mutations/round1/m6-headless-scan-jvm-layout-only.log`: `BUILD SUCCESSFUL in 34s`),
because the pre-conversion `build/classes/kotlin/main` was still on disk. That is why it now chooses
one layout from the module's sources.

### m7-census-jvm-layout-only: census walks only JVM source-set names (on `8f11426`)

`sh gradlew :udea-gradle:test --tests dev.wildware.udea.gradle.ci.WallClockBudgetCensusTest`

```diff
diff --git a/udea-gradle/src/test/kotlin/dev/wildware/udea/gradle/ci/WallClockBudgetCensusTest.kt b/udea-gradle/src/test/kotlin/dev/wildware/udea/gradle/ci/WallClockBudgetCensusTest.kt
index 08d577f..ace65c4 100644
--- a/udea-gradle/src/test/kotlin/dev/wildware/udea/gradle/ci/WallClockBudgetCensusTest.kt
+++ b/udea-gradle/src/test/kotlin/dev/wildware/udea/gradle/ci/WallClockBudgetCensusTest.kt
@@ -236,7 +236,7 @@ class WallClockBudgetCensusTest {
             .onEnter { it.name !in PRUNED }
             .filter { it.isFile && it.extension == "kt" }
             .map { it.relativeTo(root).path.replace(File.separatorChar, '/') to it }
-            .filter { (path, _) -> TEST_SOURCE_SET.containsMatchIn(path) }
+            .filter { (path, _) -> "/src/test/" in path || "/src/testFixtures/" in path }
             .associate { (path, file) -> path to KotlinSource(file.readText()) }
         check(sources.isNotEmpty()) {
             "no Kotlin test source found under ${root.absolutePath}; the fence would pass over " +
```

```
WallClockBudgetCensusTest > the scan actually found sources to scan() FAILED
```

### m8a-plugin-optional-planted: plugin-optional rule as shipped, a reference planted in commonMain (on `85e7794`)

`sh gradlew :udea-compiler-plugin:udeaVerifyPluginOptional --rerun`

```diff
diff --git a/udea-diagnostics/src/commonMain/kotlin/dev/wildware/udea/diagnostics/Planted.kt b/udea-diagnostics/src/commonMain/kotlin/dev/wildware/udea/diagnostics/Planted.kt
new file mode 100644
index 0000000..4e01733
--- /dev/null
+++ b/udea-diagnostics/src/commonMain/kotlin/dev/wildware/udea/diagnostics/Planted.kt
@@ -0,0 +1,3 @@
+package dev.wildware.udea.diagnostics
+
+// dev.wildware.udea.compiler.UdeaCompilerPlugin
```

```
PluginOptionalTest > no production source in the repository references a compiler-plugin type() FAILED
```

Without `--rerun` the same plant passed: `mutations/round1/m8a-plugin-optional-planted.log` has
`> Task :udea-compiler-plugin:udeaVerifyPluginOptional UP-TO-DATE` and then `BUILD SUCCESSFUL in 5s`.
That is the pre-existing missing-inputs defect named in section 2.

### m8b-plugin-optional-planted-old-rule: control: the same plant, rule as it was before this change (on `85e7794`)

`sh gradlew :udea-compiler-plugin:udeaVerifyPluginOptional --rerun`

```diff
diff --git a/udea-compiler-plugin/src/test/kotlin/dev/wildware/udea/compiler/PluginOptionalRule.kt b/udea-compiler-plugin/src/test/kotlin/dev/wildware/udea/compiler/PluginOptionalRule.kt
index 5474cc2..5cc4709 100644
--- a/udea-compiler-plugin/src/test/kotlin/dev/wildware/udea/compiler/PluginOptionalRule.kt
+++ b/udea-compiler-plugin/src/test/kotlin/dev/wildware/udea/compiler/PluginOptionalRule.kt
@@ -47,8 +47,8 @@ object PluginOptionalRule {
         repoRoot.listFiles().orEmpty()
             .filter { it.isDirectory && (it.name.startsWith("udea-") || it.name == "moba") }
             .filter { it.name != OWNING_MODULE }
-            .flatMap { File(it, "src").listFiles().orEmpty().toList() }
-            .filter { it.isDirectory && (it.name == "main" || it.name.endsWith("Main")) }
+            .map { File(it, "src/main") }
+            .filter { it.isDirectory }
             .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" } }
             .sortedBy { it.invariantSeparatorsPath }
 
diff --git a/udea-diagnostics/src/commonMain/kotlin/dev/wildware/udea/diagnostics/Planted.kt b/udea-diagnostics/src/commonMain/kotlin/dev/wildware/udea/diagnostics/Planted.kt
new file mode 100644
index 0000000..4e01733
--- /dev/null
+++ b/udea-diagnostics/src/commonMain/kotlin/dev/wildware/udea/diagnostics/Planted.kt
@@ -0,0 +1,3 @@
+package dev.wildware.udea.diagnostics
+
+// dev.wildware.udea.compiler.UdeaCompilerPlugin
```

```
BUILD SUCCESSFUL in 3s
```

### m9a-fieldmask-planted: FieldMask storage rule as shipped, a stored mask planted in wasmJsMain (on `8f11426`)

`sh gradlew :udea-core:test --tests dev.wildware.udea.core.replication.ReplicatorApiShapeTest`

```diff
diff --git a/udea-diagnostics/src/wasmJsMain/kotlin/dev/wildware/udea/diagnostics/PlantedMask.kt b/udea-diagnostics/src/wasmJsMain/kotlin/dev/wildware/udea/diagnostics/PlantedMask.kt
new file mode 100644
index 0000000..37414d8
--- /dev/null
+++ b/udea-diagnostics/src/wasmJsMain/kotlin/dev/wildware/udea/diagnostics/PlantedMask.kt
@@ -0,0 +1,3 @@
+package dev.wildware.udea.diagnostics
+
+private class Holder(val stored: FieldMask)
```

```
ReplicatorApiShapeTest > no declared property outside udea-core and udea-net names FieldMask in its type() FAILED
```

### m9b-fieldmask-planted-old-scope: control: the same plant, scope as it was before this change (on `8f11426`)

`sh gradlew :udea-core:test --tests dev.wildware.udea.core.replication.ReplicatorApiShapeTest`

```diff
diff --git a/udea-core/src/test/kotlin/dev/wildware/udea/core/replication/ReplicatorApiShapeTest.kt b/udea-core/src/test/kotlin/dev/wildware/udea/core/replication/ReplicatorApiShapeTest.kt
index 8eb9e66..eea048f 100644
--- a/udea-core/src/test/kotlin/dev/wildware/udea/core/replication/ReplicatorApiShapeTest.kt
+++ b/udea-core/src/test/kotlin/dev/wildware/udea/core/replication/ReplicatorApiShapeTest.kt
@@ -136,11 +136,10 @@ class ReplicatorApiShapeTest {
         assertTrue(moduleRoots.isNotEmpty(), "expected sibling udea-* modules beside udea-core")
 
         val offenders = moduleRoots.flatMap { module ->
-            module.resolve("src").listFiles().orEmpty()
-                .filter { it.isDirectory && SHIPPED_SOURCE_SET.matches(it.name) }
-                .flatMap { sourceSet ->
-                    ModuleFiles.kotlinFilesIn(sourceSet).flatMap { file -> fieldMaskProperties(file) }
-                }
+            listOf("src/main", "src/testFixtures").flatMap { sourceSet ->
+                ModuleFiles.kotlinFilesIn(module.resolve(sourceSet))
+                    .flatMap { file -> fieldMaskProperties(file) }
+            }
         }
 
         assertEquals(
diff --git a/udea-diagnostics/src/wasmJsMain/kotlin/dev/wildware/udea/diagnostics/PlantedMask.kt b/udea-diagnostics/src/wasmJsMain/kotlin/dev/wildware/udea/diagnostics/PlantedMask.kt
new file mode 100644
index 0000000..37414d8
--- /dev/null
+++ b/udea-diagnostics/src/wasmJsMain/kotlin/dev/wildware/udea/diagnostics/PlantedMask.kt
@@ -0,0 +1,3 @@
+package dev.wildware.udea.diagnostics
+
+private class Holder(val stored: FieldMask)
```

```
BUILD SUCCESSFUL in 4s
```

TDD order: `KotlinMultiplatformConventionTest` was written first and failed 4/4 with
`Plugin [id: 'dev.wildware.udea.kotlin-multiplatform'] was not found`; its two gate tests were added before the
role mapping and failed with `matched none of the configurations [...]`.
