ab19e5f

# BRIEF-192: boot moba from a saved level file, and ban loops in asset scripts

Branch `issue-192-binary-test-level-kmp`, worktree
`/srv/ssd1/workspace/Udea/.claude/worktrees/agent-a359f0d7330f3ae2d`. It branched from
`origin/kmp` at `407123a`. `origin/kmp` is merged in twice:
- `322dde9` (#213, the old tree deleted) at `6019569`;
- `7d73496` (#228, named keys) at `312bd31`, with the replay fixtures regenerated inside that
  merge.

`ab19e5f` is the last code commit. This brief is committed on top of it and changes no code.

**Rounds.**
- Round 1's review found one gap: the loop ban missed `kotlin.repeat(...)` and an aliased import
  of `kotlin.repeat`. `3d2197a` fixed that in the syntactic scanner (pass 1).
- The owner then asked for a smarter check. `a6146ec` to `ab19e5f` add one: a K2 compiler
  checker that decides by what a call **resolves to**, running inside the real asset compile
  (pass 2). Pass 1 stays as early feedback. Section 2 has the spike, the design and the gaps.
- Everything round 1 passed is unchanged: the level file, `-Plevel`, the roster test, the boot
  shots and the replay fixtures. `origin/kmp` has not moved since `7d73496`.

Scratch artefacts quoted below are in
`/tmp/claude-1000/-srv-ssd1-workspace-Udea/1ad8c5e6-2def-4055-91d2-72acdfe77daf/scratchpad/issue192/`
(called `$S`).

## 1. The evidence command

```
JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem ANDROID_HOME=$HOME/Android/Sdk sh gradlew \
  :udea-core:jvmTest --tests dev.wildware.udea.core.level.LevelSceneTest \
  :moba:desktop:test --tests dev.wildware.moba.level.TestLevelRosterTest \
                     --tests dev.wildware.moba.level.MobaLevelLaunchTest \
  :udea-assets-compiler:test --tests dev.wildware.udea.assets.compiler.scan.LoopInAssetTest \
                             --tests dev.wildware.udea.assets.compiler.daemon.AssetDaemonTest \
                             --tests dev.wildware.udea.assets.compiler.AssetLoopResolutionTest \
  :udea-compiler-plugin:test --tests dev.wildware.udea.compiler.fir.UdeaAssetLoopCheckerTest \
  --continue
```

What each part holds:

- `TestLevelRosterTest` boots the game from `moba/game/levels/test_level.udealevel` and compares
  it with `moba/desktop/src/test/resources/levels/test_level.roster.txt`. That file is the world
  the old `test_level.udea.kts` built, captured in commit `3a72b83`, before the script was
  deleted: a whole-world hash and then one line per `NetId` (team, kind, exact position bits,
  health, component list). This is AC1.
- `MobaLevelLaunchTest` checks four things. The bundled level is the checked-in file. A level
  named at launch boots, and a match restart comes back into it. The level's clock and random
  streams come back with it. A missing path fails and names the path.
- `LevelSceneTest` is the `udea-core` scene that puts a saved level back into the world.
- `AssetLoopResolutionTest` is AC2's rule as the compiler sees it: each script goes through the
  real asset compile, with the plugin's checker in it. Bare `repeat`, `kotlin.repeat`, an aliased
  import, `forEach` over a range, a helper of the script's own, `for`/`while`/`do-while`,
  recursion, the build's own two-pass path, and the negatives (`let`, `apply`, `also`, `run`,
  `with`, `takeIf` and the asset DSL's builders).
- `UdeaAssetLoopCheckerTest` is the scope: the same shapes in ordinary Kotlin source compile
  clean, so the checker touches asset scripts only.
- `LoopInAssetTest` is pass 1, the early check. `AssetDaemonTest` is the live editor path.

**Green at `ab19e5f`**, from `$S/evidence-r5.log`, run with `--rerun-tasks` added so every task
executed in that invocation. The JUnit suite lines below are from that run's XML, copied into
`$S/evidence-r5-suites.txt` straight afterwards: the log was last written at 03:27:50 and every
timestamp is inside its 36 seconds. One
skip in `AssetLoopResolutionTest` is expected: the degrade test runs only with the plugin off
(section 3).

```
BUILD SUCCESSFUL in 36s
146 actionable tasks: 146 executed
Configuration cache entry reused.
```
```
<testsuite name="dev.wildware.udea.core.level.LevelSceneTest" tests="3" skipped="0" failures="0" errors="0" timestamp="2026-09-19T03:27:39.584Z"
<testsuite name="dev.wildware.moba.level.TestLevelRosterTest" tests="1" skipped="0" failures="0" errors="0" timestamp="2026-09-19T03:27:48.155Z"
<testsuite name="dev.wildware.moba.level.MobaLevelLaunchTest" tests="4" skipped="0" failures="0" errors="0" timestamp="2026-09-19T03:27:47.759Z"
<testsuite name="dev.wildware.udea.assets.compiler.scan.LoopInAssetTest" tests="6" skipped="0" failures="0" errors="0" timestamp="2026-09-19T03:27:46.195Z"
<testsuite name="dev.wildware.udea.assets.compiler.daemon.AssetDaemonTest" tests="11" skipped="0" failures="0" errors="0" timestamp="2026-09-19T03:27:39.315Z"
<testsuite name="dev.wildware.udea.assets.compiler.AssetLoopResolutionTest" tests="11" skipped="1" failures="0" errors="0" timestamp="2026-09-19T03:27:31.867Z"
<testsuite name="dev.wildware.udea.compiler.fir.UdeaAssetLoopCheckerTest" tests="1" skipped="0" failures="0" errors="0" timestamp="2026-09-19T03:27:21.399Z"
```

**Red when the feature is reverted.** Each mutation below is the literal `git diff` I saved at
the time, and the failing tests are spliced from that run's log. The `index` line of each diff
names the mutated file's starting blob, and every one of those blobs is the file as it is at
`ab19e5f` (`$S/blobcheck.txt`, all `SAME`). So every diff applies to the SHA above. When each
ran:
- mut1, mut2 and mut5 ran before the merges.
- mut9 to mut12 and mut14 to mut16 ran at `ac9058f`. `AssetLoopResolutionTest` was shorter
  then, so its line numbers in those logs are lower than today's.
- mut13 and mut17 ran at `f9974a5`.
- mut4b ran on the tree then committed as `2cbe5ca`. mut18, mut3 and mut6 to mut8 ran at
  `ab19e5f`.

(The round-1 brief's mut4 is dropped. mut4b replaces it, against a test that only pass 1 can
satisfy.)

### The level

**mut1: boot no longer loads the level** (`$S/mut1.diff`, `$S/mut1.log`)
```diff
diff --git a/moba/game/src/commonMain/kotlin/dev/wildware/moba/entry/MobaEntry.kt b/moba/game/src/commonMain/kotlin/dev/wildware/moba/entry/MobaEntry.kt
index efb7559..3eefe38 100644
--- a/moba/game/src/commonMain/kotlin/dev/wildware/moba/entry/MobaEntry.kt
+++ b/moba/game/src/commonMain/kotlin/dev/wildware/moba/entry/MobaEntry.kt
@@ -62,7 +62,6 @@ public object MobaEntry {
         val levels = host.game.levels
         val level = levels.read(host.ctx[MobaLevel.KEY].bytes)
         host.ctx.scenes.requestScene(MobaLevel.SCENE_ID)
-        levels.load(level, host.ctx.barrier)
         host.run(1)
         // The player is the unit the level saved with a `Player` component on it: the elite orc in
         // the orc clearing, where the old game dropped it. Resolved from the world rather than
```
```
MobaLevelLaunchTest > a launch level brings back the clock and the random streams it was saved with() FAILED
...
TestLevelRosterTest > a boot places every unit where the authored test level placed it() FAILED
...
5 tests completed, 2 failed
```

**mut2: a different level file goes in as `test_level.udealevel`.** I copied
`match.udealevel`, the mid-fight level `runLevelShotSave` writes, over it. (`$S/mut2.diff`,
`$S/mut2.log`)
```diff
 moba/game/levels/test_level.udealevel | Bin 55955 -> 77327 bytes
 1 file changed, 0 insertions(+), 0 deletions(-)
diff --git a/moba/game/levels/test_level.udealevel b/moba/game/levels/test_level.udealevel
index 5681886..ce0dc97 100644
Binary files a/moba/game/levels/test_level.udealevel and b/moba/game/levels/test_level.udealevel differ
```
```
TestLevelRosterTest > a boot places every unit where the authored test level placed it() FAILED
...
5 tests completed, 1 failed
```

**mut5: a loaded level gets its `NetId`s in the wrong order** (`$S/mut5.diff`, `$S/mut5.log`)
```diff
diff --git a/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/level/LevelService.kt b/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/level/LevelService.kt
index a1035bf..c285954 100644
--- a/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/level/LevelService.kt
+++ b/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/level/LevelService.kt
@@ -184,7 +184,7 @@ public class LevelService internal constructor(
     internal fun populate(level: Level, scope: SceneScope) {
         val document = level.document
         scope.world.loadSnapshot(document.world)
-        for (binding in document.netIds.sortedBy { it.netId.index }) scope.netIds.allocate(binding.entity)
+        for (binding in document.netIds.sortedByDescending { it.netId.index }) scope.netIds.allocate(binding.entity)
         ctx.physics.rebuildFrom(scope.world, scope.netIds)
     }
 
```
```
LevelSceneTest[jvm] > a level scene puts the saved entities into the world and leaves the clock and the streams running()[jvm] FAILED
...
3 tests completed, 1 failed
```
Only `LevelSceneTest` catches mut5. The moba tests stay green under it: at boot, `NetId`s are
bound by `LevelService.load`, which restores the saved bindings, so the only path that uses
`populate`'s order alone is a match restart. No moba test compares ids across a restart.

### The loop checker (pass 2, the guarantee)

**mut9: the checker no longer sees loop keywords** (`$S/mut9.diff`, `$S/mut9.log`)
```diff
diff --git a/udea-compiler-plugin/src/main/kotlin/dev/wildware/udea/compiler/fir/UdeaFirExtensionRegistrar.kt b/udea-compiler-plugin/src/main/kotlin/dev/wildware/udea/compiler/fir/UdeaFirExtensionRegistrar.kt
index f419790..c18c5dd 100644
--- a/udea-compiler-plugin/src/main/kotlin/dev/wildware/udea/compiler/fir/UdeaFirExtensionRegistrar.kt
+++ b/udea-compiler-plugin/src/main/kotlin/dev/wildware/udea/compiler/fir/UdeaFirExtensionRegistrar.kt
@@ -84,6 +84,6 @@ internal class UdeaFirAdditionalCheckers(
 
         /** Issue #192: loops in a `.udea.kts`. [UdeaAssetLoopChecker] is silent in any other file. */
         override val loopExpressionCheckers: Set<FirExpressionChecker<FirLoop>> =
-            setOf(UdeaAssetLoopChecker.Loops)
+            emptySet()
     }
 }
```
```
AssetLoopResolutionTest > a helper that loops inside is refused at the loop and at the call() FAILED
    org.opentest4j.AssertionFailedError at AssetLoopResolutionTest.kt:117

AssetLoopResolutionTest > for, while and do-while are refused, each at its own keyword() FAILED
    org.opentest4j.AssertionFailedError at AssetLoopResolutionTest.kt:131

9 tests completed, 2 failed
```

**mut10: contracts are ignored, so every lambda is a loop** (`$S/mut10.diff`, `$S/mut10.log`)
```diff
diff --git a/udea-compiler-plugin/src/main/kotlin/dev/wildware/udea/compiler/fir/UdeaAssetLoopChecker.kt b/udea-compiler-plugin/src/main/kotlin/dev/wildware/udea/compiler/fir/UdeaAssetLoopChecker.kt
index 1496934..16d90c0 100644
--- a/udea-compiler-plugin/src/main/kotlin/dev/wildware/udea/compiler/fir/UdeaAssetLoopChecker.kt
+++ b/udea-compiler-plugin/src/main/kotlin/dev/wildware/udea/compiler/fir/UdeaAssetLoopChecker.kt
@@ -105,7 +105,7 @@ internal object UdeaAssetLoopChecker {
             if (callee.hasAnnotation(UdeaAnnotations.ASSET_DSL, context.session)) return
 
             val mapping = expression.resolvedArgumentMapping ?: return
-            val runsOnce = parametersRunAtMostOnce(callee)
+            val runsOnce = emptySet<Int>()
             val repeatable = mapping.values.any { parameter ->
                 parameter.returnTypeRef.coneType.isSomeFunctionType(context.session) &&
                     callee.valueParameterSymbols.indexOf(parameter.symbol) !in runsOnce
```
```
AssetLoopResolutionTest > scope functions and the asset DSL's own builders are not loops() FAILED
    org.opentest4j.AssertionFailedError at AssetLoopResolutionTest.kt:184

9 tests completed, 1 failed
```

**mut11: `@AssetDsl` is ignored** (`$S/mut11.diff`, `$S/mut11.log`)
```diff
diff --git a/udea-compiler-plugin/src/main/kotlin/dev/wildware/udea/compiler/fir/UdeaAssetLoopChecker.kt b/udea-compiler-plugin/src/main/kotlin/dev/wildware/udea/compiler/fir/UdeaAssetLoopChecker.kt
index 1496934..05136cd 100644
--- a/udea-compiler-plugin/src/main/kotlin/dev/wildware/udea/compiler/fir/UdeaAssetLoopChecker.kt
+++ b/udea-compiler-plugin/src/main/kotlin/dev/wildware/udea/compiler/fir/UdeaAssetLoopChecker.kt
@@ -102,7 +102,6 @@ internal object UdeaAssetLoopChecker {
                 report(expression.source, "`$name` calls itself, which is a loop in an asset script.")
                 return
             }
-            if (callee.hasAnnotation(UdeaAnnotations.ASSET_DSL, context.session)) return
 
             val mapping = expression.resolvedArgumentMapping ?: return
             val runsOnce = parametersRunAtMostOnce(callee)
```
```
AssetLoopResolutionTest > scope functions and the asset DSL's own builders are not loops() FAILED
    org.opentest4j.AssertionFailedError at AssetLoopResolutionTest.kt:184

9 tests completed, 1 failed
```

**mut12: recursion is no longer a loop** (`$S/mut12.diff`, `$S/mut12.log`)
```diff
diff --git a/udea-compiler-plugin/src/main/kotlin/dev/wildware/udea/compiler/fir/UdeaAssetLoopChecker.kt b/udea-compiler-plugin/src/main/kotlin/dev/wildware/udea/compiler/fir/UdeaAssetLoopChecker.kt
index 1496934..e617631 100644
--- a/udea-compiler-plugin/src/main/kotlin/dev/wildware/udea/compiler/fir/UdeaAssetLoopChecker.kt
+++ b/udea-compiler-plugin/src/main/kotlin/dev/wildware/udea/compiler/fir/UdeaAssetLoopChecker.kt
@@ -98,10 +98,6 @@ internal object UdeaAssetLoopChecker {
             val callee = expression.calleeReference.toResolvedCallableSymbol() as? FirFunctionSymbol<*> ?: return
             val name = callee.name.asString()
 
-            if (context.containingDeclarations.any { it == callee }) {
-                report(expression.source, "`$name` calls itself, which is a loop in an asset script.")
-                return
-            }
             if (callee.hasAnnotation(UdeaAnnotations.ASSET_DSL, context.session)) return
 
             val mapping = expression.resolvedArgumentMapping ?: return
```
```
AssetLoopResolutionTest > a function that calls itself is refused at the recursive call() FAILED
    org.opentest4j.AssertionFailedError at AssetLoopResolutionTest.kt:152

9 tests completed, 1 failed
```

**mut15: the checker forgets it is scoped to asset scripts** (`$S/mut15.diff`, `$S/mut15.log`)
```diff
diff --git a/udea-compiler-plugin/src/main/kotlin/dev/wildware/udea/compiler/fir/UdeaAssetLoopChecker.kt b/udea-compiler-plugin/src/main/kotlin/dev/wildware/udea/compiler/fir/UdeaAssetLoopChecker.kt
index 1496934..a903443 100644
--- a/udea-compiler-plugin/src/main/kotlin/dev/wildware/udea/compiler/fir/UdeaAssetLoopChecker.kt
+++ b/udea-compiler-plugin/src/main/kotlin/dev/wildware/udea/compiler/fir/UdeaAssetLoopChecker.kt
@@ -67,7 +67,7 @@ internal object UdeaAssetLoopChecker {
         "declaration out, or keep a level's units in a `.udealevel` file."
 
     context(context: CheckerContext)
-    private fun inAssetScript(): Boolean = context.containingFile?.name?.endsWith(SCRIPT_SUFFIX) == true
+    private fun inAssetScript(): Boolean = true
 
     context(context: CheckerContext, reporter: DiagnosticReporter)
     private fun report(source: KtSourceElement?, detail: String) {
```
```
UdeaAssetLoopCheckerTest > loops, repeat, forEach and recursion in ordinary source are not asset loops() FAILED
    org.opentest4j.AssertionFailedError at UdeaAssetLoopCheckerTest.kt:19

1 test completed, 1 failed
```

**mut16: the rule id is not read back out of the compiler's message**, so every refusal
arrives as a generic compile failure (`$S/mut16.diff`, `$S/mut16.log`)
```diff
diff --git a/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/AssetCompiler.kt b/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/AssetCompiler.kt
index e20fa24..bfeb0f1 100644
--- a/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/AssetCompiler.kt
+++ b/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/AssetCompiler.kt
@@ -307,7 +307,7 @@ public class AssetCompiler(
         // `udea-compiler-plugin` prints `"<id>: <detail>"`; a registered id is that rule, and the
         // prefix is dropped so the message reads the same as the one pass 1 would have written.
         val ruleId = RULE_PREFIX.find(message)?.groupValues?.get(1)
-        val rule = ruleId?.let { UdeaRules.byId(it) }
+        val rule = ruleId?.let { null as dev.wildware.udea.diagnostics.UdeaRule? }
         return (rule ?: AssetCompilerRules.SCRIPT_COMPILATION_FAILED).diagnostic(
             message = if (rule == null) message else message.substringAfter(": "),
             span = SourceSpan(
```
```
AssetLoopResolutionTest > a repeat qualified with its package is refused() FAILED
    org.opentest4j.AssertionFailedError at AssetLoopResolutionTest.kt:65

AssetLoopResolutionTest > a helper of the script's own that runs its lambda twice is refused at the call() FAILED
    org.opentest4j.AssertionFailedError at AssetLoopResolutionTest.kt:98

AssetLoopResolutionTest > a repeat imported under another name is refused at the call() FAILED
    org.opentest4j.AssertionFailedError at AssetLoopResolutionTest.kt:74

AssetLoopResolutionTest > a function that calls itself is refused at the recursive call() FAILED
    org.opentest4j.AssertionFailedError at AssetLoopResolutionTest.kt:152

AssetLoopResolutionTest > a helper that loops inside is refused at the loop and at the call() FAILED
    org.opentest4j.AssertionFailedError at AssetLoopResolutionTest.kt:117

AssetLoopResolutionTest > forEach over a range is refused() FAILED
    org.opentest4j.AssertionFailedError at AssetLoopResolutionTest.kt:80

AssetLoopResolutionTest > a bare repeat is refused at the call() FAILED
    org.opentest4j.AssertionFailedError at AssetLoopResolutionTest.kt:54

AssetLoopResolutionTest > for, while and do-while are refused, each at its own keyword() FAILED
    org.opentest4j.AssertionFailedError at AssetLoopResolutionTest.kt:131

9 tests completed, 8 failed
```

### How the checker reaches the asset compile

**mut13: the asset compiler no longer carries the plugin** (`$S/mut13.diff`, `$S/mut13.log`)
```diff
diff --git a/udea-assets-compiler/build.gradle.kts b/udea-assets-compiler/build.gradle.kts
index 7ba6012..27b07ba 100644
--- a/udea-assets-compiler/build.gradle.kts
+++ b/udea-assets-compiler/build.gradle.kts
@@ -39,7 +39,6 @@ dependencies {
     // It follows `-Pudea.compilerPlugin.enabled` like every other use of the plugin, so the
     // degrade procedure in `docs/compiler-plugin.md` takes it out of the asset compile too: a
     // Kotlin release that breaks the plugin must not break every asset build with it.
-    if (compilerPluginEnabled) runtimeOnly(project(":udea-compiler-plugin"))
     // `@AssetDsl`, the asset DSL's once-only lambda promise the plugin's loop checker trusts.
     implementation(project(":udea-annotations"))
 
```
```
AssetLoopResolutionTest > a repeat qualified with its package is refused() FAILED
    org.opentest4j.AssertionFailedError at AssetLoopResolutionTest.kt:85

AssetLoopResolutionTest > a helper of the script's own that runs its lambda twice is refused at the call() FAILED
    org.opentest4j.AssertionFailedError at AssetLoopResolutionTest.kt:121

AssetLoopResolutionTest > a repeat imported under another name is refused at the call() FAILED
    org.opentest4j.AssertionFailedError at AssetLoopResolutionTest.kt:95

AssetLoopResolutionTest > a function that calls itself is refused at the recursive call() FAILED
    org.opentest4j.AssertionFailedError at AssetLoopResolutionTest.kt:178

AssetLoopResolutionTest > a helper that loops inside is refused at the loop and at the call() FAILED
    org.opentest4j.AssertionFailedError at AssetLoopResolutionTest.kt:141

AssetLoopResolutionTest > forEach over a range is refused() FAILED
    org.opentest4j.AssertionFailedError at AssetLoopResolutionTest.kt:102

AssetLoopResolutionTest > a bare repeat is refused at the call() FAILED
    org.opentest4j.AssertionFailedError at AssetLoopResolutionTest.kt:73

AssetLoopResolutionTest > for, while and do-while are refused, each at its own keyword() FAILED
    org.opentest4j.AssertionFailedError at AssetLoopResolutionTest.kt:156

10 tests completed, 8 failed, 1 skipped
```

**mut17: the plugin stays on when the build says it is disabled.** Run with
`-Pudea.compilerPlugin.enabled=false` (`$S/mut17.diff`, `$S/mut17.log`)
```diff
diff --git a/udea-assets-compiler/build.gradle.kts b/udea-assets-compiler/build.gradle.kts
index 7ba6012..3ce5855 100644
--- a/udea-assets-compiler/build.gradle.kts
+++ b/udea-assets-compiler/build.gradle.kts
@@ -39,7 +39,7 @@ dependencies {
     // It follows `-Pudea.compilerPlugin.enabled` like every other use of the plugin, so the
     // degrade procedure in `docs/compiler-plugin.md` takes it out of the asset compile too: a
     // Kotlin release that breaks the plugin must not break every asset build with it.
-    if (compilerPluginEnabled) runtimeOnly(project(":udea-compiler-plugin"))
+    runtimeOnly(project(":udea-compiler-plugin"))
     // `@AssetDsl`, the asset DSL's once-only lambda promise the plugin's loop checker trusts.
     implementation(project(":udea-annotations"))
 
```
```
AssetLoopResolutionTest > with the plugin disabled the compile degrades to pass 1 alone() FAILED
    org.opentest4j.AssertionFailedError at AssetLoopResolutionTest.kt:185

10 tests completed, 1 failed, 8 skipped
```

### Both passes together

**mut18: the build reports a loop twice, once from each pass** (`$S/mut18.diff`, `$S/mut18.log`)
```diff
diff --git a/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/pipeline/AssetPipeline.kt b/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/pipeline/AssetPipeline.kt
index 2ebbee3..e3b5cb5 100644
--- a/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/pipeline/AssetPipeline.kt
+++ b/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/pipeline/AssetPipeline.kt
@@ -79,7 +79,7 @@ public object AssetPipeline {
         // author with the consequence and not the defect.
         val sink = DiagnosticSink()
         sink.reportAll(scan.diagnostics)
-        sink.reportAll(result.diagnostics.notAlreadyIn(scan.diagnostics))
+        sink.reportAll(result.diagnostics)
         sink.reportAll(validated.diagnostics)
         val report = sink.build()
         return Compiled(
```
```
AssetLoopResolutionTest > the build reports a loop both passes find once, at pass 1's span() FAILED
    java.lang.IllegalArgumentException at AssetLoopResolutionTest.kt:261

11 tests completed, 1 failed, 1 skipped
```

**mut14: the live daemon reports a loop twice** (`$S/mut14.diff`, `$S/mut14.log`)
```diff
diff --git a/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/daemon/AssetDaemon.kt b/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/daemon/AssetDaemon.kt
index bc53bc0..d181f58 100644
--- a/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/daemon/AssetDaemon.kt
+++ b/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/daemon/AssetDaemon.kt
@@ -281,7 +281,7 @@ public class AssetDaemon(
         // Pass 1's own diagnostics first, as `AssetPipeline.compileAndValidate` reports them: a
         // script the build refuses in its syntactic pass - a loop (issue #192) - must not be one
         // the live daemon applies. A loop both passes found is reported once, at pass 1's span.
-        return scan.diagnostics + result.diagnostics.notAlreadyIn(scan.diagnostics)
+        return scan.diagnostics + result.diagnostics
     }
 
     /**
```
```
AssetDaemonTest > a loop in an edited script is rejected with the loop rule at the loop() FAILED
    java.lang.IllegalArgumentException at AssetDaemonTest.kt:267

10 tests completed, 1 failed
```

**mut4b: the live daemon drops pass 1's diagnostics again** (`$S/mut4b.diff`, `$S/mut4b.log`)
```diff
diff --git a/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/daemon/AssetDaemon.kt b/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/daemon/AssetDaemon.kt
index bc53bc0..731e9d0 100644
--- a/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/daemon/AssetDaemon.kt
+++ b/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/daemon/AssetDaemon.kt
@@ -281,7 +281,7 @@ public class AssetDaemon(
         // Pass 1's own diagnostics first, as `AssetPipeline.compileAndValidate` reports them: a
         // script the build refuses in its syntactic pass - a loop (issue #192) - must not be one
         // the live daemon applies. A loop both passes found is reported once, at pass 1's span.
-        return scan.diagnostics + result.diagnostics.notAlreadyIn(scan.diagnostics)
+        return result.diagnostics
     }
 
     /**
```
```
AssetDaemonTest > a finding only the syntactic pass makes reaches the agent() FAILED
    java.util.NoSuchElementException at AssetDaemonTest.kt:310

11 tests completed, 1 failed
```

### Pass 1 (the early check)

**mut3: the scanner no longer looks for loops** (`$S/mut3.diff`, `$S/mut3.log`)
```diff
diff --git a/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/scan/UdeaDeclarationScanner.kt b/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/scan/UdeaDeclarationScanner.kt
index d37a867..cdf7af2 100644
--- a/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/scan/UdeaDeclarationScanner.kt
+++ b/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/scan/UdeaDeclarationScanner.kt
@@ -268,7 +268,6 @@ public class UdeaDeclarationScanner @JvmOverloads constructor(
             collectFileConstants(statements)
             statements.forEach { visitStatement(it, emptyMap()) }
             collectReferences(ktFile)
-            collectLoops(ktFile)
         }
 
         /**
```
```
LoopInAssetTest > a repeat qualified with its package is still a repeat(Path) FAILED
    java.util.NoSuchElementException at LoopInAssetTest.kt:104

LoopInAssetTest > a repeat is an error that points at the repeat(Path) FAILED
    java.util.NoSuchElementException at LoopInAssetTest.kt:44

LoopInAssetTest > for, while and do-while are errors too, each at its own line(Path) FAILED
    org.opentest4j.AssertionFailedError at LoopInAssetTest.kt:67

LoopInAssetTest > a loop inside a declaration's lambda is found(Path) FAILED
    org.opentest4j.AssertionFailedError at LoopInAssetTest.kt:91

LoopInAssetTest > an import of repeat is refused, and so is every call through its alias(Path) FAILED
    org.opentest4j.AssertionFailedError at LoopInAssetTest.kt:123

17 tests completed, 5 failed
```

**mut6: an import of `kotlin.repeat` is no longer refused** (`$S/mut6.diff`, `$S/mut6.log`)
```diff
diff --git a/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/scan/UdeaDeclarationScanner.kt b/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/scan/UdeaDeclarationScanner.kt
index d37a867..23119cd 100644
--- a/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/scan/UdeaDeclarationScanner.kt
+++ b/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/scan/UdeaDeclarationScanner.kt
@@ -433,7 +433,6 @@ public class UdeaDeclarationScanner @JvmOverloads constructor(
                 loops += loop to keyword
             }
             val repeatImports = ktFile.importDirectives.filter { it.importedFqName?.asString() == REPEAT_FQ_NAME }
-            for (directive in repeatImports) loops += directive to REPEAT_CALLEE
             val repeatNames = setOf(REPEAT_CALLEE) + repeatImports.mapNotNull { it.aliasName }
             for (call in PsiTreeUtil.collectElementsOfType(ktFile, KtCallExpression::class.java)) {
                 val callee = call.calleeExpression?.text ?: continue
```
```
LoopInAssetTest > an import of repeat is refused, and so is every call through its alias(Path) FAILED
...
6 tests completed, 1 failed
```

**mut7: a call through the import's alias is no longer a loop** (`$S/mut7.diff`, `$S/mut7.log`)
```diff
diff --git a/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/scan/UdeaDeclarationScanner.kt b/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/scan/UdeaDeclarationScanner.kt
index d37a867..228a323 100644
--- a/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/scan/UdeaDeclarationScanner.kt
+++ b/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/scan/UdeaDeclarationScanner.kt
@@ -434,7 +434,7 @@ public class UdeaDeclarationScanner @JvmOverloads constructor(
             }
             val repeatImports = ktFile.importDirectives.filter { it.importedFqName?.asString() == REPEAT_FQ_NAME }
             for (directive in repeatImports) loops += directive to REPEAT_CALLEE
-            val repeatNames = setOf(REPEAT_CALLEE) + repeatImports.mapNotNull { it.aliasName }
+            val repeatNames = setOf(REPEAT_CALLEE)
             for (call in PsiTreeUtil.collectElementsOfType(ktFile, KtCallExpression::class.java)) {
                 val callee = call.calleeExpression?.text ?: continue
                 if (callee !in repeatNames) continue
```
```
LoopInAssetTest > an import of repeat is refused, and so is every call through its alias(Path) FAILED
...
6 tests completed, 1 failed
```

**mut8: `kotlin.repeat(...)` is no longer a loop** (`$S/mut8.diff`, `$S/mut8.log`)
```diff
diff --git a/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/scan/UdeaDeclarationScanner.kt b/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/scan/UdeaDeclarationScanner.kt
index d37a867..2fc03f5 100644
--- a/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/scan/UdeaDeclarationScanner.kt
+++ b/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/scan/UdeaDeclarationScanner.kt
@@ -441,7 +441,7 @@ public class UdeaDeclarationScanner @JvmOverloads constructor(
                 val qualified = call.parent as? KtQualifiedExpression
                 if (qualified == null || qualified.selectorExpression !== call) {
                     loops += call to REPEAT_CALLEE
-                } else if (qualified is KtDotQualifiedExpression && callee == REPEAT_CALLEE &&
+                } else if (false && qualified is KtDotQualifiedExpression && callee == REPEAT_CALLEE &&
                     qualified.receiverExpression.text == REPEAT_PACKAGE
                 ) {
                     loops += qualified to REPEAT_CALLEE
```
```
LoopInAssetTest > a repeat qualified with its package is still a repeat(Path) FAILED
...
6 tests completed, 1 failed
```

## 2. Summary

**The level is a saved file now.** `moba/game/levels/test_level.udealevel` holds the world that
`test_level.udea.kts` used to build. It was written in `3a72b83`, the one commit where the script
and the file both exist, by a conversion task. That task boots the scripted level and saves it
through #191's `LevelService`. The file is byte-identical to the one the pre-port attempt saved
at `5e53e31`. The next commit (`c51b96e`) switched the game to the file and deleted the script,
`TestLevelScene` and the conversion task.

- `:moba:game`'s `udeaBundleResources` packages `levels/` next to the asset bundle, so the jar and
  both APKs carry `levels/test_level.udealevel` (the listings are under "Also checked" in section 5).
- **Boot** (`MobaEntry.seed`): in one barrier drain it swaps to a new `udea-core` `LevelScene` over
  the bytes, then runs #191's full `LevelService.load`. The scene gives the world an active
  scene, which snapshot restore and match restart need. The load brings back the saved clock,
  random streams and level sections.
- **Match restart** swaps back to the same `LevelScene`.
- `LevelScene` is in `udea-core` because it needs `LevelService`'s internal document. It reaches
  the service through an internal `SceneScope.levels`, which `UdeaGameDef` sets. Nothing was
  added to `GameContext`.
- **`-Plevel=<path>`** is on `:moba:desktop`. Every `JavaExec` there forwards it, resolved against
  the repo root, as `-Dmoba.level`. `MobaLaunchLevel.bytes()` reads it for `run` (headless and
  Kool), `runServer`, `runClient` and the shot mains. A path that is not a file throws and names
  the path; it does not fall back to the default.
- `:moba:game` still reads no system property. `MobaGame.definition/host` take the bytes as an
  argument, defaulting to the bundled level.
- `runLevelShotBoot` is new, beside the existing `runLevelShot*` phases. It photographs a boot
  from whatever level `-Plevel` names.

**The loop ban is `UDEA0015` (`UdeaRules.LOOP_IN_ASSET`), and two passes produce it.**

**The spike: K2, and the plugin is honoured.** Before building anything I ran a throwaway test
(`$S/SpikeScriptPluginTest.kt`, not committed) that compiled small scripts through the same
Kotlin scripting host the asset compiler uses. From `$S/spike1.log`:
```
    SPIKE[no-plugin] success=true
...
    SPIKE[plugin] success=true
...
    SPIKE[plugin]   WARNING The Udea K2 compiler plugin is loaded and its FIR checkers are running. @Position(line=1, col=1, absolutePos=null)
...
    SPIKE[k2-feature] success=true
...
    SPIKE[k2-feature-lv19] success=false
    SPIKE[k2-feature-lv19]   ERROR This script compiler implementatione is not compatible with Kotlin 1.9 and earlier @null
```
- `no-plugin` compiled a probe script with nothing extra. `plugin` compiled the same script
  with the plugin's jars passed as `-Xplugin`, and got the plugin's own "loaded" warning, so the
  plugin's FIR checkers run inside a script compile.
- The `-Xplugin` turned out to be unnecessary; see "How the checker gets into the asset compile"
  below.
- `k2-feature` compiled a `when` with a guard (`is Int if a > 0`), which only K2 accepts.
  The same script with `-language-version 1.9`
  was refused by the host itself, so the scripting host is K2-only. (The typo "implementatione"
  is the compiler's.)
- One more run in the log, `plugin-bad-option`, is elided above. It is not part of either answer.

So route A (a checker in the script compile) works, and route B (bytecode inspection) was not
needed.

**Pass 2, the guarantee: `UdeaAssetLoopChecker` in `udea-compiler-plugin`.** It runs only in
files named `*.udea.kts`, and it refuses:
- every `for`, `while` and `do`-`while`, at the keyword;
- any call that hands a lambda to a function that may run it more than once, at the call. A
  function is trusted with a lambda only when:
  - it carries the new `@AssetDsl` marker (in `udea-annotations`); or
  - its contract says `callsInPlace(..., EXACTLY_ONCE)` or `AT_MOST_ONCE` for that parameter,
    as `let`, `apply`, `also`, `run`, `with` and `takeIf` do.
- a function that calls itself, at the recursive call.

Because it goes by the resolved function, the spelling does not matter: `repeat`,
`kotlin.repeat`, `import kotlin.repeat as times` and `(1..3).forEach` are all the same call to
the compiler. A helper the script defines itself is refused at its call too. There is no loop
keyword in it, only the resolved function's missing promise.

`@AssetDsl` is on the four DSL functions that take lambdas: `character`, the lambda form of
`blueprint`, the lambda form of `level`, and `EntityScope.entity`. Each runs its lambdas once,
through `apply`.

**How the checker gets into the asset compile.** `udea-assets-compiler` depends on the plugin
`runtimeOnly`. The Kotlin scripting host registers any compiler plugin it finds on its own
classpath, the same way it finds `kotlin-scripting-compiler-embeddable`. My first version also
passed `-Xplugin=<jar>`. A mutation that removed that flag stayed green, which showed it was
redundant, so `ac9058f` removed it. The mutation that matters now is mut13: remove the
dependency and eight refusals go red.

- No production source names a plugin type. Spec section 7 says the plugin must never be
  required, and `PluginOptionalTest` enforces it.
- The dependency follows `-Pudea.compilerPlugin.enabled`, so the documented degrade procedure
  turns the asset checker off too (mut17). The build is green both ways (section 3).
- The plugin reports `UDEA0015: <message>`. `AssetCompiler` maps that prefix back to the rule
  through `UdeaRules.byId` and strips it from the message (mut16).

**Pass 1, early feedback: the syntactic scanner.** Its code is unchanged since `3d2197a`; only its
KDoc changed, to say it is not the guarantee. It flags `for`, `while`, `do`-`while`, `repeat`,
`kotlin.repeat` and an import of `kotlin.repeat` (at the import, and at every call through an
alias). It runs in `udeaScanAssets`, so it reports the shapes it knows before anything is
compiled.

**A loop both passes find is reported once.** Pass 1's copy spans the whole call, while pass 2's
carries only a start position, so the sink's rule-and-span dedupe could not join them. A small
`notAlreadyIn` filter drops a pass-2 finding at the same rule, file, line and column. It is used
in both the build (`AssetPipeline`, mut18) and the live daemon (`AssetDaemon`, mut14).

The live `AssetDaemon` (the `assets.*` tools) used to drop every pass-1 diagnostic. It now keeps
them, as the build already did, so the editor cannot save a loop the next build would refuse
(mut4b).

**Decisions**, each commented on #192:

- Rule id `UDEA0015`, in the shared `UdeaRules`. The alternative was the asset compiler's
  stopgap `UDEA002x` band, whose own KDoc says it is waiting to move into `UdeaRules`.
- **The route**: resolved calls in the K2 script compile. This comment replaced my round-1 comment
  about how far the ban follows `repeat`. It also reverses that comment's point about `forEach`:
  `listOf(...).forEach { }` is now refused.
- Trust is by marker (`@AssetDsl`) or by contract. The rejected alternative was "anything in the
  asset compiler's package", because a marker is a promise someone has to make on purpose.
- A restart reloads the exact saved layout. Before, it rescattered units from the `Spawn` stream,
  and the file has no cluster centres left to scatter around. This is why the replay fixtures
  moved; section 6 has the numbers.
- `MatchState.seed` stays. Removing it would move a replicated component and the wire lock.

**What the checker refused that was not a moba asset.** Each was rewritten without a loop:
- the compiler fixture `sounds.udea.kts` declared two cues with `forEach`;
- `WorkerTest`'s memory-hog script grew a list in a `while` loop. It is now one large
  allocation, which still exceeds the worker's heap;
- `AssetsToolsetTest`'s five referrers were written with `repeat(5)`. That one was found by
  pass 1 before round 1, and fixed in `02f2d7f`.

**Not covered, and why.**
- Mutual recursion (`a` calls `b` calls `a`). The checker refuses only a call to a function that
  lexically contains it.
- A function reference, `val r = ::repeat; r(2) { }`. The call resolves to `invoke` on a function
  type, which has no contract, so it should be refused. I have not run it.
- `example-assets/`, the retired corpus. No test compiles it through pass 2: `ExampleScanTest`
  only scans it and its golden records declarations, not diagnostics. So no file there is
  refused by the build. If one were compiled, `level/test_level.udea.kts` (three `repeat`
  loops) would be. It is not a moba asset, so it is outside AC3.

**Merges.**
- The #213 merge had one textual conflict: a comment in the compiler fixture
  `test_level.udea.kts` that named the corpus path #213 moved. I resolved it to the new
  `example-assets/` path.
- The #228 merge had three conflicts.
  - An import block in `MigratedCorpusBundleTest`. I kept #228's `InputKey` and dropped `Level`,
    which this branch no longer uses.
  - The two replay fixtures. I regenerated them rather than merging them (section 6).

## 3. `sh gradlew build`

Run alone on the box, at `ab19e5f`: `sh gradlew build --continue`, with no `-x`. Spliced from
`$S/build-r4.log`:

```
BUILD SUCCESSFUL in 1m 16s
908 actionable tasks: 56 executed, 82 from cache, 770 up-to-date
Configuration cache entry reused.
```

**The degrade leg**, also at `ab19e5f`:
`sh gradlew build udeaVerifyCompilerPlugin -Pudea.compilerPlugin.enabled=false --continue`.
Spliced from `$S/build-r4-plugin-off.log`:

```
BUILD SUCCESSFUL in 1m 16s
908 actionable tasks: 44 executed, 116 from cache, 748 up-to-date
Configuration cache entry reused.
```
In that run `AssetLoopResolutionTest` reported 11 tests with 9 skipped: the refusals skip and
the degrade test and the negatives run. I read that from the JUnit XML before the evidence
re-run overwrote it, and did not keep a copy.

**The gates outside `check`**, `sh gradlew udeaVerifyModuleGraph udeaVerifyAgentsMd`
(`$S/r4-gates.log`). `docs/module-graph.md` now lists the new arrows
(`udea-assets-compiler` to `udea-annotations`, and to `udea-compiler-plugin` at run time).
```
BUILD SUCCESSFUL in 3s
164 actionable tasks: 14 executed, 30 from cache, 120 up-to-date
Configuration cache entry reused.
```

The task count is 908 in these runs and 917 in `$S/build-r3b.log` at `f9974a5`. The difference is
the included `build-logic` build, which runs only when the configuration cache is stored, not
when it is reused. `build-r4.log` has no `> Task :build-logic` line and `build-r3b.log` has 12.

**GL, run for real**, at `ab19e5f` (`$S/gl-r4.log`). The branch touches no `udea-render` code,
but the level shots open a Kool context, so I ran the GL suites under xvfb:

```
xvfb-run -a -s "-screen 0 1280x720x24" env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe \
  sh gradlew udeaGlTest udeaAgentGlTest -Pudea.render.requireGl=true
```
```
> Task :udea-agent-host:udeaAgentGlTest
> Task :udea-render:udeaGlTest
BUILD SUCCESSFUL in 56s
```
The JUnit XML shows every GL suite ran, and none was skipped. I copied the suite lines out of the
XML straight after the run into `$S/gl-r4-suites.txt`, because any later build without a display
rewrites those files with every test skipped:
```
<testsuite name="dev.wildware.udea.render.gl.GlCaptureDeterminismTest" tests="1" skipped="0" failures="0" errors="0" timestamp="2026-09-19T03:28:52.265Z"
<testsuite name="dev.wildware.udea.render.gl.GlCaptureTest" tests="1" skipped="0" failures="0" errors="0" timestamp="2026-09-19T03:28:54.987Z"
<testsuite name="dev.wildware.udea.render.gl.GlKoolInputTest" tests="1" skipped="0" failures="0" errors="0" timestamp="2026-09-19T03:28:59.766Z"
<testsuite name="dev.wildware.udea.render.gl.GlKoolKeyTableTest" tests="1" skipped="0" failures="0" errors="0" timestamp="2026-09-19T03:29:08.060Z"
<testsuite name="dev.wildware.udea.render.gl.GlKoolPointerTest" tests="1" skipped="0" failures="0" errors="0" timestamp="2026-09-19T03:29:15.725Z"
<testsuite name="dev.wildware.udea.render.gl.GlModelRenderTest" tests="1" skipped="0" failures="0" errors="0" timestamp="2026-09-19T03:29:20.026Z"
<testsuite name="dev.wildware.udea.render.gl.GlOverlayIsolationTest" tests="1" skipped="0" failures="0" errors="0" timestamp="2026-09-19T03:29:23.692Z"
<testsuite name="dev.wildware.udea.render.gl.GlUiLayerTest" tests="1" skipped="0" failures="0" errors="0" timestamp="2026-09-19T03:29:27.096Z"
<testsuite name="dev.wildware.udea.render.gl.KoolThreadShutdownTest" tests="1" skipped="0" failures="0" errors="0" timestamp="2026-09-19T03:29:29.239Z"
<testsuite name="dev.wildware.udea.render.gl.OffscreenBackendExplodingCaptureTest" tests="1" skipped="0" failures="0" errors="0" timestamp="2026-09-19T03:29:31.155Z"
<testsuite name="dev.wildware.udea.render.gl.OffscreenBackendSecondCreateTest" tests="1" skipped="0" failures="0" errors="0" timestamp="2026-09-19T03:29:33.266Z"
<testsuite name="dev.wildware.udea.render.gl.OffscreenBackendShutdownTest" tests="1" skipped="0" failures="0" errors="0" timestamp="2026-09-19T03:29:34.424Z"
<testsuite name="dev.wildware.udea.render.gl.OffscreenBackendTest" tests="2" skipped="0" failures="0" errors="0" timestamp="2026-09-19T03:29:36.389Z"
<testsuite name="dev.wildware.udea.agent.host.gl.OffscreenRenderToolsTest" tests="1" skipped="0" failures="0" errors="0" timestamp="2026-09-19T03:28:51.455Z"
<testsuite name="dev.wildware.udea.agent.host.gl.OverlayCaptureIsolationTest" tests="1" skipped="0" failures="0" errors="0" timestamp="2026-09-19T03:28:56.076Z"
```

## 4. Images

All four are in `/srv/ssd1/workspace/Udea/build/debug-screenshots/` and on the dashboard. None
changed in the loop-checker rounds, which touch no rendering and no moba asset.

- `issue192-before-script-level-boot.png`: the Kool boot shot of the old script level. It is the
  "before" picture. I took it on a detached checkout of `3a72b83`, where the script still boots
  the game. On top of that checkout I made two temporary changes, neither of them committed:
  - `LevelShot.kt` copied from `a0a9c1b`, with its launch-level read replaced by the fixed name
    `script-test_level`;
  - a `runLevelShotBoot` task appended to `moba/desktop/build.gradle.kts`.

  The log is `$S/shot-before.log`.
- `issue192-after-udealevel-boot.png`: the same shot, booted from `test_level.udealevel`. `cmp`
  says it is **byte-identical** to the "before" picture. I retook it on the merged state and it is
  still identical on `312bd31` (`$S/shot-boot-merged-228.log`: "booted the bundled test level: 27 units
  at tick 1").
- `issue192-before-after-boot.png`: the two, side by side.
- `issue192-plevel-midfight-level-boot.png`: `runLevelShotBoot -Plevel=.../match.udealevel`, a
  level saved mid-fight at tick 420, booted through `-Plevel`. It shows the fight, not the
  starting layout.

## 5. The issue, criterion by criterion

**AC1: booting from `test_level.udealevel` gives the same units in the same places as the old
`.kts` level.**

- `TestLevelRosterTest` compares against the world captured from the script in `3a72b83`: the
  whole-world hash, then every `NetId`'s team, kind, exact float bits, health and components. It
  is green in section 1, and red under mut1 and mut2.
- The Kool screenshot of the file boot is byte-identical to the one of the script boot
  (section 4).
- `-Plevel`: `MobaLevelLaunchTest` "a level named on the launch line is the level the game boots
  and restarts into", plus the mid-fight shot.

**AC2: adding `repeat(2) { }` to any asset fails the build with the new rule id, pointing at that
line.** I appended `repeat(2) { }` as line 16 of `moba/game/assets/config.udea.kts` and ran
`sh gradlew :moba:game:assemble`, before the merges. Spliced from `$S/ac2-red.log`:

```
> Task :moba:game:udeaScanAssets FAILED
[udeaScanAssets] error UDEA0015 moba/game/assets/config.udea.kts:16:1 `repeat` loop in an asset script. Assets may not contain loops: the editor saves an exact value back to the line it came from, and a value a loop produces has no single line. Write each declaration out, or keep a level's units in a `.udealevel` file.
...
> Task :moba:game:udeaPackBundle
[udeaPackBundle] error UDEA0015 moba/game/assets/config.udea.kts:16:1 `repeat` loop in an asset script. Assets may not contain loops: the editor saves an exact value back to the line it came from, and a value a loop produces has no single line. Write each declaration out, or keep a level's units in a `.udealevel` file.

> Task :moba:game:udeaPackBundle FAILED
...
BUILD FAILED in 48s
```
I restored the file, and the same command went green (`$S/ac2-green.log`):
```
BUILD SUCCESSFUL in 47s
```

**Round 1: the two shapes the review found, refused by the real build.** Both ran at `3d2197a`,
and the asset file was restored afterwards.

First, `kotlin.repeat(2) { }` appended to the asset (`$S/r2-qualified.diff`):
```diff
diff --git a/moba/game/assets/config.udea.kts b/moba/game/assets/config.udea.kts
index 9a4a744..dd45bee 100644
--- a/moba/game/assets/config.udea.kts
+++ b/moba/game/assets/config.udea.kts
@@ -13,3 +13,4 @@
 gameConfig(
     defaultCharacter = reference("character/soldier"),
 )
+kotlin.repeat(2) { }
```
Spliced from `$S/r2-qualified-red.log`:
```
> Task :moba:game:udeaScanAssets FAILED
[udeaScanAssets] 22 script(s), 160 declaration(s)
[udeaScanAssets] error UDEA0015 moba/game/assets/config.udea.kts:16:1 `repeat` loop in an asset script. Assets may not contain loops: the editor saves an exact value back to the line it came from, and a value a loop produces has no single line. Write each declaration out, or keep a level's units in a `.udealevel` file.
...
BUILD FAILED in 11s
```

Second, `import kotlin.repeat as times` added with `times(2) { }` (`$S/r2-alias.diff`). The build
refused both the import on line 1 and the call on line 17:
```diff
diff --git a/moba/game/assets/config.udea.kts b/moba/game/assets/config.udea.kts
index 9a4a744..e6dea6d 100644
--- a/moba/game/assets/config.udea.kts
+++ b/moba/game/assets/config.udea.kts
@@ -1,3 +1,4 @@
+import kotlin.repeat as times
 // The root of the eager set. `BundleContent.reachable` walks from `gameConfig` to decide which
 // blobs load at launch and which stream, so a bundle without one streams everything and the
 // first frame waits on a disk read it did not need to.
@@ -13,3 +14,4 @@
 gameConfig(
     defaultCharacter = reference("character/soldier"),
 )
+times(2) { }
```
Spliced from `$S/r2-alias-red.log`:
```
> Task :moba:game:udeaScanAssets FAILED
[udeaScanAssets] 22 script(s), 161 declaration(s)
[udeaScanAssets] error UDEA0015 moba/game/assets/config.udea.kts:1:1 `repeat` loop in an asset script. Assets may not contain loops: the editor saves an exact value back to the line it came from, and a value a loop produces has no single line. Write each declaration out, or keep a level's units in a `.udealevel` file.
[udeaScanAssets] error UDEA0015 moba/game/assets/config.udea.kts:17:1 `repeat` loop in an asset script. Assets may not contain loops: the editor saves an exact value back to the line it came from, and a value a loop produces has no single line. Write each declaration out, or keep a level's units in a `.udealevel` file.
...
BUILD FAILED in 6s
```

**The resolved-call checker, in the real build.** A shape pass 1 does not know: `(1..2).forEach
{ }` appended to the same asset, at `ac9058f` (`$S/r3-foreach.diff`):
```diff
diff --git a/moba/game/assets/config.udea.kts b/moba/game/assets/config.udea.kts
index 9a4a744..c484805 100644
--- a/moba/game/assets/config.udea.kts
+++ b/moba/game/assets/config.udea.kts
@@ -13,3 +13,4 @@
 gameConfig(
     defaultCharacter = reference("character/soldier"),
 )
+(1..2).forEach { }
```
Pass 1 (`udeaScanAssets`) let it through; the asset compile in `udeaPackBundle` refused it with
the same rule id at the line. Spliced from `$S/r3-foreach-red.log`:
```
> Task :moba:game:udeaScanAssets
[udeaScanAssets] 22 script(s), 160 declaration(s)
...
> Task :moba:game:udeaPackBundle
[udeaPackBundle] error UDEA0015 moba/game/assets/config.udea.kts:16:1 `forEach` takes a lambda it may run more than once, which is a loop in an asset script. Assets may not contain loops: the editor saves an exact value back to the line it came from, and a value a loop produces has no single line. Write each declaration out, or keep a level's units in a `.udealevel` file.

> Task :moba:game:udeaPackBundle FAILED
...
BUILD FAILED in 14s
```

**Seen red first.** `AssetLoopResolutionTest` was written before the checker. Every refusal case
failed and the negatives passed, as they must with no checker at all (`$S/r3-red.log`):
```
AssetLoopResolutionTest > a repeat qualified with its package is refused() FAILED
    org.opentest4j.AssertionFailedError at AssetLoopResolutionTest.kt:65

AssetLoopResolutionTest > a helper of the script's own that runs its lambda twice is refused at the call() FAILED
    org.opentest4j.AssertionFailedError at AssetLoopResolutionTest.kt:98

AssetLoopResolutionTest > a repeat imported under another name is refused at the call() FAILED
    org.opentest4j.AssertionFailedError at AssetLoopResolutionTest.kt:74

AssetLoopResolutionTest > a function that calls itself is refused at the recursive call() FAILED
    org.opentest4j.AssertionFailedError at AssetLoopResolutionTest.kt:152

AssetLoopResolutionTest > a helper that loops inside is refused at the loop and at the call() FAILED
    org.opentest4j.AssertionFailedError at AssetLoopResolutionTest.kt:117

AssetLoopResolutionTest > forEach over a range is refused() FAILED
    org.opentest4j.AssertionFailedError at AssetLoopResolutionTest.kt:80

AssetLoopResolutionTest > a bare repeat is refused at the call() FAILED
    org.opentest4j.AssertionFailedError at AssetLoopResolutionTest.kt:54

AssetLoopResolutionTest > for, while and do-while are refused, each at its own keyword() FAILED
    org.opentest4j.AssertionFailedError at AssetLoopResolutionTest.kt:131

9 tests completed, 8 failed
```
The two round-1 `LoopInAssetTest` cases were red before `3d2197a` (`$S/r2-loop-red.log`):
```
LoopInAssetTest > a repeat qualified with its package is still a repeat(Path) FAILED
...
LoopInAssetTest > an import of repeat is refused, and so is every call through its alias(Path) FAILED
...
6 tests completed, 2 failed
```
Tests added after their production code were each seen red by a mutation instead: the scope
test (mut15), the pipeline overlap (mut18), the daemon's pass-1 finding (mut4b) and the degrade
test (mut17).

**AC3: no `.udea.kts` under `moba/game/assets` contains a loop.** Enforced: the build compiles
every moba asset with the checker on, and section 3 is green. Also checked directly
(`$S/ac3-grep.txt`). The last command is the known negative: the same probe does find loops in
the retired corpus.

```
$ grep -rnE '\b(repeat *\(|for *\(|while *\(|do *\{)' moba/game/assets --include='*.udea.kts'
exit=1
$ find moba/game/assets -name '*.udea.kts' | wc -l
22
$ grep -rnE '\b(repeat *\(|for *\(|while *\(|do *\{)' example-assets --include='*.udea.kts'   # known negative: the probe does find loops
example-assets/level/test_level.udea.kts:43:        repeat(5) {
example-assets/level/test_level.udea.kts:53:        repeat(10) {
example-assets/level/test_level.udea.kts:63:        repeat(10) {
```

### Also checked

The level file is packed in both APKs. From `$S/apk-debug-listing.txt` and
`$S/apk-release-listing.txt`:
```
    55955  1981-01-01 01:01   levels/test_level.udealevel
    55955  1981-01-01 01:01   levels/test_level.udealevel
```
The Android launcher was built, not run. There is no device on this box. iOS was not built here
(Linux).

Not exercised:

- A live `:moba:desktop:run -PdebugPort` session over the bridge. The boot shots and
  `MobaLevelLaunchTest` go through the same launch path.
- `runUdpProof`, which was already red before this branch.

## 6. Regenerated files

- **`moba/desktop/src/test/resources/fixtures/moba-3600.udearep` and `moba-36000.udearep`**,
  regenerated with `:moba:desktop:udeaWriteReplayFixture`. This happened twice: once on the branch
  (`$S/regen-fixtures.log`), and again inside the #228 merge (`$S/regen-fixtures-228.log`). For
  the second, I took #228's files only as a placeholder in the conflict and then overwrote them.
  Nothing was text-merged. The loop-checker rounds did not touch them.

  **Starting hashes.** Each fixture header carries an asset graph hash:

  | Fixture set | Asset graph hash |
  |---|---|
  | `origin/kmp` `407123a`, where the branch started | `064c663e...` |
  | #228's regeneration, on `origin/kmp` `7d73496` | `7bf327c4...` |
  | Regenerated here, at `312bd31` | `d240e7a5...` |

  The hash moves here because `level/test_level` and `gameConfig.defaultLevel` left the bundle.
  `MobaReplayEqualityTest` refuses a fixture whose asset graph hash differs from the bundle's. It
  runs in `:moba:desktop:test`, in the green build.

  **This is not a re-baseline.** I compared against both earlier sets, and the two comparisons
  come out the same.

  Against #228's fixtures (`$S/fixture-compare-228.txt`):
  ```
fixture moba-3600
  asset graph hash: base 7bf327c4dfc0c6fd..., branch d240e7a584c3492c...
  firstTick base=1 branch=1, ticks base=3600 branch=3600, peers 1/1
  input frames identical: True (30599 bytes)
  per-tick world hashes equal for the first 2081 ticks; first differing index 2081 = tick t2082
  ticks whose hash differs: 1519 of 3600
fixture moba-36000
  asset graph hash: base 7bf327c4dfc0c6fd..., branch d240e7a584c3492c...
  firstTick base=1 branch=1, ticks base=36000 branch=36000, peers 1/1
  input frames identical: True (302728 bytes)
  per-tick world hashes equal for the first 2081 ticks; first differing index 2081 = tick t2082
  ticks whose hash differs: 33919 of 36000
  ```
  Against `407123a`'s fixtures (`$S/fixture-compare-407-vs-merged.txt`):
  ```
fixture moba-3600
  asset graph hash: base 064c663e186e52c8..., branch d240e7a584c3492c...
  firstTick base=1 branch=1, ticks base=3600 branch=3600, peers 1/1
  input frames identical: True (30599 bytes)
  per-tick world hashes equal for the first 2081 ticks; first differing index 2081 = tick t2082
  ticks whose hash differs: 1519 of 3600
fixture moba-36000
  asset graph hash: base 064c663e186e52c8..., branch d240e7a584c3492c...
  firstTick base=1 branch=1, ticks base=36000 branch=36000, peers 1/1
  input frames identical: True (302728 bytes)
  per-tick world hashes equal for the first 2081 ticks; first differing index 2081 = tick t2082
  ticks whose hash differs: 33919 of 36000
  ```
  The recorded inputs are identical, and the first 2081 recorded hashes are equal, which covers
  all of match one. A replay probe (`$S/probe.log`, run before the merges against that day's
  regenerated fixture, so its `hashMatchesRecording` says nothing about the old one) shows where
  match one ends:
  ```
    ISSUE192 end of t2081 match=1 phase=Ended hashMatchesRecording=true
    ISSUE192 end of t2082 match=1 phase=Restarting hashMatchesRecording=true
    ISSUE192 end of t2083 match=2 phase=Fighting hashMatchesRecording=true
  ```
  So the first differing hash falls on the restart, within one tick of the swap. The tool
  labels it `t2082` as `firstTick + index`, and the probe prints the tick at the end of a step.
  I did not work out whether those two labels are off by one against each other. Either way,
  the conclusion stands: nothing before the restart moved. That is the restart decision in
  section 2. Match two onward starts from the saved layout rather than a fresh scatter, so every
  later tick differs. #228 did not change any of that. It moved only the asset hash.
- **`net-protocol.lock` did not move.** No replicated component changed. `udeaCheckProtocolLock`
  runs in the green build.
- **`expected-generated-hashes.txt` did not move.**
