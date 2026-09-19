0abd780

# A textured, lit 3D model, drawn through Kool (owner request, epic #199)

The SHA above is the last commit of the change. The commit that adds this file sits on top of it and touches `BRIEF-model.md` only.
- Branch: `model-textured-example`, from `origin/kmp` `407123a`.
- Worktree: `/srv/ssd1/workspace/Udea/.claude/worktrees/agent-a13d42bc124dab5fc`.
- Files named without a path are in `/tmp/claude-1000/-srv-ssd1-workspace-Udea/1ad8c5e6-2def-4055-91d2-72acdfe77daf/scratchpad/model/`.

## 1. The evidence command

```
xvfb-run -a -s "-screen 0 1280x720x24" env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe \
  ANDROID_HOME=$HOME/Android/Sdk JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem \
  sh gradlew :udea-render:udeaGlTest --tests dev.wildware.udea.render.gl.GlModelRenderTest --rerun \
  :udea-render:runModelShot -Pudea.render.requireGl=true
```

It runs `GlModelRenderTest` on a real Kool context, and `--rerun` makes it run even when Gradle thinks it is up to date. It then writes the pictures to `udea-render/build/reports/udea/model/`. The test also saves the two frames it reads to `udea-render/build/reports/udea/gl/`.

**Green at 0abd780, three runs in a row.** `evidence.sh` runs the command above three times. The lines below come from `evidence-1.log`, `evidence-2.log` and `evidence-3.log`, in that order:

```
[... 160 lines of evidence-1.log elided ...]
> Task :udea-render:runModelShot
[... 149 lines of evidence-1.log elided ...]
> Task :udea-render:udeaGlTest
[... 9 lines of evidence-1.log elided ...]
BUILD SUCCESSFUL in 35s
[... 2 lines of evidence-1.log elided ...]
[... 130 lines of evidence-2.log elided ...]
> Task :udea-render:runModelShot
[... 149 lines of evidence-2.log elided ...]
> Task :udea-render:udeaGlTest
[... 1 lines of evidence-2.log elided ...]
BUILD SUCCESSFUL in 10s
[... 2 lines of evidence-2.log elided ...]
[... 130 lines of evidence-3.log elided ...]
> Task :udea-render:runModelShot
[... 149 lines of evidence-3.log elided ...]
> Task :udea-render:udeaGlTest
[... 1 lines of evidence-3.log elided ...]
BUILD SUCCESSFUL in 9s
[... 2 lines of evidence-3.log elided ...]
```

**Red with the feature reverted.** Mutation `m0-no-models-drawn` (section 6) leaves `ModelRenderSystem` drawing no entity. The same test then fails at its first assertion:

```
org.opentest4j.AssertionFailedError: two entities have a ModelRenderer ==> expected: <2> but was: <0>
```

`runModelShot` does not assert anything. It only takes the pictures, so the red comes from the test, which runs in the same command.

## 2. Summary

**What a game does now.** It gives an entity two components:
- `Transform3D`: position, rotation and scale as plain floats. It lives in `udea-core` and has no GL.
- `ModelRenderer`: a `ModelMesh` and a `ModelMaterial`. It lives in `udea-render`, next to `SpriteRenderer`, because it holds a texture.

Then it registers one `ModelRenderSystem(resources, camera, light, lift)` in a render phase. The system is a `RenderSystem`, not a Fleks system, so `world.update` stays pure simulation. The rest of the new public surface is:
- `ModelMesh.box`, `ModelMesh.sphere` and `ModelMesh.plane`, built with Kool's own mesh builders;
- `ModelMaterial(albedo, roughness, metallic)`;
- `ModelCamera`;
- `ModelLight(direction, colour, intensity, ambient, shadowDistance)`.

None of these names a Kool type. Kool stays inside `udea-render`, in the `internal` `ModelStage` and `ScenePasses`.

**How it draws.** `ModelStage` gives each mesh-and-material pair one instanced Kool `Mesh`, drawn with a `KslPbrShader`. The shader has:
- the albedo texture;
- the material's roughness and metallic values;
- a directional light;
- uniform ambient light;
- a `SimpleShadowMap`.

