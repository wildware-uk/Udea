#!/usr/bin/env bash
# Wave reset: stop the lead's current turn, clear its context, then restart the
# loop in the fresh session with the goal and the handoff carried in.
#
# Nothing is written to disk. Everything the next session needs travels in the
# submissions this script sends, so there is no handoff file to go stale, to be
# half-written, or to be forgotten.
#
# It sends ONE key and THREE submissions, in this order, and the order is the
# whole point:
#
#   0. Escape          - stops the agent flow that is running right now.
#   1. /clear          - tears the session down and rebuilds it.
#   2. /goal <goal>    - reinstates the standing objective, into an IDLE session.
#   3. <command> <prompt> - restarts the loop with the handoff. Sent LAST
#                           because it is the one that starts long work.
#
# WHY THE GOAL GOES BEFORE THE RESTART. It used to go after, and the goal was
# silently lost every time. `/dev-team` begins a turn that runs for minutes, so
# a `/goal` typed behind it does not execute as a command at all - the harness
# delivers it to the running turn as a mid-turn user message, where it reads as
# a remark rather than as the standing objective. The session then works with no
# goal set and nobody notices, because a swallowed goal looks exactly like a
# goal that was never passed. Sent into an idle session it executes normally.
#
# WHY THE ESCAPE. The lead calls this script from inside a tool call, so a turn
# is by definition in flight. `/clear` typed under a running turn is queued
# behind it rather than clearing anything. Escape stops the flow first so every
# submission after it lands at an idle prompt.
#
# EVERY send is scheduled from a detached subshell. The Escape kills the turn
# that launched this script, so nothing in the foreground survives to send the
# rest - the subshell is what does.
#
# Usage:
#   reset.sh --goal "<the standing goal>" --prompt "<one or two sentences>"
#   reset.sh "<goal>" "<prompt>"                    # positional, same order
#   reset.sh --goal ... --prompt ... --delay 5      # default delay is 3
#   reset.sh --goal ... --prompt ... --command /foo # default is /dev-team
#
# The GOAL is the thing that must outlive the clear - the standing objective the
# session was pursuing. Copy it through verbatim; a cleared session that has
# forgotten why it is working is worse than one that has forgotten what it did.
#
# The PROMPT is one or two sentences of handoff: where the work stands and what
# to do first. Not a ledger. If it needs more than two sentences, the wave is
# not at a boundary and should finish before it resets.

set -euo pipefail

DELAY=3
STEP_DELAY=1
LEAD_DELAY=2
GOAL=""
PROMPT=""
COMMAND="/dev-team"
GOAL_COMMAND="/goal"
POSITIONAL=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    --goal)    GOAL="${2:-}";    shift 2 ;;
    --prompt)  PROMPT="${2:-}";  shift 2 ;;
    --delay)   DELAY="${2:-}";   shift 2 ;;
    --command) COMMAND="${2:-}"; shift 2 ;;
    --goal-command) GOAL_COMMAND="${2:-}"; shift 2 ;;
    --step-delay)   STEP_DELAY="${2:-}";   shift 2 ;;
    --lead-delay)   LEAD_DELAY="${2:-}";   shift 2 ;;
    --goal=*)    GOAL="${1#*=}";    shift ;;
    --prompt=*)  PROMPT="${1#*=}";  shift ;;
    --delay=*)   DELAY="${1#*=}";   shift ;;
    --command=*) COMMAND="${1#*=}"; shift ;;
    --goal-command=*) GOAL_COMMAND="${1#*=}"; shift ;;
    --step-delay=*)   STEP_DELAY="${1#*=}";   shift ;;
    --lead-delay=*)   LEAD_DELAY="${1#*=}";   shift ;;
    -h|--help)
      sed -n '2,47p' "$0" | sed 's/^# \{0,1\}//'
      exit 0 ;;
    -*)
      echo "reset.sh: unknown option $1" >&2
      exit 2 ;;
    *)
      POSITIONAL+=("$1"); shift ;;
  esac
done

# Positional fallback: reset.sh "<goal>" "<prompt>"
if [[ -z "$GOAL"   && ${#POSITIONAL[@]} -ge 1 ]]; then GOAL="${POSITIONAL[0]}";   fi
if [[ -z "$PROMPT" && ${#POSITIONAL[@]} -ge 2 ]]; then PROMPT="${POSITIONAL[1]}"; fi

if [[ -z "$GOAL" ]]; then
  echo "reset.sh: --goal is required. It is the one thing a clear destroys" >&2
  echo "that nothing downstream can reconstruct. Pass it verbatim." >&2
  exit 2
fi

if [[ -z "$PROMPT" ]]; then
  echo "reset.sh: --prompt is required - one or two sentences saying where the" >&2
  echo "work stands and what to pick up first." >&2
  exit 2
fi

for pair in "delay:$DELAY" "step-delay:$STEP_DELAY" "lead-delay:$LEAD_DELAY"; do
  name="${pair%%:*}"; value="${pair#*:}"
  if ! [[ "$value" =~ ^[0-9]+$ ]]; then
    echo "reset.sh: --$name must be a whole number of seconds, got '$value'." >&2
    exit 2
  fi
done

# tmux send-keys ends the line at a newline, so an embedded one would submit a
# half-written command and leave the rest as a stray prompt. Collapse instead.
flatten() {
  printf '%s' "$1" | tr '\n\r\t' '   ' | sed -e 's/  */ /g' -e 's/^ //' -e 's/ $//'
}

GOAL="$(flatten "$GOAL")"
PROMPT="$(flatten "$PROMPT")"

RESTART="$COMMAND WHERE THIS LEFT OFF: $PROMPT"
GOAL_LINE="$GOAL_COMMAND $GOAL"

PANE="${TMUX_PANE:-}"

if [[ -z "$PANE" ]]; then
  echo "reset.sh: TMUX_PANE is empty - not inside tmux, refusing to clear." >&2
  echo "A clear with no way to send the follow-up loses the session." >&2
  exit 1
fi

if ! tmux list-panes -a -F '#{pane_id}' | grep -qx "$PANE"; then
  echo "reset.sh: pane $PANE not found in tmux. Refusing to clear." >&2
  exit 1
fi

# Every submission is typed, allowed to settle so the slash-command completion
# menu closes, then submitted with its own Enter. An Enter sent in the same
# send-keys call selects from that menu instead of submitting - which looks
# exactly like the reset not firing.
submit() {
  tmux send-keys -t "$PANE" "$1"
  sleep "$STEP_DELAY"
  tmux send-keys -t "$PANE" Enter
}

(
  # Let the tool call that launched this script return before the Escape lands.
  sleep "$LEAD_DELAY"

  # 0. Stop the running flow, so everything after this lands at an idle prompt.
  tmux send-keys -t "$PANE" Escape
  sleep "$STEP_DELAY"

  # 1. Clear. Then wait out the teardown and rebuild: a submission that lands
  #    during it is swallowed silently.
  submit "/clear"
  sleep "$DELAY"

  # 2. The goal, into an idle session, where it executes as a command.
  submit "$GOAL_LINE"
  sleep "$DELAY"

  # 3. The restart, last, because it is the one that runs for minutes.
  submit "$RESTART"
) >/dev/null 2>&1 &
disown

echo "reset.sh: scheduled on $PANE, starting in ${LEAD_DELAY}s:"
echo "  Escape"
echo "  /clear"
echo "  $GOAL_LINE"
echo "  $RESTART"
