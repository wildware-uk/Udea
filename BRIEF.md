# windows-launch - every launch started on Windows, and one command per game to play it

07f26fc3

That is the SHA every run, every count and every log line in this document was taken at. The only
commit after it is the one that adds this file, which changes no code; `git log --oneline` on the
branch shows it as the tip.

Branch `windows-launch`, from `origin/master` at `9938829f` and merged with `origin/master` at
`483cb10e` (commit `51d70007`, the soak-test rule and nothing else). Worktree
`/srv/ssd1/workspace/Udea/.claude/worktrees/agent-a5f5a4a39322945ab`. No issue tracks this: the
owner's request is the ticket, and each decision is in section 3.

**Two developers wrote this branch.** `dev-winlaunch` wrote commits `80e96a09..1d42304b` and was cut
off by a model upgrade with `BRIEF.md` uncommitted; `dev-winlaunch2` verified that brief against the
artefacts it cites (every transcript in it was re-checked against the file on disk that produced it -
section 5.6 says what was checked and what it found), merged `master`, added the soak test #275's
rule asks for (3.9, 5.5), and ran everything in section 4 again on the merged tree.

**The sentence.** Every way a developer or a player starts `moba`, `hollow` or the new-game
template now launches on Windows, draws, connects and exits - and a CI job proves it on every push,
with one PNG per launch. **Nothing in the games was broken on Windows**; what was missing was a
driver on the runner and a check that launches anything at all. Two real defects turned up on the
way, and both are OS-independent (section 3.4, 3.5).

---

## 1. The evidence command

The Windows half is CI, because this box is Linux:

**Run `35766809941`, jobs `windows-launch` and `windows-launch (template)`, at the final SHA
`07f26fc3`** - `https://github.com/wildware-uk/Udea/actions/runs/35766809941`. Both green, and the
step's own last lines are `11 of 11 launches came up` and `2 of 2 launches came up` (from
`gh run view 35766809941 -R wildware-uk/Udea --log`). 11 launches plus the template's 2, each with
its log, and 12 PNGs in the `windows-launch-frames` artefact.

The run at `1d42304b`, `35763132162`, is the same jobs green one commit earlier, and is where the
images in section 6 were captured; the two runs' `hollow-host-join-*.png` are byte-identical
(`35fdc564...` in both), which is the engine being deterministic across runs and machines rather
than a file reused.

**It goes red when a launch is broken.** Proven, not asserted: throwaway branch
`windows-launch-break` (SHA `a4418934`, pushed, run **`35761395752`**, now deleted from the remote -
`git ls-remote --heads origin windows-launch-break` prints nothing) carried one line:

```
-            "local" -> local(mode)
+            "local" -> error("deliberate break: the windows-launch job must go red on this")
```

`windows-launch` failed on it and named exactly the two launches that use `local`, with the other
nine green - spliced from that run's `summary.txt` (`windows-launch-frames` artefact of run
35761395752):

```
FAIL moba-play: playMoba
ok   moba-run: :moba:desktop:run -PdebugPort=51179
ok   moba-editor: :moba:desktop:runEditor -PdebugPort=51180
FAIL moba-client-local: :moba:desktop:runClient --args=local
ok   moba-client-listen: :moba:desktop:runClient --args=listen
[... 6 lines elided: moba-host-join, moba-server, hollow-play, hollow-run, hollow-server-client, hollow-host-join, all `ok` ...]
  problem: moba-play: exited with status 1
  problem: moba-play: no '[udea-render] GL_RENDERER=' line: no context came up
  problem: moba-play: no frame was written to moba-play.png
```

and the job's own last line, from `gh run view 35761395752 --log`:

```
windows-launch	Launch every game task and check each came up	2026-09-22T17:46:16.4556964Z 9 of 11 launches came up; failed: moba-play, moba-client-local
```

**On this box**, the same check and the same engine change are run by:

```
flock -o scratchpad/lead/fullbuild.lock \
  xvfb-run -a -s "-screen 0 1280x720x24" env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe \
  python3 scripts/launch-check.py --expect-renderer llvmpipe --out build/reports/udea/launch
```

**11 of 11 came up here too**, at this SHA, in 4m29s - section 4.3 has the whole output - **and it
goes red here too**: section 4.8 runs the same one-line break on this box and the check answers
`0 of 1 launches came up; failed: moba-play`. So the evidence command's ability to fail is shown on
both operating systems rather than only on a CI branch that no longer exists. The engine's own
launch probe has unit and GL tests that go red when it is neutralised (section 5), and the soak
that #275's rule asks of `drive` has its own mutation (5.5).

---

## 2. What changed, file by file

| File | What it does |
|---|---|
| `udea-render/.../backend/LaunchProbe.kt` | The launch check's request to a running game: `UDEA_LAUNCH_PNG`, `UDEA_LAUNCH_FRAMES`, `UDEA_LAUNCH_STOP_FILE`, read from the environment. Plus `GlInfo`. |
| `udea-render/.../backend/KoolThread.kt` | Reads `GL_RENDERER` and `GL_VERSION` on the render thread when the context comes up, and prints them once. |
| `udea-render/.../backend/KoolBackend.kt` | `drive` counts frames; with a probe, a thread captures the frame, writes the PNG and closes the window - at once, or when the stop file appears. |
| `scripts/launch-check.py` | The check: launches every task a person types, and holds each to a frame, a `/health`, a connection and a clean exit. |
| `.github/workflows/windows-launch.yml` | `windows-launch` and `windows-launch (template)` on `windows-latest`, over a pinned, checksummed Mesa llvmpipe. |
| `moba/desktop/build.gradle.kts` | `play`; `runServer` forwards `-Pudea.net.ticks`. |
| `hollow/desktop/build.gradle.kts` | `play`. |
| `build.gradle.kts` | `playMoba`, `playHollow`. |
| `udea-render/.../gl/GlDriveSoak.kt`, `GlDriveSoakTest.kt`, `GlWindowedDriveSoakTest.kt` | A game left driving for sixteen seconds of real frames, Offscreen and Windowed: what #275's rule asks of a changed public hook (3.9, 5.5). |
| `moba/game/.../MobaShaderAssetTest.kt` | Compares the `.frag` with its line endings made LF, as the pack makes them (section 3.5). |
| `README.md`, `docs/wiki/Home.md`, `docs/wiki/Getting-Started.md`, `AGENTS.md`, `.github/workflows/ci.yml` | How to play, and where the Windows job lives. |

No generated file moved: no component was added or removed, so `net-components.lock`, every
`net-protocol.lock`, `expected-generated-hashes.txt`, both `.udearep` fixtures and
`test_level.roster.txt` are untouched (section 8).

---

## 3. The decisions

### 3.1 Mesa's software OpenGL, installed as the system ICD

`windows-latest` exposes the GDI generic renderer, OpenGL 1.1, which Kool refuses - so before this
branch no Windows machine in CI could open a window at all.

