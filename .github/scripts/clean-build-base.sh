#!/usr/bin/env bash
# Prints the commit the `clean-build-budget` job times HEAD against (issue #218).
#
#   clean-build-base.sh <integration-branch>...
#
# The base is where HEAD leaves the branch it merges into:
#
#   - a pull request names that branch in GITHUB_BASE_REF, and it is the only candidate;
#   - otherwise every argument is a candidate, and the one whose merge base with HEAD is nearest
#     (fewest commits from it to HEAD; the first listed on a tie) is the branch HEAD came from;
#   - where that merge base is HEAD itself - a push to the integration branch, or a scheduled run
#     of its tip - the base is HEAD's first parent.
#
# A candidate that does not exist on origin is skipped, so a branch can be retired or deleted
# without editing the workflow. Any other fetch failure stops the script. If no candidate shares
# history with HEAD there is no honest base, and the script fails rather than pick one.
#
# Must run inside a full-history clone whose remote is `origin`. The chosen base goes to stdout;
# the reasoning goes to stderr, so it lands in the job log.
set -euo pipefail

if [ -n "${GITHUB_BASE_REF:-}" ]; then
  candidates=("$GITHUB_BASE_REF")
else
  candidates=("$@")
fi
if [ "${#candidates[@]}" -eq 0 ]; then
  echo "clean-build-base: no candidate branches given" >&2
  exit 2
fi

head=$(git rev-parse HEAD)
best=""
best_branch=""
best_distance=""

for branch in "${candidates[@]}"; do
  status=0
  git ls-remote --exit-code --heads origin "refs/heads/$branch" > /dev/null || status=$?
  if [ "$status" -eq 2 ]; then
    echo "clean-build-base: origin has no branch '$branch'; skipped" >&2
    continue
  elif [ "$status" -ne 0 ]; then
    echo "clean-build-base: could not list origin's '$branch' (git exit $status)" >&2
    exit "$status"
  fi
  git fetch --quiet --no-tags origin "+refs/heads/$branch:refs/remotes/origin/$branch"

  status=0
  merge_base=$(git merge-base HEAD "refs/remotes/origin/$branch") || status=$?
  if [ "$status" -eq 1 ]; then
    echo "clean-build-base: HEAD shares no history with origin/$branch; skipped" >&2
    continue
  elif [ "$status" -ne 0 ]; then
    echo "clean-build-base: git merge-base failed against origin/$branch (git exit $status)" >&2
    exit "$status"
  fi
  distance=$(git rev-list --count "$merge_base..HEAD")
  echo "clean-build-base: origin/$branch meets HEAD at $merge_base, $distance commit(s) back" >&2
  if [ -z "$best" ] || [ "$distance" -lt "$best_distance" ]; then
    best=$merge_base
    best_branch=$branch
    best_distance=$distance
  fi
done

if [ -z "$best" ]; then
  echo "clean-build-base: no candidate branch (${candidates[*]}) shares history with HEAD $head" >&2
  exit 1
fi

if [ "$best" = "$head" ]; then
  best=$(git rev-parse "HEAD^1")
  echo "clean-build-base: HEAD is on origin/$best_branch; base is its first parent $best" >&2
else
  echo "clean-build-base: HEAD leaves origin/$best_branch at $best" >&2
fi
echo "$best"
