fe8aa74

(The code under review is `fe8aa74`. The commit that adds this brief sits on top of it and changes nothing else.)

# BRIEF-237: Editor gizmos G6: built-in 3D translate, rotate and scale gizmos on Transform3D

Branch `issue-237-builtin-3d-gizmos`, off `origin/master` at `46c9206`. `git fetch` then `git merge-base --is-ancestor origin/master HEAD` answered yes on 2026-09-19. `origin/master` had not moved, so nothing needed merging.

Every artefact named below is under `/tmp/claude-1000/-srv-ssd1-workspace-Udea/1ad8c5e6-2def-4055-91d2-72acdfe77daf/scratchpad/issue237/` (called `scratch/` from here on).

## 1. Evidence command

    xvfb-run -a -s "-screen 0 1280x720x24" env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe \
      sh gradlew :udea-editor:udeaEditorGlTest --tests '*GlGizmo3DDragTest*' -Pudea.render.requireGl=true

`GlGizmo3DDragTest` opens a real Kool backend. The Khronos Fox is drawn by `ModelRenderSystem`, with a `Transform3D` and the generated-style `Transform3D` gizmos. The Scene tab has a 3D orbit camera, and the test moves a real mouse and keyboard through Kool's GLFW callbacks. In order, it:
- drags each arrow (X, Y, Z)
- drags each plane square (XY, YZ, XZ)
- drags each ring (X, Y, Z), a quarter turn each, from a tilt so no ring lies along a world axis
- pulls the Z scale box 1.5 times further out
- drags the middle scale box up and to the right
- presses Escape in the middle of an arrow drag, a ring drag and a scale-box drag
- turns grid snapping on, then holds Ctrl to skip it
- turns angle snapping on
- switches to local axes

After each step it checks the Fox's `Transform3D` and the `editor` author's `editor.history`. It saves a crop of the Scene tab while each handle is held.

**It goes red when the feature is reverted.** `scratch/mut.sh` makes each change below, runs the command, saves the log and the JUnit failure, and restores the file (`scratch/mut-status.txt` records a clean `git status` afterwards). The mutations ran on `3df79e2`. `fe8aa74` changes no file the GL test touches except `EditorTools.frame`, whose own mutation is m4.

