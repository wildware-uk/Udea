# Wave handoff — 2026-09-16, wave 8

**Four tickets dispatched, four merged.** `example` went `f08db40 → f096b29 → 5e37c99 → ec628e5 → 382e53f`,
all pushed. `master` untouched.

| Issue | What merged | Commit | Rounds |
|---|---|---|---|
| #177 | `verify-art-staging.py` step 8: agent docs cannot tell a reader to run a missing script | `f096b29` | 1 |
| #184 | Warm-edit gate fails at 1500 ms; 3000 ms stays spec 6's product contract | `5e37c99` | 1 |
| #191 | Levels save/load as kotlinx CBOR over Fleks `world.snapshot()`; `:moba:runLevelShot` | `ec628e5` | 2 (one item-2 finding: publics made internal) |
| #181 | Clean-build CI gate compares commit vs base on the same runner, 1.10x | `382e53f` | 1 |

Worktrees left on disk under `.claude/worktrees/`: `agent-a25c22d91af3595d0` (#177),
`agent-a3ca34cdec2180138` (#184), `agent-a2f9401b541e763b3` (#191), `agent-a44316c8736a5a6d5` (#181).

## Cards filed this wave

- **#197** — `latency budgets (windows-latest)` red on `example`, a different gate each run
  (`udeaBenchCharacterMover`, then `udeaPhase2Exit`). Pre-existing; tell developers it is not theirs.
- **#198** — a loaded or rewound world drifts ~413 ticks later: Fleks does not restore recycled-id
  order. Matters for #196 (Play/Stop) and rewind.

## Owner, on the dashboard, this wave

**"I want more screenshots!"** Every developer prompt now says: before/during/after shots to
`build/debug-screenshots/` AND uploaded to the dashboard (project `udea`). Favour tickets with
something to see. Dashboard MCP tools work for the lead now.

## Traps this wave

- **Background agents end their turn to "wait" for CI or a build and are not woken.** Watch the
  thing yourself (background `until` loop) and `SendMessage` them when it lands, and tell them in
  the dispatch not to end a turn to wait. A `pgrep -f <worktree path>` wait loop matched shell
  wrappers and never exited — poll `gh run view` status instead.
- Developers now name the brief `BRIEF-<N>.md` themselves; merge needs no rename then. If a branch
  still has `BRIEF.md`, `git mv` it in the merge commit (done for #177).
- The dev-team skill's contract block still says Kotlin 2.2.10 / JDK 17 in its first line; correct it
  in the prompt (lead copy in scratchpad was patched per wave).
- A reviewer's `SendMessage "main"` can fail and it falls back to `team-lead`; the message still arrives.

## Next, in order (triage done 2026-09-16 at f08db40; most old tickets are PARTIAL, not done)

1. **Visible first** (owner wants pictures): **#188** HUD onto ComposeGL (then #189 ban scene2d),
   **#192** test_level to binary + loop ban (needs #191, done), **#193** `editor.*` tools (needs #191).
   #188 and #192 both touch `moba` — #188 owns `MobaHud.kt`, #192 owns `moba/assets/level` + asset
   compiler; disjoint enough. #193 owns `udea-agent`.
2. Then #194 (needs #193), #195/#196 (need #194), #198, #197.
3. #183 (inputs on udea-annotations/udea-codegen tests) — do not run beside a codegen ticket.
4. Old epic children, all PARTIAL: #103, #141→#142→#145, #143→#144, #147–#151 remnants, #123, #125,
   #127–#135 moba chain, #140, #50, #53, #90, #91, #93.
5. Owner-only parts: #179's one word, #144 Pages, #151 CODEOWNERS reviewers. Epics close when children do.
