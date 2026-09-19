17cd0b8

# BRIEF-233: gizmo API, handle annotations, KSP gizmo registry and UDEA-MG-012

Branch `issue-233-gizmo-api`. It starts from `origin/master` at 25e2865, and `origin/master` at c8271c1 (#189) was merged in as 17cd0b8. The SHA above is the code under review. This brief is committed on top of it.

## Evidence command

    sh gradlew :udea-codegen:test --tests dev.wildware.udea.codegen.GeneratedGizmoTest --tests dev.wildware.udea.codegen.GizmoProcessorTest :moba:desktop:editorTest :moba:desktop:udeaVerifyEditorAbsent

On 17cd0b8 it passes (`scratchpad/issue233/evidence-green.log`, JUnit totals from `evidence-green-junit.txt`):

```

BUILD SUCCESSFUL in 9s
148 actionable tasks: 14 executed, 13 from cache, 121 up-to-date
Configuration cache entry reused.
udea-codegen/build/test-results/test: 2 classes, tests=24 skipped=0 failures=0 errors=0, newest 11:06:42
moba/desktop/build/test-results/editorTest: 4 classes, tests=15 skipped=0 failures=0 errors=0, newest 11:06:37
```

**It goes red when the feature is reverted.** The mutation below stops the processor from generating any gizmo or gizmo registry. It was applied with `mutate.py`, which writes the literal `git diff`, runs Gradle and restores the file from HEAD.

```diff
diff --git a/udea-codegen/src/main/kotlin/dev/wildware/udea/codegen/UdeaSymbolProcessor.kt b/udea-codegen/src/main/kotlin/dev/wildware/udea/codegen/UdeaSymbolProcessor.kt
index cf344e8..73ee4a8 100644
--- a/udea-codegen/src/main/kotlin/dev/wildware/udea/codegen/UdeaSymbolProcessor.kt
+++ b/udea-codegen/src/main/kotlin/dev/wildware/udea/codegen/UdeaSymbolProcessor.kt
@@ -313,6 +313,7 @@ internal class UdeaSymbolProcessor(
      */
     private fun writeGizmoFiles(resolver: Resolver, own: List<GizmoPass.Handled>): Boolean {
         val game = options.gizmoRegistry ?: return true
+        if (game.isNotEmpty()) return true
         if (emittedGizmoFiles) return true
         if (!CodegenOptions.MODULE_NAME_FORMAT.matches(game)) {
             logger.error(
```

```
> Task :moba:desktop:compileEditorTestKotlin FAILED
e: file:///srv/ssd1/workspace/Udea/.claude/worktrees/agent-abae6f4470b37e013/moba/desktop/src/editorTest/kotlin/dev/wildware/moba/editor/MobaGizmoRegistryTest.kt:4:26 Unresolved reference 'PositionPositionGizmo'.
e: file:///srv/ssd1/workspace/Udea/.claude/worktrees/agent-abae6f4470b37e013/moba/desktop/src/editorTest/kotlin/dev/wildware/moba/editor/MobaGizmoRegistryTest.kt:12:36 Unresolved reference 'MobaGizmoRegistry'.
e: file:///srv/ssd1/workspace/Udea/.claude/worktrees/agent-abae6f4470b37e013/moba/desktop/src/editorTest/kotlin/dev/wildware/moba/editor/MobaGizmoRegistryTest.kt:29:34 Unresolved reference 'PositionPositionGizmo'.
e: file:///srv/ssd1/workspace/Udea/.claude/worktrees/agent-abae6f4470b37e013/moba/desktop/src/editorTest/kotlin/dev/wildware/moba/editor/MobaGizmoRegistryTest.kt:29:58 Unresolved reference 'MobaGizmoRegistry'.
e: file:///srv/ssd1/workspace/Udea/.claude/worktrees/agent-abae6f4470b37e013/moba/desktop/src/editorTest/kotlin/dev/wildware/moba/editor/MobaGizmoRegistryTest.kt:36:22 Unresolved reference 'PositionPositionGizmo'.
[...]
> Task :udea-codegen:compileTestKotlin FAILED
e: file:///srv/ssd1/workspace/Udea/.claude/worktrees/agent-abae6f4470b37e013/udea-codegen/src/test/kotlin/dev/wildware/udea/codegen/GeneratedGizmoTest.kt:5:43 Unresolved reference 'BeaconPositionGizmo'.
[...]
e: file:///srv/ssd1/workspace/Udea/.claude/worktrees/agent-abae6f4470b37e013/udea-codegen/src/test/kotlin/dev/wildware/udea/codegen/GeneratedGizmoTest.kt:68:73 Unresolved reference 'BeaconPositionGizmo'.
[...]
e: file:///srv/ssd1/workspace/Udea/.claude/worktrees/agent-abae6f4470b37e013/udea-codegen/src/test/kotlin/dev/wildware/udea/codegen/GeneratedGizmoTest.kt:100:22 Unresolved reference 'BeaconPositionGizmo'.
[...]
BUILD FAILED in 26s
```

The red is a compile failure, not an assertion failure. The tests name the generated classes directly, so without generation there is nothing for them to compile against. The assertion-level reds are in the mutation table below.

## Summary

- **API (`udea-editor`, new package `dev.wildware.udea.editor.gizmo`, new files only).**
  - `Gizmo<C>` declares its handles as values: `GizmoScope.handle(at, shape, constraint) { drag -> write(C::field, value) }`.
  - `Handle.drag(Drag)` answers a `List<FieldWrite>` and never touches the component.
  - `DragScope.write` refuses a non-finite value, and refuses writing one field twice in a drag.
  - `GizmoRegistry` lists a game's gizmos.
- **Annotations (`udea-annotations`), all BINARY-retained.**
  - `@PositionHandle`, `@SizeHandle` and `@RotationHandle` go on the class and name the fields they drive.
  - `@RadiusHandle` and `@RangeHandle` go on a `Float` field.
  - `@HandleIndex` is written by codegen only.
- **KSP (`udea-codegen`).**
  - The module that owns an annotated component validates it (`UDEA0017`, with a did-you-mean) and lists it on its `<Module>ModuleRegistry` as `@HandleIndex(components = [...])`.
  - A game's `editor` source set runs KSP with `udea.gizmoRegistry=<Game>`. That run reads each registry module's index by exact class name, the same route the registries already use, with no classpath scan.
  - It generates one `Gizmo` per handle, plus `<Game>GizmoRegistry`. The registry lists the generated gizmos and every hand-written `Gizmo` in the run's sources.
  - `moba:desktop` now generates `PositionPositionGizmo` and `MobaGizmoRegistry` in its editor source set, and `MobaGizmoRegistryTest` checks them.
- **Gate (`build-logic`).**
  - `UDEA-MG-012`, `udeaVerifyEditorAbsent`, runs on `check` in every project that has a `runtimeClasspath` or a `jvmRuntimeClasspath`.
  - It reads the class header of every class on that classpath and in the project's own jar. It fails on any class in `dev/wildware/udea/editor/`, on any class that extends or implements one (walking the superclass and interface chain), and names a `Gizmo` as such.
  - `ClassScanner.header` is the new entry point on the existing ASM walk. It restamps class files newer than Java 21 so it can read the LWJGL `META-INF/versions/27` classes, which are on the real release classpath.
- **Seam from #234.** `GizmoLayer`/`GizmoCanvas` in `udea-render` are kept unchanged. This ticket ships the declare half; G5 draws `Handle.at` and `shape` through `GizmoCanvas.project`.

Decisions, each commented on #233:
- cross-module discovery through `@HandleIndex` (5741079135)
- keep the #234 seam (5741079695)
- MG-012 as a class-header scan in build-logic, as its own task rather than folded into MG-010 (5741169688)
- what `UDEA0017` covers, the handle shapes and the gizmo names (5741255655)
- MG-012 does not scan `moba:android`'s `releaseRuntimeClasspath`: `udea-editor` has no Android variant, so the scan would be green by construction (5741320279)

Not done, on purpose: drawing handles and picking them (G3-G6), and any `editor.*` tool that applies `FieldWrite`s.

## `sh gradlew build`

`ANDROID_HOME=$HOME/Android/Sdk JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem sh gradlew build --continue` on 17cd0b8, no exclusions (`scratchpad/issue233/build2.log`):

```
BUILD SUCCESSFUL in 3m 45s
975 actionable tasks: 579 executed, 1 from cache, 395 up-to-date
Configuration cache entry reused.
exit 0
```

The first attempt (`build1.log`) ended in `Gradle build daemon disappeared unexpectedly (it may have been killed or may have crashed)`, with no failed task before it. Other worktrees' Gradle builds and xvfb GL runs were on the box at the time (from `pgrep` samples taken just before and after). The re-run above was green. Within that re-run, `udeaVerifyEditorAbsent` ran in every covered project, as did `:udea-render:udeaVerifyNoLibGdx` from #189.

### GL tests under xvfb

This ticket adds headless code to `udea-editor`, so the editor's GL test was run for real, along with the other two:

    xvfb-run -a -s "-screen 0 1280x720x24" env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe sh gradlew udeaGlTest udeaAgentGlTest udeaEditorGlTest -Pudea.render.requireGl=true

```
> Task :udea-editor:udeaEditorGlTest
> Task :udea-agent-host:udeaAgentGlTest
> Task :udea-render:udeaGlTest
[...]
BUILD SUCCESSFUL in 1m 46s
udea-editor/build/test-results/udeaEditorGlTest: 1 classes, tests=1 skipped=0 failures=0 errors=0, newest 11:04:25
udea-agent-host/build/test-results/udeaAgentGlTest: 2 classes, tests=2 skipped=0 failures=0 errors=0, newest 11:04:30
udea-render/build/test-results/udeaGlTest: 18 classes, tests=19 skipped=0 failures=0 errors=0, newest 11:05:39
```

Nothing was skipped.

## Images

None. This ticket's handles are values, proven headless, as the issue says ("Drawing and hit-testing handles is G3/G5"). Nothing on screen changes, so a screenshot would show the same picture as master.

## The criteria

1. **KSP golden tests for the generated gizmos and registry. A misspelled field fails the build with the new rule id and a did-you-mean.**
   - The goldens are the 8 new lines in `expected-generated-hashes.txt` (see Regenerated files), checked by `GeneratedFileDeterminismTest`.
   - `GizmoProcessorTest` pins `UDEA0017`, the did-you-mean, the source position and the fact that nothing is generated.
   - A real Gradle build fails with it. Here `Position`'s annotation in `moba:game` is misspelled:

```diff
diff --git a/moba/game/src/commonMain/kotlin/dev/wildware/moba/MobaComponents.kt b/moba/game/src/commonMain/kotlin/dev/wildware/moba/MobaComponents.kt
index 4798045..a60c36d 100644
--- a/moba/game/src/commonMain/kotlin/dev/wildware/moba/MobaComponents.kt
+++ b/moba/game/src/commonMain/kotlin/dev/wildware/moba/MobaComponents.kt
@@ -23,7 +23,7 @@ import dev.wildware.udea.annotations.Sim
  */
 @Serializable
 @Replicated
-@PositionHandle
+@PositionHandle(x = "xx")
 public class Position(
     /** World x. Agent-writable. */
     @Net(agentWritable = true) public var x: Float = 0f,
```

```
e: [ksp] /srv/ssd1/workspace/Udea/.claude/worktrees/agent-abae6f4470b37e013/moba/game/src/commonMain/kotlin/dev/wildware/moba/MobaComponents.kt:26: UDEA0017: @PositionHandle(x = "xx") on dev.wildware.moba.Position names 'xx', which it does not declare. A handle's drag writes a Float into the field it names; did you mean 'x'?
[...]
> Task :moba:game:kspKotlinJvm FAILED
[...]
BUILD FAILED in 21s
```

2. **A hand-written `Gizmo` and its generated twin produce the same handles and the same field writes for the same drag, headless.**
   - `GeneratedGizmoTest` compares three twin pairs (position, radius, rotation): each handle's `at`, `shape` and `constraint`, and the writes for seven drags, grabbed both on the handle and off-centre.
   - One more test pins a non-empty answer, so two gizmos that both do nothing cannot pass.
   - Mutation m2 below turns the generated position gizmo absolute instead of relative, and the twin test goes red.
3. **The release gate goes red with a gizmo class on a release classpath, and green without it.**
   - `EditorReleaseRulesTest` and `VerifyEditorAbsentTest` (TestKit) cover it in build-logic.
   - The real `moba:desktop` run, with its editor source set's output put on the release `runtimeClasspath`, is red:

```diff
diff --git a/moba/desktop/build.gradle.kts b/moba/desktop/build.gradle.kts
index 41c25f4..bbcb5dd 100644
--- a/moba/desktop/build.gradle.kts
+++ b/moba/desktop/build.gradle.kts
@@ -204,6 +204,7 @@ val editorSources: SourceSet = sourceSets.create("editor") {
 dependencies {
     // The window, and nothing else in this project names it: see `UDEA-MG-010`.
     "editorImplementation"(project(":udea-editor"))
+    runtimeOnly(editorSources.output) // MUTATION: the editor source set on the release classpath
 
     // The gizmos (issue #233), generated here and nowhere else. `:moba:game`'s own KSP run checks the
     // handle annotations on its components and lists them on `MobaModuleRegistry`'s `@HandleIndex`;
```

```
> Task :moba:desktop:udeaVerifyModuleGraph
> Task :moba:desktop:udeaVerifyEditorAbsent FAILED
[...]
> UDEA-MG-012 :moba:desktop: 3 editor classes on the release classpath.
      dev/wildware/moba/PositionPositionGizmo is a Gizmo, in /srv/ssd1/workspace/Udea/.claude/worktrees/agent-abae6f4470b37e013/moba/desktop/build/classes/kotlin/editor
[...]
BUILD FAILED in 17s
```

   Green without it: `:moba:desktop:udeaVerifyEditorAbsent` is part of the evidence command's green run, and of the build above.
4. **`AGENTS.md` and `docs/module-graph.md` name the new rule and the annotations.**
   - `AGENTS.md`: the `udea-annotations` and `udea-editor` rows, and the "Gizmos are editor-only" bullet (`UDEA0017`, `UDEA-MG-012`, `udeaVerifyEditorAbsent`).
   - `docs/module-graph.md`: the `udea-annotations` row, the build-gates row, and the `## UDEA-MG-012` section, which names the annotations and `UDEA0017`.
   - `ModuleGraphRulesTest` asserts that MG-012 is documented.

## Mutation table

Each row is the literal `git diff` that `mutate.py` wrote, then the failing test cases it read from the JUnit XML. Rows m1 to m5 ran on aee0c37 and m6 on 7182570, each with the file restored from HEAD afterwards. None of these files changed in the merge.

**m1.** The misspelled-field error loses its did-you-mean.

```diff
diff --git a/udea-codegen/src/main/kotlin/dev/wildware/udea/codegen/gizmo/HandleModelBuilder.kt b/udea-codegen/src/main/kotlin/dev/wildware/udea/codegen/gizmo/HandleModelBuilder.kt
index acc854b..bc7c4f3 100644
--- a/udea-codegen/src/main/kotlin/dev/wildware/udea/codegen/gizmo/HandleModelBuilder.kt
+++ b/udea-codegen/src/main/kotlin/dev/wildware/udea/codegen/gizmo/HandleModelBuilder.kt
@@ -157,7 +157,7 @@ internal class HandleModelBuilder(private val logger: KSPLogger) {
             val property = properties[field]
             val prefix = "@${annotation.shortName.asString()}($parameter = \"$field\") on $owner names '$field'"
             if (property == null) {
-                val suggestion = DidYouMean.suggest(field, floats)
+                val suggestion: String? = null
                 val hint = if (suggestion != null) {
                     "did you mean '$suggestion'?"
                 } else if (floats.isEmpty()) {
```

```
gradle exit 1
1 failing test cases
GizmoProcessorTest > a misspelled field is UDEA0017 at the annotation, with a did-you-mean, and generates nothing(File)
```

**m2.** The generated position gizmo writes the absolute cursor instead of the start plus the drag.

```diff
diff --git a/udea-codegen/src/main/kotlin/dev/wildware/udea/codegen/gizmo/GizmoEmitter.kt b/udea-codegen/src/main/kotlin/dev/wildware/udea/codegen/gizmo/GizmoEmitter.kt
index b19a564..a68b6e6 100644
--- a/udea-codegen/src/main/kotlin/dev/wildware/udea/codegen/gizmo/GizmoEmitter.kt
+++ b/udea-codegen/src/main/kotlin/dev/wildware/udea/codegen/gizmo/GizmoEmitter.kt
@@ -109,7 +109,7 @@ internal object GizmoEmitter {
                 if (z != null) sphere() else CodeBlock.of("%T.PlaneSquare(%T.XY)", EditorNames.HANDLE_SHAPE, EditorNames.PLANE),
                 constraint(threeD = z != null),
             )
-            .addStatement("write(%T::%N, x0 + drag.dx)", c, model.x)
+            .addStatement("write(%T::%N, drag.at.x)", c, model.x)
             .addStatement("write(%T::%N, y0 + drag.dy)", c, model.y)
             .apply { if (z != null) addStatement("write(%T::%N, z0 + drag.dz)", c, z) }
             .endControlFlow()
```

```
gradle exit 1
3 failing test cases
GeneratedFileDeterminismTest > the generated files match their checked-in hashes()
GeneratedGizmoTest > the generated position gizmo and its hand-written twin show and write the same()
GeneratedGizmoTest > a 2D position handle sits on the entity, on the ground plane, and moves it by the drag()
```

**m3.** The module registry no longer carries `@HandleIndex`.

```diff
diff --git a/udea-codegen/src/main/kotlin/dev/wildware/udea/codegen/registry/RegistryEmitter.kt b/udea-codegen/src/main/kotlin/dev/wildware/udea/codegen/registry/RegistryEmitter.kt
index 63e68f4..8801a38 100644
--- a/udea-codegen/src/main/kotlin/dev/wildware/udea/codegen/registry/RegistryEmitter.kt
+++ b/udea-codegen/src/main/kotlin/dev/wildware/udea/codegen/registry/RegistryEmitter.kt
@@ -104,7 +104,7 @@ internal object RegistryEmitter {
             .addType(
                 TypeSpec.objectBuilder(index.simpleName)
                     .addModifiers(KModifier.PUBLIC)
-                    .apply { if (handleComponents.isNotEmpty()) addAnnotation(handleIndex(handleComponents)) }
+                    .apply { if (false) addAnnotation(handleIndex(handleComponents)) }
                     .addSuperinterface(CoreNames.MODULE_REGISTRY)
                     .apply { facets.forEach { addSuperinterface(it.service) } }
                     .addKdoc(
```

```
gradle exit 1
4 failing test cases
GeneratedFileDeterminismTest > the generated files match their checked-in hashes()
GeneratedSourceShapeTest > the reflection exemption is one property on the agent surface and the handle index, and nothing else()
GizmoProcessorTest > an editor run generates the gizmos another module indexed, reading the compiled registry(File)
GizmoProcessorTest > a module lists its handle components on its registry, sorted, and generates no gizmo(File)
```

**m4.** MG-012 reads the interfaces only and ignores the superclass chain.

```diff
diff --git a/build-logic/src/main/kotlin/dev/wildware/udea/build/EditorReleaseRules.kt b/build-logic/src/main/kotlin/dev/wildware/udea/build/EditorReleaseRules.kt
index e38c000..e867d0a 100644
--- a/build-logic/src/main/kotlin/dev/wildware/udea/build/EditorReleaseRules.kt
+++ b/build-logic/src/main/kotlin/dev/wildware/udea/build/EditorReleaseRules.kt
@@ -86,7 +86,7 @@ public object EditorReleaseRules {
         return found
     }
 
-    private fun supertypes(header: ClassHeader): List<String> = listOfNotNull(header.superName) + header.interfaces
+    private fun supertypes(header: ClassHeader): List<String> = header.interfaces
 
     /**
      * The message to fail with when nothing was scanned, or `null` when something was. A gate with
```

```
gradle exit 1
2 failing test cases
EditorReleaseRulesTest > a gizmo through a base class is found, and so is the base()
EditorReleaseRulesTest > an editor class, and a class extending one, are violations()
```

**m5.** The generated size gizmo spreads the width along Y.

```diff
diff --git a/udea-codegen/src/main/kotlin/dev/wildware/udea/codegen/gizmo/GizmoEmitter.kt b/udea-codegen/src/main/kotlin/dev/wildware/udea/codegen/gizmo/GizmoEmitter.kt
index b19a564..d101f08 100644
--- a/udea-codegen/src/main/kotlin/dev/wildware/udea/codegen/gizmo/GizmoEmitter.kt
+++ b/udea-codegen/src/main/kotlin/dev/wildware/udea/codegen/gizmo/GizmoEmitter.kt
@@ -132,7 +132,7 @@ internal object GizmoEmitter {
                 EditorNames.HANDLE_SHAPE,
                 constraint(threeD = depth != null),
             )
-            .addStatement("write(%T::%N, maxOf(0f, width0 + drag.spread(%T.X, origin)))", c, model.width, EditorNames.AXIS)
+            .addStatement("write(%T::%N, maxOf(0f, width0 + drag.spread(%T.Y, origin)))", c, model.width, EditorNames.AXIS)
             .addStatement("write(%T::%N, maxOf(0f, height0 + drag.spread(%T.Y, origin)))", c, model.height, EditorNames.AXIS)
             .apply {
                 if (depth != null) {
```

```
gradle exit 1
2 failing test cases
GeneratedFileDeterminismTest > the generated files match their checked-in hashes()
GeneratedGizmoTest > a size handle is the box corner, and dragging it grows the box about its centre()
```

**m6.** The turn is no longer wrapped when atan2 jumps by +2pi.

```diff
diff --git a/udea-editor/src/main/kotlin/dev/wildware/udea/editor/gizmo/HandleGeometry.kt b/udea-editor/src/main/kotlin/dev/wildware/udea/editor/gizmo/HandleGeometry.kt
index 316a77b..5dbe3e5 100644
--- a/udea-editor/src/main/kotlin/dev/wildware/udea/editor/gizmo/HandleGeometry.kt
+++ b/udea-editor/src/main/kotlin/dev/wildware/udea/editor/gizmo/HandleGeometry.kt
@@ -123,7 +123,7 @@ public data class Drag(
         val to = atan2(at.y - centre.y, at.x - centre.x)
         val turn = to - from
         return when {
-            turn > PI_F -> turn - 2f * PI_F
+            false -> turn - 2f * PI_F
             turn <= -PI_F -> turn + 2f * PI_F
             else -> turn
         }
```

```
gradle exit 1
1 failing test cases
GizmoApiTest > the seam is crossed the short way in the other direction too()
```


m6 survived at first. Only one direction across the atan2 seam was pinned. 7182570 added the other direction as a test, and m6 went red on the re-run shown.

## Regenerated files

- `udea-codegen/src/test/resources/expected-generated-hashes.txt` was regenerated with `:udea-codegen:test -Pudea.updateGeneratedHashes=true`.
  - 7 gizmo files and `CodegenFixturesGizmoRegistry.kt` were added.
  - `CodegenFixturesModuleRegistry.kt`'s hash changed, because it now carries `@HandleIndex`.
  - No other line moved.
- `udea-codegen/net-protocol.lock` is **unchanged**. No replicated component was added and no id moved; `udeaCheckProtocolLock` is green in the build.

```diff
diff --git a/udea-codegen/src/test/resources/expected-generated-hashes.txt b/udea-codegen/src/test/resources/expected-generated-hashes.txt
index 6946fbb..68bb4c7 100644
--- a/udea-codegen/src/test/resources/expected-generated-hashes.txt
+++ b/udea-codegen/src/test/resources/expected-generated-hashes.txt
@@ -1,7 +1,13 @@
 # SHA-256 of every file udea-codegen generates for its fixture components.
 # Regenerate with: gradlew :udea-codegen:test -Pudea.updateGeneratedHashes=true
 dev/wildware/udea/codegen/fixtures/AiBlackboardReplicator.kt  e8cda1992c866ce8c6bbf06b9c4529f1bc7e5bcc405582009661b95f34027b3e
+dev/wildware/udea/codegen/fixtures/BeaconPositionGizmo.kt  a400a1a0258d28ed598acb4afcd0aa7001626fe8d8314eac6f096a724199cab0
+dev/wildware/udea/codegen/fixtures/BeaconReachRadiusGizmo.kt  ed78897ee06d73ae278e2489abdb828815babc9280658420e5d10fe7048fdcb2
+dev/wildware/udea/codegen/fixtures/BeaconSightRangeGizmo.kt  bf7bb58f0ee10eca1e612096f72be699373bdaffec58e2b91a4d80db5e6a5c29
 dev/wildware/udea/codegen/fixtures/CombatReplicator.kt  b287d2df77624add6e2c435280df081e21779426336f344b5ce210258fdc6360
+dev/wildware/udea/codegen/fixtures/CratePositionGizmo.kt  e706fb88b27c522ea44b93648eabe335f1f307061fbba9ab95738be5dd597f25
+dev/wildware/udea/codegen/fixtures/CrateRotationGizmo.kt  3d0b0e0d3789aa80082df22e684afb494dd8e3c9dbc461b3a378161b994eadf8
+dev/wildware/udea/codegen/fixtures/CrateSizeGizmo.kt  5ced98c876f9b36276d66c1280f9f2fcdec927d964a7f7d0d3ce48d1938a0bd6
 dev/wildware/udea/codegen/fixtures/HealthAgentState.kt  7fddcf20c583a07de61e80760b71bffb2d9ca34970dc84130ad680510053101a
 dev/wildware/udea/codegen/fixtures/HealthReplicator.kt  a03cc154e3c3cd6168f1e4fd6bad5c2579b3d261ff5598c677055c52cdd878b3
 dev/wildware/udea/codegen/fixtures/MatchClockAgentState.kt  488170ec9b43b9d1401558547611a554dde5f00e32d4143f4f287e8db8e34187
@@ -14,6 +20,7 @@ dev/wildware/udea/codegen/fixtures/PlaygroundTagEntityTool.kt  f2c1eeb4393a1bfb4
 dev/wildware/udea/codegen/fixtures/QuantisedProbeReplicator.kt  3465ecd3573c20ca7586e75ab343fbd0e64ed5d7efd8617face418f778338682
 dev/wildware/udea/codegen/fixtures/TimelineAdvanceTool.kt  ab342d2d38b8888f63537ea1227ee2172807448bcd4a19e79ffa5ad65539eec1
 dev/wildware/udea/codegen/fixtures/TimelineDescribeTool.kt  b110a65a3d65154017984bfd9ee6cec6d806fa14b79c00f82e65160fe019ac15
-dev/wildware/udea/generated/CodegenFixturesModuleRegistry.kt  b3fdaad0db9dea3aa5d986fd6a7bf7b136319d0eec73b13dea899e3fb10ccc2c
+dev/wildware/udea/generated/CodegenFixturesGizmoRegistry.kt  d969f48018526ad5694a8ee598266acdd448ccb00b671e59d7aff749b76bf03a
+dev/wildware/udea/generated/CodegenFixturesModuleRegistry.kt  ebe3ad6f696a4d9b6027d958340c8a90e8a7d3da0d9ec5ff8dadb01cfe12f7e2
 dev/wildware/udea/generated/CodegenFixturesNetProtocol.kt  80268d73ba38bc50a35e154e2a07ba795b0b88c9492236b95fad691e0f8c95e2
 dev/wildware/udea/generated/CodegenFixturesUdeaRegistry.kt  d84fdf106102484d4d98fb42658e84b3747710df6501fefc17f74ae8c3e6aed4
```