**Decided:** install `libgallium_wgl.dll` from **mesa-dist-win 26.1.8 release-msvc**, pinned by
version *and* by the SHA-256 GitHub records for that asset
(`4c6d32e653e0ff9ad07796e40c0bcfabf2764d849e3ce4f3b1590112c87e42f9`, checked in the job before the
archive is opened), copied into `System32` as `mesadrv.dll` and registered under
`HKLM\...\OpenGLDrivers\MSOGL`. That is what mesa-dist-win's own `systemwidedeploy.cmd 1` does.

**Rejected:** (a) `ssciwr/setup-mesa-dist-win`, the maintained action - it runs that same script but
verifies **no checksum**, and a third-party action is a second supply chain; (b) running
`systemwidedeploy.cmd` itself - it re-launches itself elevated when it is not admin and its
non-elevated branch **exits 0 having installed nothing**, which is a silent no-op wearing the
costume of a success; (c) dropping the DLL next to `java.exe` - which `java.exe` a Gradle toolchain
picks is not ours to know, and the ICD registration covers every JVM on the machine.

**To overturn:** replace the step with `uses: ssciwr/setup-mesa-dist-win@v3`, or move the version
pin. If a newer Mesa is wanted, change `MESA_VERSION` **and** `MESA_SHA256` together; the job fails
loudly if they disagree.

### 3.2 `play`, named per game

**Decided:** `:moba:desktop:play` (an alias of `runClient`'s default `local` mode) and
`:hollow:desktop:play` (an alias of `run`), with root aliases `playMoba` and `playHollow`. Documented
in `README.md`, the wiki home, Getting Started and `AGENTS.md`, with the Windows spelling
(`gradlew.bat playMoba`) beside the POSIX one.

**Rejected:** a bare root `play`. Gradle matches a task name in **every** project, so `./gradlew play`
would open both games at once, and a root task of that name cannot intercept it. Also rejected: a
second `JavaExec` per game - an alias cannot drift from the task it aliases, and `run` keeps its name
because `gamebridge.json`'s `launch.command` and `UdeaAgentPlugin` both name it.

**To overturn:** make `play` a `JavaExec` of its own if it ever needs different arguments from
`runClient`; the names in the four documents are the contract, not the aliasing.

### 3.3 The probe is asked for through the environment, and only ever by a check

**Decided:** `UDEA_LAUNCH_PNG` / `_FRAMES` / `_STOP_FILE`, read once in `udea-render`. Every launcher
is a Gradle `JavaExec`, which passes Gradle's environment to the forked game JVM, while a `-D` on the
Gradle command line stops at Gradle's own JVM. So one variable reaches every launcher in this
repository *and* a game in its own repository, with no build script forwarding anything. Unset - as
it is on every ordinary run - nothing is different.

Verified rather than assumed, because the configuration cache is on: two runs of
`:moba:desktop:runClient` with different `UDEA_LAUNCH_PNG` values, the second reporting
`Configuration cache entry reused.`, each wrote its frame to **its own** path (section 4.4).

**Rejected:** a `-P` property forwarded by each launch task - it would need an edit in `moba`,
`hollow`, the template and every future game, which is the coupling this avoids.

**To overturn:** the probe is one class and one call site; a `-P` forwarding could be added beside it
without changing the check.

### 3.4 `:moba:desktop:runServer -Pudea.net.ticks=N` - a documented bound that was unreachable

`MobaServer`'s KDoc has always said `-Dudea.net.ticks=N` stops it after N ticks. `JavaExec` forks, so
a `-D` given to Gradle never reached the game: the only way to stop the task was to kill it. Fixed by
forwarding a Gradle property. Not Windows-specific; found by needing a bounded server to check.

### 3.5 `MobaShaderAssetTest` was red on Windows whenever it actually ran

Found because this branch changes `udea-render`, which changes `:moba:game:jvmTest`'s inputs, so
Windows CI **executed** it instead of restoring it from the build cache. On `master`'s last Windows
build that task reads `> Task :moba:game:jvmTest FROM-CACHE` (`masterwinbuild.log`, run 35705929271,
job 106674719980) - the red was there and cached over.

**Independently confirmed, on another branch.** The developer working #244 diagnosed this same
failure down to the same two lines and proposed the same one-line fix, without seeing this branch
(reported by the lead). It also measured the Windows leg: `build (windows-latest)` is red on
`master` at `483cb10e` for **three** tests, and red on its own branch for **one** - the survivor
being this shader test. So the three Windows reds are two of #244's (the moba replay tests, which
need Assimp natives) and this one, and this branch fixes the third; when both branches are in,
`build (windows-latest)` should be green for the first time. That is a prediction about the pair,
not a claim about this branch alone: on **this** branch the two Assimp reds are still there, are
not mine, and 4.7 says which they are.

The cause is the one `windows-crlf-shaders` left open at the *test* end: the pack normalises a
shader's line endings to LF so the asset graph hash cannot move, and a Windows checkout has CRLF
files, so comparing the file's raw text to the packed text fails. The test now compares with the
endings made LF and every other byte still compared. **Reproduced on Linux** by converting the
`.frag` to CRLF (section 5.3), which is the only way this box can see it.

### 3.6 A workflow file of its own

`ci.yml`'s header says later work adds jobs there rather than a second workflow. This is the stated
exception, and `ci.yml`'s header now says so in four lines: a red run here should say "a Windows
launch broke" and nothing else; the job needs a driver installed before anything runs; and its own
file keeps it out of `ci.yml`'s cancel group, so pushing a `ci.yml` change does not cancel a launch
run that is half done. It also merges cleanly beside another branch editing `ci.yml`.

**To overturn:** the two jobs move into `ci.yml` unchanged; nothing in them depends on the file.

### 3.7 The template is a second job

It builds against a **published** engine, so it needs `publishToMavenLocal` for the engine and for
`build-logic` first - a second full build. Keeping it separate means the 11 game launches do not wait
for it. It runs headless because the template has no window yet.

**#269 (the template draws) has not merged** as this is written (`origin/master` is `483cb10e`). When
it does, the template gains a window and `template-window` is one row in `launches()` plus one line
in the template job; `--only` already names rows individually.

### 3.8 What the probe does when it fails, and why that is not a swallowed exception

If the capture throws - no capturable pass, a render loop that died mid-frame - the probe prints the
exception **and its stack trace** to stderr and then closes the window anyway. It is a report and a
decision, not a swallow: the alternative is a launcher left open, which turns a failed check into a
hung one, and the check fails regardless because the PNG it waits for is not there. The path is
reachable only when `UDEA_LAUNCH_PNG` is set, which is never on an ordinary run.

One probe per backend: `drive` may be called more than once (the editor re-drives), and the second
call keeps counting toward the probe the first started rather than starting a second.

### 3.9 The soak test #275's rule asks for: it applies here, to `drive`, and not to `play`

