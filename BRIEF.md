# BRIEF.md — issue #275, parts 1 and 3

**SHA:** `b8c15ce2` — every line of code and test on this branch, and the head every run
below was made at. The branch tip is this brief's own commit, which changes no code (a commit
cannot name its own hash; `git rev-parse --short HEAD` prints it).

Branch `issue-275-overlay-pointer`, worktree
`/srv/ssd1/workspace/Udea/.claude/worktrees/agent-a6a4ecb5d5dfadeee`.
Part 2 is **not** this branch: it merged separately as `issue-275-compose-convention` (480369b2),
and is merged in here only as part of `origin/master`.

## The evidence command

One command, complete, ready to paste:

```
cd /srv/ssd1/workspace/Udea/.claude/worktrees/agent-a6a4ecb5d5dfadeee && \
xvfb-run -a -s "-screen 0 1280x720x24" \
  env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe \
      ANDROID_HOME=$HOME/Android/Sdk JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem \
  sh gradlew :udea-render:udeaGlTest \
     --tests 'dev.wildware.udea.render.gl.GlOverlay*' \
     --tests 'dev.wildware.udea.render.gl.GlPointerPosition*' \
     -Pudea.render.requireGl=true --no-build-cache --max-workers=4 -Dorg.gradle.workers.max=8
```

Six tests: the two failure tests, the two sixteen-second soak tests, the pointer test, and
`GlOverlayIsolationTest`, which the filter also matches.

**It goes red when the feature is reverted.** Row **m8** of the table below is exactly that: every
production change reverted to `origin/master` with the branch's tests left in place.

## What was wrong, in plain words

A game registers a heads-up panel through the public `registry.overlay { }`. robot-game's panel drew
without opening the batch first, so `SpriteBatch2D.draw` refused, as it is meant to. From there the
engine lost the error completely:

- `KoolThread.run` caught the throwable, stored it in a field, and ended the render loop. Nothing
  printed it and nothing read it back.
- `KoolBackend.awaitExit()` returned normally, so the game's `main` **exited 0**.
- A capture waiting on that frame was failed with *"the render pipeline was closed before the frame
  was read"*, and the real cause was not attached.

The owner saw a window that closed itself after about eight seconds and a process that reported
success. **An overlay that draws correctly was never the problem** — sixteen seconds of a correct
overlay in both render modes passes on `origin/master` too (row m8), which is why "registering any
overlay kills the game" looked true and was not the mechanism. The eight seconds are still not
reproduced and this branch does not explain them; the exception arrives on the *first* frame the
overlay draws, so the likeliest reading is that eight seconds was robot-game's time to its first
driven frame. That is a guess, not a measurement, and it is the one thing in the issue still open.

## What this branch changes

Production, all in `udea-render`:

1. `KoolThread.report(t)` — the exception that ended the loop goes to stderr with its stack trace.
   One report, for the person who holds neither a capture nor `awaitExit()`: a player whose window
   just closed.
2. `KoolThread.awaitExit()` throws `GlContextException` with that exception as its cause, so a
   game's `main` exits non-zero with the trace instead of 0 with nothing.
3. `KoolBackend.onShutdown { cause -> pipeline.closeCaptures(cause) }`, down through
   `FrameCaptureSlot.close(cause)` — every capture failed by a dead loop carries and names what
   killed it.
4. `SpriteBatch2D`'s three draw-outside-begin messages name the call to make.
5. `KoolBackend.close()` takes the frame driver off (`kool.stopDriving()`) in the same render-thread
   task that disposes the pipeline. Tasks run at the top of a frame and the driver after them, so a
   driver left installed drew one frame on the pipeline just disposed and threw. That was always
   true; before change 2 it was swallowed with everything else, so **closing a game cleanly would
   have started reporting itself as a death.** It is the one change nobody asked for and it is the
   reason change 2 is safe — row m4 is what happens without it.

