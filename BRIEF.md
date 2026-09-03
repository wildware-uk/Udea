d7999d8

# BRIEF — issue #178: the `gl tests (xvfb)` shutdown flake

Branch `issue-178-gl-shutdown-flake`, off `origin/example` at `60a9471`.
`d7999d8` is the last commit of the change itself; this file lands in the commit after it.

**There is nothing to photograph on this ticket.** It is a thread-lifecycle assertion — the
subject is which exception a call throws and whether a latch has been counted down, neither of
which has a pixel. No screenshot was manufactured to fill the slot. Everything below is an
executed transcript, spliced from a file that is still on disk.

**Where the originals are.** Every log, every mutation diff and every preserved JUnit XML tree
is under **`/srv/ssd1/workspace/Udea/build/issue178-evidence/`** (5.3M, gitignored, in the main
checkout so it outlives my worktree). Filenames are named inline throughout. The two CI logs
came from `gh run view <id> --log` and are there too.

---

## 1. The evidence command

```
xvfb-run -a -s "-screen 0 1280x720x24" \
  env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe \
  sh gradlew udeaGlTest udeaAgentGlTest -Pudea.render.requireGl=true
```

(prefix `JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem` on this box).

**Green on this branch** — `evidence-full-green.log`, tail spliced verbatim:

```
> Task :udea-render:udeaGlTest
> Task :udea-agent-host:udeaAgentGlTest

[Incubating] Problems report is available at: file:///srv/ssd1/workspace/Udea/.claude/worktrees/agent-a10b02ebe1b965e3b/build/reports/problems/problems-report.html

Deprecated Gradle features were used in this build, making it incompatible with Gradle 9.0.

You can use '--warning-mode all' to show the individual deprecation warnings and determine if they come from your own scripts or plugins.

For more on this, please refer to https://docs.gradle.org/8.13/userguide/command_line_interface.html#sec:command_line_warnings in the Gradle documentation.

BUILD SUCCESSFUL in 38s
43 actionable tasks: 43 executed
Configuration cache entry stored.
```

27 GL tests, **0 skipped** (which is what `-Pudea.render.requireGl=true` buys), 0 failures.
Grepped out of the preserved XMLs in `evidence-full-green.xml.d/` and saved as
`evidence-full-green-counts.txt`:

```
name="dev.wildware.udea.render.gl.GlCaptureDeterminismTest" tests="4" skipped="0" failures="0"
name="dev.wildware.udea.render.gl.OffscreenBackendTest" tests="8" skipped="0" failures="0"
name="dev.wildware.udea.render.gl.GlThreadShutdownTest" tests="1" skipped="0" failures="0"
name="dev.wildware.udea.agent.host.gl.OffscreenRenderToolsTest" tests="7" skipped="0" failures="0"
name="dev.wildware.udea.agent.host.gl.OverlayCaptureIsolationTest" tests="1" skipped="0" failures="0"
name="dev.wildware.udea.render.gl.GlOverlayIsolationTest" tests="1" skipped="0" failures="0"
name="dev.wildware.udea.render.gl.GlCaptureTest" tests="5" skipped="0" failures="0"
```

**And it goes red when the shutdown path is broken.** Section 4 (mutation M2) is the transcript.

### A hazard about that directory, measured on my own tree

`dev-160` found, and I reproduced here, that running `udeaGlTest` / `udeaAgentGlTest` with no
`DISPLAY` — which is what a plain `sh gradlew build` does — **overwrites the xvfb XMLs with
skipped ones**. Measured on this worktree by running those two tasks with `--rerun-tasks` and no
`DISPLAY` (`nodisplay-clobber.log`, which opens `DISPLAY=[]` and ends BUILD SUCCESSFUL), counts
saved as `nodisplay-clobber-counts.txt`:

```
name="dev.wildware.udea.render.gl.GlCaptureDeterminismTest" tests="4" skipped="4" failures="0"
name="dev.wildware.udea.render.gl.GlCaptureTest" tests="5" skipped="5" failures="0"
name="dev.wildware.udea.render.gl.GlOverlayIsolationTest" tests="1" skipped="1" failures="0"
name="dev.wildware.udea.render.gl.GlThreadShutdownTest" tests="1" skipped="1" failures="0"
name="dev.wildware.udea.render.gl.OffscreenBackendTest" tests="8" skipped="7" failures="0"
name="dev.wildware.udea.agent.host.gl.OffscreenRenderToolsTest" tests="7" skipped="7" failures="0"
name="dev.wildware.udea.agent.host.gl.OverlayCaptureIsolationTest" tests="1" skipped="1" failures="0"
```