The rule (`.claude/agents/reviewer.md` at `483cb10e`, merged into this branch as `51d70007`, so it
is binding on it): a branch that **adds or changes a public hook a game calls** must prove a game
can live with it - 15 s or more of real frames, in each mode, still alive and still drawing at the
end, through the same public call a game makes.

**Decided: it applies, to `KoolBackend.drive`.** Both public overloads change on this branch - the
frame callback is wrapped in a counter, and the environment is read for a probe whose whole job is
to *close the window*. That is #275's shape exactly: a change to the call every launcher makes
(`MobaLaunch`, `HollowShot`, `runEditor`, every shot main), whose failure mode is a pipeline that
quietly stops. So `GlDriveSoakTest` and `GlWindowedDriveSoakTest` drive a game through
`backend.drive(host)` for sixteen seconds - twice the eight #275's games survived - asserting every
second that the loop is running and that frames were drawn *during that second*, and capturing a
frame at the end so "alive" is not mistaken for "drawing". Section 5.5 has the run and the mutation.

**Decided: it does not apply to the `play` tasks or to `scripts/launch-check.py`.** `play` is a
Gradle task alias with no code in it - `dependsOn("runClient")`, `dependsOn("run")` - so there is no
hook for a game to call and nothing that could outlive a frame count; what it does is proved by
`moba-play` and `hollow-play` launching for real on Windows, each drawing at frame ~123 and exiting
0. The launch check is a script, outside every game's classpath. The `LaunchProbe` itself is
`internal` to `udea-render` and reachable only through an environment variable, so it is not a
public hook either - but the `drive` it hangs off is, which is why the soak is written against
`drive`.

**Alternative rejected:** reading the rule as "new hooks only" and shipping no soak. The rule says
"adds or changes", the change here is in the render loop's own callback path, and #275's cost was
precisely a hook that existed and was believed safe. **To overturn:** delete the two test classes
and `GlDriveSoak.kt`; nothing else refers to them.

### 3.10 Pushing the merge cancelled the CI run that was still going at `1d42304b`

`ci.yml` has `cancel-in-progress` on the ref, and run `35763132151` (CI at `1d42304b`) was still
`in_progress` when this branch was pushed at `07f26fc3`. It was cancelled by that push. Said here
rather than left to be noticed: the CI evidence in 4.7 is the run at the **final** SHA, which is a
superset of what the cancelled one would have said, and the `Windows launch` run at `1d42304b`
(`35763132162`) had already finished green and is untouched.

---

## 4. What was run, and what it said

**How these runs shared the box.** The shared build lock
(`scratchpad/lead/fullbuild.lock`) is governed by **hold length, not invocation count** (the rule
agreed across both workspaces on 2026-09-22): a hold may cover several Gradle invocations provided
the whole hold is about 25 minutes or less, and a longer one is declared in advance with its
measured length. Every hold below is well inside that, measured on this box, on this branch:

Short single-task runs go in a **second lane** (`scratchpad/lead/lightrun.sh`, `light.lock`),
which refuses to start under an 8 GB memory floor and hard-caps at five minutes, so they do not
queue behind a full build: the two soak classes (5.5) and the launch check's negative control (4.8)
ran there, and a full build never does.

**Two exit statuses from that lane are not results**, and both matter most for a negative control,
where "it failed" is the outcome being looked for: **75 is a skip** - the memory floor refused it and
nothing ran at all - and **124 is the five-minute cap**, a kill rather than a red. Neither was
recorded as the control failing; each run below is quoted with the marker line that says which it
was, and the soak's is `EXIT=0 ... lane=light other_lane=held`, an execution.

| Hold | Measured length |
|---|---|
| the three GL suites, `--rerun-tasks` | 2m45s (`dev-winlaunch/log/glall.marker`, 17:51:26Z -> 17:54:11Z) |
| `clean build`, no build cache, no exclusions | 8m53s (`buildclean.marker`, 18:08:24Z -> 18:17:17Z) |
| the two soak classes | about a minute (the Gradle run in `M4.log` is 51s) |
| `scripts/launch-check.py`, all eleven launches | 4m36s warm (`lc3.marker`, 17:06:30Z -> 17:11:06Z); 5-9 minutes cold, which is what was declared |

Nothing needed a declaration. The runner is `scratchpad/dev-winlaunch2/run-steps.sh`, which takes
the lock per step rather than batching - allowed, and it was already queued when the rule changed,
so it was left alone rather than killed and reshuffled. Each step writes a marker carrying the time
it started *after* acquiring the lock, the worktree it ran in and its exit status, because a marker
is evidence only about the run that wrote it.

**`scripts/launch-check.py` (4.3 and 4.8) is one hold with nothing else inside it**, by the lead's
ruling: it drives Gradle eleven times as a single logical run, and splitting it would let another
agent's build land in the middle and leave the check proving a tree that no longer exists. The two
launch-check runs are two separate acquisitions.

**Every `flock` here is `flock -o`, and that is not decoration.** Without `-o`, the lock's file
descriptor is inherited by everything the command starts, and `flock` locks the open file
*description* - so a process that outlives the job keeps the lane held after the marker says
`EXIT`. What can inherit it is a **shell-started descendant**: the `Xvfb` that `xvfb-run` leaves
behind, or one of the eleven games the launch check starts, if one outlived its launch. What
**cannot** is a Gradle or Kotlin daemon: those are started from Java, whose launcher closes every
descriptor from 3 up before `exec` (measured by the lead across the whole box, and by the other
workspace, on 2026-09-22). The diagnosis for a lane that looks held with nobody in it is
`fuser -v <lock>` - anything in that list that is not a `flock` is the bug, identified by
`readlink /proc/<pid>/cwd` rather than by grepping argv - and the target is that stranded
descendant. **Never a daemon:** the Gradle and Kotlin daemons are pooled across both workspaces on
this box, so killing one to clear a lane kills another project's build.

**A second hazard, worth the next person's attention:** killing the *outer* `flock` of a
`flock <lock> sh -s <<'INNER'` releases the lock while the **inner** shell carries on. For about a
minute this branch's second step therefore ran unlocked, and the fix was to kill the whole process
group (`kill -- -<pgid>`, cmdlines read from `/proc` first) rather than the two processes that
looked like the run. Reported to the lead at the time, and said here because the shape is inviting:
the thing that holds the lock and the thing that does the work are two different processes.

### 4.1 `sh gradlew build`, clean, no build cache

Command, one hold of the full lane, `flock -o`, no exclusions and no caches:

```
ANDROID_HOME=$HOME/Android/Sdk JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem \
  sh gradlew clean build --continue --no-daemon --max-workers=4 --no-configuration-cache --no-build-cache
```

```
BUILD SUCCESSFUL in 8m 19s
1212 actionable tasks: 1090 executed, 122 up-to-date
```

Marker `build.marker`, whose `HEAD` was read **inside** the hold, so it is the tree that was built
rather than the tree at some later moment:

