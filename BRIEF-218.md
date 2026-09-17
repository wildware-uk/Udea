057144f

# #218 - CI "clean build under budget" measures kmp against the retired example branch

Branch `issue-218-ci-budget-base`, cut from `origin/kmp` at `8c733a4`. (`origin/kmp` has since
moved to `058294b`, a WAVE.md-only commit.)

Every transcript below is spliced from a file in
`build/issue218-evidence/` of this worktree (gitignored). File names are given per block.

## 1. Evidence command

    JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem ANDROID_HOME=$HOME/Android/Sdk \
      sh gradlew :udea-gradle:test --rerun --tests 'dev.wildware.udea.gradle.ci.CleanBuild*'

It runs `CleanBuildBaseScriptTest`, which executes the real `.github/scripts/clean-build-base.sh`
against throwaway git repositories with a real `origin` (a push to the integration branch, a
branch cut from it, a pull request, the post-#214 shapes, an unrelated history), plus
`CleanBuildBudgetJobTest`, which reads `ci.yml` and requires the base checkout step to call that
script. Supporting evidence: the real CI run on this branch (section 5) and the script run
against this repository (section 5, criterion 1).

Green on this branch (`i218-green.log`, and the copied JUnit XML
`TEST-dev.wildware.udea.gradle.ci.CleanBuild*.xml`):

    > Task :udea-gradle:test
    BUILD SUCCESSFUL in 5s

    testsuite name="dev.wildware.udea.gradle.ci.CleanBuildBudgetJobTest" tests="3" skipped="0" failures="0" errors="0"
    testsuite name="dev.wildware.udea.gradle.ci.CleanBuildBaseScriptTest" tests="6" skipped="0" failures="0" errors="0"

**Red with the feature reverted.** The script replaced by the rule `origin/kmp`'s `ci.yml` still
runs (diff `i218-m0-old-rule.diff`; the body is the old step's lines, printing only the base):

    +# OLD RULE, extracted verbatim from ci.yml for the red run of CleanBuildBaseScriptTest.
     set -euo pipefail
    -
    -if [ -n "${GITHUB_BASE_REF:-}" ]; then
    ...
    +git fetch --no-tags origin example
     head=$(git rev-parse HEAD)
    ...
    +base=$(git merge-base HEAD origin/example)
    +if [ "$base" = "$head" ]; then
    +  base=$(git rev-parse HEAD^1)
     fi
    -echo "$best"
    +echo "$base"

`i218-m0-old-rule.log`:

    CleanBuildBaseScriptTest > a branch is timed against its merge base with the integration branch, not its tip or a retired branch() FAILED
    ...
    CleanBuildBaseScriptTest > a candidate that no longer exists on origin is skipped() FAILED
    ...
    CleanBuildBaseScriptTest > the nearest candidate wins, so a branch off master is not timed against a stale kmp listed first() FAILED
    ...
    CleanBuildBaseScriptTest > a push to the integration branch is timed against its first parent() FAILED
    ...
    CleanBuildBaseScriptTest > a history that shares nothing with any candidate fails instead of choosing a base() FAILED
    ...
    9 tests completed, 5 failed

The one that stays green under the old rule is the pull-request case, because in that fixture the
old rule's answer (the retired branch's commit) happens to equal `master`, the PR target. It is
made to fail by mutation 1 below instead.

The same old rule, run on the real repository at `origin/kmp` (`058294b`), exactly as the kmp job
would run it (`i218-old-rule.sh`, output `i218-old-rule-at-kmp.txt`):

    From github.com:wildware-uk/Udea
     * branch            example    -> FETCH_HEAD
    head 058294bca0843dc3b589cc90a9812e631de0ebdf, base 409c0442b41a6bfb71e0f92a03d216d1068870ac
    exit 0

`409c044` is pre-port master, 61 commits back - the defect.

### Mutations

Each is a working-tree edit, run with the evidence command, then reverted. Diffs are the literal
`git diff` from that run (`i218-mN.diff`), failures from `i218-mN.log`.

| # | What it restores | Failing test |
|---|---|---|
| 1 | PRs ignore `GITHUB_BASE_REF` | `a pull request is timed against the branch it targets, which wins over the nearest candidate` |
| 2 | no first-parent step on the integration branch | `a push to the integration branch is timed against its first parent` |
| 3 | first listed candidate instead of nearest | `the nearest candidate wins, so a branch off master is not timed against a stale kmp listed first` |
| 4 | a missing branch on origin is fatal | `a candidate that no longer exists on origin is skipped` |
| 5 | no shared history falls back to HEAD | `a history that shares nothing with any candidate fails instead of choosing a base` |
| 6 | the workflow step goes back to the inline `origin/example` rule | `CleanBuildBudgetJobTest > the base checkout takes its commit from the script CleanBuildBaseScriptTest executes` |

Each run: `9 tests completed, 1 failed`.

Mutation 1:

    -if [ -n "${GITHUB_BASE_REF:-}" ]; then
    +if false; then

Mutation 2:

    -if [ "$best" = "$head" ]; then
    +if false; then

Mutation 3:

    -  if [ -z "$best" ] || [ "$distance" -lt "$best_distance" ]; then
    +  if [ -z "$best" ] ; then

Mutation 4:

    -    continue
    +    exit 2

Mutation 5:

    -  exit 1
    +  best=$head

Mutation 6 (`ci.yml`):

    -          base=$(bash .github/scripts/clean-build-base.sh kmp master)
    +          git fetch --no-tags origin example
    +          base=$(git merge-base HEAD origin/example)
    +          if [ "$base" = "$head" ]; then
    +            base=$(git rev-parse HEAD^1)
    +          fi

Mutation 6 is also the comment control: the YAML comment above that step still names
`.github/scripts/clean-build-base.sh`, and the test went red anyway, because `WorkflowJobs` drops
comment lines before reading steps.

Mutation 5 first did not bite: with a one-commit unrelated history, falling back to HEAD then
failed on `HEAD^1`, so the test passed for the wrong reason. Commit `057144f` gives that fixture a
second commit; the run above is against it.

## 2. Summary

**What changed.** The base-selection lines in the `clean-build-budget` job moved into
`.github/scripts/clean-build-base.sh`, called as `clean-build-base.sh kmp master`. The rule:

- a pull request: `GITHUB_BASE_REF` is the only candidate;
- otherwise each named branch that exists on origin is a candidate, and the one whose merge base
  with HEAD is nearest (fewest commits back; first listed on a tie) wins;
- if that merge base is HEAD itself (a push to `kmp`/`master`, or a scheduled run of the tip),
  the base is `HEAD^1`;
- a candidate missing on origin is skipped; if no candidate shares history, the script fails.

Also: `udea-gradle`'s test task declares the script as an input (otherwise an edit to it leaves
the test UP-TO-DATE), `CleanBuildBudgetJobTest` requires the step to call the script, and the
"Compared against" row of `docs/budgets.md` says the new rule. The 1.1 tolerance is untouched.

