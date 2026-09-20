241c4de

# BRIEF-193: `editor.*` agent tools, one undo history per author, on the KMP tree

Branch `issue-193-editor-tools-kmp`, off `origin/kmp` at `7073adc`. One commit, `241c4de`.
`origin/kmp` has since moved to `407acef` (#217); `git merge-tree --write-tree HEAD origin/kmp`
merges with no conflict. This file is uncommitted, as asked.

Artefacts behind every block below are on disk in the worktree under `build/issue193/`
(gitignored): `build1-193.log`, `count-193.txt`, `gates-193.log`, `mutations/` (one `.md` and one
`.log` per run, written by `mutate.py`), `live/` (`/tools`, `/health`, `transcript.jsonl`,
`transcript-normal.jsonl`), `live-editor-193.log`, `live-normal-193.log`, `drive.py`. This file
is assembled by `brief.py`, which splices those files rather than retyping them.

## 1. Evidence command

```
ANDROID_HOME=$HOME/Android/Sdk JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem sh gradlew --continue :udea-agent:jvmTest --tests 'dev.wildware.udea.agent.tools.Editor*' :udea-agent:wasmJsNodeTest --tests 'dev.wildware.udea.agent.tools.EditorRoundTripTest' :udea-core:jvmTest --tests 'dev.wildware.udea.core.identity.NetIdReservationTest' :udea-agent-host:test --tests 'dev.wildware.udea.agent.host.AgentHostContractTest'
```

On `origin/kmp` it does not compile: `EditorToolset`, `NetIdIndex.detach` and `EditorMode` do not
exist there. That is not a proof by itself, so section 8 takes the feature back one rule at a time:
ten runs of exactly this command (as `./gradlew -p <worktree>`, from `mutate.py`), one unmutated
control that is green, and nine mutations that each go red on test assertions, with **zero compile
errors in every run**. Each carries its literal `git diff` and the failing test names read from the
JUnit XML.

The live `/tools` + `/health` transcript (section 5, criterion 3) is the evidence for the one
criterion no unit test holds: that a normal `moba` run does not list the editor.

## 2. Summary

**What exists.** A new `editor` toolset in `udea-agent`, **`commonMain`**, seven generated tools:
`editor.set_field`, `editor.move`, `editor.spawn`, `editor.delete`, `editor.save`, `editor.undo`,
`editor.history`. Like every tool they run inside the `SimBarrier` drain.

This is a port of the unmerged `issue-193-editor-tools` branch (`a29c4c8`, `403bfa4`), cherry-picked
onto `kmp` and resolved onto its layout rather than rewritten. Git placed the four new files
(`EditorToolset`, `EditorHistory`, `ToolLookups`, `EditorUndoTest`) into the renamed `commonMain` /
`jvmTest` directories itself, and two content conflicts were resolved by hand (`MobaAgent.kt`, where
`kmp` had added the generated registry to `wireAll`; `ToolsetHarness.kt` imports). The five rulings already on #193 stand as implemented: author = `?session=` or remote
address via the host's one `AgentSessions`; delete/undo keeps the NetId via `NetIdIndex.detach`;
the conflict rules; `-Peditor=true` -> `-Dudea.editor` -> `EditorMode.resolve()` and
`"editor":true|false` appended to `/health`; `editor.save name=` into `moba/build/editor-levels/`
with `LevelService.saveNow()`.

**What the port changed, and the one new decision** (commented on #193):

- **`editor.save` is common code on kotlinx-io.** The old store took a `java.nio.file.Path`.
  `EditorLevelStore` now takes a kotlinx-io `Path` and writes through `SystemFileSystem`, the
  library `udea-assets` already reads `.udeapak` through on every target (#205). `udea-agent`
  declares `api(libs.kotlinx.io.core)` because the store's constructor names that type.
  A browser has no file system, so a web host passes no store and `editor.save` answers the typed
  `no_level_store` - the #208 `diag.memory` shape, no `TODO()` anywhere. Rejected: a `jvmMain`
  store with a Wasm-only refusal (ruling 6's fallback), because the store *can* exist on Wasm and
  splitting it would add an expect/actual for a write the library already does in common code.
- **A common test.** `EditorRoundTripTest` (`commonTest`, so it runs as `jvmTest`, `wasmJsNodeTest`
  and the Android host test) has its own one-component world (`Plot`, a hand-written replicator of
  the whole frozen contract) because the JVM harness's components rest on `udea-core`'s JVM-only test
  fixtures. It holds: edits then the same number of undos back to the starting `WorldHasher.hash`
  (set a non-agent-writable field, move, spawn, delete); a save that writes a file and leaves the
  history undoable; and the `no_level_store` refusal.
- The JVM `EditorUndoTest` lost its save test (now the common one) and otherwise carries the old
  suite unchanged: authors, conflicts, spawn conflict, reverse+value, drag, 1,000 cap, bad level
  name, agentWritable.
- `ToolsetHarness.fieldHash()` reuses the harness's one `ComponentRegistry` rather than a second
  `SnapshotService` field (the merge had also doubled two imports).

**Kept from the old branch, worth knowing:** `WorldToolset`'s id lookup, field-name lookup, value
parsing and spawn-with-did-you-mean moved to `ToolLookups.kt` so `world.*` and `editor.*` share one
copy. One visible difference: `world.spawn_blueprint` with no spawner or an unknown blueprint now
*throws* `AgentToolException` with the same kind and message instead of returning
`AgentResult.failed`; the dispatcher turns both into the same failed result, and the full
`udea-agent` suite is green.

**Contracts:** no file in `docs/contracts/` changed; `/health`'s shape is not pinned there, and
`SessionAdditiveTest` still asserts the contract prefix byte for byte.

**Not exercised:** undo after a rewind or level load (the `entity_gone` path) has no test; there is
no redo; `editor.save` in a real browser was not run (nothing on this tree hosts the agent surface
in a browser); iOS is not built by `udea-agent` (it is on `dev.wildware.udea.kotlin-multiplatform-no-ios`).

## 3. `sh gradlew build --continue`

Baseline: I did not run a separate baseline build; the lead's brief states `7073adc` builds with no
failing task. This branch's run has no failing task, so no baseline-green task went red, and there
is no named red task this ticket was meant to turn green.

Spliced from `build/issue193/build1-193.log` (run alone on a quiet box, load average 1.22 sampled just before it started):

```
BUILD SUCCESSFUL in 1m 49s
620 actionable tasks: 387 executed, 75 from cache, 158 up-to-date
```

JUnit XML totals afterwards (`python3 build/issue193/count.py`, every report under the worktree,
saved as `count-193.txt`):

```
report files 551, tests 3561, skipped 37, failed 0
udea-agent-host/build/test-results/test/TEST-dev.wildware.udea.agent.host.AgentHostContractTest.xml: tests=15 failures=0 errors=0 skipped=0
udea-agent/build/test-results/jvmTest/TEST-dev.wildware.udea.agent.tools.EditorRoundTripTest.xml: tests=3 failures=0 errors=0 skipped=0
udea-agent/build/test-results/jvmTest/TEST-dev.wildware.udea.agent.tools.EditorUndoTest.xml: tests=9 failures=0 errors=0 skipped=0
udea-agent/build/test-results/testAndroidHostTest/TEST-dev.wildware.udea.agent.tools.EditorRoundTripTest.xml: tests=3 failures=0 errors=0 skipped=0
udea-agent/build/test-results/wasmJsNodeTest/TEST-dev.wildware.udea.agent.tools.EditorRoundTripTest.xml: tests=3 failures=0 errors=0 skipped=0
udea-core/build/test-results/jvmTest/TEST-dev.wildware.udea.core.identity.NetIdReservationTest.xml: tests=11 failures=0 errors=0 skipped=0
udea-core/build/test-results/testAndroidHostTest/TEST-dev.wildware.udea.core.identity.NetIdReservationTest.xml: tests=11 failures=0 errors=0 skipped=0
udea-core/build/test-results/wasmJsNodeTest/TEST-dev.wildware.udea.core.identity.NetIdReservationTest.xml: tests=11 failures=0 errors=0 skipped=0
```

Gates outside `check`, `udeaVerifyModuleGraph udeaVerifyNoLegacyDependencies udeaVerifyAgentsMd`
(`gates-193.log`) - run because `udea-agent` gained a dependency:

```
BUILD SUCCESSFUL in 12s
145 actionable tasks: 9 executed, 136 up-to-date
```

**GL tests not run.** Nothing here touches `udea-render` or the render half of `udea-agent-host`:
the host change is one `/health` key, and `MobaAgent`'s change is tool wiring. The live runs below
were `Headless` (`-Pudea.render.mode=Headless`), so no GL context was opened, in line with the
owner's no-LibGDX ruling. `runUdpProof` not run - nothing touches the network path.
`udeaDaemonBudget` is not on `check` on this tree (it is listed under the root's latency budgets)
and was not run; nothing here touches the asset daemon.

## 4. Images

None. The ticket has no UI, and ruling 8 makes the evidence tests plus a `/tools` and `/health`
transcript. Dashboard posts were text.

## 5. The issue, criterion by criterion

**AC1 - through `SimHarness`, no graphics, edits then the same number of undos returns
`WorldHasher.hash` to its start.**
`EditorUndoTest > a sequence of edits then the same number of undos returns the world hash to its
start()` (JVM, five edits over four component types) and `EditorRoundTripTest > edits then the same
number of undos return the world hash to its start` (common: `jvmTest`, `wasmJsNodeTest`, Android
host test). Both assert the hash moved in between, so neither passes by doing nothing, and that the
deleted entity resolves again under its original NetId. Red under M3 and M4 on both JVM and Wasm.

**AC2 - A edits, B edits the same field, A's undo refused naming B, overwrite succeeds.**
`EditorUndoTest > an undo is refused when another author changed the field since, naming them, and
overwrite succeeds()` (red under M1 and M2). Live, `transcript.jsonl` lines 6-9 (`dev-193`, then
`designer-b`, then two undos by `dev-193`):

```
{"tool": "editor.set_field", "args": {"id": "2", "component": "Position", "field": "hp", "value": "5", "session": "dev-193"}, "answer": {"id": 6, "ok": true, "result": {"id": 2, "component": "Position", "field": "hp", "value": 5, "reverse": {"tool": "editor.set_field", "args": {"id": "2", "component": "Position", "field": "hp", "value": "50.0"}}}}}
{"tool": "editor.set_field", "args": {"id": "2", "component": "Position", "field": "hp", "value": "77", "session": "designer-b"}, "answer": {"id": 7, "ok": true, "result": {"id": 2, "component": "Position", "field": "hp", "value": 77, "reverse": {"tool": "editor.set_field", "args": {"id": "2", "component": "Position", "field": "hp", "value": "5.0"}}}}}
{"tool": "editor.undo", "args": {"session": "dev-193"}, "answer": {"id": 8, "ok": false, "error": {"kind": "edit_conflict", "message": "refused to undo your editor.set_field on entity #2@0: Position.hp now holds 77.0, not the 5.0 your edit left, because designer-b changed it since with editor.set_field. Call editor.undo with overwrite=true to put back 50.0 anyway."}}}
{"tool": "editor.undo", "args": {"overwrite": "true", "session": "dev-193"}, "answer": {"id": 9, "ok": true, "result": {"undone": "editor.set_field", "id": 2, "overwrote": true, "value": {"hp": 50}}}}
```

**AC3 - `editor.*` in `/tools` with descriptions an agent can act on, and not in a normal run's
`/tools`.** Two live runs of this commit:
`sh gradlew :moba:run -PdebugPort=7851 -Peditor=true -Pudea.render.mode=Headless` and the same on
7852 without `-Peditor`. `/tools` saved from each, read back by `live/summarize.py`
(`live/summary-193.txt`):

```
live/tools-editor.json tools 58 toolsets ['assets', 'diag', 'editor', 'events', 'game', 'input', 'render', 'replay', 'time', 'world']
  editor.delete - Remove one entity while authoring a level, keeping its components so editor.undo brings it back under the very same NetId. Its reverse is editor.undo: a deleted entity's components cannot be sent back as arguments.
    args: id
  editor.history - List the edits editor.undo would undo for you, newest first, with the tool and entity of each. Every author has their own history of up to a thousand edits; saving a level does not clear it.
    args: limit?
  editor.move - Move one entity to a world position while authoring a level. A drag is one call, made on release: pass fromX and fromY with where the drag began so the reverse and undo return there. Answers the new position and the reverse call.
    args: id, x, y, fromX?, fromY?
  editor.save - Save the whole world as a level file named <name>.udealevel in this editor's level directory, answering the path and size. Saving never clears any author's undo history, so edits made before a save can still be undone.
    args: name
  editor.set_field - Set any field of any component on one entity while authoring a level, ignoring agentWritable. Answers the value left behind and the reverse call. Recorded in your own undo history; send session=<your name> to keep it yours.
    args: id, component, field, value
  editor.spawn - Create one entity from a named blueprint while authoring a level, optionally at a position. Answers its NetId and the reverse call, editor.delete. Undoing it is refused while another author's later edit to that entity stands.
    args: blueprint, x?, y?
  editor.undo - Undo your newest editor edit. Refused, naming the author, if another author has since changed what it wrote; call again with overwrite=true to undo anyway. Only your own history is undone, so send the same session= every call.
    args: overwrite?
live/tools-normal.json tools 51 toolsets ['assets', 'diag', 'events', 'game', 'input', 'render', 'replay', 'time', 'world']
```

`/health` from each (`live/health-editor.json`, `live/health-normal.json`):

```
{"ok":true,"frame":359,"tick":369,"paused":false,"renderMode":"Headless","role":"standalone","sessionId":"s-6830","editor":true}
{"ok":true,"frame":7,"tick":7,"paused":false,"renderMode":"Headless","role":"standalone","sessionId":"s-730f","editor":false}
```

Calling an editor tool on the normal run (`transcript-normal.jsonl`):

```
{"tool": "editor.undo", "args": {"session": "dev-193"}, "answer": {"id": 1, "ok": false, "error": {"kind": "no_such_tool", "message": "no tool named editor.undo is registered; call the tools listing to see what is"}}}
```

`EngineToolSurfaceTest` covers the editor descriptions as it covers every engine module (it is in
`EngineToolModules.ALL`). There is still no unit test that a normal `moba` run omits the toolset;
the transcript above is that evidence.

**Design points in the issue:**
- Reverse plus value: `every edit answers with its reverse and the value it left behind()`; live,
  transcript line 4 (`editor.move` answering `value` and `reverse`).
- A drag is one edit: `a drag reports where it started, …()` (red under M6).
- One history per author: `each author undoes only their own edits()` (red under M2).
- Cap 1,000, oldest dropped: `history keeps the newest thousand edits per author and drops the
  oldest()` (red under M5).
- Save does not clear history: `EditorRoundTripTest > a save writes the level file and leaves the
  history undoable` on JVM and Wasm (red under M9 and M3). Live, lines 11-18: delete, `no_such_entity`,
  save (50319 bytes), history, undo of the delete restoring id 2, undo of the move.
- Ignores `agentWritable`: `the editor writes a field world set_component_field refuses()` (red under
  M4); live, line 10 shows `world.set_component_field` still refusing `Position.hp`.
- Spawn undo conflict: `undoing a spawn another author has since edited is refused naming them()`
  (red under M8). Live spawn and undo: lines 21-22.
- `/health` reports the editor: `AgentHostContractTest > health reports whether the editor is
  running()` (red under M7), plus `the editor switch reads true, false or absent and refuses anything
  else()`.
- Wasm: the round trip and the save on `wasmJsNodeTest` (section 3 counts, section 8 M3/M4/M9).

The whole live editor transcript, `live/transcript.jsonl`:

```
{"tool": "time.pause", "args": {"session": "dev-193"}, "answer": {"id": 1, "ok": true, "result": {"tick": 1532, "paused": true, "timeScale": 1, "totalTicks": 1532}}}
{"tool": "world.query_entities", "args": {"with": "Position", "limit": "3", "session": "dev-193"}, "answer": {"id": 2, "ok": true, "result": {"total": 26, "offset": 0, "entities": [{"id": 0}, {"id": 1}, {"id": 2}], "returned": 3, "hasMore": true, "nextOffset": 3}}}
{"tool": "world.get_component", "args": {"id": "2", "component": "Position", "session": "dev-193"}, "answer": {"id": 3, "ok": true, "result": {"id": 2, "component": "Position", "fields": {"hp": 50, "x": -435.9678, "y": -173.416}}}}
{"tool": "editor.move", "args": {"id": "2", "x": "-400", "y": "-150", "session": "dev-193"}, "answer": {"id": 4, "ok": true, "result": {"id": 2, "value": {"x": -400, "y": -150}, "reverse": {"tool": "editor.move", "args": {"id": "2", "x": "-435.96783", "y": "-173.416"}}}}}
{"tool": "world.get_component", "args": {"id": "2", "component": "Position", "session": "dev-193"}, "answer": {"id": 5, "ok": true, "result": {"id": 2, "component": "Position", "fields": {"hp": 50, "x": -400, "y": -150}}}}
{"tool": "editor.set_field", "args": {"id": "2", "component": "Position", "field": "hp", "value": "5", "session": "dev-193"}, "answer": {"id": 6, "ok": true, "result": {"id": 2, "component": "Position", "field": "hp", "value": 5, "reverse": {"tool": "editor.set_field", "args": {"id": "2", "component": "Position", "field": "hp", "value": "50.0"}}}}}
{"tool": "editor.set_field", "args": {"id": "2", "component": "Position", "field": "hp", "value": "77", "session": "designer-b"}, "answer": {"id": 7, "ok": true, "result": {"id": 2, "component": "Position", "field": "hp", "value": 77, "reverse": {"tool": "editor.set_field", "args": {"id": "2", "component": "Position", "field": "hp", "value": "5.0"}}}}}
{"tool": "editor.undo", "args": {"session": "dev-193"}, "answer": {"id": 8, "ok": false, "error": {"kind": "edit_conflict", "message": "refused to undo your editor.set_field on entity #2@0: Position.hp now holds 77.0, not the 5.0 your edit left, because designer-b changed it since with editor.set_field. Call editor.undo with overwrite=true to put back 50.0 anyway."}}}
{"tool": "editor.undo", "args": {"overwrite": "true", "session": "dev-193"}, "answer": {"id": 9, "ok": true, "result": {"undone": "editor.set_field", "id": 2, "overwrote": true, "value": {"hp": 50}}}}
{"tool": "world.set_component_field", "args": {"id": "2", "component": "Position", "field": "hp", "value": "9", "session": "dev-193"}, "answer": {"id": 10, "ok": false, "error": {"kind": "field_not_writable", "message": "Position.hp is not agent-writable - agentWritable = false is the default on @Net, so an agent write is opt-in per field; nothing else writes it today, so change it in the game's own code or mark it @Net(agentWritable = true) if an agent is meant to"}}}
{"tool": "editor.delete", "args": {"id": "2", "session": "dev-193"}, "answer": {"id": 11, "ok": true, "result": {"id": 2, "value": null, "reverse": {"tool": "editor.undo", "args": {}}}}}
{"tool": "world.describe_entity", "args": {"id": "2", "session": "dev-193"}, "answer": {"id": 12, "ok": false, "error": {"kind": "no_such_entity", "message": "no live entity for NetId #2@0; it has been destroyed, or its slot has been recycled since the id was issued"}}}
{"tool": "editor.save", "args": {"name": "issue193-live", "session": "dev-193"}, "answer": {"id": 13, "ok": true, "result": {"path": "/srv/ssd1/workspace/Udea/.claude/worktrees/agent-a95a8d0403add24ba/moba/build/editor-levels/issue193-live.udealevel", "bytes": 50319}}}
{"tool": "editor.history", "args": {"session": "dev-193"}, "answer": {"id": 14, "ok": true, "result": {"author": "dev-193", "size": 2, "edits": [{"sequence": 4, "tool": "editor.delete", "id": 2}, {"sequence": 1, "tool": "editor.move", "id": 2}]}}}
{"tool": "editor.undo", "args": {"session": "dev-193"}, "answer": {"id": 15, "ok": true, "result": {"undone": "editor.delete", "id": 2}}}
{"tool": "world.get_component", "args": {"id": "2", "component": "Position", "session": "dev-193"}, "answer": {"id": 16, "ok": true, "result": {"id": 2, "component": "Position", "fields": {"hp": 50, "x": -400, "y": -150}}}}
{"tool": "editor.undo", "args": {"session": "dev-193"}, "answer": {"id": 17, "ok": true, "result": {"undone": "editor.move", "id": 2, "overwrote": false, "value": {"x": -435.9678, "y": -173.416}}}}
{"tool": "world.get_component", "args": {"id": "2", "component": "Position", "session": "dev-193"}, "answer": {"id": 18, "ok": true, "result": {"id": 2, "component": "Position", "fields": {"hp": 50, "x": -435.9678, "y": -173.416}}}}
{"tool": "editor.undo", "args": {"session": "dev-193"}, "answer": {"id": 19, "ok": false, "error": {"kind": "nothing_to_undo", "message": "dev-193 has nothing to undo; editor.history lists what an author can undo, and each author undoes only their own edits"}}}
{"tool": "world.list_blueprints", "args": {"session": "dev-193"}, "answer": {"id": 20, "ok": true, "result": {"spawnable": true, "blueprints": ["orc", "orc_elite", "priest", "skeleton", "soldier", "wizard"]}}}
{"tool": "editor.spawn", "args": {"blueprint": "orc", "x": "10", "y": "20", "session": "dev-193"}, "answer": {"id": 21, "ok": true, "result": {"id": 393220, "blueprint": "orc", "value": 393220, "reverse": {"tool": "editor.delete", "args": {"id": "393220"}}}}}
{"tool": "editor.undo", "args": {"session": "dev-193"}, "answer": {"id": 22, "ok": true, "result": {"undone": "editor.spawn", "id": 393220, "overwrote": false}}}
```

## 6. Regenerated files

None. No replicated component was added or removed; `net-protocol.lock` and
`expected-generated-hashes.txt` are untouched, and `udeaCheckProtocolLock` passed inside `build`.

## 7. Files outside `udea-agent` and `udea-agent-host`

`udea-core` (`NetIdIndex.detach`, `LevelService.saveNow`, both public API `udea-agent` uses),
`moba/build.gradle.kts` (one line forwarding `-Peditor`) and `MobaAgent.kt` (wiring). No build-logic
and no `udea-net`.

## 8. Mutation table

Run by `build/issue193/mutate.py` against `241c4de`, each section the file that run wrote,
verbatim: the literal `git diff` (with `gradlew`'s local mode change excluded), the Gradle exit code,
the `e: ` compile-error lines in its log, and the failing tests from the JUnit XML of the four test
tasks. Each run deleted those four result directories first, so no report is left over from the run
before. M0 is the unmutated control.

### M0-control-none

```diff
```

gradle exit 0

compile errors (0):

failing tests (0):

### M1-no-conflict-check

```diff
diff --git a/udea-agent/src/commonMain/kotlin/dev/wildware/udea/agent/tools/EditorToolset.kt b/udea-agent/src/commonMain/kotlin/dev/wildware/udea/agent/tools/EditorToolset.kt
index 23d2a08..1f1b458 100644
--- a/udea-agent/src/commonMain/kotlin/dev/wildware/udea/agent/tools/EditorToolset.kt
+++ b/udea-agent/src/commonMain/kotlin/dev/wildware/udea/agent/tools/EditorToolset.kt
@@ -362,7 +362,7 @@ public class EditorToolset(
         if (entity == null || edit.changes.any { !it.component.isPresent(world, entity) }) {
             return gone(edit, overwrite)
         }
-        val conflict = edit.changes.firstOrNull { it.component.read(world, entity, it.fieldIndex) != it.after }
+        val conflict = edit.changes.firstOrNull { false }
         if (conflict != null && !overwrite) {
             val current = conflict.component.read(world, entity, conflict.fieldIndex)
             val changer = history.latestByOthers(edit) { it.touches(edit.netId, conflict.component, conflict.fieldIndex) }
```

gradle exit 1

compile errors (0):

failing tests (1):
- udea-agent:jvmTest EditorUndoTest > an undo is refused when another author changed the field since, naming them, and overwrite succeeds()[jvm]

### M2-one-shared-history

```diff
diff --git a/udea-agent/src/commonMain/kotlin/dev/wildware/udea/agent/tools/EditorToolset.kt b/udea-agent/src/commonMain/kotlin/dev/wildware/udea/agent/tools/EditorToolset.kt
index 23d2a08..a7ab7b8 100644
--- a/udea-agent/src/commonMain/kotlin/dev/wildware/udea/agent/tools/EditorToolset.kt
+++ b/udea-agent/src/commonMain/kotlin/dev/wildware/udea/agent/tools/EditorToolset.kt
@@ -134,7 +134,7 @@ public class EditorToolset(
         val before = type.read(world, entity, fieldIndex)
         type.write(world, entity, fieldIndex, parseFieldText(SET_FIELD, type, fieldIndex, before, value))
         val change = FieldChange(type, fieldIndex, before, type.read(world, entity, fieldIndex))
-        record(EditorEdit.Fields(history.nextSequence(), context.command.session, SET_FIELD, id, listOf(change)))
+        record(EditorEdit.Fields(history.nextSequence(), AgentSessionId.LOCAL, SET_FIELD, id, listOf(change)))
 
         return AgentResult.ok {
             put("id", id.raw)
@@ -191,7 +191,7 @@ public class EditorToolset(
             moveField(entity, position, position.xIndex, x, fromX),
             moveField(entity, position, position.yIndex, y, fromY),
         )
-        record(EditorEdit.Fields(history.nextSequence(), context.command.session, MOVE, id, changes))
+        record(EditorEdit.Fields(history.nextSequence(), AgentSessionId.LOCAL, MOVE, id, changes))
 
         return AgentResult.ok {
             put("id", id.raw)
@@ -226,7 +226,7 @@ public class EditorToolset(
         y: Float?,
     ): AgentResult {
         val netId = catalog.spawnNow(world, spawner, blueprint, x, y)
-        record(EditorEdit.Spawn(history.nextSequence(), context.command.session, netId))
+        record(EditorEdit.Spawn(history.nextSequence(), AgentSessionId.LOCAL, netId))
         return AgentResult.ok {
             put("id", netId.raw)
             put("blueprint", blueprint)
@@ -252,7 +252,7 @@ public class EditorToolset(
         // entity back behind it. `release` frees it for good once no history can undo this.
         netIds.detach(id)
         world -= entity
-        record(EditorEdit.Delete(history.nextSequence(), context.command.session, id, removed))
+        record(EditorEdit.Delete(history.nextSequence(), AgentSessionId.LOCAL, id, removed))
         return AgentResult.ok {
             put("id", id.raw)
             put("value", null as String?)
@@ -278,7 +278,7 @@ public class EditorToolset(
         )
         overwrite: Boolean,
     ): AgentResult {
-        val author = context.command.session
+        val author = AgentSessionId.LOCAL
         val edit = history.newest(author) ?: return AgentResult.failed(
             NOTHING_TO_UNDO,
             "${label(author)} has nothing to undo; editor.history lists what an author can undo, " +
@@ -302,7 +302,7 @@ public class EditorToolset(
         @Arg(description = "How many edits to list, newest first.", required = false, default = "20")
         limit: Int,
     ): AgentResult {
-        val author = context.command.session
+        val author = AgentSessionId.LOCAL
         return AgentResult.ok {
             put("author", label(author))
             put("size", history.size(author))
```

gradle exit 1

compile errors (0):

failing tests (3):
- udea-agent:jvmTest EditorUndoTest > each author undoes only their own edits()[jvm]
- udea-agent:jvmTest EditorUndoTest > an undo is refused when another author changed the field since, naming them, and overwrite succeeds()[jvm]
- udea-agent:jvmTest EditorUndoTest > undoing a spawn another author has since edited is refused naming them()[jvm]

### M3-delete-frees-the-id

```diff
diff --git a/udea-agent/src/commonMain/kotlin/dev/wildware/udea/agent/tools/EditorToolset.kt b/udea-agent/src/commonMain/kotlin/dev/wildware/udea/agent/tools/EditorToolset.kt
index 23d2a08..382b19e 100644
--- a/udea-agent/src/commonMain/kotlin/dev/wildware/udea/agent/tools/EditorToolset.kt
+++ b/udea-agent/src/commonMain/kotlin/dev/wildware/udea/agent/tools/EditorToolset.kt
@@ -250,7 +250,7 @@ public class EditorToolset(
         val removed = world.snapshotOf(entity)
         // Detached rather than freed: the id stays held, unresolvable, so an undo can put the
         // entity back behind it. `release` frees it for good once no history can undo this.
-        netIds.detach(id)
+        netIds.free(id)
         world -= entity
         record(EditorEdit.Delete(history.nextSequence(), context.command.session, id, removed))
         return AgentResult.ok {
```

gradle exit 1

compile errors (0):

failing tests (5):
- udea-agent:jvmTest EditorRoundTripTest > edits then the same number of undos return the world hash to its start()[jvm]
- udea-agent:jvmTest EditorRoundTripTest > a save writes the level file and leaves the history undoable()[jvm]
- udea-agent:jvmTest EditorUndoTest > a sequence of edits then the same number of undos returns the world hash to its start()[jvm]
- udea-agent:wasmJsNodeTest EditorRoundTripTest > edits then the same number of undos return the world hash to its start[wasmJs, node]
- udea-agent:wasmJsNodeTest EditorRoundTripTest > a save writes the level file and leaves the history undoable[wasmJs, node]

### M4-editor-honours-agentWritable

```diff
diff --git a/udea-agent/src/commonMain/kotlin/dev/wildware/udea/agent/tools/EditorToolset.kt b/udea-agent/src/commonMain/kotlin/dev/wildware/udea/agent/tools/EditorToolset.kt
index 23d2a08..4c1fa63 100644
--- a/udea-agent/src/commonMain/kotlin/dev/wildware/udea/agent/tools/EditorToolset.kt
+++ b/udea-agent/src/commonMain/kotlin/dev/wildware/udea/agent/tools/EditorToolset.kt
@@ -131,6 +131,7 @@ public class EditorToolset(
         val type = components.requireByName(component)
         val fieldIndex = type.requireFieldIndex(field)
         requirePresent(type, entity, id)
+        if (!type.isAgentWritable(fieldIndex)) return AgentResult.failed(WorldToolset.FIELD_NOT_WRITABLE, "not writable")
         val before = type.read(world, entity, fieldIndex)
         type.write(world, entity, fieldIndex, parseFieldText(SET_FIELD, type, fieldIndex, before, value))
         val change = FieldChange(type, fieldIndex, before, type.read(world, entity, fieldIndex))
```

gradle exit 1

compile errors (0):

failing tests (4):
- udea-agent:jvmTest EditorRoundTripTest > edits then the same number of undos return the world hash to its start()[jvm]
- udea-agent:jvmTest EditorUndoTest > the editor writes a field world set_component_field refuses()[jvm]
- udea-agent:jvmTest EditorUndoTest > a sequence of edits then the same number of undos returns the world hash to its start()[jvm]
- udea-agent:wasmJsNodeTest EditorRoundTripTest > edits then the same number of undos return the world hash to its start[wasmJs, node]

### M5-no-history-cap

```diff
diff --git a/udea-agent/src/commonMain/kotlin/dev/wildware/udea/agent/tools/EditorHistory.kt b/udea-agent/src/commonMain/kotlin/dev/wildware/udea/agent/tools/EditorHistory.kt
index a0033c6..dcc2cda 100644
--- a/udea-agent/src/commonMain/kotlin/dev/wildware/udea/agent/tools/EditorHistory.kt
+++ b/udea-agent/src/commonMain/kotlin/dev/wildware/udea/agent/tools/EditorHistory.kt
@@ -91,7 +91,7 @@ internal class EditorHistory(
     fun push(edit: EditorEdit) {
         val stack = stackOf(edit.author) ?: ArrayDeque<EditorEdit>().also { stacks[edit.author.raw] = it }
         stack.addLast(edit)
-        if (stack.size > capacity) onDrop(stack.removeFirst())
+        if (false && stack.size > capacity) onDrop(stack.removeFirst())
     }
 
     /** [author]'s newest edit, or `null` when there is nothing to undo. */
```

gradle exit 1

compile errors (0):

failing tests (1):
- udea-agent:jvmTest EditorUndoTest > history keeps the newest thousand edits per author and drops the oldest()[jvm]

### M6-drag-start-ignored

```diff
diff --git a/udea-agent/src/commonMain/kotlin/dev/wildware/udea/agent/tools/EditorToolset.kt b/udea-agent/src/commonMain/kotlin/dev/wildware/udea/agent/tools/EditorToolset.kt
index 23d2a08..8f57d12 100644
--- a/udea-agent/src/commonMain/kotlin/dev/wildware/udea/agent/tools/EditorToolset.kt
+++ b/udea-agent/src/commonMain/kotlin/dev/wildware/udea/agent/tools/EditorToolset.kt
@@ -453,7 +453,7 @@ public class EditorToolset(
     private fun moveField(entity: Entity, position: PositionRef, fieldIndex: Int, to: Float, from: Float?): FieldChange {
         val type = position.component
         val current = type.read(world, entity, fieldIndex)
-        val before = if (from == null) current else parseFieldText(MOVE, type, fieldIndex, current, from.toString())
+        val before = current
         type.write(world, entity, fieldIndex, parseFieldText(MOVE, type, fieldIndex, current, to.toString()))
         return FieldChange(type, fieldIndex, before, type.read(world, entity, fieldIndex))
     }
```

gradle exit 1

compile errors (0):

failing tests (1):
- udea-agent:jvmTest EditorUndoTest > a drag reports where it started, so the reverse returns there rather than to the release point()[jvm]

### M7-health-never-reports-editor

```diff
diff --git a/udea-agent-host/src/main/kotlin/dev/wildware/udea/agent/host/AgentHost.kt b/udea-agent-host/src/main/kotlin/dev/wildware/udea/agent/host/AgentHost.kt
index 81050a0..5cbcdd4 100644
--- a/udea-agent-host/src/main/kotlin/dev/wildware/udea/agent/host/AgentHost.kt
+++ b/udea-agent-host/src/main/kotlin/dev/wildware/udea/agent/host/AgentHost.kt
@@ -148,7 +148,7 @@ public class AgentHost private constructor(
                 put("renderMode", config.renderMode.name)
                 put("role", config.session.role.id)
                 put("sessionId", config.session.sessionId.value)
-                put("editor", config.editor)
+                put("editor", false)
             },
         )
     }
```

gradle exit 1

compile errors (0):

failing tests (1):
- udea-agent-host:test AgentHostContractTest > health reports whether the editor is running()

### M8-spawn-undo-ignores-later-edits

```diff
diff --git a/udea-agent/src/commonMain/kotlin/dev/wildware/udea/agent/tools/EditorToolset.kt b/udea-agent/src/commonMain/kotlin/dev/wildware/udea/agent/tools/EditorToolset.kt
index 23d2a08..3c8b3da 100644
--- a/udea-agent/src/commonMain/kotlin/dev/wildware/udea/agent/tools/EditorToolset.kt
+++ b/udea-agent/src/commonMain/kotlin/dev/wildware/udea/agent/tools/EditorToolset.kt
@@ -388,7 +388,7 @@ public class EditorToolset(
 
     private fun undoSpawn(edit: EditorEdit.Spawn, overwrite: Boolean): AgentResult {
         val entity = netIds.resolveOrNull(edit.netId) ?: return gone(edit, overwrite)
-        val later = history.latestByOthers(edit) { it.netId == edit.netId }
+        val later: EditorEdit? = null
         if (later != null && !overwrite) {
             return AgentResult.failed(
                 EDIT_CONFLICT,
```

gradle exit 1

compile errors (0):

failing tests (1):
- udea-agent:jvmTest EditorUndoTest > undoing a spawn another author has since edited is refused naming them()[jvm]

### M9-save-clears-history

```diff
diff --git a/udea-agent/src/commonMain/kotlin/dev/wildware/udea/agent/tools/EditorToolset.kt b/udea-agent/src/commonMain/kotlin/dev/wildware/udea/agent/tools/EditorToolset.kt
index 23d2a08..0f46b4b 100644
--- a/udea-agent/src/commonMain/kotlin/dev/wildware/udea/agent/tools/EditorToolset.kt
+++ b/udea-agent/src/commonMain/kotlin/dev/wildware/udea/agent/tools/EditorToolset.kt
@@ -327,6 +327,7 @@ public class EditorToolset(
             "author's undo history, so edits made before a save can still be undone.",
     )
     public fun save(
+        context: AgentContext,
         @Arg(description = "Level name: letters, digits, '-' and '_', no path.")
         name: String,
     ): AgentResult {
@@ -346,6 +347,7 @@ public class EditorToolset(
         } catch (refused: LevelSaveException) {
             return AgentResult.failed(LEVEL_NOT_SAVEABLE, refused.message ?: "the world cannot be saved as a level")
         }
+        while (history.newest(context.command.session) != null) release(history.pop(context.command.session))
         SystemFileSystem.createDirectories(store.directory)
         val file = Path(store.directory, "$name.${LevelService.FILE_EXTENSION}")
         SystemFileSystem.sink(file).buffered().use { it.write(bytes) }
```

gradle exit 1

compile errors (0):

failing tests (2):
- udea-agent:jvmTest EditorRoundTripTest > a save writes the level file and leaves the history undoable()[jvm]
- udea-agent:wasmJsNodeTest EditorRoundTripTest > a save writes the level file and leaves the history undoable[wasmJs, node]

