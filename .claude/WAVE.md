# Wave handoff — 2026-08-31 (seed)

**No wave has run yet.** This file was written when the dev team was set up on this
repository, so it is the starting state rather than a report from a previous lead. The next
lead to finish a wave overwrites it with what actually happened.

Trust `git log`, the open issues and `HANDOFF.md` over this file where they disagree.

## The integration branch is `example`

| Ref | Commit | Note |
|---|---|---|
| `example` / `origin/example` | `4d4b471` | **This is where the team works.** All of Phase 7 |
| `master` / `origin/master` | `ce7db67` | Deliberately behind. **Do not merge into it, do not push it** |
| `feat-animations` | `f8957c0` | Old tree, pre-rewrite, never merged |
| `feat-asset_rework`, `feat-assets_v2`, `feat-network_v2` | — | Local refs whose upstreams are `gone`. Old engine, safe to delete |

Whether `example` merges into `master` is **the owner's outstanding decision**, recorded in
`HANDOFF.md`: nothing about `8035374` argues against it, but it carries a known-red proof and an
unmet phase exit criterion, and it should be made deliberately rather than found already made.
Do not make it for them, and do not ask — leave it and say so in your report.

## The gate was red on arrival, and is green again

`HANDOFF.md` records `sh gradlew build` as BUILD SUCCESSFUL, 2447 tests at `8035374` — and warns
that is a **recorded** result. It did not reproduce. Two independent causes, both now fixed:

**1. `:moba:udeaValidateAssets`, 25 x `UDEA0032`.** `moba/assets/sprites/` is gitignored
third-party art, so it is absent from any fresh checkout **and from every developer worktree**.
`python3 scripts/stage-moba-art.py` copies 33 sheets out of
`example/src/main/resources/assets/sprites/`. Documented only in that script's own docstring —
`AGENTS.md`, `CLAUDE.md` and `HANDOFF.md` all say "run `./gradlew build`" and none mention it.
Now in the developer contract and in the lead's dispatch list. **Not committed** — the staged
files stay gitignored, which is the point.

**2. `:common` could not resolve `Fleks:2.13-SNAPSHOT`.** That snapshot came from the
`wildware-uk/Fleks` fork and has been unpublished; upstream Quillraven/Fleks has moved a long way
since. Fixed by pointing `common` at the catalog (`libs.fleks`, 2.14, the same version the new
tree already uses) and migrating the two accessors in
`common/src/main/kotlin/dev/wildware/udea/contextReceivers.kt`. The fork's
`Entity.get`/`getOrNull` were plain generic; upstream 2.14 makes both
`inline … <reified T : Component<*>>`, so the wrappers had to become `inline`/`reified` too.
`contains` needed nothing — upstream takes a non-generic `UniqueId<*>`.

> **Fleks 2.15 is published** and the catalog is on 2.14. Bumping the whole repo is a separate
> ticket: it touches `udea-core` and therefore the wire protocol's component surface, so it is not
> a drive-by.

**Known flake: `:udea-assets-compiler:udeaDaemonBudget` fails under load.** It is a latency budget.
Alone it is comfortable — median 170ms over 4 samples, and 134ms. Both its tests failed inside a
full `build` on a box at load 9, and passed on a re-run. **That is the box, not the branch.**
Re-run it alone before concluding anything.

## What is red, and known

**1. `:moba:runUdpProof` fails under 5% loss, 5/5.** The 28-unit roster count agrees on both
sides 5/5 and the perfect link matches 10/10, but under loss the client sits 2–10 entities
behind on creep and projectile *creates* at the sampled tick, so the whole-roster hash differs.
It failed before this team existed. **Do not let a developer report it as their regression**, and
do not accept a claim that it is fixed without the numbers.

**Retraction that came with it: the earlier "57/57 under loss" claim does not hold** against a
churning creep population and must not be repeated until this is understood.

**2. `MobaPhysicsModule` is built, tested, and not installed.** Absent from
`MobaGame.definition()`; `moba/src/main/kotlin/dev/wildware/moba/MobaGame.kt:132` explains at
length why. The Box2D backend is real and its 18 tests pass, including a restore proof that
rebuilds 32 bodies bit-identically across a rewind on the real level. What keeps it out is
**measured, not suspected**: with the solver in, 27 units deal *more* total damage over 600
ticks (1134 against 975) and produce **zero** deaths against three, because crowd separation
holds the front line apart and damage spreads instead of focusing. Tuning was tried and
rejected — `MAX_SEPARATION_STEP` at 2.5, 0.8 and 0.4 gave 10, 10 and 6 whole-suite failures.
**Installing it is one line; the balance pass over unit health and damage is the work.**

