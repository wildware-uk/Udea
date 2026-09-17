# Wave handoff — 2026-09-16, restart onto Kool + Kotlin Multiplatform

## kmp baseline

SHA `abba97b` (kmp after #205 merge; before: `4ca994d` #204, `a634450` #203), refreshed 2026-09-16; first taken at `6097ae7` on a detached checkout with
`JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem sh gradlew build --continue`:

**BUILD SUCCESSFUL. Failing tasks: none.**

**Plus, outside root `build`:** `sh gradlew -p build-logic check` is RED on `07eddef` (CI runs it):
`OuterBuildInputsTest > every repository file a build-logic test names is a declared input` - #203 left
udea-core KMP paths undeclared. Baseline failure, not any wave-4 branch's. Filed #216, dispatch in wave 5.
Reviewers and trial merges: run `-p build-logic check` too; that one test failing is baseline.

**CI baseline reds on kmp (run 35160551639, 07eddef):** migration ledger + 4x determinism (all #216), and
`clean build under budget` (ratio 1.116; it compares against retired `origin/example`, filed #218). Not branch findings.

Since #201 the build needs an Android SDK: add `ANDROID_HOME=$HOME/Android/Sdk` to every build command (developers, reviewers, trial merges). Without it: `SDK location not found`. That is environment, not a red build. Refresh after every merge.

## Wave 4 (2026-09-16): in flight

- Dispatched: #204 gas (dev-204), #205 assets (dev-205), #206 replay (dev-206), #209 net (dev-209). Disjoint modules.
- Wave 5 candidates: #216 (build-logic inputs, found wave 4), #218 (CI budget job base is retired example), #217 (determinism scanner misses TimeSource, found by dev-204).
- #204 gas: merged `4ca994d` (+ BRIEF-204.md `c58cb75`), round 1 PASS. Trial + merged build green; build-logic check only #216. udea-core `KClass.runtimeName` now public; `roundHalfUp` replaces Math.round (roundToInt differs on Wasm). Worktree kept: `.claude/worktrees/agent-a8b2f894959c4489d`.
- #206 replay: round 1 PASS at 310b5b9, no findings. Trial merge onto ca2d5f5 CONFLICTED (ci.yml, AGENTS.md, module-graph.md vs #204) - dev-206b merging origin/kmp into branch in the same worktree (agent-a32c9ac76b37a1e7a); then re-trial and merge.
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
