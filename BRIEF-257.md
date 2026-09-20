# BRIEF-257 — an orthographic isometric camera for the 3D model stage

SHA: `01409d1`

Branch `issue-257-iso-camera`, off `origin/master` at `9c95e0e`.

---

## 1. The evidence command

```
xvfb-run -a -s "-screen 0 1280x720x24" \
  env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe \
  ANDROID_HOME=$HOME/Android/Sdk JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem \
  sh gradlew :udea-render:udeaGlTest -Pudea.render.requireGl=true \
  --tests 'dev.wildware.udea.render.gl.GlIsoCameraTest'
```

It draws the real scene through a real Kool context and reads the pixels back. It leaves six PNGs
in `udea-render/build/reports/udea/gl/` and a transcript of every measurement in the test XML.

### It goes red when the feature is reverted

Four mutations, each run, each with the literal `git diff` of what was changed and the assertion
that fired. Every diff below was taken with `diff -u` against the un-mutated file saved beside it
in `scratchpad/issue257/`, and every failure line is spliced out of the run's own
`udea-render/build/test-results/.../TEST-*.xml`.

#### Mutation 1 — the stage ignores the projection the camera asked for

`scratchpad/issue257/mutation-1.diff`:

```diff
@@ -314,9 +314,9 @@
             place(it, view)
             it.fovY = view.fovYDegrees.deg
         }
-        ModelProjection.Orthographic -> orthographic.also {
+        ModelProjection.Orthographic -> perspective.also {
             place(it, view)
-            it.setCentered(view.viewHeight, view.near, view.far)
+            it.fovY = view.fovYDegrees.deg
         }
     }
```

`GlIsoCameraTest` FAILED:

```
org.opentest4j.AssertionFailedError: orthographic: the north is not in the picture (14 pixels)
```

This is the whole feature reverted: the picture becomes a 45-degree perspective from 200 world
units away, so the cubes shrink to a few pixels and the first assertion that touches them fires.

#### Mutation 2 — the stage's orthographic box is a tenth too tall

`scratchpad/issue257/mutation-2.diff`:

```diff
@@ -316,7 +316,7 @@
         }
         ModelProjection.Orthographic -> orthographic.also {
             place(it, view)
-            it.setCentered(view.viewHeight, view.near, view.far)
+            it.setCentered(view.viewHeight * 1.1f, view.near, view.far)
         }
     }
```

`GlIsoCameraTest` FAILED:

```
org.opentest4j.AssertionFailedError: orthographic: the north is drawn at (239.5, 72.25309) but the camera's own matrices put it at (240.0, 64.164276): the numbers a game would un-project through are not the picture
```

This is the sharp one, and it is the property issue #262 will rest on: a ten per cent disagreement
between what the renderer draws and what the camera says it draws moves a cube eight pixels, and
the test catches it.

#### Mutation 3 — the exposed orthographic projection matrix forgets the aspect ratio

`scratchpad/issue257/mutation-3.diff`:

```diff
@@ -174,7 +174,7 @@
             }
             ModelProjection.Orthographic -> {
                 val halfHeight = viewHeight / 2f
-                out[0] = 1f / (halfHeight * aspect)
+                out[0] = 1f / halfHeight
                 out[5] = 1f / halfHeight
                 out[10] = -2f / (far - near)
                 out[14] = -(far + near) / (far - near)
```

Two tests FAILED, one of each kind:

```
ModelCameraTest[jvm] > under orthographic the picture is its view height times the aspect wide()[jvm] FAILED
org.opentest4j.AssertionFailedError: the view is not the aspect ratio wide: expected 1.0, was 1.7777778

GlIsoCameraTest > an orthographic isometric camera draws a cube the same size anywhere on screen, with edges that stay parallel() FAILED
org.opentest4j.AssertionFailedError: orthographic: the east is drawn at (376.5, 143.7528) but the camera's own matrices put it at (445.71426, 144.16428): the numbers a game would un-project through are not the picture
```

#### Mutation 4 — the rig writes into the world

`scratchpad/issue257/mutation-4.diff` (the interesting hunks; the full file is in the scratchpad):