Tests (all in `udea-render`'s GL suite), `AGENTS.md`, `OverlaySystem`'s KDoc, moba's
`runOverlayProof`, and two rows in `WallClockBudgetCensusTest`.

## Part 1: the pointer position

**No API is missing, and none was added.** `PointerPosition` (`isPointerOver`, `pointerX`,
`pointerY` — plain floats, window pixels, `+y` down) shipped with #262, and `KoolPointer` implements
it with a public constructor that takes no `UiLayer`. It is already in the published snapshot
robot-game builds against. I fetched that artifact again from Central and read it — the hash
matches the copy the previous developer downloaded, byte for byte:

```
$ curl -sS -o dl.jar -w '%{http_code} %{size_download}\n' https://central.sonatype.com/repository/maven-snapshots/dev/wildware/udea/udea-render-jvm/0.1.0-SNAPSHOT/udea-render-jvm-0.1.0-20260921.085204-6.jar
200 717657
$ sha256sum dl.jar
53eeb24d7f3eecf826f5768eb8b9378d55e54c3ac1750c48de9716f591ce9973  dl.jar
$ javap -cp dl.jar dev.wildware.udea.render.input.PointerPosition
Compiled from "PointerPosition.kt"
public interface dev.wildware.udea.render.input.PointerPosition {
  public static final dev.wildware.udea.render.input.PointerPosition$Companion Companion;
  public abstract boolean isPointerOver();
  public abstract float getPointerX();
  public abstract float getPointerY();
  static {};
}
$ javap -cp dl.jar dev.wildware.udea.render.kool.KoolPointer | grep 'KoolPointer('
  public dev.wildware.udea.render.kool.KoolPointer(dev.wildware.udea.render.input.UiPointers);
  public dev.wildware.udea.render.kool.KoolPointer(dev.wildware.udea.render.ui.UiLayer);
  public dev.wildware.udea.render.kool.KoolPointer(dev.wildware.udea.render.ui.UiLayer, int, kotlin.jvm.internal.DefaultConstructorMarker);
```

(spliced from `scratchpad/dev-275c/published-pointer-api.txt`, which is the saved output of that
run.) Three plain accessors, no Kool type in the interface, and the third constructor is the
default-argument synthetic that makes `KoolPointer()` callable with no `UiLayer` at all.

So the gap was that **nobody could find it and nothing proved it follows a real cursor**. This
branch closes both: `GlPointerPositionOverlayTest` builds a `KoolPointer()` with no interface layer,
moves the cursor twice through Kool's own GLFW callback, has an overlay draw a crosshair at what
`PointerPosition` reports, and reads the window back to check the crosshair is on the pixel the
cursor was sent to. `AGENTS.md` and `OverlaySystem`'s KDoc now name it and say which way `y` runs.

## The decision the lead asked for: is the API itself a trap?

**Decided: no change to the rule. An overlay opens its own batch, exactly as a `RenderSystem` does;
forgetting is loud and self-describing within one frame.** Posted on the issue as
[#275 comment 5781764755](https://github.com/wildware-uk/Udea/issues/275#issuecomment-5781764755).

Three things I measured rather than assumed:

- **The guard is symmetric.** `SpriteBatch2D.begin` is
  `check(!isDrawing) { "SpriteBatch2D.begin called twice without end" }`. So "the engine opens the
  batch around each overlay" is not a compatible change: it turns every overlay that does the right
  thing today into one that throws.
- **The overlays that exist.** In this repository, exactly one shipped `OverlaySystem` —
  `AgentOverlaySystem`, which calls `beginPixels()` at line 107 — plus the GL fixtures and moba's
  proof panel. Outside it, robot-game's `Hud`. The population that would break is small, but it is
  the population that is *correct*, which is the wrong half to punish.
- **What forgetting now costs.** The exception arrives on the first frame the overlay draws, the
  message names the call to make, the trace names the game's own line, the process exits non-zero.

Rejected: *the pipeline opens the batch around each overlay* (breaks every correct overlay on the
guard above, and takes `begin(projection)` away from an overlay that wants a scaled layout);
*a draw outside `begin` does something harmless* (harmless means guessing a projection, and in a
`RenderSystem` drawing in world units a guess of "pixels" draws a silently wrong picture, which is
worse than a crash that names the line); *catch the overlay's exception and carry on with that
overlay switched off* (a swallowed exception on a reachable path, which the standards forbid, and
the same shape of quiet as the defect being fixed).

**If the owner disagrees**, the additive move is a `SpriteBatch2D.pixels { }` inline helper, or a
`PixelOverlay` base class, that opens and closes the pass for you — a path that cannot be got wrong
beside the one that can, breaking nothing. I did not add it because it is API surface for a hazard
that now fails loudly in under a second, and adding it later is additive while removing it is a
break.

## The mutation table

Predictions were **frozen in commit a4d16a4c, before any row ran** — `git show a4d16a4c:BRIEF.md`
is the whole of them, and this table is scored against that commit rather than written to match.

Run: all nine rows in one hold of the shared lock (12 minutes declared, **3m16s actual**, 21:36:11
to 21:39:27), each row `:udea-render:udeaGlTest --tests ... -Pudea.render.requireGl=true
--no-build-cache --max-workers=4 -Dorg.gradle.workers.max=8`, at SHA b8c15ce2. Each row is scored
from the **JUnit XML**, never the exit status: a row that failed to compile also exits non-zero in
seconds, so a red with no `testcase` in the XML is written VOID (repo rule ed07e820). No row was
VOID. The literal `git diff` of each mutation is below it, taken from the run
(`scratchpad/dev-275c/m<N>.diff`).

| # | Mutation | Predicted | Measured | Match |
|---|---|---|---|---|
| m0 | none — the control | EXIT=0, no failures | **GREEN ran=6 failed=0** | yes (I predicted 5 tests; 6 ran, because the filter also matches `GlOverlayIsolationTest`) |
| m1 | the loop stops printing what killed it | both failure tests, at check 3 | **RED 2/2**, "the render loop's failure did not reach stderr with its stack trace" | yes |
| m2 | `awaitExit` returns instead of throwing | both, at check 2 | **RED 2/2**, "awaitExit() returned normally for a render loop that died of an exception" | yes |
| m3 | the capture stops carrying the cause | both, at check 1 | **RED 2/2**, "the capture failed without the exception that stopped the render loop" | yes |
| m4 | `close()` stops taking the driver off first | both long-runs, at the end | **RED 2/2**, `GlContextException: ... RenderPipeline has been disposed and cannot draw` | yes |
| m5 | the message drops the hint | both, on the new assertion | **RED 2/2**, "the draw-outside-begin message does not name the call to make" | yes |
| m6 | the pointer stops following x | pointerX 60.0 vs 0.0 | **RED 1/1**, `pointerX after moving the cursor to (60, 50) ==> expected: <60.0> but was: <0.0>` | yes, to the wording |
| m7 | `isPointerOver` starts `true` | fails the never-been-over assertion | **GREEN 1/1 — the mutation did not bite** | **no** |
| m8 | **all** production reverted to `origin/master` | the two failure tests fail; the long-runs and the pointer pass | **RED ran=6 failed=2**, both `GlOverlayFailure*`, at the capture-cause check | yes |

```
m1  -            report(t)
m2  -        val cause = failure.get() ?: return
    -        throw GlContextException("the Kool render loop stopped because it threw: $cause", cause)
m3  -        kool.onShutdown { cause -> pipeline.closeCaptures(cause) }
    +        kool.onShutdown { pipeline.closeCaptures(null) }
m4  -                kool.submit {
    -                    kool.stopDriving()
    -                    pipeline.dispose()
    -                }
    +                kool.submit { pipeline.dispose() }
m5  -        check(isDrawing) { "SpriteBatch2D.draw called outside begin/end: $OPEN_FIRST" }
    +        check(isDrawing) { "SpriteBatch2D.draw called outside begin/end" }
m6  -        pointerX = x
m7  -    override var isPointerOver: Boolean = false
    +    override var isPointerOver: Boolean = true
m8  139 changed lines across RenderPipeline.kt, RenderSystem.kt, FrameCaptureSlot.kt,
    SpriteBatch2D.kt, KoolBackend.kt and KoolThread.kt — the whole production diff, reverted
```

### m7: the prediction that missed, and what it taught

I predicted m7 would go red and **it went green**. The reason is in the code, not in the test:
`KoolPointer.beginFrame` sets `isPointerOver = false` at the top of every frame of pointers, with a
comment saying why — a mouse that has left the window must stop reporting a position rather than
leave its last one standing for ever. So the field's *initialiser* is dead weight: it is overwritten
before anything can read it, and changing it cannot survive one frame.

What that means for the test is worth stating rather than hiding. `GlPointerPositionOverlayTest`'s
"before the cursor was ever over the window" assertion is guarded by **`beginFrame`'s per-frame
reset**, not by the initialiser I mutated. m6 is the row that shows the test is not vacuous; m7
shows I picked a line that no longer decides anything. A mutation that does not bite is a result,
not a gap in the table — but it is also not evidence, so I am not claiming it as one.

## The moba overlay proof, in a real window

`:moba:desktop:runOverlayProof` opens a **visible** moba window with a heads-up panel registered
through `registry.overlay { }` and runs it for twenty seconds of real frames, checking every second
that the loop is alive, the overlay drew since the last check, and a capture still comes back.

**Green** (`GREEN EXIT=0 at 19:04:51`), spliced from `scratchpad/dev-275c/proofG.log`:

```
overlay-proof: second 19: window open, overlay has drawn 1734 frames, capture 1280x720 at tick 1217
overlay-proof: second 20: window open, overlay has drawn 1829 frames, capture 1280x720 at tick 1281
overlay-proof: all checks passed after 20s; the window closes now
```

**Red** — the same game with robot-game's mistake, `-Pudea.overlayproof.forgetBegin=true`
(`RED EXIT=1 at 19:28:15`), spliced from `scratchpad/dev-275c/proofR.log` lines 441-449 and 504-509,
two contiguous runs with the elision marked:

```
udea-render: the Kool render loop stopped because it threw, and nothing will be drawn again: java.lang.IllegalStateException: SpriteBatch2D.draw called outside begin/end: call beginPixels() (or begin(projection)) before drawing and end() after, in every RenderSystem and OverlaySystem that draws with this batch
java.lang.IllegalStateException: SpriteBatch2D.draw called outside begin/end: call beginPixels() (or begin(projection)) before drawing and end() after, in every RenderSystem and OverlaySystem that draws with this batch
	at dev.wildware.udea.render.draw.SpriteBatch2D.draw-sskVvIg(SpriteBatch2D.kt:132)
	at dev.wildware.udea.render.draw.SpriteBatch2D.draw-sskVvIg$default(SpriteBatch2D.kt:119)
	at dev.wildware.moba.overlay.OverlayProof$Panel.render(OverlayProof.kt:104)
	at dev.wildware.udea.render.RenderPipeline.render(RenderPipeline.kt:187)
	at dev.wildware.udea.core.loop.GameLoop.frame(GameLoop.kt:165)

[... 55 lines elided ...]

Exception in thread "main" dev.wildware.udea.render.backend.GlContextException: the Kool render loop stopped because it threw: java.lang.IllegalStateException: SpriteBatch2D.draw called outside begin/end: call beginPixels() (or begin(projection)) before drawing and end() after, in every RenderSystem and OverlaySystem that draws with this batch
	at dev.wildware.udea.render.backend.KoolThread.awaitExit(KoolThread.kt:198)
	at dev.wildware.udea.render.backend.KoolBackend.awaitExit(KoolBackend.kt:220)
	at dev.wildware.moba.entry.MobaLaunch.runWithGl(MobaLaunch.kt:223)
	at dev.wildware.moba.entry.MobaLaunch.runWithGl$default(MobaLaunch.kt:168)
	at dev.wildware.moba.overlay.OverlayProof.main(OverlayProof.kt:47)
```

and Gradle's own verdict, from the same log:

```
> Process 'command '/home/shaun/.sdkman/candidates/java/21.0.11-tem/bin/java'' finished with non-zero exit value 1
```

Note `OverlayProof$Panel.render(OverlayProof.kt:104)` in that trace: the frame that threw is named,
and for a real game it is the game's own line, which is the whole point of the change.

## `sh gradlew build`, no exclusions

Run under the shared box lock, at head `b8c15ce2`, spliced from
`scratchpad/dev-275c/build3.marker` and the tail of `build3.log`:

```
START 2026-09-22T21:39:27+00:00 head=b8c15ce2 dirty=1
EXIT=0 END 2026-09-22T22:11:04+00:00 head=b8c15ce2 dirty=1
```

```
BUILD SUCCESSFUL in 1m 59s
1122 actionable tasks: 28 executed, 1094 up-to-date
```

(The twenty-nine minutes between START and EXIT are the queue for the lock plus the build; the
build itself is the 1m 59s Gradle reports. `dirty=1` is `BRIEF.md`, which no task reads.)

**Read that honestly: 1094 tasks were up-to-date**, from the earlier full run in the same worktree
(`build2`, 19:28-20:34, head `db6f6328`). That run was **red**, on one test — my two new test
sources read the clock without a `WallClockBudgetCensusTest` row — and `b8c15ce2` is the commit
that adds the two `NOT_A_BUDGET` rows. So the only source difference between the red run and this
green one is that census fixture, and the task that proves it re-ran here:

```
> Task :udea-gradle:test
```

`udea-gradle/build/test-results/test`: **63 tests, 0 failures**, in-XML timestamps
`2026-09-22T22:09:28Z`-`22:09:47Z` — inside this build's window, not restored.

`:udea-render:jvmTest` is `UP-TO-DATE` here, and its results are **393 tests, 0 failures** stamped
`2026-09-22T20:32:41Z`, from build2 at `db6f6328`. That is the same source: nothing in
`udea-render` changed between the two commits. I am reporting it as an up-to-date result rather
than claiming it ran again, because those are different claims.

## The GL suites, run for real under xvfb

`build` alone says **nothing** about GL here: `$DISPLAY` is empty and `udea.render.requireGl`
defaults to `false`, so the three GL tasks skip and the build stays green. So they were run
separately, with `requireGl=true`, at the same head, with the three result directories **deleted
first** and `--no-build-cache`:

```
cd /srv/ssd1/workspace/Udea/.claude/worktrees/agent-a6a4ecb5d5dfadeee && \
xvfb-run -a -s "-screen 0 1280x720x24" \
  env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe \
      ANDROID_HOME=$HOME/Android/Sdk JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem \
  sh gradlew udeaGlTest udeaAgentGlTest udeaEditorGlTest \
    -Pudea.render.requireGl=true --no-build-cache --continue \
    --max-workers=4 -Dorg.gradle.workers.max=8
```

```
START 2026-09-22T22:11:32+00:00 gl2 head=b8c15ce2 dirty=1
EXIT=0 2026-09-22T22:14:23+00:00 gl2 head=b8c15ce2
```

```
BUILD SUCCESSFUL in 2m 50s
```

Counted out of the JUnit XML, with each file's **in-XML** timestamp checked against the run
window (22:11:32-22:14:23), so none of it is a restored result:

| Task | Classes | Tests | Failures | Skipped | In-XML timestamps |
|---|---|---|---|---|---|
| `udeaGlTest` | 35 | 36 | 0 | **0** | 22:11:44.811Z - 22:14:15.305Z |
| `udeaAgentGlTest` | 2 | 2 | 0 | **0** | 22:11:43.533Z - 22:11:48.581Z |
| `udeaEditorGlTest` | 6 | 6 | 0 | **0** | 22:11:42.598Z - 22:12:43.719Z |

Forty-four GL tests, none skipped — which is the check that the run was real, because a skip is
exactly what a missing `DISPLAY` produces. The five this branch adds are in the `udeaGlTest` list:
`GlOverlayFailureOffscreenTest`, `GlOverlayFailureWindowedTest`, `GlOverlayLongRunOffscreenTest`,
`GlOverlayLongRunWindowedTest` and `GlPointerPositionOverlayTest`.

## The images

All three are in `/srv/ssd1/workspace/Udea/build/debug-screenshots/`, taken by
`:moba:desktop:runOverlayProof` on this branch — a real moba window, a real overlay registered
through the public `registry.overlay { }` call.

- **`issue275-moba-window-overlay-9s.png`** — the window as an X11 grab at nine seconds in. Shows
  the moba world drawing with the proof's heads-up panel on top of it. Proves the overlay draws in
  `Windowed` and that the window is still up well past the eight seconds at which robot-game's
  game used to die.
- **`issue275-moba-window-overlay-19s.png`** — the same window at nineteen seconds. Proves it is
  still alive and still drawing at the end of a twenty-second run, which is what the soak rule
  asks for.
- **`issue275-moba-agent-capture-20s.png`** — the engine's own `FrameCapture` PNG taken at the end
  of that run, decoded and written out. It is the picture an agent's `render.screenshot` returns:
  the world, **without** the overlay, because spec section 3.7 keeps overlays out of the captured
  frame. So the pair of images proves both halves — a player sees the panel, an agent's screenshot
  does not, and neither one closes the pipeline. Part 3's whole complaint was that registering an
  overlay made this capture impossible.

The dashboard was down while I worked (`agent-dashboard` answered `ECONNREFUSED` on every call, and
still does), so these are on the gallery only. They are named above so they can be posted from the
files as they sit.

## The issue, criterion by criterion

Parts 1 and 3 only. **Part 2 is not this branch** — it landed separately as
`issue-275-compose-convention` (480369b2) and arrives here through `origin/master`.

| What #275 asks | What proves it |
|---|---|
| **Part 1** — "A public way to read pointer position in window coordinates" | Already there, and this branch adds no API: `PointerPosition` (`udea-render`, `commonMain`) landed on master with #262 (0ddfe008), with `isPointerOver`, `pointerX`, `pointerY` — plain `Float`s, window pixels, +y down, no Kool type in the signature. The `javap` splice under "Part 1" reads it out of the published snapshot jar robot-game resolves. |
| **Part 1** — a game gets it without an interface layer | `KoolPointer(ui: UiLayer? = null)`: the secondary constructor takes no `UiLayer`. `GlPointerPositionOverlayTest` builds one exactly that way and reads the position. |
| **Part 1** — the numbers are the real cursor's | `GlPointerPositionOverlayTest`, a live GL test: it moves the OS cursor to two known window points, reads `pointerX`/`pointerY` back, draws a crosshair there and reads the window's pixels to confirm the crosshair landed on the cursor. Mutation **m6** makes it red (`expected: <60.0> but was: <0.0>`). |
| **Part 1** — documented | This branch adds the `PointerPosition` sentence to `AGENTS.md`'s "What the engine does today" list (master carried none: `git grep -n PointerPosition origin/master -- AGENTS.md` prints nothing, while the same grep on this branch prints the line) and names it in `OverlaySystem`'s KDoc. |
| **Part 3** — registering an overlay must not close the offscreen pipeline | `GlOverlayLongRunOffscreenTest`: sixteen seconds of real Offscreen frames with an overlay registered through `registry.overlay { }`, capturing throughout, asserting the loop is alive, the overlay is still drawing and a capture still decodes at the end. Plus `issue275-moba-agent-capture-20s.png` from the real game. |
| **Part 3** — and the same in a window | `GlOverlayLongRunWindowedTest`, the same sixteen seconds in `Windowed`, plus the two window grabs above at nine and nineteen seconds. |
| **Part 3** — "No error is logged by the render thread" | Fixed in three places, and each is pinned by its own mutation row: stderr with the stack trace (**m1**), `awaitExit()` throwing `GlContextException` so `main` cannot exit 0 (**m2**), and the capture's own failure naming the cause (**m3**). |
| **Part 3** — a game author can act on the message | The refusal now names the call to make: `... call beginPixels() (or begin(projection)) before drawing`. Mutation **m5** is red without it. |
| **The soak rule** (`.claude/agents/reviewer.md`, 483cb10e) | `GlOverlayLongRun*` — 16 s > 15 s, both modes, through the public call, asserting alive-and-drawing at the end, and a failure inside the overlay is loud by construction now. `:moba:desktop:runOverlayProof` does the same for twenty seconds in the real game. |

## Regenerated files

**None, and that is checkable.** This branch adds no `@Replicated` component and changes no
component id, so `net-components.lock`, every `net-protocol.lock`, `expected-generated-hashes.txt`,
the two moba `.udearep` fixtures and `moba/desktop/src/test/resources/levels/test_level.roster.txt`
are all untouched:

```
$ git diff --stat origin/master...HEAD -- '*net-protocol.lock' '*net-components.lock' \
    '*expected-generated-hashes.txt' '*.udearep' '*.roster.txt'
$
```

Empty. The control that the same command can print something: the identical invocation with
`'*.kt'` in place of those patterns lists this branch's changed Kotlin files (15 files, 846 insertions), so the
command runs and the filter is what is empty, not the search.
