# Wave handoff — 2026-08-31, wave 4

**Two tickets dispatched, two merged, both at review round 1 with zero findings between them.**

`example` went `db477f4 → 32eab56 → d1526f9`, both pushed. `master` untouched at `ce7db67`.

| Issue | What merged | Commit |
|---|---|---|
| #172 | The determinism gate replays the game, not a world written not to drift | `32eab56` |
| #176 | A Windows checkout stops defeating the gates that read their own subject | `d1526f9` |

Worktrees left on disk: `.claude/worktrees/agent-a8b9947d3dbda0c54` (#172),
`agent-a6181135d60b20d17` (#176). Nobody asked for them to go.

---

## The wave in one sentence

**Phase 7's last clause closed: the gate now replays `moba`, and it has been watched failing on it.**

#152 and #169 built a working cross-OS gate and pointed it at `DriftWorld` — a world that routes its
trigonometry through `StrictMath` on purpose. A green matrix reported the health of its own fixture.
Both legs now replay a recorded `moba` match: `moba-3600.udearep` on every push, `moba-36000.udearep`
nightly. `DriftWorld` stays as the gate's self-test.

**The result worth keeping:** 36,000 ticks of a real match — every replicated field of every unit,
plus the RNG state and the id allocator — are cell-for-cell identical across Windows Server 2025 and
Linux, and across Corretto and Adoptium. First time this repository has established that about the
game rather than about a purpose-built world.

**And the gate has been seen to fail on it.** Run `33444524021`, dispatched with
`replay_plant_ulp_at: 1200`: join RED, `FAILED at t1200`, one differing cell,
`dev.wildware.moba.Position.x`, `0x4397abfe` vs `0x4397abff`. A gate nobody has watched fail is
unverified; this one is not.

---

## What CI on `example` looks like now

Run `33448310887` at `d1526f9`: **18 green, 4 red** (was 16 green / 6 red at `32eab56`, and
10 red at the start of wave 3).

**Newly green this wave:** `determinism (windows-latest, temurin)` and
`determinism (windows-latest, corretto)` — both were red on CRLF, and both are the jobs that actually
run `AgentsMdTest`. `clean build under budget` also recovered.

**Still red, and every one maps to a filed issue:**

| Job | Failing task | Issue |
|---|---|---|
| `build (ubuntu-latest)` | `:udea-assets-compiler:udeaDaemonBudget`, `:udea-assets-compiler:udeaPackGate` | **#175** |
| `build (windows-latest)` | `:udea-agent-host:udeaPhase2Exit` — **and nothing else now** | **#175** |
| `build with the K2 plugin disabled` | same budgets | **#175** |
| `gl tests (xvfb)` | `OffscreenBackendTest` shutdown flake | **#178** |

`build (windows-latest)`'s second failure used to be `:udea-assets-compiler:test` — the CRLF bug.
It is gone. **The branch removed a failure and added none.**

---

## Read this before you dispatch anything

### Phase 7's checkpoint is open as #179 and it is waiting on one word

`docs/decisions/phase-log.md` has **no entries through seven phases**, and the reason recorded in it
is that the eight checkpoint issues could not be opened — the automation's credentials got
`403 Resource not accessible by personal access token`. **That limit no longer applies.**

So **#179, `Phase 7 checkpoint: decide whether to continue`**, is now open, with all three spec
section 6 exit criteria ticked and evidenced:

- replay equality on ≥2 OS/JVM combinations — met, and on `moba`
- divergence as first differing tick **and field**, not a failing hash — met, run `33444524021`
- the allowlist is a reviewed artefact, not a dumping ground — met: `determinism-allowlist.txt`
  carries **zero exception entries**, only two version pins, behind a 233-line
  `determinism-audit.md` and a parser where `ALLOW004` fails the build on a stale entry

**The one-word decision was deliberately left blank.** Spec section 7's mitigation for the top risk
is that *a person* says out loud whether to continue; an agent writing "continue" into an append-only
log that forbids hindsight edits empties it of the only thing it does. Do not fill it in for them.

Note what #179 says and a future lead should not gloss: **Phase 7 landed while Phases 3–6 still carry
open work** (#127–#135, #141–#145, #103). Spec section 8's open question 3 anticipated exactly that,
so it reads as deliberate — but "past Phase 7" is not "past Phases 3–6" here.

### Two corrections this wave produced

**`example/`'s corpus is NOT committed with CRLF, and #176's body says it is.** Measured rather than
reasoned: every **text** blob under `example/` is LF in the repository, and the CRs a Windows checkout
shows come from the checkout filter. The 1914 CR bytes that do exist in the tree are **all inside
`.png` and `.ogg` files** — binary payload, not line endings.

Option 3 (`* text=auto eol=lf`) is still the trap, for a better reason: it would put **80 binary
blobs** on git's `text=auto` binary-detection heuristic. The correction is commented on #176 rather
than filed, because a corrected premise belongs where it will be quoted from.

**Acceptance criteria can name a job that cannot run the test.** #176's criterion 1 asked for
`build (windows-latest)` to pass `AgentsMdTest`. `build-logic` is an *included build*
(`settings.gradle.kts:3`), so the root `build` never runs its tests and that job cannot ever run that
class. It runs in `determinism (windows-latest, *)`. Worth checking when writing a criterion that
names a job.

### The wall-clock budgets will waste your time if you let them

`:udea-assets-compiler:udeaDaemonBudget`, `:udea-core:udeaBenchCharacterMover`,
`:udea-agent-host:udeaPhase2Exit` and `udeaPackGate`/`GraphBudgetTest` fail inside a full `build`
under load and pass alone. This wave they went red for the lead twice and for both reviewers once
each, at loads between 10 and 22 — the box is shared with `melon-merge`'s dev team.

Measured alone: `udeaDaemonBudget` 170–187ms against ~900ms; `udeaBenchCharacterMover`
2.049–2.154ms against 4.0ms; warm validate 124ms against 300ms. **Re-run alone before concluding
anything**, and that is #175's whole subject.

---

## Left for the owner

- **#179 — the Phase 7 checkpoint. One word: continue, stop, or re-plan**, and the entry in
  `phase-log.md` in the same change that closes it. Everything else on that issue is filled in.
- **Mark `replay-equality` and `the FIR checkers fail a real build` as required status checks.**
  Carried from wave 3. Branch protection; no agent can set it. Both produce a verdict and both have
  now been seen to fail for the right reason — `replay-equality` on a `moba` fixture as of this wave.
- **Run `sh gradlew :udea-assets-compiler:udeaPackGate --rerun` once locally.** Carried from wave 2
  and now more interesting: `udeaPackGate` is failing in CI too, and #168's contract derives frame
  size from the images and asserts every frame is 100x100. If a real corpus is not uniform, relax the
  uniformity assertion, not the corpus.
- **Whether `example` merges into `master`.** Still yours, still untouched.
- **Two orphan `Xvfb` servers** (`:104` at 16h, `:99` at 5h, both parentless). Left again: the box is
  shared and neither is provably ours.

---

## Pick up next

1. **#175** — three of the four remaining red jobs. The wall-clock budgets cannot pass on a loaded
   runner, and four waves have now paid the re-run tax.
2. **#178** — the last red. The GL flake on the only job that makes the GL surface pass-or-fail.
3. **#174** — `docs/contracts/` is declared frozen and nothing enforces it. Every other cross-cutting
   agreement here has a verifier task; this one has a reviewer's attention. It is the single
   remaining gap in "every frozen contract enforced by code rather than only declared".
4. **#166** — item actives and unique passives. Untouched for two waves.

**Do not run #175 and #176-shaped work in the same wave** — both live in `udea-assets-compiler`.
That collision is why #175 was held back this wave; it is now free to run.
