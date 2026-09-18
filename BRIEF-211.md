# BRIEF — issue #211: port `udea-render` to Kool

**SHA:** `b7a60b3`

## Evidence command

```
xvfb-run -a -s "-screen 0 1280x720x24" \
  env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe \
  sh gradlew udeaGlTest udeaAgentGlTest -Pudea.render.requireGl=true
```

This is the whole of AC2: every test that needs a real Kool context, run for real under xvfb,
with the property that turns "no display" into a hard failure rather than a silent skip.

**It goes red when the feature is reverted — done for real, not asserted.** I reverted the fix
(`AgentContext.kt`, `RenderToolset.kt`, `RenderToolsHarness.kt`, `RenderToolsetTest.kt` back to
`git show HEAD:...`, i.e. before this branch's second commit) and re-ran `udeaAgentGlTest` under
the identical xvfb command:

```
> Task :udea-agent-host:udeaAgentGlTest

OffscreenRenderToolsTest > the render toolset against a real driver, end to end() FAILED
    org.opentest4j.AssertionFailedError at OffscreenRenderToolsTest.kt:110

Execution failed for task ':udea-agent-host:udeaAgentGlTest'.
> There were failing tests. See the report at: file:///.../udea-agent-host/build/reports/tests/udeaAgentGlTest/index.html

BUILD FAILED in 16s
112 actionable tasks: 12 executed, 4 from cache, 96 up-to-date
```

Then restored the four files exactly (byte-identical, `diff` checked) and re-ran the same
command:

```
> Task :udea-agent-host:udeaAgentGlTest FROM-CACHE

BUILD SUCCESSFUL in 3s
108 actionable tasks: 10 executed, 7 from cache, 91 up-to-date
```

## Summary

I took over #211 mid-rewrite from `dev-211`, who had the Kool API port compiling but had never
run the GL suite that actually proves it works — `GlAvailability`/`GlAvailabilityHere`'s own
availability probe created and closed a throwaway `KoolBackend` to answer "is there a display",
which spent Kool's one-context-per-JVM allowance on the probe itself. Every GL test then failed
its *own* context creation with `GlContextException`, which read as "no display" and was reported
that way — so the suite had never actually run.

Fixing that probe (now a `$DISPLAY` check, no context created) uncovered three more bugs, none
previously reachable:

1. **`udeaAgentGlTest` had no `forkEvery = 1`.** `udea-render`'s equivalent `udeaGlTest` does (Kool
   allows one context per JVM, so a class that opens a backend needs its own JVM), but the
   agent-host copy was missing it — every GL test class in that module shared one JVM, so only the
   first to open a context ever succeeded. Added.

2. **Several GL test classes had multiple `@Test` methods, each opening its own `KoolBackend`,**
   which is the same bug one level down: `forkEvery = 1` gives a class one JVM, not one per
   method. Fixed by merging each class's scenarios into one method sharing one backend
   (`GlCaptureTest`, `GlCaptureDeterminismTest`, `GlOverlayIsolationTest`,
   `OverlayCaptureIsolationTest`, `OffscreenRenderToolsTest`), or — where the claim genuinely is
   about an *independent* backend lifecycle — splitting into its own class
   (`OffscreenBackendTest` → itself plus `OffscreenBackendSecondCreateTest`,
   `OffscreenBackendExplodingCaptureTest`, `OffscreenBackendShutdownTest`).

3. **`render.screenshot` deadlocked on every real host.** `AgentContext.answerLater` runs its work
   once, synchronously, in the same host iteration that queued it — correct for LibGDX's
   synchronous draw-and-readback, wrong for Kool's two-phase capture (`RenderPipeline`: a request
   is *claimed* at one frame's capture point and *read* only at the top of the next). On any host
   whose dispatch and render share a thread — every `Offscreen`/`Windowed` host driven by
   `KoolBackend.drive` — that blocked the one thread that could ever draw the second frame, and
   the capture always hit its 500ms grace and failed. Added `AgentContext.answerWhenReady(poll: ()
   -> AgentResult?)`, rechecked once per host iteration until it returns non-`null` or a deadline
   passes; moved `RenderToolset.capture` onto it. `answerLater` and every other caller is
   untouched. Full reasoning and the rejected alternative (reordering `AgentRuntime.afterFrame`
   generically, rejected as too wide a blast radius for this ticket) are on the issue.

