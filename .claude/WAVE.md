# Wave handoff — 2026-08-31, wave 3

**Four tickets dispatched, four merged.** Three at review round 1 with zero findings; #170 took two
rounds on a single finding that was the lead's, not the developer's.

`example` went `7942823 → c74c730 → efab1d0 → 7691b3d → ea9b267`, all pushed. `master` untouched at
`ce7db67`, as `HANDOFF.md` reserves.

| Issue | What merged | Commit |
|---|---|---|
| #173 | The FIR-checker gate reaches a checker — first pass in the repo's history | `c74c730` |
| #171 | `game-bridge-mcp conformance` runs all 19 steps — first time since the job existed | `efab1d0` |
| #165 | The 36000-tick nightly, `--update-replay-fixtures`, and a bisect reproduction block | `7691b3d` |
| #170 | A clone builds `moba` and nobody types anything | `ea9b267` |

Worktrees left on disk under `.claude/worktrees/` — `agent-a46c9497c28044fbe` (#173),
`agent-abeb18c3aaaf4e15d` (#171), `agent-a5d07682a843e49fe` (#165), `agent-aae42d941ef837a54` (#170).
Nobody asked for them to go.

---

## The wave in one sentence

**Three CI gates had never once run, and the red that hid them was a fourth.**

#170's art failure made every `moba`-building job red on every push, and three separate gates sat
behind it in a state nobody could see: the FIR-checker probe could not resolve `@Net`, the vendored
client's hashes had never matched their own bytes, and — found only after those merged — a
newly-added Windows test cannot read a CRLF checkout. Each was found by reading `gh run list`, not
by any ticket.

**A closed ticket is not a working feature, and a red CI is a CI nobody reads.** Wave 2 wrote the
first half of that. This wave earned the second.

---

## What CI on `example` looks like now

Before (run `33425479983` at `7942823`) — 10 red, most carrying the same 25 `UDEA0032`.
After (run `33437939749` at `ea9b267`):

**Green, and every one was red before this wave:** `the FIR checkers fail a real build`,
`game-bridge-mcp conformance`, `clean build under budget`, `determinism (ubuntu-latest, temurin)`,
`determinism (ubuntu-latest, corretto)`.

**Green and new:** `replay-equality-nightly` — three legs and the join, on the integration branch
exactly as #165 designed, `36000 tick(s) of 'drift-36000.udearep' are cell-for-cell identical`.

**Still green:** all five `replay-equality` legs and the join, `migration ledger`,
`agent brief matches the tree`, `KSP stays incremental`.

**Still red, each mapping to a filed issue — nothing unexplained:**

| Job | Cause | Issue |
|---|---|---|
| `build (ubuntu-latest)`, `build with the K2 plugin disabled` | `:udea-agent-host:udeaPhase2Exit` over budget | **#175** |
| `build (windows-latest)`, `determinism (windows-latest, x2)` | `AgentsMdTest`, `ExampleScanTest`, `CompilerPluginSwitchTest` on CRLF | **#176** |
| `gl tests (xvfb)` | `OffscreenBackendTest` shutdown flake | **#178** |

`grep -c UDEA0032` is **0** everywhere. That number was 50 on the baseline.

---

## Read this before you dispatch anything

### The staging script is gone. Do not tell anyone to run it

`python3 scripts/stage-moba-art.py` **no longer exists.** `:moba:udeaStageCharacterArt` stages the
33 sheets as part of the build, ahead of `udeaScanAssets`, `udeaValidateAssets` and `udeaPackBundle`.
A fresh clone, a fresh worktree and a detached review checkout all build `:moba` with **nothing
typed**, and `git status` stays clean because the staged art is gitignored.

`.claude/skills/dev-team/SKILL.md`, `.claude/agents/engineer.md` and `.claude/agents/team-lead.md`
were corrected on #170's branch. **A `UDEA0032` about a `spritePath` is now a real defect in whatever
you just changed**, not an environment step you forgot.

That correction was the wave's one review finding, and it was mine: dev-170 spotted all four
documents in round 1, listed them with line numbers, and asked; I said leave them, because at that
moment they were still correct. That reasoning expired when the other three tickets merged, and
`review-170-r1` was right to fail the branch for it. **#177** is filed to make
`verify-art-staging.py` fence `.claude/`, so the next such drift is caught by the build rather than
by a reviewer.

### Two ways to get a confident wrong answer on this box

Both cost real time this wave. Put them in the dispatch.

**1. A backgrounded Bash command starts in the *session* directory, not wherever you last `cd`-ed.**
dev-170b launched a `clean build` with `run_in_background: true` and no `cd`; it ran in
`/srv/ssd1/workspace/Udea` on branch `example` and returned `BUILD SUCCESSFUL` **about the wrong
repository**. Nothing errored. It caught it only because an expected task was missing from the log,
and confirmed it by comparing jar mtimes. Echo `pwd` and `git rev-parse` from the same command line.

That `clean` also deleted `/srv/ssd1/workspace/Udea/build/`, which is **where the screenshot gallery
lives** — `tools/screenshot-gallery.py` serves `build/debug-screenshots/`. Round 1's image was
destroyed and had to be regenerated. Worth moving the gallery out of a directory `gradle clean`
deletes by design.

**2. `grep` on this box is a shell function wrapping ugrep, which skips gitignored directories.**
A working-tree `grep -rn` silently reads nothing under `build/`. Use `git grep`. dev-170b was bitten
by exactly this; `review-170-r2` then found a hit that the developer's own `git grep -- '*.md'` had
filtered out. **Grep the class, not the instances you thought of.**

And one of mine: **do not pipe a build through `| tail`.** The pipeline's exit status becomes
`tail`'s, so a `BUILD FAILED` comes back as exit 0. Redirect to a file and grep it.

### The wall-clock budgets, now with a ticket

`:udea-assets-compiler:udeaDaemonBudget`, `:udea-assets-compiler:udeaPackGate`,
`:udea-core:udeaBenchCharacterMover`, `:udea-core:udeaBenchTickLoop` and
`:udea-agent-host:udeaPhase2Exit` fail under load and pass alone. **Every trial merge this wave hit
between two and four of them, and every one passed solo with 2-4x headroom.** They also fail on
GitHub runners, which is **#175** — a decision, not a number, and `DaemonLatencyBudgetTest`'s KDoc
forbids widening the budget.

The procedure, never once wrong this wave:

```
until awk '{exit !($1 < 9.0)}' /proc/loadavg; do sleep 20; done
sh gradlew <task> --rerun-tasks --no-build-cache
```

**And the trap that makes a green meaningless:** after a budget task passes once, the next plain
`sh gradlew build` reports it `FROM-CACHE` and the whole build goes green. dev-171 nearly wrote that
up as its green build. Use `--rerun-tasks --no-build-cache` for anything you intend to rule on.

### What reviewers did that turned reviews into checks

All four verdicts were reached by breaking something rather than by reading a brief. Keep asking:

- `review-173-r1` **reverted the fix in its own checkout** and reproduced the issue's six error
  lines, confirming the developer's cause and the ticket's error first-hand.
- `review-171-r1` applied **five mutations and got five reds**, including proving the new recorder
  *refuses* to write a CRLF-converted file, and pinned upstream identity out of band against a real
  clone at `ecc9ac5`.
- `review-165-r1` proved `drift-3600.udearep` regenerates byte-identical by **deleting the file and
  rebuilding it**, not by reading `git status`.
- `review-170-r2` **followed the new instructions literally** on a bare checkout with no manual step,
  and grepped without a path filter where the developer's grep had one.

Three developers also corrected their own work before reporting — a KDoc claiming a gate had been
broken "for a year" when it was eight days, a spliced transcript carrying a stale pid, three
transcripts quoting files a later run had overwritten. **That self-review is the cheapest round in
the ticket. Keep demanding it.**

---

## Issues opened this wave

Filed: **#171**, **#172**, **#173**, **#174**, **#175**, **#176**, **#177**, **#178**.
Closed: **#165**, **#170**, **#171**, **#173**.

**Dropped rather than filed**, per the standing instruction: the `vendor-hash.mjs` circularity
between recorder and verifier (pinned out of band; a card at most); `verify-vendor` alone still
passing a hand-reordered manifest (only `test:vendor` catches it, and CI runs `test:vendor`);
regenerating a deleted fixture reporting red on the run that rebuilds it; `clean build under budget`
measuring the runner rather than the tree.

**#178 was dropped on its first occurrence and filed on its second.** That is the right shape: one
flake is noise, two in the only job that makes a surface pass-or-fail is a pattern.

---

## Phase 7, precisely

- **Done: the real Actions run.** Verified on `example` itself, not on a branch — run `33425479983`,
  `replay-equality (join)`: `3 digest stream(s)`, then `replay equality holds: 3600 tick(s) of
  'drift-3600.udearep' are cell-for-cell identical`. And proven able to fail: run `33419266780` with
  `replay_plant_ulp_at=1200` goes red at t1200 naming `Drifter.x`, `0x40d21533` vs `0x40d21532`.
- **Done: the nightly.** #165, green on the integration branch on its first run there.
- **Not done: the gate still replays `DriftWorld`, not `moba`.** That is **#172**, now unblocked —
  #170 merged so CI can build `moba`, and #165's regeneration flag exists.

**#172's scope was corrected this wave and the correction matters.** It originally excluded the
nightly on the grounds that #165 owned it; what #165 delivered is a nightly pointed at `DriftWorld`.
So *pointing the long nightly at the game* was owned by neither issue. #172 now covers both legs.
Found by `review-165-r1`, not by me.

---

## The frozen-contract audit

Run this wave against `AGENTS.md`'s table. **All eight are backed by real code**, not merely declared:

| Contract | Its enforcement |
|---|---|
| Serialization | `udeaCheckProtocolLock`, `expected-generated-hashes.txt` |
| Dirty determination | Capture-and-diff by construction; no setter instrumentation exists |
| Id assignment | `net-protocol.lock`, `ProtocolDescriptor.PROTO_HASH_BITS`, `ServiceLoader` |
| Between-tick mutation | `SimBarrierTest`, `TornWorldRestoreTest` |
| Entity identity | `NetIdIndex`, `EntityDetail.render(NetId)` — Fleks `Entity` appears in `udea-agent` only *below* the tool boundary |
| Time | `DET001`, `DET003` |
| Authority vocabulary | `@Net` parameters plus the K2 checkers |
| Diagnostics | `DiagnosticSink.MAX_DIAGNOSTICS = 25`, `UdeaRuleParityTest` |
| Randomness | `DET002` |

**One gap, one level up: nothing gates `docs/contracts/` itself.** A grep for `docs/contracts` across
every `.kt`, `.kts`, `.yml` and `.py` returns only KDoc prose. Wire ids, module arrows, generated
output, `AGENTS.md`'s own table and migration copies each have a verifier task; the frozen contracts
have a reviewer's attention. That is **#174**.

A structural-skip sweep came back clean: the only tests skipping in the tree are the GL suites (no
`$DISPLAY`, covered by the `gl-tests` job) and the `RealArt*` twins (deliberate, #168). Zero
allocation tests skipped.

---

## Left for the owner

- **Mark `replay-equality` and `the FIR checkers fail a real build` as required status checks.**
  Branch protection; no agent can set it. Both now produce a verdict and both have been seen to fail
  for the right reason. `checkers-fire`'s own comment has demanded this since Phase 0 and it was
  never possible until today.
- **Run `sh gradlew :udea-assets-compiler:udeaPackGate --rerun` once locally.** Carried from wave 2,
  still outstanding: #168's contract derives frame size from the images and asserts every frame is
  100x100. The real-art twins have never been executed anywhere, so a real corpus that is not
  uniform will newly fail **on your machine only**. If it does, relax the uniformity assertion, not
  the corpus.
- **Whether `example` merges into `master`.** Still yours, still untouched.
- **Three orphan `Xvfb` servers** (`:104` at 14h, `:99` at 2.8h, both parentless). Left deliberately:
  this box is shared with `melon-merge` and neither is provably ours.

---

## Pick up next

1. **#172 — point the replay-equality gate at `moba`.** The last Phase 7 clause. Unblocked, scope
   corrected, and it needs both the PR leg and the nightly.
2. **#175 and #176** — between them they are every remaining red job on `example` bar the flake.
   #176 also blocks trusting any cross-platform byte-identity claim, which Phase 7 rests on.
3. **#174** — the frozen contracts have no gate.
4. **#166** — item actives and unique passives. Untouched this wave.
