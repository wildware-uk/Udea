# Wave handoff — 2026-09-03, wave 7

**Three tickets dispatched, three merged, every one at review round 1 with zero findings.**

`example` went `60a9471 → fd10c87 → 4f075c4 → e3a341f → 81727f8`, all pushed. `master` untouched
at `ce7db67`, as `HANDOFF.md` reserves.

| Issue | What merged | Commit |
|---|---|---|
| #160 | The overlay's allocation-free claim is measured, and its blind spot with it | `fd10c87` |
| — | Archive every brief under its own number (lead, see below) | `4f075c4` |
| #178 | A stopped render loop says so, without waiting for its thread object to die | `e3a341f` |
| #166 | Item actives on a shared bar, and unique passives that do not stack | `81727f8` |

Worktrees left on disk under `.claude/worktrees/`, none removed:
`agent-a5b65b93c1b5ec78c` (#160), `agent-a10b02ebe1b965e3b` (#178), `agent-a5e39bad901aec41f` (#166).

**This wave was a deliberate pivot to features.** Waves 4–6 were six consecutive infrastructure
tickets. #166 is game content; #160 closed the last unproved criterion on the agent overlay epic;
#178 was the one bug worth taking, because it sat on the only job that makes the GL surface
pass-or-fail.

---

## The one sentence

**Three tickets, three different ways a claim can be true in prose and unmeasured in fact — and in
all three the developer found it against its own work before any reviewer did.**

| The claim | What was actually under it |
|---|---|
| "The overlay is allocation-free" | Three KDocs asserting it; `AgentOverlayModel` even named `OverlayAllocationTest`, a file never written (#160) |
| "`OffscreenBackendTest` is flaky under load" | Not a slow shutdown at all — a **wrong exception type**, which no timeout could have fixed (#178) |
| "Put the cooldown group on the `AbilityDef`" | Would have cooled the priest's own heal down with `item/aegis`, silently passing nothing (#166) |

---

## Read this before you dispatch anything

### The GL evidence clobber, measured twice this wave

**A plain `sh gradlew build` re-runs `udeaGlTest`/`udeaAgentGlTest` with no DISPLAY and OVERWRITES
the xvfb result XMLs with skipped ones.** `dev-160` measured `udeaAgentGlTest` going from
`tests=8 skipped=0` to `tests=8 skipped=8` in the same file; with no `DISPLAY`, 26 of 27 GL tests
come back skipped.

So on any GL-touching ticket, tell the developer **and the reviewer**:

- Sequence the plain build FIRST and the xvfb run LAST, or copy the XMLs out at capture time.
- `--rerun-tasks` is not optional. `dev-166`'s first xvfb attempt came back `FROM-CACHE`, and it
  said plainly that is not evidence.
- A reviewer that runs the build and then finds skips will wrongly conclude the developer never ran
  the GL tests. Warn it explicitly.

### An allocation probe has a blind spot, and it is JIT-state dependent

Measured on #160, and it applies to any allocation gate anyone writes here. Sensitivity to a
**non-escaping** allocation is test-ORDER dependent within one class: the same mutation reads
**4800 bytes for 1 marker and 38400 for 8 run alone**, and **zero** run after the rest of the class,
once C2 has inlined enough to scalar-replace. `udea-render`'s `RenderAllocationTest` documents the
same blindness independently.

**Absolute-zero assertions are order-independent for an escaping allocation. Comparative ones
("1 marker vs 8, same bytes") are not** — one was passing as `0 == 0` and was deleted rather than
pinned to a method order.

### Evidence under `build/` is not safe

`dev-166` lost its evidence directory to `git clean` mid-run because it sat under `build/`.
`dev-178` put its 90 artefacts in the **main checkout** at `build/issue178-evidence/`, where they
outlive the worktree. Tell developers to do the latter.

### Rename detection will eat a brief if you let it

When merging, watch `BRIEF-*.md`. Git detected `BRIEF.md → BRIEF-172.md` from `4f075c4` as a rename
and **cleanly applied #178's brief onto `BRIEF-172.md`** — a merge with no conflict that silently
destroyed the wrong file. Caught in the trial worktree. The root `BRIEF.md` is now **removed**, so
this cannot recur, and every merged ticket has a `BRIEF-<N>.md`. A developer still writes
`BRIEF.md` in its own worktree; archive it under its number at merge time.

---

## What is now true that was not

- **The agent activity overlay epic (#155) has no unproved criterion left.** `OverlayAllocationTest`
  measures three absolute zeros over busy, empty and post-expiry frames, guarded so a zero cannot be
  reported for an overlay that quietly stopped drawing (the reviewer proved that guard itself:
  expected 352032, was 800).
- **`gl tests (xvfb)` is deterministic.** `isRunning` reads the loop's own monotone exit signal
  (`finished.count > 0L`) rather than `thread.isAlive`. Reverted to pre-fix code, the new
  `GlThreadShutdownTest` is red **4/4** where the old assertion was red **0/4**.
- **`moba` has item actives and unique passives**, on a cooldown group that is a property of the
  **slot**, not the `AbilityDef`. `udea-gas` gained `CooldownGroup`/`CooldownSharing` and **no
  replicated component**, so neither lock file moved.
- **Blocked-while-dead is a GAS tag**, so a corpse can no longer cast through a key press or an RPC.
  Wider than #166 asked; reviewed as a correct implementation because no existing test's
  expectations were rewritten to accept it. **One line to revert** if the owner disagrees.

---

## Still open, and not this wave's to answer

- **#179 — the Phase 7 checkpoint. One word: continue, stop, or re-plan**, plus the entry in
  `docs/decisions/phase-log.md`. Still deliberately blank; spec section 7's mitigation is that *a
  person* says it out loud. **That file still has no entries through seven phases.**
- **Mark `replay-equality`, `latency budgets` and `the FIR checkers fail a real build` as required
  status checks.** Branch protection; no agent can set it.
- **Run `sh gradlew :udea-assets-compiler:udeaPackGate --rerun` once locally.** Carried from wave 2.
- **Whether `example` merges into `master`.** Still the owner's, still untouched.

## Backlog notes

`#147`–`#151` are still open and still describe work that shipped at `8035374`. `#160` was open and
five of its six criteria had shipped — the triage comment on it is the model: grep the tree, find
what is really missing, redirect the developer to that and say so in the dispatch.

**The dashboard MCP is configured now.** All three developers and all three reviewers this wave
reported `mcp__agent-dashboard__*` absent, so nothing reached the owner's wall for the whole run.
`~/.claude-second/.claude.json` now carries both servers, token verified against the live server on
`127.0.0.1:8010`. **It needs a session restart to take effect** — MCP servers connect at startup.
Tell agents to use `post_update` and `heartbeat`; `request_input`/`await_request` block on a human
and nobody is watching.
