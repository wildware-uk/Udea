# Wave handoff — 2026-09-01, waves 4–6

**Six tickets dispatched, six merged, every one at review round 1 with zero findings.**

`example` went `db477f4 → 32eab56 → d1526f9 → e7159c1 → cada9ed → 293649b → c3c3c41 → 784f614`,
all pushed. `master` untouched at `ce7db67`, as `HANDOFF.md` reserves.

| Wave | Issue | What merged | Commit |
|---|---|---|---|
| 4 | #172 | The determinism gate replays the game, not a world written not to drift | `32eab56` |
| 4 | #176 | A Windows checkout stops defeating the gates that read their own subject | `d1526f9` |
| 5 | #174 | `docs/contracts/` frozen by a lock the build reads | `cada9ed` |
| 5 | #175 | The latency budgets measure latency, not the build contending with them | `293649b` |
| 6 | #180 | `build-logic` tests declare the files they read, and a guard finds the sixth | `c3c3c41` |
| 6 | #182 | The wall-clock census, and the five gates still inside the build | `784f614` |

**CI on `example` at `784f614` (run `33462366896`): 24 jobs, 24 green, 0 red.** The first fully
green run in this sequence. Wave 3 opened at 10 red.

Worktrees left on disk under `.claude/worktrees/`, none removed: `agent-a8b9947d3dbda0c54` (#172),
`agent-a6181135d60b20d17` (#176), `agent-a3feddd0686493e0b` (#174), `agent-a5773b1d0f90f1f83` (#175),
`agent-a9ffff83bda14fb94` (#180), `agent-a937a08ec67e02f7e` (#182).

---

## The three waves in one sentence

**Every gate in this repository was asked the same question — *does it run, and can it fail?* — and
five of them answered no.**

| Found green having measured nothing | How |
|---|---|
| The cross-OS `replay-equality` matrix | Replayed `DriftWorld`, written to be deterministic (#172) |
| `AgentsMdTest` on Windows | Failed on CRLF, so it reported the same red whether `AGENTS.md` was stale or not (#176) |
| `docs/contracts/` | Declared frozen; nothing read the files (#174) |
| The `latency budgets` CI job, first green run | Both runners served all six gates `FROM-CACHE` (#175) |
| `build-logic`'s own tests | Undeclared inputs, so `UP-TO-DATE` after an edit to the file they police (#180) |
| `udeaPhysicsRebuildBudget` | Worst sample **over** its budget in both full runs, inside a green build (#182) |

Three of those were caught by the developer against its own work, before any reviewer saw it.

---

## What is now enforced by code rather than declared

- **`docs/contracts/`** — `docs/contracts.lock` (SHA-256 per file), `udeaVerifyContracts` on `check`,
  rules `UDEA-FRZ-001/002/003` covering edit, add, delete, rename, directory deleted, lock deleted.
  Route out is a **named task**, `udeaWriteContractLock`, deliberately not a `-P` flag: a `-P` flag
  can be passed to a whole `build` and re-baseline the freeze as a side effect, which is the act the
  gate refuses.
- **Wall-clock budgets** — eleven, all on `udeaLatencyBudgets`, measured by their own CI job on both
  runner images with `--no-parallel --max-workers=1`, never cacheable, never up-to-date-able.
  `WallClockBudgetCensusTest` reads every test source and requires each clock reading to be a member
  of the aggregate or a census row saying what it is instead. `LatencyBudget.measuredBy` refuses a
  budget measured by any task but its own. **Four enumerations were needed before the census existed;
  it is what makes a fifth unnecessary.**
- **`build-logic` test inputs** — the five files declared, plus `OuterBuildInputsTest`, which compares
  reads found in test sources against *the collection Gradle actually resolved*, handed over as a
  manifest rather than regexed out of the build script.
- **Phase 7** — the gate replays `moba` on every push (`moba-3600`) and nightly (`moba-36000`), and
  **has been watched failing on it**: run `33444524021`, planted ulp, join red, naming
  `dev.wildware.moba.Position.x` at t1200.

---

## Read this before you dispatch anything

### The developer contract changed twice and the old advice is now wrong

- **The latency budgets are NOT on `check`.** A green `sh gradlew build` does not cover them. Run
  `sh gradlew udeaLatencyBudgets --no-parallel --max-workers=1`. **The old "they fail under load,
  re-run them alone" advice is obsolete** — if one fails inside a `build` now, something was wired
  back onto `check` and that is a real finding.
- **`udeaVerifyContracts` IS on `check`.** Do not edit `docs/contracts/`; if a ticket needs a
  contract's content changed, it stops and says so.
- **`:build-logic:test` does not run in the root `build`** — `grep -c 'build-logic:test'` over a full
  build log is 0. CI runs it as its own `-p build-logic` steps. A green `build` never covered it.

### Two pre-existing failures on this box that are not yours

- **`KotlinPinCheckTest` x2** — no JDK 17 installed here and `GradleFixture` writes a
  `settings.gradle.kts` with no foojay resolver. Fails identically on a clean `example`; confirmed
  three times this run. It lives in `build-logic`, so it looks like your fault if you are working
  there.
- **`:moba:runUdpProof`** under 5% loss. `HANDOFF.md` documents it.

### `example/`'s corpus is NOT committed with CRLF, whatever #176's body says

Measured, not reasoned: every **text** blob under `example/` is LF in the repository. The 1914 CR
bytes that exist there are **all inside `.png` and `.ogg` files** — binary payload. Option 3
(`* text=auto eol=lf`) remains the trap, for a better reason than the one recorded: it would put 80
binary blobs on git's `text=auto` heuristic. Corrected on #176.

### The estimator question has been settled twice, in opposite directions, both times by measurement

`udeaBenchCharacterMover` moved from median-of-9 to **best-of-25** (#175): one-sided error, and the
tail was over the line on a run where the code was fine. `udeaWarmEditBudget` **keeps
`samples.max()`** (#182): 19.6% spread against the daemon gate's 37%, and a per-edit deadline makes
the tail the subject. **Measure before choosing; do not reason by analogy from either.**

---

## Left for the owner

- **#179 — the Phase 7 checkpoint. One word: continue, stop, or re-plan**, plus the entry in
  `docs/decisions/phase-log.md` in the same change that closes it. All three spec section 6 exit
  criteria are ticked with evidence on the issue; the decision was deliberately left blank, because
  spec section 7's mitigation for the top risk is that *a person* says it out loud, and an agent
  writing "continue" into an append-only log empties it of the only thing it does. **That file still
  has no entries through seven phases.** Note the issue also records that Phase 7 landed while
  Phases 3–6 still carry open work, so answering it in isolation could imply more than is true.
- **Mark `replay-equality`, `latency budgets` and `the FIR checkers fail a real build` as required
  status checks.** Branch protection; no agent can set it. All three now produce a verdict and all
  three have been seen to fail for the right reason.
- **Run `sh gradlew :udea-assets-compiler:udeaPackGate --rerun` once locally.** Carried from wave 2.
  #168's contract derives frame size from the images and asserts every frame is 100x100; a real
  corpus that is not uniform will newly fail on your machine only. Relax the uniformity assertion,
  not the corpus.
- **Whether `example` merges into `master`.** Still yours, still untouched.

---

## Pick up next

1. **#178** — the `gl tests (xvfb)` `OffscreenBackendTest` shutdown flake. Passed in the final run,
   but it is a flake with four recorded occurrences and it is the only job making the GL surface
   pass-or-fail. `dev-172` recorded the newest detail: a `CancellationException` at
   `OffscreenBackendTest.kt:206`, on a branch touching no GL code.
2. **#183** — two more tests reading repo files their build script does not declare
   (`udea-annotations`, `udea-codegen`), neither script declaring any inputs at all. Same class as
   #180, one module out. Includes the question of whether #180's guard should generalise.
3. **#181** — `clean build under budget` flips red and green on identical work. Now has **seven**
   data points and a recorded swing of **21 806 ms** (#182's runs), wider than the 13 558 ms the
   issue records. It already runs in isolation, so it needs a better-conditioned measurement.
4. **#184** — `udeaWarmEditBudget`'s 3000 ms line measures ~180 ms now it runs alone. One constant
   doing two jobs badly: a product contract and a regression threshold.
5. **#166** — item actives and unique passives. Untouched for three waves.

**Roster note:** #183 and #184 are disjoint (different modules) and either pairs safely with #178.
Memory is the ceiling on this box, not cores — it is shared with `melon-merge`'s dev team, and
~3.5G per developer against what `free -g` reports available is the budget that held all three waves.