```diff
@@ -198,6 +203,14 @@
     /** Whether a frame has been drawn yet: the first one arrives rather than easing in from nowhere. */
     private var placed = false
 
+    private var world: World? = null
+    private var movers: Family? = null
+
+    override fun onBind(world: World, ctx: GameContext) {
+        this.world = world
+        movers = world.family { all(Transform3D) }
+    }
+
@@ -246,6 +259,9 @@
         // frame; doing it again would ease twice as fast while an editor is open.
         if (resources.viewing.current != null) return
+        val bound = world
+        val found = movers
+        if (bound != null && found != null) with(bound) { found.forEach { it[Transform3D].z += 0.001f } }
         ease()
         place()
```

Two tests FAILED:

```
IsometricRigTest[jvm] > a world ticked with the rig present hashes the same as one ticked without it()[jvm] FAILED
org.opentest4j.AssertionFailedError: expected: <4103198644635102206> but was: <8811301293086919302>

IsometricRigTest[jvm] > the rig is handed no world, so there is nothing it could write into()[jvm] FAILED
org.opentest4j.AssertionFailedError: IsometricRig holds a world, a context or an entity index, so it can write to the simulation: [... , boolean, com.github.quillraven.fleks.World, com.github.quillraven.fleks.Family, int, ...]
```

After every mutation the file was restored from its saved copy and the suite re-run green.

---

## 2. Summary

### What the change is

Three things, in `udea-render` only.

**`ModelCamera` can now say how it flattens the world.** A new `ModelProjection` enum — `Perspective`,
as every camera did before, or `Orthographic` — plus a `viewHeight` in world units that an
orthographic camera frames the picture by. `fovYDegrees` stays and is read under perspective.

**`ModelCamera` hands itself out as plain numbers.** `writeViewMatrix(FloatArray)` and
`writeProjectionMatrix(FloatArray, aspect)` write sixteen floats each, column-major, in OpenGL's
convention, into an array the caller owns. No Kool type is involved, so #262 can un-project a click
without naming one, and reading the camera every frame allocates nothing. Picking itself is **not**
implemented — that is #262 and the ticket says so.

**`ModelStage` draws through whichever camera the frame asks for.** It keeps one `PerspectiveCamera`
and one `OrthographicCamera` for the life of the stage and swaps the pass's camera and the shadow
map's scene camera together when the projection changes. Nothing is allocated per frame. The
editor's Scene view is untouched and stays perspective (decision 5 below).

**`IsometricRig` is the preset**, a sibling of #248's `ThirdPersonRig` in the same package and
sharing the same `CameraMath`. Tilted 30 degrees above the ground at a yaw of 45, orthographic,
panned across the ground with `panBy`, zoomed by `zoomBy` (which changes the view height, not the
distance), turned in quarter turns by `turnLeft`/`turnRight`, and put back on a quarter turn by
`snapYaw`. Everything eases in wall seconds through the `halfLifeStep` the other two rigs use, and a
turn takes the short way round through `wrapDegrees`.

`radians()` moved out of `ThirdPersonRig`'s companion into `CameraMath.kt`, where the two rigs share
it, which is the one change to existing camera code.

### Does the rig write anything into the world? No, and here is how I know

**By construction, not by the test passing.** `IsometricRig`'s constructor takes a `RenderResources`,
a `FrameTime` and a `ModelCamera`. It declares no `World`, no `GameContext` and no `NetIdIndex`
field, and it does not override `onBind`, which is the only place a `RenderSystem` is ever handed a
world. `render()` reads `resources.viewing.current` and `frameTime.frameSeconds`, and writes its own
fields and the camera. There is no reference through which a component could be written.

That claim is checked three ways, because "it has no world" is a structural fact and a structural
fact can be broken by a two-line edit:

- `the rig is handed no world, so there is nothing it could write into` reads the compiled class and
  fails if any field is a Fleks, context or entity-index type. Mutation 4 above reds it.
- `a world ticked with the rig present hashes the same as one ticked without it` ticks two worlds
  120 times, renders the rig into one of them while panning, zooming and turning it, and compares
  `WorldHasher` over a captured snapshot. Mutation 4 reds it.
