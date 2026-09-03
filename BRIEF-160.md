# BRIEF-160 — the overlay's allocation-free claim, measured

ac4dbac

`ac4dbac` is the change: every file in this ticket's diff except this one. This brief is the commit
on top of it and touches nothing else, so the branch tip and `ac4dbac` are the same code. (A SHA
cannot name its own commit, which is why the top line is the code's rather than `HEAD`'s;
`BRIEF-170.md`, `BRIEF-171.md` and the brief this file replaced all do the same.)

Branch `issue-160-overlay-allocation-free`, off `origin/example` at `60a9471`.
Worktree `/srv/ssd1/workspace/Udea/.claude/worktrees/agent-a5b65b93c1b5ec78c`.

---

## 1. The evidence command

```
JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem sh gradlew \
  :udea-agent-host:test --tests 'dev.wildware.udea.agent.host.overlay.OverlayAllocationTest'
```

Leaves `udea-agent-host/build/test-results/test/TEST-dev.wildware.udea.agent.host.overlay.OverlayAllocationTest.xml`.
On `ac4dbac`: `tests=3 failures=0 skipped=0`.

**It goes red when an allocation is introduced on the frame path.** Section 4 has the six
mutations, each with its literal `git diff` and the failing-test names from that run. The short
version: the canonical red-proof is mutation **B**, which deletes `AgentOverlayModel`'s change
gate so the panel re-formats every frame. All three tests go red under it, deterministically, in
every ordering.

`skipped=0` is not by itself proof the probe ran — `AllocationProbe.isSupported` returning false
makes each test `return` early, which records as a **pass**, not a skip. The mutations are what
prove the probe measured: a test that returns early cannot report `38400 bytes`.

---

## 2. Summary

### The outcome, stated plainly

**The overlay was already allocation-free. No production code changed.** The diff is two new
test files. Issue #160's first five acceptance criteria were implemented and tested on
`origin/example` before I started; the sixth — *"allocation-free per frame in steady state"* —
was asserted in three shipped KDocs and measured by nothing. My deliverable is that measurement,
plus the verification that the other five tests can actually fail.

`git diff --stat origin/example -- udea-agent-host/src/main udea-agent/src/main` is empty.

### What was claimed but unmeasured

- `AgentOverlayModel` KDoc: a frame on which nothing changed *"did no string work at all, which
  is the property `OverlayAllocationTest` asserts"* — naming a file that **did not exist**.
- `AgentOverlayView.drawPanel` KDoc: *"Allocation-free."*
- `OverlayCanvas` KDoc justifies a packed `Int` colour over a colour object because an object per
  draw *"would be presentation-thread garbage sixty times a second"*.

The nearest existing test, `AgentOverlayViewTest`'s *"a frame on which nothing changed re-formats
nothing"*, asserts `AgentOverlayModel.refreshes` does not move. That is a real assertion and it is
a different one: a refresh counter says the *rows* were not rebuilt and says nothing about the
marker pass, the panel measure loop, the colour arithmetic, or the boxing of a session id — all
of which run every frame whether the model refreshed or not.

### What I built

`OverlayAllocationTest` (3 tests) and a module-local `AllocationProbe`, copied from the ones
`udea-core`, `udea-agent`, `udea-gas` and `udea-render` each hold in their own `test` source set.
I used the existing house pattern rather than inventing one; `udea-render`'s
`RenderAllocationTest` is the direct precedent and I followed its structure, its `warmups = 200`,
and its practice of stating the technique's blind spot.

Two guards run beside every measurement, as assertions rather than comments, because "zero bytes"
is trivially achievable by measuring frames that do nothing:

- **the measured frames drew** — `CountingCanvas` counts primitives, and the expected total is
  *draws-per-frame × frames-actually-run*, warmups included (`AllocationProbe.invocations`).
- **the measured frames were steady** — `AgentMarkers.refreshes` and `AgentOverlayModel.refreshes`
  must not move across the measured region.

Three states measured, not one: busy (8 markers, 7-row panel), empty (no calls at all), and
post-expiry (markers collected and walked, every one taking the aged-out branch). An empty fixture
satisfies invariants a populated one does not, so it is measured rather than assumed covered.

