# Wave handoff — 2026-09-16, restart onto Kool + Kotlin Multiplatform

## kmp baseline

SHA `73a09e5` (kmp after #219 merge; trial tree identical, root build + build-logic check green); earlier `fcdeb63` (kmp after #193 merge; trial tree identical, root build + build-logic check green); earlier `303abe7` (kmp after #217 merge; trial tree identical to merged tree, root build + build-logic check green); earlier `25cc650` (kmp after #218 merge; trial root build + build-logic check green); earlier `47ec3b9` (kmp after #208 merge; trial root build + build-logic check green); earlier `89e6113` (kmp after #220 merge; trial root build + build-logic check green); earlier `e9639e0` (kmp after #207 merge; trial root build + build-logic check green; `6d95f67` #216, trial tree identical, root build + `-p build-logic check` both green; `dc6c708` #209; `236ad47` #206; before: `abba97b` #205, `4ca994d` #204, `a634450` #203), refreshed 2026-09-16; first taken at `6097ae7` on a detached checkout with
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

## Wave 7 (2026-09-17): in flight

- composegl-ef replied: composegl-kool free. Must also touch root build.gradle.kts `published`, `.github/ci-legs.json`, ci.yml OpenGL job. Avoid WorldPanel.kt/ScenePass.kt (composegl #230). Model: KorgeCanvas (HostState.Restore, maxSceneSize), SceneView proof c61c2a30. `--max-workers=2`, never `--stop` there. Land squashed+rebased `push origin HEAD:master`; snapshot via `release.yml -f kind=snapshot` only.
- #210 split: JVM desktop + SceneView + snapshot here; Wasm/Android + Wasm demo -> #222 (added to epic). #211 needs #222 too.
- #215 ruling: vendor Fleks 2.14 as `udea-fleks` (Central latest 2.15, no iOS). Commented.
- Dispatched: dev-215 (Udea worktree, branch issue-215-vendor-fleks), dev-210 (composegl-wt/kool-210, branch issue-210-composegl-kool).

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