```
START 2026-09-22T20:01:00Z
LANE full
WORKTREE /srv/ssd1/workspace/Udea/.claude/worktrees/agent-a5f5a4a39322945ab
HEAD 07f26fc3
RUN build
EXIT 0
END 2026-09-22T20:09:20Z
DONE
```

`clean` first and `--no-build-cache`, so 1090 tasks **executed**; the string `FAILED` does not
appear anywhere in the 8-minute log. The gates that matter to this branch ran inside it:
`:udeaVerifyAgentsMd` (the `AGENTS.md` edit), `:udeaVerifyWiki` (the two wiki pages),
`:udeaVerifyContracts`, `:udeaVerifyModuleGraph`, `:udeaVerifyDeterminism`, and
`udeaCheckProtocolLock` in each module that has a lock. `:build-logic:udeaBuildLogicCheck` is in
the graph too.

**What a green build here does *not* say:** the three GL tasks run in it but this box has no
`DISPLAY` and the build passes no `-Pudea.render.requireGl`, so their tests skip. That is the whole
reason for 4.2, which runs them for real under xvfb with `requireGl=true`.

### 4.2 The GL suites, for real, under xvfb

`udea-render` is changed, so the GL surface is run rather than skipped:

```
flock scratchpad/lead/fullbuild.lock \
  xvfb-run -a -s "-screen 0 1280x720x24" env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe \
  sh gradlew :udea-render:udeaGlTest :udea-agent-host:udeaAgentGlTest :udea-editor:udeaEditorGlTest \
    -Pudea.render.requireGl=true --no-build-cache --rerun-tasks --max-workers=4 --console=plain --continue
```

```
$ flock -o scratchpad/lead/fullbuild.lock sh -c "xvfb-run -a -s '-screen 0 1280x720x24' \
    env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe sh gradlew \
    :udea-render:udeaGlTest :udea-agent-host:udeaAgentGlTest :udea-editor:udeaEditorGlTest \
    -Pudea.render.requireGl=true --no-build-cache --rerun-tasks --max-workers=4 --console=plain --continue"

> Task :udea-agent-host:udeaAgentGlTest
> Task :udea-render:udeaGlTest
> Task :udea-editor:udeaEditorGlTest

BUILD SUCCESSFUL in 3m 33s
```

Marker `glall.marker`: `START 2026-09-22T19:19:37Z`, `LANE full`, `HEAD 07f26fc3`, `EXIT 0`,
`END 2026-09-22T19:23:11Z`.

**43 tests, 0 failures, 0 errors, 0 skipped**, counted out of the JUnit XML that this run wrote -
`udeaGlTest` 35 in 34 classes, `udeaAgentGlTest` 2 in 2, `udeaEditorGlTest` 6 in 6 - and **executed
rather than restored**: the three task lines carry no `FROM-CACHE` and no `UP-TO-DATE` (the run
passed `--no-build-cache --rerun-tasks`), and the in-XML timestamps run from `19:20:25.335Z` to
`19:23:05.562Z`, inside this run's own window of 19:19:37Z to 19:23:11Z. `-Pudea.render.requireGl=true`
means a missing context would have failed rather than skipped, which is the trap this project's
GL evidence exists to avoid.

### 4.3 The launch check on this box

The evidence command of section 1's second half, one hold of the full lane, at `07f26fc3`:

```
flock -o scratchpad/lead/fullbuild.lock \
  xvfb-run -a -s "-screen 0 1280x720x24" env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe \
  python3 scripts/launch-check.py --expect-renderer llvmpipe --out build/reports/udea/launch
```

```
ok   moba-play (14s)
ok   moba-run (17s)
ok   moba-editor (11s)
ok   moba-client-local (9s)
ok   moba-client-listen (9s)
ok   moba-host-join (34s)
ok   moba-server (6s)
ok   hollow-play (11s)
ok   hollow-run (11s)
ok   hollow-server-client (125s)
ok   hollow-host-join (20s)

11 of 11 launches came up
```

Marker `localcheck.marker`: `START 2026-09-22T20:43:46Z`, `LANE full`, `HEAD 07f26fc3`, `EXIT 0`,
`END 2026-09-22T20:48:15Z` - 4m29s, inside the 5-9 minutes declared for this hold. Every row
includes the frame decoded and colour-counted here and the renderer checked to be llvmpipe, because
those are what the check fails on; `hollow-server-client`'s 125s is its dedicated server running its
bounded 7200 ticks.

### 4.4 The environment reaches a configuration-cached launch

`lc1` wrote `/srv/ssd1/workspace/udea-review/dev-winlaunch/lc1/moba-client-local.png`; `lc2`, a
second run of the same task with a different `UDEA_LAUNCH_PNG`, reports (lines 3, 411 and 419 of
`lc2/moba-client-local.log`, elided between):

```
$ env UDEA_LAUNCH_PNG=/srv/ssd1/workspace/udea-review/dev-winlaunch/lc2/moba-client-local.png
[... lines 4-410 elided: Gradle's task output and the game's own startup lines ...]
[udea.launch] frame 124: 1280x720 written to /srv/ssd1/workspace/udea-review/dev-winlaunch/lc2/moba-client-local.png; GL_RENDERER=llvmpipe (LLVM 20.1.2, 256 bits); GL_VERSION=4.5 (Compatibility Profile) Mesa 25.2.8-0ubuntu0.24.04.2
[udea.launch] closing the window
[... lines 413-418 elided: Gradle's `BUILD SUCCESSFUL` banner and task counts ...]
Configuration cache entry reused.
```

### 4.5 The positive control on the driver

Every Windows launch log carries the line the check requires, and `--expect-renderer llvmpipe` fails
the launch if the renderer is anything else. From `final/frames/moba-play.log`, lines 298 and 301
(299 is the game's own greeting and 300 is the audio device falling back to silence on a runner
with no sound card - elided, and not consecutive, which is how this block read before it was
re-checked):

```
[udea-render] GL_RENDERER=llvmpipe (LLVM 23.1.1, 256 bits); GL_VERSION=4.6 (Compatibility Profile) Mesa 26.1.8 (git-c5ec97122e)
[... lines 299-300 elided: "[moba.client] you are net id 0; WASD to walk, Space to swing" and the audio fallback ...]
[udea.launch] frame 123: 1280x720 written to D:\a\Udea\Udea\build\reports\udea\launch\moba-play.png; GL_RENDERER=llvmpipe (LLVM 23.1.1, 256 bits); GL_VERSION=4.6 (Compatibility Profile) Mesa 26.1.8 (git-c5ec97122e)
```

`Mesa 26.1.8` is the version the job pinned and checksummed, so the driver answering is the driver
installed - not something the runner already had.

### 4.6 The frame check has a known negative

A PNG that is one flat colour is what a context that came up and drew nothing produces, so the check
counts colours rather than bytes. Run against a black 320x200 PNG made for the purpose, a real moba
frame and a real hollow frame (`png-control.txt`):