**3. There is no replay-equality gate in CI (#152).** Phase 7's exit criterion is bit-exact
replay on **two** OS/JVM combinations and today the replay proof runs on one machine.
`determinism-audit.md` and the determinism job's own comment both point at a cross-OS
`replay-equality` job as *the* gate, and it does not exist. **Phase 7 is not done.**

## Traps on this repository

- **Invoking Gradle at all.** `JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem sh gradlew build`.
  `sh gradlew`, because the wrapper is checked in **without the executable bit** and `./gradlew` dies
  with `Permission denied` before Gradle starts. `JAVA_HOME` at 21, because the default `java` here is
  Temurin 25.0.2, Gradle 8.13 refuses it, and **the entire error message is the line `25.0.2`** — no
  cause, no hint, no mention of Java. There is no JDK 17 on this box; `jvmToolchain(17)` is satisfied
  by provisioning while the launcher JVM is whatever `JAVA_HOME` says.
  The generated `gamebridge.json` names `./gradlew`, so `launch_instance` fails the same way —
  `chmod +x gradlew` in the worktree and **never commit that mode change**.
- **The GL skip is silent.** `-Pudea.render.requireGl` defaults to **false**, `check` depends on
  `udeaGlTest` and `udeaAgentGlTest`, and `$DISPLAY` is empty on this box — so those tests
  **skip** and the build stays green. A green `sh gradlew build` is not evidence about GL. Any
  GL-touching ticket must carry an xvfb run with `-Pudea.render.requireGl=true`.
- **A clean text merge is not a consistent merge.** Two branches that both add a replicated
  component merge with zero textual conflicts and produce a `udea-codegen/net-protocol.lock` and
  an `expected-generated-hashes.txt` that agree with **neither**. That is a regeneration, not a
  text resolution. `udeaCheckProtocolLock` runs on `check`, so **building the trial merge is what
  catches it**; regenerate with `:udea-codegen:udeaWriteProtocolLock` and
  `:udea-codegen:test -Pudea.updateGeneratedHashes=true`, and send the result back to the developer
  to commit rather than committing it in the merge.
- **The backlog lies.** Issues **#147, #148, #149, #150, #151** are open and describe work that
  **shipped** on `example` at `8035374` — `udea-replay`, the deterministic replay, the
  determinism scanner. Grep the tree before dispatching. A ticket left open is not evidence the
  work is outstanding.
- **Read `determinism-audit.md` before trusting `udeaVerifyDeterminism`.** It is the hand-written
  record of what the ASM scanner structurally *cannot* see.
- **This box is shared with `melon-merge`**, whose own dev team runs fifteen-minute scenario
  suites. `pgrep -af "melon-merge|fruitgame"` before assuming a quiet machine, and count its JVMs
  against your own 90% budget.
- **pid magnitude tells you nothing.** `pid_max` is 4194304 and the counter has wrapped, so a
  process started a minute ago can be pid 688. Read `/proc/<pid>/cmdline` before acting on a pid.
  There are dozens of orphaned X displays under `/tmp/.X11-unix`; same rule.
- **Ports.** `moba`'s declared bridge range is **7840–7859**, set in `moba/build.gradle.kts` off
  the engine default of 7820–7839 because `melon-merge`'s bridge scans 7811–7829 and overlapping
  ranges let either project's bridge enumerate and `stop_instance` the other's game.

## Process debt, inherited

- **`docs/decisions/phase-log.md` has no entries.** Not one, through seven phases of committed
  work. Its own rule is that a checkpoint is answered out loud at each boundary while stopping is
  still cheap. If a ticket closes a phase boundary, write the entry.
- **The eight phase-checkpoint issues were never opened.** The automation's token could read
  issues but not create them (`403 Resource not accessible by personal access token`). The
  template, the blocking order and the verbatim exit criteria are all in `phase-log.md`.
- **The old tree is still in `settings.gradle.kts`** — `common`, `gradle-plugin`, `example`,
  `example:assets`. Phase 6's exit criterion is not met. `docs/migration/ledger.md` carries 143
  rows (128 `rewrite`, 14 `delete`) and is enforced by `udeaLegacyReport` and
  `udeaVerifyMigration`; it just is not finished.
- **The Trello board is stale**, describing the old engine's backlog. Every card was last touched
  2026-08-23.

## Cards filed — do NOT re-file these

None yet; no wave has run. **Search open issues before filing anything** — on the sister project
a lead filed a duplicate of a developer's card within minutes because it did not search first.

## Pick up next — the honest ordering from `HANDOFF.md`

1. **The lossy-UDP divergence.** Red now, understood only as a symptom, and it blocks any repeat
   of the convergence claim.
2. **The `replay-equality` CI job (#152)**, because without it Phase 7 cannot be closed and the
   determinism story rests on a scanner its own audit says is insufficient.
3. **The Phase 7 checkpoint entry** in `docs/decisions/phase-log.md` — cheap, and it is the
   mechanism that was supposed to catch exactly the drift `HANDOFF.md` documents.
4. **The physics balance pass**, if the fight is what you care about. One line to install; the
   work is everything after.
