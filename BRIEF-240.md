80b3a87

# #240 — Animated models A1: import glTF/GLB models as assets and draw them with their textures

Branch `issue-240-gltf-import`, from `origin/kmp` at `9d6629f`. Every result below was taken at
code SHA `80b3a87`, except where a row says `54f49a4`. This brief is committed on top of that SHA
and changes no code.

All logs and diffs named here are in the developer's scratchpad,
`/tmp/claude-1000/-srv-ssd1-workspace-Udea/1ad8c5e6-2def-4055-91d2-72acdfe77daf/scratchpad/issue240/`,
shortened below to `$S/`.

## 1. Evidence command

    xvfb-run -a -s "-screen 0 1280x720x24" env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe ANDROID_HOME=/home/shaun/Android/Sdk JAVA_HOME=/home/shaun/.sdkman/candidates/java/21.0.11-tem sh gradlew :udea-assets-compiler:test --tests 'dev.wildware.udea.assets.compiler.validate.ModelAssetTest' --rerun :udea-render:udeaGlTest --tests 'dev.wildware.udea.render.gl.GlImportedModelRenderTest' --rerun :udea-render:runModelShot -Pudea.render.requireGl=true

The command runs three things:

- the four asset-pipeline tests (import, missing file, unreadable file, misspelled id);
- the GL test that samples the fox's texture colours;
- the model shot, which writes the fox PNGs to `udea-render/build/reports/udea/model/`.

**Green at 80b3a87** (`$S/evidence-green.log`, lines 331, 338-339):

    model shots written to /srv/ssd1/workspace/Udea/.claude/worktrees/agent-ac2178956ad64e85f/udea-render/build/reports/udea/model
    ...
    BUILD SUCCESSFUL in 12s
    77 actionable tasks: 10 executed, 67 up-to-date

**Red with the feature reverted.** The mutation is `$S/m4.diff`: the renderer takes an imported model
and draws nothing.

```diff
@@ -179,7 +179,7 @@ internal class ModelStage(
                 run.mesh.isVisible = true
             }
             // The file is Y-up: turned onto the world's Z-up before anything else is applied.
-            is ImportedModel -> importsFor(source).show(matrix.rotate(Y_UP_TO_Z_UP, Vec3f.X_AXIS))
+            is ImportedModel -> Unit
         }
     }
```

The result is in `$S/evidence-red-m4.log`, lines 297-300, 305-307 and 336:

    > Task :udea-render:udeaGlTest FAILED

    GlImportedModelRenderTest > the imported fox is drawn with the colours of its own texture() FAILED
        org.opentest4j.AssertionFailedError at GlImportedModelRenderTest.kt:89
    ...
    Exception in thread "main" java.lang.IllegalStateException: the fox was not drawn in 240 frames
    	at dev.wildware.udea.render.model.ModelShot.main(ModelShot.kt:145)
    ...
    BUILD FAILED in 23s

The assertion message comes from that run's test XML, saved as
`$S/evidence-red-m4-GlImportedModelRenderTest.xml`:
`the fox is not drawn: 0 lit pixels after 240 frames`.

The mutation was then reverted: `git status --short` was empty afterwards. The asset half of the
command (`ModelAssetTest`) has its own red runs, in rows a1-a3 below.

## 2. Summary

**What an author writes.** In an asset script, `model("fox", file = "models/fox/Fox.glb")` declares
a typed `Model` asset (`udea-assets`, with no Kool in it). The file is checked when the build runs,
and the asset is packed into the `.udeapak` as a typed `Model`.

**At run time.** `loadModel(assetRoot, model)` in `udea-render` (jvmMain) reads the file with Kool
0.19.0's own glTF reader (`GltfFile(...)`). An entity with `Transform3D` plus
`ModelRenderer(model = fox)` then draws the model through Kool's PBR path, in bind pose. It gets the
same sun, shadow map and ambient light as the built-in shapes.

**The component change.** `ModelRenderer.model` is now a `ModelSource`. That is either
`MeshModel(mesh, material)` (the built-in shapes) or an `ImportedModel`. The old
`ModelRenderer(mesh, material)` constructor still works, so no existing caller changed.

