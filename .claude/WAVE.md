# Wave handoff — 2026-09-16, restart onto Kool + Kotlin Multiplatform

## kmp baseline

SHA `90b26fc` (kmp after #201 merge), refreshed 2026-09-16; first taken at `6097ae7` on a detached checkout with
`JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem sh gradlew build --continue`:

**BUILD SUCCESSFUL. Failing tasks: none.**

Since #201 the build needs an Android SDK: add `ANDROID_HOME=$HOME/Android/Sdk` to every build command (developers, reviewers, trial merges). Without it: `SDK location not found`. That is environment, not a red build. Refresh after every merge.

## Wave 1 (2026-09-16): done

- #200 Kool Offscreen spike: merged `d97517b`, round 1 PASS. Yes: Kool 0.19.0, GL on llvmpipe under xvfb.
  Needs an X11 GLFW-init workaround and a per-backend row flip; noted on #211. Code in `spikes/kool-offscreen/`.
- #201 KMP convention plugin: merged `90b26fc`, round 1 PASS. Plugins `udea.kotlin-multiplatform`,
  `udea.kotlin-multiplatform-render`, `udea.kotlin-base`, `udea.jvm-test-fixtures`.
- Cards filed: none. Notes carried as comments on #211 (Kool workarounds) and #203 (`udeaVerifyDeterminism`
  layout; add module to CI `ios-tests`).

## Standing rulings and traps

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

1. Wave 2: **#202** (generated registry, the one contract change allowed) and **#207** (udea-audio, needs
   only #201) if `grep -r ServiceLoader udea-audio` is empty, so they are disjoint. #203 (udea-core) overlaps
   #202's discovery sites, so it waits for wave 3 unless #202's diff proves otherwise.
2. Then #203; then #204, #205, #206, #209 in parallel (all need #203); #208 needs #202 and #203.
3. #210 lives in `wildware-uk/composegl`; a separate `composegl-ef` session is active there. Check its state
   before dispatching.
