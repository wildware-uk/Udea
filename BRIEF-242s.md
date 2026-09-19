84ac94e

(The code under review is at `84ac94e`. The commit on top of it adds only this file.)

# #242 reopened: a skinned model's shadow follows its own pose

Branch `issue-242-skinned-shadow`, from `origin/master`. It merges `origin/master` at `6625ec9`
(#233), in `a0f4720`.

## Evidence command

    xvfb-run -a -s "-screen 0 1280x720x24" env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe \
      sh gradlew :udea-render:udeaGlTest --tests dev.wildware.udea.render.gl.GlSkinnedModelRenderTest \
      -Pudea.render.requireGl=true -i

It prints `bind-pose shadows N px; M floor px changed shadow in Run` and writes the two frames it
reads to `udea-render/build/reports/udea/gl/skinned-fox-shadow-bind-pose.png` and
`skinned-fox-shadow-run.png`.

**It goes red when the fix is reverted.** This is the literal diff, taken on the final tree (`84ac94e`)
and saved at `scratchpad/issue242s/mutation1-final.diff`:

```diff
@@ -283,7 +283,7 @@ internal class ModelStage(
             placed.node.isVisible = true
         }
 
-        private fun make(): Placed = Placed(model.gltf.makeModel(config).withOwnShadowSkins()).also { placed ->
+        private fun make(): Placed = Placed(model.gltf.makeModel(config)).also { placed ->
             placed.setAmbient()
             nodes += placed
             drawNode.addNode(placed.node)
```

These lines are spliced from `scratchpad/issue242s/logs/mut1-final.log`, lines 1412, 1420, 1455 and 1460:

```
    GlSkinnedModelRenderTest: bind-pose shadows 3692 px; 0 floor px changed shadow in Run
[... lines 1413-1419 ...]
    org.opentest4j.AssertionFailedError: the fox's shadow mid-stride in Run is its bind-pose shadow: only 0 floor pixels changed
[... lines 1421-1454: stack trace and Gradle's failure summary ...]
BUILD FAILED in 11s
[... lines 1456-1459 ...]
EXIT 1
```

With the fix, this line comes from `udea-render/build/test-results/udeaGlTest/TEST-dev.wildware.udea.render.gl.GlSkinnedModelRenderTest.xml`, written by the forced xvfb run below:

```
GlSkinnedModelRenderTest: bind-pose shadows 3692 px; 1859 floor px changed shadow in Run
```

That mutation leaves `ModelStage` the same as `origin/master` in behaviour: the only other change is the
new private function, which is then never called. So this red run is also the "red on master"
the criterion asks for.

### The mutation table

| # | Mutation (literal diff) | Result |
|---|---|---|
| 1 | Above: the fix never called (master's behaviour) | red: `only 0 floor pixels changed` (`mut1-final.log`; also `mut1.log` on `3446f9f`) |
| 2 | The shadow shader made per mesh but **without** Kool's skinning (the lead's first guess at the cause: "the depth pass draws the unskinned mesh"). Diff below | red: `only 0 floor pixels changed` (`mut2.log`, on `3446f9f`) |

```diff
@@ -399,7 +399,7 @@ internal fun KoolModel.withOwnShadowSkins(): KoolModel = apply {
     for (mesh in meshes.values) {
         if (mesh.skin == null || mesh.depthShaderConfig != null) continue
         val cull = (mesh.shader as? KslShader)?.pipelineConfig?.cullMethod ?: CullMethod.CULL_BACK_FACES
-        mesh.depthShaderConfig = DepthShader.Config.forMesh(mesh, cull)
+        mesh.depthShaderConfig = DepthShader.Config()
     }
 }
```

(At `3446f9f` the function was still `internal`; `84ac94e` makes it `private`. That is the only
difference in the mutated lines' context.)

## Summary

**The cause was not what the reopen guessed, and that changed the test.** The shadow was never
drawn from the unskinned mesh. Kool 0.19.0's shadow pass (`DepthMapPass.getDepthPipeline`) draws a
mesh with no depth shader and no `depthShaderConfig` of its own with a `DepthShader` it **caches and
shares** between every mesh with the same layout and configuration (`DepthMapPass.depthShaders`,
keyed by `DepthShaderKey`). A skin's joint matrices are a uniform of that shader (`ArmatureData`,
in the program's common uniform buffer). So every skinned mesh that shares it casts its shadow in the
pose of **whichever skinned mesh was drawn last**. Kool's glTF reader sets a per-mesh
`depthShaderConfig` only for an alpha-masked material (`GltfFile$ModelGenerator.makeKslMaterial`,
read with `javap` from the 0.19.0 jar). And `enableArmature(n)` rounds the joint count up to 64,
so the Fox (24 joints) and the FBX human (34) ask for the same 64-joint shader. The probe pictures
show the two sharing one; I did not compare their vertex layouts directly.

- A fox on its own was always right, which is why #242's tests passed.
- dev-244's human stood beside a fox with no `Animator`. The fox is drawn after the human, so the human's
  shadow was skinned with the **fox's** rest joints: a fixed, odd shape that read as "arms out".
  Removing the fox made the human's shadow punch. That is `issue242s-cause-shadow-borrows-other-models-pose.png`,
  from an uncommitted probe (below).

**The fix** (`ModelStage.kt`, `withOwnShadowSkins`): when `ModelStage` makes a scene node from a
glTF file, it gives each skinned mesh with no `depthShaderConfig` Kool's own
`DepthShader.Config.forMesh(mesh, cull)`. That config is skinned because the mesh has a skin. The
shadow pass then builds one `DepthShader` per mesh from it. This is Kool's own mechanism, the
same call Kool makes for masked materials; no shader is written by hand. The cull method is the one the
shared path would have used. `SimpleShadowMap` never sets the pass's `cullMethod`, so
`getMeshCullMethod` falls back to the mesh's own pipeline's, which is its material's.

**Decisions** (commented on #242):
- **The test draws two foxes.** The lead asked for a Fox-only test "red on master". One fox cannot be
  red on master, because one fox's shadow is correct there. `GlSkinnedModelRenderTest` part 4
  adds a floor and a second fox in bind pose, created after the first so it is drawn after it. It then
  checks that the first fox's shadow changes between its bind pose and Run at clip tick 28. Only
  floor-in-both-frames pixels count, so a leg crossing the floor is not counted as shadow.
- **Load-config knobs rejected**, such as `GltfMaterialConfig.fixedNumberOfJoints`. None of them makes the reader
  set a depth config for an opaque material, so the shader would still be shared.
- **Cost:** one depth shader per skinned mesh per drawn node, instead of one shared shader. Each such
  mesh already has a PBR shader of its own, so this adds a second shader beside it.

**Scope kept:** only `udea-render`'s model code (`ModelStage.kt`) and its GL test. No view,
viewport, editor or `runModelShot` change.

**Not exercised:** an alpha-masked skinned material. The reader already sets that mesh's
config, and `withOwnShadowSkins` leaves it alone (`depthShaderConfig != null`). Neither sample
model has one. Android: `udea-render` has no Android Kool backend, so nothing draws a shadow there.

## `sh gradlew build`

`ANDROID_HOME=$HOME/Android/Sdk JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem sh gradlew build --continue`,
no exclusions, on `84ac94e`. Tail of `scratchpad/issue242s/logs/build2.log`:

```
BUILD SUCCESSFUL in 1m 13s
975 actionable tasks: 88 executed, 2 from cache, 885 up-to-date
Configuration cache entry reused.
EXIT 0
```

The first full build after the merge (`build1.log`) was `BUILD SUCCESSFUL in 2m 21s`, with
`984 actionable tasks: 695 executed, 272 from cache, 17 up-to-date`. I edited `ModelStage.kt` during that run
(`internal` to `private`), so `build2.log` is the run that covers the final tree. I have not looked into why its
actionable-task count differs (975 vs 984).

### The GL tests, for real

    xvfb-run -a -s "-screen 0 1280x720x24" env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe \
      sh gradlew :udea-render:udeaGlTest --rerun :udea-agent-host:udeaAgentGlTest --rerun \
      :udea-editor:udeaEditorGlTest --rerun -Pudea.render.requireGl=true

The run uses `--rerun` because the mutation run replaced the test results, and a plain re-run
was restored from the build cache. On `84ac94e`, the tail of `scratchpad/issue242s/logs/gl-all3.log`:

```
BUILD SUCCESSFUL in 1m 23s
126 actionable tasks: 12 executed, 114 up-to-date
Configuration cache entry stored.
EXIT 0
```

The test-result XML from that run, summed by `scratchpad/issue242s/glsum.py` and saved as
`glsum-final.txt`. Nothing was skipped:

```
udea-render/build/test-results/udeaGlTest: 19 classes, tests=20 skipped=0 failures=0 errors=0 newest=11:46:55
udea-agent-host/build/test-results/udeaAgentGlTest: 2 classes, tests=2 skipped=0 failures=0 errors=0 newest=11:45:49
udea-editor/build/test-results/udeaEditorGlTest: 1 classes, tests=1 skipped=0 failures=0 errors=0 newest=11:45:44
```

An earlier plain run on the same tree (`gl-all.log`) was also green, with the same counts.

## Images

All in `/srv/ssd1/workspace/Udea/build/debug-screenshots/`:

- `issue242s-two-foxes-before-after.png`: the running fox with a bind-pose fox behind it. On the left
  (master) the running fox casts a standing fox's shadow; on the right (fix) its shadow runs with it,
  tail lifted. **The criterion's before/after.**
- `issue242s-before-running-fox-casts-standing-shadow.png`: the test's Run frame on master, full size.
- `issue242s-after-running-fox-casts-running-shadow.png`: the same frame with the fix.
- `issue242s-both-foxes-bind-pose.png`: the test's bind-pose frame. It is byte-identical before and after
  the fix (`cmp` returned nothing), because both foxes are in the same pose.
- `issue242s-cause-shadow-borrows-other-models-pose.png`: **the cause, on master.** dev-244's FBX
  human beside a fox, using dev-244's camera and light. The human punches in the middle panel, but his
  shadow is the fox-skinned shape from the left panel. In the right panel the fox is removed, and his shadow punches.
- `issue242s-after-human-shadow-own-pose.png`: the same three panels with the fix. The human's own
  standing shadow, then his punching shadow, fox or no fox.

The two human pictures come from an **uncommitted probe test**, kept at
`scratchpad/issue242s/GlShadowProbeTest.kt.txt`. It reads dev-244's converted `Human.glb`, copied
to `scratchpad/issue242s/human/`, which is not in this branch. It was run through `udeaGlTest` under xvfb and then deleted from the tree.

## The criterion

- [x] **A skinned model's shadow follows its pose.** `GlSkinnedModelRenderTest` part 4 checks the
  shadow region, on floor pixels only, where the bind-pose shadow and the animated shadow differ. It is red with the fix
  reverted (the transcript above, `only 0 floor pixels changed`) and green with it (1859 pixels). The before/after
  screenshot is `issue242s-two-foxes-before-after.png`, and the reported human case is
  `issue242s-after-human-shadow-own-pose.png`.

## Regenerated files

None. No replicated component was added, so `net-protocol.lock` and
`expected-generated-hashes.txt` are untouched.

## Out of scope, for the lead

- The defect is in Kool, not Udea. Any Kool user with two skinned opaque glTF models has it.
  Worth reporting upstream; no issue was created here (owner rule).