One test-only fallout from fix 3: `OffscreenRenderToolsTest`'s merged rewind scenario started
failing on a 144-pixel diff after the merge, because `KoolBackend.drive` now pumps with the
frame's *real* wall delta (needed so a capture gets more than one real frame to settle) instead of
always zero — so a camera left mid-follow-ease by an earlier scenario in the same merged method
kept drifting in real time between two captures the rewind scenario expected to be pixel-identical.
Fixed by pinning the camera (`render.set_camera`, which also stops following) at the top of that
scenario, matching what the original, unmerged test implicitly got for free from a fresh boot.

`WallClockBudgetCensusTest`'s census updated for the two new files that read a wall clock
(`OffscreenBackendExplodingCaptureTest.kt`, and `OffscreenRenderToolsTest.kt`'s poll loop) — both
are deadlines, not latency budgets.

Decisions (this session's; four more from the earlier `dev-211`/`dev-211b` work are already on the
issue) are recorded on **issue #211** with what was rejected and why: the `answerWhenReady`
addition versus reordering `afterFrame`, and the GL-suite isolation fixes.

No `docs/contracts/` file was touched. No replicated component was added or removed, so
`net-protocol.lock` and `expected-generated-hashes.txt` are untouched.

## `sh gradlew build` — real output

Two runs. The first found the census gap above (from the new/split test files); the second, after
fixing it, is clean except for `moba`, which is the pre-authorized D9 red (spec D9 "big bang";
`moba` still draws with LibGDX until #212 moves it over — the lead's ruling on this issue says so
explicitly, dispatch wave 9).

Second run, every `FAILED` line in the log:

```
> Task :moba:compileKotlin FAILED
```

That's the only one. Full tail:

```
* What went wrong:
Execution failed for task ':moba:compileKotlin'.
> A failure occurred while executing org.jetbrains.kotlin.compilerRunner.btapi.BuildToolsApiCompilationWork
   > Compilation error. See log for more details

BUILD FAILED in 19s
751 actionable tasks: 18 executed, 733 up-to-date
```

(`BUILD FAILED` is Gradle's own verdict on the whole invocation because one task failed; the
correct read is "751 actionable tasks, 1 failed, and it's the named exception" — not "the branch
is red".) `moba:compileKotlin`'s errors are all `SpriteBatch2D`/`SpriteRegion` API mismatches in
`moba/src/main/kotlin/dev/wildware/moba/lane/LaneRender.kt` and eight sibling files — exactly the
LibGDX-shaped calls #212 exists to replace.

The GL run (xvfb) is the evidence command above, run separately per the "GL trap": `check` alone
does not exercise this, because `-Pudea.render.requireGl` defaults to `false` and `udeaGlTest`/
`udeaAgentGlTest` skip silently with no display. Both ran for real above.

## Images

In `/srv/ssd1/workspace/Udea/build/debug-screenshots/`:

- **`issue211-offscreen-black-boot.png`** — the very first `render.screenshot` from a freshly
  booted `Offscreen` host, before anything is spawned. Black, correctly: `entityCount` is 0 and
  debug draw defaults off, so there is nothing to draw. Proves the pixel path returns real,
  decodable bytes even for an empty frame (`w":320,"h":180` in the transcript below).
- **`issue211-offscreen-box.png`** — same host, after `world.spawn_blueprint` and
  `render.toggle_debug_draw`: a green debug grid and a white box. Proves the render path actually
  draws simulated state, not a constant.
- **`issue211-windowed-black-boot.png`** / **`issue211-windowed-box.png`** — the identical two
  shots from a `Windowed` host on port 7842, proving the same pixel path serves both modes (spec
  3.7's point: a capture always reads the offscreen target, never the window, in either mode).

## The issue, criterion by criterion

**AC1 — `udea-render` builds for JVM and Android with no LibGDX dependency (Wasm excluded per
#223).** Unchanged by this session (already true from the earlier `dev-211`/`dev-211b` work), and
reconfirmed: `commonMain`'s `dependencies {}` in `udea-render/build.gradle.kts` names only
`udea-core`, `udea-assets`, `kool-core`, `kotlinx-coroutines-core`, `kotlinx-atomicfu`. The one
`implementation(libs.gdx)` line in the module is `jvmTest`-scoped, for `GlFixtures`' negative
control that proves the headless bytecode scanner catches a real gdx type — explicitly not shipped
code, and the module's own build script says so in a comment next to it. `udeaVerifyModuleGraph`
and `udeaVerifyNoLegacyDependencies` (which include `UDEA-MG-008`, the LibGDX-specific ban on this
module) both ran green in the full build above.

**AC2 — GL tests under xvfb with `-Pudea.render.requireGl=true` green, including a test that a
capture never contains the agent overlay.** The evidence command above, green. The overlay-never-
in-capture test is `GlOverlayIsolationTest` (`udea-render`, a synthetic overlay against a direct
`FrameCaptureSlot`) and `OverlayCaptureIsolationTest` (`udea-agent-host`, the *real*
`AgentOverlayView` reached through the *real* tool surface, enumerating every declared
`CaptureToolDef` so a new capture route is covered automatically) — both ran and passed in the
command above; both assert the two-sided claim (identical capture on/off, and the window *not*
identical) rather than only the vacuous half.

**AC3 — Headless/Offscreen/Windowed render modes all start; `/health` reports each mode.** Ran all
three via `udeaPhase1Demo`/`udeaPhase1OffscreenDemo`/`udeaPhase1WindowedDemo` (ports 7843/7841/7842,
outside both the engine default range and `melon-merge`'s scan range). Transcripts:

*Headless* — `/health` reports the mode; `render.screenshot` is refused by name, not thrown or
stalled:
```
{"ok":true,"tick":356,"paused":true,"renderMode":"Headless", ...}
{"id":1,"ok":false,"error":{"kind":"no_render_context","message":"this process runs in RenderMode.Headless: there is no GL context to read pixels from, so the render toolset is not live. Drive the game through /state and the world tools instead."}}
```

*Offscreen* — `/health` reports the mode; `render.screenshot` succeeds and files a real PNG:
```
{"ok":true,"tick":221,"paused":true,"renderMode":"Offscreen", ...}
{"id":2,"ok":true,"result":{"artifactId":"cap_0000","path":".../udea-agent-artifacts-offscreen/cap_0000.png","w":320,"h":180,"tick":221,"region":null}}
```
Second capture, after spawning a box and turning on debug draw (`issue211-offscreen-box.png`):
```
{"id":5,"ok":true,"result":{"artifactId":"cap_0001", ..., "w":320,"h":180,"tick":298,"region":null}}
```

*Windowed* — identical shape, `renderMode":"Windowed"`, same two captures
(`issue211-windowed-black-boot.png`, `issue211-windowed-box.png`):
```
{"ok":true,"tick":253,"paused":true,"renderMode":"Windowed", ...}
{"id":2,"ok":true,"result":{"artifactId":"cap_0000", ..., "w":320,"h":180,"tick":253,"region":null}}
```

All three instances were stopped after their transcript was taken (`pkill` on the exact task name,
confirmed by a subsequent `curl` connection refusal) — nothing left running.

## Regenerated files

None. This ticket added or renamed test classes only; no `@Replicated` component was added,
removed or renamed, so `net-protocol.lock` and `expected-generated-hashes.txt` are untouched
(`udeaCheckProtocolLock` ran green in the full build).

## Open items for the reviewer

- `udea-render/src/jvmTest/.../gl/OffscreenBackendTest.kt`'s "gets a real context, drives frames,
  captures, and screenshots" is now one merged method rather than four; if that reads as doing too
  much per method, splitting it the way `OffscreenBackendSecondCreateTest`/
  `OffscreenBackendExplodingCaptureTest`/`OffscreenBackendShutdownTest` were split is a mechanical
  follow-up, not a design question — I merged rather than split there because none of those four
  claims needs an *independent* backend the way the other three do, and splitting them anyway
  would have meant four more files for no isolation benefit.
- `AgentContext.answerWhenReady` is new public surface, next to `answerLater`. It has no test
  of its own beyond `RenderToolset.capture`'s use of it; if a future caller needs the same shape,
  a direct unit test for the retry/deadline mechanics in `udea-agent` would be worth adding then.
