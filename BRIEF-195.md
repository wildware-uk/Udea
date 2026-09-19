cafa6cb

# BRIEF-195: save editor changes into `.udea.kts` by replacing only the exact value

Branch `issue-195-save-to-kts`, cut from `origin/kmp` at `5821d25`. `6a77324` is the one code commit. `793a969` merges `origin/kmp` at `48554d4` (#188, the HUD on ComposeGL), and `cafa6cb` merges it again at `1e6c08b` (`udea-physics2d`), each as the lead asked. Neither merge touched a file this branch changes. The commit that adds this file sits on top and changes nothing else. The full build, the evidence runs and the GL run below were made on the merged tree `cafa6cb`. The mutation table and the live drive were made before the merge, on `6a77324`. None of the files those runs mutated or drove changed in the merge.

Scratch artefacts are under `/tmp/claude-1000/-srv-ssd1-workspace-Udea/1ad8c5e6-2def-4055-91d2-72acdfe77daf/scratchpad/issue195/` (called `S/` below).

## Evidence command

```
sh gradlew :moba:desktop:editorTest --rerun --tests 'dev.wildware.moba.editor.MobaEditorSaveTest'
```

This test drives the real editor window with keys and clicks. It uses the real `assets.*` tools and the real asset daemon, over a copy of `moba/game/assets` under `moba/desktop/build/tmp/editor-save`. It covers all three acceptance criteria. For the first, it runs `git diff --no-index` on the saved file and requires exactly `-    health = 100F,` / `+    health = 120F,`. It then saves 100 back and requires the file to be byte-identical to the original.

**It goes red when Save is reverted.** `S/evidence.py` runs the command, applies the revert below, runs it again, and restores the tree with `git checkout`. The revert, as the script saved it:

source: `S/evidence-merged-revert.diff`
```diff
diff --git a/udea-editor/src/main/kotlin/dev/wildware/udea/editor/AssetPanel.kt b/udea-editor/src/main/kotlin/dev/wildware/udea/editor/AssetPanel.kt
index 7a47c82..05ad599 100644
--- a/udea-editor/src/main/kotlin/dev/wildware/udea/editor/AssetPanel.kt
+++ b/udea-editor/src/main/kotlin/dev/wildware/udea/editor/AssetPanel.kt
@@ -48,7 +48,7 @@ internal fun AssetPanel(assets: EditorAssets) {
                 }
             }
         }
-        Button("Save  (Ctrl+S)", onClick = { assets.save() }, modifier = Modifier.fillMaxWidth().testTag(EditorAssetTags.SAVE))
+        Button("Save  (Ctrl+S)", onClick = { }, modifier = Modifier.fillMaxWidth().testTag(EditorAssetTags.SAVE))
         Row(Modifier.fillMaxWidth().padding(top = GAP)) {
             TextField(
                 assets.newName,
diff --git a/udea-editor/src/main/kotlin/dev/wildware/udea/editor/EditorWindow.kt b/udea-editor/src/main/kotlin/dev/wildware/udea/editor/EditorWindow.kt
index 08399b4..b081f1b 100644
--- a/udea-editor/src/main/kotlin/dev/wildware/udea/editor/EditorWindow.kt
+++ b/udea-editor/src/main/kotlin/dev/wildware/udea/editor/EditorWindow.kt
@@ -41,7 +41,7 @@ internal fun EditorWindow(session: EditorSession) {
         Column(Modifier.fillMaxSize().background(Background)) {
             MenuBar(Modifier.fillMaxWidth()) {
                 Menu("&File") {
-                    Item("&Save", shortcut = KeyShortcut(Key.S, Modifiers(Modifiers.CONTROL))) { session.assets.save() }
+                    Item("&Save", shortcut = KeyShortcut(Key.S, Modifiers(Modifiers.CONTROL))) { }
                 }
                 Menu("&Edit") {
                     Item("&Undo", shortcut = KeyShortcut(Key.Z, Modifiers(Modifiers.CONTROL))) { session.undo() }
```

Both runs on the merged tree, as the script read them back from the JUnit XML (the script deletes the old XML before each run, so a run that never reaches the tests cannot pass as the previous one):

source: `S/evidence-merged-green.txt`
```
# gradle exit 0
MobaEditorSaveTest > save as new writes a generated asset the asset compiler accepts with no diagnostics(): PASSED
MobaEditorSaveTest > a value set by the file's own constant is read-only, with the constant and its line(): PASSED
MobaEditorSaveTest > changing one value in a commented script and pressing Ctrl+S changes exactly that value(): PASSED
```

source: `S/evidence-merged-reverted.txt`
```
# gradle exit 1
MobaEditorSaveTest > save as new writes a generated asset the asset compiler accepts with no diagnostics(): PASSED
MobaEditorSaveTest > a value set by the file's own constant is read-only, with the constant and its line(): PASSED
MobaEditorSaveTest > changing one value in a commented script and pressing Ctrl+S changes exactly that value(): FAILED org.opentest4j.AssertionFailedError: Editing character/soldier. Change a value, then Save (Ctrl+S).
```

The other two tests in the class go red under other mutations; see the mutation table.

## Summary

The editor has a new **Asset** panel. You type an asset id and press Open, and it lists every value the asset's call writes. A plain literal (a number, a string, a boolean, or `reference("...")`) gets an edit box. Anything else is shown as read-only, with the reason and the line, for example `scale  read-only: set by `val soldierScale` on line 8`. **File > Save** (Ctrl+S, or the panel's Save button) writes each changed value into its `.udea.kts`. It replaces only that value's exact text, so comments, blank lines and spacing stay byte-identical. **Save as new asset** copies the open asset under a new name into a new file generated with KotlinPoet, and then saves any changed values into the copy.