(`OffscreenBackendTest` reads 7 of 8 rather than 8 of 8 because
`Headless is refused rather than quietly opening a window` does not call `GlAvailability.require()`
and so has nothing to skip on.)

Two guards, both taken. I copied every XML out at capture time, so every transcript in this brief
comes from a preserved copy rather than from a directory a later run could clobber. And the
**last** thing to write to `build/test-results` in this worktree was the evidence command itself
(`evidence-final-after-build.log`, BUILD SUCCESSFUL), so the live report directory a reviewer opens
shows the real GL results and not skips.

---

## 2. Summary

### The failure mode, precisely: which assertion, and why it can lose

This is acceptance criterion 1, so it is argued from the code and from CI's own bytes.

**The assertion that lost** was the last line of the test, at `OffscreenBackendTest.kt:206` on
`efab1d0`:

```
198:     @Test
199:     fun `closing the backend stops the render thread`() {
200:         GlAvailability.require()
201:         val backend = startBackend(RenderRegistry())
202:         backend.close()
203: 
204:         // If the loop were still running, this would block for the full shutdown timeout.
205:         backend.awaitExit()
206:         assertFailsWith<IllegalStateException> { backend.create(definition().build()) }
207:     }
```

(the whole file at that commit is saved as `efab1d0-OffscreenBackendTest.kt`; this excerpt,
with the line numbers `awk` added, is `efab1d0-shutdown-test.txt`.)

**CI says which step lost, and it is not a timeout.** From `gh run view 33432054044 --attempt 1
--log`, saved as `run-33432054044-attempt1.log`, lines 491–496 verbatim including the job/step
prefix the tool emits:

```
gl tests (xvfb)	Run the GL suites against a real context	2026-08-31T19:46:58.1700692Z OffscreenBackendTest > closing the backend stops the render thread() FAILED
gl tests (xvfb)	Run the GL suites against a real context	2026-08-31T19:46:58.1702053Z     org.opentest4j.AssertionFailedError at OffscreenBackendTest.kt:206
gl tests (xvfb)	Run the GL suites against a real context	2026-08-31T19:46:58.1703480Z         Caused by: dev.wildware.udea.render.backend.GlContextException at OffscreenBackendTest.kt:206
gl tests (xvfb)	Run the GL suites against a real context	2026-08-31T19:46:58.1704968Z             Caused by: java.util.concurrent.CancellationException at OffscreenBackendTest.kt:206
gl tests (xvfb)	Run the GL suites against a real context	2026-08-31T19:47:00.2687819Z 
gl tests (xvfb)	Run the GL suites against a real context	2026-08-31T19:47:00.2750650Z 18 tests completed, 1 failed
```

The second failing run carries the **identical** chain — `run-33437939749.log`, lines 478–481
and line 490, with lines 482–489 elided and marked (they are the `:udea-agent:` tasks that ran in
between):

```
gl tests (xvfb)	Run the GL suites against a real context	2026-08-31T20:50:14.6020404Z OffscreenBackendTest > closing the backend stops the render thread() FAILED
gl tests (xvfb)	Run the GL suites against a real context	2026-08-31T20:50:14.6070564Z     org.opentest4j.AssertionFailedError at OffscreenBackendTest.kt:206
gl tests (xvfb)	Run the GL suites against a real context	2026-08-31T20:50:14.6100163Z         Caused by: dev.wildware.udea.render.backend.GlContextException at OffscreenBackendTest.kt:206
gl tests (xvfb)	Run the GL suites against a real context	2026-08-31T20:50:14.6101315Z             Caused by: java.util.concurrent.CancellationException at OffscreenBackendTest.kt:206
[... 8 lines elided ...]
gl tests (xvfb)	Run the GL suites against a real context	2026-08-31T20:50:16.8001417Z 18 tests completed, 1 failed
```

