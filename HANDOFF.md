# Handoff

Written 2026-08-31, at commit `8035374` on branch `example`. It says where the work actually
stands, what is green, what is red, and what a reader should not mistake for finished. It is a
snapshot, not a contract: when it disagrees with `AGENTS.md`, `docs/engineering-standards.md` or
`docs/contracts/`, those win and this file is stale.

---

## Where the tree is

| Ref | Commit | Note |
|---|---|---|
| `example` | `8035374` | The Phase 7 work. **Was local-only until this handoff was pushed.** |
| `origin/example` | `8035374` | Now level with the local branch |
| `master` / `origin/master` | `ce7db67` | Deliberately not advanced. See below. |
| `feat-animations` | `f8957c0` | Old tree. Pre-rewrite `animationswip`, never merged |
| `feat-asset_rework`, `feat-assets_v2`, `feat-network_v2` | — | Local refs whose upstreams are `gone`. Old engine. Safe to delete. |

`master` still points at `ce7db67`. Nothing about `8035374` argues it should not be merged, but
merging it is a decision somebody should make deliberately rather than find already made — it
carries a known-red proof (below) and an unmet phase exit criterion.

---

## What `8035374` actually did

241 files, ~14.5k lines added. Five things, in rough order of how much they matter:

- **`udea-replay` exists.** `.udearep` input recording, deterministic headless replay, the
  bisect tools, and a `replay.*` toolset on the live agent surface (`/tools` went 45 to 51).
- **`MobaReplayProofTest`** records a real 2000-tick headless `moba` match driven by a
  wall-clock-seeded pilot, writes the `.udearep`, replays it into a fresh world and compares
  `WorldHasher.hash` every tick. Recorded result: 5 runs x 5 matches, 25/25 bit-exact, every
  match different. The in-suite mutation at t301 diverged at t301 in 5/5.
- **A determinism verifier** in `build-logic/determinism` (`udeaVerifyDeterminism`), wired into
  `check`, plus `determinism-audit.md` — the hand-written record of what the scanner
  structurally *cannot* see. Read the audit before trusting the scan.
- **A CI workflow** (`.github/workflows/ci.yml`), 13 jobs, two OSes, and for the determinism job
  two JVM vendors.
- **The lane pays gold.** Waves, tower aggro, last-hit gold, `:moba:runLaneShot` rendering
  wave/farm/clash PNGs on a real GL context.

Two integrator fixes worth knowing, because both were silent: `udea-codegen/net-protocol.lock`
and `expected-generated-hashes.txt` were regenerated (the lane's five components shifted the
fixture ids by 5), and `MobaReplayHost` was written — it was named in `ReplayHost`'s KDoc and did
not exist.

---

## Verify it

```
./gradlew build
```

No `-x` exclusions. Last recorded clean run at this commit: BUILD SUCCESSFUL, 2447 tests, 0
failures. That is a **recorded** result, not one re-run when this file was written — run it
yourself before believing it.

Three gates are deliberately outside `check` and are run by name:

```
./gradlew :moba:runUdpProof     # three OS processes, real UDP. CURRENTLY RED, see below
./gradlew :moba:runLaneShot     # lane PNGs, needs a real GL context
./gradlew udeaVerifyModuleGraph udeaVerifyNoLegacyDependencies udeaVerifyAgentsMd
```

Each is out of `check` for a stated reason in its own KDoc — wall-clock timing across forked
JVMs, or a GL driver that CI may not have. Do not "fix" that by wiring them in.

---

## Known red, and known unfinished

Three items. None is a surprise; all three are documented at the site, and this file exists so
they are not rediscovered.

**1. `:moba:runUdpProof` fails under loss, 5/5.** The 28-unit roster count agrees on both sides
5/5 and the perfect link matches 10/10, but under 5% loss the client sits 2-10 entities behind on
creep and projectile *creates* at the sampled tick, so the whole-roster hash differs. This is the
most useful thing to pick up next. Note the retraction that came with it: the earlier
**"57/57 under loss" claim does not hold** against a churning creep population and should not be
repeated until this is understood.

