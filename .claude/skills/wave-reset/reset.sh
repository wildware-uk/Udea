#!/usr/bin/env bash
# Wave reset: clear the lead's context, then restart the loop in the fresh
# session with the goal and the handoff carried inside the restart command.
#
# Nothing is written to disk. Everything the next session needs travels in the
# command this script sends after the clear, so there is no handoff file to go
# stale, to be half-written, or to be forgotten.
#
# Order matters and is easy to get backwards:
#   1. /clear goes FIRST and immediately. It kills the turn that sends it.
#   2. The restart goes SECOND, from a detached subshell, after the clear has
#      finished tearing down and rebuilding the session.
#
# The subshell is what survives step 1. A second tool call would not.
#
# Usage:
#   reset.sh --goal "<the standing goal>" --prompt "<one or two sentences>"
#   reset.sh "<goal>" "<prompt>"                    # positional, same order
#   reset.sh --goal ... --prompt ... --delay 5      # default delay is 3
#   reset.sh --goal ... --prompt ... --command /foo # default is /dev-team
#
# After the clear the script sends TWO submissions, in this order:
#   1. "<command> <prompt>"  Enter     - restarts the loop with the handoff
#   2. "/goal <goal>"        Enter     - reinstates the standing objective
#
# They are two prompts, not one line. A slash command opens the completion menu
# as it is typed, and an Enter sent in the same send-keys call selects from that
# menu instead of submitting. The text and its Enter go in separate calls, with
# a pause between, so the menu settles first.
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
    --goal=*)    GOAL="${1#*=}";    shift ;;
    --prompt=*)  PROMPT="${1#*=}";  shift ;;
    --delay=*)   DELAY="${1#*=}";   shift ;;
    --command=*) COMMAND="${1#*=}"; shift ;;
    --goal-command=*) GOAL_COMMAND="${1#*=}"; shift ;;
    --step-delay=*)   STEP_DELAY="${1#*=}";   shift ;;
    -h|--help)
      sed -n '2,38p' "$0" | sed 's/^# \{0,1\}//'
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

if ! [[ "$DELAY" =~ ^[0-9]+$ ]]; then
  echo "reset.sh: --delay must be a whole number of seconds, got '$DELAY'." >&2
  exit 2
fi

if ! [[ "$STEP_DELAY" =~ ^[0-9]+$ ]]; then
  echo "reset.sh: --step-delay must be a whole number of seconds, got" >&2
  echo "'$STEP_DELAY'." >&2
  exit 2
fi

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

# Step 2, scheduled before step 1 runs but landing after it. Each submission is
# typed, allowed to settle so the slash-command menu closes, then submitted with
# its own Enter.
(
  sleep "$DELAY"
  tmux send-keys -t "$PANE" "$RESTART"
  sleep "$STEP_DELAY"
  tmux send-keys -t "$PANE" Enter
  sleep "$STEP_DELAY"
  tmux send-keys -t "$PANE" "$GOAL_LINE"
  sleep "$STEP_DELAY"
  tmux send-keys -t "$PANE" Enter
) >/dev/null 2>&1 &
disown

# Step 1.
tmux send-keys -t "$PANE" "/clear" Enter

echo "reset.sh: /clear sent to $PANE; restart follows in ${DELAY}s as:"
echo "  $RESTART"
echo "  $GOAL_LINE"