### Decisions I made

**`dtSeconds = 0f` in the measured block.** The probe runs its block 220 times × 100 frames =
22,000 frames. At a plausible 1/60s that is over an hour of overlay wall time, every marker would
be long dead of its 4-second TTL, and the measurement would be of an empty marker pass reporting
zero *for the wrong reason*. `0f` avoids that rather than merely detecting it. The code path is
identical — `ages[index] += dtSeconds` and `1f - age / ttlSeconds` are the same instructions
whatever the value — and the expired state is measured by its own test. Rejected: a shorter frame
count (would not have fixed it, only delayed it), and re-arming markers inside the block (makes
the block non-steady, which is the thing being measured).

**Warmup count: 200 blocks**, i.e. 20,000 render calls before the first byte is counted. Not a
number I chose — it is `RenderAllocationTest`'s, and inventing a second number for the same JVM
and the same technique would be exactly the parallel convention I was told not to build.
First-frame lazy work (the initial marker collect, the initial row format) happens on the
fixture's own priming frame, *before* the probe is called, and would in any case be absorbed by
the warmups and then by the minimum-of-20.

**A frozen `AgentClock` for the narration.** `AgentNarration.version` bumps on caption expiry,
which would re-format the panel inside the measured region. Injecting a frozen clock removes a
wall-clock dependency from a measurement rather than leaving it to be caught by a guard as a
confusing failure about refresh counts.

**Second set of test fakes.** `OverlayFakes.kt`'s `RecordingCanvas` allocates a `Draw` per
primitive into a growing `ArrayList` and `MapLocator` boxes a `Pair` into a `HashMap` per lookup.
A measurement through either would be measuring the test's own garbage and attributing it to the
overlay. `RenderAllocationTest` needed the same second set for the same reason.

### The assertion I deleted, and why

I wrote a fourth test comparing bytes for one marker against bytes for eight, reasoning that a
per-marker allocation shows up as a difference. **I deleted it.** It ran late in the class,
measured 0 against 0, and **passed under two of the three mutations the surviving assertions
caught** — including mutation A, which is a real per-marker allocation. It was reading `0 == 0`
as coverage. For an escaping allocation it added nothing the eight-marker zero does not already
catch; for a non-escaping one it reported a false pass. It is a paragraph in the test's KDoc now.

### The blind spot, measured rather than assumed

`AllocationProbe` counts heap bytes, so an allocation C2 proves does not escape its frame is
scalar-replaced and invisible. The honest scope is ***the overlay's frame path allocates nothing
the JIT cannot eliminate*** — which is what matters operationally, because a scalar-replaced
object costs no GC, and which is narrower than "no object is written anywhere on this path".

That boundary was measured. Mutation A (per-marker `FloatArray(2)` pair, undoing the two reused
scratch fields):

| how it was run | result |
|---|---|
| `a hundred steady-state frames…` as the only test in the class | **RED**, 38400 bytes |
| the same, with 1 marker instead of 8 | **RED**, 4800 bytes |
| the deleted 1-vs-8 test, with the class ahead of it in the same JVM | **zero**, passed |

38400 = 8 markers × 2 arrays × 24 bytes × 100 frames. 4800 = 1 × 2 × 24 × 100. 24 bytes is a
`FloatArray(2)` under compressed oops (12-byte header + 4-byte length + 8 bytes of data). The
arithmetic matches the effect exactly, which is what makes it a measurement rather than a story.

**Ordering, and what pins it.** The observed order in Gradle's result XML — stable across every
run I made — is: *after every marker has expired*, *a hundred steady-state frames*, *no markers
and no calls*. JUnit 5 applies no `MethodOrderer` by default; its order is deterministic for a
given set of methods but unspecified, and it changes when a method is added or renamed.
**Nothing pins it, and I did not try to pin it.** That is the design point rather than a gap: all
three surviving assertions are *absolute zeros*, and an absolute zero is order-independent for an
escaping allocation. A comparison between two measurements is not, which is precisely how the
deleted test failed. Pinning the order with `@TestMethodOrder` would have preserved a fragile
assertion instead of removing it.