**The Fox.** The Khronos Fox sample (`example-assets/models/fox/Fox.glb`) is committed, with a
`NOTICE.md` beside it. It is credited in `LICENSE` and `docs/art-assets.md`.

### Decisions (each is commented on #240)

**Licence.** The issue calls the Fox CC0. It is not wholly CC0:

- the model (PixelMannen) is CC0;
- the rigging and animation (tomkranis) are CC BY 4.0;
- the glTF conversion (@AsoboStudio, @scurest) is CC BY 4.0.

So the whole file is treated as CC BY 4.0 and credited in all three places. The alternative was a
different model; the Fox is what the issue names, and attribution costs nothing.

**Y-up to Z-up.** glTF is Y-up and Udea's world is Z-up. So an imported model gets a quarter turn
about X *after* the entity's T·Rz·Ry·Rx·S. Its "up" becomes the world's up, and `rotationZ` still
turns it on the ground. The alternative was making each author rotate by hand. Mutation m2 shows the
turn matters.

**One Kool node per entity.** Each entity gets its own node, not instancing. A2/A3 skin and animate
each fox separately, so per-entity nodes are what they need. Nodes are pooled and hidden, not
destroyed, when an entity goes.

**Build failures, and which rule reports each:**

- A missing file is **UDEA0032**, the existing missing-file rule, with a did-you-mean. The `file` is
  a `ResPath`, so the existing validator covers it.
- A file that exists but is not glTF 2.0 is the new **UDEA0038**, `ModelFileValidator`. It checks:
  - the extension;
  - the GLB header (magic, version 2, stated length equals the file size, first chunk is JSON);
  - that the JSON parses;
  - that `asset.version` is `2.x`;
  - that every relative `uri` (buffers and images) exists.
- A misspelled model id is **UDEA0004**, the existing unresolved-reference rule, with a did-you-mean.
  The `Model` kind is published into the catalog, and the catalog is what the K2 checker resolves
  `reference<Model>(...)` against.

**Loader crash check.** Kool's glTF reader and its stb image decode ran across every GL run on this
branch without killing the JVM, unlike the `.ogg` loader in #221.

