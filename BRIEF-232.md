03e1baf

# BRIEF-232: edit sessions, multi-entity set_field, selection, common_fields, and edits in the replay file

Branch `issue-232-edit-sessions`, off `origin/kmp` at `9d6629f`. The line above is the commit the
code and this evidence belong to; the brief itself is the commit after it.

## 1. The evidence command

```
sh gradlew :udea-agent:jvmTest --tests 'dev.wildware.udea.agent.tools.EditSessionTest' :udea-replay:jvmTest --tests 'dev.wildware.udea.replay.EditingSessionReplayTest'
```

Green at 03e1baf, run with `--rerun` on both test tasks so neither came from the build cache.
Every artefact named in this brief is under
`/tmp/claude-1000/-srv-ssd1-workspace-Udea/1ad8c5e6-2def-4055-91d2-72acdfe77daf/scratchpad/issue232/`
(written `scratchpad/issue232/` below).

`grep -E '^> Task :udea-(agent|replay):jvmTest$|^BUILD|actionable' evidence-green.log`:

```
> Task :udea-agent:jvmTest
> Task :udea-replay:jvmTest
BUILD SUCCESSFUL in 4s
112 actionable tasks: 9 executed, 103 up-to-date
```

`grep -ho '<testsuite name="[^"]*" tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*"' evidence-xml/*.xml`,
then `grep -ho '\[edit-replay\][^<]*' evidence-xml/*.xml` (the proof's own printed lines):

```
<testsuite name="dev.wildware.udea.agent.tools.EditSessionTest" tests="14" skipped="0" failures="0"
<testsuite name="dev.wildware.udea.replay.EditingSessionReplayTest" tests="5" skipped="0" failures="0"
[edit-replay] 13 recorded edits over 40 ticks: bit-exact: 40 tick(s) from t0 replayed to the recorded hash stream, every tick
[edit-replay] one tick late: replay diverged at t4 (4 tick(s) matched first): recorded hash 3719612448294410932, replayed 8508858971894922908
[edit-replay] edits ignored: replay diverged at t4 (4 tick(s) matched first): recorded hash 3719612448294410932, replayed 8508858971894922908
```

**It goes red when the feature is taken away.** Reverting the feature outright does not compile
(the tools and `ReplayEdit` vanish), so the proof is the mutations below, each one restoring a
plausible wrong shape, each run with exactly the command above by
`scratchpad/issue232/mutate.py`, which applies the edit, saves `git diff`, runs, collects the
failing test names from the JUnit XML, and restores the file. Every one went red; `git status` was
clean afterwards. The diffs and names below are spliced from the files that run wrote. It ran
at `00f17eb`; the one later code commit, `03e1baf`, deletes an unused helper in `EditSessions.kt`,
a file no mutation touches (the `index` lines below still match `git ls-tree` at 03e1baf).

### M1-commit-records-nothing

```diff
diff --git a/udea-agent/src/commonMain/kotlin/dev/wildware/udea/agent/tools/EditorToolset.kt b/udea-agent/src/commonMain/kotlin/dev/wildware/udea/agent/tools/EditorToolset.kt
index 84e9569..035f61b 100644
--- a/udea-agent/src/commonMain/kotlin/dev/wildware/udea/agent/tools/EditorToolset.kt
+++ b/udea-agent/src/commonMain/kotlin/dev/wildware/udea/agent/tools/EditorToolset.kt
@@ -786,7 +786,7 @@ public class EditorToolset(
             }
         }
         openEdits.close(session)
-        if (changes.isEmpty()) {
+        if (true) {
             bridge.event("editor_commit_edit:${session.id.raw}:${label(session.author)}:unchanged", clock.tick.value)
             return null
         }
```

Red (`M1-commit-records-nothing.failed`):

```
exit=1
EditSessionTest > a committed session whose field another author changed since is refused on undo, naming them()[jvm]
EditSessionTest > begin then several updates then commit is one undo entry, and undo restores the starting values()[jvm]
EditSessionTest > beginning a second session commits the first()[jvm]
EditingSessionReplayTest > a recorded editing session replays bit-identically, with every edit applied between ticks()[jvm]
EditingSessionReplayTest > a replay that applies each edit one tick late diverges on the first edited tick()[jvm]
EditingSessionReplayTest > a replay that ignores the recorded edits diverges on the first edited tick()[jvm]
EditingSessionReplayTest > a replay world that cannot apply edits refuses an edited recording rather than diverging()[jvm]
EditingSessionReplayTest > an edited recording is format 2 and carries every edit, and the idle cancel is one of them()[jvm]
```

### M2-cancel-restores-nothing

```diff
diff --git a/udea-agent/src/commonMain/kotlin/dev/wildware/udea/agent/tools/EditorToolset.kt b/udea-agent/src/commonMain/kotlin/dev/wildware/udea/agent/tools/EditorToolset.kt
index 84e9569..7c3764e 100644
--- a/udea-agent/src/commonMain/kotlin/dev/wildware/udea/agent/tools/EditorToolset.kt
+++ b/udea-agent/src/commonMain/kotlin/dev/wildware/udea/agent/tools/EditorToolset.kt
@@ -805,7 +805,6 @@ public class EditorToolset(
                     if (netId !in gone) gone.add(netId)
                     continue
                 }
-                ref.component.write(world, entity, ref.fieldIndex, session.startOf(entityIndex, fieldIndex))
             }
         }
         openEdits.close(session)
```

Red (`M2-cancel-restores-nothing.failed`):

```
exit=1
EditSessionTest > a session idle for thirty seconds is cancelled and restored, and one updated in time is not()[jvm]
EditSessionTest > an author who leaves has their open session cancelled and restored()[jvm]
EditSessionTest > cancel restores every field exactly and leaves no undo entry()[jvm]
```

### M3-idle-boundary-off-by-one

```diff
diff --git a/udea-agent/src/commonMain/kotlin/dev/wildware/udea/agent/tools/EditorToolset.kt b/udea-agent/src/commonMain/kotlin/dev/wildware/udea/agent/tools/EditorToolset.kt
index 84e9569..a26eba4 100644
--- a/udea-agent/src/commonMain/kotlin/dev/wildware/udea/agent/tools/EditorToolset.kt
+++ b/udea-agent/src/commonMain/kotlin/dev/wildware/udea/agent/tools/EditorToolset.kt
@@ -833,7 +833,7 @@ public class EditorToolset(
                 session.touchedNanos = now
                 continue
             }
-            if (session.expiring || now - session.touchedNanos < IDLE_TIMEOUT_NANOS) continue
+            if (session.expiring || now - session.touchedNanos <= IDLE_TIMEOUT_NANOS) continue
             val cancel = AgentCommand(CANCEL_EDIT, mapOf(SESSION_ID to session.id.raw.toString()), session = session.author)
             // Refused only by a full queue; the next drain tries again.
             if (bridge.submit(cancel) is AgentSubmission.Accepted) {
```

Red (`M3-idle-boundary-off-by-one.failed`):

```
exit=1
EditSessionTest > a session idle for thirty seconds is cancelled and restored, and one updated in time is not()[jvm]
```

### M4-update-does-not-restart-idle

```diff
diff --git a/udea-agent/src/commonMain/kotlin/dev/wildware/udea/agent/tools/EditorToolset.kt b/udea-agent/src/commonMain/kotlin/dev/wildware/udea/agent/tools/EditorToolset.kt
index 84e9569..6f51f60 100644
--- a/udea-agent/src/commonMain/kotlin/dev/wildware/udea/agent/tools/EditorToolset.kt
+++ b/udea-agent/src/commonMain/kotlin/dev/wildware/udea/agent/tools/EditorToolset.kt
@@ -414,7 +414,6 @@ public class EditorToolset(
             }
         }
         for (write in planned) write.ref.component.write(world, write.entity, write.ref.fieldIndex, write.value)
-        session.touched = true
 
         AgentResult.ok {
             put("sessionId", session.id.raw)
```

Red (`M4-update-does-not-restart-idle.failed`):

```
exit=1
EditSessionTest > a session idle for thirty seconds is cancelled and restored, and one updated in time is not()[jvm]
```

### M5-set-field-one-entry-per-entity

```diff
diff --git a/udea-agent/src/commonMain/kotlin/dev/wildware/udea/agent/tools/EditorToolset.kt b/udea-agent/src/commonMain/kotlin/dev/wildware/udea/agent/tools/EditorToolset.kt
index 84e9569..3a7f307 100644
--- a/udea-agent/src/commonMain/kotlin/dev/wildware/udea/agent/tools/EditorToolset.kt
+++ b/udea-agent/src/commonMain/kotlin/dev/wildware/udea/agent/tools/EditorToolset.kt
@@ -180,7 +180,7 @@ public class EditorToolset(
             type.write(world, entity, fieldIndex, parsed[index])
             FieldChange(id[index], type, fieldIndex, before, type.read(world, entity, fieldIndex))
         }
-        record(EditorEdit.Fields(history.nextSequence(), context.command.session, SET_FIELD, changes))
+        for (change in changes) record(EditorEdit.Fields(history.nextSequence(), context.command.session, SET_FIELD, listOf(change)))
 
         val befores = changes.map { FieldValues.textOf(it.before) }.distinct()
         AgentResult.ok {
```

Red (`M5-set-field-one-entry-per-entity.failed`):

```
exit=1
EditSessionTest > a set_field on several entities is one write and one undo entry()[jvm]
```

### M6-common-fields-never-mixed

```diff
diff --git a/udea-agent/src/commonMain/kotlin/dev/wildware/udea/agent/tools/EditorToolset.kt b/udea-agent/src/commonMain/kotlin/dev/wildware/udea/agent/tools/EditorToolset.kt
index 84e9569..27a31c3 100644
--- a/udea-agent/src/commonMain/kotlin/dev/wildware/udea/agent/tools/EditorToolset.kt
+++ b/udea-agent/src/commonMain/kotlin/dev/wildware/udea/agent/tools/EditorToolset.kt
@@ -544,7 +544,7 @@ public class EditorToolset(
                     if (live.any { !type.isPresent(world, it) }) continue
                     for (fieldIndex in type.fieldNames.indices) {
                         val first = type.read(world, live[0], fieldIndex)
-                        val mixed = live.any { type.read(world, it, fieldIndex) != first }
+                        val mixed = false
                         element {
                             put("component", type.name)
                             put("field", type.fieldNames[fieldIndex])
```

Red (`M6-common-fields-never-mixed.failed`):

```
exit=1
EditSessionTest > common_fields reports a shared value and Mixed where the values differ()[jvm]
```

### M7-selection-shows-only-your-own

```diff
diff --git a/udea-agent/src/commonMain/kotlin/dev/wildware/udea/agent/tools/EditorToolset.kt b/udea-agent/src/commonMain/kotlin/dev/wildware/udea/agent/tools/EditorToolset.kt
index 84e9569..d90738d 100644
--- a/udea-agent/src/commonMain/kotlin/dev/wildware/udea/agent/tools/EditorToolset.kt
+++ b/udea-agent/src/commonMain/kotlin/dev/wildware/udea/agent/tools/EditorToolset.kt
@@ -514,6 +514,7 @@ public class EditorToolset(
         put("you", label(context.command.session))
         arr("authors") {
             selections.forEachAuthor { author, selected ->
+                if (author != context.command.session) return@forEachAuthor
                 val live = liveOnly(selected)
                 if (live.isNotEmpty()) {
                     element {
```

Red (`M7-selection-shows-only-your-own.failed`):

```
exit=1
EditSessionTest > two authors' selections are independent and each reads the other's()[jvm]
```

### M8-journal-records-nothing

```diff
diff --git a/udea-agent/src/commonMain/kotlin/dev/wildware/udea/agent/tools/EditorToolset.kt b/udea-agent/src/commonMain/kotlin/dev/wildware/udea/agent/tools/EditorToolset.kt
index 84e9569..adb5e8a 100644
--- a/udea-agent/src/commonMain/kotlin/dev/wildware/udea/agent/tools/EditorToolset.kt
+++ b/udea-agent/src/commonMain/kotlin/dev/wildware/udea/agent/tools/EditorToolset.kt
@@ -948,7 +948,7 @@ public class EditorToolset(
     /** [body], journaled when it succeeded. A refused or throwing call changed nothing, so nothing is kept. */
     private inline fun journaled(context: AgentContext, body: () -> AgentResult): AgentResult {
         val result = body()
-        if (result is AgentResult.Ok) {
+        if (false) {
             val command = context.command
             journal.record(EditorJournalEntry(clock.tick, label(command.session), command.name, command.args))
         }
```

Red (`M8-journal-records-nothing.failed`):

```
exit=1
EditingSessionReplayTest > a recorded editing session replays bit-identically, with every edit applied between ticks()[jvm]
EditingSessionReplayTest > a replay world that cannot apply edits refuses an edited recording rather than diverging()[jvm]
EditingSessionReplayTest > an edited recording is format 2 and carries every edit, and the idle cancel is one of them()[jvm]
```

### M9-journal-stamps-next-tick

```diff
diff --git a/udea-agent/src/commonMain/kotlin/dev/wildware/udea/agent/tools/EditorToolset.kt b/udea-agent/src/commonMain/kotlin/dev/wildware/udea/agent/tools/EditorToolset.kt
index 84e9569..397c7dd 100644
--- a/udea-agent/src/commonMain/kotlin/dev/wildware/udea/agent/tools/EditorToolset.kt
+++ b/udea-agent/src/commonMain/kotlin/dev/wildware/udea/agent/tools/EditorToolset.kt
@@ -950,7 +950,7 @@ public class EditorToolset(
         val result = body()
         if (result is AgentResult.Ok) {
             val command = context.command
-            journal.record(EditorJournalEntry(clock.tick, label(command.session), command.name, command.args))
+            journal.record(EditorJournalEntry(clock.tick + 1L, label(command.session), command.name, command.args))
         }
         return result
     }
```

Red (`M9-journal-stamps-next-tick.failed`):

```
exit=1
EditingSessionReplayTest > a recorded editing session replays bit-identically, with every edit applied between ticks()[jvm]
EditingSessionReplayTest > an edited recording is format 2 and carries every edit, and the idle cancel is one of them()[jvm]
```

### M10-verifier-feeds-input-only

```diff
diff --git a/udea-replay/src/commonMain/kotlin/dev/wildware/udea/replay/ReplayVerifier.kt b/udea-replay/src/commonMain/kotlin/dev/wildware/udea/replay/ReplayVerifier.kt
index ee06187..580a386 100644
--- a/udea-replay/src/commonMain/kotlin/dev/wildware/udea/replay/ReplayVerifier.kt
+++ b/udea-replay/src/commonMain/kotlin/dev/wildware/udea/replay/ReplayVerifier.kt
@@ -139,7 +139,8 @@ public object ReplayVerifier {
             var compared = 0
             for (index in 0 until recording.tickCount) {
                 val tick = recording.firstTick + index.toLong()
-                world.feed(recording, tick, slots)
+                recording.samplesInto(tick, slots)
+                world.applyInput(slots)
                 world.step()
                 compared++
                 val replayed = world.hash()
```

Red (`M10-verifier-feeds-input-only.failed`):

```
exit=1
EditingSessionReplayTest > a recorded editing session replays bit-identically, with every edit applied between ticks()[jvm]
EditingSessionReplayTest > a replay world that cannot apply edits refuses an edited recording rather than diverging()[jvm]
```

### M11-encode-drops-edits

```diff
diff --git a/udea-replay/src/commonMain/kotlin/dev/wildware/udea/replay/ReplayRecording.kt b/udea-replay/src/commonMain/kotlin/dev/wildware/udea/replay/ReplayRecording.kt
index 2585eca..2fa1ab3 100644
--- a/udea-replay/src/commonMain/kotlin/dev/wildware/udea/replay/ReplayRecording.kt
+++ b/udea-replay/src/commonMain/kotlin/dev/wildware/udea/replay/ReplayRecording.kt
@@ -161,7 +161,6 @@ public class ReplayRecording internal constructor(
         sink.patchI32(lengthAt, sink.size - headerStart)
         sink.raw(frameBytes)
         for (hash in hashes) sink.i64(hash)
-        if (edits.isNotEmpty()) writeEdits(sink)
         sink.i32(ReplayFormat.crc32(sink.backing(), sink.size).toInt())
         return sink.toByteArray()
     }
```

Red (`M11-encode-drops-edits.failed`):

```
exit=1
EditingSessionReplayTest > a recorded editing session replays bit-identically, with every edit applied between ticks()[jvm]
EditingSessionReplayTest > a replay that applies each edit one tick late diverges on the first edited tick()[jvm]
EditingSessionReplayTest > a replay that ignores the recorded edits diverges on the first edited tick()[jvm]
EditingSessionReplayTest > a replay world that cannot apply edits refuses an edited recording rather than diverging()[jvm]
EditingSessionReplayTest > an edited recording is format 2 and carries every edit, and the idle cancel is one of them()[jvm]
```


M10 is the old shape of `ReplayVerifier` exactly: input only, no edits. M11 is the old encoder.

## 2. Summary

What an agent (or the editor window, #194) can now do, all through `editor.*`:

- `begin_edit(entities, fields)` opens a live edit and answers a `sessionId` and every starting
  value; `update_edit(sessionId, values)` writes as often as a drag moves, with no undo entry;
  `commit_edit` closes it as **one** undo entry whose reverse is the starting values;
  `cancel_edit` puts every starting value back exactly and records nothing. One open session per
  author; beginning another commits the first. Only the author can touch their session.
- A session idle for 30 seconds is cancelled; so is one whose author calls the new `editor.leave`.
- `set_field` takes several ids (`id=7,8,9`) as one write and one undo entry. One id answers
  byte-for-byte as before (pinned by a test).
- `select(entities, mode=replace|add|remove)` and `selection`: one selection per author, every
  author sees every selection.
- `common_fields(entities)`: each field of each component all of them carry, with its value, or
  `mixed:true`.
- Every editing call that changed something is journaled with the tick it was applied on
  (`EditorToolset.journal`), and a `.udearep` can now carry those edits (format 2) so a replay
  makes them again before the same tick.

Decisions, each also commented on #232 with the alternative and how to undo it:

1. **Author = the calling session**, not an `author` argument (a typed author could act as anyone).
2. **Idle timeout on the agent host's clock (`AgentClock`), read only by a sweep between ticks.**
   The sweep runs at the start of each `AgentBridge.drain` (outside `Simulation.step()`); a begin
   or update only marks its session touched, and the sweep stamps it. An expired session is ended
   by the sweep *submitting an ordinary `editor.cancel_edit`* as its author, so the cancel goes
   through the queue, the `SimBarrier` and the dispatcher, and is journaled like any call.
   Registration is one internal hook on `AgentBridge` (`beforeEachDrain`), so no host wiring
   changed. Tests use a manual clock.
3. **"Disconnect"**: the agent host is plain HTTP, with no connection to watch close, so a vanished
   agent is an idle one. `editor.leave` is the clean exit.
4. **Recording.** The brief said to reuse #193's path to `.udearep`; there was none - a `.udearep`
   carried input and hashes only. `.udearep` is not under `docs/contracts/` (that holds
   `agent-tools.md`, `asset-index.md`, `replicator.md`), so, per the lead, I versioned it:
   **format 2 = format 1 + an edits section** (tick, author, tool, arguments). A recording with no
   edits is still written as **format 1, byte for byte**, so every existing file and every older
   reader is unaffected. `ReplayVerifier`, `ReplaySession` and `ReplayDigestRecorder` now call one
   internal `ReplayWorld.feed`, which hands each tick's edits to `ReplayWorld.applyEdits` before its
   input. `applyEdits` defaults to refusing a tick that has edits, so an input-only replay world
   (moba's today) fails loudly on an edited file rather than diverging.
5. **List arguments are the generator's typed `List<NetId>`** (one query parameter split on `,`,
   each element parsed by generated code; a bad element is `bad_argument` naming it). The schema
   calls it a string for the reason `ToolEmitter` gives: the bridge sends query parameters, and a
   JSON array would arrive as text. `update_edit`'s values are `Component.field=value` or
   `<id>:Component.field=value` entries, parsed at the edge into typed writes with a typed error
   naming the bad entry; field names are strings everywhere else on this surface too
   (`set_field`'s `component`/`field`).
6. A commit that changed nothing records nothing (`"recorded":false`). `common_fields` uses
   `mixed:true` rather than a `"Mixed"` value a String field could genuinely hold.

Not done, and why: no production host records an editor session into a `.udearep` yet, and
moba's replay world does not implement `applyEdits` - #232's criteria name neither, and both sit
on this API. The proof records and replays through a small headless game in `udea-replay`'s tests.

Public API added, and who uses it: `SelectMode` (a tool parameter type), `EditorJournal`,
`EditorJournalEntry` and `EditorToolset.journal` (read by `udea-replay`'s proof to fill the
recording), `ReplayEdit`, `ReplayRecorder.recordEdit`, `ReplayRecording.edits`/`editsAt`,
`ReplayWorld.applyEdits` (the hook a game's replay world implements), and the format constants
and `ByteSink.text`/`ByteSource.text`, which sit beside their existing public siblings.

## 3. `sh gradlew build`

`kmp` was green at `9d6629f` per the lead (917 tasks), so there is no baseline red list. My full
run with `--continue` at `1437cc7` found one red I caused: `:udea-replay:compileTestKotlinIosArm64`
and `...IosSimulatorArm64` - Kotlin/Native refuses a comma in a test name. Fixed at `00f17eb`. The
final run, on the tree committed as 03e1baf (log `scratchpad/issue232/build4.log`):

```
BUILD SUCCESSFUL in 1m 30s
908 actionable tasks: 65 executed, 843 up-to-date
Configuration cache entry reused.
```

Most tasks were up to date: Gradle had executed them in earlier runs in this worktree with
identical inputs. The box was at load ~18 with another
developer's GL test running during the first run. No GL code is touched (`udea-render` and the
render half of `udea-agent-host` are unchanged), so no xvfb `udeaGlTest` run is claimed here.

## 4. Driven for real

`:moba:desktop:run -PdebugPort=7846 -Peditor=true` under xvfb (Offscreen), driven over HTTP by
`scratchpad/issue232/live_session.py`; every answer is in `live/transcript.jsonl`, the printed
run in `live/run.txt`. `/tools` (saved as `live-tools.json`) listed every new tool: `editor.begin_edit`, `update_edit`, `commit_edit`, `cancel_edit`, `leave`, `select`, `selection` and `common_fields`. Spliced from
`live/run.txt`:

```
time.pause {"tick": 305, "paused": true, "timeScale": 1, "totalTicks": 305}
editor.select {"author": "alice", "ids": [7, 8, 9]}
editor.select {"author": "bob", "ids": [1]}
editor.selection {"you": "bob", "authors": [{"author": "alice", "ids": [7, 8, 9]}, {"author": "bob", "ids": [1]}]}
editor.common_fields {"entities": 3, "fields": [{"component": "GameUnit", "field": "kind", "mixed": false, "value": 3}, {"component": "GameUnit", "field": "targetRaw", "mixed": true}, {"component": "GameUnit", "field": "team", "mixed": false, "value": 2}, {"component": "Position", "field": "hp", "mixed": false, "value":
editor.begin_edit {"sessionId": 1, "start": [{"id": 7, "component": "Position", "field": "x", "value": 17.1567}, {"id": 7, "component": "Position", "field": "y", "value": -3.3796}, {"id": 8, "component": "Position", "field": "x", "value": -2.4534}, {"id": 8, "component": "Position", "field": "y", "value": -5.204}, {"
editor.update_edit {"sessionId": 1, "written": 6}
editor.update_edit {"sessionId": 1, "written": 6}
editor.update_edit {"sessionId": 1, "written": 6}
editor.cancel_edit {"sessionId": 1}
editor.history {"author": "alice", "size": 0, "edits": []}
editor.begin_edit {"sessionId": 2, "start": [{"id": 7, "component": "Position", "field": "x", "value": 17.1567}, {"id": 7, "component": "Position", "field": "y", "value": -3.3796}, {"id": 8, "component": "Position", "field": "x", "value": -2.4534}, {"id": 8, "component": "Position", "field": "y", "value": -5.204}, {"
editor.update_edit {"sessionId": 2, "written": 3}
editor.update_edit {"sessionId": 2, "written": 3}
editor.update_edit {"sessionId": 2, "written": 3}
editor.commit_edit {"sessionId": 2, "recorded": true, "fields": 3}
editor.history {"author": "alice", "size": 1, "edits": [{"sequence": 1, "tool": "editor.commit_edit", "id": 7, "ids": [7, 8, 9]}]}
editor.undo {"undone": "editor.commit_edit", "id": 7, "overwrote": false, "ids": [7, 8, 9], "values": [{"id": 7, "component": "Position", "field": "x", "value": 17.1567}, {"id": 8, "component": "Position", "field": "x", "value": -2.4534}, {"id": 9, "component": "Position", "field": "x", "value": -14.2446}]}
editor.history {"author": "alice", "size": 0, "edits": []}
editor.begin_edit {"sessionId": 3, "start": [{"id": 7, "component": "Position", "field": "x", "value": 17.1567}]}
editor.update_edit {"sessionId": 3, "written": 1}
update after idle: {"id": 39, "ok": false, "error": {"kind": "no_such_edit", "message": "editor.update_edit: there is no open edit session 3; it was committed, cancelled, replaced by a later editor.begin_edit, or cancelled after 30 seconds with no update"}}
world.get_component {"id": 7, "component": "Position", "fields": {"hp": 40, "x": 17.1567, "y": -3.3796}}
expired events: []
editor.set_field {"id": 7, "ids": [7, 8, 9], "component": "GameUnit", "field": "team", "value": 2, "reverse": {"tool": "editor.set_field", "args": {"id": "7,8,9", "component": "GameUnit", "field": "team", "value": "2"}}}
editor.history {"author": "alice", "size": 1, "edits": [{"sequence": 2, "tool": "editor.set_field", "id": 7, "ids": [7, 8, 9]}]}
```

The idle case is real wall time: session 3 was begun and moved, then left for 31 seconds; the next
update got `no_such_edit`, `Position.x` is back at the starting 17.1567, and the event ring holds
`editor_cancel_edit:3:alice` although the script never cancelled session 3.

## 5. Images (in `/srv/ssd1/workspace/Udea/build/debug-screenshots/`)

- `issue232-drag-cancel-commit-undo.png` - tiles 0-6: before; three skeletons dragged together over
  three updates (1-3); after `cancel_edit` they are back (4); a second drag committed (5); one
  `undo` returns all three (6). Proves live updates, exact cancel, one-entry commit and undo.
- `issue232-idle-timeout.png` - before, one skeleton moved by a session, and the same view 31 real
  seconds later with the skeleton back. Proves the idle cancel on the host clock.
- `issue232-live-00-before.png`, `issue232-live-03-drag-step3.png`, `issue232-live-04-after-cancel.png`,
  `issue232-live-05-after-commit.png`, `issue232-live-06-after-undo.png` - the full-size frames behind
  the collage.

## 6. The issue, criterion by criterion

| Criterion | Proof |
|---|---|
| begin, update ×N, commit is exactly one undo entry; undo restores the starting values | `EditSessionTest` "begin then several updates then commit is one undo entry, and undo restores the starting values" (5 updates, 2 entities, 3 fields; history 0 then 1; field hash back to start). "beginning a second session commits the first". Live: tiles 5-6, `history` size 1 then 0. Mutation M1 |
| cancel restores every field exactly, no undo entry; idle or disconnected cancelled the same way | "cancel restores every field exactly and leaves no undo entry" (including another author's mid-session write); "a session idle for thirty seconds is cancelled and restored, and one updated in time is not" (29 s no, exactly 30 s yes); "an author who leaves has their open session cancelled and restored". Live: tile 4 and the 31-second idle run. M2, M3, M4 |
| multi-entity `set_field` is one undo entry; `common_fields` reports shared value or Mixed | "a set_field on several entities is one write and one undo entry"; "common_fields reports a shared value and Mixed where the values differ"; "common_fields leaves out a component one of the entities does not carry"; "a malformed id in a list is refused by name, and nothing is written". Live: `set_field id=7,8,9` then `history` shows one entry with `ids [7,8,9]`; `common_fields 7,8,9`. M5, M6 |
| two authors' selections are independent and each reads the other's | "two authors' selections are independent and each reads the other's" (replace/add/remove). Live: bob's `selection` lists alice's `[7,8,9]` and his `[1]`. M7 |
| replaying a recorded editing session reproduces the same world, edits applied between ticks | `EditingSessionReplayTest`: a 40-tick scripted session by two authors (session begun/updated across ticks/committed, multi-entity set_field, undo, leave, and an idle cancel from the recording's clock) is recorded into one `.udearep`, encoded and decoded, and replays bit-exact through `ReplayVerifier`. Replayed by a world that drops the edits, or applies each one a tick late, it diverges at t4, the first edited tick. `ReplayFormatTest` round-trips edits (format 2) and pins that an edit-free recording is format 1; `ReplayByteCompatibilityTest` (master's bytes) and `MasterRecordingReplayTest` (master's `drift-3600.udearep`) still pass. M8-M11 |

## 7. Regenerated files

None. No replicated component was added or removed, so `net-protocol.lock` and
`expected-generated-hashes.txt` did not move. No file under `docs/contracts/` changed.
