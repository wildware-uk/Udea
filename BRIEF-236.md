8e5c815

(The code under review is `8e5c815`. The commit that adds this brief sits on top of it and changes nothing else.)

# BRIEF-236: Editor gizmos G5: built-in 2D move, resize, rotate and range gizmos, snapping, moba dogfood

Branch `issue-236-builtin-2d-gizmos`, off `origin/master`. It has merged `origin/master` at `a300db0`, which includes #243.

## 1. Evidence command

    xvfb-run -a -s "-screen 0 1280x720x24" env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe \
      sh gradlew :udea-editor:udeaEditorGlTest --tests '*GlGizmoDragTest*' -Pudea.render.requireGl=true

`GlGizmoDragTest` runs a real Kool backend and moves a real mouse and keyboard through Kool's own GLFW callbacks. It tests, in order:
- the X arrow
- the rotate ring
- a box corner
- a radius rim
- Escape in the middle of a drag
- one drag on two selected entities
- grid snapping, then Ctrl to skip it
- local axes

After each step it checks the world and the `editor` author's `editor.history`. It saves a picture of the window while each handle is held.

**It goes red when the feature is reverted.** `scratchpad/issue236/evidence-red.sh` applies the change below, runs the command above, and puts the line back. The diff is `evidence-red.diff`, taken from that run:

    diff --git a/udea-editor/src/main/kotlin/dev/wildware/udea/editor/SceneNavigation.kt b/udea-editor/src/main/kotlin/dev/wildware/udea/editor/SceneNavigation.kt
    index d6036ef..26a0cf4 100644
    --- a/udea-editor/src/main/kotlin/dev/wildware/udea/editor/SceneNavigation.kt
    +++ b/udea-editor/src/main/kotlin/dev/wildware/udea/editor/SceneNavigation.kt
    @@ -65,7 +65,7 @@ internal class SceneNavigation(
                     drag = when {
                         !onView -> Drag.None
                         event.button == PointerButton.Primary && view.pressGizmo(at.x, at.y) -> Drag.Gizmo.also {
    -                        gizmos?.press(at.x, at.y)
    +                        Unit
                             // The pressed handle is drawn lit.
                             moved = true
                         }

`evidence-red.log`, lines 217-218 and 222:

    GlGizmoDragTest > each built-in handle dragged with the real mouse changes the entity by the drag in one undo entry, and Escape puts it back() FAILED
        org.opentest4j.AssertionFailedError at GlGizmoDragTest.kt:280
    ...
    > Task :udea-editor:udeaEditorGlTest FAILED

The failure message, from the JUnit XML saved as `evidence-red.xml`:

    failure message="org.opentest4j.AssertionFailedError: the X arrow did not move the body by the drag: expected -3.7924528, was -5.0"

With the line restored, the same command is green. See section 4, `udeaEditorGlTest`, 5 tests and 0 skipped.

Every artefact named here is under `/tmp/claude-1000/-srv-ssd1-workspace-Udea/1ad8c5e6-2def-4055-91d2-72acdfe77daf/scratchpad/issue236/`.

## 2. Summary

**What a person gets.** Select entities in the Scene tab and the handles of each gizmo they share are drawn:
- arrows along X and Y, and a square for a free move
- box corners and the middle of each side, with an outline between the corners
- a ring to rotate
- a grip on the rim of a radius or a range, with the circle drawn at its size in the world

Dragging a handle opens one `editor.begin_edit`. Each move is an `editor.update_edit`, which is live and files nothing. Release is `editor.commit_edit`, so a drag is **one undo entry** however many moves it took. Escape is `editor.cancel_edit`, which puts every value back and files nothing.

With several entities selected, a handle they share is drawn at their centre. One drag moves all of them in **one** edit, and each is measured from its own position. So a shared radius grip grows every circle by the drag, not by its distance from the centre.

A toolbar over the Scene tab holds Grid (on/off and a step), Angle (on/off and a step in degrees) and a World/Local switch. Holding Ctrl during a drag skips snapping.

**How it is built. There is no private path.** The built-ins are public extension functions on `GizmoScope`: `moveHandles`, `sizeHandles`, `rotationHandle`, `radiusHandle`, `rangeHandle`. They use only `handle`, `mark` and `DragScope.write`. The gizmos `udea-codegen` generates from `@PositionHandle` / `@SizeHandle` / `@RotationHandle` / `@RadiusHandle` / `@RangeHandle` in 2D are now one call to one of them (`GizmoEmitter`). The 3D bodies it generates are unchanged; they are G6's (#237).

Every shape is drawn and hit-tested by one internal `HandlePainter`. It is dimension-neutral: shapes are world points and axes, projected through `GizmoCanvas.project`, and an arrow or a ring finds its direction on screen by projecting a step. The drag in the 3D view is left to #237. `GizmoDrag` refuses a press in 3D and says so in the status line.

**Public API added** (issue comment 3 has the list and the reasons):
- `GizmoCanvas.line`, byte for byte #243's
- `GizmoScope.mark`, `Mark` and `Gizmo.marks`, #243's
- `HandleShape.Circle(radius, normal)` and `HandleShape.BoxEdge(axis)`
- `AxisFrame`, `GizmoTarget.axes`, and an `axes` argument on `handle` plus `Handle.axes`
- `Snap` and `write(field, value, snap)` / `FieldWrite.snap`
- `Drag.along`, `Drag.spread`
- `GizmoPlacement` / `Placement`
- `EditorGizmos`, `GizmoPreferences`
- `AgentComponentType.componentType` and `AgentComponentIndex.findByType`, which name a gizmo's component in an edit session
- `EditorTags.SCENE_VIEW`, made public so moba's editor test can find the Scene tab

**Folded with #243, which merged first.** This branch had its own `Guide` type for "drawn, never grabbed". I replaced it with #243's `mark` API, taken verbatim, before the merge. The merge produced a duplicate `mark()`, which I removed. `GizmoMarkLayer` (the bone overlay's layer) now draws through `HandlePainter`, so a bone, a box outline and a range ring are drawn one way, in #243's yellow with a dark edge. `GlAnimationPreviewTest` reads that colour from `HandlePainter.MARK` now.

The handle layer is built over the Scene view's existing layer, so the two stack. The bone overlay stays drawn and still gets a press that misses every handle (`HandleLayerTest`). Before this, a session with gizmos would have replaced the bone overlay.

**moba dogfood.**
- `Position`'s `@PositionHandle` already existed. Its generated gizmo is now the built-in move gizmo (three handles).
- `TowerRangeGizmo`, in `moba/desktop/src/editor`, is written by hand against the public API. It marks a circle at the range and puts a grip on the rim, held to X, that writes `Tower.attackRange`. `TowerRangeGizmoTest` scans its `import` lines: only `dev.wildware.udea.editor.gizmo.*`, `Tower` and Fleks' `ComponentType`. A control case shows a commented-out import is not read. It is also in another module, so the compiler already refuses anything `internal`.
- A tower's range was the constant `LaneGeometry.TOWER_RANGE`, so there was nothing to drag. `Tower` now has `@Sim var attackRange = TOWER_RANGE`, and `TowerSystem` targets by it (issue comment 2).
- `MobaAgent` adds `Tower` to the tool surface, read-only for `world.*`, and hands its component index to the editor.
- `MobaPlacement` places an entity at its `Position`.

**Decisions, each commented on #236:**
1. Built-ins are public `GizmoScope` functions, and generated gizmos call them.
2. `Tower.attackRange` is a `@Sim` field.
3. Preferences are kept in `<project>/.udea/editor-preferences.properties`, which `runEditor` names with `-Dudea.editor.preferences` and git ignores.
4. The public API list above.
5. With world axes, an arrow writes only its own axis. The GL test caught this: with grid snapping on, dragging the X arrow also rounded `y` onto the grid and nudged the entity sideways.

**Deferred on purpose:** dragging in the 3D view (#237).

## 3. Mutations

Each row applies one change with `scratchpad/issue236/mutate.py`, saves `git diff` to `mut/<name>.diff`, runs the tests shown, lists the failing tests from the JUnit XML (`failed.py`) and reverts. `mutations.sh` and `mutations2.sh` ran them all. `mut/status-after.txt` shows the tree back as it was. The diffs below are copied from `mut/*.diff`.

**release-cancels** (`:udea-editor:test`): 9 failed of 98.

    -        if (drag.finishing) close(drag, EditorInspector.COMMIT_EDIT, session)
    +        if (drag.finishing) close(drag, EditorInspector.CANCEL_EDIT, session)

The failures are every `SceneGizmoDragTest` drag case: X arrow, square, ring, box, rim, two selected, grid + Ctrl, angle snap, local axes.

**escape-ignored** (`:udea-editor:test`): 1 failed.

    -    internal val keys: EditorKeys = EditorKeys(escape = { gizmoDrag?.takeIf { it.holding }?.cancel() != null })
    +    internal val keys: EditorKeys = EditorKeys(escape = { false })

`SceneGizmoDragTest > Escape mid-drag puts the entity back and files nothing, and the release after it does nothing()`

**no-centre-shift** (`:udea-editor:test`). The first run had **0 failed of 98**: a move is the same wherever it is measured from, so no test could see this. I added the two-selected radius test, and the second run (`mutations2.sh`) has 1 failed of 99. That diff also contains the new test, which was not committed yet; only its first hunk is the mutation:

    -                HandlePart(handle, WorldPoint(handle.at.x - at.x, handle.at.y - at.y, handle.at.z - at.z))
    +                HandlePart(handle, WorldPoint(0f, 0f, 0f))

`SceneGizmoDragTest > with two selected a radius drag on the shared grip grows each by the drag, measured from its own centre()`

**ctrl-ignored** (`:udea-editor:test`): 1 failed.

    -        val bypass = ctrl()
    +        val bypass = false

`SceneGizmoDragTest > grid snapping rounds the position to the step, and Ctrl held writes it exactly()`

**along-unheld** (`:udea-editor:test`): 1 failed.

    -                is DragConstraint.Along -> {
    +                is DragConstraint.Along -> if (true) point else {

`SceneGizmoDragTest > local axes turn the arrows with the entity, and world axes do not()`. With world axes the X arrow writes only `x`, so an unheld drag there cannot change `y`. The turned arrow is what shows the line is held.

**local-ignored** (`:udea-editor:test`): 1 failed.

    -        val axes = if (preferences.axes == GizmoAxes.Local) placed.first().place.axes else AxisFrame.WORLD
    +        val axes = AxisFrame.WORLD

`SceneGizmoDragTest > local axes turn the arrows with the entity, and world axes do not()`

**under-unpressed** (`:udea-editor:test`): 1 failed.

    -        return pressed != null || under?.press(canvas, viewX, viewY) == true
    +        return pressed != null

`HandleLayerTest > a press that misses every handle goes on to the layer underneath()`

**arrow-writes-both** (`:udea-editor:test`): 1 failed.

    -        val arrow = move(abs(direction.x) > ACROSS, abs(direction.y) > ACROSS)
    +        val arrow = move(acrossX = true, acrossY = true)

`BuiltinGizmosTest > move is two axis arrows and a free square, all on the entity, each moving it by the drag()`

**no-drag** (`:udea-editor:test :moba:desktop:editorTest`): 11 failed of 120.

    -                        gizmos?.press(at.x, at.y)
    +                        Unit

The failures are `TowerRangeGizmoTest > dragging a selected tower's rim out in the Scene tab lengthens its range, as one undo entry()` and 10 `SceneGizmoDragTest` cases.

**tower-constant** (`:moba:desktop:test --tests '*LaneProofTest*' :moba:desktop:editorTest`): 1 failed of 30.

    -            if (distance > tower.attackRange) continue
    +            if (distance > LaneGeometry.TOWER_RANGE) continue

`LaneProofTest > a tower shoots only as far as its own attack range()`

**Control:** `mut/control.failed`, the unmutated `:udea-editor:test` in the same script, has 0 failed of 99.

**TDD.** `LaneProofTest`'s range test was written before `TowerSystem` read the field. It failed with `expected: <0> but was: <2>`: `lane1-red.log` line 311, and the JUnit message. It is green in `lane1-green.log`. `WorldViewportTest`'s line test did not compile until `GizmoCanvas.line` existed. The editor's drag tests passed on their first run, so the mutations above are what show they can fail.

**Not tested anywhere:** that the handle layer *draws* the layer under it (`under?.draw(canvas)` in `HandleLayer.draw`). A `GizmoCanvas` is made only inside `udea-render`'s GL pass, so a headless test cannot reach it. No GL test has both the Animation panel and gizmos: `GlAnimationPreviewTest` builds its session without `gizmos`, so the handle layer is absent there. The press half of the stacking is tested (`HandleLayerTest`); the draw half is one line, read but not run.

## 4. `sh gradlew build`

    ANDROID_HOME=$HOME/Android/Sdk JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem sh gradlew --max-workers=6 build --continue

`build1.log`, lines 1687-1689:

    BUILD SUCCESSFUL in 2m 12s
    984 actionable tasks: 607 executed, 216 from cache, 161 up-to-date
    Configuration cache entry stored.

No other build was running on the box at the time. A melon-merge `core:test` had finished 20 seconds before.

**GL, for real:**

    xvfb-run -a -s "-screen 0 1280x720x24" env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe \
      sh gradlew --max-workers=6 udeaGlTest udeaAgentGlTest udeaEditorGlTest -Pudea.render.requireGl=true

`glall.log`, lines 282-289:

    > Task :udea-agent-host:udeaAgentGlTest
    > Task :udea-render:udeaGlTest
    > Task :udea-editor:udeaEditorGlTest

    [Incubating] Problems report is available at: file:///srv/ssd1/workspace/Udea/.claude/worktrees/agent-a5b67b0b5aa591be1/build/reports/problems/problems-report.html

    BUILD SUCCESSFUL in 1m 30s
    126 actionable tasks: 12 executed, 114 up-to-date

Counts from the JUnit XML of that run:
- `udeaGlTest`: 23 tests, 0 skipped, 0 failed
- `udeaAgentGlTest`: 2 tests, 0 skipped, 0 failed
- `udeaEditorGlTest`: 5 tests, 0 skipped, 0 failed; `GlGizmoDragTest` is one of them

**Not run:** `runUdpProof`, and `runLaneShot`, which needs GL. The branch changes a tower's range from a constant to a field with the same starting value. The replay fixtures were regenerated and replay green inside `build`.

## 5. Images

All are in `/srv/ssd1/workspace/Udea/build/debug-screenshots/`. Each `issue236-gl-*` picture is saved by `GlGizmoDragTest` while the handle is held, so the lit handle is the one being dragged.

- `issue236-gl-move-x-arrow.png`: the X arrow, lit, dragged right. The body moves along X only.
- `issue236-gl-resize-corner.png`: a box corner dragged. The yellow outline and eight grips, with the Inspector showing the new `Box.halfWidth`.
- `issue236-gl-rotate-ring.png`: the ring, lit, turned a quarter. The nub on the body shows its heading.
- `issue236-gl-radius-rim.png`: the radius grip dragged out. The yellow circle is drawn at the radius.
- `issue236-gl-two-selected-move.png`: two bodies selected. One set of handles at their centre, and the Inspector says "2 entities selected".
- `issue236-gl-grid-snap.png`: Grid on (toolbar checkbox ticked, step 1.0) during a snapped X-arrow drag.
- `issue236-gl-grid-ctrl-bypass.png`: the same arrow dragged with Ctrl held.
- `issue236-gl-local-axes.png`: the toolbar switch reads "Local". On a body turned a quarter, the X arrow points up the screen.
- `issue236-gl-collage.png`: the eight pictures above, tiled.
- `issue236-moba-tower-range-ring-150.png`: moba's real editor (`runEditor`, driven over its agent port, `editor.screenshot view=scene`). A tower selected, its range ring drawn at the built 150, the grip on the rim, and `Position`'s move arrows.
- `issue236-moba-tower-range-ring-90.png`: the same tower after `editor.set_field Tower.attackRange=90`. The ring follows the field.
- `issue236-moba-two-units-selected.png`: two moba units selected with `editor.select`. Both are outlined, and one set of move handles sits between them.

For the moba shots I moved the tower next to the fight with `editor.move` so the ring was in view. That session was never saved. I had no way to move the mouse in the live window: xdotool is not installed. The real-mouse drag is `GlGizmoDragTest`, and `TowerRangeGizmoTest` drags the tower's rim through the editor window's own pointer in a real moba world.

## 6. Criterion by criterion

- **Dragging each handle changes the entity by the expected world amount in one undo entry, and Escape mid-drag restores it (GL tests driving real pointer input).** `GlGizmoDragTest` covers the X arrow, the ring, a box corner, a radius rim and Escape, with a history check after each. The headless `SceneGizmoDragTest` also covers the square, a box side, angle snapping and the rest. Pictures: move, rotate, resize and radius above.
- **A multi-selection drag moves every selected entity as one edit.** `GlGizmoDragTest` covers the two-selected step. `SceneGizmoDragTest` has the move case and the radius case measured from each entity's centre (the no-centre-shift row in section 3). Pictures: `issue236-gl-two-selected-move.png` and `issue236-moba-two-units-selected.png`.
- **Snapping rounds to the configured step and Ctrl bypasses it.** `GlGizmoDragTest` covers grid then Ctrl. `SceneGizmoDragTest` covers grid + Ctrl and angle snapping. `GizmoPreferencesTest` covers rounding per kind, bypass, the file round trip and the toolbar. Pictures: grid-snap and grid-ctrl-bypass.
- **Local axes follow the entity's rotation.** `GlGizmoDragTest` covers the local step. `SceneGizmoDragTest > local axes turn the arrows with the entity, and world axes do not()` and `BuiltinGizmosTest` cover it headless. Picture: `issue236-gl-local-axes.png`.
- **The tower range-ring gizmo works and imports nothing outside the public API.** `TowerRangeGizmoTest` (values, the import scan and its control, and a drag through the editor window on a real tower in a real moba world) and `MobaGizmoRegistryTest`. `LaneProofTest > a tower shoots only as far as its own attack range()` shows the range is what the game reads. Pictures: the two moba ring shots.
- **Screenshots of each gizmo are posted.** Section 5, the gallery, and the dashboard: a collage card and a range-ring card.

## 7. Regenerated files

- **`moba/game/net-protocol.lock`**, by `:moba:game:udeaWriteProtocolLock` (`lock-diff.txt`).
  - `protoHash 0xc67b` became `0xfbba`.
  - No component id moved.
  - Inside `component 11 dev.wildware.moba.lane.Tower`, `attackRange f32:32` is field 0, because fields are in name order. `readyTick`, `shots`, `targetRaw` and `team` each move up one, from 0-3 to 1-4.
  - `LaneModule`'s hand-written `ComponentSchema` for `Tower` puts `FieldKind.Float` first to match. The first run had it last, and the snapshot refused it with "Tower.attackRange is a Long field, accessed as Float".
- **`moba/desktop/src/test/resources/fixtures/moba-3600.udearep` and `moba-36000.udearep`**, by `:moba:desktop:udeaWriteReplayFixture`. Its own reason (`fix1.log`, lines 294-295): "rebuilt because this build cannot replay it - protoHash: recorded 0x0d1e, this build 0xe91d". The sizes are unchanged: 59572 and 590901 bytes.
- **`udea-codegen/src/test/resources/expected-generated-hashes.txt`**, by `:udea-codegen:test -Pudea.updateGeneratedHashes=true`. Four lines changed, one per gizmo whose generated body now calls a built-in: `BeaconPositionGizmo`, `BeaconReachRadiusGizmo`, `BeaconSightRangeGizmo`, `CrateRotationGizmo`. No id is involved.
- **`udea-codegen/net-protocol.lock`**: unchanged. No engine component was added.