This all happens in its own `OffscreenPass2d`, with a depth buffer, a perspective camera and 4x MSAA. The system then draws that pass's picture into the capturable 2D batch, full frame, in its own phase slot. So a capture (an agent's screenshot) includes the models, 2D drawn before them shows behind, and 2D drawn after is on top. `KoolSurface` adds the pass to the scene through the new internal `ScenePasses`, and makes the capturable pass depend on it, so Kool draws the models first.

**Decisions** (each is commented on #199, with the alternative and how to change it):
- **Z is up.** A 2D position `(x, y)` is the 3D point `(x, y, 0)`.
- **The 2D lift.** An entity with a `ModelRenderer` and no `Transform3D` is placed through a `PoseSource`, the seam `CameraRig` already uses. That covers `Interpolator` for `PhysicsBody`, or a game's own. It is drawn on the ground plane, turned by the 2D angle about Z, at scale 1.
  - What it cannot do yet: it gives no height, no tilt and no scale, and moba does not use it this wave. moba's `Position` needs moba's own `PoseSource`.
  - A `Transform3D` is not interpolated, because nothing simulates one yet.
- **`Transform3D` is not replicated.** It is `@Serializable`, so a level file saves it, but it is not `@Replicated`. `udea-core` has no replicated component today, so neither lock file moved.
- **Textures and geometry are made in code.** There is no asset, no licence, and no Kool image loader. The owner's "look great" is met with Kool's PBR shader, shadows and MSAA. There is no image-based lighting, which would need an HDR environment file.
- **`ModelLight.shadowDistance`** feeds the shadow map's `clipFar`. Kool's `SimpleShadowMap` defaults to 100 (read from the 0.19.0 JAR's bytecode), and at that reach the shadows in the example were barely visible.
- **The first frame of a new mesh-and-material pair can come back empty.** This was seen in the test: the capture right after the models were added was all black, and the next four were byte-identical with the models in. The test and the shot let one frame pass. I have not proven the cause; it is written down as an observation in the test.

**Things found on the way:**
- **The test's draw count raced the next frame.** It was read from the test thread, and two runs of `m1` read 2 and then 0. The test now reads the count on the render thread, between frames (commit `0abd780`). The racy run's files are kept in `mutations-run2-racy/`.
- **Taking the light out of the pass's lighting crashes rather than drawing wrong.** Kool's generated shader fails to compile (`error index must be >= 0`, in `mutations/m2-light-off.xml`), the render loop stops, and the capture reports `CaptureStalledException`. The code cannot reach that state, because `ModelStage` always adds the light. Mutation `m2b` is the lighting mutation that reaches the brightness assertion.

**Not exercised:**
- iOS, which is not a `udea-render` target anyway.
- Android at run time. `udea-render`'s Android compile is in `build`, but the model path has only been drawn on desktop.
- A model on a real moba entity.
- A camera or light moved mid-run. The fields are read every frame, but no test moves them.

**Public declarations used only inside the module.** `ModelRenderSystem`, `ModelRenderer`, `ModelMesh`, `ModelMaterial`, `ModelCamera` and `ModelLight` have no user outside `udea-render` yet: the test and the shot are in `udea-render`'s own test source set. They are public because the request is for a surface a game names, and moba was out of bounds this wave. `Transform3D` is used by `udea-render`. `drawnCount` is `internal`.

## 3. `sh gradlew build`

Run with `--continue` at 0abd780 (`build2.log`):

```
[... 1580 lines of build2.log elided ...]
BUILD SUCCESSFUL in 41s
943 actionable tasks: 22 executed, 921 up-to-date
[... 1 lines of build2.log elided ...]
```

And at `b3c8c8e`, before the test-only commit `0abd780` (`build1.log`):

```
[... 1692 lines of build1.log elided ...]
BUILD SUCCESSFUL in 2m 19s
952 actionable tasks: 580 executed, 131 from cache, 241 up-to-date
[... 1 lines of build1.log elided ...]
```

There is no `DISPLAY` in those runs, so the GL tests were skipped. The real GL run follows.

### The GL run, for real

```
xvfb-run -a -s "-screen 0 1280x720x24" env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe \
  sh gradlew udeaGlTest udeaAgentGlTest -Pudea.render.requireGl=true --rerun-tasks
```

Run with the same `ANDROID_HOME` and `JAVA_HOME` as section 1 (`g.sh` exports both). At 0abd780, from `glrun2.log`:

```
[... 298 lines of glrun2.log elided ...]
BUILD SUCCESSFUL in 1m 47s
121 actionable tasks: 121 executed
[... 1 lines of glrun2.log elided ...]
```

Per test class, from the test-result XML (`gl-summary.txt`):

```
testsuite name="dev.wildware.udea.render.gl.GlCaptureTest" tests="1" skipped="0" failures="0" errors="0"
testsuite name="dev.wildware.udea.render.gl.OffscreenBackendExplodingCaptureTest" tests="1" skipped="0" failures="0" errors="0"
testsuite name="dev.wildware.udea.render.gl.GlModelRenderTest" tests="1" skipped="0" failures="0" errors="0"
testsuite name="dev.wildware.udea.render.gl.GlKoolInputTest" tests="1" skipped="0" failures="0" errors="0"
testsuite name="dev.wildware.udea.render.gl.GlKoolPointerTest" tests="1" skipped="0" failures="0" errors="0"
testsuite name="dev.wildware.udea.render.gl.KoolThreadShutdownTest" tests="1" skipped="0" failures="0" errors="0"
testsuite name="dev.wildware.udea.render.gl.GlUiLayerTest" tests="1" skipped="0" failures="0" errors="0"
testsuite name="dev.wildware.udea.render.gl.GlCaptureDeterminismTest" tests="1" skipped="0" failures="0" errors="0"
testsuite name="dev.wildware.udea.render.gl.GlOverlayIsolationTest" tests="1" skipped="0" failures="0" errors="0"
testsuite name="dev.wildware.udea.render.gl.OffscreenBackendSecondCreateTest" tests="1" skipped="0" failures="0" errors="0"
testsuite name="dev.wildware.udea.agent.host.gl.OffscreenRenderToolsTest" tests="1" skipped="0" failures="0" errors="0"
testsuite name="dev.wildware.udea.render.gl.OffscreenBackendTest" tests="2" skipped="0" failures="0" errors="0"
testsuite name="dev.wildware.udea.agent.host.gl.OverlayCaptureIsolationTest" tests="1" skipped="0" failures="0" errors="0"
testsuite name="dev.wildware.udea.render.gl.OffscreenBackendShutdownTest" tests="1" skipped="0" failures="0" errors="0"
```

## 4. Images

All of these are in `/srv/ssd1/workspace/Udea/build/debug-screenshots/`.

- **`model-textured-example-hero.png`**: output of `runModelShot`, 960x540.
  - What it shows: a wooden crate, a small crate and a glossy beach ball on a stone-tile floor, under a 2D sky gradient.
  - What a viewer sees: plank and tile textures; each face shaded by its angle to the sun; a white specular highlight on the ball; soft shadows falling left of each model; smooth edges.
  - What it proves: texture, lit material, shadows, MSAA, and the 3D pass sitting on 2D.
- **`model-textured-example-turn.png`**: 2x2 collage of `model-turn-0..3.png`. The big crate turns a quarter turn in four steps while the light stays fixed. Its faces get brighter and darker as they turn towards and away from the sun. What it proves: `Transform3D.rotationZ` is read every frame, and the shading follows it.
- **`model-textured-example-gltest-texture-and-light.png`**: the frame `GlModelRenderTest` reads, 320x240.
  - The checker cube turned 45 degrees: the left face is lit, the right face is ambient only.
  - The small cube is on the right.
  - Nothing is on the left, where an entity with only a `Transform3D` stands.
- **`model-textured-example-gltest-lifted-from-2d.png`**: the test's frame after the small cube's `ModelRenderer` is removed and the big cube is placed by a 2D pose instead of a `Transform3D`. It shows the same cube in the same place and the same light, with the small cube gone.

## 5. The acceptance criteria, one by one

1. **`udea-render` draws a 3D model with a texture and a lit material through Kool. The public surface is small and Kool-free.** The shot and the test frames above show it. The Kool-free surface is the list in section 2; every Kool type is in `ModelStage` and `ScenePasses`, both `internal`. `build` includes `udeaVerifyModuleGraph` and `udeaVerifyHeadless`, and `udea-core`'s `Transform3D` imports only Fleks and kotlinx.serialization.
2. **A runnable example produces a PNG of the textured, lit model, from an xvfb shot task on Kool Offscreen.** `:udea-render:runModelShot` (section 1) writes it, and the hero image is that output. It shows the texture, and shading that differs across faces, with a specular highlight.
3. **A GL test fails if the texture is not bound, and fails if the lighting is off. Both mutations proven red.** `GlModelRenderTest` under `-Pudea.render.requireGl=true`:
   - it samples two windows of known texels for both checker hues;
   - it compares the brightness of two faces at different angles to the light.
   - Red for texture: `m1` and `m1b`. Red for lighting: `m2b`, and `m2` crashes the frame (section 6).
   - The owner's added condition, an entity without the model component draws nothing: the test asserts that the bare-`Transform3D` window is background, and that a removed `ModelRenderer` disappears on the next frame (`m3` red).
4. **Model and texture are generated in code, or a clearly licensed asset.** Generated in code: Kool's `cube`, `uvSphere` and `grid` builders, and RGBA byte arrays in `ModelShot` and the test. There is no asset file, so there is no licence to record.
5. **`sh gradlew build` stays green, and the GL tests run for real under xvfb.** See section 3.

The owner's follow-up asked for components and a system, not a standalone scene. That is `Transform3D`, `ModelRenderer` and `ModelRenderSystem`. The shot and the test build worlds of entities and nothing else.

## 6. Mutations

Each diff below is the literal `git diff` the script `mutate.py` took while the mutation was applied. Each mutation was applied to the production code of 0abd780, run through `GlModelRenderTest` under xvfb, and reverted. The failure message is spliced from the test-result XML saved for that run.

#### `m0-no-models-drawn`: The feature reverted: the system draws no entity.

```diff
diff --git a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/model/ModelRenderSystem.kt b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/model/ModelRenderSystem.kt
index e620da4..d245c99 100644
--- a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/model/ModelRenderSystem.kt
+++ b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/model/ModelRenderSystem.kt
@@ -78,7 +78,6 @@ public class ModelRenderSystem(
         drawnCount = 0
         stage.begin(camera, light)
         with(bound.world) {
-            bound.models.forEach { entity -> draw(entity, alpha) }
         }
 
         val batch = resources.batch
```

Failure (from `mutations/m0-no-models-drawn.xml`):

```
org.opentest4j.AssertionFailedError: two entities have a ModelRenderer ==> expected: <2> but was: <0>
```

#### `m1-texture-unbound`: The material's texture is never bound to its shader.

```diff
diff --git a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/model/ModelStage.kt b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/model/ModelStage.kt
index 9291d1c..2c6f812 100644
--- a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/model/ModelStage.kt
+++ b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/model/ModelStage.kt
@@ -181,7 +181,6 @@ internal class ModelStage(
                 uniformAmbientLight(Color.WHITE)
             }
         }.apply {
-            colorMap = material.albedo.kool()
         }
 
         val mesh = Mesh(VertexLayouts.PositionNormalTexCoord, instances, name = "udea-model-$index").apply {
```

Failure (from `mutations/m1-texture-unbound.xml`):

```
org.opentest4j.AssertionFailedError: the lit face must show both checker colours, so the texture is bound: Window(warm=0.0, cool=0.0, background=1.0, luminance=0.0)
```

#### `m1b-untextured-material`: The material is not textured at all: a constant white albedo.

```diff
diff --git a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/model/ModelStage.kt b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/model/ModelStage.kt
index 9291d1c..4636a6d 100644
--- a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/model/ModelStage.kt
+++ b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/model/ModelStage.kt
@@ -173,7 +173,7 @@ internal class ModelStage(
         val shader = KslPbrShader {
             vertices { instancedModelMatrix() }
             // sRGB texels, linearised before lighting; Kool's shader converts back on output.
-            color { textureColor() }
+            color { constColor(Color.WHITE) }
             roughness(material.roughness)
             metallic(material.metallic)
             lighting {
```

Failure (from `mutations/m1b-untextured-material.xml`):

```
org.opentest4j.AssertionFailedError: the lit face must show both checker colours, so the texture is bound: Window(warm=0.0, cool=0.0, background=0.0, luminance=216.1444)
```

#### `m2-light-off`: The directional light is left out of the pass's lighting.

```diff
diff --git a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/model/ModelStage.kt b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/model/ModelStage.kt
index 9291d1c..338f5d6 100644
--- a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/model/ModelStage.kt
+++ b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/model/ModelStage.kt
@@ -71,7 +71,7 @@ internal class ModelStage(
         numSamples = MSAA_SAMPLES,
     ).apply {
         camera = this@ModelStage.camera
-        lighting = Lighting().apply { addLight(sun) }
+        lighting = Lighting()
     }
 
     private val shadow = SimpleShadowMap(camera, drawNode, sun, SHADOW_MAP_SIZE, "udea-model-shadow")
```

Failure (from `mutations/m2-light-off.xml`):

```
dev.wildware.udea.render.capture.CaptureStalledException: CaptureRequest(region=full, afterTick=now): the render pipeline was closed before the frame was read
```

#### `m2b-light-intensity-zero`: The light's intensity never reaches Kool.

```diff
diff --git a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/model/ModelStage.kt b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/model/ModelStage.kt
index 9291d1c..51adf9a 100644
--- a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/model/ModelStage.kt
+++ b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/model/ModelStage.kt
@@ -123,7 +123,7 @@ internal class ModelStage(
         direction.set(light.directionX, light.directionY, light.directionZ).norm()
         sun.setup(direction)
         lightColor.set(light.color)
-        sun.setColor(lightColor, light.intensity)
+        sun.setColor(lightColor, 0f)
 
         // The shadow map covers the camera's view from its near plane out to the shadow distance:
         // the shorter that is, the more of the map's texels land on what is close, and the
```

Failure (from `mutations/m2b-light-intensity-zero.xml`):

```
org.opentest4j.AssertionFailedError: the face turned to the light must be brighter than the face turned away: lit=Window(warm=0.4944, cool=0.5056, background=0.0, luminance=54.709843) shaded=Window(warm=0.5056, cool=0.4944, background=0.0, luminance=54.783863)
```

#### `m3-stale-instances`: Last frame's instances are kept, so a mesh nobody drew this frame is still drawn.

```diff
diff --git a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/model/ModelStage.kt b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/model/ModelStage.kt
index 9291d1c..c8748f8 100644
--- a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/model/ModelStage.kt
+++ b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/model/ModelStage.kt
@@ -110,8 +110,6 @@ internal class ModelStage(
     fun begin(view: ModelCamera, light: ModelLight) {
         for (index in allRuns.indices) {
             val run = allRuns[index]
-            run.instances.clear()
-            run.mesh.isVisible = false
         }
 
         eye.set(view.eyeX, view.eyeY, view.eyeZ)
```

Failure (from `mutations/m3-stale-instances.xml`):

```
org.opentest4j.AssertionFailedError: a removed ModelRenderer is still drawn: Window(warm=0.5024, cool=0.4976, background=0.0, luminance=57.694477) ==> expected: <1.0> but was: <0.0>
```

#### `m4-lift-no-heading`: The 2D lift drops the heading.

```diff
diff --git a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/model/ModelRenderSystem.kt b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/model/ModelRenderSystem.kt
index e620da4..7304a8e 100644
--- a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/model/ModelRenderSystem.kt
+++ b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/model/ModelRenderSystem.kt
@@ -103,7 +103,7 @@ public class ModelRenderSystem(
         } else {
             val lift = lift ?: return
             if (!lift.poseOf(this, entity, alpha, pose)) return
-            stage.add(model.mesh, model.material, pose.x, pose.y, 0f, 0f, 0f, pose.angle, 1f, 1f, 1f)
+            stage.add(model.mesh, model.material, pose.x, pose.y, 0f, 0f, 0f, 0f, 1f, 1f, 1f)
         }
         drawnCount++
     }
```

Failure (from `mutations/m4-lift-no-heading.xml`):

```
org.opentest4j.AssertionFailedError: a 2D pose at (0, 0) heading 45 degrees must draw what Transform3D did: lit Window(warm=0.4976, cool=0.5024, background=0.0, luminance=82.07825) vs Window(warm=0.4944, cool=0.5056, background=0.0, luminance=130.2465), shaded Window(warm=0.5024, cool=0.4976, background=0.0, luminance=81.234314) vs Window(warm=0.5056, cool=0.4944, background=0.0, luminance=54.783863)
```

## 7. Regenerated files

None. No component is `@Replicated`, so `udea-codegen/net-protocol.lock` and `expected-generated-hashes.txt` did not move. `udeaCheckProtocolLock` runs in the `build` above.

`udea-render/build.gradle.kts` gained the `runModelShot` task. No library coordinate was added, and `libs.versions.toml` is untouched.