```
black.png 1
lc2/moba-client-local.png 64
spike/windows-launch-frames/hollow/desktop/build/reports/udea/clearing.png 64
```

`1` is below the threshold of 8, so the black frame fails; the two real frames stop counting at the
cap of 64.

### 4.7 The rest of CI

Run **`35766809909`** (`CI`) at the final SHA `07f26fc3`, alongside the `Windows launch` run of
section 1. **Twenty jobs green, two red, and both reds are known and are not this branch's:**

```
success	build (ubuntu-latest)          success	gl tests (xvfb)
success	iOS simulator tests            success	clean build under budget
success	determinism (ubuntu, temurin)  success	determinism (windows, temurin)
success	determinism (ubuntu, corretto) success	determinism (windows, corretto)
success	latency budgets (ubuntu)       success	latency budgets (windows)
success	replay-equality (ubuntu, temurin)   success	replay-equality (ubuntu, corretto)
success	build-logic tests              success	the FIR checkers fail a real build
success	KSP stays incremental          success	build with the K2 plugin disabled
success	game-bridge-mcp conformance    success	a game outside this repository
success	agent brief matches the tree
failure	build (windows-latest)
failure	replay-equality (windows-latest, temurin)
```

`agent brief matches the tree` is `udeaVerifyAgentsMd`, so the `AGENTS.md` edit in this branch is
checked rather than asserted. `gl tests (xvfb)` is CI's own GL leg, which now includes the two soak
classes.

**The two reds, named from the run's own log** (`gh run view 35766809909 --log-failed`, saved as
`udea-review/dev-winlaunch2/ci-failed.log`):

```
MobaReplayEqualityTest > the checked-in gate fixture is regenerable, input for input() FAILED
MobaReplayFixturesCurrentTest > every checked-in moba replay fixture can be replayed by this build() FAILED
> Task :moba:desktop:test FAILED
> Task :moba:desktop:udeaReplayDigest FAILED
```

Those are the moba replay tests the lead flagged as known-red on Windows for want of Assimp
natives, and #244 is fixing them. They are red on `master` too, they touch nothing this branch
changes, and this branch adds no replay code: section 8 shows no `.udearep` fixture moved.

**And the third Windows red is gone, measured rather than predicted.** `MobaShaderAssetTest` was the
survivor of the three (3.5). In this run `:moba:game:jvmTest` **executed** on the Windows runner -

```
> Task :moba:game:jvmTestClasses
> Task :moba:game:jvmTest
```

no `FROM-CACHE`, no `UP-TO-DATE`, which is what hid it before - and the string `MobaShaderAssetTest`
appears **nowhere** in that job's 2403-line log (`winbuild-final.log`), failures included. So the
test ran on Windows, against CRLF files, and passed.

### 4.8 The launch check's negative control, on this box

Section 1's proof that the check goes red is a CI run on a deleted branch. This is the same break
run here, at this SHA, in the light lane. Literal `git diff` (`localbreak.diff`):

```
--- a/moba/desktop/src/main/kotlin/dev/wildware/moba/entry/MobaClient.kt
+++ b/moba/desktop/src/main/kotlin/dev/wildware/moba/entry/MobaClient.kt
@@ -176,7 +176,7 @@ public object MobaClient {
             ?: (if (legacy) "listen" else "local")
         println("[moba.client] ${MobaGame.NAME} ${MobaGame.VERSION} $mode $requested")
         when (requested) {
-            "local" -> local(mode)
+            "local" -> error("deliberate break: the launch check must go red on this")
             "listen" -> networked(mode)
             "host" -> udp(mode, bindPort = portOf(args.getOrNull(1)), joinTo = null)
             "join" -> udp(mode, bindPort = null, joinTo = addressOf(args.getOrNull(1)))
```

`python3 scripts/launch-check.py --only moba-play --expect-renderer llvmpipe`, whole output:

```
=== moba-play
FAIL moba-play (6s)
     problem: moba-play: exited with status 1
     problem: moba-play: no '[udea-render] GL_RENDERER=' line: no context came up
     problem: moba-play: no frame was written to moba-play.png
     moba-play: [moba.client] moba 0.1.0 Windowed local

0 of 1 launches came up; failed: moba-play
```

Marker: `QUEUED ... lane=light mem_available_mb=13088`, `START ... other_lane=held`,
`EXIT=1 2026-09-22T20:48:21+00:00`.

**Not a 75 and not a 124**, the two light-lane statuses that are not results: 75 would mean the
memory floor refused it and nothing ran, 124 would mean the five-minute cap killed it. This is an
honest 1. **And not a compile failure either**, which is the other way a red means nothing: the last
line of the check's own capture is `[moba.client] moba 0.1.0 Windowed local`, printed by the
mutated `MobaClient` on the statement *before* the `when` it breaks - so the mutation compiled, the
game started, and the check failed it for the three reasons it exists to check. The mutation was
reverted with `git checkout` and `git status` shows only `BRIEF.md` modified.

---

## 5. Tests, and each one watched failing

### 5.1 Written first, watched red

`LaunchProbeTest` (`jvmTest`, no context) and `GlLaunchProbeTest` / `GlLaunchProbeStopFileTest`
(`udeaGlTest`, a real context each in its own JVM) were written against a stub `LaunchProbe` whose
`fromEnvironment` returned `null` and whose `drive(frame, probe)` ignored the probe. Red, at
`80e96a09` plus the stub:

```
LaunchProbeTest[jvm] > a frame count that is not a positive whole number is refused by name()[jvm] FAILED
    org.opentest4j.AssertionFailedError at LaunchProbeTest.kt:50

LaunchProbeTest[jvm] > a named frame file asks for the default frame count and closes without waiting()[jvm] FAILED
    java.lang.IllegalStateException at LaunchProbeTest.kt:28

LaunchProbeTest[jvm] > the frame count and the stop file are read when given()[jvm] FAILED
    java.lang.IllegalStateException at LaunchProbeTest.kt:42

4 tests completed, 3 failed
```

```
GlLaunchProbeStopFileTest > the window stays open after the frame until the stop file appears, then closes() FAILED
    org.opentest4j.AssertionFailedError at GlLaunchProbeStopFileTest.kt:33

GlLaunchProbeTest > the probe writes the frame after its count and then closes the window by itself() FAILED
    org.opentest4j.AssertionFailedError at GlLaunchProbeTest.kt:46

2 tests completed, 2 failed
```

(`scratchpad/dev-winlaunch/log/red-unit.log` and `red-gl.log`; markers `red-gl.marker` records
`START 2026-09-22T16:58:41Z ... EXIT=1`.)

### 5.2 Mutations, with their diffs

| # | Mutation (literal `git diff`) | Result |
|---|---|---|
| M1 | see below | `moba-server` FAILS: `never printed 'stopped after 600 tick(s)'`, `did not exit within 120s; killed` |
| M2 | see below | both GL probe tests FAIL |
| M3 | see below | the stop-file test alone FAILS |