**m1: the pre-#237 refusal restored.** Diff `scratch/m1.patch`:

    @@ -62,6 +62,11 @@ internal class GizmoDrag(
         /** A press at view pixel ([viewX], [viewY]) that the handle layer took. Starts the drag on its handle. */
         fun press(viewX: Float, viewY: Float) {
             val handle = layer.pressed ?: return
    +        if (camera.dimension != dev.wildware.udea.render.view.ViewDimension.TwoD) {
    +            layer.letGo()
    +            told("Dragging a handle in the 3D view comes with the 3D gizmos; switch the Scene tab to 2D")
    +            return
    +        }
             camera.ray(viewX, viewY, pointer)

`scratch/m1.log` lines 218-219, then 223:

    GlGizmo3DDragTest > each built-in 3D handle dragged with the real mouse changes the Fox's Transform3D by the drag in one undo entry, and Escape puts it back() FAILED
        org.opentest4j.AssertionFailedError at GlGizmo3DDragTest.kt:395
    ...
    > Task :udea-editor:udeaEditorGlTest FAILED

`scratch/m1.fail`: `the x arrow did not move the Fox by the drag: expected 0.35, was 0.0 (tolerance 0.022780722)`

**m2: a ring no longer gives way to the handles it crosses.** Diff `scratch/m2.patch`:

    -        pressedIndex = hit.lastOrNull { handles[it].shape !is HandleShape.Ring } ?: hit.lastOrNull() ?: NONE
    +        pressedIndex = hit.lastOrNull() ?: NONE

`scratch/m2.log` line 222 is `> Task :udea-editor:udeaEditorGlTest FAILED`. `scratch/m2.fail`: `the x arrow did not move the Fox by the drag: expected 0.35, was 0.0 (tolerance 0.022780722)`. The ring took the press meant for the arrow.

**m3: the rings in world axes instead of the model's gimbal.** Diff `scratch/m3.patch`:

    -        Triple(Axis.X, rotationX to rotationX0, AxisFrame.euler(rotationX0, rotationY0, rotationZ0)),
    -        Triple(Axis.Y, rotationY to rotationY0, AxisFrame.euler(0f, rotationY0, rotationZ0)),
    -        Triple(Axis.Z, rotationZ to rotationZ0, AxisFrame.euler(0f, 0f, rotationZ0)),
    +        Triple(Axis.X, rotationX to rotationX0, AxisFrame.WORLD),
    +        Triple(Axis.Y, rotationY to rotationY0, AxisFrame.WORLD),
    +        Triple(Axis.Z, rotationZ to rotationZ0, AxisFrame.WORLD),

`scratch/m3.log` line 222 is `> Task :udea-editor:udeaEditorGlTest FAILED`. `scratch/m3.fail`: `the x ring left angle 0 at 0.3: expected 1.8707964, was 0.3 (tolerance 0.06)`

With every file restored, the same command is green: `scratch/gl3d.log` line 217 is `BUILD SUCCESSFUL in 1m 10s`, and section 4 has it inside the full GL run.

**m4: the in-process editor reads the capped answer again** (the second fix, section 2). Diff `scratch/m4.patch`:

    -            wholes[writeIndex] = whole
    +            wholes[writeIndex] = result

`scratch/m4.log`:

    266:OversizedResultTest[jvm] > a caller in the same process reads an oversized answer whole()[jvm] FAILED
    273:InspectorTest > a selection with more fields in common than an outside agent gets inline still lists every one() FAILED

`scratch/m4.fail` line 2: `editor.common_fields answered with no fields member: {"resultTooLarge":true,"resultChars":1757,"maxInlineChars":1236,"resultRef":null}`. That is the same failure `scratch/insp-red.log` shows for the new `InspectorTest` case before `EditorTools` changed (line 267), which is the test-first run.

## 2. Summary

**What a person gets.** Select a model that has a `Transform3D` and turn the Scene tab to 3D. The editor then draws:
- three arrows
- three small plane squares
- three rotation rings
- a box at the end of each axis, for scale
- a box in the middle, to scale all three axes at once

Dragging a handle moves the pointer's ray onto that handle's line or plane, so an arrow moves the model along its axis whichever way the camera looks. A plane square moves the model inside its plane. A ring turns one angle. An axis box scales one axis by how much further out it is pulled. The middle box scales all three together as the pointer goes up and to the right. Each drag is one G1 edit session and one undo entry. Escape puts the model back. Grid snapping, angle snapping, Ctrl to skip snapping, and world/local axes all work exactly as they do in 2D, because the 3D handles are ordinary handles with the same `Snap` and `AxisFrame`.

**How, briefly.**
- `udea-render`: `EditorCamera.ray(viewX, viewY, out: ViewRay)`, which in 3D runs from the eye through the pixel and in 2D runs straight down. Also `EditorCamera.unitsPerPixelAt(x, y, z)`. These are public because `udea-editor` uses them, and they expose floats and a plain `ViewRay` class only, no Kool type.
- `udea-editor` `GizmoDrag`: the 2D ground-plane read is replaced by the ray. An Along drag takes the closest point between two lines, an Across drag intersects the ray with a plane, and a ViewPlane drag holds the plane facing the press ray for the whole drag. The 3D refusal is gone.
- The gizmo API gains public pieces 3D needed, all floats:
  - `HandleShape.PlaneTab`, `ScaleBox` and `UniformBox`
  - `Drag.unitsPerPixel`
  - `Drag.turnAbout(centre, axis)`
  - `AxisFrame.euler(x, y, z)`
  - the built-ins `translateHandles`, `rotationRings` and `scaleHandles`

  The painter and hit-testing handle the new shapes. A ring now gives way to any other handle it crosses (`HandleLayer.press`), because a ring seen at an angle is a thin ellipse cutting through the arrows.
- `udea-annotations`: `@RotationHandle` gains `aboutX` and `aboutY`, and there is a new `@ScaleHandle`. `udea-codegen` emits `translateHandles`, `rotationRings` and `scaleHandles` for a 3D component. It reports UDEA0017 if only one of `aboutX`/`aboutY` is named.
- `udea-core` `Transform3D`: now `@Replicated` with every field `@Sim`, plus `@PositionHandle(z)`, `@RotationHandle(aboutX, aboutY)` and `@ScaleHandle`. Every annotation argument is written out, because KSP on KMP common metadata reports no annotation defaults.
- `moba`: `Transform3D` is reachable through the agent tools, and the gizmo placement uses it.

**A second defect this surfaced, fixed on the branch with the lead's approval.** Once `Transform3D` is editable, `editor.common_fields` for the moba Fox answers with 1529-1553 characters. The bridge's inline cap for outside agents is 1236 (`AgentBridge.MAX_DELIVERABLE_RESULT_CHARS`), and above it the answer is swapped for a spill handle. The editor's Inspector read its answers from the same ring, found no `fields`, and threw every frame. `MobaAnimationPanelTest` went red on this branch, which is how it was found.

The bug was latent on master for any selection with enough fields in common. The fix: the result ring keeps each answer as the tool gave it beside the capped copy, the new `AgentBridge.wholeCommandResults()` returns the whole ones, and `EditorTools.frame` reads those. `commandResults()`, `/state` and everything an HTTP caller sees are unchanged. `docs/contracts/agent-tools.md` says nothing about the cap and is untouched.

I checked it live in `:moba:desktop:runEditor -PeditorFox=true -PdebugPort=7846`:
- `/state` showed the editor's own `common_fields` read (command 19) as `{"resultTooLarge":true,"resultChars":1553,"maxInlineChars":1236,"resultRef":"cap_0009"}` to the outside caller.
- The editor window threw nothing: `scratch/editor.log` has 0 lines containing `Exception`.

dev-238 says their play-edits panel will need the same fix at about 7 play edits.

**Decisions, each commented on #237:**
1. `Transform3D` is `@Replicated` with every field `@Sim`, so it is never on the wire. That is the only way the tools can reach its fields (`AgentComponentType` needs a generated `Replicator`).
2. The rings follow the model's Euler gimbal (R = Rz·Ry·Rx), not world axes. The X ring's frame is the whole rotation, Y's is the rotation after X, and Z's is Z alone, so each ring changes exactly its own field.
3. The scale formulas, and no snapping on scale.
4. The API and annotation additions above.
5. The editor reads answers whole (the second defect above).

**Rejected alternatives:**
- World-axis rings: they would write a mix of angles for one ring.
- A separate rotation type, such as a quaternion field: Kool types would leak, and the lead had ruled for plain floats.
- Shrinking the `common_fields` answer, or teaching the Inspector to follow a spill handle: the first only moves the limit; the second means file reads on the render thread.

**Not exercised:**
- A drag whose ray is exactly parallel to the handle's line or plane: the move is skipped by design. No test aims one exactly, only near.
- Scale below zero: clamped at 0 by design, with no GL case.
- iOS: this box cannot build it.

## 3. What the tests cover

- `Scene3DGizmoDragTest` (headless, 9 cases): each arrow, plane square, ring, axis box and the middle box, plus Escape, grid snap with Ctrl, angle snap, and local axes. Each goes through `EditorToolLoop` and the real `editor.*` tools, with a 3D `EditorCamera`.
- `BuiltinGizmos3DTest` (10 cases): the built-ins' written values, `AxisFrame.euler`, and `Drag.turnAbout`.
- `HandleLayerTest`: a ring gives way to a handle it crosses.
- `EditorCameraTest` (3 new cases): the ray passes through projected points, the 2D ray goes straight down, and units per pixel matches the measured scale.
- `GeneratedGizmoTest` and `GizmoProcessorTest`: the `Drone` fixture's generated translate, rotation and scale gizmos in world and local axes, the aboutX-without-aboutY error, and a misspelled scale field.
- `MobaGizmoRegistryTest`: moba's registry lists the three `Transform3D` gizmos.
- `OversizedResultTest` (1 new case) and `InspectorTest` (1 new case): the second defect.
- `GlGizmo3DDragTest`: section 1.

## 4. Build

The first full `sh gradlew build --continue --max-workers=6` on `3df79e2` (`scratch/build.log` from that run was overwritten by the second) was red on:
- `:udea-core:udeaCheckProtocolLock`
- `:udea-codegen:test` (`GeneratedFileDeterminismTest` and `GeneratedSourceShapeTest`)
- `:moba:desktop:editorTest` (`MobaAnimationPanelTest`, the second defect)

`fe8aa74` fixes all three. The second full build, on `fe8aa74`, `scratch/build.log` lines 1620-1623:

    BUILD SUCCESSFUL in 1m 12s
    975 actionable tasks: 60 executed, 5 from cache, 910 up-to-date
    Configuration cache entry reused.
    DONE 0

(It is short because the first build had already run everything that did not change.)

GL, under xvfb with software GL, `scratch/glall.log` lines 251-254:

    > Task :udea-agent-host:udeaAgentGlTest
    > Task :udea-editor:udeaEditorGlTest
    ...
    BUILD SUCCESSFUL in 1m 9s

In that run `:udea-render:udeaGlTest` came `FROM-CACHE` (line 220). A cached result says nothing about this tree's GL, so I re-ran it with `--rerun` (`scratch/glrender.log` lines 160 and 164: `> Task :udea-render:udeaGlTest`, `BUILD SUCCESSFUL in 1m 29s`). JUnit XML from these runs, 0 skipped everywhere:
- `udea-render/build/test-results/udeaGlTest`: 22 suites, 23 tests.
- `udea-agent-host`: 2 suites.
- `udea-editor`: 6 suites, including `GlGizmo3DDragTest` and `GlGizmoDragTest`.

Command: `xvfb-run -a -s "-screen 0 1280x720x24" env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe sh gradlew udeaGlTest udeaAgentGlTest udeaEditorGlTest -Pudea.render.requireGl=true`.

Not run: `runUdpProof` and `runLaneShot`. This ticket touches neither the wire (Transform3D is `@Sim` only) nor the lane.

## 5. Images

All in `/srv/ssd1/workspace/Udea/build/debug-screenshots/`. Each is taken by `GlGizmo3DDragTest` while the named handle is held (drawn white), except the last.

- `issue237-collage.png`: all sixteen GL shots below, labelled.
- `issue237-fox-selected-all-handles.png`: the Fox selected, with every 3D handle drawn: arrows, plane squares, rings, scale boxes and the middle box.
- `issue237-translate-x-arrow.png`, `-y-arrow`, `-z-arrow`: each arrow mid-drag. The Fox has moved along that axis alone.
- `issue237-translate-xy-plane.png`, `-yz-plane`, `-xz-plane`: each plane square mid-drag. The Fox moves within that plane.
- `issue237-rotate-x-ring.png`, `-y-ring`, `-z-ring`: each ring mid-turn, from the tilt.
- `issue237-rotated.png`: after the three ring turns.
- `issue237-scale-z-box.png`: the Z box pulled out. The Fox is taller only.
- `issue237-scale-uniform-box.png`: the middle box dragged up and to the right. The Fox is bigger on every axis.
- `issue237-snap-grid-z-arrow.png`: a Z move with grid snapping on.
- `issue237-snap-angle-z-ring.png`: a Z ring turn with angle snapping on.
- `issue237-local-axes-x-arrow.png`: local axes. The arrows turn with the Fox, and its own X arrow is held.
- `issue237-moba-editor-fox-gizmos-2d.png`: the real moba editor (`runEditor -PeditorFox=true`), with the Fox selected over the bridge as `editor`. The Scene tab is in its 2D view, and the `Transform3D` gizmos come from moba's generated registry. Taken with `editor.screenshot view=scene`. It shows the moba wiring. The 3D drags are in the GL shots above.

## 6. The issue, criterion by criterion

1. **Each 3D handle changes Transform3D by the expected amount along or around its axis, in one undo entry, and Escape restores it, shown by GL tests driving real pointer input through a 3D editor camera.**
   - `GlGizmo3DDragTest` (section 1): arrows by `MOVE` along each axis, rings by a quarter turn, the Z box by 1.5 times, and the middle box by the factor the camera's own rays predict (`scratch/gl3d.log` run: `the middle box scaled by 1.7886604, the camera's rays say 1.7886608`).
   - After each drag it asserts `editor.history` grows by exactly one `editor.commit_edit`.
   - Escape is checked on an arrow, a ring and a scale box, each asserting every field is back and no entry is filed.
   - Headless, the same is in `Scene3DGizmoDragTest`.
2. **Plane handles move the model within their plane only, and a rotation ring changes only its own axis.**
   - The GL test asserts each plane square's move has no component along the plane's normal, and each ring leaves the other two angles unchanged within `ANGLE_TOLERANCE`. m3 shows the ring check going red.
   - Headless: `each plane square moves the model within its plane alone` and `each ring turns its own angle alone by a quarter for a quarter round it`.
3. **Snapping and local axes behave as in 2D.**
   - GL: a Z move rounded to the 0.25 grid, then Ctrl writing it exactly; a 50-degree ring drag landing on the 45-degree step; a local X arrow moving the Fox along its own X and not the world's.
   - Headless: the last three `Scene3DGizmoDragTest` cases.
4. **Screenshots of each 3D handle on a textured model are posted.** Section 5, and the collage on the dashboard ("#237: 3D gizmos work with the real mouse").

## 7. Regenerated files

Both were rewritten by their tasks, not by hand:
- `udea-core/net-protocol.lock` by `:udea-core:udeaWriteProtocolLock`:
  - `component 28 dev.wildware.udea.core.spatial.Transform3D` is added, with 9 fields: rotationX/Y/Z, scaleX/Y/Z, x, y, z.
  - `protoHash` goes from `0xa328` to `0x35f2`.
  - **No existing id moved**: Transform3D sorts after every other name in the file.
- `udea-codegen/src/test/resources/expected-generated-hashes.txt` by `:udea-codegen:test -Pudea.updateGeneratedHashes=true`:
  - Six files added: `Drone{Position,Rotation,Scale}Gizmo` and `core/spatial/Transform3D{Position,Rotation,Scale}Gizmo`.
  - Three hashes changed: `CratePositionGizmo` (its 3D position now calls `translateHandles`), `CodegenFixturesGizmoRegistry` and `CodegenFixturesModuleRegistry`.

`net-components.lock` has no task and is hand-reviewed. It gains `dev.wildware.udea.core.spatial.Transform3D` at the end (id 28), with a header paragraph saying why. `moba/game/net-protocol.lock` did not change: its `udeaCheckProtocolLock` is green.
