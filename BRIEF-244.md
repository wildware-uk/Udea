04dab11

(The code under review is `04dab11` on `issue-244-fbx-import`: the work, merged with `origin/master`
at `ed7c3ab` (#242 merged). This brief is committed on top of it and changes no code.)

# BRIEF-244: FBX import, converted to glTF at asset-build time

Issue #244, ticket A5 of epic #239. Branch `issue-244-fbx-import`, taken from `origin/master` at `25e2865`.

In the paths below, `scratchpad/` means
`/tmp/claude-1000/-srv-ssd1-workspace-Udea/1ad8c5e6-2def-4055-91d2-72acdfe77daf/scratchpad/`.

## 1. Evidence command

```
xvfb-run -a -s "-screen 0 1280x720x24" env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe sh gradlew :udea-assets-compiler:test --tests '*Fbx*' --tests '*ModelClipAccessorsTest' :moba:game:jvmTest --tests '*HumanModelTest' :moba:desktop:runModelShot --continue
```

Set `ANDROID_HOME` and `JAVA_HOME` as the contract says. The command does four things:

- It runs the converter's tests, the FBX-as-a-model validation and pack tests, and the generated-clip tests.
- It runs the game's `HumanModelTest`. `Human.Clips` is generated from the converted FBX, and this test plays it through the `Animator`.
- It runs the shot. That writes `moba/desktop/build/reports/udea/model/model-human*.png`: the textured character, its turns, and 15 animated frames, each at a known tick.

`scratchpad/issue244/evidence.py` ran it both ways.

**Green**, on `04dab11`, from `scratchpad/issue244/evidence/green.log`:

```
model shots written to /srv/ssd1/workspace/Udea/.claude/worktrees/agent-ab9b668f339e1ddbb/moba/desktop/build/reports/udea/model
...
BUILD SUCCESSFUL in 9s
...
exit=0
```

The JUnit XML of that run:

| Test class | tests | failures |
|---|---|---|
| FbxConverterTest | 10 | 0 |
| FbxModelAssetTest | 4 | 0 |
| ModelClipAccessorsTest | 11 | 0 |
| HumanModelTest | 3 | 0 |

**Red when the feature is reverted.** For this run, `.fbx` stops going through the converter everywhere. The diff below is `scratchpad/issue244/evidence/red-conversion-off.diff`:

```
diff --git a/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/model/ModelSources.kt b/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/model/ModelSources.kt
index 1d18343..02d6cfe 100644
--- a/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/model/ModelSources.kt
+++ b/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/model/ModelSources.kt
@@ -24,7 +24,7 @@ internal object ModelSources {
     fun extensionOf(file: ResFile): String = file.value.substringAfterLast('.', missingDelimiterValue = "").lowercase()
 
     /** True when [file] is converted by the build rather than published as it is. */
-    fun isConverted(file: ResFile): Boolean = extensionOf(file) == FBX
+    fun isConverted(file: ResFile): Boolean = false
 
     /** The glTF file a game is given for the model file [file] a script names. */
     fun runtimeFile(file: ResFile): ResFile =
```

The output below is from `scratchpad/issue244/evidence/red-conversion-off.log`. The lines are in log order, and each gap is marked:

```
> Task :moba:game:udeaGenerateAccessors FAILED
[udeaGenerateAccessors] error UDEA0027 moba/game/assets/models/human.udea.kts:5:1 model `models/human` names `models/human/Human.fbx`, which is not a glTF 2.0 file: its JSON does not parse
...
ModelClipAccessorsTest > an fbx that does not convert fails the accessors pass with the converter's rule, not this one() FAILED
...
ModelClipAccessorsTest > an fbx model's clips are read from the glb it converts to, and generate typed clips() FAILED
...
FbxConverterTest > the glb a converted fbx is published as sits beside it with the extension changed() FAILED
...
FbxModelAssetTest > an fbx declared as a model validates clean and packs as the glb it converts to() FAILED
...
FbxModelAssetTest > an fbx whose texture is missing fails the build with UDEA0039 naming the texture() FAILED
...
FbxModelAssetTest > a broken fbx fails the build with UDEA0039 naming the file() FAILED
...
> Task :udea-assets-compiler:test FAILED
...
[udeaPackBundle] error UDEA0038 moba/game/assets/models/human.udea.kts:5:1 model `models/human` names `models/human/Human.fbx`, which is not a model file: a model is glTF 2.0, a .glb or .gltf file, or an .fbx the build converts to one
...
> Task :moba:game:udeaPackBundle FAILED
...
BUILD FAILED in 17s
...
exit=1
```

Because of this failure the game's accessors are never generated. So `HumanModelTest` does not compile, and the shot never runs; that run wrote 0 shots.

## 2. Summary

A game can now name an `.fbx` in a `model(...)`. The asset build converts it to a self-contained `.glb`, and from there it is the same typed model asset that #240 imports, with the generated clips from #241.

### The converter

**Choice: Assimp** through `org.lwjgl:lwjgl-assimp` 3.3.6 (Assimp 5.4.0). This is the same LWJGL version Kool 0.19.0 uses. The comparison is posted on #239 and #244. In short:

- **FBX2glTF** v0.13.1 had four problems:
  - It resampled Run to 30fps, so the clip came out 0.6s instead of 0.625s.
  - It emitted a second skin with no inverse bind matrices.
  - Its binary statically links the proprietary Autodesk FBX SDK.
  - It ships no macOS arm64 or Linux arm64 binary.
- **Assimp** kept one skin of 34 joints with inverse bind matrices, and all four clips at their exact lengths.

`FbxConverter` (`udea-assets-compiler`) does three things to Assimp's output:

1. **Embeds the texture** named by the FBX. It reads the texture from beside the `.fbx`, checks for PNG or JPEG by magic bytes, and embeds it. Assimp leaves the texture as an external URI.
   - A missing texture fails the build.
   - So does a texture that is not PNG or JPEG. glTF allows only those two.
2. **Rewrites the binary buffer compacted, view by view.** Assimp's `.glb` ends in about 37KB of uninitialised slack that changes from run to run. I checked that every differing byte lies outside every bufferView. After compaction, one `.fbx` always converts to the same bytes.
3. **Renames each clip** to what follows the last `|`. Blender names FBX takes `Armature|Action`, so the result is `Human.Clips.Walk` rather than `HumanArmatureWalk`.

### Where conversion happens

Three passes handle an `.fbx`:

- **Validation** (`ModelFileValidator`) raises `UDEA0039`.
- **Accessor generation** (`ModelClipSource`) reads the clips from the converted bytes. A conversion failure there is `UDEA0039` too, not the clip rule `UDEA0027`.
- **Pack** (`AssetPipeline.convertModels`) writes each `.glb` to `build/udea/converted/<same path>.glb`. The packed `Model.file` names that `.glb` (`ModelSources.runtimeFile`).

The bundle does not carry model bytes, as with #240. So the shot reads the converted `.glb` from `build/udea/converted`, and the fox from the asset root.

### Rules

- **`UDEA0039`**, `AssetValidationRules.MODEL_CONVERSION`: the FBX is broken or unconvertible, or its texture is missing or of the wrong type.
- **`UDEA-MG-013`**, `NO_MODEL_CONVERTER_AT_RUN_TIME`: bans `*:*assimp*` from the compile and runtime classpaths of every headless, GL-allowed and moba project, except the two that run the converter.
- **`UDEA-MG-002` now has a per-project exemption**, `DependencyRule.allowedIn`. It excuses exactly `org.lwjgl:lwjgl` and `org.lwjgl:lwjgl-assimp` in `:udea-assets-compiler` and `:udea-gradle`, the `MODEL_CONVERTER_PROJECTS`. A GL binding there still fails.
- **The bytecode scan** `udeaVerifyHeadless` excuses `org/lwjgl/assimp/` in those same two modules. The allowance is handed over from `build-logic` through the system property `udea.headless.modelConverter`, the same way the module list is.

### Sample and fixtures

**Sample:** `moba/game/assets/models/human/Human.fbx` is Quaternius' Animated Human, CC0.
- The provenance, the original file's SHA-256 and every change made are in `NOTICE.md` beside it, and there is an entry in `LICENSE`.
- `relink.py` is the whole change, run in Blender 5.2.2. It links the texture by a relative path, keeps four takes, and writes no machine path into the file.

**Test fixture:** `udea-assets-compiler/src/test/resources/fbx/bender/`. It is a 56KB, 2-bone cylinder with two takes, generated by `make_bender.py`.

### Decisions the issue left open

- **Clip rename.** Posted on #244 with the converter comment. The alternative was to keep `HumanArmatureWalk`. To undo it, delete the rename in `FbxConverter`.
- **The `agent` source set.** `moba:desktop`'s `agent` source set carries the asset compiler, and therefore the converter, as it already carries the Kotlin compiler.
  - `UDEA-MG-013` does not govern that source set.
  - `UDEA-REL-002` keeps it out of a release.
  - This is written down in `docs/module-graph.md`, in the MG-013 section.

### Touches outside the asset build

`udea-render`'s **test** gate and its build script:
- `HeadlessScan` and `BytecodeBan` gained the excused-namespace parameter.
- `udea-render/build.gradle.kts` hands `udea.headless.modelConverter` to `udeaVerifyHeadless` and `jvmTest`.

No `udea-render` main code changed.

### Merges

`origin/master` was merged twice.

**#189.** `HeadlessScan` had been refactored onto `BytecodeBan`. The excused filter moved into `BytecodeBan.violations`. `docs/module-graph.md` was merged by hand, and the #189 paragraph and my allowance paragraph are now one.

**#242.** One conflict, in `AGENTS.md`. Both lines are kept.

### Found, out of scope: the skinned shadow stays in the bind pose

With #242 merged the body animates, but the ground shadow keeps the arms-out bind pose in every frame, a punch included (`issue244-shadow-stays-in-bind-pose.png`). This is #242's shadow-map pass, not the FBX import. The lead confirmed it from the image, reopened #242 and is dispatching a fix on master. It is not fixed here, as instructed.

## 3. `sh gradlew build`

`ANDROID_HOME=/home/shaun/Android/Sdk JAVA_HOME=/home/shaun/.sdkman/candidates/java/21.0.11-tem sh gradlew build --continue` ran on `04dab11`. The lines below are the tail of `scratchpad/issue244/build-3.log`:

```
BUILD SUCCESSFUL in 1m 52s
960 actionable tasks: 245 executed, 2 from cache, 713 up-to-date
Configuration cache entry stored.
```

**Earlier run.** `scratchpad/issue244/build-1.log` was the first full build after the #189 merge. It failed in two places:

- **`MobaReplayEqualityTest` and `MobaReplayFixturesCurrentTest`.** This was expected. The new human model moved the asset graph hash.
  - I regenerated the fixtures with `sh gradlew :moba:desktop:udeaWriteReplayFixture` and committed them in `2e939f6`.
  - The only reason that task gave for each fixture was `assetGraphHash: recorded 9aef8e071c037a42... (32 bytes), this build 7ba1a6532362da7b... (32 bytes)`.
- **`HeadlessHostTest > time pause stops a free-running host, and its ticks are the loop's()`** in `udea-core`.
  - This is a threading test in code this branch does not touch. Other builds were running on the box at the time.
  - It passed on the next full build, `build-2.log`, and on `build-3.log`.

`sh gradlew -p build-logic check`, from `scratchpad/issue244/build-logic-check.log`:

```
BUILD SUCCESSFUL in 1m 15s
13 actionable tasks: 4 executed, 9 up-to-date
```

### The GL run

The ticket's picture is a GL render, and the shot uses `udea-render`. `scratchpad/issue244/gl.log`:

```
xvfb-run -a -s "-screen 0 1280x720x24" env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe sh gradlew udeaGlTest udeaAgentGlTest udeaEditorGlTest -Pudea.render.requireGl=true --continue
```
```
> Task :udea-editor:udeaEditorGlTest
> Task :udea-agent-host:udeaAgentGlTest
> Task :udea-render:udeaGlTest
...
BUILD SUCCESSFUL in 1m 20s
126 actionable tasks: 12 executed, 114 up-to-date
```

The JUnit XML totals, from `scratchpad/issue244/gl-counts.txt`:

```
udea-render/build/test-results/udeaGlTest: tests=20 skipped=0 failures=0 errors=0
udea-agent-host/build/test-results/udeaAgentGlTest: tests=2 skipped=0 failures=0 errors=0
udea-editor/build/test-results/udeaEditorGlTest: tests=1 skipped=0 failures=0 errors=0
```

**Not run:** `runUdpProof` and `runLaneShot`. This branch touches neither networking nor the lane.

## 4. Images

All of them are in `/srv/ssd1/workspace/Udea/build/debug-screenshots/`.

- `issue244-fbx-human-textured.png`: the FBX character, converted at build time, drawn with its embedded texture beside the fox. It was taken before #242 merged, so the character is in its bind pose. **AC1.**
- `issue244-fbx-human-turns.png`: the same character, a quarter turn a step. The texture wraps all the way round. **AC1.**
- `issue244-fbx-human-idle-walk-run-punch-sequence.png`: 15 frames, each labelled with its tick. The character is played by `Human.Clips` through the `Animator`: Idle (t0), a crossfade to Walk (t30), Walk (t50, 65, 80), a crossfade to Run at t90 photographed at 0, a third and two thirds of the way (t90, 94, 98), Run (t102, 111), Punch played once (t120, 140, 155), and a crossfade back to Idle (t180, 192). **AC2**, on screen.
- `issue244-fbx-human-punch-t155.png`: one full-size frame of that sequence, mid-punch.
- `issue244-shadow-stays-in-bind-pose.png`: the out-of-scope #242 finding. Three crops show the shadow keeping the arms-out pose while the body does not: the bind-pose turn, idle at t0, and the punch at t155.

## 5. The issue, criterion by criterion

1. **An `.fbx` builds into a model asset that draws with its textures, and its clips appear as generated typed clips.**
   - **Converter tests** (`FbxConverterTest`):
     - clips `Bend`/`Twist` at 60 and 38 ticks;
     - one skin with 2 joints and inverse bind matrices;
     - the texture embedded byte for byte;
     - deterministic output.
   - **As a model** (`FbxModelAssetTest`): it validates clean, packs as `models/bender/Bender.glb`, and `convertModels` writes that `.glb`.
   - **Generated clips** (`ModelClipAccessorsTest`): `Bender.Clips.Bend` and `Twist` are generated from the conversion. In the game, `HumanModelTest` shows `Human.Clips` = Idle 600, Punch 60, Run 38, Walk 60.
   - **Screenshots:** `issue244-fbx-human-textured.png` and `issue244-fbx-human-turns.png`.
2. **The FBX character animates through the same `Animator` API as the Fox.**
   - #242 merged during this ticket, so this is proven on screen: `issue244-fbx-human-idle-walk-run-punch-sequence.png`, from `GameModelShot.animate`, which calls `Animator.play` and `Animator.crossfade` with `Human.Clips`, the same calls `ModelShot` makes for the fox.
   - Headless: `HumanModelTest`.
     - Walk wraps at 60.
     - A Punch played `Loop.Once` finishes at 60.
     - A 6-tick crossfade to Idle has blend weight 0.5 at +3 and 1 at +6.
3. **A broken FBX fails the build with a diagnostic, seen red.**
   - Tests: `FbxModelAssetTest` covers a broken file, a missing texture and a missing file; `FbxConverterTest` covers truncated, not FBX, missing texture and not PNG or JPEG. Their mutation rows are M3, M4 and M6 below.
   - The real game build, with `Human.fbx` swapped for its first 400000 bytes (`scratchpad/issue244/truncated.fbx`). From `scratchpad/issue244/ac3-broken-fbx.log`:
     ```
     > Task :moba:game:udeaGenerateAccessors FAILED
     [udeaGenerateAccessors] error UDEA0039 moba/game/assets/models/human.udea.kts:5:1 model `models/human` names `models/human/Human.fbx`, which could not be read as FBX: FBX-Tokenize (offset 0x3bd1) block offset is out of range

     > Task :moba:game:udeaValidateAssets FAILED
     [udeaValidateAssets] 162 asset(s), 1 diagnostic(s)
     [udeaValidateAssets] error UDEA0039 moba/game/assets/models/human.udea.kts:5:1 model `models/human` names `models/human/Human.fbx`, which could not be read as FBX: FBX-Tokenize (offset 0x3bd1) block offset is out of range
     ```
   - With `ClothedLightSkin.png` moved away. From `scratchpad/issue244/ac3-missing-texture.log`:
     ```
     > Task :moba:game:udeaValidateAssets FAILED
     [udeaValidateAssets] 162 asset(s), 1 diagnostic(s)
     [udeaValidateAssets] error UDEA0039 moba/game/assets/models/human.udea.kts:5:1 model `models/human` names `models/human/Human.fbx`, which names the texture `ClothedLightSkin.png`, and there is no `models/human/ClothedLightSkin.png` under the asset root
     ```
   - Both files were restored with `git checkout`.
4. **No FBX or converter library is on any runtime classpath, seen red.**
   - `UDEA-MG-013` is green in the full build.
   - The red run added the converter to the game. The diff is `scratchpad/issue244/ac4-mutation.diff`:
     ```
     diff --git a/moba/game/build.gradle.kts b/moba/game/build.gradle.kts
     index 500e933..55d963f 100644
     --- a/moba/game/build.gradle.kts
     +++ b/moba/game/build.gradle.kts
     @@ -209,6 +209,7 @@ dependencies {
          // is the configuration the other multiplatform modules use, and its task does not exist here.
          add("kspJvm", project(":udea-codegen"))
      
     +    "jvmMainImplementation"(libs.lwjgl.assimp)
      }
      
      /**
     ```
   - The output below is from `scratchpad/issue244/ac4-mg013-red.log`:
     ```
     Execution failed for task ':moba:desktop:udeaVerifyModuleGraph'.
     > udeaVerifyModuleGraph: 1 violation
       UDEA-MG-013 :moba:desktop runtimeClasspath -> org.lwjgl:lwjgl-assimp
           no runtime module and no game classpath resolves the FBX converter; only the asset build may
           resolution path: :moba:desktop -> :moba:game -> org.lwjgl:lwjgl-assimp
     ...
     Execution failed for task ':moba:game:udeaVerifyModuleGraph'.
     > udeaVerifyModuleGraph: 4 violations
       UDEA-MG-013 :moba:game jvmCompileClasspath -> org.lwjgl:lwjgl-assimp
           no runtime module and no game classpath resolves the FBX converter; only the asset build may
           resolution path: :moba:game -> org.lwjgl:lwjgl-assimp
       UDEA-MG-013 :moba:game jvmMainCompileClasspath -> org.lwjgl:lwjgl-assimp
           no runtime module and no game classpath resolves the FBX converter; only the asset build may
           resolution path: :moba:game -> org.lwjgl:lwjgl-assimp
       UDEA-MG-013 :moba:game jvmMainRuntimeClasspath -> org.lwjgl:lwjgl-assimp
           no runtime module and no game classpath resolves the FBX converter; only the asset build may
           resolution path: :moba:game -> org.lwjgl:lwjgl-assimp
       UDEA-MG-013 :moba:game jvmRuntimeClasspath -> org.lwjgl:lwjgl-assimp
           no runtime module and no game classpath resolves the FBX converter; only the asset build may
           resolution path: :moba:game -> org.lwjgl:lwjgl-assimp
     ```
   - The rule and its allowance also have unit tests in `ModuleGraphRulesTest`; see mutations M7 and M8.

## 6. Mutation table

Each mutation was applied alone, then the file was restored with `git checkout`.
- The runner was `scratchpad/issue244/mut.py`. It saved the `git diff` taken while the mutation was applied, ran Gradle, restored the file, and listed the failing cases from the JUnit XML.
- Everything is in `scratchpad/issue244/mutations/`: the diff (`Mn.diff`), the full Gradle log (`Mn.log`) and the failures (`Mn.failed`).
- M1-M8 ran before the two `origin/master` merges, on lines the merges did not change. M9 was re-run after the #189 merge, because that merge moved the filter into `BytecodeBan`.
- The `index` lines are dropped from the diffs below. Failure messages are cut.

**M1: Assimp's buffer is kept as it is, with no compaction.** Task: `:udea-assets-compiler:test`.
```
--- a/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/model/FbxConverter.kt
+++ b/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/model/FbxConverter.kt
@@ -158,7 +158,7 @@ internal object FbxConverter {
         val buffers = document["buffers"] as? JsonArray ?: JsonArray(emptyList())
         if (buffers.size > 1) return failure("was converted by Assimp into a glTF with ${buffers.size} buffers, and a .glb holds one")
 
-        val bin = ByteArrayOutputStream()
+        val bin = ByteArrayOutputStream().apply { write(container.bin) }
         val views = ArrayList<JsonElement>()
         for (view in (document["bufferViews"] as? JsonArray).orEmpty()) {
             val fields = view as? JsonObject ?: return failure("was converted by Assimp into a glTF with a buffer view that is not an object")
@@ -168,7 +168,7 @@ internal object FbxConverter {
             if (offset < 0 || length < 0 || offset + length > container.bin.size) {
                 return failure("was converted by Assimp into a glTF whose buffer view runs past its buffer")
             }
-            views += fields.with("byteOffset", JsonPrimitive(append(bin, container.bin, offset, length)))
+            views += fields
         }
```
Failed: `FbxConverterTest > one fbx always converts to the same bytes`, with "Array elements differ at index 15552".

This one fails because Assimp's slack is nondeterministic, so in principle it could pass by chance. It went red on both runs made.

**M2: the clip rename is removed.** Task: `:udea-assets-compiler:test`.
```
--- a/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/model/FbxConverter.kt
+++ b/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/model/FbxConverter.kt
@@ -80,7 +80,7 @@ internal object FbxConverter {
      * The clip name for an FBX take called [take]: what follows its last `|`, or the whole of it
      * when there is no `|` or nothing follows one.
      */
-    fun clipName(take: String): String = take.substringAfterLast(CLIP_SEPARATOR).ifEmpty { take }
+    fun clipName(take: String): String = take
```
Failed:
- `ModelClipAccessorsTest > an fbx model's clips are read from the glb it converts to, and generate typed clips`
- `FbxConverterTest > a clip name keeps what follows the last bar, and a name with none is left alone`
- `FbxConverterTest > an fbx converts to a glb whose clips are the file's takes, named without the armature`
- `FbxModelAssetTest > an fbx declared as a model validates clean and packs as the glb it converts to`

**M3: the texture is never loaded or embedded.** Task: `:udea-assets-compiler:test`.
```
--- a/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/model/FbxConverter.kt
+++ b/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/model/FbxConverter.kt
@@ -179,7 +179,7 @@ internal object FbxConverter {
                 images += image
                 continue
             }
-            val texture = load(uri).getOrElse { return Result.failure(it) }
+            if (uri.isNotEmpty()) { images += image; continue }; val texture = load(uri).getOrElse { return Result.failure(it) }
             val view = views.size
             views += JsonObject(
                 linkedMapOf(
```
Failed:
- `ModelClipAccessorsTest > an fbx that does not convert fails the accessors pass with the converter's rule, not this one`
- `FbxConverterTest > the texture beside the fbx is embedded, so the glb needs no other file`
- `FbxConverterTest > an fbx whose texture is missing fails, naming the texture and where it was looked for`
- `FbxConverterTest > a texture that is not png or jpeg fails, because glTF allows only those two`
- `FbxModelAssetTest > an fbx whose texture is missing fails the build with UDEA0039 naming the texture`

**M4: the validator's FBX branch is switched off.** Task: `:udea-assets-compiler:test`.
```
--- a/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/validate/ModelFileValidator.kt
+++ b/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/validate/ModelFileValidator.kt
@@ -68,7 +68,7 @@ public object ModelFileValidator : AssetValidator {
                 if (path.isMalformed) return@mapNotNull null
                 val file = context.fileOf(path)
                 if (!file.isRegularFile()) return@mapNotNull null
-                val (rule, problem) = if (ModelSources.isConverted(path)) {
+                val (rule, problem) = if (false && ModelSources.isConverted(path)) {
                     val failure = FbxConverter.convert(context.assetRoot, path).exceptionOrNull() ?: return@mapNotNull null
                     AssetValidationRules.MODEL_CONVERSION to failure.message
                 } else {
```
Failed:
- `MigratedCorpusCompilesTest > every migrated script compiles and validates with zero errors`
- `FbxModelAssetTest > an fbx declared as a model validates clean and packs as the glb it converts to`
- `FbxModelAssetTest > an fbx whose texture is missing fails the build with UDEA0039 naming the texture`
- `FbxModelAssetTest > a broken fbx fails the build with UDEA0039 naming the file`

**M5: the pack publishes the `.fbx` path, not the `.glb`.** Task: `:udea-assets-compiler:test`.
```
--- a/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/pack/GraphPacker.kt
+++ b/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/pack/GraphPacker.kt
@@ -201,7 +201,7 @@ public object GraphPacker {
          */
         fun model(): Map<String, PackValue> = buildMap {
             val file = pathOf(asset.fields["file"]) ?: return@buildMap
-            put("file", PackValue.Path(ModelSources.runtimeFile(ResFile.of(file)).value))
+            put("file", PackValue.Path(file))
         }
```
Failed:
- `MobaWarmEditTest > an edited scale reaches the graph as a delta naming every sheet that shares it`
- `MigratedCorpusBundleTest > packing the game's asset root is clean`
- `MigratedCorpusBundleTest > every asset in the corpus binds to a typed model value`
- `MigratedCorpusBundleTest > character, gameplayEffect and effect come back as typed model values`
- `FbxModelAssetTest > an fbx declared as a model validates clean and packs as the glb it converts to`

Each failed with the runtime `Model` refusing an `.fbx`. For example, `MigratedCorpusBundleTest` failed with "model 'models/human' names 'models/human/Human.fbx'; a model is a glTF 2.0 file, one of [glb, gltf]".

**M6: the accessors pass reports a conversion failure under the clip rule.** Task: `:udea-assets-compiler:test`.
```
--- a/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/gen/ModelClipSource.kt
+++ b/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/gen/ModelClipSource.kt
@@ -43,7 +43,7 @@ internal object ModelClipSource {
             readOne(assetRoot, model).fold(
                 onSuccess = { clips[model.id] = it },
                 onFailure = { problem ->
-                    val rule = if (problem is ConversionFailure) AssetValidationRules.MODEL_CONVERSION else AssetCompilerRules.MODEL_CLIPS
+                    val rule = AssetCompilerRules.MODEL_CLIPS
                     diagnostics += rule.diagnostic(
                         message = "model `${model.id}` ${problem.message}",
                         span = model.span,
```
Failed: `ModelClipAccessorsTest > an fbx that does not convert fails the accessors pass with the converter's rule, not this one`, with "expected: <UDEA0039> but w…".

**M7: the MG-002 exemption is removed.** Task: `-p build-logic test`, then the real gate.
```
--- a/build-logic/src/main/kotlin/dev/wildware/udea/build/ModuleGraphRules.kt
+++ b/build-logic/src/main/kotlin/dev/wildware/udea/build/ModuleGraphRules.kt
@@ -239,7 +239,6 @@ public object ModuleGraphRules {
         projects = HEADLESS_PROJECTS,
         configurations = setOf("compileClasspath", "runtimeClasspath"),
         banned = RENDERER_ARTIFACTS,
-        allowedIn = MODEL_CONVERTER_PROJECTS.associateWith { MODEL_CONVERTER_ARTIFACTS },
     )
```
- Unit test failed: `ModuleGraphRulesTest > UDEA-MG-002 lets the asset build resolve the FBX converter and nothing else of LWJGL`.
- With the same diff, the real gate `:udea-assets-compiler:udeaVerifyModuleGraph :udea-gradle:udeaVerifyModuleGraph` also failed. From `M7-gate.log`:

```
  UDEA-MG-002 :udea-gradle runtimeClasspath -> org.lwjgl:lwjgl
...
  UDEA-MG-002 :udea-gradle runtimeClasspath -> org.lwjgl:lwjgl-assimp
...
  UDEA-MG-002 :udea-assets-compiler compileClasspath -> org.lwjgl:lwjgl
...
  UDEA-MG-002 :udea-assets-compiler compileClasspath -> org.lwjgl:lwjgl-assimp
...
  UDEA-MG-002 :udea-assets-compiler runtimeClasspath -> org.lwjgl:lwjgl
...
  UDEA-MG-002 :udea-assets-compiler runtimeClasspath -> org.lwjgl:lwjgl-assimp
```

**M8: MG-013 is removed from `ALL`.** Task: `-p build-logic test`.
```
--- a/build-logic/src/main/kotlin/dev/wildware/udea/build/ModuleGraphRules.kt
+++ b/build-logic/src/main/kotlin/dev/wildware/udea/build/ModuleGraphRules.kt
@@ -493,7 +493,6 @@ public object ModuleGraphRules {
         NO_LIBGDX,
         NO_EDITOR_ON_A_SHIPPED_CLASSPATH,
         EDITOR_NAMES_NO_RENDERER,
-        NO_MODEL_CONVERTER_AT_RUN_TIME,
     )
```
Failed: `ModuleGraphRulesTest > UDEA-MG-013 fails an Assimp binding on every runtime module and every game classpath`, with "…:udea-agent compileClasspath…".

**M9: the bytecode scan's Assimp excuse is ignored.** Task: `:udea-render:jvmTest --tests '*HeadlessScan*' :udea-render:udeaVerifyHeadless`. This was re-run after the #189 merge.
```
--- a/udea-render/src/jvmTest/kotlin/dev/wildware/udea/render/bytecode/BytecodeBan.kt
+++ b/udea-render/src/jvmTest/kotlin/dev/wildware/udea/render/bytecode/BytecodeBan.kt
@@ -57,7 +57,7 @@ internal class BytecodeBan(
      */
     fun violations(module: String, classFiles: List<File>, excused: List<String> = emptyList()): List<UdeaDiagnostic> = classFiles
         .flatMap { file -> ClassRefScanner.scan(file) }
-        .filterNot { use -> excused.any { use.owner.startsWith(it) } }
+        .filterNot { use -> false && excused.any { use.owner.startsWith(it) } }
         .mapNotNull { use -> banned.firstOrNull { it.matches(use.owner) }?.let { use to it } }
         .map { (use, entry) ->
             UdeaDiagnostic(
```
Failed:
- `HeadlessScanTest > the asset compiler names Assimp, and only there is Assimp excused`, with "…udea-assets-compiler is a headles…".
- The real gate: `UdeaVerifyHeadlessTest > no headless module references a GL type`, with "GL reached a headless module; move the code to udea-render (spec 4) ==> expected: <[]> but was: <[udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/model/FbxConve…".

The evidence command's own red run, with conversion switched off, is in section 1.

## 7. Regenerated files

- **`net-protocol.lock` and `expected-generated-hashes.txt`:** not regenerated. No replicated component was added, and `udeaCheckProtocolLock` is green in the full build.
- **`moba/desktop/src/test/resources/fixtures/moba-3600.udearep` and `moba-36000.udearep`:** regenerated by `:moba:desktop:udeaWriteReplayFixture` in `2e939f6`. The only reason that task gave was the asset graph hash, which moved from `9aef8e07…` to `7ba1a653…` because the game gained `models/human`.
  - After regeneration: 3600 ticks and 59572 bytes; 36000 ticks and 590901 bytes.
  - The #242 merge did not move the hash again; the fixtures pass in `build-3.log`.
