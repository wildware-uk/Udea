# Wave handoff — 2026-09-16, restart onto Kool + Kotlin Multiplatform

## kmp baseline

**SHA `d22fcac`** (kmp after #192, merged tree == reviewed tree `3442bf5` + WAVE.md; reviewer build --no-configuration-cache 917 tasks green). Before: kmp after #228, merged tree == reviewed tree `c102932` + WAVE.md; reviewer build 917 tasks green). Before that `72b949d` (kmp after #213; merged tree == trial tree `/tmp/trial-213`, which merged #213 onto `fdb82b9`
= kmp with the textured-model example). Trial, 2026-09-19:
`ANDROID_HOME=$HOME/Android/Sdk JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem sh gradlew build --continue`

**BUILD SUCCESSFUL - 915 tasks, failing tasks: NONE** (952 -> 915: the old tree's tasks are gone). GL under xvfb
requireGl green; `-p build-logic check` green. **Any red task on a branch is the branch's** (latency budgets: re-run
alone first). Earlier fully green: `6a7a9b1` (after #212, 952 tasks).

Previous baseline `08ca441` (after #221): one red `:moba:compileKotlin`.
Earlier: `30731e4` (after #227), `26333d5` (after #230), `7ac6559` (after #229), `18bb13f` (after #224), `87d8b7c` (after #211), same single red. Before #211 the baseline was fully green: `52e92aa` (after #225), `0befdec` (kmp after #215 merge; trial tree identical, root build + build-logic check green); earlier `73a09e5` (kmp after #219 merge; trial tree identical, root build + build-logic check green); earlier `fcdeb63` (kmp after #193 merge; trial tree identical, root build + build-logic check green); earlier `303abe7` (kmp after #217 merge; trial tree identical to merged tree, root build + build-logic check green); earlier `25cc650` (kmp after #218 merge; trial root build + build-logic check green); earlier `47ec3b9` (kmp after #208 merge; trial root build + build-logic check green); earlier `89e6113` (kmp after #220 merge; trial root build + build-logic check green); earlier `e9639e0` (kmp after #207 merge; trial root build + build-logic check green; `6d95f67` #216, trial tree identical, root build + `-p build-logic check` both green; `dc6c708` #209; `236ad47` #206; before: `abba97b` #205, `4ca994d` #204, `a634450` #203), refreshed 2026-09-16; first taken at `6097ae7` on a detached checkout with
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

## Wave 10 (2026-09-18): done

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
- **SETTLED: W is 87.** dev-224 measured it and retracted its own number without softening.
  **CORRECTED LATER (dev-230, lead-verified):** the explanation of the 119 below was itself wrong.
  `UniversalKeyCode(Char)` is `Character.toUpperCase` in **Kool 0.19.0** (our dependency, `javap -c`) and
  `lowercaseChar()` only in **Kool `main`** (`KeyCode.kt:16` in the #225 spike clone, which is what dev-224
  read). **The helper's case FLIPS between Kool versions.** So KeyTable was CORRECT on 0.19.0 and #230's
  premise was false today - but it is TRUE after the Kool upgrade that would unshelve web. Never key a table
  through `UniversalKeyCode(Char)`; key on raw ints. The "W is 87" measurement itself was never in doubt.
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

- **#230 REFRAMED, not abandoned.** dev-230 measured three ways (javap on desktop jar + Android aar;
  all-26-letters real-GLFW test PASSES on merged code; lowercase mutation reds exactly A-Z): the constructor
  UPPERCASES on 0.19.0, so KeyTable was right. The lead found why everyone believed otherwise: Kool `main`
  lowercases. Now lands: (1) key the table on RAW INTS, never via the Char helper, so the upgrade cannot
  break it; line-86 punctuation same check; (2) the 26-letter real-GLFW test as the UPGRADE GUARD, failure
  message naming the constructor and the version flip; (3) corrected KDocs; (4) **the real player-visible
  defect, different cause:** ComposeGL `TextField` does not take a bare letter key-down
  (`KeyboardEditor.onKey` returns null for plain letters; typing arrives as TEXT), so typing "w" into it
  still sends W to the game - #230's AC1. dev-230 wanted to file it as a new issue; told NO (owner rule, and
  it is AC1 in its own module). Fix: judge the key event and its text event together; an unfocused letter
  must still reach the game; a focused BUTTON must not swallow W. Corrections posted on #230 and #228.
- **Lesson for the lead:** I "confirmed" #230 by reading line 29 and trusting the relayed claim about what
  the constructor does, not by reading the constructor in the version we ship. That is confirming a claim
  from the sentence that made it. Check the dependency version before trusting a source read.

- **#229 done, under review** (`review-229-r1`, SHA `c7c9d7a`; change `a3dfb1a`). Push arm now names
  `refs/heads/kmp`; comment block explains GitHub runs `schedule` only on the default branch, and that #214
  needs no edit (the arm stops matching, the cron covers the ported master). **One forced change outside
  `.github/`:** `ReplayEqualityProofTest` (udea-replay) asserted the `if:` CONTAINS `refs/heads/example` - a
  test pinning the defect - so the ci.yml fix alone turned `:udea-replay:jvmTest` red; it now checks against
  the clean-build job's branch list (`kmp master`) both ways. CI proof: before - scheduled `35323890628`
  green on master (old engine), push `35397674705` on kmp SKIPPED the nightly; probe `35400527981` started
  all 3 legs; controls `35400489693`/`35401417595` skipped; dispatch `35401442677` ran all 3 legs on
  `a3dfb1a`, each stopping at `:moba:compileKotlin` (D9 red) - failure is the true answer about kmp today.
  **Lead asked the reviewer to rule on over-firing:** every push to kmp (incl. WAVE.md bookkeeping) now runs
  three 36000-tick legs.
- **Owner decision pending, not an issue:** the `example` branch is still on origin at `409c0442` (same
  commit as master); nothing in `.github/` names it now. dev-229 thinks it can be deleted. Remote branch
  deletion is irreversible and outward-facing - the owner's call, raise in the wave report.
- dev-230 build finished 22:36:31 (only baseline red); it had stopped mid-build and was nudged.

- **#229 round 1 PASS** (`review-229-r1`, SHA `c7c9d7a`), no findings. Rulings: the udea-replay test edit
  WAS forced (the old test pinned `refs/heads/example`); a dispatch that honestly reports kmp red meets AC1;
  the dispatch arm fired before the fix too, so the push-arm proof is the probe `35400527981` plus the two
  controls, and that suffices; at #214 nothing needs editing (control C2 green). ReplayEqualityProofTest:
  21 tests, 0 failures. Out of scope: every kmp push now runs 3 long legs - handled by batching pushes.

- **#229 MERGED `7ac6559`** (+ `d04b5c7` renaming BRIEF.md -> BRIEF-229.md per standing ruling), round 1
  PASS. Trial: only baseline `:moba:compileKotlin`; `:udea-replay:jvmTest` green. Baseline unchanged.
  Worktree kept: `.claude/worktrees/agent-a9cc4e1091da97349`. THIS push to kmp is the first real run of the
  new push arm - check it started three replay-equality-nightly legs.

- **#230 MERGED `26333d5`**, round 1 PASS, no findings. Table keyed on GLFW codes (never via the case-flipping
  `UniversalKeyCode(Char)`); all-26-letter real-GLFW upgrade guard; KDocs fixed; the REAL defect fixed:
  `KoolKeyboard` holds a UI-declined key for one event, and if the UI then takes that key's character it
  was typing and never becomes an intent. **Hold-back ruled sound by the reviewer:** a declined key-down is
  released at the next event or at the end of the same `onKeyEvents` list (`typing?.let(::record)`), so
  nothing waits across frames, non-printables and Ctrl chords cannot stick; `KoolKeyboard` carries no tick
  stamp (it counts presses), so tick attribution is unchanged; A is recorded before B, no reordering. Each
  case unit-tested; mutations m2-m4 red. Trial: only baseline red; GL under xvfb green. Tree identical.
  Worktree kept: `.claude/worktrees/agent-a5ecd4c2831637278`. `udea-render` now free -> #227 next.

- **#227 dispatched** (dev-227, branch `issue-227-kool-pointer-intents`, off `1cc5f60`), `udea-render` (+
  `udea-core` if the binding model lives there; nobody else in it). Told: AC3 re-worded (NO fake verdict
  exists - pointers get a SECOND seam beside `UiInput`); pointers are ABSENT today (no ActionBinding mouse
  field, no PointerState) so AC1 builds pointer->intent from nothing; the full `PointerUse` API from
  composegl `007ea1cf`, esp. MATCH ON `frame` (listener-read), it is a STREAM (once per pointer per frame,
  used or not, final report on lift/leave), `isConsumed()` stays false; `PointerUse.frame` must never leak
  into the sim as an input stamp (Tick only); extend `GlKoolInputTest` (one Kool context per JVM); drive
  Kool's REAL pointer path, assert the negative; do not disturb #230's `KoolKeyboard` hold-back; no moba;
  no new issues; scratchpad `issue227/`; will not be re-notified if it stops mid-build.