**2. `MobaPhysicsModule` is built, tested, and not installed.** It is absent from
`MobaGame.definition()` (see `moba/src/main/kotlin/dev/wildware/moba/MobaGame.kt:132`, which
explains it at length). The Box2D backend is real and its 18 tests pass, including a restore
proof that rebuilds 32 bodies bit-identically across a rewind on the real level. What keeps it
out is measured, not suspected: with the solver in, 27 units deal *more* total damage over 600
ticks (1134 against 975) and produce **zero** deaths against three, because crowd separation
holds the front line apart and damage spreads instead of focusing. First death moves from t~501
to t~701. Tuning was tried and rejected — `MAX_SEPARATION_STEP` at 2.5, 0.8 and 0.4 gave 10, 10
and 6 whole-suite failures while the solver's own separation floor failed at all three, which is
a chaotic system being fitted to a test count. **Installing it is one line; the balance pass over
unit health and damage is the work.**

**3. The replay-equality gate now covers `moba`, and `DriftWorld` is its self-test.** Issue #152
added the cross-OS `replay-equality` job to `.github/workflows/ci.yml`: three legs (Linux and
Windows on Temurin, Linux on Corretto) replay a checked-in `.udearep` headless, upload a per-tick
digest of every value `WorldHasher` folds, and a join step names the first differing tick, entity,
component and field. It replayed `udea-replay`'s own float-heavy world until issue #172, which is
the defect that mattered: that world routes its trigonometry through `StrictMath` on purpose, so
six green legs reported the health of their own fixture. Both jobs now replay `moba` —
`moba-3600.udearep` on every push and `moba-36000.udearep` nightly — and `DriftWorld` stays as the
gate's self-test, because it is the only place a divergence of exactly one ulp on exactly one
field at exactly one tick can be arranged. Regeneration is `:moba:udeaWriteReplayFixture` or
`:moba:test -Dupdate.replay.fixtures=true`; `determinism-audit.md` §0 is the written version.

---

## Process debt

- **`docs/decisions/phase-log.md` has no entries.** Not one, through seven phases of committed
  work. The file's own rule is that a checkpoint is answered out loud at each boundary while
  stopping is still cheap; that has not happened.
- **The eight phase-checkpoint issues were never opened.** The automation's token could read
  issues but not create them (`403 Resource not accessible by personal access token`). The
  template, the blocking order and the verbatim exit criteria are all in `phase-log.md`; opening
  them is a manual step nobody has done.
- **The Trello board is stale.** `DOING` holds one card — "Create basic example game" (#28) — and
  every card was last touched 2026-08-23 in what looks like a bulk pass. It describes the *old*
  engine's backlog. Either re-point it at the rewrite's phases or stop treating it as the
  backlog; right now it is neither.

---

## The old tree is still here

`settings.gradle.kts` still includes `common`, `gradle-plugin`, `example` and `example:assets`.
Phase 6's exit criterion — settings down to the new modules only — is **not** met. The migration
ledger (`docs/migration/ledger.md`) carries 143 rows: 128 `rewrite`, 14 `delete`.
`udeaLegacyReport` fails on a file with no row and `udeaVerifyMigration` fails on an unreviewed
copy, so the ledger is enforced rather than aspirational — it just is not finished.

Nothing new may depend on `common`; that is a Gradle rule, not a convention.

---

## If you are picking this up cold

Read `AGENTS.md` first — module arrows, the tick model, the frozen contracts, the do-not list.
Then `docs/engineering-standards.md` section 8, which is the list a reviewer rejects against.
Then come back here.

The honest ordering of what is next:

1. **The lossy-UDP divergence.** It is red now, it is understood only as a symptom, and it blocks
   any repeat of the convergence claim.
2. **The first real cross-OS divergence, if #172's gate finds one.** Both `replay-equality` jobs
   now replay `moba` rather than a world written not to drift, which is the first time this
   repository has asked whether the *game* is bit-exact across two operating systems and two JVM
   vendors. A red leg is a finding rather than a fault: the join names the tick, the entity, the
   component and the field, and prints the `replay.*` loop that walks into it.
3. **The Phase 7 checkpoint entry** in `phase-log.md` — cheap, and it is the mechanism that was
   supposed to catch exactly the drift this file is documenting.
4. **The physics balance pass**, if the fight is what you care about. One line to install; the
   work is everything after.