How it is built:

- **`udea-assets-compiler`, new package `edit`.** `AssetSources` finds an asset's declaring call using pass 1's own scanner, so it uses the same ids as the build and the daemon. It then reads each argument with pass 1's PSI parser: its exact span, whether it is a plain literal and of which type, or why it is read-only and on which line. `AssetSource.set` parses the new value as the type the file already has there (`1.58F` stays a Float), renders it with KotlinPoet (`%S`/`%L`), and splices it over that span. A file with `\r\n` line endings keeps them: `RawOffsets` maps the parser's `\n` offsets back to the raw text. `AssetSources.create` writes the new script with `FileSpec.scriptBuilder`. A value set by a `val` that holds a literal is written as that literal, because the new file does not have the constant. Any other computed value refuses the copy, naming the field, the reason and the line. `UdeaDeclarationScanner` gains `scanSource` (internal), the text-taking half of `scanFile`. `AssetDaemon` exposes `sources` over its own scanner.
- **`udea-agent`, three tools in `assets.*`.** `assets.fields` lists the values. It is paged so that every answer fits inside `AgentBridge.MAX_DELIVERABLE_RESULT_CHARS` (see Surprise below). `assets.set` saves one value through the existing `edit` path: write, validate, restore byte for byte on any diagnostic, then hot-reload. It refuses a computed value with `read_only_field`, the reason and the line. `assets.create` writes the KotlinPoet file, validates it with the daemon, deletes it on any diagnostic, and reports the reload outcome (always "next launch", because adding an asset is a shape change).
- **`udea-editor`: new files only, plus a minimal hook.** New: `EditorAssets.kt` (the panel's state; every action is a tool call through `EditorTools`) and `AssetPanel.kt` (the panel and `EditorAssetTags`). Hooks: `EditorSession` gains one `internal val assets`. `EditorWindow` gains the File > Save item, one `DebugWindow("Asset")` and its dock line. dev-196's toolbar should merge beside these without conflict.
- **`:moba:desktop`**: `MobaEditorSaveTest` (in `editorTest`, which is on `check`). The `editorTest` task now passes the game's asset tree (declared as an input) and the daemon's script classpath, which is the same one `run` and `runEditor` pass.

**Where the parser runs:** in the game process that holds the warm asset daemon, which is `:moba:desktop:run` or `:moba:desktop:runEditor`. `udea-assets-compiler` is only on `:moba:desktop`'s `agent` and `editor` source sets. `udea-agent` takes it `compileOnly`. `udea-editor` names none of its types: it only sends tool calls. So `UDEA-MG-005`, `UDEA-MG-010`, `UDEA-MG-011` and the release rules are unaffected, and `udeaVerifyModuleGraph` is green.

Decisions, each commented on #195 with how to reverse it:

1. **The patcher is in `udea-assets-compiler` and reached through `assets.*` tools**, not in `:moba:desktop`'s `editor` source set as the issue text says. This lets an agent save exactly what a person saves (#190: the editor is a screen over the tool surface), and it works for any game. The issue's reason for its placement, keeping the compiler off shipped classpaths, still holds. I used `assets.*` rather than `editor.*`: `editor.*` is `commonMain` and registered with `-Peditor=true`, while the compiler is JVM-only and `assets.*` is already its `jvmMain` home. (https://github.com/wildware-uk/Udea/issues/195#issuecomment-5739558555)
2. **A save hot-reloads.** #91 has landed (it is closed, and `assets.patch` already hot-reloads), so the issue's sentence "saving does not change the running game until #91" no longer describes the tree. The panel says plainly which outcome happened: "The running game has it now.", or "It applies on the next launch." (https://github.com/wildware-uk/Udea/issues/195#issuecomment-5739558843)
3. **What is editable**: the named arguments of the declaring call whose value is a plain literal. `name` is read-only because it is the id. Entries inside a `mapOf(...)`/`listOf(...)` or a builder block are read-only in this ticket. **A new asset** is a copy of the open one under a new name. A blank "new asset of kind X" form was rejected: the file is the only place a field's type is written down, so copying an existing asset is the one way to guarantee the new file compiles. (https://github.com/wildware-uk/Udea/issues/195#issuecomment-5739558908)

**Surprise (posted at the time):** the first end-to-end run failed. The soldier's `assets.fields` answer was 2198 characters, and the bridge replaces any answer over `MAX_DELIVERABLE_RESULT_CHARS` (1236) with a file handle (`resultTooLarge`). The editor reads answers in-process and cannot follow that handle. The answer is now paged by size: the tool keeps adding fields while the rendered page fits, always sends at least one field, and the panel reads every page. `AssetsSaveToolsTest` pins each page against the bridge's constant. Separately, the same live run showed `assets.get` on `character/soldier` already answering with `resultTooLarge` (1254 characters). That behaviour predates this branch and I did not change it.

Known limits, stated rather than hidden:

- The Asset panel docks as a third column on the right, so the viewport is narrower than in #194's shots. The dividers are resizable. I tried docking it under History, but that pane is too short for the soldier's ten fields. Layout belongs to epic #231.
- `name`, positional arguments, and values inside maps, lists and blocks are read-only (see decision 3).
- `/state` lagged while the editor was paused. The #194 brief notes the same thing. I read the refusal below after polling `/state` a few times, and did not investigate further.

### Mutation table

`S/mutate.py` applies each mutation, runs the four suites, and restores the file with `git checkout`:
`:udea-assets-compiler:test --tests 'dev.wildware.udea.assets.compiler.edit.*' :udea-agent:udeaAssetTools :udea-editor:test :moba:desktop:editorTest --continue`

Each diff below is the literal `git diff` the script saved (`S/mutations/<name>.diff`). The failing tests are read back from the JUnit XML (`S/mutations/<name>.txt`, which also lists every PASSED case). All six runs exited 1. A suite marked UP-TO-DATE or FROM-CACHE in a row did not see the mutated code and says nothing about it.

**naive-find-replace**: replace the first occurrence of the old text, the way `assets.patch` does. `MobaEditorSaveTest` stays **green** under this one: in the real `soldier.udea.kts` the first `100F` happens to be `health`'s. The unit fixtures are what catch it.

source: `S/mutations/naive-find-replace.diff`
```diff
diff --git a/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/edit/AssetSource.kt b/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/edit/AssetSource.kt
index 7c55c42..19937b7 100644
--- a/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/edit/AssetSource.kt
+++ b/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/edit/AssetSource.kt
@@ -120,7 +120,7 @@ public class AssetSource internal constructor(
         }
         val value = LiteralValue.parse(current.type, input) ?: return SetResult.NotParseable(field, current.type, input)
         // A splice of the file, not code assembled from pieces: the one new piece is KotlinPoet's.
-        val replaced = StringBuilder(text).replace(site.start, site.end, value.replacement().toString())
+        val replaced = StringBuilder(text.replaceFirst(site.text, value.expression().toString()))
         return SetResult.Changed(replaced.toString(), site, value)
     }
 
```

source: `S/mutations/naive-find-replace.txt` (exit line and every FAILED line; the file also lists each PASSED case)
```
# gradle exit 1
AssetSourceSetTest > a file with Windows line endings keeps every one of them(): FAILED
AssetSourceSetTest > changing one value rewrites exactly that value's text and nothing else(): FAILED
AssetsSaveToolsTest > assets set replaces exactly one value, keeps every comment, and hot-reloads the game(): FAILED
```


**constant-edited-in-place**: overwrite a `val`-bound value where it is used, instead of refusing it.

source: `S/mutations/constant-edited-in-place.diff`
```diff
diff --git a/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/edit/AssetSource.kt b/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/edit/AssetSource.kt
index 7c55c42..8e86769 100644
--- a/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/edit/AssetSource.kt
+++ b/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/edit/AssetSource.kt
@@ -115,7 +115,7 @@ public class AssetSource internal constructor(
     public fun set(field: String, input: String): SetResult {
         val site = fields.firstOrNull { it.name == field } ?: return SetResult.NoSuchField(field, fields.map { it.name })
         val current = when (val editability = site.editability) {
-            is Editability.ReadOnly -> return SetResult.ReadOnly(field, editability.reason)
+            is Editability.ReadOnly -> editability.constant ?: return SetResult.ReadOnly(field, editability.reason)
             is Editability.Editable -> editability.value
         }
         val value = LiteralValue.parse(current.type, input) ?: return SetResult.NotParseable(field, current.type, input)
```

source: `S/mutations/constant-edited-in-place.txt` (exit line and every FAILED line; the file also lists each PASSED case)
```
# gradle exit 1
AssetSourceSetTest > a value set by a named constant is refused with the constant and its line(): FAILED
AssetsSaveToolsTest > assets set refuses a computed value with the reason and the line, and leaves the file alone(): FAILED
MobaEditorSaveTest > a value set by the file's own constant is read-only, with the constant and its line(): FAILED
```


**create-copies-literals-only**: a new asset refuses a `val`-bound value instead of writing its literal.

source: `S/mutations/create-copies-literals-only.diff`
```diff
diff --git a/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/edit/AssetSources.kt b/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/edit/AssetSources.kt
index b379f07..1b806a1 100644
--- a/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/edit/AssetSources.kt
+++ b/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/edit/AssetSources.kt
@@ -144,7 +144,7 @@ public class AssetSources(
                 field.name == NAME_ARGUMENT -> LiteralValue.StringValue(name)
                 else -> when (val editability = field.editability) {
                     is Editability.Editable -> editability.value
-                    is Editability.ReadOnly -> editability.constant ?: return CreateResult.Refused(
+                    is Editability.ReadOnly -> return CreateResult.Refused(
                         "`${field.name}` is ${editability.reason.message}, and a new asset copies plain values only",
                     )
                 }
```

source: `S/mutations/create-copies-literals-only.txt` (exit line and every FAILED line; the file also lists each PASSED case)
```
# gradle exit 1
NewAssetScriptTest > a new asset copied from an existing one passes the asset compiler with no diagnostics(): FAILED
NewAssetScriptTest > the new file is the template's call with its own name and plain literals, and names where it came from(): FAILED
AssetsSaveToolsTest > assets create writes a generated asset that validates, and its values can then be set(): FAILED
MobaEditorSaveTest > save as new writes a generated asset the asset compiler accepts with no diagnostics(): FAILED
```


**crlf-offsets-ignored**: splice at the parser's `\n` offsets in a `\r\n` file. Only the CRLF test catches it, because every `.udea.kts` in `moba/game/assets` is LF today.

source: `S/mutations/crlf-offsets-ignored.diff`
```diff
diff --git a/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/edit/AssetSources.kt b/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/edit/AssetSources.kt
index b379f07..cba85fa 100644
--- a/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/edit/AssetSources.kt
+++ b/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/edit/AssetSources.kt
@@ -262,7 +262,7 @@ public class AssetSources(
             offsets.toIntArray()
         }
 
-        fun of(normalized: Int): Int = map?.get(normalized) ?: normalized
+        fun of(normalized: Int): Int = normalized
     }
 
     private companion object {
```

source: `S/mutations/crlf-offsets-ignored.txt` (exit line and every FAILED line; the file also lists each PASSED case)
```
# gradle exit 1
AssetSourceSetTest > a file with Windows line endings keeps every one of them(): FAILED
```


**fields-not-paged**: one answer holding every field, the shape before the Surprise above. `MobaEditorSaveTest` stays green: once read-only fields stopped carrying their `text`, the soldier's whole list fits in one answer.

source: `S/mutations/fields-not-paged.diff`
```diff
diff --git a/udea-agent/src/jvmMain/kotlin/dev/wildware/udea/agent/assets/AssetsToolset.kt b/udea-agent/src/jvmMain/kotlin/dev/wildware/udea/agent/assets/AssetsToolset.kt
index e18aa4c..ab2889a 100644
--- a/udea-agent/src/jvmMain/kotlin/dev/wildware/udea/agent/assets/AssetsToolset.kt
+++ b/udea-agent/src/jvmMain/kotlin/dev/wildware/udea/agent/assets/AssetsToolset.kt
@@ -269,7 +269,7 @@ public class AssetsToolset(
         // over `MAX_DELIVERABLE_RESULT_CHARS` reaches its caller as a handle to a file, which the
         // editor's panel - reading answers in-process - cannot follow. One field longer than that
         // on its own still goes out, as a handle: refusing it would hide the field altogether.
-        var end = start + 1
+        var end = source.fields.size
         var page = fieldsPage(source, start, end.coerceAtMost(source.fields.size))
         while (end < source.fields.size) {
             val wider = fieldsPage(source, start, end + 1)
```

source: `S/mutations/fields-not-paged.txt` (exit line and every FAILED line; the file also lists each PASSED case)
```
# gradle exit 1
AssetsSaveToolsTest > assets fields pages an asset too wide for one answer, and every page reaches the caller inline(): FAILED
```


**save-menu-unwired**: File > Save (Ctrl+S) does nothing.

source: `S/mutations/save-menu-unwired.diff`
```diff
diff --git a/udea-editor/src/main/kotlin/dev/wildware/udea/editor/EditorWindow.kt b/udea-editor/src/main/kotlin/dev/wildware/udea/editor/EditorWindow.kt
index 08399b4..b081f1b 100644
--- a/udea-editor/src/main/kotlin/dev/wildware/udea/editor/EditorWindow.kt
+++ b/udea-editor/src/main/kotlin/dev/wildware/udea/editor/EditorWindow.kt
@@ -41,7 +41,7 @@ internal fun EditorWindow(session: EditorSession) {
         Column(Modifier.fillMaxSize().background(Background)) {
             MenuBar(Modifier.fillMaxWidth()) {
                 Menu("&File") {
-                    Item("&Save", shortcut = KeyShortcut(Key.S, Modifiers(Modifiers.CONTROL))) { session.assets.save() }
+                    Item("&Save", shortcut = KeyShortcut(Key.S, Modifiers(Modifiers.CONTROL))) { }
                 }
                 Menu("&Edit") {
                     Item("&Undo", shortcut = KeyShortcut(Key.Z, Modifiers(Modifiers.CONTROL))) { session.undo() }
```

source: `S/mutations/save-menu-unwired.txt` (exit line and every FAILED line; the file also lists each PASSED case)
```
# gradle exit 1
## :udea-editor:test FAILED
EditorAssetsTest > Ctrl+S saves each changed value with assets set, and only the changed ones(): FAILED
EditorAssetsTest > saving with nothing changed sends nothing and says so(): FAILED
MobaEditorSaveTest > changing one value in a commented script and pressing Ctrl+S changes exactly that value(): FAILED
```


## `sh gradlew build`

Baseline: the lead's statement that `origin/kmp` `5821d25` is fully green at 928 tasks. I did not re-run that baseline, and did not run the merged `origin/kmp` `1e6c08b` on its own.

`ANDROID_HOME=/home/shaun/Android/Sdk JAVA_HOME=/home/shaun/.sdkman/candidates/java/21.0.11-tem sh gradlew build --continue` on the merged tree (`S/build-merged.log`):

source: `S/build-merged.log` (its last 3 lines)
```
BUILD SUCCESSFUL in 2m 17s
958 actionable tasks: 600 executed, 280 from cache, 78 up-to-date
Configuration cache entry stored.
```

No task is red. Before the merges, on `6a77324`'s tree, the build ran 928 tasks (`S/build-1.log`), the same count as the base `5821d25`. On the merged tree it runs 958. The two merges brought in the new `udea-physics2d` project, and I did not count which of the 30 tasks are its. This branch registers no new task: its one build-script change configures the existing `editorTest`, and its new tests live in existing test tasks. dev-196 was building in its own worktree at the same time, and nothing failed, so there was nothing to re-run alone.

### GL, for real

The ticket adds a panel to the editor window. It does not touch `udea-render` or `udea-agent-host`, but the window is GL, so I ran the GL tests anyway:

```
xvfb-run -a -s "-screen 0 1280x720x24" env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe sh gradlew udeaGlTest --rerun udeaAgentGlTest --rerun udeaVerifyModuleGraph udeaVerifyAgentsMd -Pudea.render.requireGl=true --continue
```

On the merged tree (`S/gl-merged.log`):

source: `S/gl-merged.log` (its last 3 lines)
```
BUILD SUCCESSFUL in 2m 7s
185 actionable tasks: 15 executed, 170 up-to-date
Configuration cache entry stored.
```

Read from the fresh JUnit XML afterwards: `udeaGlTest` 17 tests (16 before the merge; upstream's `GlCapturedUiTest` is the new one), `udeaAgentGlTest` 2, with 0 skipped, 0 failures and 0 errors.

## Images

All in `/srv/ssd1/workspace/Udea/build/debug-screenshots/`. Each is from a live `:moba:desktop:runEditor -PdebugPort=7845` on Xvfb `:195` at 1280x720 (`S/editor-start.sh`), driven by XTest clicks and keys (`S/xinput.py`), on the real `moba/game/assets`. The tree was restored afterwards and `git status` was clean.

- `issue195-asset-panel-soldier-open.png`: `character/soldier` opened. `health` and `spriteAnimationSet` have edit boxes. `name`, the three `mapOf(...)` fields and the two blocks are read-only, each with its reason and line. This proves the panel lists editable values and read-only ones.
- `issue195-health-saved-ctrl-s.png`: after typing 120 and pressing Ctrl+S. The message says "Saved `health` = 120 into moba/game/assets/character/soldier.udea.kts. The running game has it now." This proves AC1's save, through the real keyboard shortcut.
- `issue195-readonly-set-by-val-line8.png`: `character/soldier_idle_sheet` opened. It shows `scale  read-only: set by `val soldierScale` on line 8`, the exact example from the issue. This proves AC2 in the window.
- `issue195-saved-as-new-asset.png`: after Save as new asset with the name `soldier_idle_copy`. The panel has opened the copy, and its `scale` is now an editable `1.58`. This proves AC3's creation.
- `issue195-sequence.png`: the four images above, tiled in order.

## The issue, criterion by criterion

**1. Changing one value in a commented `.udea.kts` file and saving produces a `git diff` of exactly that one value.**

- Live: after the Ctrl+S in `issue195-health-saved-ctrl-s.png`, `git diff moba/game/assets` in the worktree (`S/live/git-diff-after-save.txt`):

source: `S/live/git-diff-after-save.txt`
```
diff --git a/moba/game/assets/character/soldier.udea.kts b/moba/game/assets/character/soldier.udea.kts
index b55bdd2..5857bea 100644
--- a/moba/game/assets/character/soldier.udea.kts
+++ b/moba/game/assets/character/soldier.udea.kts
@@ -114,7 +114,7 @@ spriteSheet(
 
 character(
     name = "soldier",
-    health = 100F,
+    health = 120F,
     spriteAnimationSet = reference("character/soldier_animation_set"),
     animationMap = mapOf(
         "idle" to reference("character/soldier_idle"),
```

- Tests: `MobaEditorSaveTest > changing one value ...` (a real `git diff --no-index`, then a round trip back to byte-identical). `AssetSourceSetTest` covers comments, a block comment, a trailing comment on the value's own line, odd spacing, the same value text elsewhere in the file, and CRLF. Each of those tests compares the whole file. `AssetsSaveToolsTest > assets set replaces exactly one value ...` also checks that the running game's registry has the new value after a tick.

**2. A computed field is refused with the reason and the line number.**

- Window: `issue195-readonly-set-by-val-line8.png`.
- Tool: an agent's `assets.set` on that field over HTTP during the live run, read back from `/state` (`S/live/state-refused.json`):

excerpt: `S/live/state-refused.json` (one entry of `commandResults`, cut from the single-line file)
```
{"id":11,"ok":false,"error":{"kind":"read_only_field","message":"`scale` is read-only: set by `val soldierScale` on line 8. Change it there, in moba/game/assets/character/soldier.udea.kts."}}
```

- Tests: `MobaEditorSaveTest > a value set by the file's own constant ...`, `AssetSourceSetTest > a value set by a named constant ...`, `AssetSourceSetTest > a value built by an expression ...` (`built by `mapOf(...)` on line 20`, `computed by `2F * 1.5F` on line 20`), and `AssetsSaveToolsTest > assets set refuses a computed value ...`.

**3. A new asset saved from the editor passes the asset compiler with no diagnostics.**

- Live: the file Save as new wrote (`S/live/soldier_idle_copy.udea.kts`):

source: `S/live/soldier_idle_copy.udea.kts`
```
// Created by the Udea editor from `character/soldier_idle_sheet`.
spriteSheet(
    name = "soldier_idle_copy",
    spritePath = "sprites/soldier/Soldier-Idle.png",
    rows = 1,
    columns = 6,
    scale = 1.58F,
)
```

  With that file in the real tree, the build's own validator, `sh gradlew :moba:game:udeaValidateAssets --rerun` (`S/live/validate-with-new-asset.log`, with `diagnostics.json` saved as `S/live/diagnostics-with-new-asset.json`, `"diagnostics": []`), reported:

source: `S/live/validate-with-new-asset.log`
```
[udeaValidateAssets] 161 asset(s), 0 diagnostic(s)
```

  Control, to show that check can go red: I edited the new file's `scale = 1.58F` back to `scale = soldierScale` (the unfolded copy) and re-ran it (`S/live/validate-control-unfolded-constant.log`):

source: `S/live/validate-control-unfolded-constant.log`
```
[udeaValidateAssets] 160 asset(s), 1 diagnostic(s)
[udeaValidateAssets] error UDEA0021 moba/game/assets/character/soldier_idle_copy.udea.kts:7:13 Unresolved reference 'soldierScale'.
```

- Tests: `NewAssetScriptTest > a new asset copied from an existing one passes the asset compiler with no diagnostics` runs `AssetPipeline.compileAndValidate`: pass 1 with the loop scan, the script compile with the K2 loop checker on the classpath, and every validator. It requires `emptyList()` of diagnostics and the copy's values equal to the template's. Also `MobaEditorSaveTest > save as new ...` (the daemon's `assets.validate` answers `"ok":true` with `"diagnostics":[]`) and `AssetsSaveToolsTest > assets create ...`.

**Design points from the issue body:**

- The new text goes exactly at the pass-1 span: AC1 above.
- New assets are written with KotlinPoet (`AssetSources.create`, `FileSpec.scriptBuilder`, `%N`/`%S`/`%L`). Values spliced into an existing file are rendered by KotlinPoet too.
- The editor says plainly whether the running game has the change: decision 2 and `EditorAssetsTest > a save the game took says so, and one it could not take says it applies on the next launch`.

## Regenerated files

None. No replicated component was added or removed, and neither `net-protocol.lock` nor `expected-generated-hashes.txt` moved.