`udea-render`'s `RenderAllocationTest` documents the same blindness, reached the same way. This is
a property of the technique across this repository, not something specific to the overlay.

---

## 3. Build output

### `sh gradlew build`

No exclusions.

```
JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem sh gradlew build --console=plain
```

Tail (`scratchpad/build.log`):

```
> Task :moba:test
> Task :moba:check
> Task :moba:build
> Task :udea-assets-compiler:check
> Task :udea-assets-compiler:build

BUILD SUCCESSFUL in 1m 32s
209 actionable tasks: 123 executed, 45 from cache, 41 up-to-date
```

Aggregated out of every `*/build/test-results/*/*.xml` after that run:
**2272 tests, 0 failures, 34 skipped** (`udea-core` 435, `udea-net` 255, `udea-codegen` 244,
`moba` 216, `udea-render` 204, `udea-assets-compiler` 191, `udea-agent-host` 165, and the rest).
The one compile warning is a pre-existing reified-type-argument warning in
`moba/.../Box2DPhysicsWorldTest.kt:263`, untouched by this branch.

**25 of those 34 skips are the GL tests**, which is exactly the trap and is why the run below is
separate. That is not an inference — I watched it happen: my first xvfb run left
`udeaAgentGlTest` at `tests=8 skipped=0`, then this `build` re-ran the same task with no `DISPLAY`
and **overwrote the result XML with `tests=8 skipped=8`**. A green `build` says nothing whatever
about GL here. The xvfb XMLs are therefore preserved outside the build directory, at
`scratchpad/gl-xml/`, where the next `build` cannot overwrite them.

### The xvfb GL run

The ticket touches the render half of `udea-agent-host`, so the GL tests were run for real rather
than left to skip on an empty `$DISPLAY`.

```
xvfb-run -a -s "-screen 0 1280x720x24" \
  env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe \
  JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem \
  sh gradlew udeaGlTest udeaAgentGlTest -Pudea.render.requireGl=true --console=plain
```

Run with `--rerun-tasks` so nothing came from the cache. Tail (`scratchpad/gl.log`):

```
> Task :udea-agent-host:udeaAgentGlTest

BUILD SUCCESSFUL in 26s
34 actionable tasks: 34 executed
```

Counted out of the result XMLs rather than from the word SUCCESSFUL, because **a skip is also
success** — that is the whole trap. Per suite, from `scratchpad/gl-xml/`:

```
dev.wildware.udea.render.gl.GlCaptureTest               tests=5 skipped=0 failures=0
dev.wildware.udea.render.gl.OffscreenBackendTest        tests=8 skipped=0 failures=0
dev.wildware.udea.render.gl.GlCaptureDeterminismTest    tests=4 skipped=0 failures=0
dev.wildware.udea.render.gl.GlOverlayIsolationTest      tests=1 skipped=0 failures=0
dev.wildware.udea.agent.host.gl.OffscreenRenderToolsTest tests=7 skipped=0 failures=0
dev.wildware.udea.agent.host.gl.OverlayCaptureIsolationTest tests=1 skipped=0 failures=0
```

**26 GL tests, 0 skipped, 0 failures**, on a real LWJGL3 context under llvmpipe — including
`OverlayCaptureIsolationTest > every declared capture route is identical with the overlay on and
off, and the window is not`, which is criterion 1.

The known `OffscreenBackendTest > closing the backend stops the render thread()` flake (#178, this
same wave, another developer) did **not** occur: that suite is 8 tests, 0 failures, in both of my
xvfb runs.

---

## 4. The mutation table

Every diff below is the literal `git diff` from the run that produced the named result, saved at
the time to `scratchpad/mutations/<id>.diff` and pasted from there. Failing-test names come from
that run's result XML. Mutations A–C are against my new test; D–F verify the five criteria that
already existed. All were reverted; `git diff origin/example -- udea-agent-host/src/main` is empty
on `ac4dbac`.

### A — undo the marker scratch arrays (the JIT finding)