- `the hash this rests on sees a one-ulp nudge to a transform` is that test's control: it nudges one
  `Transform3D.z` by a single ulp and asserts the hashes differ. Without it the test above would be
  an equality over a hash that cannot tell two worlds apart.

It is also a `RenderSystem` rather than a Fleks system, so `NoRenderSystemIsAFleksSystemTest` and the
existing pipeline gates apply to it as they do to every other presentation system.

### The near miss: my first world-hash test was vacuous, and its own control caught it

Worth reading in full, because it is exactly the defect the reviewer's list names.

**The symptom.** On the first executed run, `a world ticked with the rig present hashes the same as
one ticked without it` **passed** and `the hash this rests on sees a one-ulp nudge to a transform`
**failed** with `expected: not equal but was: <-6081324122382265364>`. Both fixtures hashed to the
same value after nudging one float, which is impossible if the hash sees the float.

**Why it passed.** My fixture's `spawnMoving()` created an entity with a `Transform3D` and a marker
component — and **no `NetId`**. `SnapshotService` captures the entities its `NetIdIndex` knows about
and no others, so the snapshot was empty. `WorldHasher.hash` of an empty snapshot is the same number
whatever the world contains, so the equality assertion was comparing two constants. It would have
gone on passing for ever, including after somebody made the rig write to the world.

`ThirdPersonRigTest`, which I copied the fixture's shape from, does not have this problem, because
its `spawnFollowed` allocates a `NetId` as a side effect of setting the rig's follow target. My rig
follows nothing, so I dropped the line that mattered without noticing the line mattered.

**The fix**, in `IsometricRigTest.Fixture`:

```kotlin
fun spawnMoving(): Entity = world.entity {
    it += Transform3D()
    it += Walker()
}.also { netIds.allocate(it) }
```

and the fixture now also calls `rig.onBind(world, ctx)` as `RenderPipeline` does, so whatever a rig
does with the world it is handed at bind time is inside what the hash covers.

**And it now reds.** Mutation 4 above turns both the hash test and the structural test red. Before
the fix, mutation 4 would have turned only the structural one red.

The general lesson, and the reason it is written up here rather than quietly fixed: **an empty
fixture is not a neutral one.** An empty snapshot satisfies "these two worlds agree" trivially.

### Decisions taken, and what was rejected