**Read the chain backwards and it names every step.** A `CancellationException` reaches the
caller only by being thrown out of `task.get(...)` and caught at `GlThread.kt:143`, which is the
single place that wraps one into a `GlContextException`. `GlThread` calls `task.cancel(false)` in
two places — `submit`'s own timeout branch at line 154, and `failAllQueued` at line 227 — and the
first of those cannot be the source, because it throws on the very next line without going back to
`task.get`, and the exception it throws (`"a GL task was not run within 30000ms"`) carries no
cause, whereas CI's chain shows a `CancellationException` *as* the cause. So the cancel came from
`failAllQueued`. And for a task to be cancelled by `failAllQueued` it must first have been
*queued* — which means `create` got past `check(isRunning)` in `submit`. So on those two runs:

1. `close()` returned. `stop()` waits on the `finished` latch, so the render loop had signalled
   that it was over.
2. `create()` → `gl.submit { ... }` → `check(isRunning)` **passed**.
3. `tasks.add(task)`.
4. `failAllQueued()` cancelled it.
5. `submit` turned that into `GlContextException`; `assertFailsWith<IllegalStateException>` saw
   the wrong type and failed.

**Why step 2 could pass at all** is the defect. `isRunning` was:

```kotlin
    val isRunning: Boolean get() = thread.isAlive && failure.get() == null
```

and `run()`'s `finally` runs in this order:

```kotlin
        } finally {
            ready.countDown()
            finished.countDown()
            failAllQueued()
            // Last, and swallowing its own failure: a broken hook must not stop the two latches
            // above from having been counted down, and there is nobody left to report it to.
            runCatching { shutdown.get()?.invoke() }
        }
```

`finished.countDown()` is the line that releases `stop()`, and *only then* does the thread run
`failAllQueued()` and the shutdown hook and unwind. Between those two points the loop is over and
`Thread.isAlive` is still `true`. Nothing bounds that gap — it is however long the scheduler
takes to get back to that thread, which on an idle laptop is microseconds and on an
oversubscribed runner is not.

**So: the assertion was waiting on neither a deadline nor an event.** It was reading a *proxy*
for the event — thread liveness — that lags the event by an unbounded amount. The issue asked
which of the two it was; the honest answer is "a third thing, and that is why widening a timeout
could not have helped": the failure is a **wrong exception type**, not a slow one. And it does
*not* distinguish "the thread did not stop" from "the thread had not stopped yet" — it could not,
because it never asked about the loop at all.

To the issue's third question — does anything else in the suite depend on the previous test's
backend being gone? Not in a way that explains this, on two grounds. Every `OffscreenBackendTest`
test that starts a backend also closes it before it returns: the four that go through `withBackend`
and the two that manage their own both close in a `finally` (lines 144, 195, 232), the shutdown
test under discussion closes it as the thing it is testing (line 203), and
`Headless is refused rather than quietly opening a window` never creates one. And the failure did
not move around — it was the same test, at the same line, on both CI runs, which is not what an
intermittent leak from a neighbour looks like.

### The fix

`isRunning` reads the loop's own exit signal instead of a proxy for it:

```kotlin
    val isRunning: Boolean get() =
        thread.isAlive && finished.count > 0L && failure.get() == null
```

The argument that this closes the window completely rather than narrowing it turns on one
property: **a `CountDownLatch` is monotone.** `stop()` blocks on `finished.await(...)`, so when it
returns normally it either observed the latch at zero or its budget elapsed. If it observed zero,
`finished.count` is zero from that instant onwards and can never go back up — so from the moment
`close()` returns, `check(isRunning)` refuses every `submit` with an `IllegalStateException`,
whatever the thread object is doing, and no amount of scheduling can put a caller back inside the
old window. If the budget elapsed instead, the loop really did not stop and `isRunning` correctly
still reports `true` — which is the condition `OffscreenBackendTest` now asserts on directly.
`thread.isAlive` stays in the conjunction because it is what answers *before* `start()`, where the
latch is armed and the loop has not begun.

