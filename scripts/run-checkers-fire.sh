#!/usr/bin/env bash
#
# Run the `checkers-fire` CI job's own step on this machine.
#
# The job asks the one question no other gate in this repository asks: does a `@Net val`
# written into a real module's real source set actually stop a real `compileKotlin`, with the
# right rule id at the right symbol? For the whole of Phase 0 it had never got far enough to
# find out (issue #173), and a job that has never run is indistinguishable on the Actions page
# from one that is failing for the reason everything else is failing.
#
# The step is **extracted from `.github/workflows/ci.yml` and executed**, not transcribed here.
# A transcription is a second implementation of the gate, and two implementations of a gate
# disagree eventually — which is the same species of defect as the one this script exists to
# make visible. What runs locally is the bytes Actions runs.
#
#   bash scripts/run-checkers-fire.sh
#
# `bash`, not `sh`: the step this extracts is a `shell: bash` step and uses `pipefail` and
# `${var%% *}`, neither of which dash has.
#
# Exit 0 means: the probe failed to compile, every diagnostic it failed with carried a UDEA
# rule id, UDEA0001 and UDEA0003 landed on the two property names at the computed line and
# column, and the same file compiled clean under `-Pudea.compilerPlugin.enabled=false`. The
# step's own `$GITHUB_STEP_SUMMARY` table is printed at the end.
#
# Two things this script does that the Actions runner does for the job, and one it undoes:
#
#   * `JAVA_HOME` is pointed at a JDK 21 if the caller has not set one. Gradle 8.13 does not
#     support the JDK 25 that is first on this box's PATH, and its entire error message is the
#     string `25.0.2`.
#   * `chmod +x ./gradlew`, which the job has a step for, because the wrapper is checked in
#     without the executable bit. The mode is **restored on exit** if it was not set before, so
#     running this does not leave `M gradlew` in `git status` for a reviewer to trip over.
#   * `$GITHUB_STEP_SUMMARY` is pointed at a temporary file rather than left unset, since the
#     step appends its result table to it and `set -u` would otherwise abort at the last line.

set -euo pipefail

repo=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
workflow="$repo/.github/workflows/ci.yml"
job=checkers-fire
step="A broken component fails a real build, and compiles clean without the plugin"

[ -f "$workflow" ] || { echo "not found: $workflow" >&2; exit 2; }

work=$(mktemp -d)
gradlew_was_executable=yes
[ -x "$repo/gradlew" ] || gradlew_was_executable=no
cleanup() {
  rm -rf "$work"
  [ "$gradlew_was_executable" = yes ] || chmod -x "$repo/gradlew" 2>/dev/null || true
}
trap cleanup EXIT

# Extracted by name, from the parsed YAML, so a step renamed or reordered is a loud failure
# here rather than a silently empty script that exits 0.
python3 - "$workflow" "$job" "$step" > "$work/step.sh" <<'PY'
import sys
import yaml

workflow, job, step_name = sys.argv[1], sys.argv[2], sys.argv[3]
with open(workflow) as handle:
    doc = yaml.safe_load(handle)

jobs = doc.get("jobs") or {}
if job not in jobs:
    sys.exit(f"{workflow} has no job named {job!r}; it has {sorted(jobs)}")
steps = jobs[job].get("steps") or []
matches = [s for s in steps if s.get("name") == step_name]
if len(matches) != 1:
    names = [s.get("name") for s in steps]
    sys.exit(f"job {job!r} has {len(matches)} steps named {step_name!r}; it has {names}")
run = matches[0].get("run")
if not run or not run.strip():
    sys.exit(f"step {step_name!r} has no `run:` script")
sys.stdout.write(run)
PY

# Set, not defaulted from the ambient `JAVA_HOME`. This box exports
# `JAVA_HOME=~/.sdkman/candidates/java/current`, which is Temurin 25, and Gradle 8.13's entire
# response to a JDK 25 launcher is the one-line message `25.0.2` — no cause, no mention of
# Java, and an empty diagnostic list that reads exactly like the checkers not firing.
# `UDEA_JAVA_HOME` is the way to point it somewhere else deliberately.
JAVA_HOME=${UDEA_JAVA_HOME:-$HOME/.sdkman/candidates/java/21.0.11-tem}
export JAVA_HOME
[ -x "$JAVA_HOME/bin/java" ] || { echo "no JDK at $JAVA_HOME (set UDEA_JAVA_HOME)" >&2; exit 2; }
export GITHUB_STEP_SUMMARY="$work/summary.md"
: > "$GITHUB_STEP_SUMMARY"
chmod +x "$repo/gradlew"

echo "running the '$step' step of job '$job' from $workflow"
echo "JAVA_HOME=$JAVA_HOME"
echo

status=0
( cd "$repo" && bash "$work/step.sh" ) || status=$?

echo
echo "--- \$GITHUB_STEP_SUMMARY ---"
cat "$GITHUB_STEP_SUMMARY"
echo "--- end ---"
echo "step exit=$status"
exit "$status"