- **In flight: 2** (dev-212 moba, dev-227 udea-render). Nothing else free: #221/#228 are udea-render
  (dev-227's), everything else waits on #212.

- **#227 MERGED `30731e4`**, round 1 PASS, no findings. Clicks were ABSENT (no intent at all); now
  `ActionBinding.pointerButtons` -> `DeviceIntent` via new `PointerState`. `KoolPointer` holds each frame's
  button changes until `onPointerUsed` for that pointer+frame (matched on listener-read frame); a press the UI
  used is dropped, a release always applies, a leaving pointer releases all it held. Seam: internal
  `UiPointers` beside `UiInput`. Edges from button LEVELS - Kool 0.19 reports a stale `buttonEventMask` bit on
  mouse re-entry, and dev-227's first version phantom-pressed from it (GL step 5 guards). **Reviewer rulings:**
  no layer / detached layer applies at once; attached-with-no-screen still reports, and GL step 6 asserts one
  press so a held-forever press fails it; a later report settles all earlier frames for that pointer as
  unused (tested); release-without-press is a no-op in `apply()` (tested); `InputFrame` internal, never
  reaches `Intent`; floating snapshot out of scope. #224/#230 key tests byte-identical. **Decision to flag to
  owner:** clicks are button actions only, NO screen position in `Intent` - click-to-move needs a separate
  `Intent` change (moba moves by WASD today, nothing breaks). **Probable ComposeGL bug** (stale mask on
  re-entry -> `KoolPointerInput.onFrame` may click a button on mouse re-entry) sent to composegl-ef directly,
  not filed. **Stale-snapshot trap:** a box that fetched `composegl-kool` 0.7.0-SNAPSHOT before `007ea1cf`
  fails `:udea-render:compileAndroidMain` with `Unresolved reference onPointerUsed` - run
  `--refresh-dependencies` once (done on this box 23:07). Trial: only baseline red; GL green.
  Worktree kept: `.claude/worktrees/agent-ab22c07454e2b1eaa`. `udea-render` free -> #221 next.

- **ComposeGL re-entry phantom click: CONFIRMED and FIXED upstream** in composegl-kool `0.7.0-SNAPSHOT` from
  **`9cf8a04c`**. Their real-GLFW demo test counted 2 clicks where 1 was right before the fix. Fix: a
  newly-seen pointer is read by current button levels only (mask ignored); a known pointer still uses the
  mask, so a one-frame click still registers. No Udea change needed (#227 reads levels itself). Routed as a
  direct report, not an issue, per owner rule - it worked.
- **#221 dispatched** (dev-221, branch `issue-221-kool-audio-device`, udea-render). Browser half shelved;
  ACs = desktop transcript + udea-audio free of Kool. Box has ALSA cards (HDA NVidia, HD-Audio Generic, snd_hda
  loaded) but NO aplay / PulseAudio / PipeWire and nobody to listen - evidence proves the PATH (device opened,
  clip loaded, playback started, from Kool's own state) and must go red with `Silent` swapped in; never claim
  audible. Told to read the Kool 0.19.0 JAR, not the `main` spike clone. A device that cannot open must not
  be a swallowed exception.
- **In flight: 2** (dev-212 moba, dev-221 udea-render).

- **#221 MERGED `08ca441`**, round 1 PASS, no findings. `KoolAudioDevice` (udea-render commonMain,
  internal) over Kool `AudioClip`; public entry `jvmMain koolAudioDevice(assetRoot: Path): AudioDevice`.
  **Kool 0.19.0's own .ogg loader crashes the JVM** (SIGSEGV in libjemalloc: frees stb_vorbis's buffer via
  LWJGL `memFree`); reproduced standalone on LWJGL 3.3.6 and 3.4.3, not with the system allocator. Fix:
  `OggToWav` decodes .ogg itself and hands Kool a WAV; regression test crashes the JVM 3/3 with the fix
  reverted. Pitch/pan NOT honoured - Kool 0.19.0 `AudioClip` is volume-only (reviewer javap-verified; KDocs
  say so; a test pins they do not change gain). Box has ZERO Java Sound mixers (build user not in `audio`
  group), so a jvmTest-only `MixerProvider` sits BELOW Kool; assertions read Kool's own `isEnded`/
  `currentTime`. No output -> `AudioLoadException`, never swallowed, no fallback inside the device.
  **LEDGER CONDITION (reviewer):** `koolAudioDevice` stays public ONLY because moba is its caller - moba must
  call it or it goes internal. **It is #212's own AC:** moba's sound is `GdxAudioDevice` (LibGDX), picked at
  `MobaAudio.kt:150`; #212 must remove LibGDX, so it replaces that line with `koolAudioDevice`, catching
  `AudioLoadException` -> `AudioDevice.Silent`. Relayed to dev-212. Not reported upstream to Kool (outward
  third-party filing is the owner's call). Trial: only baseline red; module-graph/no-legacy/AgentsMd green;
  GL green. Worktree kept: `.claude/worktrees/agent-a86393fdc904896ad`.

- **#212 MERGED `6a7a9b1`**, round 1 PASS, no findings. **kmp is FULLY GREEN.** moba split into `:moba:game`
  (jvm+android), `:moba:desktop`, `:moba:android` (debug APK), no LibGDX (UDEA-MG-009 goes red if restored).
  Audio: `MobaDesktopAudio.forHost` - Headless->Silent, else `koolAudioDevice`; `AudioLoadException` -> close,
  one log line, Silent. #221 ledger condition MET. Net-protocol lock blob unchanged (no ids moved). 57 files
  outside moba, reviewer checked all: forced renames (`moba/assets`->`moba/game/assets`,
  `:moba:udeaReplayDigest`->`:moba:desktop:udeaReplayDigest`, `moba/*/src` globs). Rulings: withdrawing the
  2.5% MOVING threshold is honest (branch fails it against itself; `CameraRig` smooths on wall-clock frame
  seconds, render side - expected, not a determinism smell). `lane/clash` crescent = soldier attack sprite
  frame, timing not a missing draw (reviewer caught it at tick 758); shot tick moves with frame pacing (shot =
  2 ticks after first corpse seen on a frame), no tick-numbering off-by-one. Out of scope (no cards, owner
  rule): one collage left-panel label wrong; frame-paced shot harness could be tick-exact. Worktree kept:
  `.claude/worktrees/agent-a3cbf6ce8ad2f0116`. Report: `scratchpad/review212/review-212-r1.md`.
- **In flight: 0.** Wave 10 complete.

## Wave 21 (2026-09-20): the wiki, done
- MERGED and PUSHED (master 4c8e8a6): `wiki-core` (13 engine pages, Home, _Sidebar, the udeaVerifyWiki
  gate), `wiki-render` (Getting-Started, Rendering-with-Kool, Models-and-Animation, UI-with-ComposeGL,
  Input, Cameras) and `wiki-tools2` (Audio, The-Editor, Gizmos, Agent-Tool-Surface, Tutorial-Make-a-Game,
  Example-Games). 25 pages, 5 images. `udeaVerifyWiki` green on the merged tree.
- PUBLISHED to the GitHub wiki, commit 6358fba. The sidebar's `Building-Hollow-in-the-Editor` link is
  removed until #255 writes that page.
- Merge conflicts resolved by hand: `docs/home.md` (kept the wiki pointer, kept new-game.md and
  engineering-standards links), `docs/getting_started.md` (master deleted it in #265; deletion wins),
  `.pending`/Home/_Sidebar add/add. `.pending` deleted once every page it listed existed.
- dev-wiki-tools KILLED after 19h: it had drifted into building a tutorial *game with tests* instead of
  writing pages. Two writers replaced it and did 12 pages in about 90 minutes. Lesson for a docs
  dispatch: say "you write documentation, you do not write code or tests" in the prompt.
- The writers caught three classes of error worth remembering: a documented command that silently does
  nothing (`-Dudea.render.mode=Windowed` never reaches `:moba:desktop:run`), a confident false caption
  (lane ribbon called tower range), and seven elided `.../` paths nobody can open - the gate caught one,
  a writer swept the class.
- AGENTS.md fixed: the generated node member is `Chassis.Nodes.socket_roof`, not `socketRoof`.

## Wave 20 (2026-09-20): in flight
- MERGED: #265 (publish the engine + outside-repo games, PASS at 6d48acd), the #246 reopen (level save,
  PASS at 9b353ad), #250 (the Hollow player, PASS at 9e77add). master at 98bc86e.
- #265 merge conflicted with #264 in build-logic: #265 replaced `:moba:*` path lists with role scopes
  (`ProjectScope.GAME`, `udeaGates { ships/simulation }`), #264 had added entries to those lists. Took
  the new structure and re-added by hand: udea-nav's SimScope, udea-nav in HEADLESS_PROJECTS,
  `ships(":hollow:desktop")`. Then #265's own gates caught two more: udea-nav missing from `published`,
  and udea-nav having no POM description. Both fixed; every engine module needs both from now on.
- #250 shifted every component's wire id by one (`dev.wildware.hollow.Player` sorts first): four
  net-protocol.lock files, net-components.lock, expected-generated-hashes.txt, moba's two .udearep
  fixtures and moba's roster worldHash all regenerated. Reviewer verified from the generating tasks.
- #250 merged with NO trial build, deliberately: the branch had merged master at 9c95e0e, master had
  not moved, and the merged tree was byte-identical to the reviewed one.
- IN FLIGHT: dev-257 (isometric camera), dev-wiki-tools. review queue empty.
- Owner asked for video. Recipe in memory (`recording-udea-video.md`): Xvfb + ffmpeg x11grab, and
  `:moba:desktop:run` is Offscreen so it records black - use `runClient --args=local`. Sent moba,
  Hollow clearing, and the player running (issue250-player-clearing.mp4).
- BOX SHARING: melon-merge-31 runs timing-sensitive suites here. Standing arrangement - it asks, I hold
  my developers' builds for 30 minutes, it pings when done. Owner requests override the hold; tell it after.
- Carried forward: two-client screenshots on #254; `CharacterSystem.sync()` mutating the world from the
  render thread, with the ModelRenderer save question, on #255.

## Wave 19 (2026-09-20): in flight
- MERGED into master at 8ba4c5d: #264 (udea-nav pathfinding), #248 (third-person camera), #260 (model sockets),
  #249 (Hollow H1 scaffold). All four PASS at round 1/2, 0 findings. Trial build green: 984+ tasks, GL suites green.
  #260 and #264 both touched `net-components.lock`; resolved by keeping both comment paragraphs, the name list
  merged clean and stayed in strict ASCII order (Animator 27, AttachedTo 28, Transform3D 29, NavAgent 30, NavObstacle 31).
- Shader API spec written on the owner's request: docs/superpowers/specs/2026-09-20-shader-api-design.md.
  Five tickets sketched (S1 materials, S2 material assets, S3 screen passes closing #259, S4 editor inspector,
  S5 compute). NOT filed - waiting on the owner's go.
- New friction issues filed by the robot-game session: #266 (no shader API - answered on the issue, KSL not GLSL
  strings), #267 (no sky or clear colour), #268 (screenshot needs backend.pipeline?.capture), #269 (the new-game
  template cannot draw).