Real shape: exactly what `WorldProjector.project`'s KDoc says keeps the pass allocation-free
(*"Taking the array rather than returning a pair is what keeps the per-frame marker pass
allocation-free"*).

```diff
@@ -178,6 +178,8 @@ public class AgentMarkers(
             val age = ages[index]
             if (age >= ttlSeconds) continue
             val kind = kinds[index] ?: continue
+            val scratchWorld = FloatArray(2)
+            val scratchScreen = FloatArray(2)
             val worldX: Float
             val worldY: Float
             when (kind) {
```

`a hundred steady-state frames allocate nothing at all` **FAILED**:

```
100 steady-state frames allocated 38400 bytes. ... ==> expected: <0> but was: <38400>
```

Run alone, `a frame allocates no more for eight markers than for one` (since deleted) **FAILED**
`expected: <4800> but was: <38400>`; run after the rest of the class it **passed** with
`one=0 eight=0`. This is the escape-analysis result in section 2.

### B — remove the panel's change gate (the canonical red-proof)

Real shape: the regression `AgentOverlayModel`'s entire KDoc exists to prevent — *"formatting six
of them sixty times a second is three hundred and sixty short-lived strings a second"*. The
strings escape into the `rowText` array, so C2 cannot eliminate them and the result is
order-independent.

```diff
@@ -92,12 +92,6 @@ public class AgentOverlayModel(
     public fun refreshIfStale(verbosity: OverlayVerbosity): Boolean {
         val activityVersion = activity.version
         val narrationVersion = narration.version
-        if (verbosity == seenVerbosity &&
-            activityVersion == seenActivityVersion &&
-            narrationVersion == seenNarrationVersion
-        ) {
-            return false
-        }
         seenVerbosity = verbosity
         seenActivityVersion = activityVersion
         seenNarrationVersion = narrationVersion
```

**All three FAILED:**

```
a frame after every marker has expired allocates nothing()
    walking 8 expired markers allocated 139200 bytes over 100 frames ==> expected: <0> but was: <139200>
a hundred steady-state frames allocate nothing at all()
    the panel was re-formatted inside the measurement, so it was not steady state ==> expected: <2> but was: <22002>
a frame with no markers and no calls allocates nothing()
    an idle overlay allocated 2400 bytes over 100 frames ==> expected: <0> but was: <2400>
```

Two numbers worth checking against the effect. `22002` = 22,000 frames (220 blocks × 100) + the 2
refreshes before the measurement, which confirms `AllocationProbe.invocations` accounting is
right and that the steady-state guard fires on the real quantity. `2400` over 100 frames is 24
bytes/frame — the capturing lambda `rebuild` passes to `activity.forEachRecent`, which is a
2-field object: 16-byte header + 2 × 4-byte refs.

### C — an `ArrayList` on the marker draw path

The shape the ticket brief specifically names as the thing a loose threshold would hide. The
threshold here is 0, so it does not hide.

```diff
@@ -174,7 +174,9 @@ public class AgentMarkers(
      * @param locator where an anchored entity is now. A stale generation draws nothing.
      */
     public fun draw(canvas: OverlayCanvas, projector: WorldProjector, locator: EntityLocator) {
-        for (index in 0 until count) {
+        val live = ArrayList<Int>()
+        for (index in 0 until count) if (ages[index] < ttlSeconds) live.add(index)
+        for (index in live) {
             val age = ages[index]
             if (age >= ttlSeconds) continue
             val kind = kinds[index] ?: continue
```

`a hundred steady-state frames allocate nothing at all` **FAILED**, `expected: <0> but was:
<11200>`. The other two passed *in this ordering*: an `ArrayList` that does not escape is
scalar-replaceable too, which is the section-2 finding again and the reason the steady-state
assertion is the one to trust.

> Reported honestly rather than tidied: against the earlier four-test version of the file the same
> mutation took all three of those tests red (5600 / 11200 / 5600). Removing one test changed the
> JIT ordering and with it which of them C2 could eliminate. The steady-state assertion went red
> both times.

### D — cache the anchor position instead of tracking (criteria 2 and 3)

```diff
@@ -184,9 +184,8 @@ public class AgentMarkers(
                 AnchorKind.ENTITY -> {
                     // The stale-generation case: nothing is drawn at all, rather than a ring
                     // around whatever recycled the slot.
-                    if (!locator.locate(netIds[index], scratchWorld)) continue
-                    worldX = scratchWorld[0]
-                    worldY = scratchWorld[1]
+                    worldX = xs[index]
+                    worldY = ys[index]
                 }
 
                 AnchorKind.POINT -> {
```

```
FAILED an entity anchor draws a ring that tracks the entity as it moves()
    expected: <100.0> but was: <0.0>
FAILED a stale generation draws nothing rather than ringing whatever recycled the slot()
    a marker was drawn for an entity that no longer exists: 1 ring(s)
FAILED a write marker is drawn differently from a read marker()
    Collection contains more than one matching element.
```

### E — clamp an off-screen marker to the edge instead of dropping it (criterion 4)

```diff
@@ -196,7 +196,10 @@ public class AgentMarkers(
 
                 AnchorKind.NONE -> continue
             }
-            if (!projector.project(worldX, worldY, scratchScreen)) continue
+            if (!projector.project(worldX, worldY, scratchScreen)) {
+                scratchScreen[0] = 0f
+                scratchScreen[1] = 0f
+            }
 
             val write = writes[index]
             val base = OverlayPalette.forSession(AgentSessionId(sessions[index]))
```

```
FAILED a marker off screen draws nothing rather than clamping to the window edge()
    Expected value to be true.
```

### F — draw reads and writes identically (criterion 5)

```diff
@@ -203,11 +203,8 @@ public class AgentMarkers(
             // Two channels at once: the fade says how long ago, and the read/write dimming says
             // whether it changed anything. A read at full age is faint on purpose.
             val fade = 1f - age / ttlSeconds
-            val colour = OverlayPalette.withAlpha(
-                base,
-                fade * if (write) 1f else OverlayPalette.READ_ALPHA,
-            )
-            val thickness = if (write) WRITE_THICKNESS else READ_THICKNESS
+            val colour = OverlayPalette.withAlpha(base, fade)
+            val thickness = WRITE_THICKNESS
             if (kind == AnchorKind.ENTITY) {
                 canvas.ring(scratchScreen[0], scratchScreen[1], ENTITY_RADIUS, thickness, colour)
             } else {
```

```
FAILED a write marker is drawn differently from a read marker()
    a write and a read are drawn with the same stroke
```

---

## 5. Images

**This ticket produces no image, and I did not manufacture one.** An allocation count is a number.

The one thing here that *is* visual — the markers themselves — is structurally unphotographable
from the agent side by design: the overlay draws only in `Windowed` and only onto `ScreenTarget`,
which `FrameCapture` never reads, so a `render.screenshot` correctly shows no markers. That
exclusion is #162's guarantee and `OverlayCaptureIsolationTest` is what asserts it; driving a
bridge session to photograph an overlay that is defined not to appear in a capture would produce a
picture that proves the opposite of what it appeared to. Nothing was copied to
`/srv/ssd1/workspace/Udea/build/debug-screenshots/`.

The executed transcripts in sections 3 and 4 stand in their place, and every one is spliced from a
file still on disk under
`/tmp/claude-1000/-srv-ssd1-workspace-Udea/184f8e9c-009e-46cb-9cba-389394ecf6fb/scratchpad/`
(`gl.log`, `mutations/*.diff`, `mutations/*.log`) or from a Gradle result XML in the worktree.

---

## 6. The issue, criterion by criterion

| # | Criterion | Proved by | Verified how |
|---|---|---|---|
| 1 | `OverlayExclusionTest` still passes with markers active and anchored | `OverlayCaptureIsolationTest > every declared capture route is identical with the overlay on and off, and the window is not` | Run for real under xvfb with `-Pudea.render.requireGl=true`: 8 tests in `udeaAgentGlTest`, 0 skipped, 0 failures (§3). Pre-existing; #162 owns it |
| 2 | A marker anchored to a moving entity tracks it across frames | `AgentOverlayViewTest > an entity anchor draws a ring that tracks the entity as it moves` | Pre-existing. **Mutation D** → `expected: <100.0> but was: <0.0>` |
| 3 | A stale `NetId` draws nothing, asserted directly | `AgentOverlayViewTest > a stale generation draws nothing rather than ringing whatever recycled the slot` | Pre-existing. **Mutation D** → `a marker was drawn for an entity that no longer exists: 1 ring(s)` |
| 4 | An off-screen anchor does not throw and does not draw outside the viewport | `AgentOverlayViewTest > a marker off screen draws nothing rather than clamping to the window edge` | Pre-existing. **Mutation E** → red |
| 5 | Read-calls and write-calls are visually distinct | `AgentOverlayViewTest > a write marker is drawn differently from a read marker` (asserts both stroke and alpha) | Pre-existing. **Mutation F** → `a write and a read are drawn with the same stroke` |
| 6 | **Allocation-free per frame in steady state** | `OverlayAllocationTest` (3 tests) — **new** | **Mutations A, B, C** all take `a hundred steady-state frames allocate nothing at all` red; B takes all three red deterministically |

A note on criterion 3, because I went looking for a problem there and did not find one. Its test
asserts only that *nothing* was drawn, which is the shape that can pass by construction — a marker
that was never drawn for any reason would satisfy it. It is not vacuous: mutation D removes the
generation guard and the test reports `1 ring(s)`, and the paired positive control is in the same
file (`an entity anchor draws a ring that tracks the entity as it moves`, same `entityRule()`,
same call shape, which does draw a ring). Same for criterion 4, whose positive control is `a point
anchor draws a cross, not a ring`.

---

## 7. Regenerated files

**None.** No replicated component was added or removed, so `udea-codegen/net-protocol.lock` and
`udea-codegen/src/test/resources/expected-generated-hashes.txt` are untouched and no id moved. No
file in `docs/contracts/` was changed and `docs/contracts.lock` is untouched. `AGENTS.md`'s module
table is unaffected — no module moved.

`gradlew` shows as `M` in `git status` (the `chmod +x` this box needs) and is deliberately **not**
staged; `git show --stat ac4dbac` lists only the two new test files.

### One thing this file overwrote, flagged rather than silently done

`BRIEF.md` was not a new file. On `origin/example` it held **BRIEF-172** (789 lines, last touched
by `231f5f3`), and writing this brief replaced it — which my instructions require ("write
`BRIEF.md` in the root of your worktree") and which is how every brief in this repository has been
produced. Nothing is lost: it is recoverable in full with

```
git show 231f5f3:BRIEF.md
```

But it is worth a ruling, because #172 looks like the odd one out. Every other completed ticket has
been archived under its own number — `BRIEF-154`, `165`, `167`, `168`, `169`, `170`, `171`, `173`,
`174`, `175`, `176`, `180`, `182` are all present as files — and there is **no `BRIEF-172.md`**.
So #172's brief existed only as `BRIEF.md`, and the next ticket to write a brief was always going
to overwrite it; I happen to be that ticket. **I have not created `BRIEF-172.md`**, because
inventing a file outside this ticket is exactly the unreviewed change I was told not to add. Naming
it is the job; ruling on whether it should be restored is not.

---

## 8. Out of scope — reported, not fixed

The defect that started this ticket has a class: **a KDoc citing a test class by name that does not
exist.** `AgentOverlayModel` named `OverlayAllocationTest` as the thing that asserted its central
property, and that file had never been written. Having found one instance I swept for the class
rather than stopping — every `*Test` identifier named in `udea-agent/src/main` and
`udea-agent-host/src/main`, resolved against every `*Test.kt` in the repository. **Five more, and
that is the complete list for those two modules:**

| Named test | Cited in |
|---|---|
| `AssetToolSurfaceTest` | `udea-agent/.../assets/AssetToolModule.kt:22` |
| `CommandResultRingTest` | `udea-agent/.../AgentBridge.kt:341` |
| `DigestAllocationTest` | `udea-agent/.../state/DigestBudgets.kt:47`, `udea-agent/.../Json.kt:213` |
| `OverlayHotkeyIsHardwareTest` | `udea-agent-host/.../overlay/AgentOverlaySystem.kt:187`, `.../overlay/OverlayVerbosity.kt:65` |
| `SayToolsetTest` | `udea-agent/.../tools/SayToolset.kt:123` |

**I have not touched any of them**, and they are not in this diff. One distinction the lead should
have when ruling on whether these clear the filing bar: `OverlayHotkeyIsHardwareTest` is a *stale
name over a property that is genuinely tested elsewhere* — `AgentOverlayViewTest > nothing an
agent can reach moves the level` does exactly what that KDoc describes. That is a different and
much smaller thing than a name over a test that was never written, which is what
`OverlayAllocationTest` was. I did not check the other four to that depth; naming them is the job,
ruling on them is not.

The sweep covered `udea-agent` and `udea-agent-host` only — the two modules this ticket owns. I
did not scan the rest of the tree.

---

## 9. My own pass over the diff

Against `docs/engineering-standards.md` §8 and `AGENTS.md`'s do-not list:

- **No production code changed at all**, so no §1 smell, no `TODO()`, no stubbed return, no
  swallowed exception, no copy-pasted logic differing by a constant can have been introduced on a
  reachable path.
- **No `public` declaration added.** `AllocationProbe` is `internal`; every class inside
  `OverlayAllocationTest` is `private`.
- **A test that cannot fail** — the item this ticket lived closest to. Three assertions, six
  mutations, and the one assertion that could not fail reliably was **deleted rather than kept**
  (§2). Every surviving test has been watched go red.
- No generated code, no string concatenation producing code, no new `GameContext` field.
- **No wall clock and no unseeded randomness.** The test injects a *frozen* `AgentClock` to remove
  the one wall-clock dependency the fixture had. Nothing here is inside `Simulation.step()` — the
  overlay is presentation and takes `dtSeconds`, never a `Tick`. The `WallClockBudgetCensusTest`
  scan is unaffected: this file reads no clock, so it is not a census candidate and needs no row.
- No `by net(...)`, no snapshot codec, no setter instrumentation, no `common` dependency, no
  reflection on a per-tick path, no bare `Int`/`Long`/`String` for a domain concept.
- **No GL outside `udea-render`.** The new test names no `com.badlogic.gdx` type (`grep badlogic`
  over both new files returns nothing); it drives the `OverlayCanvas` port. Stated precisely, so
  it is not read as more than it is: `udea-agent-host` is **not** in
  `ModuleGraphRules.HEADLESS_PROJECTS` — it was taken out deliberately, as its `build.gradle.kts`
  explains — so `udeaVerifyHeadless` does not gate this module and did not check this. The rule
  holds here because no GL was added, not because a verifier confirmed it.
- **No presentation system implemented as a Fleks system**, no module arrow moved, no frozen
  contract changed, no `fieldNames`/`FieldMask`/`FieldStore` alignment touched.

### What I did not exercise

- **A marker expiring on the frame being measured.** The measured blocks run at `dtSeconds = 0f`,
  so no marker crosses its TTL *inside* a measurement. The state either side is measured (live in
  the steady-state test, expired in its own test); the transition frame is not. It is one
  `continue` and allocates nothing either side of it, but I did not measure it and am not claiming
  it.
- **The overflow case.** `AgentMarkers.capacity` is 8 and my busy fixture records exactly 8
  anchored calls, so the `if (count >= capacity) return@forEachRecent` path in `collect()` is not
  taken. `collect()` is off the steady-state frame path, so this is not a gap in the criterion.
- **Verbosity changes during a measurement.** Measured at `VERBOSE` throughout, which is the most
  work per frame. `OFF` and `CAPTION` draw strictly less; `AgentOverlayViewTest` covers what they
  draw, not what they allocate.
- **A non-HotSpot JVM.** Every test returns early without `com.sun.management.ThreadMXBean`, so on
  such a JVM criterion 6 is unproved rather than falsely proved. Stated, not hidden.
