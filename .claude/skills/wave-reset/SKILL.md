---
name: wave-reset
description: Use at the end of every wave of dev-team tickets, whatever its size - one issue or ten - when every branch has merged into `example` and no agent is still running, to clear the lead's context and immediately restart the dev-team loop. Carries the standing goal and a one-or-two-sentence handoff through the clear inside the restart command itself, so the fresh session knows why it is working and what to pick up. Trigger with "wave reset", "clear and continue", "start the next wave", or as soon as the batch that was dispatched in parallel is merged and closed.
---

# Wave reset

**Reset after every wave, whenever it is safe to do so.** Not when something
forces it — a fresh lead each wave is simply how this runs.

**A wave is whatever was dispatched in parallel, and its size does not matter.**
One issue is a wave. Ten issues sent out together are a wave. The wave is over
when that batch — that batch, and nothing dispatched since — has been through
review and merge.

Which means **a wave is closed at dispatch**. When one developer finishes and a
slot frees, the next issue does not go into it; it belongs to the next wave,
after the reset. Refilling a slot the moment it empties is how a wave never
ends, and it is the actual failure this skill exists to prevent: there is never
a moment when nothing is running, so the boundary never arrives.

The lead is the one agent that cannot be replaced mid-ticket — it holds the
ledger, the frozen SHAs and the merge order — so ending waves deliberately is
the only way it ever gets replaced at all. This skill clears it and restarts the
loop in a fresh session, with the goal and a short handoff carried inside the
restart command itself.

The safety conditions below are the whole of the judgement. If they pass, reset;
do not look for an additional reason, and do not wait for a bigger wave to have
happened first.

`/clear` is not reversible and it destroys everything not carried through. Two
things must survive: the **goal** — why the session is working at all — and one
or two sentences saying where the work stands. Both travel in the restart
command. The durable record of what happened is elsewhere and stays there.

What GitHub and the repo already know does not need carrying: merged SHAs are in
`git log`, filed cards are open issues, rulings are issue comments, and what the
tree owes is in `HANDOFF.md`. That is the point of commenting decisions on
issues as the wave runs — the record is durable, and the reset does not have to
reproduce it.

`.claude/WAVE.md` carries the rest: the standing rulings, the traps, the cards
already filed, and what to pick up next. Write it **before** you run the script.

## Never fire mid-wave

Check all five before doing anything. If any fails, finish the wave first —
this skill is for the boundary between waves, never for the middle of a ticket.

1. **No agent is running.** `ListAgents` shows no developer and no reviewer.
   A cleared lead cannot receive a report, and a developer that reports into a
   dead session has done its work for nothing.
2. **Nothing is mid-merge.** No trial worktree under `/tmp/trial-*`, no review
   checkout under `/tmp/review-*`, no conflicted tree, `git status` clean in the
   main repo, and `git worktree list` showing nothing unexpected.
3. **Every passed branch has merged into `example` and been pushed**, or is
   recorded in `.claude/WAVE.md` as deliberately unmerged with its reason.
4. **Every merged ticket is closed** and every out-of-scope note has been
   triaged — filed if it clears the substantial-defect bar, dropped and said so
   in the closing comment if it does not.
5. **No background shell is still running a build.** `sh gradlew build` here is
   minutes; a result that lands in a cleared session lands nowhere.

A branch that passed review but never merged, in a session that then cleared, is
work nobody will find again.

## Compose the two arguments first

**The goal.** The standing objective, copied through verbatim. This is the one
thing a clear destroys that nothing downstream can reconstruct — `git log` says
what was done, never what it was for. A session that has forgotten why it is
working is worse than one that has forgotten what it did, because it will
cheerfully do the wrong next thing.

On this project the goal is usually a phase, because the phases are what the
work is organised around and each has a stated exit criterion. For example:

> Close out Phase 7: bit-exact replay proven on two OS/JVM combinations, with
> the cross-OS replay-equality job actually in CI.

**The prompt.** One or two sentences: where the work stands and what to do
first. Not a ledger.

> If it needs more than two sentences, the wave is not at a boundary. Finish the
> ticket, close the issue, triage the cards, and then reset. The length of the
> handoff is a measurement of whether this skill should be firing at all.

Anything that belongs to a ticket belongs **on the ticket**, before the reset,
not in the restart command:

- a branch that passed review but has not merged — say so in a comment on its
  issue, with the worktree path, the SHA and the verdict;
- a decision made without the owner — comment it with the alternative and how to
  overturn it;
- an out-of-scope note that clears the bar — file it as its own issue.

Do that as the wave runs, not at the boundary. A card filed before the clear is
permanent; a card the lead meant to file is gone.

