# Wave handoff — 2026-09-16, restart onto Kool + Kotlin Multiplatform

## kmp baseline

**Not taken yet.** The first lead of the port runs, on a detached `origin/kmp` checkout:

    JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem sh gradlew build --continue

and writes here the SHA and every failing task (expected: none, since `kmp` == `master` == old
`example` plus docs). Refresh after every merge. The list only shrinks.

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

1. Take the baseline above.
2. Wave 1: **#200** (Kool Offscreen spike) and **#201** (KMP convention plugin). Disjoint; nothing else
   is ready until they merge.
3. Then #202, then #203–#210 in parallel where modules are disjoint (see spec section 9).

`composegl-kool` (#210) is built in `wildware-uk/composegl`, not this repository.