**M1** - the forwarding of section 3.4 removed (`M1.diff`):

```
--- a/moba/desktop/build.gradle.kts
+++ b/moba/desktop/build.gradle.kts
@@ -329,7 +329,7 @@ tasks.register<JavaExec>("runServer") {
     // `MobaServer` stops after `-Dudea.net.ticks` ticks, and `JavaExec` forks, so a `-D` given to
     // Gradle never reached it: the documented bound was unreachable through this task. A Gradle
     // property is forwarded instead, read through `providers` so the configuration cache sees it.
-    providers.gradleProperty("udea.net.ticks").orNull?.let { systemProperty("udea.net.ticks", it) }
+    // MUTATION M1: forwarding removed
 }
```

with `python3 scripts/launch-check.py --only moba-server --start-deadline 120` (marker `M1.marker`:
`START 2026-09-22T17:24:50Z ... EXIT=1`), `M1/summary.txt`:

```
FAIL moba-server: :moba:desktop:runServer -Pudea.net.ticks=600
  problem: moba-server: never printed 'stopped after 600 tick(s)'
  problem: moba-server: did not exit within 120s; killed
  moba-server: [moba.server] moba 0.1.0 authoritative; 1 client(s); proto 51577; perfect links
```

That is the check catching the defect, and the note underneath shows the server *did* start - so the
failure is the bound, not the launch.

**M2** - the probe never closes the window (`M2.diff`):

```
--- a/udea-render/src/jvmMain/kotlin/dev/wildware/udea/render/backend/KoolBackend.kt
+++ b/udea-render/src/jvmMain/kotlin/dev/wildware/udea/render/backend/KoolBackend.kt
@@ -243,7 +243,7 @@ public class KoolBackend private constructor(
             System.err.println("[udea.launch] the probe failed; closing the window: $failure")
             failure.printStackTrace()
         }
-        kool.stop()
+        // MUTATION M2: the probe never closes the window
     }
```

```
GlLaunchProbeStopFileTest > the window stays open after the frame until the stop file appears, then closes() FAILED
    org.opentest4j.AssertionFailedError at GlLaunchProbeStopFileTest.kt:41

GlLaunchProbeTest > the probe writes the frame after its count and then closes the window by itself() FAILED
    org.opentest4j.AssertionFailedError at GlLaunchProbeTest.kt:46

2 tests completed, 2 failed
```

Note the stop-file test fails at **line 41** here - "the stop file appeared and the window stayed
open" - and at line 33 in the TDD red, where no frame was written at all. Different assertions, so
the two runs are not the same failure wearing one name.

**M3** - the stop file ignored (`M3.diff`):

```
--- a/udea-render/src/jvmMain/kotlin/dev/wildware/udea/render/backend/KoolBackend.kt
+++ b/udea-render/src/jvmMain/kotlin/dev/wildware/udea/render/backend/KoolBackend.kt
@@ -230,7 +230,7 @@ public class KoolBackend private constructor(
                 "[udea.launch] frame ${drawn.get()}: ${shot.width}x${shot.height} written to " +
                     "${probe.png.toAbsolutePath()}; ${kool.glInfo}",
             )
-            val stop = probe.stopFile
+            val stop: java.nio.file.Path? = null // MUTATION M3: the stop file is ignored
             if (stop != null) {
                 println("[udea.launch] waiting for ${stop.toAbsolutePath()} before closing the window")
                 while (!Files.exists(stop)) {
```

```
GlLaunchProbeStopFileTest > the window stays open after the frame until the stop file appears, then closes() FAILED
    org.opentest4j.AssertionFailedError at GlLaunchProbeStopFileTest.kt:37

2 tests completed, 1 failed
```

Line 37 is `the window closed before its stop file appeared`, and `GlLaunchProbeTest` - which names
no stop file - stays green, which is the scoping this mutation exists to show.

Each mutation was reverted with `git checkout <path>` immediately after its run; `git status` is
clean at `1d42304b`.

**None of these rows is VOID under the rule added in `ed07e820`** - a mutation that fails to
*compile* exits non-zero in seconds and reads exactly like the mutation biting, so a row with no
`tests completed` line scores as VOID rather than as a red. Every row here carries that line, the
named failing test and its assertion: M2 `2 tests completed, 2 failed` at `GlLaunchProbeStopFileTest.kt:41`
and `GlLaunchProbeTest.kt:46`; M3 `2 tests completed, 1 failed` at `GlLaunchProbeStopFileTest.kt:37`
with the other class staying green, which a compile failure could not produce; M4 (5.5)
`2 tests completed, 2 failed` with both assertion messages quoted from the XML. **M1 is not a
test-suite row** - it mutates a Gradle script and is scored by the launch check, so what stands in
for `tests completed` is the check's own accounting, `0 of 1 launches came up`, plus the note
showing the server *did* start: a build that failed to configure would have neither.

### 5.3 The CRLF defect of section 3.5, reproduced and fixed on Linux

With the committed test and the `.frag` converted to CRLF in the working tree
(`sed -i 's/$/\r/' moba/game/assets/shaders/scanlines.frag`, `file` then reports `CRLF line
terminators`), `:moba:game:jvmTest --tests dev.wildware.moba.MobaShaderAssetTest --no-build-cache`:

```
MobaShaderAssetTest[jvm] > the packed bundle holds the GLSL of the real frag file, byte for byte()[jvm] FAILED
    org.opentest4j.AssertionFailedError at MobaShaderAssetTest.kt:37

4 tests completed, 1 failed
```

Same class, same test, same line 37 as Windows CI reported at `1fcb7620`
(`winbuild.log`: `MobaShaderAssetTest[jvm] > ... FAILED / org.opentest4j.AssertionFailedError at
MobaShaderAssetTest.kt:37`). With the fix applied and the file still CRLF: `EXIT=0`, and the suite
XML reads `tests="4" skipped="0" failures="0" errors="0" timestamp="2026-09-22T17:49:16.729Z"`
against a marker `START 2026-09-22T17:49:15Z`. The `.frag` was then restored (`file` reports `ASCII
text`).

### 5.4 What is only covered by CI, and said plainly

- **That the ICD installation works, and that `gradlew.bat` launches anything.** Neither can be run
  on a Linux box. The `windows-launch` job is the whole of that evidence; it is why the job exists
  and why it saves a PNG per launch.
- **The CRLF test fix goes red on Linux only with a CRLF file in the working tree** (5.3), because a
  Linux checkout has LF files. That is a reproduction, not a permanent Linux-red test; the permanent
  cover is `build (windows-latest)`, and 4.7 shows that leg **executing** `:moba:game:jvmTest` at
  the final SHA with no mention of `MobaShaderAssetTest` anywhere in its log. That half is now
  measured on Windows rather than only reproduced here.
- **`scripts/launch-check.py` has no unit tests.** It is an executed check, and its own honesty is
  shown two ways: the deliberate break (section 1) and the flat-colour control (4.6).