All five are also on the issue as
[a comment](https://github.com/wildware-uk/Udea/issues/257#issuecomment-5750487349), with what to
change if the owner disagrees.

1. **Enum + two fields on `ModelCamera`, not a sealed `ModelProjection` carrying its numbers.**
   The sealed type reads better, but `ModelCamera` is mutated every frame by a rig and by
   `EditorCamera.writeOrbit`, so `camera.projection = Perspective(fov)` would allocate once per
   frame per open view on the drawing path. It would also have forced a signature change on
   `hollow/game`'s `HollowScene`, which is frozen under review on #250.
2. **Matrices as `write*Matrix(FloatArray)` on the camera, not a new readout type.** Caller owns the
   array, nothing allocates, no `project`/`unProject` helper because picking is #262. Depth runs -1
   to 1: Kool 0.19.0's GL backend reports `DeviceCoordinates.OPEN_GL`, whose `ndcDepthRange` is
   `DepthRange.NEGATIVE_ONE_TO_ONE` — read out of the published jar with `javap`, not assumed.
3. **The rig binds no key and reads no pointer.** `panBy`/`zoomBy`/`turnLeft`/`turnRight`/`snapYaw`
   are what a game calls from its own input code. An isometric game's controls vary too much between
   games for a default to be right, and every scheme is one call.
4. **Quarter-turn snapping is measured from `homeYawDegrees` (45), not from north**, because the four
   isometric views are 45/135/225/315 and those are not multiples of 90. A game that wants
   0/90/180/270 sets `homeYawDegrees = 0f`.
5. **The editor's Scene tab stays perspective** when the game camera is orthographic. It is the
   editor's own orbit camera (#234) and `EditorCamera` un-projects a gizmo drag through a field of
   view (#236, #237). Mirroring the game's projection there is an editor follow-up.

### What the issue left open that I had to rule on

- The issue says "optionally snap to 90 degree turns" without saying from where. Decision 4.
- The issue says "expose the camera's view and projection as plain floats and matrices" without
  saying which convention. Decision 2, with the Kool depth range read out of the jar.
- The issue does not say how the rig is driven. Decision 3.

### Known limitation, deliberately not fixed here

`ModelStage` measures the shadow slab from the camera's near plane
(`shadow.clipNear = view.near`, `shadow.clipFar = light.shadowDistance`). An orthographic eye sits
far back — the rig defaults to 200 world units — so a game must raise `ModelLight.shadowDistance`
until the slab reaches the ground, or it gets no shadows at all. Centring the slab on the focus
instead is a few lines in `begin`, but it changes shadow behaviour for a ticket about cameras, and I
would rather it were done by somebody looking at shadows. The GL test sets `shadowDistance = 300f`
and the shadows in `issue257-ortho-cubes.png` are real ones.

### Out of scope, noted for the lead

Nothing else. No contract in `docs/contracts/` was touched, nothing in `moba`, `hollow` or
`udea-physics2d` was touched, and `ModelStage`'s MSAA lines and `KoolSurface`/`PassBlit` are
untouched so #258 does not collide.

---

## 3. `sh gradlew build`

Run with no exclusions, on the branch, alone on the box. Full log:
`scratchpad/issue257/full-build.log`.

```
ANDROID_HOME=$HOME/Android/Sdk JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem \
  sh gradlew build --continue --no-configuration-cache --max-workers=6 --console=plain
```

```
BUILD SUCCESSFUL in 6m 22s
1116 actionable tasks: 707 executed, 247 from cache, 162 up-to-date
```

`:udea-assets-compiler:udeaDaemonBudget` passed inside that run; it did not need a solo re-run.

### The GL suite, for real, under xvfb

This ticket is all `udea-render`, so a green `build` says nothing about GL: with no `DISPLAY` the
three GL tasks skip. Run for real, full log `scratchpad/issue257/gl-suite-green.log`:

```
xvfb-run -a -s "-screen 0 1280x720x24" \
  env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe \
  ANDROID_HOME=$HOME/Android/Sdk JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem \
  sh gradlew udeaGlTest udeaAgentGlTest udeaEditorGlTest -Pudea.render.requireGl=true \
  --max-workers=6 --console=plain
```

```
> Task :udea-agent-host:udeaAgentGlTest
> Task :udea-render:udeaGlTest
> Task :udea-editor:udeaEditorGlTest

BUILD SUCCESSFUL in 1m 50s
126 actionable tasks: 21 executed, 1 from cache, 104 up-to-date
```

All three tasks executed rather than skipped, which is what `-Pudea.render.requireGl=true` plus a
real `DISPLAY` buys.

### The measurements the GL test printed

Spliced from `udea-render/build/test-results/udeaGlTest/TEST-dev.wildware.udea.render.gl.GlIsoCameraTest.xml`,
also saved whole at `scratchpad/issue257/gliso-measurements.txt`. Consecutive, in order; the elision
markers are mine.

```
GlIsoCameraTest: orthographic: north measured (239.5, 63.752808) over 2314 pixels, 52x57; the matrices predict (240.0, 64.164276)
GlIsoCameraTest: orthographic: south measured (239.5, 223.7528) over 2314 pixels, 52x57; the matrices predict (240.0, 224.16429)
GlIsoCameraTest: orthographic: east measured (376.5, 143.7528) over 2314 pixels, 52x57; the matrices predict (377.14285, 144.16428)
GlIsoCameraTest: orthographic: west measured (102.5, 143.7528) over 2314 pixels, 52x57; the matrices predict (102.85715, 144.16428)
GlIsoCameraTest: orthographic: left pillar measured (91.0, 240.73758) over 1791 pixels, 19x99; the matrices predict (91.428566, 241.17603)
GlIsoCameraTest: orthographic: right pillar measured (388.0, 240.73758) over 1791 pixels, 19x99; the matrices predict (388.57144, 241.17603)
GlIsoCameraTest: orthographic: the lifted cube is at (377.0, 60.5), the one under it at (376.5, 143.7528)
GlIsoCameraTest: orthographic: cube areas [2314, 2314, 2314, 2314]
GlIsoCameraTest: orthographic: the left pillar leans 0.0 pixels
GlIsoCameraTest: orthographic: the right pillar leans 0.0 pixels
GlIsoCameraTest: perspective: north measured (239.5, 76.12051) over 1726 pixels, 46x48; the matrices predict (240.0, 76.67965)
GlIsoCameraTest: perspective: south measured (239.5, 235.51472) over 3260 pixels, 62x71; the matrices predict (240.0, 235.9515)
GlIsoCameraTest: perspective: east measured (377.02637, 143.67459) over 2314 pixels, 55x57; the matrices predict (377.48566, 144.12448)
GlIsoCameraTest: perspective: west measured (101.97364, 143.67459) over 2314 pixels, 55x57; the matrices predict (102.51436, 144.12448)
GlIsoCameraTest: perspective: left pillar measured (37.668587, 262.67535) over 2954 pixels, 42x116; the matrices predict (39.032635, 269.80365)
GlIsoCameraTest: perspective: right pillar measured (441.33142, 262.67535) over 2954 pixels, 42x116; the matrices predict (440.96735, 269.80365)
GlIsoCameraTest: perspective cube areas [1726, 3260, 2314, 2314]
GlIsoCameraTest: perspective: the left pillar leans -13.226374 pixels
GlIsoCameraTest: perspective: the right pillar leans 13.226379 pixels
```

... the four yaw steps, each measured the same way ...

```
GlIsoCameraTest: yaw 45.0: cube areas [2314, 2314, 2314, 2314]
```
```
GlIsoCameraTest: yaw -45.0: cube areas [2314, 2314, 2235, 2314]
```
```
GlIsoCameraTest: yaw -135.0: cube areas [2314, 2308, 2314, 2314]
```
```
GlIsoCameraTest: yaw 135.0: cube areas [2314, 2314, 2314, 2314]
```

Read those four lines carefully, because the obvious summary of them is wrong. In the two-lens scene
(no fox) every cube is **exactly** 2314 pixels. In the yaw sweep the fox stands in the middle, and at
yaw -45 and yaw -135 it clips a few pixels off the cube nearest it: 2235 and 2308. The **bounding
box** is 52x57 for every cube in every frame, including those two, so nothing changed size — a
foreground model overlapped a few pixels of one. The test's tolerance is a ratio of 1.12 between the
largest and smallest; the worst of these is 2314/2235 = 1.035.

Under perspective, for contrast, the same four cubes come out 1726 and 3260 — a ratio of 1.89 — and
the pillars lean by 13.2 pixels each, in opposite directions.

---

## 4. The images

All in `/srv/ssd1/workspace/Udea/build/debug-screenshots/`, all produced by the evidence command and
copied across byte-identically.

| File | What it shows | What it proves |
|---|---|---|
| `issue257-flat-vs-perspective.png` | The collage: the flattened view on the left, the perspective control on the right, same scene, same eye, same target | The whole ticket in one picture. On the right the far red cube is visibly smaller than the near green one and the pillars splay outwards; on the left every cube is the same and the pillars are exactly vertical |
| `issue257-ortho-cubes.png` | Four identical cubes at four places, two pillars, one cube hanging in the air, on a tiled ground | AC 1: a cube is the same size anywhere on screen, and the pillars' edges are parallel. The floating cube's shadow on the ground is real |
| `issue257-perspective-control.png` | The same scene with only the projection swapped to perspective | The measurement can fail. Without this, "the cubes are all one size" is a number that would be true of any renderer that drew nothing |
| `issue257-four-yaw-steps.png` | The collage of the four quarter turns | AC 2 in one image: the same fox and the same four cubes from four angles |
| `issue257-yaw-0-yaw045.png` | The isometric preset, yaw 45, the Khronos Fox on the ground | AC 2, step 1. The fox is a real imported glTF model, textured |
| `issue257-yaw-1-yaw315.png` | One quarter turn right, yaw 315 | AC 2, step 2 |
| `issue257-yaw-2-yaw225.png` | Two quarter turns, yaw 225 | AC 2, step 3 |
| `issue257-yaw-3-yaw135.png` | Three quarter turns, yaw 135 | AC 2, step 4 |

---

## 5. The issue, criterion by criterion

### "A GL test: a unit cube looks the same size anywhere on screen (no perspective), with parallel edges."

**Proved by `GlIsoCameraTest`, run under xvfb with `-Pudea.render.requireGl=true`.**

- *The same size anywhere on screen.* Four identical 1.6-unit cubes stand on a cross round what the
  camera looks at, one to each side. Each is found in the capture by its own colour. Measured:
  `cube areas [2314, 2314, 2314, 2314]`, and every one has a bounding box of 52x57. The assertion is
  a ratio of the largest to the smallest below 1.12 plus bounding boxes within 2 pixels of each
  other. The control, the same scene under perspective, gives `[1726, 3260, 2314, 2314]`.
- *Parallel edges.* A thin 4.5-unit pillar stands to each side, low on the picture. A pillar is a box
  along world +Z; under a projection with no vanishing point its silhouette sits the same distance
  across the picture at the top as at the foot. Measured: `the left pillar leans 0.0 pixels`, `the
  right pillar leans 0.0 pixels`. Under perspective the same measurement gives -13.2 and +13.2 — they
  lean towards each other, which is what a vanishing point is.
- Picture: `issue257-ortho-cubes.png`, with `issue257-perspective-control.png` beside it.

The arithmetic behind it is pinned separately and without a context by `ModelCameraTest`, in
particular `under orthographic a thing is the same size however far away it is` and `under
orthographic parallel world lines stay parallel on the picture`, each with its perspective control in
the same file.

### "Screenshots of a model at 4 yaw steps."

**Proved by `issue257-yaw-0-yaw045.png` through `issue257-yaw-3-yaw135.png`, and the collage
`issue257-four-yaw-steps.png`.**

The model is the Khronos Fox, the same `.glb` `GlImportedModelRenderTest` uses, imported and drawn
by the real `ModelRenderSystem`. Each step is one `rig.turnRight()`. The shots are not only saved:
at every one of the four the test re-measures all four cubes against the camera's own matrices and
re-asserts they are all one size, so the pictures are backed by numbers rather than by my having
looked at them. (I did look at them.)

Precisely what "all one size" means across the sweep, because the obvious summary is false: the
**bounding box is 52x57 for every cube in every one of the four frames**, and the pixel counts are
`[2314, 2314, 2314, 2314]`, `[2314, 2314, 2235, 2314]`, `[2314, 2308, 2314, 2314]` and
`[2314, 2314, 2314, 2314]`. The two short counts are the fox, which stands in the middle and clips a
few pixels off the cube nearest it at those two angles; nothing changed size, which is why the
bounding boxes did not. Worst ratio 1.035, against a tolerance of 1.12. In the two-lens scene of the
first criterion, which has no fox in it, every cube is exactly 2314 pixels.

### "The world hash is unchanged by the camera."

**Proved by `IsometricRigTest`**, three tests, described in full in section 2 under *Does the rig
write anything into the world?*:

- `a world ticked with the rig present hashes the same as one ticked without it` — 120 ticks, the
  rig rendered into one of the two worlds and panned, zoomed and turned throughout.
- `the hash this rests on sees a one-ulp nudge to a transform` — the control that makes the above
  mean something, and the test that caught my own vacuous fixture.
- `the rig is handed no world, so there is nothing it could write into` — the same claim as a
  structural fact over the compiled class.

Mutation 4 reds the first and third.

### The three design constraints the ticket and the lead added

- *"Plain floats in the public surface. No Kool type escapes `udea-render`."* No public declaration
  added here names a Kool type: `ModelProjection` is an enum, `viewHeight` is a `Float`, and the two
  matrix writers take and return a `FloatArray`. `udeaVerifyModuleGraph` (`UDEA-MG-002`) runs on
  `check` and the full build is green.
- *"The view and projection must be readable as plain floats or matrices, because #262's picking will
  un-project through them."* `writeViewMatrix` / `writeProjectionMatrix`, and `GlIsoCameraTest`
  proves they describe the real picture to within 0.7 pixels by predicting each shape's pixel from
  them and reading it back out of a capture. Mutation 2 shows a 10% disagreement is caught.
- *"Share whatever `CameraMath` already offers rather than growing a second copy of the same
  arithmetic."* `IsometricRig` uses `halfLifeStep` and `wrapDegrees` as they stand, and `radians`
  moved out of `ThirdPersonRig`'s private companion into `CameraMath.kt` so both rigs use one copy.

---

## 6. What I did not exercise

Stated rather than glossed, because the reviewer will ask.

- **iOS and Android.** `udea-render` builds for `jvm` and `android`; iOS cannot build on this Linux
  box and I make no claim about it. The Android target compiles in the full build above; no Android
  device ran this.
- **The editor's Scene tab with an orthographic game camera.** By decision 5 the Scene tab keeps its
  own perspective orbit, and `GlViewportOrbitTest` and the editor GL suite pass unchanged. I did not
  drive the editor window with an orthographic game camera, because nothing in the tree has one yet.
- **A resize while orthographic.** Kool's `isKeepAspectRatio` widens the box to the viewport every
  frame, which is why the code leaves left and right alone, but no test resizes the frame under an
  orthographic camera. `GlFrameResizeTest` and `GlViewResizeTest` cover the perspective path and pass.
- **A camera looking straight down.** `writeViewMatrix` has a documented branch for it — the screen's
  up becomes +Y, rather than a matrix of NaN — and `IsometricRig` cannot reach it, because its pitch
  is required to be strictly below 90. That branch is reachable through `ModelCamera.lookAt` and is
  **not** covered by a test; it is three lines and I would rather say so than claim it.
- **Zoom at the limits through a real frame.** `the zoom stops at the limits it was given` is a unit
  test; no GL frame was captured at `minViewHeight` or `maxViewHeight`.

---

## 7. Regenerated files

**None.** No replicated component was added or removed, so `udea-codegen/net-protocol.lock` and
`udea-codegen/src/test/resources/expected-generated-hashes.txt` are untouched and no id moved.
`udeaCheckProtocolLock` and `udeaVerifyContracts` both run on `check` and the full build above is
green. `docs/contracts/` is untouched.

`AGENTS.md` was updated in the same change: the "What the engine does today" bullet that named
`ThirdPersonRig` now also names `IsometricRig`, `ModelCamera.projection` and the two matrix writers.
The module table did not change, so `udeaVerifyAgentsMd` is unaffected — and green.

---

## 8. Files

New:

- `udea-render/src/commonMain/kotlin/dev/wildware/udea/render/camera/IsometricRig.kt`
- `udea-render/src/jvmTest/kotlin/dev/wildware/udea/render/camera/IsometricRigTest.kt`
- `udea-render/src/jvmTest/kotlin/dev/wildware/udea/render/model/ModelCameraTest.kt`
- `udea-render/src/jvmTest/kotlin/dev/wildware/udea/render/gl/GlIsoCameraTest.kt`
- `udea-render/src/jvmTest/kotlin/dev/wildware/udea/render/support/CameraProjections.kt`

Changed:

- `udea-render/src/commonMain/kotlin/dev/wildware/udea/render/model/ModelView.kt` — `ModelProjection`,
  `projection`, `viewHeight`, the two matrix writers
- `udea-render/src/commonMain/kotlin/dev/wildware/udea/render/model/ModelStage.kt` — two cameras, the
  swap, `aim`/`place`
- `udea-render/src/commonMain/kotlin/dev/wildware/udea/render/camera/CameraMath.kt` — `radians` moved in
- `udea-render/src/commonMain/kotlin/dev/wildware/udea/render/camera/ThirdPersonRig.kt` — its private
  `radians` removed
- `AGENTS.md`