## Update `.claude/WAVE.md` before you clear

Everything you know that is not written down dies at the clear. `WAVE.md` is
where the next lead looks first, and it must carry:

- **What merged this wave**, with the merge SHA and one line each.
- **Every card filed, by number.** A lead that does not have this list files
  duplicates — that has happened.
- **Branches left on disk**, with their worktree paths and why they are still
  there.
- **Standing rulings** the next lead would otherwise re-litigate.
- **Traps that cost hours rather than minutes** this wave.
- **What to pick up next, in order.**

It is a handoff, not an archive. Trim what `git log`, the open issues and
`HANDOFF.md` already say.

## The reset itself

Do not hand-roll the tmux calls. The order is easy to get backwards, and a
reset that sends `/dev-team` before `/clear` clears away the very command that
was meant to restart the loop. Run the script that ships with this skill:

```bash
.claude/skills/wave-reset/reset.sh \
  --goal "Close out Phase 7: bit-exact replay on two OS/JVM combinations, with the cross-OS replay-equality job actually in CI." \
  --prompt "The lossy-UDP divergence in runUdpProof is still red and understood only as a symptom; pick that up before #152."
```

Both arguments are required. `--delay` (seconds, default 3), `--step-delay`
(seconds between keystrokes and their Enter, default 1), `--command` (default
`/dev-team`) and `--goal-command` (default `/goal`) are optional, and the goal
and prompt may also be passed positionally as `reset.sh "<goal>" "<prompt>"`.

It does exactly five things:

1. Refuses to run unless both arguments are present, `$TMUX_PANE` is set, and
   that pane really exists. A clear with nothing carried through, or with no way
   to send the follow-up, is unrecoverable.
2. Flattens newlines and tabs in both arguments to single spaces. `tmux
   send-keys` ends the line at a newline, so an embedded one would submit half a
   command and leave the rest sitting as a stray prompt.
3. Schedules the restart from a **detached** subshell. The subshell is what
   survives the clear — a second tool call would never run, because `/clear`
   kills the turn that sends it.
4. Types each submission and sends its Enter as a **separate** `send-keys`
   call, `--step-delay` seconds apart. A slash command opens the completion
   menu as it is typed, and an Enter in the same call selects from that menu
   instead of submitting — which looks exactly like the reset not firing.
5. Sends `/clear` to the lead's own pane, last.

What it schedules is **two prompts, not one line**. The loop restarts first,
carrying the handoff; the standing objective follows as its own command:

```
/dev-team WHERE THIS LEFT OFF: <prompt>
/goal <goal>
```

The pane is read from `$TMUX_PANE`, never hardcoded. Three seconds rather than
one: `/clear` tears down and rebuilds the session, and a follow-up that lands
during the teardown is swallowed silently, which looks exactly like the skill
not firing. Raise the delay on a loaded box rather than retrying — and this box
is loaded, because it also runs `melon-merge`'s dev team.

Do not chain `sleep` in the foreground — the harness blocks it. The script's
backgrounded subshell is the supported shape.

## Verify it fired

The lead cannot check its own reset, because it no longer exists. So the FRESH
session checks instead, as its first act:

- Both submissions arrived: the restart carrying a handoff, then `/goal`. If
  either arrived bare, or only one landed, the follow-up was swallowed during
  the teardown — say so rather than guessing at what the wave was doing.
- `ListAgents` is empty, confirming nothing was orphaned.
- The repo is clean, `git worktree list` holds no strays, and
  `git log --oneline -5 origin/example` plus `gh issue list` say what actually
  happened.
- `.claude/WAVE.md` was updated this wave. If its date is older than the last
  merge on `example`, the previous lead cleared without writing it.

**Trust the repo over the prompt.** The handoff was written by a session that is
gone and cannot be asked, and it is one or two sentences standing in for a whole
wave. The log, the open issues, the issue comments and `HANDOFF.md` are the
record; the prompt only says where to start reading.

## What not to do with it

- **Do not clear to escape a problem.** A stuck ticket is still stuck in a fresh
  context, and now nobody remembers what was tried.
- **Do not clear with a question outstanding to the owner.** They will answer
  into a session that has forgotten what it asked.
- **Do not clear while a background shell is running a build.** The result lands
  nowhere. Wait for it, or record in `WAVE.md` that it was abandoned and why.
- **Do not clear with a `/tmp/trial-*` or `/tmp/review-*` worktree still
  registered.** `git worktree list` in a fresh session will show a stray whose
  purpose nobody can reconstruct, and `git worktree remove` on it will look
  risky rather than routine.
