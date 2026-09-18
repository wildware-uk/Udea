# BRIEF: issue #230, letter keys and focused ComposeGL controls

**SHA:** `b3d8eb8` (the code under review. The commit on top of it adds only this file.)

Branch `issue-230-keytable-letters`, cut from `origin/kmp` at `db302a3`. Since then `origin/kmp` has
moved by three commits, all of them `.claude/WAVE.md` only (`git diff --stat db302a3 origin/kmp`).
Worktree `/srv/ssd1/workspace/Udea/.claude/worktrees/agent-a5ecd4c2831637278`.
All artefacts named below are under
`/tmp/claude-1000/-srv-ssd1-workspace-Udea/1ad8c5e6-2def-4055-91d2-72acdfe77daf/scratchpad/issue230/`,
called `$A` from here on.

## 0. The short version

- **The defect the issue names does not exist on the Kool we build against.** On kool-core
  **0.19.0**, `UniversalKeyCode(Char)` *uppercases*, so the table keyed W at 87, which is what GLFW
  sends. The as-merged code passes the new all-keys test through Kool's real GLFW callback. On Kool
  `main` the same constructor *lowercases*, so the defect would have arrived on the next Kool
  upgrade. The table now uses the codes GLFW sends and never calls that constructor.
- **The player-visible symptom the issue describes was real, from a different cause.** ComposeGL's
  real `TextField` takes the *character* a keystroke types and never the letter's key-down. So
  typing "w" into a focused field also pressed W for the game. This is fixed in `KoolKeyboard`.

## 1. The evidence command

```
cd /srv/ssd1/workspace/Udea/.claude/worktrees/agent-a5ecd4c2831637278 && \
xvfb-run -a -s "-screen 0 1280x720x24" \
  env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe \
      ANDROID_HOME=/home/shaun/Android/Sdk \
      JAVA_HOME=/home/shaun/.sdkman/candidates/java/21.0.11-tem \
  sh gradlew :udea-render:jvmTest --rerun :udea-render:udeaGlTest --rerun \
    -Pudea.render.requireGl=true --continue --console=plain
```

`$A/evidence.sh` holds exactly that. `--rerun` is needed because both tasks are cacheable, and
`-Pudea.render.requireGl=true` turns "no display, skip" into a failure.

**Green at `b3d8eb8`.** From `$A/evidence-green-b3d8eb8.log`:

```
> Task :udea-render:jvmTest
> Task :udea-render:udeaGlTest
...
BUILD SUCCESSFUL in 47s
142 actionable tasks: 13 executed, 129 up-to-date
```

**Red with the table keyed lowercase**, which is what the next Kool's constructor produces
(acceptance criterion 3). The diff is `$A/evidence-red-m1.diff`:

```
-        ('A'..'Z').forEach { letter ->
-            put(letter.code, Key(Key.A.code + (letter - 'A')))
+        ('a'..'z').forEach { letter ->
+            put(letter.code, Key(Key.A.code + (letter - 'a')))
```

From `$A/evidence-red-m1.log`:

```
GlKoolInputTest > a Kool key becomes an intent, and one the interface takes does not() FAILED
...
11 tests completed, 1 failed
...
> Task :udea-render:udeaGlTest FAILED
...
BUILD FAILED in 59s
```

The failure message is in `$A/evidence-red-m1-TEST-GlKoolInputTest.xml`. It names all 26 letters
and nothing else. The message starts with the upgrade guidance: *"If only letters are wrong after
a Kool upgrade, look first at `UniversalKeyCode(Char)`: Kool 0.19.0 uppercases the character and
Kool main lowercases it (KeyCode.kt line 16)..."*. Next come fifty-two lines like these two:
`A: GLFW key 65 reached the focused control as [Key.Unknown], not [Key.A]` and
`A: the focused control was offered it and it became an intent anyway`.

## 2. Summary

### What was measured before any change