- REVIEWER TRAP, cost 13 hours: an `Agent` spawn with a name and NO `isolation` registers as a pane teammate in
  this session and never runs - no worktree, no build, no reply, and the spawn reports success. Always spawn
  reviewers with `isolation: "worktree"` and have them `git checkout --detach <SHA>` inside it.

## Wave 18 (2026-09-19): in flight - robot-game engine features (epic #256, R1-R9 = #257-#265, filed by owner)
- dispatched: dev-260 (sockets; udea-core, assets compiler, udea-render), dev-264 (pathfinding, new module udea-nav),
  dev-265 (outside-repo games by composite build; build-logic, udea-gradle gates). Decisions commented on #264, #265.
- held: #257/#258/#259/#262/#263 touch udea-render camera and presentation and wait for #248 (camera rig) to merge;
  #261 needs #260; #263 needs #262. Order after that: #257, #258, then #259 and #262, then #263.
- published wiki-core pages to the GitHub wiki (881e3da) before its review, on the owner's word.

## Wave 17 (2026-09-19): in flight - Hollow (owner: "Create a feature rich 3D game to test out the engine")

- Spec docs/superpowers/specs/2026-09-19-hollow-3d-game-design.md; epic #245; tickets E1 #246, E2 #247, E3 #248, H1 #249, H2 #250 .. H7 #255 (created at the owner's request).
- In flight: dev-246 (Transform3D @Net + 3D interp: udea-core, udea-render/interp), dev-247 (physics drives Transform3D: udea-physics2d), dev-248 (third-person camera: udea-render/camera), dev-249 (hollow scaffold + settings/AGENTS.md/module graph).
- #247: merged, round 1 PASS (no findings). Only docs/WAVE differ between master and the reviewed base. Internal systems in udea-physics2d: new bodies start at Transform3D, kinematic via b2Body_SetTargetTransform, static follows Transform3D, dynamic writes Transform3D x/y/rotationZ after the step. Cards for H7: editor drag of a dynamic body is overwritten next step; Teleport on a kinematic/static 3D body is undone.
- #246: merged, round 1 PASS (no findings). Trial on master after #247: 984 tasks green, GL green. All nine Transform3D fields @Net (scale @Sim gave clients scale 0); Transform3D.snapshotType(); udea-render/interp Interp3D records poses at end of tick. Card: net-protocol.lock cannot see a net/sim change (handshake hash does); 3D teleport smears one tick.
- Wiki (owner: "Create a wiki for udea, explaining everything about the engine in detail"): plan scratchpad/lead/wiki-plan.md; docs/wiki/ in repo, mirrored to the GitHub wiki after merge. dev-wiki-core (branch wiki-core: architecture pages, Home/_Sidebar, udeaVerifyWiki gate, stale docs/*.md to pointers), dev-wiki-tools (branch wiki-tools: tools/how-to pages).
- Common contract: scratchpad/lead/common-w17.md. H2 needs H1+E2+E3; H3..H5 serial in :hollow:game; H6 needs H5+E1; H7 needs H1 (can run beside H3-H5).

## Wave 16 (2026-09-19): done

- #235: merged, round 1 PASS (no findings). Merged tree == reviewed tree. Left button selects (gizmo first), right button orbits/pans; public PickBounds in udea-render (sprites, models, moba units by body); EditorInspector with Mixed, no Set button (owner): G1 edit session per field, commit on Enter/focus loss, cancel on refused value. Cards: AnimationRenderSystem not pickable; G1 30s idle cancel reverts an un-entered value; inspector text boxes only; selection outline re-queries pick sources per redraw.
- #243: merged, round 1 PASS (no findings). Merged tree == reviewed tree. Animation panel follows selection; clip choice is one edit session (undoable); scrub preview is WorldViewport.modelPreview via ModelStage per-view drawFilter, capture filters preview nodes, world hash unchanged; bone overlay public-API only (added GizmoScope.mark, Mark, Gizmo.marks, GizmoCanvas.line); joint parents from glTF node tree. Cards: GizmoMarkLayer draw line untested alone; selection outline stays on hidden fox during model preview. dev-243 once killed an unread Xvfb pid (disclosed).
- #236: merged, round 1 PASS (no findings). Merged tree == reviewed tree; lead re-ran runUdpProof on it (2 tests green). Public GizmoScope moveHandles/sizeHandles/rotationHandle/radiusHandle/rangeHandle over internal HandlePainter; drags are G1 edit sessions; snapping and axes in gitignored <project>/.udea/editor-preferences.properties; moba TowerRangeGizmo (public API only). Tower gains @Sim attackRange (default TOWER_RANGE): moba net-protocol.lock protoHash 0xc67b -> 0xfbba, ids unchanged; replay fixtures regenerated. Folded #243's line/mark API. Cards: handle layer drawing its under-layer untested; held box corner not visibly lit.
- #238: merged, round 1 PASS (no findings). Merged tree == reviewed tree. editor.play_edits/keep/unkeep in EditorToolset; PlaySession records NetIds live at Play; Stop runs #196 restore then re-applies kept field edits (final value) as normal undoable edits; spawn/delete/spawned-entity edits not keepable. Changes during Play panel + inspector Keep pins. Past ~7 edits the list says "too long to list" until #237's whole-answer fix lands. Cards: Inspector pins vanish past ~7 edits (until #237).
- #237: merged via fold branch issue-237-238-fold (r1 PASS on #237 itself, fold r1 PASS; merged tree == reviewed tree). 3D arrows/planes/rings/scale boxes via EditorCamera.ray; Transform3D @Replicated all-@Sim (id 28, udea-core protoHash 0xa328 -> 0x35f2, netMask empty); bridge keeps whole answers beside capped copies, in-process EditorTools.frame reads wholeCommandResults (HTTP unchanged). Fold removed #238's unreachable "too long" fallback; new test lists 12 play edits past the cap.
- **Gizmo epic #231 complete (G1-G7).** Wave 16 done.

## Wave 15 (2026-09-19): in flight, WIP 5 (owner)

- Integration branch is now `master` (port done). Developers and reviewers are subagents only (owner).
- In flight: #234 follow-up (dev-234v: viewport between panels, owner dashboard ask; udea-editor layout, udea-render view), #233 G2 gizmo API (dev-233: annotations, codegen+locks, new udea-editor gizmo files, udea-gradle; UDEA0017, UDEA-MG-012), #242 A3 skinning (dev-242: udea-render model), #244 A5 FBX (dev-244: assets-compiler; UDEA0039, UDEA-MG-013), #189 scene2d ban (dev-189: docs + gate; MG-014 if needed).
- Merge-order risk: 233 and 244 both touch udea-gradle module-graph rules; 234v, 242 and 233 all touch udea-render or udea-editor, but different files.
- #189: merged, round 1 PASS (no findings). New `:udea-render:udeaVerifyNoLibGdx` (UDEA-MG-009-BYTECODE, on check) scans every project's main bytecode for com/badlogic/ and box2dLight/; moba:android gets `udeaMainBytecode`. Shared BytecodeBan with the headless scan. 960 tasks. Cards: headless table doesn't ban Kool (de/fabmax/kool/); docs/home.md stale overall ("built on LibGDX"); UiConfig.defaultSkin unused.
- #242: merged, round 1 PASS (no findings). Trial on master after #189: build --no-configuration-cache 960 tasks green, GL green. ClipPose is the only ticks-to-seconds place; applyPose writes Kool clip weights once per entity per frame; ClipPlayback holds/clipTime/isFinished now internal. Cards: Kool keyframe sampler boxes a Float per lookup; Kool culls skinned meshes by bind-pose bounds; no unskinned glTF in the tree to test.
- #233: merged, round 1 PASS (no findings). Trial on master after #242: build --no-configuration-cache 984 tasks green, GL green. Gizmo API in udea-editor `gizmo` package; handle annotations; UDEA0017 did-you-mean; `<Game>GizmoRegistry` (moba: MobaGizmoRegistry, PositionPositionGizmo); UDEA-MG-012 `udeaVerifyEditorAbsent` on check (not moba:android: udea-editor is JVM-only). Card: size/range handles have no hand-written twin test. **Baseline now 984 tasks.**
- #244: merged, round 1 PASS (no findings). Trial on master after #233: build --no-configuration-cache 984 tasks green, build-logic check green, GL green. FBX via lwjgl-assimp 3.3.6 at asset-build time, deterministic .glb (compacted buffer), UDEA0039, UDEA-MG-013 (Assimp banned from runtime/game classpaths; MG-002 + headless scan exempt only udea-assets-compiler and udea-gradle). Sample: Quaternius CC0 Animated Human. Cards: FBX converted 3x per build; converted glb not re-run through GltfCheck; data: URI textures refused; nearest filter lost.
- #242 reopen (skinned shadow): merged, round 1 PASS (no findings). Cause: Kool 0.19.0's shadow pass shares one cached depth shader (with bone matrices) across meshes lacking their own depth config, so every skinned shadow took the last-drawn skinned model's pose. Fix in ModelStage: DepthShader.Config.forMesh per skinned mesh (once per pooled node). Two-fox GL test red on master. Card: possibly report upstream to Kool; masked skinned material not exercised.
- #234 follow-up (owner: view between panels, no forced aspect): merged, round 1 PASS (no findings). Trial on master after #244 and the #242 shadow fix: 984 tasks green, GL green. ViewArea puts the Scene/Game page in the largest rect the docked panels leave; views take their tab size; in an editor render.screenshot takes the Game tab's size (D4); PassBlit re-attaches by Kool texture object (GL names are reused). **Box note:** background builds here got killed as "low memory" even with 27G free; the lead now runs trial builds detached (setsid) and waits in the foreground.

## Wave 14 (2026-09-19): done

- Dispatched: #214 docs + CI green (dev-214: AGENTS.md, standards, module-graph, skill + .claude/agents, HANDOFF, ci.yml, per-task tmpdir, budget tests), #241 Animator (dev-241, udea-core/codegen/compiler-plugin/assets-compiler), #234 Scene/Game tabs (dev-234, udea-editor/udea-render).
- #241: merged, round 1 PASS (no findings). Reviewer build --no-configuration-cache 958 tasks green. Animator id 27, udea-core protoHash 0x0826 -> 0xa328; UDEA0016 clip checker, UDEA0027 unreadable model; runNetProof now exits 1 on disagreement. Ledger: ClipPlayback public for #242 (use or make internal); tick counts as Long/Int OK. Card: MobaNetProof.kt KDoc "same number it always was" is stale. Possible AGENTS.md tick-model line: "Animation time is Animator.clipTime(now), derived from a start Tick, never accumulated."

- #234: merged, round 1 PASS (no findings). Reviewer build --no-configuration-cache 959 tasks green (new udeaEditorGlTest, on check and in the CI GL job); 21 GL suites green. WorldViewport per editor view, EditorCamera (2D pan/zoom, 3D orbit Z-up), GizmoLayer/GizmoCanvas seam (test-only implementers; G2 #233 owns the real API), editor.screenshot(view) in udea-agent-host. Cards: Game-tab gizmos project through the first CameraRig only; 3D Scene tab proven only by GlViewportOrbitTest (moba has no models).

- #214: merged, round 1 PASS (no findings). Reviewer: build --no-configuration-cache 959 tasks green, build-logic check green, GL 19/2/1 green, CI run 35432807405 (14a7907) 21/21 jobs green incl. iOS macOS and Windows latency. SIGBUS root cause: parallel GL test JVMs shared java.io.tmpdir, where LWJGL/box2d-jni extract .so files; fix = per-task temporaryDir for every forked Test/JavaExec (ForkedJvmTmpdirTest). Windows CharacterMover: warm-up too short (timing JIT), budgets unchanged, medians. Docs, skill and .claude/agents now say master is the integration branch.
- **kmp merged into master (fast-forward) after #214. The port (epic #199) is done; web #223/#226 remain shelved by the owner. From now on: branch from origin/master, merge into master.**

## Wave 13 (2026-09-19): done

- Baseline `5821d25`: FULLY GREEN, 928 tasks.
- Dispatched (4, owner "wip up to 4"): dev-195 (#195 save to .kts: patcher in :moba:desktop editor source set, Save
  action in udea-editor), dev-196 (#196 Play/Stop/Step/standalone: toolbar in udea-editor, CBOR-bytes restore, Stop
  hook point for #238 Keep), dev-physics (udea-physics2d over box2d-jni 1.0.0, no issue, tracked on #199), dev-188 (#188
  HUD on ComposeGL). **Known overlap:** #195 and #196 both add to udea-editor - told new files + minimal hooks; expect a
  trial-merge for the second one. physics and #188 may each add one libs.versions.toml hunk.
- **#196 r1 FAIL (2 findings):** (1) pre-Play undo of a delete puts shared Fleks Snapshot objects back live
  (EditorToolset.kt:848, EditorHistory.mark shares edits) -> play-time values after Stop; (2) Stop leaves snapshot-ring
  frames newer than the restored tick -> rewind lands in discarded play, and >120-tick play then Step x2 throws from
  SnapshotRing.kt:187 require. Relayed verbatim. **Ledger (passed r1):** build 928 green; evidence 5/5, M1 red 2/5;
  Stop between ticks (AgentGameLoop.pump drains before host.frame); standalone honest (separate MobaAgent JVM, UNDEAD
  11); public API used cross-module. Out of scope: stale score bar/camera after Stop.
- **#188 MERGED `569e8de`**, round 1 PASS, no findings. `MobaHudScreen` (ComposeGL composables) replaces the
  BitmapFont2D HUD; HudState/MobaHudModel/MobaHudTest byte-unchanged. New udea-render `CapturedUi` (internal ctor, via
  RenderResources.capturedUi(fonts)): second Kool view on the capturable OffscreenPass2d so game HUD is IN captures;
  UiLayer/overlay/editor stay out (structural). DejaVu Sans font in moba:desktop with licence beside it. runMatchShot
  checks HUD panels in all 7 PNGs (+ dead.png). HeadlessHostTest 6863/6864 = load flake (udea-core). Card: licence
  header text copied from udea-editor names the wrong module. Worktree kept `.claude/worktrees/agent-a80b8fdec32c67256`.
- **udea-physics2d MERGED `8de7cf5`**, round 1 PASS, no findings. box2d-jni 1.0.0 (native reports **Box2D 3.1.1**, README's
  3.3.1 wrong; owner corrected). jvm+android via new `udea.kotlin-multiplatform-jvm-android` convention, shared
  src/box2dMain + typealiases. Physics components now @Replicated all-@Sim (ids 22-26 appended), new
  `udea-core/net-protocol.lock` (by task), net-components.lock appended by hand (sorted-list gate checks it). Rewind:
  restore == rebuild-at-tick exactly; != unrewound run once bodies touch/spin (warm-start lost); only reachable via
  time.rewind+step and no game installs physics yet. Static chains ok; changed chain throws. Card: Physics2DModule KDoc
  should name the rewind limit. Worktree kept `.claude/worktrees/agent-afe076be56583b1e7`.
- **#195 MERGED `f2839c6`**, round 1 PASS, no findings. Asset panel in udea-editor; Save/Ctrl+S splices a
  KotlinPoet literal over the pass-1 span (git diff = one value); computed fields refused (typed `read_only_field`, reason +
  line); Save-as-new via KotlinPoet (0 diagnostics incl. UDEA0015). Patcher in udea-assets-compiler as `assets.fields`
  (paged) / `assets.set` / `assets.create` - deviation from issue (editor source set) ruled design choice: Kotlin compiler
  only on :moba:desktop agent/editor classpaths, not runtime. Hot reload works (AssetHotReload on kmp; #91 closed
  NOT_PLANNED). Cards: `$` string saves as ${'$'} and reads back read-only; editor keys "has it now" on `applied` not
  `pushedToGame`. Worktree kept `.claude/worktrees/agent-ad11f31c6132f037c`.
- **#196 MERGED `6efd35b`**, round 2 PASS (trial onto #195 green: build 958, editorTest, GL). editor.play/stop tools;
  Play = CBOR bytes via LevelService.saveNow + undo-history copy + commit open sessions; Stop = cancel sessions,
  LevelService.loadNow (1), afterRestore hook (2) for #238 Keep. Pre-Play undo during Play refused (`undo_before_play`);
  Stop reclaims pre-Play delete NetIds (NetIdIndex.reclaim). LevelService.apply truncates the rewind ring
  (TimeTravel.forgetAfter) on every load. Standalone = separate MobaAgent JVM on editor classpath. Card: score
  bar/camera one tick stale after Stop. Worktree kept `.claude/worktrees/agent-adaebd966d17e4337`.
- **WAVE 13 DONE: #188, physics2d, #195, #196 merged. Baseline `6efd35b`, 958 tasks.**
- **Epic #199: only #214 left** (plus #223/#226 shelved by the owner).
- Held: #241 A2 (asset compiler clip gen collides with #195), gizmo G2 #233 / G3 #234 (udea-editor busy), #189.
- Remaining for #214: #195, #196 (then docs + kmp -> master). Shelved #223/#226 stay open (owner's shelving).

## Wave 12 (2026-09-19): done

- Baseline `9d6629f`: FULLY GREEN, 917 tasks.
- Dispatched: dev-194 (#194 editor window: new udea-editor, settings, AGENTS.md, :moba:desktop editor source set,
  udea-render SceneView binding, new release-classpath MG rule), dev-232 (#232 gizmo G1: udea-agent editor toolset),
  dev-240 (#240 animated A1: glTF import; udea-render/model + model asset kind + Fox CC-BY sample). Three devs.
- **#232 MERGED `e8f3bed`**, round 1 PASS, no findings. editor.begin/update/commit/cancel_edit, editor.leave,
  multi-entity set_field (typed `List<NetId>` via existing @Arg List<T>), select/selection, common_fields (mixed).
  Idle timeout 30 s on AgentClock, read only in the AgentBridge.drain sweep before the step; cancels journaled.
  **.udearep format 2** (edits section; no edits = byte-identical format 1). ReplayWorld.applyEdits; moba's replay
  world does NOT implement it and refuses edited files loudly. Cards (no issue): EditorJournal.complete unread;
  replay-side editor needs a frozen idleClock; update_edit values cannot contain a comma; no production host records
  editor sessions yet. Worktree kept `.claude/worktrees/agent-a2d575d58570effd7`.
- **#240 MERGED `6a3caef`**, round 1 PASS, no findings (trial onto e1311e0: build 917 + GL green). `model("fox",
  file = ...)` -> typed `Model` asset (udea-assets, no Kool); Kool glTF loader in udea-render jvmMain (`loadModel`;
  `ImportedModel` ctor internal, so Android cannot reach it); `ModelSource` = MeshModel | ImportedModel; Y-up -> Z-up in
  one place. UDEA0038 (not glTF 2.0) new; UDEA0032 missing file; UDEA0004 misspelled id. Fox CC BY 4.0 credited. Empty
  first frame = first shader-program compile (not pinned inside Kool). Gap noted: misspelled-id test never shown red
  alone. Worktree kept `.claude/worktrees/agent-ac2178956ad64e85f`.
- **#194 MERGED `4ac81b7`**, round 1 PASS, no findings. New `udea-editor` (composegl-debug panels: Create, History +
  Undo, Edit menu); `:moba:desktop:runEditor` (editor source set) opens paused; udea-render `WorldView` blits the
  capturable pass into SceneView raw{} (HostState.Restore), stateless, reusable by #234. **UDEA-MG-010** (udea-editor
  on any compileClasspath/runtimeClasspath fails), **UDEA-MG-011** (renderers off udea-editor compile classpath).
  Build 928 tasks. Cards: translucent panels (#231 layout); a non-main, non-editor source set could take udea-editor
  without MG-010 firing (harden in #233); two stale KDocs. UdpTwoProcessTest rateLimited flake at load 25.
  Worktree kept `.claude/worktrees/agent-a92ca41c5832fb62e`.
- **WAVE 12 DONE: #232, #240, #194 merged.** Baseline now `4ac81b7`, 928 tasks.
- Held: udea-physics2d (settings/AGENTS.md collide with #194), #188 HUD (udea-render UI beside #194), #195/#196 (need
  #194), gizmo G2/G3 (need #194), A2 (needs #240).
- Contracts refreshed for wave 12: `scratchpad/lead/dev-contract.md`, `rev-contract-w11.md` (addenda updated).

## Wave 11 (2026-09-19): done

- Baseline `6a7a9b1`: FULLY GREEN. Any red on a branch is the branch's.
- Dispatched: dev-228 (#228 symbolic keys; udea-render input + moba controls), dev-192 (#192 binary
  test_level + loop ban; udea-assets-compiler, udea-diagnostics rule id, moba level, -Plevel on
  :moba:desktop), dev-213 (#213 delete old tree/LibGDX/legacy gates; root build, build-logic, AGENTS.md,
  CI, art source move). Box: 24 cores, 13G available, melon-merge running. Three developers.
- Shared developer contract (skill block + wave-11 addenda, task paths updated for the moba split):
  `scratchpad/lead/dev-contract.md`.
- Decisions: #228 subsumes ui/KeyTable.kt, per backend = GLFW + Android (web shelved, no stub).
  #192 level at `moba/game/levels/test_level.udealevel`. #213 art `git mv` out of example to a committed
  non-module path, licence position unchanged, never un-ignore moba/game/assets/sprites.
- **Owner request (dashboard, 2026-09-19): textured model with a material as an example.** No issue (owner
  rule); tracked by comment on #199. dev-model, branch `model-textured-example`, new files in udea-render
  (3D mesh + texture + Kool PBR material, Kool-free API), xvfb shot + GL test (texture and lighting
  mutations red). 4th developer (owner "wip up to 4"); told to check `free -g` before full builds.
- **Owner, 2D/3D + physics (dashboard, 2026-09-19):** separate Transform2D/Transform3D components (plain floats,
  no vector objects; one copy step per library in the engine), one renderer; 3D model system also lifts 2D
  positions onto the ground plane. Physics 2D = Box2D 3 (owner). Lead advice: use `de.fabmax.box2d-jni:box2d-jni`
  1.0.0 (Box2D 3.3.1, Kool-free) in a `udea-physics2d` module behind udea-core's `PhysicsWorld`, NOT
  `kool-physics-2d` (pulls kool-core into simulation/headless, UDEA-MG-002). 3D: Kool uses PhysX (physx-jni
  2.7.1); Box3D (erincatto, MIT, v0.1.0) has no JVM/KMP binding yet. No physics backend exists today (#213
  confirmed gdx-box2d unused). **Owner: "sounds good" - `udea-physics2d` (box2d-jni) QUEUED FOR WAVE 12** (commented on #199, no issue).
- **Owner brainstorm: editor gizmos (2026-09-19).** Spec `docs/superpowers/specs/2026-09-19-editor-gizmos-design.md`
  (`24c9fa4`, pushed). Owner explicitly asked for issues (overrides no-new-issues for this): epic **#231**, G1 #232
  (edit sessions/selection tools, udea-agent, needs #193 only: READY next wave), G2 #233 + G3 #234 (need #194), G4
  #235, G5 #236, G6 #237 (needs textured-model work), G7 #238 (needs #196). NOT part of #199, does not block #214.
- **Textured model MERGED `52d8684`**, round 1 PASS, no findings. `Transform3D` (udea-core, plain floats, Z up,
  @Serializable, not replicated: locks unchanged) + `ModelRenderer` (udea-render: ModelMesh box/sphere/plane,
  ModelMaterial albedo/roughness/metallic) drawn by `ModelRenderSystem` (RenderSystem) in its own Kool pass
  (KslPbrShader, directional + ambient, SimpleShadowMap, 4x MSAA) composited into the capturable 2D pass. 2D lift via
  `PoseSource`. `:udea-render:runModelShot`; `GlModelRenderTest`. **Ledger: public model types stay public on the
  #221 precedent - the first game that draws a model must call them** (moba showcase). Cards (commented on #199, no
  issue): cause of the empty first frame of a new mesh/material; mutation on the up axis; mutation making a
  model-less entity draw. Worktree kept `.claude/worktrees/agent-a13d42bc124dab5fc`.
- **#213 MERGED `72b949d`**, round 1 PASS, no findings. Deleted common, gradle-plugin, example, example:assets,
  migration ledger, the three legacy gates (+ CI step), every com.badlogicgames coordinate (incl. gdx-box2d), the
  udea.kotlin-library-gl convention. Old asset tree moved (history kept, 107 renames) to `example-assets/` (not a
  project; staging reads example-assets/sprites; assets-compiler test corpus lives there). MG-009 now bans LibGDX from
  EVERY project; MG-008 retired (not reused); MG-002 drops gdx patterns. `udea.migration-check` -> `udea.docs-check`.
  udea-render jvmTest fixtures moved off gdx to LWJGL GL11. Owner-open (commented #213): moba/game/assets/sounds are
  byte copies of LICENSE's 24 unknown-provenance sounds; LICENSE never named that path (pre-existing). Out of scope:
  RenderModuleGraphTest never reads moba/game/build.gradle.kts; bytecode banned-owner table has no Kool entry.
  Lead removed untracked leftovers of deleted modules in the main checkout (common/, gradle-plugin/, example/ build
  dirs, stale moba/assets/ staged art). Worktree kept `.claude/worktrees/agent-a56d33c250e95b123`.
- **#194 now unblocked** (settings/AGENTS.md collision gone).
- **Owner: imported animated models (dashboard, 2026-09-19), asked for issues.** Epic **#239**: A1 #240 glTF import
  (Khronos Fox, CC-BY 4.0; READY next wave), A2 #241 Animator + typed clips (sim, ticks, replicated), A3 #242 GPU
  skinning in udea-render, A4 #243 editor panel/scrub/bone gizmo (needs #194, #233, #234), A5 #244 FBX -> glTF at
  asset-build time. Not part of #199.
- **Wave 12 candidates:** #232 (G1, udea-agent), `udea-physics2d` (box2d-jni, no issue), #240 (A1, udea-render model +
  assets), #194 (editor window; now unblocked), #188 (HUD). Check module overlap before picking.
- **#228 MERGED `1c5b681`**, round 1 PASS, no findings. `InputKey` enum in udea-assets (`key(InputKey.W)`; `key(87)`
  no longer compiles); `KoolKeyTable` in udea-render (GLFW + Android tables, maps the UNIVERSAL code); ui/KeyTable.kt
  gone; runtime speaks names. udea-render -> udea-assets now `api`. Android: 9 punctuation keys collide with letters in
  Kool 0.19.0's Android map, left out and tested; card (no issue): load-time diagnostic when an Android key path runs.
  moba replay fixtures regenerated by task (only asset hash + trailer changed). **#192 must regenerate fixtures after
  merging origin/kmp.** Worktree kept `.claude/worktrees/agent-ae967e2598f737a7b`.
- **#192 r1 FAIL (1 finding):** loop ban misses `kotlin.repeat(...)` and an aliased import of kotlin.repeat
  (UdeaDeclarationScanner.kt:98-102). Relayed verbatim to dev-192. **Ledger (passed r1, do not reopen):** restart
  reloads saved layout (re-scatter gone; forced by exact positions; deterministic; replay-equality passes through it);
  UDEA0015 new id, span at loop; AC1 roster captured pre-deletion; boot shots byte-identical; missing -Plevel fails
  loudly; AssetDaemon keeps pass-1 diagnostics (forced by AC2), AssetsToolsetTest assertion unchanged; build 917 green;
  runNetProof identical to origin/kmp.
- **#192 MERGED `d22fcac`**, round 2 PASS (r1 FAIL: qualified/aliased repeat bypass). moba boots from
  `moba/game/levels/test_level.udealevel` via udea-core `LevelScene` + #191 `LevelService.load`; `-Plevel=<path>` on every
  `:moba:desktop` JavaExec. Restart reloads saved layout (re-scatter gone; replay fixtures regenerated, match one identical).
  Loop ban **UDEA0015**: pass-1 text check (early feedback) + **K2 `UdeaAssetLoopChecker`** in udea-compiler-plugin, run
  inside the asset scripting host (runtimeOnly dep; host loads plugins from classpath; K2-only host). Refuses loops,
  lambdas passed to callees not `@AssetDsl` (new, udea-annotations) without EXACTLY_ONCE/AT_MOST_ONCE contract, and direct
  recursion. Plugin-disabled switch turns the resolved check off (pass 1 remains) - ruled honest. Out of scope: script
  `fun interface` invoked twice by a helper; mutual recursion. Worktree kept `.claude/worktrees/agent-a359f0d7330f3ae2d`.
- **WAVE 11 DONE: #213, textured model, #228, #192 merged. In flight: 0.**
- Held: #194 (settings.gradle.kts + AGENTS.md table collide with #213), #188 (moba HUD beside #228),
  #189 (after #188; may be mostly done by #213), #195/#196 (need #194), #214 last.

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
- **Batch WAVE.md pushes - once per merge, not per note (from #229).** Since #229, EVERY push to `kmp`
  runs replay-equality-nightly's three 36000-tick legs. The lead had been pushing bookkeeping ~20 times a
  session. Commit WAVE.md locally as often as needed; push it WITH the next merge. Reviewer ruled the
  over-firing out of scope (CI minutes, breaks nothing); this is the lead-side mitigation, no code change.
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

## Wave 22 — the isometric camera, and the signing key

`master` is `7c62f50`. #257 merged at `713ebea` (reviewed at `9c8f2da`, PASS round 1, zero
findings), hygiene commit on top.

**#257 — orthographic isometric camera.** `ModelCamera` chooses a projection and publishes its
view and projection as sixteen plain floats each; `ModelStage` keeps one perspective and one
orthographic Kool camera for the stage's life and swaps the pass camera and the shadow map's
scene camera together; `IsometricRig` sits beside `ThirdPersonRig` on a shared `CameraMath`.
#262 un-projects through those matrices.

**Three things this wave taught that are not about cameras.**

1. **A check that measures nothing reports success.** Four instances in one afternoon: the
   developer's first world-hash test hashed an empty snapshot because the fixture allocated no
   `NetId`, so the equality compared two constants; the peer session's report writer truncated a
   file so a partial report read as a passing one; images in the shared gallery were
   indistinguishable from the run that supposedly produced them; and a size threshold had a
   failing case on only one side. The fix is always the same - make the failing case exist.

2. **A mutation is faithful because its magnitude comes through, not because something went
   red.** Mutation 2 injected 10% and the cube went 2314px/52x57 to 1944px/48x52 -
   `sqrt(2314/1944) = 1.091`. A red of the wrong magnitude is a red for a different reason and
   passes every check a normal suite has. Ask the reviewer to prove the mutation faithful
   *before* it trusts the red; untold, it reports "the mutation reds the suite" and you believe
   a red that was right for reasons nobody checked.

3. **A silent assertion can be correctly silent.** Mutation 2 does not red `assertOneSize`,
   because all four cubes scale together and the ratio stays 1.0. Its failing case is a separate
   control asserting the ratio must *exceed* 1.12, measuring 1.89, against a worst real case of
   1.035. Record this kind of thing in the ledger or a later round reads it as a gap and widens
   something.

**`build/debug-screenshots/` in the main checkout is shared by every agent on this box**, written
from their own worktrees, with nothing namespacing it. An image being there proves nothing about
which run made it. Make the developer state the producing run per image in the brief. Our GL
shots happen to be byte-identical across a rebuild, which means `cmp` proves "same bytes" and
never "same run" - mtime is the only thing that answers that.

**Scratchpad policy, decided this wave.** A ticket's `scratchpad/` keeps its mutation diffs and
its measurements, which is what a later reader checks a claim against. Raw build logs and `.orig`
copies of source files do not go in: the copies never update and a grep finds both.
`scratchpad/**/*.log` and `*.orig` are gitignored as of `7c62f50`. Hygiene goes in its own commit,
never by editing the merge - what was reviewed must be byte-for-byte what was merged.

**Publishing.** All four org secrets are set on `wildware-uk` (private visibility):
`MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD`, `SIGNING_KEY`, `SIGNING_PASSWORD`. The key is
`rsa4096/1BC2C0184273987F` "Wildware Ltd."; its passphrase and both armoured halves are in
`~/wildware-signing/` with a README, written when the key was made on 2026-09-11. The passphrase
was verified by signing a probe file before the secrets were set, not after.

**A trap to know about:** `gh secret set` accepts empty stdin without complaint. A failed
`gpg --armor --export-secret-keys | gh secret set` pipe stores an empty secret that looks set and
signs nothing - `gh` never sees gpg's exit code. Export to a file, check the byte count, then set
from the file. That is the same defect as everything in point 1, wearing a green tick.

**Still open:** the public key is on `keys.openpgp.org` but not on `keyserver.ubuntu.com`.
Snapshots do not need it; a real release does, and publishing to a keyserver is one-way, so it
waits on the owner.

**Box sharing:** see the memory note. Release explicitly, never on a lapsed timer, and a hold
means the agent is told not to start a JVM rather than trusted to finish early.

### Next ticket, not yet dispatched: make `:build-logic:test` reachable from `sh gradlew build`

Found 2026-09-20 by `dev-plugin-namespace` while working the plugin-namespace branch.

`build-logic` is an **included build**, so the outer `build` never reaches its `test` task.
`:build-logic:test` has not compiled since `4b2aca4` - today's #265 merge, which deleted
`ModuleGraphRules.governs` and `ModuleGraphRules.GAME_PROJECTS` and left
`ModuleGraphRulesTest.kt:701,712` calling them:

```
e: ModuleGraphRulesTest.kt:701:50 Unresolved reference: governs
e: ModuleGraphRulesTest.kt:712:61 Unresolved reference: GAME_PROJECTS
```

**Four hours, not three weeks - and that is luck rather than health.** The detector is switched
off, so the elapsed time says nothing. The same breakage could have sat there for a month.

**Be exact about which half is dark.** The `udeaVerify*` *tasks* - contracts, AGENTS.md, module
graph, determinism - are on the outer `check` and have been running fine throughout. What is
absent is the *unit* half: the tests of the rules themselves. The rules have been correctly
applied while untested. "The contract freeze gate is out of service" would be false.

**The part worth remembering: #265's reviewer could not have caught this.** It ran
`sh gradlew build` with no exclusions, got a real green, and passed. The tests #265 broke are
exactly the tests its own gate does not run. A reviewer doing everything right, on a green
build, merging a red suite.

Scope: **the wiring alone.** `dev-plugin-namespace` compiled the suite and counted the JUnit
XML reports rather than reading the console line: **374 tests, 0 failed, 0 skipped**, across 43
report files. Those two compile errors were the whole of the breakage; there is no cleanup
hiding behind them.

**That 374 is a baseline, not a comparison** - it is the first time anybody has seen the number,
because the suite could not compile to produce one. So if wiring it into the outer `build` turns
something red, expect it to be something the *wiring* newly exposes - a test reading a repository
file that is not declared as an input, say - rather than a regression against this figure.

Dispatch the moment the plugin-namespace branch merges; it edits `build-logic` too, so it cannot
run beside it.

## Wave 23 — the plugin namespace, and the dark suite

`master` is `dd82a11`. Two merges: the plugin-namespace fix (`d1bf245`, reviewed at `cc15575`) and
the build-logic gate (`8cf72f0`, reviewed at `6149934`). Both PASS round 1, zero findings.

**The engine is published.** `dev.wildware.udea:*:0.1.0-SNAPSHOT` is on Central's snapshot
repository, signed, and verified by downloading the jar and its `.asc` rather than by a green tick.
All four org secrets are on `wildware-uk`, scoped `selected` to `Udea` and `composegl`.

**Three traps this wave, all the same species: something reports success while measuring nothing.**

1. **`gh secret set` accepts empty stdin without a word.** A failed
   `gpg --armor --export-secret-keys | gh secret set` pipe stored a zero-byte `SIGNING_KEY` that
   looked set and signed nothing - `gh` never sees gpg's exit code. Export to a file, check
   `wc -c`, then set from the file.
2. **An org secret with `PRIVATE` visibility is invisible to a public repository.** `Udea` is
   public, all four secrets were `PRIVATE`, and the workflow received empty strings. The symptom
   named nothing: `secret key ring doesn't start with secret key tag: tag 0xffffffff`, which is
   end-of-input on byte one. Verify scope with
   `gh api orgs/wildware-uk/actions/secrets/<NAME>/repositories`, not the word `SELECTED`.
3. **`mavenLocal()` enforces no namespace rule**, so `scripts/outside-game-proof.sh` was green
   while the real publish 403'd. **Central is the first place that rule exists.** A proof that
   resolves locally proves nothing about publishing.

**`sh gradlew build` now runs `:build-logic:test`.** It never did before, because `build-logic` is
an included build - so the repository could be green while that suite was red, and it was for four
hours. Read the #265 comment thread for the whole of it; the sentence worth keeping is that
**#265's reviewer could not have caught it**, having run the full build correctly and got a true
green. A green build is evidence about the tasks the build reaches and nothing about the ones it
does not.

**CI does not test every commit.** `ci.yml:67-69` has `cancel-in-progress: true` keyed on the ref,
so on a fast-moving branch only the last commit of a burst is ever tested. Three commits this wave
have `total_count: 0` check runs. Right setting for a busy PR, wrong one for `master`.

**`master` is red on Windows**, in two pre-existing places neither branch caused:
`VerifyEditorAbsentTest.kt:62` asserts a forward-slash path against output Windows writes with a
backslash, and `UdeaAgentPluginTest > a release build generates a flag that refuses to bind()`.
Both want a developer; neither is a finding against anything merged.

**The shader design was rewritten at the owner's direction** (`67470ae`). Materials are **data** -
albedo, normal, metallic, roughness, emissive, no code. Shaders are **programs** - fragment, vertex,
compute - written in **GLSL**, not a Kotlin DSL. The first draft chose KSL for portability to a
backend that does not speak GLSL, and Udea has no such target: web is shelved, Kool has no iOS
backend, so `udea-render` is `jvm` and `android` alone. S1 (the screen-effect pass, closing #259)
is dispatched.

**In flight:** `dev-262` (pointer position and ground picking), `dev-266-shader` (S1, held off the
box until a slot frees). Both are in `udea-render`; their files are disjoint and neither adds a
replicated component, so neither regenerates a lock file. `dev-262` owns `model/ModelView.kt`,
`view/PickBounds.kt`, the camera rigs and anything touching `PointerState`.

**Parallel work exists outside this session.** Another agent filed #272 and opened PR #273 for the
same 403, forty minutes before ours merged. Check `gh pr list` before starting anything - we both
wrote the same fix.

---

## Wave 24 — 2026-09-20 late: shaders become an asset

**`master` is `a1b3527`** (merge of the screen-shader branch, #266 + #259). Post-merge build
`EXIT=0`, read off `pm266.marker`. That SHA is what this wave is cut from.

**Merged and closed earlier today:** #257 (`713ebea`), the plugin namespace rename (`d1bf245`),
the build-logic gate (`8cf72f0`), #262 (`a549816`), shader S1 (`a1b3527`). Issues closed: #257,
#262, #272, #259, #266.

**Published:** `dev.wildware.udea:*:0.1.0-SNAPSHOT` resolves from Central's snapshot repository —
engine modules, convention plugins and `udea-version-catalog`. Verified by fetching coordinates,
not by the workflow's tick. `udea.android-application.gradle.plugin` is **404 by design**: its
marker group is outside the verified `dev.wildware` namespace and is deliberately suppressed.

**In flight: three developers, every one on a build hold.** None may start a JVM until its own
gate file exists, under
`/tmp/claude-1000/-srv-ssd1-workspace-Udea/1ad8c5e6-2def-4055-91d2-72acdfe77daf/scratchpad/`:

| Developer | Branch | Modules it owns | Gate file |
|---|---|---|---|
| `dev-shaderassets` | `shader-assets` | `udea-assets-compiler`, `udea-render/shader`, docs | `BOX_FREE` |
| `dev-274` | `issue-274-net-components` | `build-logic`, `udea-gradle`, the template | `BOX_FREE_274` |
| `dev-270` | `issue-270-attachment-index` | `udea-core` attachments, `udea-render` model view | `BOX_FREE_270` |
| `dev-windows` | `windows-green` | `build-logic` and `udea-gradle` **tests only** | `BOX_FREE_WIN` |

**A gate file per developer, not one for the wave.** That is what lets me let them in one at a
time when the box comes back rather than having three builds start on the same second.

**`dev-270` owns the locks this wave.** It adds a `@Replicated` component, so it is the only branch
permitted to move `net-protocol.lock` and `expected-generated-hashes.txt`. The other two are told
to stop and tell me if their change moves either — that is a regeneration, not a text conflict.

The box is `melon-merge-31`'s while `dev-chaindecay` runs unit tests, an icon pack, a solo
scenario and `-Pscenario=all`. They release **in words**; the files are what I write when they do.
A hold that lapses because a timer ran out is not a release, and neither side knows the window
stopped being real.

**What it is.** The owner read `docs/new-game.md:306` —
`val source = checkNotNull(javaClass.getResource("/shaders/scanlines.frag")).readText()` — and
called it disgusting. They are right, and it is worse than ugly: `javaClass` is a JVM property, so
**that line cannot compile in `commonMain` at all**. The engine's own documented example breaks the
engine's own multiplatform rule. A `.frag` becomes an asset like a model, declared in a `.udea.kts`
and given a typed accessor, so a game writes `GameAssets.shaders.scanlines` and never a path. The
string-and-path overload stays for runtime-generated GLSL and stops being what the docs show.

No issue was created for it — the owner's rule. The decision record is a comment on #269:
`gh issue view 269 --comments`, the one naming the alternative and what to change to overturn it.

**Remaining shader tickets:** S2 (`UdeaMaterial` as data), S3 (a shader on an object), S4 (the
editor's material inspector), S5 (`UdeaCompute`). Spec:
`docs/superpowers/specs/2026-09-20-shader-api-design.md`.

**The defect of the day, and it is a whole class.** Ten instances in one afternoon, every one *a
check that reports success while measuring nothing*: a vacuous test comparing two constants, a
truncated report that read as passing, a one-sided threshold, stale images, an empty secret, a
stale JUnit XML, a stale marker, a false positive that named a real plugin, `pgrep` finding its own
command line, and zsh refusing to glob `--include=*.kt` and answering with the words you hoped for.
Three rules came out of it and they belong in every dispatch and every review prompt:

1. **A threshold needs a demonstrated failing case on both sides.**
2. **A mutation is faithful because its magnitude arrives**, not because something went red.
3. **Before believing a zero, make the search return a non-zero on something you know is there.**
   Any "X appears nowhere" finding needs a positive control printed beside it.

I walked into the zsh one **twice**, ten minutes apart, having just written it up. Knowing about a
trap does not change what you type; having the control ready does.

**`dev-windows` is the cheapest of the four to hold**, because its evidence is a Windows CI run
rather than a local build: there is no Windows on this box, so the diagnosis phase is `gh` calls
that fork no JVM. One red is `VerifyEditorAbsentTest.kt:62` asserting `"moba/editor"` against a
path Windows writes with a backslash - the gate is right and the assertion encodes the platform its
author was standing on. The other, `UdeaAgentPluginTest > a release build generates a flag that
refuses to bind`, has **no diagnosis**, and I deliberately did not invent one for the brief.

**Still open and unassigned:** CI's
`cancel-in-progress: true` on `master`; #272 option 3 (publish `udea-version-catalog` from the root
build). PR #273 is another session's to close or rebase.

**Published and verified from inside the jar, 2026-09-20 22:42 UTC.** Release run 35541062406 went
green and `dev.wildware.udea:udea-render-jvm:0.1.0-20260920.224220-4` on Central's snapshot
repository contains fourteen classes under `dev/wildware/udea/render/shader/` - `UdeaShader`,
`ScreenEffects`, `ScreenShaderSource` and the five uniform kinds. Checked by downloading the jar and
listing it, with a class that predates the shader work as the positive control and a made-up name as
the negative. A green workflow means an upload returned 200; the jar is what a game resolves.

Note for anyone dating an artifact: every entry in that jar is stamped `1980-02-01`. That is
deliberate - the build discards timestamps so the same source produces a byte-identical jar - so a
jar cannot tell you when it was built and the date has to come from the snapshot version string.

**Incident, mine, 2026-09-20: a clean rebase with the wrong result.** At `a2b73ff` I archived
`BRIEF.md` as `BRIEF-266.md` and left no `BRIEF.md`, so four branches each adding the file would not
conflict. That is exactly the shape **git's rename detection** looks for: `master` deletes a file, a
branch writes one with the same name, and git concludes the branch's file *is* the archived one
moving. `dev-shaderassets` rebased across it and had its brief applied onto `BRIEF-266.md`, leaving
no `BRIEF.md` at all. **No conflict, no warning, wrong result**, and the same fires on a merge - so
it would have reached `master` through me.

Fixed at the cause in `67d9f99`: `master` keeps a placeholder `BRIEF.md` whose text explains the
convention, so a branch's brief collides with it as an ordinary modify/modify conflict. `dev-windows`
then verified that on a throwaway branch rather than on its own - the rebase stops with
`CONFLICT (content): Merge conflict in BRIEF.md` and nothing else unmerged.

**The rule, from `dev-windows`, and it is the same one this repository keeps relearning:** *the
failure mode and the success mode print the same thing.* A clean rebase and a clean rebase that put
the file somewhere else are byte-identical at the terminal. So the check is **name the file and read
its first line**, never "no conflict appeared":

    head -1 BRIEF-266.md   # must be #266's title
    head -1 BRIEF.md       # must be the branch's own

Run it against one file you know should be untouched *and* one you know should carry your text -
one of each, or the check has only ever seen one answer. The same caution applies with far worse
consequences to `net-protocol.lock` and `expected-generated-hashes.txt`: a lock misfiled by rename
detection would still be one internally consistent file, and nothing downstream would report a
mismatch. After any regeneration, check `git status` names the files you expected **by name**,
rather than that the tree is clean.

**A reviewer's FAIL that was never applied, found 2026-09-21 by re-checking a 30-hour-old message.**
`review-wikicore-r1` failed the wiki branch over two false claims; the branch merged and **both
claims were still live on `master` two days later**. Now fixed: `docs/wiki/Home.md` said the
generated `Replicator` serves "save files" (it does not - a level file is Fleks' `world.snapshot()`
through `LevelService`, which the do-not list and the wiki's own `Levels.md` both state), and
`Replication-and-Networking.md:53` named `NoClientStateUploadTest`, which **does not exist**.

**Unassigned, and it is the root of the second one:** that phantom test name survives in two KDocs,
`udea-net/.../input/InputCommand.kt` and `.../replication/ReplicationClient.kt`. The wiki quoted a
comment, and the comment was wrong - *a description of a thing is not the thing*. Not fixed here
because `udea-compiler-plugin` propagates KDoc, so editing one can move
`expected-generated-hashes.txt`, which `dev-270` owns this wave. It is two words once that merges.

**And the process failure is worth more than the fix.** A verdict is only worth what gets applied
from it. `udeaVerifyWiki` gates links, paths and task paths - it cannot gate a *claim*, so nothing
failed when the wiki said something untrue, which is the same reason a stale `AGENTS.md` survives:
a document is a measurement with no exit code. Before quoting any of the four authority documents,
check the claim against the code rather than assuming its last editor did.

**Unassigned, from `dev-274`, waiting on a lock this wave:** `GeneratedSources.files` filters
`extension == "kt"`, so **every generated resource this build has ever written** - `net-protocol.lock`,
the tool manifest, the new component manifest - has been outside `GeneratedFileDeterminismTest`
entirely. `dev-274` covered them with a new `GeneratedSources.resources` rather than widening
`files`, because widening adds three rows to `expected-generated-hashes.txt`, which `dev-270` owns
this wave - a regeneration, not a text conflict. Once #270 has merged, widening `files` is a
one-line change plus three rows, and it buys the thing a two-run byte comparison cannot: a
**checked-in** hash, so a resource that changes for a reason nobody intended is caught in review
rather than being consistently wrong on both runs.

**Unassigned, from `dev-windows`, deliberately not fixed inside its branch:** `udea-agent`'s
`AssetsToolset` puts a native-separator `created.path` into a tool result's `path` field and
`udea-editor`'s `EditorAssets` prints it verbatim, which `MobaEditorSaveTest` asserts forward-slashed.
Same defect class as `UDEA-MG-012`'s message, **but not the same decision**: it is a shipped runtime
module, a tool result's `path` is not a `SourceSpan` so the frozen diagnostics convention does not
automatically reach it, and those tests are `editorTest`/GL-gated and skip on Windows - so they are
untested rather than correct. Wants an owner's view on whether an agent tool should answer
platform-native or repo-relative.

**Unassigned, found while waiting and deliberately not fixed mid-wave:** two Gradle
`DomainObjectCollection.all { }` calls in the gate code - `DependencyVerification.kt:37` and
`UdeaVerifyEditorAbsentTask.kt:110` - want a comment naming the receiver, because `.all { }` there
returns `Unit` and registers a callback rather than answering a question. Neither is wrong; both are
in `build-logic`, which `dev-274` owns this wave, so it waits rather than becoming an unreviewed
edit on somebody's branch. The general rule is now in `.claude/agents/engineer.md`.

**Backlog:** robot-game #258, #261, #263, #267, #268, #269, #270, #271, #274. Hollow #251-#255.


---

## Wave 25 — 2026-09-21: sky, model extras, and the first creatures

**Wave 24 closed with three merges.** Shaders as a declared asset kind (`318a0a9`), #274
(`ffcc10a`), #270 (`343d219`). Every post-merge build `EXIT=0` off its marker. Snapshot release
35559075031 dispatched at `00a2093`, which contains all three. `dev-windows` is still out: code done,
waiting on Windows CI run 35559034659 to **complete** rather than be killed by `cancel-in-progress`.

| Developer | Branch | Modules | Owns |
|---|---|---|---|
| `dev-271` | `issue-271-model-nodes-extras` | `udea-assets-compiler`, `udea-assets` | nothing exclusively - see below |
| `dev-267` | `issue-267-sky` | `udea-render` | nothing generated |
| `dev-251` | `issue-251-fox-waves` | `hollow:game` | `net-components.lock`, every `net-protocol.lock` |

**My lock-ownership split was wrong, and `dev-251` caught it before touching anything.** Component
ids are **global**: `net-components.lock` is one sorted list and every `dev.wildware.hollow.*` name
sorts first, so one Hollow component shifts every id in every lock **and** both moba `.udearep`
fixtures, which carry the protocol hash. `dev-271` moves the same two fixtures for a different reason
(the asset-graph hash). H2 (`16ddb43`) had already proved it. So the rule this wave, and from now on:
**each branch regenerates on its own branch to stay green; nobody resolves a generated file as text;
whoever merges second merges `master` in and regenerates on the merged tree, then measures.**
Ownership of a generated file cannot be assigned by module when the ids it holds are global.

**No gate files this wave.** The other project released the box for the night; `robot-game` still
builds on its own schedule and is outside every gate. Each developer runs `--max-workers=4` and
re-runs anything load-shaped alone.

**The daemon's metaspace is a shared resource.** `dev-windows`' rebased build went red with five
failures, every one `Metaspace`, after its daemon had served eight builds; a fresh JVM was green.
The remedy is `--no-daemon` for your own build. **Never `sh gradlew --stop`**: it kills every
Gradle 8.13 daemon this user owns, which on this night included the lead's post-merge build.

**Two claims I relayed to the owner as findings were wrong, and the #270 reviewer caught both.**
"The full-world scan is wrong about generations" does not hold - the scan compares whole `NetId`s;
the difference is a policy, not a generation bug. And the sentinel test's KDoc gives the wrong reason
for its `NetId.of(0, 0)` fixture: any other parent makes `add()` throw first, so the fixture is needed
for the test to *run*. Both corrected on #270. Both were specific, plausible and confidently written,
and nobody had executed them - which is the defect of the whole session in its final form: **an
explanation is a claim, and it needs the same evidence as a number.**

**Deferred to after `dev-251` merges** (it owns the locks): widening `GeneratedSources.files` to
cover resources; the phantom `NoClientStateUploadTest` name in two KDocs (`InputCommand.kt`,
`ReplicationClient.kt`), which propagate through KDoc into generated output.

**Backlog after this wave:** #252-#255 (Hollow H4-H7, sequential after H3), #258, #261 (after
#271 - both touch glTF extras), #263, #268, #269, #275, #276. #223 and #226 are the owner's shelving,
blocked upstream on Kool publishing no wasmJs artifact.
