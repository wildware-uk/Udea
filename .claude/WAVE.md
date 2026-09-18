# Wave handoff — 2026-09-16, restart onto Kool + Kotlin Multiplatform

## kmp baseline

**SHA `18bb13f`** (kmp after #224 merge; merged tree byte-identical to the trial tree `41140c7`, so the trial
build IS the merged build), refreshed 2026-09-18 with
`ANDROID_HOME=$HOME/Android/Sdk JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem sh gradlew build --continue`:

**Failing tasks: `:moba:compileKotlin` ONLY** - and every moba task downstream of it does not run.
That is the authorised D9 red: moba still draws with LibGDX until #212 ports it. Nothing else fails.
A reviewer or trial merge sees exactly that one red and treats any other as the branch's.

Earlier: `87d8b7c` (kmp after #211 merge), same single red. Before #211 the baseline was fully green: `52e92aa` (after #225), `0befdec` (kmp after #215 merge; trial tree identical, root build + build-logic check green); earlier `73a09e5` (kmp after #219 merge; trial tree identical, root build + build-logic check green); earlier `fcdeb63` (kmp after #193 merge; trial tree identical, root build + build-logic check green); earlier `303abe7` (kmp after #217 merge; trial tree identical to merged tree, root build + build-logic check green); earlier `25cc650` (kmp after #218 merge; trial root build + build-logic check green); earlier `47ec3b9` (kmp after #208 merge; trial root build + build-logic check green); earlier `89e6113` (kmp after #220 merge; trial root build + build-logic check green); earlier `e9639e0` (kmp after #207 merge; trial root build + build-logic check green; `6d95f67` #216, trial tree identical, root build + `-p build-logic check` both green; `dc6c708` #209; `236ad47` #206; before: `abba97b` #205, `4ca994d` #204, `a634450` #203), refreshed 2026-09-16; first taken at `6097ae7` on a detached checkout with
`JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem sh gradlew build --continue`:

**BUILD SUCCESSFUL. Failing tasks: none.**

**Plus, outside root `build`:** `sh gradlew -p build-logic check` GREEN since #216 (`6d95f67`). History: was RED on `07eddef` (CI runs it):
`OuterBuildInputsTest > every repository file a build-logic test names is a declared input` - #203 left
udea-core KMP paths undeclared. Baseline failure, not any wave-4 branch's. Filed #216, dispatch in wave 5.
Reviewers and trial merges: run `-p build-logic check` too; that one test failing is baseline.

**CI baseline reds on kmp (run 35160551639, 07eddef):** migration ledger + 4x determinism (all #216), and
`clean build under budget` (ratio 1.116; it compares against retired `origin/example`, filed #218). Not branch findings.

Since #201 the build needs an Android SDK: add `ANDROID_HOME=$HOME/Android/Sdk` to every build command (developers, reviewers, trial merges). Without it: `SDK location not found`. That is environment, not a red build. Refresh after every merge.

## Wave 6 (2026-09-17): done

- Dispatched: #217 determinism TimeSource (dev-217, build-logic), #219 UDP straddle Position desync (dev-219, udea-net + moba UDP client), #193 editor.* tools (dev-193, udea-agent, udea-agent-host, MobaAgent, udea-core NetIdIndex/LevelService).
- #215 held to wave 7: vendoring Fleks moves the Fleks pin (`determinism-allowlist.txt`, ALLOW005) that #217's scanner code owns, and touches udea-core beside #193. Commented on #215.
- #193: port of the pre-restart branch `issue-193-editor-tools` (worktree agent-a2c3e8e4459ef1aea); new branch `issue-193-editor-tools-kmp`. Previous developer's five rulings on #193 stand.
- #219: issue had no acceptance criteria; lead set them (root cause named, deterministic straddle test red on origin/kmp, >=20 runUdpProof runs reported).
- CI checked: kmp run 35175857880 (7073adc) budget base = first parent 25cc650, ratio 1.030. Migration ledger + determinism jobs now green. Windows latency `CharacterMoverBudgetTest` (median 7.3ms vs 4ms) failed once, rerun green: flake. Whole run green.
- #217: merged `303abe7`, round 1 PASS (no findings). DET001 gains TimeSource.Monotonic + mark elapsedNow/hasPassedNow; DET003 gains kotlin.time/kotlinx.datetime Clock.System. udeaVerifyGasTime kept (not equivalent). Dropped as cards (commented on #217, audit row): inlined-code spans past EOF; klib-only source sets unscanned. Worktree kept: `.claude/worktrees/agent-ab6b17f2ef1d868c5`. #215 now free of #217 collision.
- #193: merged `fcdeb63`, round 1 PASS (no findings). Cherry-picked pre-restart branch onto KMP; editor toolset commonMain; editor.save via kotlinx-io (udea-agent api dep), browser host gets typed no_level_store. Live runs need `-Pudea.render.mode=Headless` on this box. Dropped cards: entity_gone-after-rewind test, unit test normal run hides editor.*. Worktree kept: `.claude/worktrees/agent-a95a8d0403add24ba`. #194 still needs #210/#211/#212.
- #219: merged `73a09e5`, round 1 PASS (no findings). Root cause: late ack re-tracked a dead generation whose index a new creep reused; pending Destroy withheld the new occupant (frozen at spawn = Position only). Fix: occupant Create replaces dead generation client-side, Destroy only if Create not sent. Also pending-send overflow (>32) ended on any ack; now only on ack covering the unfitted send. runUdpProof lossy: old 6/30 Position-only, fix 0/50. HANDOFF.md's 'runUdpProof RED' note is now stale. Dropped cards: generation regression inside one ack (unobserved); MobaStraddledPollTest seeds 2/9 may need re-pick if level changes. Worktree kept: `.claude/worktrees/agent-a626c4bc6ebbd60ae`.

## Wave 7 (2026-09-17): done

- composegl-ef replied: composegl-kool free. Must also touch root build.gradle.kts `published`, `.github/ci-legs.json`, ci.yml OpenGL job. Avoid WorldPanel.kt/ScenePass.kt (composegl #230). Model: KorgeCanvas (HostState.Restore, maxSceneSize), SceneView proof c61c2a30. `--max-workers=2`, never `--stop` there. Land squashed+rebased `push origin HEAD:master`; snapshot via `release.yml -f kind=snapshot` only.
- #210 split: JVM desktop + SceneView + snapshot here; Wasm/Android + Wasm demo -> #222 (added to epic). #211 needs #222 too.
- #215 ruling: vendor Fleks 2.14 as `udea-fleks` (Central latest 2.15, no iOS). Commented.
- Dispatched: dev-215 (Udea worktree, branch issue-215-vendor-fleks), dev-210 (composegl-wt/kool-210, branch issue-210-composegl-kool).
- #210: landed composegl master `1086a586`, round 1 PASS (no findings). Draft PR composegl#232 (CI only; composegl CI does not run on branch pushes) closed. Master CI 35183141663 green, snapshot release 35183678529 green: `dev.wildware.composegl:composegl-kool:0.7.0-SNAPSHOT`. Wiki pushed. Kool must start with `renderBackend = RenderBackendGl`. Worktree kept: `/srv/ssd1/workspace/composegl-wt/kool-210`. Next wave: #222 (composegl, Wasm/Android).
- #215: merged `0befdec`, round 1 PASS (no findings). Fleks 2.14 vendored as `udea-fleks` (byte-identical to tag, reviewer diffed). iOS on: udea-fleks, core, gas, audio, replay (CI ios-tests 570 tests). udea-net + udea-agent stay no-iOS (expect with no native actual). UDEA-MG-007: udea-fleks deps stdlib + serialization-core only. Determinism pin = 2.14+sha256 of vendored source; DET002 flags Fleks random() picks. CI `clean build under budget` red once (1.636, accepted by lead, commented); merge push goes red once too, next kmp push should be green - CHECK. Worktree kept: `.claude/worktrees/agent-a5908c0877dd4f81f`.
- Dropped cards: stale "Fleks requests stdlib 2.3.21" KDoc in udea.kotlin-base.

## Wave 8 (2026-09-17): done

- composegl-ef cleared #222: nothing in flight; standard wasmJs/android task names need no ci-legs edit; add karma.config.d (chromium --no-sandbox); WebGL1 task by name if Kool supports it; Kool Wasm demo out of `published`, pages.yml untouched; one snapshot release after landing. Dispatched dev-222 (worktree composegl-wt/kool-222, branch issue-222-composegl-kool-wasm-android).
- BLOCKER: Kool 0.19.0 (latest, 2025-12) publishes no wasmJs; wasm on Kool main since 2025-12-24, unreleased, main uses jvmToolchain(25). Filed #223 (options: self-publish pinned Kool main / wait 0.20 / Kotlin/JS / desktop+Android first). Interim: #222 ships Android only; #211 AC1 planned JVM+Android, Wasm to #223. Wave 9 candidate: #223 spike (option 1).
- #222: round 1 PASS (no findings). Landed composegl master `d8f2da9a` (fast-forward, one commit; PR #233 shows merged). Wiki pushed. composegl-kool jvm + android (AGP KMP library, ContextGl expect object, AndroidFonts, `android` emulator CI job). Master CI 35190562150 green; snapshot release 35191171225 green: composegl-kool, -jvm, -android 0.7.0-SNAPSHOT on Sonatype (verified metadata). #222 closed. Dropped: CLAUDE.md release file count for -android publication (composegl doc), tap-in-one-frame Kool upstream. Worktree kept: `/srv/ssd1/workspace/composegl-wt/kool-222`. kmp CI run 35186519448 (7d4ebad): whole run green, clean-build budget green again.

## Wave 9 (2026-09-17): in flight

- Baseline unchanged: `5f7c3cf` fully green (last code merge `0befdec`).
- #211 split (commented): #211 = GameHost, SpriteBatch2D, camera, capture, overlay, render modes; input + composegl-kool UI host -> #224 (needs #211). Targets JVM + Android.
- #211 ruling (commented): moba may go red on this branch (D9 big bang; repaired by #212). Brief lists them as expected reds. Everything else, udea-agent-host included, stays green. Trial merge rule for #211: baseline + those named moba reds only.
- #223 option-1 spike filed as #225 (spikes/kool-wasm/ only, publish nothing).
- Dispatched: dev-211 (branch issue-211-udea-render-kool), dev-225 (branch issue-225-kool-wasm-spike). Disjoint. Stopped 2 idle Udea Gradle 8.13 daemons first (19G free after).
- Epic #199 checklist gained 6c #225 and 7b #224.
- 2026-09-18: dev-211 and dev-225 both killed by an API weekly limit on Opus (resets Sep 22 16:00 UTC) after a day's work. Work survived on disk. Took over on **sonnet**: dev-211b and dev-225b, pointed at the SAME worktrees/branches (`agent-a27b81d547010a2d9` = issue-211-udea-render-kool, 120 uncommitted changes, told to WIP-commit first; `agent-aee3e20c78d24843c` = issue-225-kool-wasm-spike, 3 commits, README says ANSWER YES).
- #225 preliminary answer (from its README, not yet reviewed): Kool main pinned `ab762acd` builds kool-core for jvm (major 65), android and wasmJs with a 13-line build-script patch in 3 files, no source change; a Kotlin 2.4.20 consumer compiles against it and draws in headless Chrome on WebGL 2. Reviewer must confirm.

- **#225 merged `3544c40`** (brief `52e92aa`), round 1 PASS, no findings. Answer YES: Kool main `ab762acd`, 13-line patch in 3 build files, no source change, jvm major 65 + android + wasmJs; Kotlin 2.4.20 consumer reads both; WebGL 2 draw in headless Chrome. Nothing published. Reviewer reproduced the whole run. Trial + merged kmp both green (0 failing tasks). Baseline refreshed: **`52e92aa`, failing tasks: none**; `-p build-logic check` green. Worktree kept: `.claude/worktrees/agent-aee3e20c78d24843c`. Dropped as cards: duplicate #223 answer comments (dev-225 and dev-225b each posted one).
- #223 now has the option-1 answer and recommendation; the owner's call on which option to take is still open.

- **#211 merged `b708767`** (brief `87d8b7c`), round 1 PASS, no findings. udea-render off LibGDX: Kool GameHost, SpriteBatch2D (instanced quads + KslUnlitShader), orthographic camera, OffscreenPass2d capture to PNG, agent overlay moved out of udea-agent-host as an OverlaySystem. JVM + Android only. 159 files, most of them moves into commonMain.
  - Two real defects found on the way, both fixed here: (1) the GL suite had NEVER run - its availability probe created and closed a throwaway Kool context, spending the one-context-per-process allowance, so every GL test failed before its body and blamed a missing display; now it checks `$DISPLAY`, and several test classes that opened two contexts per JVM were merged/split, plus a missing `forkEvery = 1` on udea-agent-host's GL test task. (2) `render.screenshot` DEADLOCKED on every real Offscreen/Windowed host: `answerLater` runs its work synchronously in the tick that queued it, which suited LibGDX's one-call readback and not Kool's two-phase capture. New `AgentContext.answerWhenReady` polls once per host tick; `answerLater` untouched.
  - Reviewer re-ran the xvfb evidence command and read the JUnit XML: 10 GL test classes executed, 0 skipped, 0 failed, both overlay-isolation tests assert real pixels in both directions. `CAPTURE_GRACE_MILLIS` is wall-clock but unchanged from origin/kmp and lives in `AgentRuntime.afterFrame`, outside `Simulation.step()` - pre-existing, not an item-20 violation.
  - Trial and merged kmp both show `:moba:compileKotlin` as the only red. Worktree kept: `.claude/worktrees/agent-a27b81d547010a2d9`.
  - Dropped as a card: AGENTS.md's Kool-port paragraph still says "udea-render will apply" in future tense (not the module table; udeaVerifyAgentsMd green). Fold into #212 or #213.
- **Wave 9 is done. #224 (input + composegl-kool UI host) and #212 (moba split) are the wave 10 candidates; #221 is now unblocked too.**

## Wave 10 (2026-09-18): in flight

- Baseline unchanged: `87d8b7c`, one red: `:moba:compileKotlin` (authorised D9; #212 repairs it).
- **#212 split (commented):** `:moba:web` + `runWebShot` moved to new issue **#226** (needs #212 and #223).
  #212 keeps `:moba:game`, `:moba:desktop`, `:moba:android` and must leave zero failing tasks.
  Reason: #211 shipped udea-render on Kool for JVM+Android only, and #223 (no published Kool has a
  wasmJs artifact) is open - a web AC nothing can satisfy fails review by construction.
- **#212 ruling (commented):** input wiring belongs to #224, not #212. `:moba:desktop` takes the input
  seam on origin/kmp at branch time; shot tasks are harness-driven, no keyboard. dev-212 told not to
  edit udea-render; dev-224 told not to edit moba/settings.gradle.kts.
- **#223 decided: option 1** (build Kool `main` pinned `ab762acd` with the 13-line patch, publish
  ourselves). Commented with the alternatives and how to overturn. Kool is Apache-2.0.
  **NOT dispatched - two owner gates, raised by `composegl-ef` and put to Shaun on the dashboard:**
  (a) publishing a patched third-party library under his `dev.wildware` Sonatype namespace is his call;
  (b) **the snapshot-to-release trap** - a released `composegl-kool` wasmJs target cannot depend on a
  Kool snapshot, so before that target lands one of: permanent release of the patched Kool under our
  namespace / wasmJs left out of the next ComposeGL release / Kool 0.20.0 shipped.
  Hosting revised on composegl-ef's advice: a **manually triggered workflow** with the pin+patch+NOTICE
  in `third_party/kool/`, NOT a subproject (a subproject makes every ComposeGL CI run build Kool and
  puts a foreign renderer past the architecture confinement checks). Do not touch ComposeGL's version
  logic; `javap` in the publish job AND on the jar a consumer resolves; `karma.config.d/` needed but no
  ci-legs.json edit; WorldPanel.kt / ScenePass.kt still off limits (ComposeGL #230).
- **#221 held to wave 11**: it lives in `udea-render`, which #224 owns this wave. Same-module rule.
- Dispatched: dev-212 (branch `issue-212-moba-split`), dev-224 (branch `issue-224-kool-input-ui-host`).
  Box at dispatch: 24 cores, load 1.4, 19G free / 24G available. Two developers only.
- Epic #199 gained 8b (#226); row 8 reworded to drop web.
- **CI baseline on kmp (run 35390467276, `aaacad4`, checked 2026-09-18): 13 jobs red, ALL of them the same
  `:moba:compileKotlin` D9 red.** Confirmed from the logs: every failure is moba source against APIs #211
  replaced - `Unresolved reference 'projectionMatrix'/'color'/'combined'/'viewportWidth'` on `SpriteBatch2D`
  and `Camera2D`, `Texture` where `RenderResource` is wanted, `TextureRegion` where `SpriteRegion` is,
  `Unresolved reference 'Scene2dUiScreen'` in `MobaHud.kt:370`. Red jobs: build (ubuntu, windows), build with
  the K2 plugin disabled, the FIR checkers fail a real build, clean build under budget, 4x determinism,
  3x replay-equality, latency budgets (windows). **Green and worth noting as green:** gl tests (xvfb),
  iOS simulator tests, agent brief matches the tree, game-bridge-mcp conformance, migration ledger,
  KSP stays incremental, latency budgets (ubuntu). **#212 is what turns all 13 green.** Not a branch finding
  for anyone this wave.
- **Owner ruling on #212 parity (dashboard, 2026-09-18):** "Yes but the UI would look slightly different
  because of compose gl so account for that." So: the pixel threshold covers WORLD content only; UI regions
  are masked before the diff and the brief names which and why; inflating one whole-frame threshold until the
  UI difference fits under it is explicitly rejected (it would hide a world regression behind a cosmetic one);
  a UI difference is not a parity failure, a world difference is; a missing/partial HUD in the Kool shots is
  EXPECTED at this SHA (#224 brings the UI host, #188 ports MobaHud and is undispatched) and is named, not
  fixed; unmasked side-by-side collages still go to the gallery - the owner comparing scenes is the primary
  evidence, the threshold is secondary. Relayed to dev-212 and commented on #212.

- **#224 blocker found and routed (2026-09-18):** `composegl-kool` 0.7.0-SNAPSHOT honours the `InputSink`
  contract for keys/pads but NOT pointers. `ComposeGlScene` installs its own private `InputStack` pointer
  listener in `init`, calls `KoolPointerInput.onFrame(...)` a frame later on the render thread and
  **discards the `used` boolean**, and never calls `Pointer.consume()`. So `isConsumed()` is always false
  and Udea cannot learn whether the UI took a click; asking `scene.player` ourselves double-delivers.
  Asked composegl-ef for the upstream 3-line fix (`if (used) pointer.consume()`) + a fresh snapshot.
  **BOTH OF THOSE WERE WRONG and are withdrawn.** composegl-ef checked the code: the listener
  (`ComposeGlScene.kt:134`) runs on Kool's UPDATE thread and only samples; the toolkit handles pointers at
  the start of the next RENDER (`:169`), where `used` first exists - a frame later, another thread. So
  `consume()` cannot go in the listener. And the fallback was a DATA RACE, not merely fragile: `scene.player`
  goes straight to `KeyRouter`/`KeyNavigator`/`GamepadNavigator`, which read and move `FocusManager` and the
  node tree, none thread-safe, none marshalling. Rule: **every `scene.player` call must be on the render
  thread**, keys and pads included. The KDoc never said so; composegl is fixing that KDoc and `Input.md`.
- **#224 input DECIDED - composegl-ef's option 2, one seam.** After `onFrame`, on the render thread, the
  scene reports per pointer whether the UI took it; plus a **render-thread hook just before each frame** in
  which a consumer drains queued key/pad events, calls `player` and gets `used` immediately. Being built
  with tests, then a snapshot. **Rejected option 1** (thread-safe prediction published each frame so Kool's
  own `isConsumed()` is right same-frame): needs new public hit-query API on composegl-ui plus an owner gate,
  and buys a same-frame `isConsumed()` nothing in Udea reads. **Accepted cost:** Udea's pointer translation
  moves to the render thread after the report, so pointer intents land ONE FRAME LATER. Fine - 60Hz fixed
  sim, decoupled render, inputs stamped in `Tick`; determinism rests on the stamped tick, not wall-clock.
- **#224 threading, CORRECTED by dev-224 from Kool's source:** Udea's key path is NOT racy and never was.
  `udea-render`'s `KoolThread.config()` passes **`asyncSceneUpdate = false`** (so sprite batches are not read
  while a renderer writes them). With it off, Kool 0.19.0's `Lwjgl3Context.renderFrame()` never sets
  `nextFrameData`, so `render()` - holding `Input.poll(this)` and the `onRender` callbacks - runs INLINE on
  the same thread that then calls `backend.renderFrame(...)` where `ComposeGlScene.render()` touches the
  toolkit. One thread, `udea-kool`. Udea's input callback IS the render thread: it satisfies the rule.
  Android: both are the `GLSurfaceView` thread. So the key/pad half proceeds as written and does NOT migrate
  onto the new hook (churn for a property already held). Guarded by a GL test recording
  `Thread.currentThread()` at the Kool input callback and inside the scene's render, failing if they differ -
  which reddens the day somebody flips `asyncSceneUpdate` back on. The KDoc names that test and names the
  hook as the migration path. **"One thread" does NOT buy a same-frame verdict:** frame ordering is
  poll -> Udea's tick -> ComposeGL's `onFrame`, so the verdict on frame N exists only after N has ticked.
  One frame late for an ORDERING reason, not a threading one. All commented on #224.
- **Kool fork NOT approved.** The owner dismissed the question rather than answering; composegl-ef correctly
  read that as a no. Nothing published, no publish path in composegl. It has since come back round: the owner
  asked on the dashboard what the browser needs, and the lead put it to him plainly (no released Kool has a
  wasmJs artifact; the only route is publishing the patched pinned build ourselves; his yes or no), with the
  release trap attached. Owner also ruled: **the browser frontend is Udea (`:moba:web`), not a Kool app** -
  Kool is the renderer only. That is already the design (module-graph gate).
- **Repository defect found by dev-224:** `composegl-kool:0.7.0-SNAPSHOT` resolves from
  `https://central.sonatype.com/repository/maven-snapshots/`; **both Sonatype hosts `build.gradle.kts`
  declares today 404.** Fix is in #224's branch. One-sided: composegl's wiki (`Kool.md:22`) already names
  the right host, so this is Udea's build file being stale, not composegl documenting the wrong one.
- **#212 plan change, approved:** dev-212 ports `MobaHudSystem` off scene2d onto `udea-render`'s existing
  `BitmapFont2D` rather than deleting the HUD, so parity compares a HUD against a HUD. Not scope creep -
  `MobaHud.kt:370` cannot resolve `Scene2dUiScreen` since #211, so moba cannot compile until scene2d goes.
  Interim only; #188 now replaces `BitmapFont2D` drawing rather than scene2d (commented on #188).
  `HudState` stays untouched. Reference PNGs captured from `origin/master` `409c044`, 9 frames, in gallery.

- **WEB IS SHELVED (owner, 2026-09-18).** He asked "Does kool not have a snapshot we can use? If not, then
  shelve web for now, make a note." Checked: `de/fabmax/kool/kool-core/maven-metadata.xml` is **404** on
  `central.sonatype.com/repository/maven-snapshots/`, on `oss.sonatype.org/.../snapshots` (host retired 2025)
  and on `s01.oss.sonatype.org/.../snapshots`; Maven Central releases 200 with `<latest>0.19.0</latest>`,
  `lastUpdated 20251220180520`. Kool is CONFIGURED to publish snapshots and never has (`build.gradle.kts`
  `version = "0.20.0-SNAPSHOT"`, `publishToMavenCentral()`, README points at the retired host).
  **That README line is stale - do not chase it again.**
  **#223 and #226 are parked**, retitled `[SHELVED]`, commented, and marked in epic #199. Unshelve on any of:
  Kool 0.20.0 shipping with a wasmJs artifact (watch `<latest>` in that metadata URL); the owner reversing
  the publish decision (`spikes/kool-wasm/` holds pin `ab762acd`, `toolchain-21.patch`, `reproduce.sh`,
  transcripts - nothing to rediscover); or Kotlin/JS chosen instead.
  **Nothing else is affected:** #212 and #224 were already JVM+Android; web was never in `sh gradlew build`,
  so #214 does not wait on it; every merged `wasmJs` target stays (core, gas, assets, replay, audio, net,
  agent). Spec D1 is DEFERRED, not overturned.
- **Route B exists and was put to the owner too** (composegl-ef corrected the lead for calling the fork "the
  only route"): Kool 0.19.0 publishes a Kotlin/JS build, so no fork and nothing published under his name -
  but ComposeGL's browser target is Wasm only, so it needs a Kotlin/JS target adding as well, and so do nine
  Udea modules, against seven that already build wasmJs. Lead recommended Route A on that asymmetry; the
  owner shelved instead.

- **#224 SPLIT: pointer half -> new issue #227.** The composegl-ef session was CLEARED and lost the
  per-pointer report it had promised - no branch, no commit, no memory of it, master still `d8f2da9a`, no
  snapshot since. It advised splitting rather than holding, and the lead agreed: no snapshot, no honest ETA.
  The full option-2 spec was re-sent to composegl-ef from the lead's record so it can be rebuilt.
  **#224's revised ACs (commented): key/pad intents + UI first refusal, ComposeGL screen over the Kool scene
  and absent from captures, module-graph gate.** All three already done and proved at `97b0ca4`.
  #224 must state in BRIEF.md what a user experiences with pointers unhandled - known-wrong or absent.
  **NOTE for composegl:** Udea no longer needs the render-thread key/pad hook (`asyncSceneUpdate = false`
  puts Udea's key path on the render thread already); only the pointer report is wanted. Told them so.
- **#224 findings worth keeping:** `UiFonts` (abstract, internal constructor) instead of exposing
  `AtlasFonts` - keeps a renderer off every game's compile classpath, confirmed correct by composegl-ef;
  `RenderModuleGraphTest` asserting dependency SCOPES because the gate cannot see a frontend arriving
  transitively; `android.useAndroidX=true` needed (AGP refuses `androidx.compose.runtime` from
  `composegl-ui`, and udea-render has an Android target); `WallClockBudgetCensusTest` census rows for two GL
  tests that spin-wait on `System.nanoTime` for a frame deadline.
- **CROSS-TICKET, relayed to dev-212 as urgent:** Kool's key table is not LibGDX's.
  `moba/assets/control/controls.udea.kts` holds gdx literals (`KeyW = 51`); under Kool 51 is `'3'` and W is
  `'w'.code` = 119. It compiles, the asset validates, nothing catches it - a wrong binding is invisible in a
  screenshot. dev-212 owns the fix (it is in moba), told to use named constants not fresh literals and to
  prove the bindings with a test or transcript. dev-224 rejected an engine-owned `UdeaKeys` table for its
  own ticket and commented why on #224.

- **Key-code dispute, routed not adjudicated.** The lead relayed dev-224's "W is `'w'.code` = 119".
  dev-212 disputed it from bytecode: `GlfwInput` builds the universal code as
  `KEY_CODE_MAP[glfwKey] ?: UniversalKeyCode(glfwKey)`, `KEY_CODE_MAP`'s 41 entries are all SPECIAL keys, so
  a printable key is the raw GLFW int - and `GLFW_KEY_W = 87` (GLFW letters are ASCII UPPERCASE).
  `localKeyCode` is also uppercased (`Character.toUpperCase(glfwGetKeyName(...)[0])`). 119 is `'w'.code`,
  which GLFW never sends. **dev-212 proceeds on 87/65/83/68/81/69/82/32**, written as `'W'.code` not bare
  ints, with a per-key test driving `DeviceIntent` and asserting movement. dev-224 asked to settle it
  empirically from its running `GlKoolInputTest` and to say WHICH FIELD it matches (universal vs local -
  they agree on GLFW, may not elsewhere) and whether its test would pass with the other number.
  Lesson: the lead should have questioned a lowercase-ASCII *key code* before relaying it.
- **SETTLED: W is 87.** dev-224 measured it and retracted its own number without softening. Source of the
  119: Kool's `UniversalKeyCode` has a convenience constructor
  `constructor(codeChar: Char) : this(codeChar.lowercaseChar().code)` - a helper for callers that GLFW never
  goes through. Reading it as a description of the table is the mistake.
  **Second fact, and it matters more:** printable keys are raw GLFW ints, but SPECIAL keys go through
  `KEY_CODE_MAP` and become **Kool's own NEGATIVE codes** - `GLFW_KEY_ESCAPE` is 256 but Kool's `KEY_ESC` is
  **-9**. Map covers ctrl/shift/alt/super/escape/menu/enter/numpad/backspace/tab/delete/insert/home/end/
  pageup/pagedown/cursors/F1-F12. So ONE integer field holds two incompatible schemes with nothing saying
  which - an independent second reason for #228. dev-212 told to check moba's asset for any non-letter
  binding. `KoolKeyboard` reads the UNIVERSAL code (`event.keyCode.code`), the physical key - correct half.
  lwjgl on this box is **3.4.3**, not 3.3.6; constants agree.
- **dev-224 found its own evidence hole and is fixing it rather than writing it up:** `GlKoolInputTest` built
  the `KeyEvent` itself and bound the same code, so it passed for ANY number - it proved the path and nothing
  about the table. Now driving Kool's real GLFW key callback with raw `GLFW_KEY_W`, asserting
  `isKeyDown(GLFW_KEY_W)` true AND `isKeyDown('w'.code)` false, so the wrong belief cannot come back green.
  dev-212 asked whether its own per-key test has the same hole (supplying the code it asserts).
- **MEASURED, settled for good.** dev-224 drove Kool's installed GLFW key callback with raw `GLFW_KEY_W`
  (retrieved via `glfwSetKeyCallback(glfwGetCurrentContext(), null)` on the render thread and restored
  straight after - GLFW has no getter) and observed the code where `UiLayer` is offered it. Negative control
  bound `'w'.code`: `"W never became an intent. ... The interface saw [87]"`. **THE TABLE:**
  letters/digits/space = raw GLFW constant, ASCII UPPERCASE (W 87, A 65, S 83, D 68, Q 81, E 69, R 82,
  space 32); the 41 SPECIAL keys = Kool's own NEGATIVE codes via `KEY_CODE_MAP` (**Escape is `-9`, NOT
  GLFW's 256**) and must be written `KeyboardInput.KEY_*.code`. `KoolKeyboard` reads the UNIVERSAL code.
  lwjgl on this box is **3.4.3**. All relayed to dev-212, which is auditing moba's asset for non-letter
  bindings.
- **TEST-DESIGN LESSON, applies beyond this wave:** a binding test that SYNTHESISES the event it then binds
  against passes for any number - it proves the path and asserts nothing about the table. The fix is the
  negative: assert the WRONG code produces no intent. Both developers now carry it; recorded on #228.
- **Filed #228** (udea-render owns the key table; games stop writing backend key codes). Cleared the bar:
  it compiles, validates, gates green, and the game responds to the WRONG KEYS - invisible to screenshots.
  `InputBindings.keys`' KDoc still claims "com.badlogic.gdx.Input.Keys codes", which is false and is what
  makes the next game write gdx numbers. Needs #224. Added to epic as 7d.

- **Owner asked to run 4 developers. Dispatched a THIRD (#229); a fourth is not safely available.**
  Every other open ticket either collides with #212 (`moba`) or #224 (`udea-render`), or is blocked by
  them: #221 and #228 are udea-render (and collide with each other); #227 waits on a composegl snapshot;
  #213 deletes `example/`, which `:moba:udeaStageCharacterArt` still reads the character art out of, so it
  cannot land before #212; #188/#192 are moba; #194-#196 need #212; #189 needs #188 AND edits
  `udea-render/src/test/.../BannedOwner.kt`; #223/#226 shelved. Checked the old-tree edges directly: only
  `example` depends on `common` and `gradle-plugin`, so #213 is genuinely one unit and cannot be split to
  free a slot. Box was also at **load 26.4 on 24 cores** with 12G available when asked.
- **Filed and dispatched #229** (dev-229, branch `issue-229-nightly-branch-trigger`, `.github/` only):
  `ci.yml:1746` gates replay-equality-nightly on `github.ref == 'refs/heads/example'`, a branch retired
  2026-09-16 - a push clause that can never fire. Worse than tidy-up: the surviving `schedule` clause runs
  on the DEFAULT branch, `master`, the pre-port LibGDX tree. So the nightly verifies the engine being
  replaced while the port's own replay determinism has no nightly coverage - and replay equality is exactly
  the gate that catches determinism drift across JDK vendors and OSes, the defect class a KMP port
  introduces. Cleared the "file it" bar as a gate that cannot fire. dev-229 authorised to push ITS OWN
  branch to origin (the deliverable is a real `workflow_dispatch` run); told not to delete the `example`
  branch.
- **Slot 4 opens on the next merge.** #224 frees `udea-render` for #221 or #228 (not both - same module);
  #212 frees `moba` for #188/#192 and unblocks #213 and #194.

- **#227 UNBLOCKED, dispatch on the next merge.** composegl-kool `0.7.0-SNAPSHOT` from **`007ea1cf`**
  carries the pointer report: `ComposeGlScene.onPointerUsed: ((PointerUse) -> Unit)?` with
  `data class PointerUse(val pointer: Int, val frame: Int, val used: Boolean)`. Fires on the RENDER thread
  at the start of the ComposeGL scene's render, ONCE PER VALID POINTER PER KOOL FRAME (used or not,
  including a final report when a finger lifts or the mouse leaves). **`frame` is `Time.frameCount` from
  when the LISTENER READ the pointer, not when the report arrives** - the detail most likely to produce an
  off-by-one that only shows under load. `isConsumed()` stays false, deliberately (option-1 rejection).
  Confirms the one-frame ordering from the other side: under `asyncSceneUpdate = false` it fires AFTER the
  game's update for that frame. composegl skipped the render-thread key/pad hook entirely - dev-224's
  `asyncSceneUpdate` finding made it unnecessary, and their docs now tell default-Kool consumers to queue
  and hand over on the render thread. Wiki gained the render-thread rule on `player`, the `Input.md` rule,
  the `Kool.md` "holds only under the default" correction, a "Did the interface take that click?" section
  and the `android.useAndroidX=true` line. Full API recorded on #227.
  **Not dispatched: it is `udea-render`, which #224 owns until it merges.**

- **#224 MERGED `18bb13f`**, round 1 PASS, no findings. Kool key/pad input to intents with UI first refusal,
  `composegl-kool` UI host (`UiLayer`, owned by `KoolBackend.show` on the render thread), capture isolation
  proved structural AND measured mid-redraw (all five captures hash `cdb6bdc8...`), `RenderModuleGraphTest`
  scope fence. Trial: build red ONLY on baseline `:moba:compileKotlin`; GL suite under xvfb with
  `-Pudea.render.requireGl=true` green. Merged tree == trial tree (`41140c7`). Baseline unchanged.
  Worktree kept: `.claude/worktrees/agent-a01eb6c762a84065b`.
  **Out-of-scope card the reviewer raised, CONFIRMED by the lead and filed as #230, not dropped:**
  `KeyTable.kt` (new in #224) line 29 keys letters with `UniversalKeyCode(letter)` over 'a'..'z' - the
  lowercasing constructor - so W maps to 119 not the 87 GLFW sends. Every letter reaches a focused ComposeGL
  control as `Key.Unknown`, is not taken, and leaks through as a game intent: typing in a text field also
  moves the player. #224's tests only pressed Escape (special-key map, correct). Latent - moba has no
  ComposeGL screen until #188 - so merging on the PASS was safe; #230 must land BEFORE #188. The reviewer's
  out-of-scope label was generous (it is new code breaking the ticket's own AC1 for letters) but the PASS
  is the sign-off and the lead did not overrule it.

- **Dispatched #230** (dev-230, branch `issue-230-keytable-letters`, `udea-render` only) the moment #224
  merged - fix before feature, so it goes AHEAD of #227 (same module; only one at a time). Told it to key the
  table on what GLFW actually sends rather than lowercase incoming codes (that would make the table agree
  with itself while disagreeing with Kool about what a key code is); extend `GlKoolInputTest` rather than add
  a class (one Kool context per JVM); drive Kool's REAL GLFW callback for all 26 letters; prove the
  lowercase mutation red; check line 86's punctuation `forEach`, which uses the same constructor; and prove
  an UNFOCUSED letter still reaches the game, so fixing the UI does not break movement.
- **In flight: 3** (dev-212 moba, dev-229 .github, dev-230 udea-render). A 4th is still not available:
  #227/#221/#228 are all `udea-render` (dev-230's), and everything else needs #212. Slot 4 opens when
  #212 merges (frees moba; unblocks #213/#188/#192/#194) or #230 merges (frees udea-render for #227).
- Xvfb at merge time: one server, 49s old, a live developer's - left alone.

## Wave 10 plan

- Ready: #212 (moba, scoped down), #224 (udea-render).
- Held: #221 (udea-render, collides with #224) - wave 11. #223 (owner-gated, above).
- Blocked: #226 needs #212 and #223. #192 needs #212. #194-#196 need #212. #188 needs #224's UI host.
  #213 then #214.

## Wave 9 plan

- Ready: #211 udea-render on Kool (needs #222 - landed), scoped JVM + Android; its Wasm AC moves to #223 (commented on #211). #223 spike (build pinned Kool main with toolchain 21, check wasmJs + WebGL2 draw; publish nothing). #211 is Udea repo (udea-render); #223 spike is outside Udea (Kool clone in scratch dir) - disjoint, can pair if box has memory (session hit low-memory kill once this wave).
- Blocked: #212 needs #211; #221 needs #211; #192 needs #212; #194-#196 need #210-#212; #213 then #214.

## Wave 8 plan

- Ready: #222 (composegl-kool Wasm + Android, composegl repo; composegl-ef session active there - message it before dispatch). Possible iOS follow-up for udea-net/udea-agent native actuals: not filed (not a spec ticket yet; file only if #214 needs it).
- Blocked: #211 needs #222. #212 needs #211; #221 needs #211; #192 needs #212; #194-#196 need #210-#212; #213 then #214.
- So wave 8 is #222 alone. Udea repo has nothing free until #211.

## Wave 7 plan

- Ready: #215 (Fleks iOS; build-logic determinism pin + udea-core + ci.yml ios-tests). #210 composegl-kool lives in wildware-uk/composegl (check composegl-ef session). #211 needs #210; #212 needs #211; #221 needs #211; #192 needs #212; #194-#196 need #210-#212; #213 then #214 last.
- So wave 7 is #215 alone in this repo, plus #210 if the ComposeGL repo is free.
- #210: 2026-09-17 lead messaged peer session `composegl-ef` (busy in /srv/ssd1/workspace/composegl) asking whether composegl-kool is in progress and what to avoid; reply goes to this machine's Udea lead session. No `composegl-kool` module or branch exists in that repo yet. If no answer, dispatch #210 into a composegl worktree off origin/master touching only a new `composegl-kool` module + settings, and name the collision risk on #210.
- Wave 6 cleanup: no Udea game instances live (7840-7859 free); stale registry entries are melon-merge's, left alone.

## Wave 5 (2026-09-17): done

- Dispatched: #207 audio (dev-207, SPI+Silent+drain only), #208 agent (dev-208), #216 build-logic inputs (dev-216), #218 CI budget base (dev-218, may push its branch for CI), #220 UDP reader silent stop (dev-220). Disjoint modules; #207/#208 may both touch docs/module-graph.md and AGENTS.md.
- #207 split: Kool AudioDevice + desktop/browser playback moved to #221 (lives in udea-render, needs #211). Commented on #207; #221 added to epic #199.
- #208 ruling: targets jvm/android/wasmJs (no iOS until #215); assets-compiler code stays JVM. Commented.
- Hand-offs: module-graph.md udea-assets row -> dev-207; ReplayFixtures.kt:106 `:udea-net:test` -> dev-208.
- #216: merged `6d95f67`, round 1 PASS. Paths were temp-dir fixture names, not repo reads: exempted in OuterBuildInputsTest (commented). build-logic check now green, fully. Worktree kept: `.claude/worktrees/agent-ae7538127c3c34047`.
- #207: merged `e9639e0`, round 1 PASS (no findings). udea-audio no-ios KMP; mixer seed reads kotlin.time.Clock (presentation, reviewer-ruled OK). Worktree kept: `.claude/worktrees/agent-ad8fa4bba8720b520`.
- #220: merged `89e6113`, round 1 PASS (no findings). Reader stop queues a marker; poll counts receiveErrors, disconnects with local-only DisconnectReason.ReceiveFailed(8), sets failure. Worktree kept: `.claude/worktrees/agent-af52e01da27821b3f`.
- #208: merged `47ec3b9`, round 1 PASS (no findings). udea-agent no-ios KMP; KSP twice (common + jvmMain scoped by new `udea.sourceSet` option); Wasm refuses diag.memory and enum writes with typed errors; /tools byte-identical to master. Worktree kept: `.claude/worktrees/agent-a9f0019cbc1541a12`. #193 (editor tools) now unblocked.
- #218: merged `25cc650`, round 1 PASS (no findings). Base picked by .github/scripts/clean-build-base.sh ("kmp master"). Branch CI ratio 1.015. CHECK: first kmp push CI run's budget job base should be HEAD^1. Windows UDEA0021 asset-validation flake seen once (rerun green). Worktree kept: `.claude/worktrees/agent-a693b967af25ee619`; remote branch issue-218-ci-budget-base pushed.
- Trap: this session's memory guard kills `run_in_background` builds even with 25G free; run trial builds via `setsid nohup ... &` and a Monitor on the log. Stop Gradle daemons after (`sh gradlew --stop`).
- For #214 cleanup (dropped as cards, noted on #214): docs/contracts/agent-tools.md says udea-agent `src/main` (stale path, frozen - lock route); ci.yml replay-equality-nightly `if:` still names refs/heads/example (schedule on default branch still runs).
- Reviewers cannot SendMessage `main`; they reach the lead as `team-lead`.
- Trap: the lead's scratchpad is shared with developers; name logs per issue.
- Held to wave 6: #217 (build-logic, collides with #216), #219 (udea-net, collides with #220), #193 (needs #208).

## Wave 4 (2026-09-16/17): done

- Dispatched: #204 gas (dev-204), #205 assets (dev-205), #206 replay (dev-206), #209 net (dev-209). Disjoint modules.
- #209 net: merged `dc6c708`, round 1 PASS (no findings). Conflicted with #206 (same three docs); dev-209 merged origin/kmp (75e3100). Ktor 3.6.0, coroutines 1.11.0, cryptography-kotlin 0.6.0. runUdpProof lossy fails more often (6/32 vs 1/26): reviewer ruled it exposes #219, not a branch defect. Filed #220 (UdpTransport reader stops silently). Worktree kept: `.claude/worktrees/agent-a4a820538f153b24b`.
- Wave 5 note: stale `:udea-net:test` in udea-replay ReplayFixtures.kt:106 (after #209). docs/module-graph.md row for udea-assets still says `udea.kotlin-library` (stale after #205); hand the fix to the next ticket editing that file (#207 or #208).
- Wave 5 candidates: #216 (build-logic inputs, found wave 4), #218 (CI budget job base is retired example), #217 (determinism scanner misses TimeSource, found by dev-204).
- #204 gas: merged `4ca994d` (+ BRIEF-204.md `c58cb75`), round 1 PASS. Trial + merged build green; build-logic check only #216. udea-core `KClass.runtimeName` now public; `roundHalfUp` replaces Math.round (roundToInt differs on Wasm). Worktree kept: `.claude/worktrees/agent-a8b2f894959c4489d`.
- #206 replay: merged `236ad47`, round 1 PASS (no findings). First trial conflicted with #204 (ci.yml/AGENTS.md/module-graph.md, one sentence each); dev-206b merged origin/kmp into the branch (docs-only resolutions), second trial clean + green. KSP on kspJvm only until #208; pure-Kotlin CRC-32. Worktree kept: `.claude/worktrees/agent-a32c9ac76b37a1e7a`.
- Trap: `git stash push <path>` on an unmodified file stashes nothing, and the next `stash pop` applies an unrelated old stash (there is a stale `issue-172` stash in the main repo). Check `git status` before stashing.
- #205 assets: merged `abba97b` (+ BRIEF-205.md `0ee301b`), round 1 PASS. Full KMP incl. iOS (CI: 75 iOS tests). Windows latency red was noise (rerun 205ms). Shared build-logic: K2 plugin classpath transitive on Native; MG-006 allows kotlinx-io. Worktree kept: `.claude/worktrees/agent-a3cb3603dfb72ee9f`. #207 and #208 now unblocked (wave 5).
- Owner ruling 2026-09-17: Kool/KMP work only; no LibGDX render tasks as evidence.
- Ruling: developers leave BRIEF.md uncommitted in worktree root; lead commits it as BRIEF-<N>.md after the merge.
- #205 touched shared build-logic: K2 plugin classpath transitive on Kotlin/Native compilations (iOS crash fix); UDEA-MG-006 allows kotlinx-io.
- Held to wave 5: #208 (udea-agent `implementation`-depends on udea-assets, needs #205; commented), #207 (needs #205).
- #206 ruling: udea-agent stays JVM until #208, so replay keeps agent-tool code in a JVM source set (commented).
- #205 (assets) does not depend on udea-core, so it takes full `udea.kotlin-multiplatform` including iOS.

## Wave 3 (2026-09-16): done

- #203 udea-core to KMP: merged `a634450`, round 1 PASS. jvm + android + wasmJs; **iOS OFF** because Fleks publishes
  no iOS artifact (any version). Named convention `udea.kotlin-multiplatform-no-ios`; follow-up #215. Every module
  depending on udea-core inherits no-iOS until #215. udea-core not in CI `ios-tests`.
  `udeaVerifyDeterminism` now scans KMP jvm+android bytecode. Pin test `SimHarnessWorldHashPinTest` in udea-agent.
  Worktree kept: `.claude/worktrees/agent-aba51a45c03b91d81`.

## Wave 2 (2026-09-16): done

- #202 generated registry: merged `f45bbeb`, round 1 PASS. Per-module `<Module>ModuleRegistry` + `<Module>UdeaRegistry`;
  `UdeaGameDef` requires the registry; modules declare themselves with `udeaModule("Name")` in build scripts.
  ServiceLoader left only in build-time code. Worktree kept: `.claude/worktrees/agent-af8703c835b0fca84`.
- #207 held: udea-audio `api`-depends on udea-core and udea-assets (still JVM-only), so four-target build needs
  #203 and #205 first. Decision commented on #207.

## Wave 1 (2026-09-16): done

- #200 Kool Offscreen spike: merged `d97517b`, round 1 PASS. Yes: Kool 0.19.0, GL on llvmpipe under xvfb.
  Needs an X11 GLFW-init workaround and a per-backend row flip; noted on #211. Code in `spikes/kool-offscreen/`.
- #201 KMP convention plugin: merged `90b26fc`, round 1 PASS. Plugins `udea.kotlin-multiplatform`,
  `udea.kotlin-multiplatform-render`, `udea.kotlin-base`, `udea.jvm-test-fixtures`.
- Cards filed: none. Notes carried as comments on #211 (Kool workarounds) and #203 (`udeaVerifyDeterminism`
  layout; add module to CI `ios-tests`).

## Wave 6 plan

- Ready: #217 (determinism TimeSource; build-logic), #219 (UDP straddle desync; udea-net), #193 (editor.* tools; needs #208 - merged), #215 (Fleks iOS), #210 (composegl-kool, other repo; check composegl-ef session first). #211 needs #210. #221 needs #211.
- #217 vs #193: check module overlap before pairing.

## Wave 5 plan (done)

- Ready now: #207 audio (needs #205 - merged), #208 agent (needs #205 - merged), #216 build-logic inputs, #217 determinism TimeSource, #218 CI budget base, #219 UDP straddle desync, #220 UDP reader silent stop, #210 composegl-kool (other repo; check composegl-ef session first).
- Collisions: #216 and #217 both edit build-logic (determinism tests) - not together. #219 and #220 both udea-net - not together. #193 editor tools needs #208.
- Editor epic #190 reopened by owner 2026-09-17 (#192-#196), part of the port; viewport is ComposeGL 0.7.0-SNAPSHOT `SceneView`. Order in #199.
- Open offer to owner (2026-09-17, unanswered at reset): add `BASH_MAX_TIMEOUT_MS=120000` to `.claude/settings.json` env, and/or a PreToolUse hook rejecting foreground `gradlew`. Do it only if the owner says yes.
- Lead rule: every gradle build runs `run_in_background`; never foreground (froze the session once). Verify a trial merge applied before trusting it.

## Standing rulings and traps

- **OWNER RULE (dashboard, 2026-09-18): "STOP CREATING ISSUES, if things need fixing, just do it, stop
  creating new issues."** Overrides the dev-team skill wherever it says `gh issue create`. A defect found in a
  ticket's own module is fixed ON THAT BRANCH. A defect elsewhere is folded into the next existing ticket in
  that module, or dispatched directly - no new issue. A round-3 split keeps its remainder on the SAME issue as
  a comment. Commenting on existing issues is still required for decisions. Triggered by wave 10 filing five
  issues (#226-#230) while closing one. Saved to memory as `owner-no-new-issues`.
- **The scratchpad is SESSION-WIDE** - every developer and reviewer resolves it to the same path. Each agent
  writes only under `scratchpad/issue<N>/` and never cites a file it did not write. Put this in EVERY
  dispatch. (Wave 10: dev-212 found dev-229's generic `build.log`/`final/`/`ev/`/`mutations/` in "its"
  scratchpad; nothing clobbered, all three told.)

- New runtime module must call `udeaModule("Name")` in its build script, or codegen errors (#202 ruling).
- Modules depending on udea-core cannot have iOS until #215 (Fleks); use `udea.kotlin-multiplatform-no-ios` (#203 ruling).
- #207 (audio) needs #203 and #205 too, not only #201: it `api`-depends on core and uses assets.

- **Every build command needs `ANDROID_HOME=$HOME/Android/Sdk`** since #201 (put it in developer, reviewer
  and trial-merge commands). Without it: `SDK location not found`. Environment, not a red build.
- CI's `ios-tests` job lists converted modules by hand: each port ticket adds its module there.
- A standalone spike build not included in root settings may use Kool/GLFW outside `udea-render` (#200 ruling).
- `build-logic` is not a `udea-*` module; reject item 2 (unused public) does not apply there (#201 ruling).
- Developer agents sometimes stop while a Gradle build is still running and say "I'll pick up when
  notified"; they are not re-notified. Check the worktree and nudge with SendMessage.

## What happened

- Wave 9 (#188, #192, #193) was **stopped by the owner** mid-flight. Nothing merged. Worktrees left:
  `.claude/worktrees/agent-a3e7f21c806774c0d` (#188, 3 commits, HUD on ComposeGL — composables still
  useful), `agent-a6c0efd2131540f28` (#192), `agent-a2c3e8e4459ef1aea` (#193). #192 and #193 are closed.
- Owner decided the restart: Kool rendering, KMP runtime. Spec
  `docs/superpowers/specs/2026-09-16-kool-kmp-port-design.md`. Epic #199, tickets #200–#214.
- `master` fast-forwarded to `example` (`409c044`). `example` branch retired. `kmp` cut from `master`.
- 51 issues closed as superseded. Open: #185, #188, #189 (ComposeGL) and the port tickets.
- dev-team skill and the engineer/reviewer/team-lead agents now branch from `origin/kmp`, merge into
  `kmp`, and gate on "named tasks green, no baseline-green task red".

## Next

1. Wave 4: #204, #205, #206, #209 in parallel (all need #203); #208 needs #202 and #203.
3. #210 lives in `wildware-uk/composegl`; a separate `composegl-ef` session is active there. Check its state
   before dispatching.