### 5.5 The soak #275's rule asks for, and the mutation that reds it

`GlDriveSoakTest` (Offscreen) and `GlWindowedDriveSoakTest` (Windowed) each drive a game through
`backend.drive(host)` - the call `MobaLaunch` and `HollowShot` make - for sixteen seconds, asserting
once a second that the render loop is running **and** that frames were drawn during that second,
then capturing a frame at the end. Section 3.9 is the judgement that the rule applies here.

**M4, the mutation: a probe every game gets.** #275's failure was a pipeline that closed itself
about eight seconds in, so the mutation is the same shape - the environment is ignored and every
`drive` is given a probe that closes the window after 300 frames. Literal `git diff`, taken from the
run (`scratchpad/dev-winlaunch2/M4.diff`):

```
--- a/udea-render/src/jvmMain/kotlin/dev/wildware/udea/render/backend/KoolBackend.kt
+++ b/udea-render/src/jvmMain/kotlin/dev/wildware/udea/render/backend/KoolBackend.kt
@@ -180,7 +180,8 @@ public class KoolBackend private constructor(
      *   render loop.
      */
     public fun drive(frame: (Float) -> Unit) {
-        drive(frame, LaunchProbe.fromEnvironment(System::getenv))
+        // MUTATION M4: a probe every game gets, closing the window after 300 frames.
+        drive(frame, LaunchProbe(java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "udea-m4.png"), 300, null))
     }
 
     /**
```

Both classes go red, and the messages say the arithmetic is right - 300 frames is three to four
seconds at the rate llvmpipe gives these little 128x64 targets, which is where each one stopped
(`M4-results/TEST-*.xml`, `message=` attribute):

```
GlDriveSoakTest > an offscreen game keeps drawing for sixteen seconds of real frames() FAILED
    org.opentest4j.AssertionFailedError at GlDriveSoakTest.kt:14

GlWindowedDriveSoakTest > a windowed game keeps drawing for sixteen seconds of real frames() FAILED
    org.opentest4j.AssertionFailedError at GlWindowedDriveSoakTest.kt:16

2 tests completed, 2 failed
```

```
message="org.opentest4j.AssertionFailedError: the render loop stopped 3s into the soak, after 307 frames, with no probe asked for"
message="org.opentest4j.AssertionFailedError: the render loop stopped 4s into the soak, after 304 frames, with no probe asked for"
```

Marker `M4.marker`: `START 2026-09-22T18:38:49Z`, `EXIT 1`, in this worktree. The mutation was
reverted immediately afterwards with `git checkout` and `git status` is clean.

