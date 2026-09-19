3182c27

# The #237 / #238 fold

Branch `issue-237-238-fold`, made from `origin/master` at `1f9bc7a` (#238 merged). `origin/issue-237-builtin-3d-gizmos` at `4c9b15b` was merged into it with no conflict (`1bc9aae`). The fold's own change is `3182c27`. This brief is committed after it and changes no code.

Worktree: `/srv/ssd1/workspace/Udea/.claude/worktrees/agent-a7d7f2544f68823a9`. This is my own #238 worktree with the new branch checked out, not a fresh `git worktree add`. The isolation harness refused git commands in a second worktree, so I removed that one and switched branches here.

## Evidence command

    sh gradlew :moba:desktop:editorTest --tests dev.wildware.moba.editor.MobaPlayKeepPanelTest

On `3182c27` all 4 tests pass (`scratchpad/issue238/fold-green.log`).

It goes red when #237's fix is reverted. `scratchpad/issue238/fold/red.sh` applied this diff, ran the command and put the file back. This is the diff as `red.diff` recorded it:

```
-        val results = bridge.wholeCommandResults()
+        val results = bridge.commandResults()
```

The diff is in `udea-editor/src/main/kotlin/dev/wildware/udea/editor/EditorTools.kt`, in `frame()`. The result, from `red.log` and `red.xml`:

```
4 tests completed, 1 failed
```
```
<testsuite name="dev.wildware.moba.editor.MobaPlayKeepPanelTest" tests="4" skipped="0" failures="1" errors="0" timestamp="2026-09-19T16:16:47.761Z" hostname="wild-home-server" time="0.936">
```

The failing test is `the panel lists every play edit even when the list is too long for one HTTP answer, and the inspector still pins()`, with `java.util.NoSuchElementException: Key playing is missing in the map.`, thrown at `EditorPlayEdits.frame$lambda$0(EditorPlayEdits.kt:91)`.

Why it fails that way: once the window reads the cut-down answer, it gets the `resultTooLarge` handle in place of the list. The fallback that used to catch the handle is gone, so the parse throws. That is the defect #238's fallback once guarded against. With #237 in place, nothing in the process can produce it. `git status` after the run showed only the three files of the fold, uncommitted at that point, so `EditorTools.kt` was restored.

## Summary

**The test.** The old test asserted that the panel says "too long". With #237 merged, that no longer holds, because the window now reads answers whole. The replacement test does the following:

- It starts Play, selects the tower and makes 12 `editor.set_field` edits to it.
- It checks the precondition: over HTTP, `editor.play_edits` answers `"resultTooLarge":true`.
- It reads the edit ids from the whole answer of the same command. The test's `asAnAgentBothWays` reads both `commandResults()` and `wholeCommandResults()`.
- It asserts that every one of the 12 edits has a Keep toggle in the panel.
- It asserts that the Inspector's Keep pin for `Tower.attackRange` is shown.
- It scrolls the docked window to the last edit, clicks its Keep toggle and presses Stop. It then asserts that the stopped world's range is the kept value, 171.

**Found on the way.** 12 rows are taller than the docked "Changes during Play" window. The first run failed with `#editor:play-edit-keep:12 is off the 1280x720 screen`. ComposeGL's docking already puts the window in a scroll body (`debugwindow:editor-play-edits:body`), so this was a test problem, not a product one. The test now scrolls with the wheel, as a person would, and needed no layout change.

**The fallback is removed.** These were deleted:

- `EditorPlayEdits.tooLong`, its spilled-answer branch and two imports
- the "too long to show here" branch in `PlayEditsPanel`

**What could still reach the fallback:** nothing. `EditorPlayEdits` reads `editor.play_edits` only through `EditorTools.read`. `EditorTools.frame` is the only thing that delivers those answers, and it reads `bridge.wholeCommandResults()`, which never swaps in a handle.

`grep -rn "resultTooLarge\|commandResults()" udea-editor/src/main` found only the removed line, before the change. Every other `commandResults()` caller is a test helper that plays an HTTP agent, or `/state`.

The decision is recorded on the issue: https://github.com/wildware-uk/Udea/issues/238#issuecomment-5743415300

## `sh gradlew build`

`scratchpad/issue238/fold/all.sh` ran the following on `3182c27`, detached, with melon-merge's GL run also on the box:

    sh gradlew --max-workers=6 --console=plain --no-configuration-cache build --continue

Tail of `fold/build.log`:

```
BUILD SUCCESSFUL in 6m 1s
984 actionable tasks: 240 executed, 187 from cache, 557 up-to-date
```

## GL suites under Xvfb

    xvfb-run -a -s "-screen 0 1280x720x24" env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe \
      sh gradlew --no-configuration-cache udeaGlTest --rerun udeaAgentGlTest --rerun udeaEditorGlTest --rerun -Pudea.render.requireGl=true

From `fold/gl.log`:

```
> Task :udea-render:udeaGlTest
```
```
> Task :udea-agent-host:udeaAgentGlTest
```
```
> Task :udea-editor:udeaEditorGlTest
```
```
BUILD SUCCESSFUL in 2m 40s
```

The result XMLs were written at 16:24, during this run:

| Suite | Tests | Failures | Skipped |
|---|---|---|---|
| `udea-render` `udeaGlTest` | 23 | 0 | 0 |
| `udea-agent-host` `udeaAgentGlTest` | 2 | 0 | 0 |
| `udea-editor` `udeaEditorGlTest` | 6 | 0 | 0 |

## Images

None. The change removes a text branch nobody can reach, and it replaces a test. The panel's look is the one #238 was reviewed with.

## Criteria (the lead's list)

1. The new branch comes from `origin/master` `1f9bc7a`, with `origin/issue-237-builtin-3d-gizmos` `4c9b15b` merged in (`1bc9aae`). Proof: `git log`.
2. The test proves every play edit past the cap is listed, and that the Inspector pins still show. It fails with #237's fix reverted. Proof: the evidence command above, green and red.
3. The unreachable fallback is removed, and nothing reaches it. See the Summary.
4. The full build and the GL suites are green. See the two sections above.

## Regenerated files

None. No replicated component changed.

All scratch paths are under `/tmp/claude-1000/-srv-ssd1-workspace-Udea/1ad8c5e6-2def-4055-91d2-72acdfe77daf/scratchpad/`.