- `javap -c` of `UniversalKeyCode(char)` in kool-core-desktop-0.19.0.jar and in the 0.19.0
  Android aar. Both call `java/lang/Character.toUpperCase:(C)C`. The dumps are
  `$A/javap-UniversalKeyCode-desktop-0.19.0.txt` and `$A/javap-UniversalKeyCode-android-0.19.0.txt`,
  line 31 of each. `gradle/libs.versions.toml` pins `kool = "0.19.0"`.
- Kool `main` (`/srv/ssd1/workspace/kool-spike-225/kool/kool-core/src/commonMain/kotlin/de/fabmax/kool/input/KeyCode.kt:16`)
  has `constructor(codeChar: Char) : this(codeChar.lowercaseChar().code)`. So the case flips
  between releases. `GlfwInput.kt:146` still passes the raw GLFW key on `main`.
- The new all-keys test (commit `b4b2e59`) was run against the table as merged, before any fix. It
  **passed**: `$A/red-before-fix.log`, `exit=0`. It is not vacuous. Keying the merged table on
  `letter.code` over `'a'..'z'` turns it red on exactly the 26 letters:
  `$A/mutations/m0-on-merged-code-letter-code/`.
- A throwaway probe put a real ComposeGL `TextField` on screen and drove it through GLFW's key and
  character callbacks. The probe diff is `$A/probe-textfield.diff` and was never committed. From
  `$A/probe-textfield-TEST-GlKoolInputTest.xml`:
  `PROBE230 field text='w' W intent presses=1 codes offered=[87, 119, 87]`. So the field got the
  letter, and so did the game.

### What changed (udea-render only)

- **`KeyTable`**: letters over `'A'..'Z'`, and digits, space and punctuation, are keyed by
  `Char.code`, which is the code GLFW sends. `UniversalKeyCode(Char)` is gone from the file. The KDoc
  now says what Kool reports, and why the constructor must not be used.
- **`KoolKeyboard.onKeyEvents`**: a key-down the interface declined is held back for one event. If
  the next event is a character and the interface takes it, the key was typing. It is marked
  consumed and not recorded. In every other case it is recorded as before, and a key still held at
  the end of the frame's list is recorded too. The class KDoc has a new section on this.
- **`DeviceState.kt`** (the `KeyboardState.isKeyDown` KDoc): `'W'.code` (87) instead of
  `'w'.code`.
- **Tests**: `GlKoolInputTest` gains phases 4 to 7 (section 4 below). `KoolKeyboardOrderTest` gains
  five unit tests of the typing rule, and its W fixture is now `'W'.code` rather than going through
  `UniversalKeyCode('w')`.

### Decisions, and what was rejected

1. **Key the table on what GLFW sends.** I rejected keeping `UniversalKeyCode(letter)`: it is right
   on 0.19.0 and wrong on Kool `main`. I also rejected lowercasing incoming codes before the lookup,
   as the dispatch ruled out.
2. **Fix the text-field symptom here, in `KoolKeyboard`.** The lead directed this, and the owner's
   rule is no new issues. I rejected suppressing printable keys whenever anything is focused,
   because a focused button would then swallow movement. The rule follows the interface's answer to
   the character. The alternative is a ComposeGL change that makes `TextField` take a letter's
   key-down itself. That is the place to go if the owner prefers it.
3. **The one-event hold stays within one frame's list.** It relies on GLFW reporting the character
   straight after its key in the same poll. Kool's `KeyboardInput` queues both, in arrival order, on
   one list (`handleKeyEvent` and then `handleCharTyped` on `queuedKeyEvents`). The GL test drives
   both callbacks in one render-thread call, the way one poll would. I have not measured a real
   OS keyboard doing this on other platforms.
4. **No new issue** was filed for the text-field finding, per the owner's rule relayed by the lead.
   Both decisions are recorded on #230 (comment 5737002086).

### What is not covered

- IME and dead keys: a key-down with no character after it is recorded as a key, as before.
- Shift held while typing: Shift itself still reaches the game, because no character follows it.
  Only the key that typed the character is withheld.
- Android: `kool-core-android` 0.19.0 has no platform key dispatch (no `handleKeyEvent` caller in
  the aar), so there is nothing to drive there. iOS is not a target of this module and cannot build
  on this box.
