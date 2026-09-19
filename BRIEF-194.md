a654700

# BRIEF-194: the editor window

Branch `issue-194-editor-window`, cut from `origin/kmp` at `9d6629f`. `origin/kmp` has been merged twice since then: `e1311e0` (#232) and `ae3e32a` (#240). `a654700` is the second merge, and every result below was produced on it unless a line says otherwise. The commit that adds this file sits on top of it and changes nothing else.

## Evidence command

```
xvfb-run -a -s "-screen 0 1280x720x24" env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe sh gradlew :udea-render:udeaGlTest --rerun --tests '*GlWorldViewTest' :moba:desktop:editorTest --rerun -Pudea.render.requireGl=true --continue
```

It covers two things. The first is the world drawn by Kool inside the editor's `SceneView`, with the panels kept out of a capture, on a real GL context. The second is the window's button calling `editor.spawn` against the real `EditorToolset` in a real `moba` world, where the edit shows in the `editor` author's undo history and the world stays paused. `--rerun` is there because without it, one of my runs restored `editorTest` FROM-CACHE instead of executing it.

**It goes red when the feature is reverted.** The revert, applied by `/tmp/claude-1000/-srv-ssd1-workspace-Udea/1ad8c5e6-2def-4055-91d2-72acdfe77daf/scratchpad/issue194/evidence.py` and restored with `git checkout`, puts back the shape before this branch: the viewport draws nothing, and the button sends nothing.

```diff
diff --git a/udea-editor/src/main/kotlin/dev/wildware/udea/editor/EditorSession.kt b/udea-editor/src/main/kotlin/dev/wildware/udea/editor/EditorSession.kt
index e806728..ebd4815 100644
--- a/udea-editor/src/main/kotlin/dev/wildware/udea/editor/EditorSession.kt
+++ b/udea-editor/src/main/kotlin/dev/wildware/udea/editor/EditorSession.kt
@@ -101,7 +101,7 @@ public class EditorSession(
 
     /** The Create panel's button. */
     internal fun spawn() {
-        edit(
+        if (false) edit(
             SPAWN,
             mapOf("blueprint" to spawn.blueprint.value, "x" to spawn.x.toString(), "y" to spawn.y.toString()),
         )
diff --git a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/ui/WorldView.kt b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/ui/WorldView.kt
index ff298cc..cabf38a 100644
--- a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/ui/WorldView.kt
+++ b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/ui/WorldView.kt
@@ -44,7 +44,7 @@ public class WorldView internal constructor(private val blit: PassBlit) {
     public fun drawInto(scene: SceneDrawScope) {
         val width = scene.width
         val height = scene.height
-        scene.raw { blit.into(width, height) }
+        Unit
     }
 
     override fun toString(): String = "WorldView($blit)"
```

Both runs on `a654700`, as the script wrote them from the JUnit XML:

```
# gradle exit 0
GlWorldViewTest > a SceneView shows the capturable world the right way up, and the capture never shows the panels(): PASSED
MobaEditorTest > the spawn button adds a unit beside the player, paused, in the editor author's undo history(): PASSED
MobaEditorTest > the editor opens paused, and the frames it pumps do not tick the world(): PASSED
```

```
# gradle exit 1
GlWorldViewTest > a SceneView shows the capturable world the right way up, and the capture never shows the panels(): FAILED org.opentest4j.AssertionFailedError: the top of the panel should show the world's top band, was #000000 ==> expected: <16711680> but was: <0>
MobaEditorTest > the spawn button adds a unit beside the player, paused, in the editor author's undo history(): FAILED org.opentest4j.AssertionFailedError: the edits filed under `editor` were [] ==> expected: <[editor.spawn]> but was: <[]>
MobaEditorTest > the editor opens paused, and the frames it pumps do not tick the world(): PASSED
```

## Summary

`sh gradlew :moba:desktop:runEditor` opens `moba` in a window. It has a menu bar, a docked Create panel on the left, a docked History panel on the right, the world in a ComposeGL `SceneView` between them, and a status line. The world is paused before the first frame. Every button is an `editor.*` tool call submitted to the same `AgentBridge` an HTTP agent reaches, filed under the author `editor`. There is no second implementation of an edit in the editor.

What was built:

- **`udea-editor`**, a new JVM module over `udea-render` and `udea-agent`. `EditorSession` holds the window (a `UiScreen`) and a per-frame `frame()`. `EditorTools` submits calls and delivers answers from the bridge's result ring; an answer that has already left the ring is reported as `answer_lost` so nothing waits forever. The History panel is only ever `editor.history`'s answer. It is re-read whenever any command other than its own read completes, so an agent's edits under `session=editor` show up too. The docked panels are ComposeGL's `composegl-debug` (`DebugWindowHost`, `dockToScreen`, `MenuBar`). There is one `SceneView`, and it draws through a viewport block, which is the seam a second view (epic #231) can reuse. The module bundles DejaVu Sans with its licence.
- **`udea-render`: `WorldView`**. `KoolBackend.worldView()` hands out a `WorldView` whose `drawInto(scope)` runs inside the `SceneView`'s `raw {}` block. It blits the capturable pass's colour texture, letterboxed, into whatever framebuffer the `SceneView` has bound, then restores the previous read binding (`PassBlit`). The world is drawn once, by Kool, as before. The window's panels are a `UiLayer`, which draws only to the window, so a screenshot cannot contain them. `Letterbox.fit` is shared with `KoolSurface`'s present. Nothing in `udea-render/.../model/` was touched.
- **`:moba:desktop`**: an `editor` source set holds `MobaEditor`, and `runEditor` runs it. Only it and the `editorTest` source set built over it resolve `udea-editor`. `MobaAgent.runWithGl` gained a screen hook, so the editor is the same agent instance as `run -Peditor=true` with a window on top. The window's layer takes keys before the game, so Ctrl+Z reaches Edit > Undo. A separate `editorTest` source set and task (on `check`) hold `MobaEditorTest`.
- **Two module-graph rules.** `UDEA-MG-010`: no project's `compileClasspath` or `runtimeClasspath` may resolve `:udea-editor`. `UDEA-MG-011`: `udea-editor`'s compile classpath may not name Kool, LWJGL or a ComposeGL backend. `udea-editor` joins the GL-allowed set for run time only.
- **Docs**: the `AGENTS.md` module row, and its "there is no level editor" sentence now says the editor is a screen over the tool surface. `docs/module-graph.md` gets the row, the arrows and both rules.

Decisions, each commented on #194 with how to reverse it:

- The world reaches the `SceneView` by a framebuffer blit inside `udea-render`. Re-rendering inside `raw {}` was rejected because Kool draws only through its own passes.
- The release rule is `UDEA-MG-010`, a classpath rule. A check that only reads the jar was rejected, because a dependency can sit on the classpath without being in the jar.
- The one button places a skeleton 48 units right of the player, because the camera starts on the player and a unit on the player's own spot would be hidden under its sprite. Edits are filed under the author label `editor`, so an agent can name that author.
- The panels use `composegl-debug`. `udea-assets` is not a direct dependency yet: nothing names an asset type, and it arrives through `udea-render`'s `api`. The asset panels (#195) add it with their first use.
- The editor's code and tests are in their own source sets. The Compose compiler is switched on for the `editorTest` compilation only, because the whole-project plugin would stamp `$stable` into every shipped class.

The lead's #232 FYI offered the new edit-session tools. The spawn button already meets the one-button criterion, so I kept it. `editor.history` gained an optional `ids` key, which the panel's parser ignores (`ignoreUnknownKeys`).

Known limitations, stated rather than hidden:

- The `SceneView` fills the space behind the docked panels, and ComposeGL's panels are translucent. So the letterboxed world's left and right edges, including the part of the game HUD under the Create panel, show faintly through the panels. You can see it in every window shot below. Reserving dock space so the viewport sits between the panels is layout work I left for the gizmo and tab epic (#231).
- In `GlWorldViewTest`'s window picture, the test's own backend world shows as a one-pixel column at the window's left edge, under the test's grey layer. That comes from the test setup, not the editor, and the test's assertions sample well inside the panel.
- `/state`'s `commandResults` lagged the window by a few seconds while paused. The panel updates straight from the bridge. A `/state` read a few seconds later had every answer: `/tmp/claude-1000/-srv-ssd1-workspace-Udea/1ad8c5e6-2def-4055-91d2-72acdfe77daf/scratchpad/issue194/final/state-later.json`. I did not investigate further; it is not this branch's code.

## `sh gradlew build`

Baseline on `origin/kmp` `9d6629f` before any change (`/tmp/claude-1000/-srv-ssd1-workspace-Udea/1ad8c5e6-2def-4055-91d2-72acdfe77daf/scratchpad/issue194/baseline.log`):

```
BUILD SUCCESSFUL in 1m 30s
917 actionable tasks: 615 executed, 302 from cache
Configuration cache entry stored.
```

The final run on `a654700`, `sh gradlew build --continue` (`/tmp/claude-1000/-srv-ssd1-workspace-Udea/1ad8c5e6-2def-4055-91d2-72acdfe77daf/scratchpad/issue194/build-final.log`):

```
BUILD SUCCESSFUL in 1m 57s
928 actionable tasks: 174 executed, 2 from cache, 752 up-to-date
Configuration cache entry stored.
```

`928` against `917`: the new tasks are the new module and the `editor`/`editorTest` source sets.

Two earlier full runs on this branch were red, and both are resolved:

- On `99df57f` (`build-full.log`), `:udea-render:udeaVerifyHeadless`, `:moba:desktop:udeaVerifyKotlinPin` and `:udea-gradle:test` were red, all because of this branch: each enumerates modules or classpaths and did not yet know about the editor. `udeaVerifyKotlinPin` had four unclassified `editor*` classpaths. `WallClockBudgetCensusTest` had no census row for `GlWorldViewTest`'s frame deadline. `udeaVerifyHeadless` has its own copy of the GL-allowed list. All are fixed in `97cc0d0`.
- On `3c28248` (after merging #232), `udea-net`'s `UdpTwoProcessTest` failed with `rateLimited moved during a well-behaved session: {... rateLimited=1 ...}`. The load average was 25 at the time. This branch does not touch `udea-net`, and the test passed alone straight after: `sh gradlew :udea-net:jvmTest --rerun --tests '*UdpTwoProcessTest*'` gave `BUILD SUCCESSFUL`, 1 test, 0 failures (`/tmp/claude-1000/-srv-ssd1-workspace-Udea/1ad8c5e6-2def-4055-91d2-72acdfe77daf/scratchpad/issue194/udp-solo.log`). The final run above is green with it in.

`build-logic` is an included build, so the root `build` does not run its tests (CI runs them in their own job). `sh gradlew -p build-logic check` gave `BUILD SUCCESSFUL`, with 295 tests, 0 failed and 0 skipped in the JUnit XML. The count was read from the reports on `3c28248`; `build-logic` is unchanged between that merge and `a654700`.

### GL, for real

```
xvfb-run -a -s "-screen 0 1280x720x24" env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe sh gradlew udeaGlTest --rerun udeaAgentGlTest --rerun -Pudea.render.requireGl=true --continue
```

On `a654700` (`/tmp/claude-1000/-srv-ssd1-workspace-Udea/1ad8c5e6-2def-4055-91d2-72acdfe77daf/scratchpad/issue194/gl-final.log`):

```
BUILD SUCCESSFUL in 1m 1s
121 actionable tasks: 11 executed, 110 up-to-date
Configuration cache entry stored.
```

Read from the JUnit XML afterwards: `udeaGlTest` ran 16 tests in 15 classes, `udeaAgentGlTest` ran 2 tests in 2 classes, with 0 skipped and 0 failed. `GlWorldViewTest` is new in this branch.

## Images

All in `/srv/ssd1/workspace/Udea/build/debug-screenshots/`.

- `issue194-editor-opened-paused.png`: `runEditor` on `a654700` under Xvfb at 1280x720, as it opens. Create and History panels docked left and right, the world in the viewport, "Nothing to undo", status "Paused - tick 1". Proves the window, the docked panels, the Kool world in the `SceneView`, and that it starts paused.
- `issue194-editor-spawn-button.png`: after a real X click (XTest) on "Spawn skeleton beside the player". The skeleton stands right of the player, History lists `#1 editor.spawn #31`, and the tick is still 1. Proves the button calls a tool, the world changes, the history shows it, and nothing ticked.
- `issue194-editor-undo-button.png`: after a click on Undo. The skeleton is gone and the panel says "Nothing to undo". Proves the button's edit is undoable from the window.
- `issue194-editor-sequence.png`: the three above, tiled in order.
- `issue194-sceneview-world-shown.png`: `GlWorldViewTest`'s window. The test's red-over-green world, right way up, inside a 320x180 `SceneView` on a grey layer.
- `issue194-sceneview-world-reverted.png`: the same test with `drawInto` reverted. The `SceneView` is black. This is the "before".
- `issue194-sceneview-before-after.png`: those two side by side.
- `issue194-sceneview-capture-no-panels.png`: the same test's capture of the capturable pass. Only the red and green world; no grey panel. Proves panels never reach a screenshot.

## The issue, criterion by criterion

**1. `sh gradlew :moba:desktop:runEditor` opens the editor window with docked panels and the world drawn by Kool in a `SceneView`, paused. Screenshot under xvfb, with the GL tests run with `-Pudea.render.requireGl=true`.**

- The window: `issue194-editor-opened-paused.png`, from `runEditor -PdebugPort=7851` on `a654700` under `Xvfb :194` (`/tmp/claude-1000/-srv-ssd1-workspace-Udea/1ad8c5e6-2def-4055-91d2-72acdfe77daf/scratchpad/issue194/editor-start.sh`, `drive.sh`). The run's log line is `[moba.agent] listening on http://127.0.0.1:7851 in Windowed with 66 tools`.
- Paused: `/health` before and after the clicks (`/tmp/claude-1000/-srv-ssd1-workspace-Udea/1ad8c5e6-2def-4055-91d2-72acdfe77daf/scratchpad/issue194/final/health-0.json`, `health-1.json`):
  ```
  {"ok":true,"frame":1541,"tick":1,"paused":true,"renderMode":"Windowed","role":"standalone","sessionId":"s-8801","editor":true}
  {"ok":true,"frame":2263,"tick":1,"paused":true,"renderMode":"Windowed","role":"standalone","sessionId":"s-8801","editor":true}
  ```
  and `MobaEditorTest > the editor opens paused, and the frames it pumps do not tick the world`, which goes red if the pause is removed (mutation `no-pause` below).
- The world drawn by Kool in a `SceneView`: `GlWorldViewTest`, part of the evidence command, and its two pictures.
- The GL tests: the xvfb run above, 18 tests, none skipped.

**2. A build rule fails if `udea-editor` is on `:moba:desktop`'s release runtime classpath, proven red by adding it.**

`UDEA-MG-010`. The dependency added:

```diff
diff --git a/moba/desktop/build.gradle.kts b/moba/desktop/build.gradle.kts
index 2c1a288..643b560 100644
--- a/moba/desktop/build.gradle.kts
+++ b/moba/desktop/build.gradle.kts
@@ -62,6 +62,7 @@ dependencies {
     implementation(project(":udea-core"))
     implementation(project(":udea-render"))
     implementation(project(":udea-net"))
+    implementation(project(":udea-editor"))
 
     // The ability system. `:moba:game` takes it as `implementation`, so it is not on this
     // project's classpath transitively - and the shot mains and the HUD tests here name
```

`sh gradlew :moba:desktop:udeaVerifyModuleGraph` (`/tmp/claude-1000/-srv-ssd1-workspace-Udea/1ad8c5e6-2def-4055-91d2-72acdfe77daf/scratchpad/issue194/release-rule-red.log`, lines 40-46):

```
> udeaVerifyModuleGraph: 2 violations
  UDEA-MG-010 :moba:desktop compileClasspath -> :udea-editor
      no compile or runtime classpath resolves udea-editor; only a game's editor source set may
      resolution path: :moba:desktop -> :udea-editor
  UDEA-MG-010 :moba:desktop runtimeClasspath -> :udea-editor
      no compile or runtime classpath resolves udea-editor; only a game's editor source set may
      resolution path: :moba:desktop -> :udea-editor
```

Reverted afterwards; the final build's `udeaVerifyModuleGraph` is green. `ModuleGraphRulesTest` has the rule's unit tests for MG-010 and MG-011, and they run in the `build-logic` check above.

**3. One button in the window calls an `editor.*` tool, and the change shows in that author's undo history.**

- `MobaEditorTest > the spawn button adds a unit beside the player, paused, in the editor author's undo history`. It clicks the button in ComposeGL's `uiTest`, pumps the real `AgentGameLoop`, then calls `editor.history` as an outside agent would, under the session label `editor`. It asserts the edit is listed, the entity exists beside the player, the tick has not moved, and the panel shows it. It then clicks Undo and asserts the entity and the history entry are gone.
- Live, on `a654700`: the spawn and undo pictures above, and an agent's view over HTTP. Command 4 was the `/command?cmd=editor.history&session=editor` read made after the spawn click. From `/tmp/claude-1000/-srv-ssd1-workspace-Udea/1ad8c5e6-2def-4055-91d2-72acdfe77daf/scratchpad/issue194/final/state-after-undo.json`, the matching entries of `commandResults` re-printed by Python's `json.dumps` (the file itself is compact JSON):
  ```
  [{"id": 4, "ok": true, "result": {"author": "editor", "size": 1, "edits": [{"sequence": 1, "tool": "editor.spawn", "id": 31}]}}]
  ```
  and after the Undo click, from `state-later.json`:
  ```
  [{"id": 6, "ok": true, "result": {"undone": "editor.spawn", "id": 31, "overwrote": false}}, {"id": 8, "ok": true, "result": {"author": "editor", "size": 0, "edits": []}}]
  ```

## Mutations

Each row applies one mutation, runs `:udea-editor:test` and `:moba:desktop:editorTest`, lists the failing test cases from the JUnit XML, and restores the file with `git checkout`. The script is `/tmp/claude-1000/-srv-ssd1-workspace-Udea/1ad8c5e6-2def-4055-91d2-72acdfe77daf/scratchpad/issue194/mutate.py`, run on `3c28248`; none of these files changed between that merge and `a654700`. Each diff and failure list is spliced from `/tmp/claude-1000/-srv-ssd1-workspace-Udea/1ad8c5e6-2def-4055-91d2-72acdfe77daf/scratchpad/issue194/mutation-<name>.txt`.

### `author`

```diff
diff --git a/moba/desktop/src/editor/kotlin/dev/wildware/moba/editor/MobaEditor.kt b/moba/desktop/src/editor/kotlin/dev/wildware/moba/editor/MobaEditor.kt
index b368ea9..7ee16ea 100644
--- a/moba/desktop/src/editor/kotlin/dev/wildware/moba/editor/MobaEditor.kt
+++ b/moba/desktop/src/editor/kotlin/dev/wildware/moba/editor/MobaEditor.kt
@@ -85,7 +85,7 @@ public object MobaEditor {
     internal fun session(host: GameHost, session: MobaAgent.Session, viewport: SceneDrawScope.() -> Unit): EditorSession {
         host.time.pause()
         return EditorSession(
-            tools = EditorTools(session.wiring.bridge, session.wiring.sessions.intern(AUTHOR)),
+            tools = EditorTools(session.wiring.bridge, session.wiring.sessions.intern("somebody-else")),
             tick = { host.ctx.clock.tick },
             paused = { host.time.paused },
             spawn = spawnBeside(host, session.player),
```

Gradle exit 1. Failing:

```
FAILED MobaEditorTest > the spawn button adds a unit beside the player, paused, in the editor author's undo history(): org.opentest4j.AssertionFailedError: the edits filed under `editor` were [] ==> expected: <[editor.spawn]> but was: <[]>
```

### `spawn-unwired`

```diff
diff --git a/udea-editor/src/main/kotlin/dev/wildware/udea/editor/EditorSession.kt b/udea-editor/src/main/kotlin/dev/wildware/udea/editor/EditorSession.kt
index e806728..ebd4815 100644
--- a/udea-editor/src/main/kotlin/dev/wildware/udea/editor/EditorSession.kt
+++ b/udea-editor/src/main/kotlin/dev/wildware/udea/editor/EditorSession.kt
@@ -101,7 +101,7 @@ public class EditorSession(
 
     /** The Create panel's button. */
     internal fun spawn() {
-        edit(
+        if (false) edit(
             SPAWN,
             mapOf("blueprint" to spawn.blueprint.value, "x" to spawn.x.toString(), "y" to spawn.y.toString()),
         )
```

Gradle exit 1. Failing:

```
FAILED EditorSessionTest > a refused edit is shown, not swallowed(): java.util.NoSuchElementException: List is empty.
FAILED EditorSessionTest > the spawn button calls editor spawn at the configured point, filed under the editor's author(): java.util.NoSuchElementException: Collection contains no element matching the predicate.
FAILED EditorSessionTest > an edit's answer refreshes the history panel from editor history, for the editor's author(): java.util.NoSuchElementException: List is empty.
FAILED MobaEditorTest > the spawn button adds a unit beside the player, paused, in the editor author's undo history(): org.opentest4j.AssertionFailedError: the edits filed under `editor` were [] ==> expected: <[editor.spawn]> but was: <[]>
```

### `undo-unwired`

```diff
diff --git a/udea-editor/src/main/kotlin/dev/wildware/udea/editor/EditorSession.kt b/udea-editor/src/main/kotlin/dev/wildware/udea/editor/EditorSession.kt
index e806728..0413003 100644
--- a/udea-editor/src/main/kotlin/dev/wildware/udea/editor/EditorSession.kt
+++ b/udea-editor/src/main/kotlin/dev/wildware/udea/editor/EditorSession.kt
@@ -109,7 +109,7 @@ public class EditorSession(
 
     /** The History panel's Undo, and Edit > Undo. */
     internal fun undo() {
-        edit(UNDO, emptyMap())
+        if (false) edit(UNDO, emptyMap())
     }
 
     internal val spawnLabel: String get() = spawn.label
```

Gradle exit 1. Failing:

```
FAILED EditorSessionTest > the undo button and Ctrl+Z both call editor undo as the editor's author(): org.opentest4j.AssertionFailedError: expected: <[editor.undo, editor.undo]> but was: <[]>
FAILED MobaEditorTest > the spawn button adds a unit beside the player, paused, in the editor author's undo history(): org.opentest4j.AssertionFailedError: undo left NetId(#31@0) in the world ==> expected: <null> but was: <(19.16669, -27.917236)>
```

### `no-history-refresh`

```diff
diff --git a/udea-editor/src/main/kotlin/dev/wildware/udea/editor/EditorSession.kt b/udea-editor/src/main/kotlin/dev/wildware/udea/editor/EditorSession.kt
index e806728..4ec9a50 100644
--- a/udea-editor/src/main/kotlin/dev/wildware/udea/editor/EditorSession.kt
+++ b/udea-editor/src/main/kotlin/dev/wildware/udea/editor/EditorSession.kt
@@ -91,7 +91,6 @@ public class EditorSession(
         tools.frame()
         val completed = tools.completed
         if (completed != seenCompleted) {
-            if (completed != historyRead) historyStale = true
             seenCompleted = completed
         }
         if (historyStale && !historyPending) readHistory()
```

Gradle exit 1. Failing:

```
FAILED EditorSessionTest > an edit another caller files under the editor's author shows in the panel too(): java.util.NoSuchElementException: List is empty.
FAILED MobaEditorTest > the spawn button adds a unit beside the player, paused, in the editor author's undo history(): org.opentest4j.AssertionFailedError: the history panel shows "Nothing to undo"
```

### `no-offset`

```diff
diff --git a/moba/desktop/src/editor/kotlin/dev/wildware/moba/editor/MobaEditor.kt b/moba/desktop/src/editor/kotlin/dev/wildware/moba/editor/MobaEditor.kt
index b368ea9..a3b3b31 100644
--- a/moba/desktop/src/editor/kotlin/dev/wildware/moba/editor/MobaEditor.kt
+++ b/moba/desktop/src/editor/kotlin/dev/wildware/moba/editor/MobaEditor.kt
@@ -99,6 +99,6 @@ public object MobaEditor {
             "the player $player is not in the world the editor opened on"
         }
         val position = with(host.world) { entity[Position] }
-        return EditorSpawn("Spawn skeleton beside the player", BlueprintId("skeleton"), position.x + SPAWN_OFFSET_X, position.y)
+        return EditorSpawn("Spawn skeleton beside the player", BlueprintId("skeleton"), position.x, position.y)
     }
 }
```

Gradle exit 1. Failing:

```
FAILED MobaEditorTest > the spawn button adds a unit beside the player, paused, in the editor author's undo history(): org.opentest4j.AssertionFailedError: the skeleton is not beside the player ==> expected: <19.16669> but was: <-28.83331>
```

### `no-pause`

```diff
diff --git a/moba/desktop/src/editor/kotlin/dev/wildware/moba/editor/MobaEditor.kt b/moba/desktop/src/editor/kotlin/dev/wildware/moba/editor/MobaEditor.kt
index b368ea9..23de70a 100644
--- a/moba/desktop/src/editor/kotlin/dev/wildware/moba/editor/MobaEditor.kt
+++ b/moba/desktop/src/editor/kotlin/dev/wildware/moba/editor/MobaEditor.kt
@@ -83,7 +83,6 @@ public object MobaEditor {
      * test can press its buttons with no GL context. Pauses [host] first: the editor starts paused.
      */
     internal fun session(host: GameHost, session: MobaAgent.Session, viewport: SceneDrawScope.() -> Unit): EditorSession {
-        host.time.pause()
         return EditorSession(
             tools = EditorTools(session.wiring.bridge, session.wiring.sessions.intern(AUTHOR)),
             tick = { host.ctx.clock.tick },
```

Gradle exit 1. Failing:

```
FAILED MobaEditorTest > the spawn button adds a unit beside the player, paused, in the editor author's undo history(): org.opentest4j.AssertionFailedError: the skeleton is not beside the player ==> expected: <19.16669> but was: <19.751093>
FAILED MobaEditorTest > the editor opens paused, and the frames it pumps do not tick the world(): org.opentest4j.AssertionFailedError: the editor opened on a running world
```

### `session-dropped`

```diff
diff --git a/udea-editor/src/main/kotlin/dev/wildware/udea/editor/EditorTools.kt b/udea-editor/src/main/kotlin/dev/wildware/udea/editor/EditorTools.kt
index 8b74b86..7f5f891 100644
--- a/udea-editor/src/main/kotlin/dev/wildware/udea/editor/EditorTools.kt
+++ b/udea-editor/src/main/kotlin/dev/wildware/udea/editor/EditorTools.kt
@@ -53,7 +53,7 @@ public class EditorTools(
      * @return the command's id, which is what [AgentBridge.completedCommandId] reaches when it has run.
      */
     internal fun call(tool: String, args: Map<String, String> = emptyMap(), onAnswer: (AgentResult) -> Unit): Long {
-        val command = AgentCommand(tool, args, session = author)
+        val command = AgentCommand(tool, args)
         lastSent = command.id
         when (val submitted = bridge.submit(command)) {
             is AgentSubmission.Accepted -> waiting[submitted.commandId] = onAnswer
```

Gradle exit 1. Failing:

```
FAILED EditorSessionTest > the undo button and Ctrl+Z both call editor undo as the editor's author(): org.opentest4j.AssertionFailedError: Expected value to be true.
FAILED EditorSessionTest > the spawn button calls editor spawn at the configured point, filed under the editor's author(): org.opentest4j.AssertionFailedError: the edit must land in the editor's own undo history ==> expected: <AgentSessionId(1)> but was: <AgentSessionId(0)>
FAILED EditorSessionTest > an edit's answer refreshes the history panel from editor history, for the editor's author(): org.opentest4j.AssertionFailedError: a history read under any other author lists someone else's edits ==> expected: <AgentSessionId(1)> but was: <AgentSessionId(0)>
FAILED MobaEditorTest > the spawn button adds a unit beside the player, paused, in the editor author's undo history(): org.opentest4j.AssertionFailedError: the edits filed under `editor` were [] ==> expected: <[editor.spawn]> but was: <[]>
```

The GL half's mutation is the evidence command's revert above: `drawInto` drawing nothing makes `GlWorldViewTest` fail with `the top of the panel should show the world's top band, was #000000`.

## Regenerated files

None. No replicated component was added or removed, so `net-protocol.lock` and `expected-generated-hashes.txt` are unchanged.