**Not a compile failure, and said explicitly** (`ed07e820`'s rule): the row carries
`2 tests completed, 2 failed`, so both tests were built and run; each names its class and its
assertion; and the two messages differ from one another in the way the mutation predicts - 307
frames and 304 frames, three seconds and four - which a mutation that failed to compile could not
produce. **Order, plainly:** this mutation ran at 18:38:49Z and the green baseline below at
18:58:11Z, so the baseline came *after* the red rather than before it. Both are this branch's own
runs at this SHA, sixteen seconds of frames each in the green one; the red is not being read off a
suite nobody had ever seen pass.

**And green with the mutation gone**, the same two classes, same command:

```
$ sh scratchpad/lead/lightrun.sh soak <marker> sh -c "xvfb-run -a -s '-screen 0 1280x720x24' \
    env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe sh gradlew :udea-render:udeaGlTest \
    --tests dev.wildware.udea.render.gl.GlDriveSoakTest \
    --tests dev.wildware.udea.render.gl.GlWindowedDriveSoakTest \
    -Pudea.render.requireGl=true --no-build-cache --rerun-tasks --max-workers=3 --console=plain --continue"

BUILD SUCCESSFUL in 1m 3s
```

Marker (`soak.marker`, written by the light lane's own wrapper):

```
WORKTREE /srv/ssd1/workspace/Udea/.claude/worktrees/agent-a5f5a4a39322945ab
HEAD 07f26fc3
RUN soak
QUEUED 2026-09-22T18:58:11+00:00 soak lane=light
START 2026-09-22T18:58:11+00:00 soak lane=light other_lane=held mem_available_mb=9112
EXIT=0 2026-09-22T18:59:15+00:00 soak lane=light other_lane=held
```

**Executed, not restored from the cache**, which for a soak is the whole question - the two things
that tell them apart are the in-XML timestamp against the wall clock, and the time each test took:

```
<testsuite name="dev.wildware.udea.render.gl.GlDriveSoakTest" tests="1" skipped="0" failures="0" errors="0" timestamp="2026-09-22T18:58:37.900Z"
time="16.786"
<testsuite name="dev.wildware.udea.render.gl.GlWindowedDriveSoakTest" tests="1" skipped="0" failures="0" errors="0" timestamp="2026-09-22T18:58:55.353Z"
time="16.865"
```

The run finished at 18:59:15Z and those timestamps are 18:58:37Z and 18:58:55Z, so they are this
run's; `16.786` and `16.865` seconds are the sixteen seconds of frames plus the context coming up,
which a restored result could not have spent. `--no-build-cache --rerun-tasks` besides.


### 5.6 The first developer's evidence, re-checked against the files that produced it

Every transcript `dev-winlaunch` spliced into sections 1, 4 and 5 was re-read by `dev-winlaunch2`
out of the artefact it names, before any of it was believed. What was checked, and what it said:

| Claim | Checked against | Result |
|---|---|---|
| section 1's break summary, 5 lines then an elision | `udea-review/dev-winlaunch/break/frames/summary.txt` | contiguous and verbatim; the 6 elided rows really are `ok` |
| section 1's `9 of 11 launches came up; failed: moba-play, moba-client-local` | `gh run view 35761395752 -R wildware-uk/Udea --log`, re-run today | verbatim, and the run's log is still retrievable although its branch is deleted |
| section 1's deleted branch | `git ls-remote --heads origin windows-launch-break` | prints nothing |
| 4.4's lines 3, 411, 412, 419 of `lc2/moba-client-local.log` | the file (419 lines) | each line is at the line number claimed, and 411-412 are consecutive |
| 4.6's colour counts | `udea-review/dev-winlaunch/png-control.txt` | verbatim (`black.png 1`, two real frames at the cap of 64) |
| 4.5's two consecutive lines of `final/frames/moba-play.log` | the file, lines 298 and 301 | **not consecutive**: the `[udea-render]` line is 298 and the `[udea.launch]` line is 301. Corrected in 4.5 |
| 5.1's red unit and GL blocks | `scratchpad/dev-winlaunch/log/red-unit.log`, `red-gl.log` | verbatim, including `4 tests completed, 3 failed` and the marker's `head=80e96a09` |
| 5.2's M1/M2/M3 blocks and their line numbers | `log/M1.log`, `M1/summary.txt`, `log/M2.log`, `log/M3.log` | verbatim; the failing lines really are 41 (M2) and 37 (M3) against 33 in the TDD red, as claimed |
| 5.3's CRLF red and green | `log/crlf-red.log`, `crlf-green.marker` | verbatim; `MobaShaderAssetTest.kt:37`, then `EXIT=0` |
| section 8's "no generated file moved" | `git diff --name-only 483cb10e...HEAD` filtered for `lock\|hashes\|udearep\|roster` | empty - and the same command filtered for `AGENTS.md`, a file that *did* change, prints it, so the search works |

One correction came out of it (4.5) and no claim was withdrawn. The two runs in section 4 that the
first developer had not finished writing up were re-run from scratch on the merged tree; 4.1, 4.2,
4.3 and 4.7 are `dev-winlaunch2`'s own runs, not inherited ones.

---

## 6. The images

All in `/srv/ssd1/workspace/Udea/build/debug-screenshots/`, every one captured **on the Windows
runner** by the launch probe, at `1d42304b` unless said otherwise. (The final SHA changes no
rendering code; the frames a re-run produces are the same frames, and the run at the final SHA in
4.7 uploads its own copy of them.)

**Three of these are on the owner's dashboard and the rest are not, and here is exactly why.**
`winlaunch-windows-every-launch-collage.png`, `winlaunch-windows-moba-play.png` and
`winlaunch-windows-hollow-play.png` were posted at 18:22Z. The dashboard then refused connections
(the lead hit it too), and although the service answers again now, **this session's connection to it
is gone** - its tools are no longer callable from here, and the connection is made when a session
starts. So the remaining twelve are on disk under the names below, in the gallery directory, ready
for whoever has a live connection; nothing in this branch waits on them.

**Two pairs of these files are byte-identical, and that is the result rather than a copying
mistake.** `md5sum` over the artefact as GitHub served it:
`hollow-play.png` == `hollow-run.png` (`7d287876...`), because `:hollow:desktop:play` *is* `run` -
an alias, so an identical picture is the aliasing working - and `hollow-host-join-server.png` ==
`hollow-host-join-client.png` (`35fdc564...`), two separate OS processes whose replicated worlds and
default cameras agree to the byte at the same frame. Each was written by its own process to its own
path: `hollow-host-join-server.log` says `frame 122: 1280x720 written to
D:\a\Udea\Udea\build\reports\udea\launch\hollow-host-join-server.png` and the client's log says
the same for `...-client.png`. Both frames hold **two** characters, the host's and the joiner's -
looked at, not inferred from the colour count.

| File | What it shows, and what it proves |
|---|---|
| `winlaunch-windows-every-launch-collage.png` | All twelve frames of run 35763132162 in one picture: both games, every launch, drawn on Windows by llvmpipe. |
| `winlaunch-windows-moba-play.png` | `gradlew.bat playMoba` - the documented play command, a real match with its HUD. |
| `winlaunch-windows-moba-run.png` | `:moba:desktop:run`, the agent's Offscreen instance, which also answered `/health` and closed on the `close` tool. |
| `winlaunch-windows-moba-editor.png` | `:moba:desktop:runEditor`, `Windowed`, `/health` reporting the editor's mode. |
| `winlaunch-windows-moba-client-local.png` | `runClient --args=local`. |
| `winlaunch-windows-moba-client-listen.png` | `runClient --args=listen`, the in-process authoritative session. |
| `winlaunch-windows-moba-host-join-server.png` / `-client.png` | Two OS processes over a real UDP socket: the host's window and the joiner's, each drawn from its own world. |
| `winlaunch-windows-hollow-play.png` | `gradlew.bat playHollow` - the clearing with the player in it. |
| `winlaunch-windows-hollow-run.png` | `:hollow:desktop:run`, the listen server and its window. |
| `winlaunch-windows-hollow-host-join-server.png` / `-client.png` | Hollow's two-process pair; both characters are in both frames. |
| `winlaunch-windows-hollow-server-client-client.png` | The dedicated `runServer` and a client joined to it - the client's own frame, foxes and all. |
| `winlaunch-spike-hollow-clearing-windows.png`, `winlaunch-spike-moba-roster-windows.png` | The first two frames ever drawn on a Windows runner (spike run 35757205080), which is what settled that Mesa can give Kool a context before anything was built on it. |

---

## 7. The request, line by line

| What was asked | Where it is proved |
|---|---|
| moba `run` starts and answers | `windows-launch` row `moba-run`: `/health: renderMode=Offscreen`, a frame, `close accepted`, `exited 0` |
| moba `runClient` in all four modes | rows `moba-client-local`, `moba-client-listen`, `moba-host-join` (host **and** join) |
| moba `runServer` | row `moba-server`: `authoritative`, then `stopped after 600 tick(s)`, `exited 0` |
| moba `runEditor` | row `moba-editor`: `/health: renderMode=Windowed`, a frame, `close accepted` |
| hollow `run` | row `hollow-run`: `connected as client1; 184 entities`, a frame, `exited 0` |
| hollow `runServer` + `runClient` | row `hollow-server-client`: server `listening on ...`, client `connected as client1; 200 entities`, server `stopped after 7200 tick(s)` |
| hollow `runClient host` / `join` | row `hollow-host-join` |
| the template's headless run | `windows-launch (template)`: `new-game ran 600 ticks; rovers at [...]`, and `-PdebugPort` answering `/health` `renderMode=Headless` then closing |
| the template's window, when #269 lands | not merged as of `483cb10e`; section 3.7 says what to add |
| draws real frames | a PNG per drawing launch, decoded and colour-counted here, with a black-frame control (4.6) |
| responds | `/health` for the two agent launches; the client's own `connected as` line for every networked pair |
| exits cleanly or after a bounded count | every row ends `exited 0`, and a launch that had to be killed is a failure by construction |
| a CI job that goes red when a launch breaks | section 1, run 35761395752, branch deleted |
| one PNG per launch as an artefact | `windows-launch-frames`, 12 PNGs plus every log and `summary.txt` |
| a separate job | `windows-launch`, in its own workflow file (3.6) |
| fix what breaks on Windows | nothing in the games did; two OS-independent defects found and fixed (3.4, 3.5) |
| a way to start the actual game | `playMoba` / `playHollow` (3.2), documented in four places, and proved by the `moba-play` and `hollow-play` rows |
| post the frames | section 6, and three of them on the owner's dashboard |
| *(the standing rule, not the request)* a changed public hook proved by a game living with it (#275) | 3.9 for the judgement, 5.5 for the sixteen-second soak in both modes and the mutation that reds it |

---

## 8. Regenerated files

**None.** No `@Replicated` component was added, removed or renamed, so no id moved:
`net-components.lock`, each module's `net-protocol.lock`, `expected-generated-hashes.txt`, both
`moba` `.udearep` fixtures and `moba/desktop/src/test/resources/levels/test_level.roster.txt` are
byte-identical to `origin/master`'s. `git diff --stat origin/master...HEAD` lists no generated file
(section 2's table is the whole diff).
