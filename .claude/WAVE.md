# Wave handoff — 2026-08-31, wave 2

**Three tickets dispatched, three merged at review round 1, zero findings on any of them.**
`example` went `5dc9024 → 1f6cddd → 65d6ac9 → 71c3a36 → 3881dcc`, all pushed. `master` untouched at
`ce7db67`, as `HANDOFF.md` reserves.

| Issue | What merged | Commit |
|---|---|---|
| #167 | `@Net(visibility = OwnerOnly)` enforced end to end; `moba`'s `Inventory` no longer leaks | `65d6ac9` |
| #168 | 327-sheet synthetic corpus; the nine atlas determinism tests run on every clone | `71c3a36` |
| #169 | The `replay-equality` gate produces a verdict — its first ever | `3881dcc` |

Worktrees left on disk under `.claude/worktrees/` — `agent-a3079d2d31be163cf` (#167),
`agent-a8e84931037574687` (#168), `agent-a30fa1e7426fea7a5` (#169). Nobody asked for them to go.

---

## Read this before you dispatch anything

### CI has been red on every push, and it is TWO faults, not one

Nobody had read a CI run in a long time. Both faults were found by reading `gh run list`, not by
any ticket.

1. **#170 (open, unassigned, first pick).** Every job that builds `moba` fails with 25 x
   `UDEA0032` on `moba/assets/sprites/` — gitignored licensed art a clean clone does not have.
   `build` on both OSes, `clean build under budget`, both `determinism` legs, `plugin-disabled`
   and `bridge-conformance`. `scripts/stage-moba-art.py` fixes it from art **already committed**
   under `example/`, and nothing in CI runs it.
2. **#169 (merged).** The `replay-equality` legs died at the upload step on every run since #152,
   so `replay-equality-join` — which `needs:` them — had never executed. **#152 closed against a
   gate that had never compared anything.**

**The lesson worth carrying: a closed ticket is not a working feature.** #152 shipped a job, a
fixture, a matrix and a divergence renderer, all reviewed and all correct, and the thing still did
not run. Read the Actions run, not the merge commit.

### A detached review checkout cannot build `moba`, and it costs an hour every time

Same root cause as #170. `git worktree add --detach` gives a tree with no `moba/assets/sprites/`,
so `:moba:udeaValidateAssets` and `:moba:udeaPackBundle` fail and **`:moba:test` never runs** —
which silently breaks any evidence command that names a `:moba:` test. `review-167-r1` lost an hour
to it and warned the next reviewer would too.

**Do this in every review checkout and every trial merge, before the build:**

```
cd /tmp/<checkout> && python3 scripts/stage-moba-art.py
```

Everything it copies is gitignored, so `git status` stays clean and the tracked tree remains
exactly the branch. I did it on all three trial merges this wave. Put it in the reviewer prompt.

### Name briefs `BRIEF-<issue>.md`. This is settled

Wave 1 hit an add/add conflict at the repo root on the second and third merge, and each resolution
threw away the previous ticket's brief. Ruled at the start of this wave and commented on all three
issues; all three developers complied and there was **no conflict at any merge**. `example` still
carries wave 1's `BRIEF.md` (#154's) plus `BRIEF-169.md`. Leave them or bin them, but do not go
back to the unnumbered name.

### Three developers is fine; the four budget tasks are not evidence of anything

24 cores, ~14G free at dispatch. Three developers plus three reviewers peaked at **load 33**. It
cost nothing but patience: **every trial merge failed on wall-clock budget tasks and every one
passed solo.**

| Task | Under load | Alone |
|---|---|---|
| `:udea-assets-compiler:udeaPackGate` (`GraphBudgetTest`) | 34.9ms / 15.478ms | 11.18ms / 11.80ms |
| `:udea-assets-compiler:udeaDaemonBudget` | 1022ms + 661ms | 147ms / 99ms |
| `:udea-core:udeaBenchCharacterMover` | 12.476ms vs 4ms | 2.590ms |
| `:udea-agent-host:udeaPhase2Exit` | fail | 12ms |

The procedure that works: `until awk '{exit !($1 < 9.0)}' /proc/loadavg; do sleep 20; done` in a
background shell, then re-run the failing task with `--rerun-tasks` alone. **Do not merge on a
loaded red build and do not merge without re-running it.**

dev-168 did the better version of this: a matched control on `origin/example`, same box, minutes
apart, which failed **more** budgets than its own branch. That is the arithmetic answer to "did my
change push a budget over", and it beats waving at load.

### The trap that should scare you most this wave

dev-169 wrote `digests` + `/` + `*` into a KDoc in `udea-replay/build.gradle.kts`. That opens a
**nested Kotlin block comment which runs to EOF**, and it switched off *every* `udeaReplay*` task
registration.

- `sh gradlew build` stayed **green** — none of those tasks is in `check`.
- `ReplayEqualityProofTest` stayed **green** — it was matching text that was present in the file
  and merely commented out.
- Only `gradlew :udea-replay:tasks` noticed.

That class has read the raw build script since #152, so the weakness was pre-existing. It now
strips comments with nesting and string literals handled; the reviewer planted a `/*` itself and
confirmed the fence goes red. **`sh gradlew :udea-replay:tasks` is a cheap check worth typing after
anything touches a build script.**

### What the reviewers did right, and what to keep asking for

All three verdicts were PASS at round 1 with zero findings, and none of them was a rubber stamp:

- `review-167-r1` checked the `NetStateProbe` narrowing **arithmetically** — exactly one of moba's
  16 replicators implements `OwnerOnlyFields`, so `andNot(netMask, EMPTY) == netMask` for the other
  15 — rather than accepting a plausible story about why a desync probe went quiet.
- `review-168-r1` measured the corpus off the **PNG `IHDR` headers on disk**, not from
  `CorpusShape`. A quietly-30-sheet corpus would have passed every test on the branch.
- `review-169-r1` read the **Actions logs and jobs API**, not the brief's list of run URLs.

Each applied the developer's own mutations itself. Keep putting "apply the mutations yourself, do
not trust the table" in the reviewer prompt — it is the line that turns a review into a check.

---

## Issues opened this wave

- **#169** — merged. Filed and closed in the same wave.
- **#170** — CI cannot build `moba` on a clean clone. **Open, unassigned, and the first thing to
  pick up.** It blocks reading CI at all, and it blocks pointing the replay gate at `moba`.

**Dropped rather than filed**, per the standing instruction: `VisibilityPolicy.ownerOnlyMask`'s
`as?` + `require` per component per entity per recipient per tick (a type check, not reflection —
hoist only if the packer shows in a profile); the narrowed `NetStateProbe` no longer covering
whether an owner receives its *own* private fields (moved to two live-client tests, said plainly in
the KDoc); the join printing two pairwise comparisons for three legs (a chain against a reference,
transitive over byte equality); a double space `run: >-` leaves when the plant expression is empty.

---

## Left for the owner

- **Run `sh gradlew :udea-assets-compiler:udeaPackGate --rerun` once locally.** #168's shared
  contract now derives frame size from the images and asserts every frame is 100x100. The real-art
  twins have never been *executed* anywhere — there is no paid art on this box, which is why the
  ticket existed — so a real corpus that is not uniform will newly fail **on your machine only**.
  If it does, relax the contract's uniformity assertion, not the corpus.
- **Mark `replay-equality` required for merge.** Branch protection; no agent can set it. It now
  actually produces a verdict, so this is finally worth doing.
- **Whether `example` merges into `master`.** Still yours, still untouched.
- **The eight phase-checkpoint issues.** `docs/decisions/phase-log.md` says they were never opened
  because the automation's token got `403 Resource not accessible by personal access token`.
  **That note is stale — this session's `gh` creates issues fine** (#169 and #170 are proof). I did
  not open all eight unasked; it is a one-command job whenever you want the mechanism installed.

---

## What is still red or unfinished

- **#170.** Every `moba`-building CI job, every push. Nothing else on this list moves until it does.
- **`:moba:runUdpProof` fails under 5% loss, 5/5.** Unchanged, pre-existing, `HANDOFF.md`
  documents it. Still the honest top of the queue after #170.
- **`:moba:runNetProof` reports `perfect units DISAGREED`.** Pre-existing; confirmed again this
  wave by `review-167-r1` running it on the branch and on a control checkout of `origin/example`
  and getting identical verdicts. Nobody owns it.
- **`MigratedCorpusCompilesTest`** fails identically on `origin/example` — another #170 symptom,
  confirmed by `review-168-r1`.
- **`docs/decisions/phase-log.md` still has no entries.** I deliberately added none: #169 closed
  *half* of one exit criterion, not a phase boundary, and that file's own rules forbid an entry
  that flatters progress.

### Phase 7, precisely

Do not read "#169 merged" as "Phase 7 done". The exit criterion in `ci.yml:1028` is "The cross-OS
`replay-equality` CI job. Nothing else."

- **Done, and confirmed on `example` itself rather than only on a topic branch.** Run
  [33425001370](https://github.com/wildware-uk/Udea/actions/runs/33425001370) at `d6146e3`:
  all three legs green — ubuntu/temurin, ubuntu/corretto, windows/temurin — and
  **`replay-equality (join)` green**, its log reading `3 digest stream(s)` then
  `replay equality holds: 3600 tick(s) of 'drift-3600.udearep' are cell-for-cell identical`.
  It is also proven to FAIL: run
  [33419266780](https://github.com/wildware-uk/Udea/actions/runs/33419266780) with
  `replay_plant_ulp_at=1200` goes red at t1200 naming `Drifter.x`,
  `A=6.565088 (0x40d21533)` vs `B=6.5650873 (0x40d21532)`, with t1195-t1199 agreeing.
  A gate that only ever passes is the defect #169 existed to fix, so both directions matter.

  **Everything else in that run is red, and all of it is #170 or older.** The ten failing jobs
  are `build` x2, `clean build under budget`, `determinism` x4, `plugin-disabled`,
  `bridge-conformance` — every one of which builds `moba` — plus `the FIR checkers fail a real
  build`, which was failing identically on run `33413123651` before this wave started.
  `replay-equality` is the only job whose result CHANGED this wave, and it changed red to green.
- **Not done:** the gate replays `udea-replay`'s own `DriftWorld`, **not `moba`**. That is the
  goal's remaining clause and it is blocked on **#170** — a gate cannot be pointed at a module CI
  cannot build.
- **Not done:** #165's nightly 36000-tick fixture and `--update-replay-fixtures`. Held this wave
  with the reason commented on the issue: it extends a gate that had never executed, and building a
  nightly on that would have produced a second thing that also did not run. **That objection is now
  spent — #169 is merged, so #165 is unblocked.**

---

## Pick up next

1. **#170** — CI cannot build `moba`. Unblocks reading CI, and unblocks pointing the gate at `moba`.
2. **#165** — the nightly and `--update-replay-fixtures`. Now genuinely unblocked.
3. **Point the replay-equality gate at `moba`.** Needs #170 first, and #165's regeneration flag,
   because a checked-in `moba` `.udearep` is refused by `BuildIdentity` the moment `protoHash`
   moves — which #167 just did (`0xea9f -> 0xc67b`). No ticket exists yet; file one when #170 lands.
4. **#166** — item actives and unique passives. Its lock collision with #167 is now resolved,
   because #167 has merged.
5. **The lossy-UDP divergence.** Still red, still understood only as a symptom.