**Decisions** (commented on the issue:
https://github.com/wildware-uk/Udea/issues/218#issuecomment-5707322071):

- *A script, not inline YAML*, so the rule can be executed by a test for both the push and the
  branch case - the push case cannot be run on CI from a branch.
- *Nearest of `kmp master`, not `origin/${{ github.base_ref || 'kmp' }}`.* This workflow runs
  mostly on `push`, where `base_ref` is empty, so that form hard-codes `kmp` and needs an edit at
  #214. With the nearest-candidate rule a branch off `master` after #214 finds `master` nearer,
  and a deleted `kmp` is skipped. If the owner disagrees: change the step's argument list.
- *Rejected:* a repository variable naming the integration branch (one more thing to flip at #214).
- *Windows:* `CleanBuildBaseScriptTest` is skipped on Windows (JUnit assumption). The job it
  guards runs only on `ubuntu-latest`, and the `bash` first on a Windows runner's PATH is not
  guaranteed to be Git's. It runs on the Linux `build` leg and on this box.

**Out of scope, noted on the issue:** `replay-equality-nightly` still has
`github.ref == 'refs/heads/example'` in its `if:`, a separate stale reference to the retired
branch. Not touched here - the brief kept this change to the budget job.

## 3. Build

`sh gradlew build --continue` on this branch (`JAVA_HOME` 21.0.11, `ANDROID_HOME` set), from
`i218-build.log`:

    BUILD SUCCESSFUL in 1m 50s
    539 actionable tasks: 360 executed, 154 from cache, 25 up-to-date
    Configuration cache entry stored.
    exit 0

Baseline (per the lead, `origin/kmp` root `build --continue` green at `dc6c708`): nothing red
before, nothing red now. Tasks this ticket turned green: none named (a CI-only defect).

`sh gradlew -p build-logic check --continue` (`i218-build-logic.log`): the only failure is the
baseline one, `OuterBuildInputsTest` (#216):

    OuterBuildInputsTest > every repository file a build-logic test names is a declared input of this task() FAILED
        org.opentest4j.AssertionFailedError at OuterBuildInputsTest.kt:130

    311 tests completed, 1 failed

Its message lists `udea-core/src/...` paths from `DeterminismLayoutTest`, nothing this branch
touches.

No GL, no `udea-render`, no generated files: the xvfb run does not apply.

## 4. Images

None. Nothing on this ticket renders; the evidence is transcripts and the CI run.

## 5. Acceptance criteria

**1. "The job on a `kmp` push compares against its parent on `kmp`, and is green on a commit
that does not touch the build."** - *Proven locally, not on CI*: a branch cannot push `kmp`. The
lead checks the first `kmp` run after merge.

- The test `a push to the integration branch is timed against its first parent` (green; red
  under the old rule and mutation 2).
- The script run on this repository with HEAD detached at `origin/kmp` (`058294b`), output
  `i218-real-kmp-push.txt`:

      clean-build-base: origin/kmp meets HEAD at 058294bca0843dc3b589cc90a9812e631de0ebdf, 0 commit(s) back
      clean-build-base: origin/master meets HEAD at 409c0442b41a6bfb71e0f92a03d216d1068870ac, 61 commit(s) back
      clean-build-base: HEAD is on origin/kmp; base is its first parent 8c733a4b6006ecc3fb5515082284aae6c60ba737
      8c733a4b6006ecc3fb5515082284aae6c60ba737
      exit 0

  Against the old rule at the same commit: base `409c044` (section 1).
- "Green on a commit that does not touch the build": not observable until a kmp run. The branch
  run below is the nearest evidence - its head changes only CI files, a doc, and `udea-gradle`'s
  tests and test-task inputs, and it measured 1.015.

**2. "A branch off `kmp` compares against its merge base with `origin/kmp`."** - Proven on CI.

Run https://github.com/wildware-uk/Udea/actions/runs/35172984773, job
`clean build under budget` (id 105048529494), head `057144f`. From `i218-ci-clean-build.log`
(timestamps and job prefix kept):

    clean build under budget	Check out the base beside the head	2026-09-17T02:05:29.1418310Z clean-build-base: origin/kmp meets HEAD at 8c733a4b6006ecc3fb5515082284aae6c60ba737, 2 commit(s) back
    clean build under budget	Check out the base beside the head	2026-09-17T02:05:29.5293204Z clean-build-base: origin/master meets HEAD at 409c0442b41a6bfb71e0f92a03d216d1068870ac, 62 commit(s) back
    clean build under budget	Check out the base beside the head	2026-09-17T02:05:29.5294240Z clean-build-base: HEAD leaves origin/kmp at 8c733a4b6006ecc3fb5515082284aae6c60ba737
    clean build under budget	Check out the base beside the head	2026-09-17T02:05:29.5314386Z Preparing worktree (detached HEAD 8c733a4)
    clean build under budget	Check out the base beside the head	2026-09-17T02:05:29.6902294Z HEAD is now at 8c733a4 WAVE.md: note the open settings offer to the owner
    clean build under budget	Check out the base beside the head	2026-09-17T02:05:29.6927432Z head 057144f4a8d8511e8f362be5c75d4ce356b48251, base 8c733a4b6006ecc3fb5515082284aae6c60ba737

Also the test `a branch is timed against its merge base with the integration branch, not its tip
or a retired branch`, and the same script on this worktree locally (`i218-real-branch.txt`):

    clean-build-base: origin/kmp meets HEAD at 8c733a4b6006ecc3fb5515082284aae6c60ba737, 2 commit(s) back
    clean-build-base: origin/master meets HEAD at 409c0442b41a6bfb71e0f92a03d216d1068870ac, 62 commit(s) back
    clean-build-base: HEAD leaves origin/kmp at 8c733a4b6006ecc3fb5515082284aae6c60ba737
    8c733a4b6006ecc3fb5515082284aae6c60ba737
    exit 0

**3. "Transcript of the job's base SHA and ratio in the brief."** The base SHA is above. The
samples and verdict, same log:

    clean build under budget	Time clean builds of the base and the head, interleaved	2026-09-17T02:12:06.7777693Z first clean build of the head, daemons cold: 174758 ms
    clean build under budget	Time clean builds of the base and the head, interleaved	2026-09-17T02:20:42.7738334Z base 60313
    clean build under budget	Time clean builds of the base and the head, interleaved	2026-09-17T02:20:42.7740355Z head 53315
    clean build under budget	Time clean builds of the base and the head, interleaved	2026-09-17T02:20:42.7740827Z head 47217
    clean build under budget	Time clean builds of the base and the head, interleaved	2026-09-17T02:20:42.7741258Z base 42703
    clean build under budget	Time clean builds of the base and the head, interleaved	2026-09-17T02:20:42.7741686Z base 40731
    clean build under budget	Time clean builds of the base and the head, interleaved	2026-09-17T02:20:42.7742100Z head 40104
    clean build under budget	Time clean builds of the base and the head, interleaved	2026-09-17T02:20:42.7742524Z head 39140
    clean build under budget	Time clean builds of the base and the head, interleaved	2026-09-17T02:20:42.7742945Z base 39150
    clean build under budget	Time clean builds of the base and the head, interleaved	2026-09-17T02:20:42.7743337Z base 39048
    clean build under budget	Time clean builds of the base and the head, interleaved	2026-09-17T02:20:42.7747403Z head 38449
    clean build under budget	Time clean builds of the base and the head, interleaved	2026-09-17T02:20:42.7747877Z head 38168
    clean build under budget	Time clean builds of the base and the head, interleaved	2026-09-17T02:20:42.7748295Z base 37603
    ...
    clean build under budget	Judge the head against its base	2026-09-17T02:20:44.5077568Z | Base, fastest sample | 37603 ms |
    clean build under budget	Judge the head against its base	2026-09-17T02:20:44.5078311Z | Head, fastest sample | 38168 ms |
    clean build under budget	Judge the head against its base	2026-09-17T02:20:44.5079251Z | Head / base | 1.015 |
    clean build under budget	Judge the head against its base	2026-09-17T02:20:44.5080579Z | Verdict | within tolerance |

The job concluded `success`. For contrast, the issue's numbers against `example`: kmp 1.116, #206
1.242.

### The rest of that CI run

The run as a whole is `failure`. None of the failed jobs is this change, as far as the evidence
goes:

- `migration ledger`: fails in `build-logic`'s `OuterBuildInputsTest` (`i218-ci-ledger.log`), the
  #216 baseline; also red on `kmp` run 35172658202 (`058294b`).
- `determinism` (all four legs): also red on `kmp` run 35172658202.
- `build (windows-latest)`: failed in `:moba:udeaValidateAssets` with
  `error UDEA0021 moba/assets/item/trinkets.udea.kts:0:0 Cannot invoke "java.util.jar.Manifest.getMainAttributes()" because the return value of "java.util.jar.JarInputStream.getManifest()" is null`
  (`i218-ci-windows-build.log`). The same leg passed on `kmp` run 35172658202, and this branch
  touches nothing in `moba` or the asset pipeline. Re-run of that job on the same commit (job id 105051983114): `completed success`. So it was a
  flake, not this change.

## 6. Regenerated files

None. `net-protocol.lock` and `expected-generated-hashes.txt` are untouched.
