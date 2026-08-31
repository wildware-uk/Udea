# Wave handoff — 2026-08-31, wave 1

**Three tickets dispatched, three merged at review round 1, zero findings on any of them.**
`example` went `866ba0a → a1d5217 → 303fc4b → 901bde2`, all pushed. `master` untouched at
`ce7db67`, as `HANDOFF.md` reserves.

| Issue | What merged | Commit |
|---|---|---|
| #152 | `replay-equality` CI job, 3600-tick fixture, two-JVM axis, field-level divergence | `a1d5217` |
| #132 | The shop: `item` asset kind, `Inventory`, `ShopSystem` buy/sell/recipe | `303fc4b` |
| #154 | LICENSE art-path exclusion fix, executable fresh-clone proof | `901bde2` |

Developer worktrees left on disk under `.claude/worktrees/` — `agent-a6cc34edc8f68a0fb` (#152),
`agent-a5b3c68bd564f1fda` (#132), `agent-ae07475ff2761864b` (#154). Nobody asked for them to go.

---

## Read this before you dispatch anything

### The backlog lies more than the last handoff said

The previous WAVE.md named #147–#151 as open-but-shipped. Add to that list:

- **#160 was shipped too.** `AgentAnchor.kt` in `udea-agent`, and `AgentMarkers.kt` /
  `AgentOverlaySystem.kt` / `AgentOverlayModel.kt` / `AgentOverlayView.kt` / `OverlayCanvas.kt` /
  `OverlayPalette.kt` in `udea-agent-host`. `AgentOverlayViewTest` covers five of its six
  acceptance criteria **by name**. Left open, scoped down to the one real gap — "allocation-free
  per frame" has nothing measuring it. Commented on the issue.
- **#154's premise was stale**: it says "no `LICENSE` file", and one had existed since `3f962bb`.

**Two of my three dispatch decisions were built on a wrong reading of the tree.** Both were caught
by developers, not by me:

1. I told dev-132 that `visibility = OwnerOnly` on `Inventory` "must be proved". Nothing in the
   engine enforces `OwnerOnly` at all — see #167 below. The instruction was unbuildable.
2. I told dev-154 to "add a real `LICENSE` file". It existed.

**How #2 happened, because the failure mode is repeatable.** I ran `ls LICENSE* COPYING*` in zsh.
`COPYING*` matched nothing, zsh's `nomatch` aborted **the whole command** before `ls` ran, and the
only output was `no matches found: COPYING*`. I read that as "no LICENSE". **Never let a glob you
have not confirmed share a command with the thing you are actually testing.** Use `ls -d LICENSE`
on its own, or `git show origin/example:LICENSE`.

Grep the tree before every dispatch, and grep for the *thing*, not the ticket's vocabulary.

### The scratchpad is shared per project, not per session

`/tmp/claude-1000/-srv-ssd1-workspace-Udea/<uuid>/scratchpad/` is **the same directory for every
agent on this project**, despite the harness advertising it as session-specific. Two developers'
mutation logs silently overwrote each other under identical filenames. Nothing errored. dev-154
caught it only because a log it had just written came back holding dev-132's `RecipeTest` failures
with a report path in dev-132's worktree.

**It changed an answer.** dev-132 re-ran its mutations after the warning and one row moved: M4
reddened 4 tests the first time and 11 the second, the first pass having read a stale packed
bundle.

So: give every reviewer an explicit, unique report filename in its prompt, tell developers to
write working files under a directory of their own naming, and **tell every reviewer to apply
mutations itself rather than trust a table**. Both reviewers did, and both branches held up.

### `BRIEF.md` collides add/add on every merge

Every developer commits `BRIEF.md` at the repo root, so the second and every subsequent merge of a
wave conflicts there. It happened on #132 and again on #154. I resolved each to the incoming
branch's copy; earlier ones stay retrievable (`671a75a:BRIEF.md`, `8b8de40:BRIEF.md`).

**`example` currently carries #154's brief, which describes only #154.** That is misleading.
Either stop committing briefs to the integration branch, or name them `BRIEF-<issue>.md`. Decide
it at the start of the next wave rather than resolving the same conflict three more times.

### The wall-clock budget family is FOUR tasks, not one

The developer contract names only `:udea-assets-compiler:udeaDaemonBudget`. Under load, these also
fail and pass alone:

- `:udea-assets-compiler:udeaDaemonBudget`
- `:udea-assets-compiler:udeaPackGate` (`GraphBudgetTest`)
- `:udea-core:udeaBenchCharacterMover`
- `:udea-agent-host:udeaPhase2Exit`

I confirmed all four against clean baselines today. `udeaBenchCharacterMover` deserved real
suspicion on #152 because that branch touches `udea-core`; I ran it on the pre-merge baseline
(2.34/2.92ms) against merged (2.86/2.47ms) — same distribution, no regression. **Do that
subtraction rather than waving at "box load".**

**Load average is the wrong signal.** dev-154 established the right one: what matters is a
competing *Udea* `gradlew` build, not the machine's one-minute average. It measured a load of 15
as quiet by that criterion and the budgets passed solo at 166/110/4.81ms. `pgrep -cf "[g]radlew"`
is useless here — it counts melon-merge's `gradlew lwjgl3:run` game loop. Read
`/proc/<pid>/cmdline` and filter to this repository.

### Three developers is the ceiling, and it hurt

24 cores, ~13G available at dispatch. Three developers plus their reviewers drove load to 52 and
made every budget task above flap. The wave still finished clean, but every merge needed two or
three build attempts. **Two developers would have been faster in wall-clock terms.**

---

## Issues opened this wave

- **#166** — item actives and unique passives, split out of #132 so that ticket was one
  reviewable pass. Depends on #132, now merged.
- **#167** — `@Net(visibility = OwnerOnly)` is **declared and enforced by nothing**. Only three
  references in the whole tree: the enum case in `udea-annotations/Net.kt:56` and two lines of
  `AnnotationVocabularyTest`. No codegen mask, no per-recipient stripping in `udea-net`. `AGENTS.md`
  lists that vocabulary in its **frozen contracts** table, so this is a frozen contract nothing
  enforces. Design in the issue follows #114's `lifetime` precedent.
  **Live consequence: a champion's `Inventory` now replicates to every client relevant to that
  entity.** #132 built no workaround, so #167 has clean ground.
- **#168** — `AtlasPackerTest` ×7 and `ReproducibilityTest` ×2 skip on every machine but the
  owner's, gated on a 327-sheet corpus only the paid archives produce. `MobaArt`'s own KDoc says
  the corpus is 2269 same-size frames, "the exact case where a packer's tie-break decides
  everything", and that "a three-sheet fixture would have passed a determinism test that this
  corpus fails". Structural skip hiding a green build.

**Dropped rather than filed**, per the standing instruction: the `replay-equality` join job's
missing `if: always()` (no false green — the red leg fails on its own); a stale `internal` comment
in `NetStateProbe.kt:151`; a scene-swap inventory teardown and two champions shopping in one tick,
both coherent by construction; `common`/`gradle-plugin` POMs declaring Apache-2.0 against an MIT
`LICENSE` (recorded on #154 instead — deleting those modules in Phase 6 resolves it).

---

## Left for the owner

- **Mark `replay-equality` required for merge.** Branch protection; no agent can set it.
- **Whether `example` merges into `master`.** Still yours, still untouched.
- `common`/`gradle-plugin` publish Apache-2.0 POMs. Harmless until someone runs `mavenLocal`.

---

## What is still red or unfinished

- **`:moba:runUdpProof` fails under 5% loss, 5/5.** Unchanged, pre-existing. `HANDOFF.md`
  documents it. Still the honest top of the queue.
- **`:moba:runNetProof` reports `perfect units DISAGREED`.** Confirmed pre-existing this wave by
  running it on the branch and on `866ba0a` in the same checkout — identical verdicts. This is a
  **different task** from `runUdpProof` and is *not* covered by the `HANDOFF.md` note. Nobody owns
  it. It probably deserves a ticket once somebody understands it.
- **Phase 7 is not done.** #152 closed one of three exit criteria. The other two need a real
  Actions run and #165's nightly.
- **The gate covers engine float paths, not `moba`'s.** #152's fixture is `udea-replay`'s own
  `DriftWorld`, because a checked-in `moba` `.udearep` is refused by `BuildIdentity` the moment
  `protoHash` moves — which #132 then did. Wiring `moba` in is one task registration once #165
  lands the regeneration flag.
- **`docs/decisions/phase-log.md` still has no entries.** Nothing this wave closed a phase
  boundary, so I added none. It remains the mechanism that was supposed to catch exactly this
  drift.

### Orchestration docs that were wrong — FIXED at the wave-1/wave-2 boundary

`HANDOFF.md` item 3 said "There is no replay-equality gate in CI"; dev-152 corrected it in-branch.
The same claim survived in four `.claude/` files. dev-152 correctly left them alone mid-wave rather
than editing the harness under a running team. **All four are now corrected**, between waves with
no team running:

- `.claude/WAVE.md` (this file), fixed during wave 1.
- `.claude/agents/team-lead.md:197` — the "prefer what unblocks a phase" list. Item 2 now says the
  job **shipped** at `a1d5217` and names what Phase 7 actually still owes: a real Actions run,
  #165's nightly, and pointing the gate at `moba`.
- `.claude/skills/dev-team/SKILL.md` — "what Phase 7 still owes (the cross-OS `replay-equality` CI
  job, issue #152)" replaced, and it now says to read `WAVE.md` **before** `HANDOFF.md`, because
  `HANDOFF.md`'s Phase 7 section is stale and nobody is updating it. The hardcoded `example` SHA
  (`4d4b471`) is gone too — it said to read `git log` instead, since that line goes stale every wave.
- `.claude/skills/wave-reset/SKILL.md:79,126` — the example goal, which was the achieved one.

Still outstanding in the developer contract: the four-task budget family and the shared-scratchpad
trap. Put both in every dispatch prompt verbatim until the contract itself carries them.

### `reset.sh` lost the goal on every reset, and that is fixed too

The script sent `/clear`, then the `/dev-team` restart, then `/goal`. **The goal never executed.**
`/dev-team` begins a turn that runs for minutes, so a `/goal` typed behind it is not read as a
command at all — the harness delivers it into the running turn as a mid-turn user message, where it
reads as a passing remark. Every wave since this script was written has run with no standing goal
set, and it was invisible because a swallowed goal looks exactly like a goal nobody passed.

The new order is one key and three submissions: **`Escape`, `/clear`, `/goal <goal>`, then the
restart last.** The Escape is what makes the rest land — the lead calls the script from inside a
tool call, so a turn is always in flight, and a `/clear` typed under a running turn queues behind it
instead of clearing. Everything is scheduled from the detached subshell now, the Escape included,
because the Escape kills the turn that launched the script.

**Verify it fired before dispatching anything.** The goal arriving is the thing to check.

---

## Pick up next

1. **#167** — a frozen contract that nothing enforces, with a live information leak behind it now
   that `Inventory` ships. Highest value on the board.
2. **The lossy-UDP divergence.** Still red, still understood only as a symptom.
3. **#166** — item actives and unique passives; #132 left the schema fields ready for it.
4. **#165** — the nightly and `--update-replay-fixtures`, which unblocks pointing the equality
   gate at `moba`.
5. **#168** — cheap, and it turns nine permanently-skipped tests into a real gate.