The two other readings of the same proxy in the same file went with it (see "grepping for the
class" below).

### What I decided, and what I rejected

Both are also on the issue as comments
[#178 comment 5528587623](https://github.com/wildware-uk/Udea/issues/178#issuecomment-5528587623)
and [5528592351](https://github.com/wildware-uk/Udea/issues/178#issuecomment-5528592351).

- **Criterion 2 by the mechanism route, not by 10 CI runs.** The criterion offers both. I cannot
  push to `origin/example` or trigger CI from this box, so "link the runs" is not available; a run
  count I cannot produce is worth less than a mechanism anyone can check by reading `GlThread.kt`.
  Local repetition is reported below as **corroboration**, explicitly not as the criterion.
- **Rejected: widening a timeout.** Forbidden by the issue, and the mechanism says it could not
  have worked — a wrong exception type does not become the right one with more time.
- **Rejected: a `closed` flag on `Lwjgl3Backend` so `create` refuses before touching the thread.**
  It is the obvious fix and it is the whitewash: the test would then pass against a backend that
  never stops its render thread, which is exactly what criterion 3 exists to catch.
- **Chosen: convert the read, and add a positive assertion.** `OffscreenBackendTest` now asserts
  `assertFalse(backend.renderLoopRunning)` — the loop stopped, as a fact — *before* asserting the
  refusal. So the refusal cannot be the only thing holding the test up.
- **`renderLoopRunning` is `internal`, not `public`.** One caller, in the same module's tests.
  A `public` declaration nobody outside the module uses is on the reject list.
- **Nothing was wired onto `check`**, and `udea-agent-host` was not edited — #160 owns it this
  wave. `udeaAgentGlTest` is *run* by the evidence command but no source of its module changed.
- **One `Long` of seconds, and why it is not the `Tick` rule.** `GlThreadShutdownTest` has
  `EXIT_WAIT_SECONDS: Long = 10L`. The rule that a duration must be a `Tick` is about simulation
  time; this is a test waiting for an OS thread to reach a shutdown hook, where there is no
  simulation, no `SimClock` and no tick a `Tick` could denominate. It is the same shape as the
  wall-clock bounds `GlThread` already carries in reviewed code — `STARTUP_TIMEOUT_SECONDS`,
  `TASK_TIMEOUT_MILLIS`, `SHUTDOWN_TIMEOUT_SECONDS` — and as `timeoutMillis = 30_000` in the
  existing `OffscreenBackendTest`. Flagging it because it is on the reviewer's blocking list and I
  would rather argue it here than have it read as an oversight.

### Grepping for the class, not fixing the instance

The class: **"read `Thread.isAlive` as a proxy for the render loop having exited"**. I grepped
`\.isAlive` over every `*.kt` outside `build/`. Three production readings, all in `GlThread.kt`,
all three changed:

| # | Where | Before | After |
|---|---|---|---|
| 1 | `isRunning` — the one the flake came through | `thread.isAlive && failure.get() == null` | `thread.isAlive && finished.count > 0L && failure.get() == null` |
| 2 | `submit`'s poll loop | `if (cause != null \|\| !thread.isAlive)` | `if (cause != null \|\| finished.count == 0L)` |
| 3 | `stop`'s guard | `if (thread.isAlive)` | `if (thread.isAlive && finished.count > 0L)` |

(2) reports the same exception sooner and for the stated reason: nothing is drained after the
loop signals its exit, so waiting for the thread object to die is waiting past the moment the
answer became known. (3) keeps **both** conjuncts deliberately — `thread.isAlive` is what stops
a `GlThread` that was never started from posting an exit to whichever *other* backend owns the
`Gdx.app` static, and `finished` stops a loop that already ended from being asked again.

**And where the sweep found nothing.** The remaining `.isAlive` readings are all test code —
`HeadlessHostTest`, `SimBarrierTest`, `SessionPropertyProcessTest`, and `LiveInstance` /
`Phase2Demo`'s `loopFinished()` in `udea-agent-host`. In each of those the thread object genuinely
*is* the subject ("did `run()` return"), not a proxy for a loop's exit. **None of them is an
instance of this class and I changed none of them.** That is a clean sweep, said out loud so a
reviewer can tell it from an unmade one.

### One thing changed that is not strictly the ticket

`GlThread` had two consecutive KDoc blocks above `awaitExit()`. Kotlin binds only the nearest, so
the block written for `stop()` was documenting nothing and `stop()` had no doc at all. I moved it
onto `stop()` and extended it to state the property `stop()` now guarantees. One hunk, in a file
this change already edits. Flagged rather than buried; happy to split it out if a reviewer
prefers.

---

## 3. `sh gradlew build` — real output

`full-build.log`, tail spliced verbatim:

```
For more on this, please refer to https://docs.gradle.org/8.13/userguide/command_line_interface.html#sec:command_line_warnings in the Gradle documentation.

BUILD SUCCESSFUL in 2m 39s
211 actionable tasks: 127 executed, 45 from cache, 39 up-to-date
Configuration cache entry stored.
```

No `-x`, no exclusions.

A census over every `*/build/test-results/*/*.xml` in the worktree — 381 files — reads
**2551 tests, 9 skipped, 0 failures and 0 errors**, and the 9 are all pre-existing real-art tests
(`RealArtAtlasPackerTest` ×7, `RealArtReproducibilityTest` ×2), none of them GL.

Two things about that number, because it is exactly the kind that goes stale or misleads. It was
taken **after** the final xvfb run, not at the end of the `build` — the `build` leaves those same
directories holding *skipped* GL results, as section 1 shows and `nodisplay-clobber-counts.txt`
records (26 GL tests, all skipped), so the same census run immediately after the `build` alone
would report more skips and exactly the same 2551 and 0. And it is a census of result files, not
a number the `build` printed; the `build`'s own output is the block above.

The three gates outside `check` (`gates.log`):

```
BUILD SUCCESSFUL in 5s
42 actionable tasks: 42 up-to-date
```

for `udeaVerifyModuleGraph udeaVerifyNoLegacyDependencies udeaVerifyAgentsMd`.

The GL run with `-Pudea.render.requireGl=true` is section 1 above, and it was executed twice
green: once before the build (`evidence-full-green.log`) and once after it
(`evidence-final-after-build.log`).

---

## 4. Mutations, each with its literal diff

Every diff below is `git diff HEAD` output from the run that produced the failure beside it,
saved to the named `.diff` file — not a description, and not retyped.

### M1 — revert the fix (`M1.diff`, `M1-final.log`, `M1-final.xml.d/`)

Produced by `git checkout d7999d8^ -- udea-render/src/main/kotlin/.../GlThread.kt`, i.e. the exact
pre-fix file. Abridged to the three functional hunks; the full 79-line diff, including the KDoc,
is in `M1.diff`:

```diff
-    val isRunning: Boolean get() =
-        thread.isAlive && finished.count > 0L && failure.get() == null
+    /** True between a successful [start] and the GL thread exiting. */
+    val isRunning: Boolean get() = thread.isAlive && failure.get() == null
```
```diff
             val cause = failure.get()
-            // `finished` and not `thread.isAlive`, for the same reason [isRunning] reads it:
-            // nothing is drained after the loop signals its exit, so waiting for the thread
-            // object to die is waiting past the moment the answer became known.
-            if (cause != null || finished.count == 0L) {
+            if (cause != null || !thread.isAlive) {
```
```diff
     fun stop() {
-        // Both conjuncts are load-bearing. `thread.isAlive` keeps a `GlThread` that was never
-        // started from posting an exit to whichever *other* backend owns the `Gdx.app` static;
-        // `finished` keeps a loop that has already ended from being asked again.
-        if (thread.isAlive && finished.count > 0L) {
+        if (thread.isAlive) {
```

Result — `M1-final.log`, lines 46–55 verbatim:

```
> Task :udea-render:testClasses UP-TO-DATE

> Task :udea-render:udeaGlTest

GlThreadShutdownTest > a stopped GL thread refuses work before its thread object has died() FAILED
    org.opentest4j.AssertionFailedError at GlThreadShutdownTest.kt:71

19 tests completed, 1 failed

> Task :udea-render:udeaGlTest FAILED
```

`GlThreadShutdownTest.kt:71` is `assertFalse(gl.isRunning, ...)`. Message, grepped from the preserved
`M1-final.xml.d/TEST-dev.wildware.udea.render.gl.GlThreadShutdownTest.xml` and saved as
`M1-message.txt`:

```
message="org.opentest4j.AssertionFailedError: the loop has exited but the thread reports itself running, so every check that guards on it is answering about the thread object rather than the loop"
```

Note what M1 shows about the *old* test: on this particular run
`OffscreenBackendTest > closing the backend stops the render thread` **passed** even with the fix
reverted. That is the whole problem the ticket describes, and it is why the new test exists.

### M2 — break the shutdown path genuinely (`M2.diff`, `M2-glthread.log`, `M2-offscreen.log`)

This is **acceptance criterion 3**: the loop is never asked to exit, so the render thread really
does not stop. Full literal diff:

```diff
diff --git a/udea-render/src/main/kotlin/dev/wildware/udea/render/backend/GlThread.kt b/udea-render/src/main/kotlin/dev/wildware/udea/render/backend/GlThread.kt
index 81ec0e5..e2ec67a 100644
--- a/udea-render/src/main/kotlin/dev/wildware/udea/render/backend/GlThread.kt
+++ b/udea-render/src/main/kotlin/dev/wildware/udea/render/backend/GlThread.kt
@@ -199,9 +199,9 @@ internal class GlThread(private val window: WindowConfig, visible: Boolean) {
         // started from posting an exit to whichever *other* backend owns the `Gdx.app` static;
         // `finished` keeps a loop that has already ended from being asked again.
         if (thread.isAlive && finished.count > 0L) {
-            // `postRunnable` rather than `submit`: the block ends the loop that would have
-            // completed a `submit`'s future, so waiting for it to return is waiting forever.
-            runCatching { Gdx.app?.postRunnable { Gdx.app.exit() } }
+            // MUTATION M2 (issue #178, criterion 3): the loop is never asked to exit, so the
+            // render thread genuinely does not stop. Reverted immediately after this run.
+            runCatching { Gdx.app?.postRunnable { } }
         }
         finished.await(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
     }
```

`M2-glthread.log`, lines 45–54 verbatim:

```
> Task :udea-render:compileTestJava NO-SOURCE
> Task :udea-render:testClasses UP-TO-DATE
> Task :udea-render:udeaGlTest

GlThreadShutdownTest > a stopped GL thread refuses work before its thread object has died() FAILED
    org.opentest4j.AssertionFailedError at GlThreadShutdownTest.kt:64

1 test completed, 1 failed

> Task :udea-render:udeaGlTest FAILED
```

`M2-offscreen.log`, lines 46–55 verbatim:

```
> Task :udea-render:testClasses UP-TO-DATE

> Task :udea-render:udeaGlTest

OffscreenBackendTest > closing the backend stops the render thread() FAILED
    org.opentest4j.AssertionFailedError at OffscreenBackendTest.kt:211

1 test completed, 1 failed

> Task :udea-render:udeaGlTest FAILED
```

The two messages come from two different files, so they are quoted separately rather than run
together. From `M2-glthread.xml.d/TEST-...GlThreadShutdownTest.xml`, saved as
`M2-message-glthread.txt`:

```
message="org.opentest4j.AssertionFailedError: the render loop never reached its shutdown hook, so it never exited"
```

From `M2-offscreen.xml.d/TEST-...OffscreenBackendTest.xml`, saved as `M2-message-offscreen.txt`:

```
message="org.opentest4j.AssertionFailedError: the render loop was still running after close()"
```

`GlThreadShutdownTest.kt:64` is the `assertTrue(entered.await(...))`; `OffscreenBackendTest.kt:211`
is the new `assertFalse(backend.renderLoopRunning, ...)`. Both name the real defect in words, and
neither is the refusal assertion — which is the point: **the refusal cannot be the only thing
holding this test up.**

Those two runs are filtered to one test each on purpose, so the transcripts are legible. Under M2
the **whole** evidence command is also red — `evidence-full-M2red.log`, `exit=1`,
`FAILURE: Build completed with 2 failures` — but messily, because 19 tests each leaving a live
GLFW application behind takes the test JVM down:

```
> Task :udea-render:udeaGlTest FAILED
> Task :udea-agent-host:udeaAgentGlTest

X Error of failed request:  GLXBadDrawable
  Major opcode of failed request:  150 (GLX)
  Minor opcode of failed request:  11 (X_GLXSwapBuffers)
  Serial number of failed request:  44923
  Current serial number in output stream:  44923

> Task :udea-agent-host:udeaAgentGlTest FAILED
```

with `Process 'Gradle Test Executor 3' finished with non-zero exit value 134`. The JVM's own crash
report is preserved as `M2-fullsuite-hs_err_pid1017783.log` (it wrote into the worktree; I moved
it out, so `git status` is clean).

### M3 — the control: can the fix be faked by refusing always? (`M3.diff`, `M3-final.log`)

A fence that passes on prose is as wrong as one that fails on it. The cheapest thing that
satisfies "a stopped GL thread refuses work" is to refuse *always* — so I ran it.

```diff
@@ -96,8 +96,9 @@ internal class GlThread(private val window: WindowConfig, visible: Boolean) {
      * `thread.isAlive` stays in the conjunction because it is what answers *before* [start],
      * where the latch is still armed and the loop has yet to begin.
      */
-    val isRunning: Boolean get() =
-        thread.isAlive && finished.count > 0L && failure.get() == null
+    // MUTATION M3 (issue #178, control): the cheapest thing that satisfies "a stopped GL
+    // thread refuses work" -- refuse always. Reverted immediately after this run.
+    val isRunning: Boolean get() = false
```

`M3-final.log`:

```
19 tests completed, 16 failed
```

The three that passed — the `<testcase>` elements in `M3-final.xml.d/` with no `<failure>`
child — saved as `M3-passed.txt`:

```
PASSED: a stopped GL thread refuses work before its thread object has died()
PASSED: closing the backend stops the render thread()
PASSED: Headless is refused rather than quietly opening a window()
```

**Stated honestly: my two shutdown tests do pass under M3.** They are satisfied by an
always-refuse, and I am not going to claim otherwise. What stops that being a hole is that the
other 16 GL tests go red — the property "refuses *after* stop" is pinned by the suite, because
every one of them needs `submit` to work *before* stop. This is a real limit of the two tests
read in isolation and I would rather write it down than let a reviewer find it.

---

## 5. Local repetition — corroboration, explicitly not criterion 2

The criterion asks for 10 consecutive CI runs *or* an argument from the mechanism. The mechanism
is section 2. This section is extra, and it is a sample on a shared box, so it proves less than
the mechanism does.

**Controlled arms**, 16 spinner processes on 24 cores (load 24→41 across the runs), same command,
`--rerun-tasks` each time. The spinners were tagged `udea178spinner` in their command line and
each was verified by reading `/proc/<pid>/cmdline` before it was signalled; nothing belonging to
melon-merge or to any other agent was touched.

| Arm | Runs | `GlThreadShutdownTest` red | `OffscreenBackendTest > closing…` red | Runs with any `AssertionFailedError` |
|---|---|---|---|---|
| **A** — fix reverted (`M1load-1..10.log`) | 10 | **10/10** | **3/10** | 10/10 |
| **B** — fix in place (`fixload-1..10.log`) | 10 | 1/10 | 1/10 | **0/10** |

**Arm B's two red cells are not the flake, and I am not going to round them to zero.** Exactly two
Arm B runs failed at all — `fixload-1.log` and `fixload-9.log` — and in both the *entire suite*
went down together, not one test. Every failure in those two files is a `GlContextException`
raised at a **context-startup** line (`GlThreadShutdownTest.kt:52` is `gl.start()`;
`OffscreenBackendTest.kt:236` is `Lwjgl3Backend.start`), caused by
`GdxRuntimeException at Lwjgl3Application.java:90`. That is the box refusing to create a GL context
under starvation — different in cause, in signature and in blast radius from #178. The column that
separates the two is the last one: `grep -l AssertionFailedError fixload-*.log` matches **no file
at all**, while it matches all ten in Arm A.

An earlier unloaded pair, before I set up the controlled arms: reverted (`M1loop-*.log`) gave
`GlThreadShutdownTest` 10/10 red and `OffscreenBackendTest > closing…` **5/10** red; fixed
(`fixed-*.log`) gave 10/10 BUILD SUCCESSFUL. I am reporting both pairs because the loads differed
between them and I would rather show the spread than pick the flattering one.

**What this does and does not say.** It says the CI flake reproduces on this box, at 3/10 and
5/10 on the pre-fix code, and that the new test turns that same window into a 10/10 red. It says
**nothing** about GitHub's runners. And zero assertion failures across the 20 runs with the fix in
place is *not* evidence that the flake rate is now zero — 20 runs cannot show that. Only the
mechanism argument supports it, and that argument rests on `CountDownLatch` being monotone, not on
any count in this section.

**One over-stress result, reported because it misled me first.** An initial attempt used 48
spinners on 24 cores (load 52) — `fixedload-*.log`, with the fix in place. One run in 10 went red
(`fixedload-8.log`) and it was *not* the flake: it was the same whole-suite `Lwjgl3Application`
context-creation cascade, and `grep -l AssertionFailedError fixedload-*.log` matches nothing. I had
to read the cause chain rather than the failure count to see that. The count on its own would have
read as "the fix does not work", which is why I dropped that arm and built the controlled pair
above at a load where a context can still be created.

---

## 6. The issue's acceptance criteria, one by one

**☑ "The test's failure mode is stated here with evidence — which assertion, and why it can lose."**

Section 2. The assertion is `assertFailsWith<IllegalStateException>` at `OffscreenBackendTest.kt:206`
on `efab1d0`, spliced from `git show`. Why it can lose is argued from `GlThread.run`'s `finally`
ordering and confirmed by both CI runs' cause chains, spliced from `gh run view --log` output that
is preserved at `build/issue178-evidence/run-334*.log`. The `CancellationException` at the bottom
of the chain is the part that pins *which* step lost: section 2 walks it back to `failAllQueued`
by elimination — `GlThread` cancels a task in two places and only one of them can surface as a
*cause* — and a task can only reach `failAllQueued` if `check(isRunning)` had already let it be
queued.

**☑ "`gl tests (xvfb)` passes 10 consecutive runs on the integration branch, *or the change is
argued from the mechanism rather than from a sample*. Link the runs."**

Taken by the second route, and the decision is recorded on the issue. The mechanism is in
section 2: `stop()` returns only after observing `finished` or timing out on it; a `CountDownLatch`
at 0 stays at 0; therefore after a normal `close()` every `submit` is refused, with no dependence
on thread teardown. **I cannot link runs** — this box does not push to `origin/example` and does
not trigger CI, and I would rather say that than link something else. Local repetition is section 5
and is labelled corroboration throughout.

**☑ "The test still fails when the backend genuinely does not stop its render thread. Show it — a
shutdown assertion that has been relaxed into always passing is worse than the flake."**

Mutation M2, section 4: `stop()` no longer asks the loop to exit. Both tests go red, with the
literal diff, both console transcripts and both assertion messages — and they fail with *"the
render loop never reached its shutdown hook, so it never exited"* and *"the render loop was still
running after close()"*, which are statements about the loop, not about a refusal. Mutation M3 is
the control in the other direction, and section 4 states plainly the one thing M3 exposes: the two
shutdown tests alone are satisfied by an always-refuse, and it is the other 16 GL tests that close
that off.

---

## 7. Regenerated files

**None.** This change adds and removes no replicated component, so neither
`udea-codegen/net-protocol.lock` nor `expected-generated-hashes.txt` moved, and no id shifted.
`udeaCheckProtocolLock` runs on `check` and the `build` in section 3 was green, which is the
positive check on that claim rather than my say-so. No file in `docs/contracts/` was touched;
`udeaVerifyContracts` is on `check` and likewise passed.

---

## 8. What I did not exercise

Said plainly, because it is where a reviewer should look next.

- **The `stop()` timeout path in production.** `stop()` still discards the `false` that
  `finished.await(SHUTDOWN_TIMEOUT_SECONDS, ...)` returns when the loop overran its budget. After
  this change that is no longer *silent* — `isRunning` still reports `true` in exactly that case,
  and `OffscreenBackendTest` now asserts on it — but `stop()` itself remains quiet. I left it
  because making it throw would change shutdown behaviour for `moba`'s entry points and the agent
  host, neither of which is this ticket's module. It is a small follow-up if wanted.
- **Concurrent `close()` from two threads.** Untested before and after; not a shape this ticket
  introduced.
- **The `Windowed` mode path.** All GL tests here are `Offscreen`. The shutdown code is identical
  and does not branch on mode, but I did not run a visible window.
- **`udea-agent-host`'s own use of the changed API.** `udeaAgentGlTest` (8 tests) runs green under
  the evidence command, which exercises `Lwjgl3Backend` from that side; but I edited nothing there
  and wrote no new test for it, since #160 owns that module this wave.
- **CI itself.** No run of `gl tests (xvfb)` on this branch exists, for the reason above.