- **No images.** The interface layer draws to the window framebuffer, which `FrameCapture` never
  reads (`UiLayer` KDoc, `GlUiLayerTest`), and this is an input fix with nothing new to see. The
  evidence is the executed test transcripts.

## 3. The build

### `sh gradlew build --continue` at `b3d8eb8`

Command: `$A/fullbuild.sh`, i.e.
`ANDROID_HOME=/home/shaun/Android/Sdk JAVA_HOME=/home/shaun/.sdkman/candidates/java/21.0.11-tem sh gradlew build --continue --console=plain`.
From `$A/build-b3d8eb8.log`:

```
> Task :moba:compileKotlin FAILED
...
* What went wrong:
Execution failed for task ':moba:compileKotlin'.
...
BUILD FAILED in 1m 35s
761 actionable tasks: 450 executed, 184 from cache, 127 up-to-date
```

`grep -n FAILED` on that log returns two lines: 1422 `> Task :moba:compileKotlin FAILED` and 1491
`BUILD FAILED in 1m 35s`.

- **Baseline failures, unchanged:** `:moba:compileKotlin`. This is the authorised D9 red: moba
  still draws with LibGDX until #212. I did **not** run my own baseline build before the first
  change. The baseline is the lead's (`18bb13f`: exactly this one task). `db302a3` differs from
  `18bb13f` only in `.claude/WAVE.md`.
- **Tasks this ticket turned green:** none. No task was red for this ticket's reason: the table
  defect was latent, and the text-field defect had no test until now. What the ticket adds is new
  coverage inside `:udea-render:jvmTest` and `:udea-render:udeaGlTest`.
- `:udea-assets-compiler:udeaDaemonBudget` did not fail in this run.
- In this plain build, `udeaGlTest` ran with no display and so skipped. The real GL run follows.

### The GL run, for real under xvfb

`$A/gltest.sh`, i.e. the dispatch's command:

```
xvfb-run -a -s "-screen 0 1280x720x24" \
  env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe ANDROID_HOME=/home/shaun/Android/Sdk \
    JAVA_HOME=/home/shaun/.sdkman/candidates/java/21.0.11-tem \
  sh gradlew :udea-render:udeaGlTest --rerun -Pudea.render.requireGl=true --console=plain
```

`$A/gl-after-fix.log`: `BUILD SUCCESSFUL in 1m 3s`, `exit=0`. Right after it, the test-results
XMLs gave `tests="1" skipped="0" failures="0"` for `GlKoolInputTest`, and zero skipped and zero
failures for all ten GL suites. That listing is in this session's transcript. The XMLs themselves
were overwritten by the later mutation runs. The evidence command's green run above is the
reproducible form.

## 4. The acceptance criteria