**The texture gate was removed** (correction commented on #240). An early version hid the node until
its texture had decoded. Mutation m3 forced that gate open and the test stayed green. Kool already
refuses to draw a mesh whose texture has no pixels yet (`MappedUniformTex.checkLoadingState`). The
gate was redundant, so it went, rather than keeping code no test can tell from its absence.

### The "empty first frame" (the issue asked for its cause)

Probes are logged in `$S/probe-1.log` to `$S/probe-5.log`. Here is what they showed:

- A first fox's first frame is empty, even though its draw data and texture bindings are complete
  after that frame.
- A *second* fox, added later, draws on its very first frame. It uses the same shader program, with
  a new node and new mesh.
- A first fox added after a cube is also one frame late.
- A fox that is moved shows in the very next capture, so there is no steady one-frame lag.

**Conclusion:** the empty frame belongs to the first use of a shader program Kool has not compiled
before. I did not pin down *where* inside Kool that frame goes; llvmpipe program linking is a suspect,
not a finding.

I did not add a warm-up frame to hide it. The test and the shot take frames until the fox is there,
within a budget of 240 frames, and print how many that took. The GL test took 2 (`$S/gl-5.log`,
line 1411):

    GlImportedModelRenderTest: the fox appeared after 2 captured frame(s)

### Not exercised

- **Android.** `loadModel` is `jvmMain`. There is no Android loader yet, and nothing here ran on
  Android.
- **A `.gltf` with external buffers or images, at run time.** The validator checks that their `uri`s
  exist, but only a `.glb` has been drawn.
- **iOS.** Cannot build on this box. `udea-render` has no iOS target anyway.
- **Skins, animation, morph targets.** Deliberately off (`loadAnimations = false`,
  `applySkins = false`); they are A2/A3.
- **Moba.** No moba entity uses a model yet.

## 3. `sh gradlew build`

Full build at 80b3a87, run once other Gradle clients on the box had gone quiet
(`$S/build-1.log`, last lines):

    BUILD SUCCESSFUL in 2m 16s
    917 actionable tasks: 601 executed, 179 from cache, 137 up-to-date
    Configuration cache entry stored.

The command was
`ANDROID_HOME=/home/shaun/Android/Sdk JAVA_HOME=/home/shaun/.sdkman/candidates/java/21.0.11-tem sh gradlew build --continue`,
with no `-x` exclusions. The baseline `origin/kmp` 9d6629f is green per the lead (917 tasks), so this
branch turns nothing red.

### GL under xvfb

The command:

    xvfb-run -a -s "-screen 0 1280x720x24" env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe ANDROID_HOME=/home/shaun/Android/Sdk JAVA_HOME=/home/shaun/.sdkman/candidates/java/21.0.11-tem sh gradlew udeaGlTest --rerun udeaAgentGlTest --rerun -Pudea.render.requireGl=true

The result, from `$S/glrun-1.log`, last lines:

    BUILD SUCCESSFUL in 1m
    121 actionable tasks: 11 executed, 110 up-to-date

`$S/gl-summary.txt` is a grep of the `<testsuite>` lines from that run's XML reports, not program
output. Every GL suite has `skipped="0" failures="0"`, including:

    testsuite name="dev.wildware.udea.render.gl.GlImportedModelRenderTest" tests="1" skipped="0" failures="0" errors="0"
    testsuite name="dev.wildware.udea.render.gl.GlModelRenderTest" tests="1" skipped="0" failures="0" errors="0"

## 4. Images

These are in `/srv/ssd1/workspace/Udea/build/debug-screenshots/`.

- `issue240-fox-under-existing-lighting.png`: the model shot. The imported Fox stands on the same
  ground as the crates and ball, under the same sun. (It is given the same shadow map, but I cannot make out its own shadow at this size, so this brief does not claim one.) Its orange, white and brown come
  from its own texture. This proves the "screenshot under the existing lighting" criterion.
- `issue240-fox-turning-quarter-turns.png`: the fox at four headings, a quarter turn apart. Only the
  entity's `rotationZ` changes between tiles. This proves the Y-up to Z-up turn keeps the fox upright
  while `rotationZ` turns it on the ground.
- `issue240-gl-test-frame-textured-fox.png`: the exact frame `GlImportedModelRenderTest` asserts on.
  The fox is side-on, 215x113 pixels, 0.84 orange and 0.046 white.

## 5. Criterion by criterion

| Criterion | Proof |
|---|---|
| An entity with `Transform3D` + `ModelRenderer(model = <imported asset>)` draws the Fox with its texture; the GL test samples the texture's colours and goes red when the texture is not bound | `GlImportedModelRenderTest`, green under xvfb (`$S/glrun-1.log`, `$S/gl-summary.txt`). Red when the texture is not bound: m1b, `orange 0.0 ... the texture is not bound`. Red when there is no material: m1. Red when nothing is drawn: m4 |
| A missing model file fails the build with a diagnostic | `ModelAssetTest` "a missing model file fails the build with a did-you-mean over the files that exist": UDEA0032, "did you mean `models/fox/Fox.glb`?". Red: a1 |
| An unreadable model file fails the build | `ModelAssetTest` "a model file that is not glTF 2 fails the build naming what it is instead": UDEA0038. Red: a1, a2 |
| A misspelled model id fails the build with a diagnostic | `ModelAssetTest` "a misspelled model id fails the build with a did-you-mean": UDEA0004, "did you mean `models/fox`?". It goes red when the kind is not published (a3 makes the first test red, and the catalog is what resolves the id) |
| Licence and attribution next to the file and in `LICENSE` | `example-assets/models/fox/NOTICE.md`; `LICENSE`, section "Third-party files that ARE redistributable"; `docs/art-assets.md`, "3D models" |
| A screenshot of the imported Fox under the existing lighting | `issue240-fox-under-existing-lighting.png` |
| Kool's own glTF loader, 0.19.0 | `ModelFiles.kt` calls `de.fabmax.kool.modules.gltf.GltfFile`, and `ModelStage` calls `GltfFile.makeModel`. The API was read from the 0.19.0 JAR with `javap` |
| Loaders do not crash the JVM | `grep -l "SIGSEGV\|hs_err\|fatal error has been detected" $S/*.log` matched nothing, and `find <worktree> -name 'hs_err*'` found nothing. Every GL run ended in a JUnit verdict, not a dead JVM |
| A typed asset id, not a `String` | `Model(id: AssetId, file: ResPath)`; `ImportedModel.asset: Model` |
| Kool stays in `udea-render`; `udea-assets` does not see it | `Model` holds only `AssetId` and `ResPath`. The full build includes `udeaVerifyModuleGraph` (UDEA-MG-002), and it is green |
| Find the empty-first-frame cause, or say why not | Section 2, "The empty first frame": narrowed to first use of an uncompiled shader program; not pinned inside Kool |

## 6. Mutation table

Each diff below is the literal `git diff` saved at the time, in `$S/<row>.diff`, with its run in
`$S/<row>.log`.

- m1, m1b, m2 and m3 were run at `54f49a4` (blob `ab9fea2` of `ModelStage.kt`).
- m1, m1b and m2 change lines that are unchanged at `80b3a87`. Only their line numbers moved.
- m3 targets the gate that `80b3a87` deleted *because* m3 stayed green.
- m4, a1, a2 and a3 were run against the current code.

| Row | Mutation | Result |
|---|---|---|
| m1 | `applyMaterials = true` to `false` | RED: `the fox is not drawn: 0 lit pixels after 240 frames` |
| m1b | pbrBlock forces a flat grey colour instead of the texture | RED: `the fox's coat is not its texture's orange (0.0 of 8898 pixels): the texture is not bound` |
| m2 | Y-up to Z-up turn removed | RED: `the fox is not side-on: 115x197 pixels` |
| m3 | texture readiness gate forced true | GREEN. The gate was removed as redundant (section 2) |
| m4 | imported model not drawn | RED: the GL test (`0 lit pixels`) and `runModelShot` (`the fox was not drawn in 240 frames`) |
| a1 | `"file" to file` (a plain `String`, not a `ResPath`) | RED, 2 of 4: the not-glTF test (`ModelAssetTest.kt:85`) and the missing-file test (`:116`) |
| a2 | `ModelFileValidator` removed from the pipeline | RED, 1 of 4: the not-glTF test (`:85`) |
| a3 | `AssetKind.Unpublishable("model")` | RED, 1 of 4: the publish/pack test (`:48`) |

m1:
```diff
-            applyMaterials = true,
+            applyMaterials = false,
```
m1b:
```diff
-            pbrBlock = { _ -> lighting { uniformAmbientLight(Color.WHITE) } },
+            pbrBlock = { _ -> color { uniformColor(Color.GRAY) }; lighting { uniformAmbientLight(Color.WHITE) } },
```
m2:
```diff
-            is ImportedModel -> importsFor(source).show(matrix.rotate(Y_UP_TO_Z_UP, Vec3f.X_AXIS))
+            is ImportedModel -> importsFor(source).show(matrix)
```
m3:
```diff
-            if (!loaded) loaded = textures.all { it.isLoaded || it.uploadData != null }
+            loaded = true
```
m4: shown in full in section 1.

a1:
```diff
-        declare(AssetKind.of<Model>(), "model", name, "file" to resPath(file))
+        declare(AssetKind.of<Model>(), "model", name, "file" to file)
```
a2:
```diff
             SpriteSheetGeometryValidator,
-            ModelFileValidator,
             AnimationNotifyValidator,
```
a3:
```diff
-        declare(AssetKind.of<Model>(), "model", name, "file" to resPath(file))
+        declare(AssetKind.Unpublishable("model"), "model", name, "file" to resPath(file))
```

The hunks above are cut from the saved diff files, keeping their changed lines. The full files, with
headers and index lines, are in `$S/`.

## 7. Regenerated files

None. `Model` is an asset, not a replicated component, so neither `udea-codegen/net-protocol.lock`
nor `expected-generated-hashes.txt` moved, and no ids shifted. `udeaCheckProtocolLock` passed inside
the full build.

No `docs/contracts/` file changed.