| Criterion | Proof |
|---|---|
| A real GLFW letter press, through Kool's installed key callback, reaches a focused ComposeGL control as the right `Key`, is taken by it, and never becomes an intent | `GlKoolInputTest` phase 4: `KeyRecordingScreen`, where a panel with a focused button takes every key but `Key.Unknown`. Each key goes through `glfwSetKeyCallback`'s previous callback, and for each one the test checks the `Key` seen and that the intent's `pressCount` is 0. Phase 5 does the same for typing into a real `TextField`: field text must be `abcdefghijklmnopqrstuvwxyz`, and no letter intent or held key is allowed. Green in the evidence run |
| All 26 letters | `LETTERS` in `GlKoolInputTest`, written out by name on both sides (`GLFW_KEY_A` to `Key.A` ...), so the expected values share nothing with the table |
| The negative: fails if the table is keyed lowercase, proven by mutation | M1 below, and the evidence command's red run in section 1. Note: "restore `UniversalKeyCode(letter)`" as the issue phrases it is **green** on 0.19.0, because that constructor uppercases there. The merged code passed (`$A/red-before-fix.log`). The mutation that reproduces the failure mode is keying on the lowercase char, which is what Kool `main`'s constructor produces |
| KDocs repeating "`'w'` is 119" say what is true | `KeyTable.kt` class KDoc, and `DeviceState.kt` `isKeyDown` KDoc. Class sweep with `git grep -e "UniversalKeyCode('" -e "is 119" -e "'w'.code" -e lowercaseChar` outside the old tree: the other hits are `.claude/WAVE.md` (the lead's, corrected by the lead in `c2748ed`), `BRIEF-224.md` (a historical record, left alone), and in `GlKoolInputTest` two places. One is its negative control, `isKeyDown('w'.code)` must be false, and I corrected that message. The other is `Case.typed`, the character GLFW's character callback reports, which is lowercase with no shift held |
| An unfocused letter still reaches the game (dispatch) | Phase 7: `ui.show(null)`, then all 26 typed with key and character. Each must be exactly one intent. Phase 1 (from #224) still passes |
| A focused button does not swallow a letter (lead) | Phase 6: the focused-button screen, all 26 typed with key and character. Each must be exactly one intent |

### Mutation table

Each row has its literal diff in `$A/mutations/<row>/mutation.diff`, its logs in `gl.log` and
`unit.log`, and its report XMLs beside them. Every one was reverted with `git checkout --
udea-render`, and the tree was clean afterwards.

| Row | Mutation | GL (`GlKoolInputTest`) | Unit (`KoolKeyboardOrderTest`) |
|---|---|---|---|
| `m0-on-merged-code-letter-code` | on the **merged** table: `put(UniversalKeyCode(letter).code, ...)` becomes `put(letter.code, ...)` over `'a'..'z'` | red: A..Z each `[Key.Unknown]` and "became an intent anyway". Digits, punctuation and special keys pass | not run |
| `m1-table-keyed-lowercase` | `('A'..'Z')` / `- 'A'` becomes `('a'..'z')` / `- 'a'` | red, same 26 letters and nothing else | 13 run, 0 failed (the unit tests do not touch the table) |
| `m2-no-typing-rule` | `onKeyEvents` restored to the #224 loop | red: `typing into a focused text field also pressed [A, B, ..., Z] for the game` | 3 failed: `a key whose character a text field took never reaches the keyboard`, `a key held down in a text field never becomes held for the game`, `a character only speaks for the key immediately before it` |
| `m3-swallow-whatever-the-ui-said` | `if (taken && event.isCharTyped)` becomes `if (event.isCharTyped)` | red: `with a button focused, [A, ..., Z] did not become exactly one intent each` | 1 failed: `a key whose character the interface declined still reaches the keyboard` |
| `m4-drop-a-key-at-the-end-of-the-frame` | `typing?.let(::record)` deleted | red at phase 1: `W never became an intent. The binding is on 87; ...` | 3 failed: `a key with nothing after it in the frame is recorded`, `a press the interface declined becomes an intent`, `a key the interface declines still reaches the keyboard` |

Before the typing fix existed, the new unit tests were run against the #224 loop:
`$A/unit-red-before-fix.log`, `13 tests completed, 3 failed`, the same three as M2. The new GL
phases were also run before the fix: `$A/gl-red-before-fix.log`. That red run was on the text-field
phase, and the table phase passed, because the table was already right on 0.19.0.

## 5. Line 86's punctuation (now line 93) and the other groups

Nothing was wrong there on 0.19.0, and there is proof either way. `-`, `=`, `[`, `]`, `\`, `;`, `'`,
`` ` ``, `,`, `.` and `/` have no case, so `UniversalKeyCode(char)` returned the character's own
code under either case rule. That is also GLFW's constant for each key (`GLFW_KEY_MINUS` is 45, and
so on). Measured: phase 4 presses every punctuation key, every digit, space, F1-F12, the cursor
keys, Home/End/PageUp/PageDown, Enter and keypad Enter, Escape, Tab, Backspace, Delete, Insert and
both of each modifier. It passed on the merged table (`$A/red-before-fix.log`). Under M0 and M1
only the letters fail. The group now uses `char.code` as well, so the file no longer depends on
the constructor at all.

## 6. Regenerated files

None. No replicated component changed, so neither `net-protocol.lock` nor
`expected-generated-hashes.txt` moved.
