4266549

(The code under review is `4266549`. The commit on top of it adds this file and nothing else;
`git diff 4266549 HEAD --stat` shows one file, `BRIEF.md`.)

# BRIEF — #177: fence the agent documents against an instruction to run a deleted script

Branch `issue-177-fence-claude-docs`, off `origin/example` at `f08db40`. Every artefact quoted
below is a file under `/srv/ssd1/workspace/Udea/build/issue177-evidence/`; blocks are spliced from
those files, with `...` marking a cut.

## 1. Evidence command

    python3 -m unittest -v scripts/test_verify_art_staging.py

19 tests, about half a second, no Gradle. Green at `4266549` (`green-unittest-4266549.txt`):

    Ran 19 tests in 0.526s

    OK

**It goes red when the feature is reverted.** Put `origin/example`'s script back under the new
tests (`git checkout origin/example -- scripts/verify-art-staging.py`), run, restore
(`revert-run-origin-example-script.txt`):

    Ran 19 tests in 0.358s

    FAILED (errors=24)
    exit=1

Those are `AttributeError`s: the old script has no fence to call. The more useful red is the
**scope reverted to what step 7 had** — README only — which leaves the function in place and
fails on behaviour. See mutation 1 in section 3.

The end-to-end script, which is what the issue's acceptance names, is in section 6.

## 2. Summary

`scripts/verify-art-staging.py` gains **step 8**: no committed document an agent reads may tell
its reader to run a script `HEAD` does not have. It fails naming every `file:line`, and it runs
**before** the Gradle steps, so a stale instruction fails in seconds.

**Scope (`AGENT_DOCUMENTS`).** Every committed file, any extension, under `.claude/` and `docs/`,
plus `README.md`, `AGENTS.md`, `CLAUDE.md`, `HANDOFF.md`. Listed with `git ls-tree -r HEAD`, found
with `git grep -I ... HEAD --`, read with `git show HEAD:<path>`. Nothing goes through a shell, so
the `grep` shell function (ugrep) is never involved, a gitignored directory cannot hide a tracked
file, and the untracked `.claude/worktrees/` is out of scope by construction.

**The imperative-vs-history rule** (`instructions_to_run_a_missing_script`). A mention of a
`.py`/`.sh` path counts only when both hold:

1. *It is run, not named*: an interpreter right before it (`python`, `python3`, `sh`, `bash`,
   `zsh`, `source`, `exec`), a verb (`run`, `execute`, `invoke`), a `./` prefix, or it starts a
   command (start of line, after `$ `, `&&`, `||`, `;`).
2. *The script is missing and nothing records that*: a command in a code block (fenced, indented,
   or any non-markdown file) is never excused. A mention in a prose paragraph is history when the
   paragraph says the script was `deleted`, `removed` or `no longer exists`.

Paragraph, not sentence, because the #170 record in `.claude/WAVE.md` at `e69b01d` said "ran
`python3 scripts/stage-moba-art.py` by hand ... That script is deleted." across two sentences.

**Known limit, stated in the docstring:** it cannot read English. A prose paragraph that says
"run `scripts/x.py`" *and* says "deleted" about something else is excused. And a present-tense
claim that is not an instruction ("`scripts/x.py` fixes it", `.claude/WAVE.md:28` at `05ab99d`)
is not caught: the issue is about imperatives.

**Decisions, each commented on the issue**
([rule](https://github.com/wildware-uk/Udea/issues/177#issuecomment-5699494292),
[scope and placement](https://github.com/wildware-uk/Udea/issues/177#issuecomment-5699497751)):

- *Wider scope than ".claude/ plus README"*: `AGENTS.md` and `CLAUDE.md` are loaded into every
  agent, and `HANDOFF.md`/`docs/` are what the brief tells an agent to read. Rejected: every
  tracked file. The rule over the whole repository at `origin/example` flags only quoted commands
  in `BRIEF-154.md` and `BRIEF-170.md` (`wide-scan-origin-example.txt`), and at `9333087` also
  the fence's own test fixtures (`wide-scan-9333087.txt`), so whole-repo needs an exclusion list
  that grows.
- *A new step 8, not a rewrite of step 7*: step 7 checks that README's licence section names the
  same script as the documented step — a different claim — and stays.
- *Rejected rules*: fail on any mention (deletes history); a marker comment on history lines
  (every existing record needs an edit to pass); sentence-level scope (misses the WAVE.md record).

**The issue text, checked against the tree.** (1) There is no `NO_STAGING_SCRIPT` in
`scripts/verify-art-staging.py` on `origin/example`; the name appears only in `BRIEF-170.md`. (2)
"Seven" past-tense mentions: on `origin/example`, outside `BRIEF-*`, `git grep -n stage-moba-art`
returns `.claude/agents/team-lead.md:244`, `docs/art-assets.md:33`, `:227`, `:231` and
`moba/build.gradle.kts:158`. The two `.claude/WAVE.md` lines `BRIEF-170` counted were rewritten by
later handoffs (`28bfcde` and others on `e69b01d..origin/example -- .claude/WAVE.md`).
`test_every_committed_record_of_the_deleted_script_reads_as_history` holds the rule to every
committed file that mentions the script, wherever it sits, except the briefs and the fence itself.

**Checked against real history.** `wide-scan.py` runs the rule, unscoped, over every committed
file at a ref. At `05ab99d` (#170 before its round-2 fix), `wide-scan-05ab99d.txt`:

    .claude/WAVE.md:48: scripts/stage-moba-art.py
    .claude/agents/engineer.md:58: scripts/stage-moba-art.py
    .claude/agents/team-lead.md:242: scripts/stage-moba-art.py
    .claude/skills/dev-team/SKILL.md:339: scripts/stage-moba-art.py
    BRIEF-170.md:570: scripts/stage-moba-art.py
    BRIEF.md:55: scripts/stage-moba-art.py
    BRIEF.md:119: scripts/stage-moba-art.py
    BRIEF.md:276: scripts/stage-moba-art.py

Outside the briefs those are the four lines `BRIEF-170.md:748-754` lists as the round-2 finding.
At `e69b01d` (after the fix), `wide-scan-e69b01d.txt` has only `BRIEF` lines.

**Not touched:** no Kotlin, no Gradle, no `docs/contracts/`, no `AGENTS.md`, no CI. Nothing in
`net-protocol.lock` or `expected-generated-hashes.txt`. The tests are not wired into `sh gradlew
build`: there is no Python test task in the build today, and adding one is build logic outside
this ticket (#181 owns `ci.yml`).

## 3. Mutations

Each diff is the literal `git diff` saved at the time, against blob `761ce8d` of
`scripts/verify-art-staging.py` (the script at `ca819cb` and `9333087`; `4266549` changed only
the failure message and one comment). Rows 1–6 ran on the 17-test suite at `ca819cb`; rows 7–13
on the 18- and 19-test suite after two tests were added. Failure counts include subtests.

**1. Scope back to README only** — `mutation-1-readme-only.diff` / `.txt`, `FAILED (failures=13)`

    -AGENT_DOCUMENTS = (".claude", "README.md", "AGENTS.md", "CLAUDE.md", "HANDOFF.md", "docs")
    +AGENT_DOCUMENTS = ("README.md",)

Red: `test_a_file_under_claude_that_is_not_markdown_is_fenced`,
`test_a_gitignored_directory_cannot_hide_a_tracked_file`,
`test_every_offending_line_is_named_not_only_the_first`,
`test_history_in_one_paragraph_does_not_excuse_a_command_in_the_next`,
`test_prose_around_a_code_block_does_not_excuse_the_command_in_it`,
`test_the_agent_briefs_at_the_root_are_fenced` ×4 (AGENTS.md, CLAUDE.md, HANDOFF.md,
docs/art-assets.md), `test_the_engineer_code_block_from_before_170_fails`,
`test_the_shells_grep_is_not_what_finds_the_hit`,
`test_the_team_lead_imperative_from_before_170_fails`,
`test_the_wave_fenced_command_from_before_170_fails`.

**2. Markdown only (the filter that hid `moba/build.gradle.kts:158`)** — `FAILED (failures=1)`

    -    candidates = [hit.split(":", 1)[1] for hit in filter(None, out.decode("utf-8").split("\0"))]
    +    candidates = [hit.split(":", 1)[1] for hit in filter(None, out.decode("utf-8").split("\0")) if hit.endswith(".md")]

Red: `test_a_file_under_claude_that_is_not_markdown_is_fenced`.

**3. Working-tree `grep` from `PATH`** — `FAILED (failures=1, errors=1)`

    -    code, out, err = git_out(
    -        ["grep", "-l", "-z", "-I", "-E", "-e", r"\.(py|sh)", "HEAD", "--", *AGENT_DOCUMENTS], clean
    +    proc = subprocess.run(
    +        ["grep", "-rlIZsE", r"\.(py|sh)", *AGENT_DOCUMENTS], cwd=clean, stdout=subprocess.PIPE, stderr=subprocess.PIPE
         )
    -    if code not in (0, 1):
    +    code, out, err = proc.returncode, proc.stdout, proc.stderr.decode()
    +    if code not in (0, 1, 2):
             raise Failure(f"git grep failed reading the agent documents: {err.strip()}")
    -    candidates = [hit.split(":", 1)[1] for hit in filter(None, out.decode("utf-8").split("\0"))]
    +    candidates = [hit for hit in filter(None, out.decode("utf-8").split("\0"))]

Red: `test_the_shells_grep_is_not_what_finds_the_hit` (FAIL),
`test_an_untracked_file_under_claude_is_out_of_scope` (ERROR).

**4. A search that honours `.gitignore` (the ugrep shape)** — `FAILED (failures=1, errors=1)`

    -        ["grep", "-l", "-z", "-I", "-E", "-e", r"\.(py|sh)", "HEAD", "--", *AGENT_DOCUMENTS], clean
    +        ["grep", "--no-index", "--exclude-standard", "-l", "-z", "-I", "-E", "-e", r"\.(py|sh)", "--", *AGENT_DOCUMENTS], clean
    ...
    -    candidates = [hit.split(":", 1)[1] for hit in filter(None, out.decode("utf-8").split("\0"))]
    +    candidates = [hit for hit in filter(None, out.decode("utf-8").split("\0"))]

Red: `test_a_gitignored_directory_cannot_hide_a_tracked_file` (FAIL),
`test_an_untracked_file_under_claude_is_out_of_scope` (ERROR).

Rows 3 and 4 together: GNU `grep` from `PATH` reads ignored directories, so it trips the stub test
and not the ignore test; `git grep --exclude-standard` is the opposite. Each shape is caught by a
different test.

**5. No history exemption** — `FAILED (failures=1, errors=2)`

    -        excused = kind == "prose" and SCRIPT_IS_GONE.search(" ".join(line for _, line in unit))
    +        excused = False

Red: `test_the_history_that_replaced_each_imperative_passes`, `test_this_repository_head_passes`,
`test_every_committed_record_of_the_deleted_script_reads_as_history` (name='.claude/agents/team-lead.md').

**6. Exemption document-wide instead of per paragraph** — `FAILED (failures=2)`

    -        excused = kind == "prose" and SCRIPT_IS_GONE.search(" ".join(line for _, line in unit))
    +        excused = SCRIPT_IS_GONE.search(text)

Red: `test_history_in_one_paragraph_does_not_excuse_a_command_in_the_next`,
`test_prose_around_a_code_block_does_not_excuse_the_command_in_it`.

**7. Code units excusable too** — `FAILED (failures=1)`

    -        excused = kind == "prose" and SCRIPT_IS_GONE.search(" ".join(line for _, line in unit))
    +        excused = SCRIPT_IS_GONE.search(" ".join(line for _, line in unit))

Red: `test_a_file_that_is_not_markdown_is_never_excused_as_history`.

**8. Every mention counts as run** — `FAILED (failures=2, errors=2)`

    -                run_here = (
    +                run_here = True or (

Red: `test_the_history_that_replaced_each_imperative_passes`, `test_this_repository_head_passes`,
`test_every_committed_record_of_the_deleted_script_reads_as_history` (name='docs/art-assets.md'),
(name='moba/build.gradle.kts').

**9. No existence check** — `FAILED (errors=2)`

    -                if script in tracked:
    +                if False and script in tracked:

Red: `test_a_script_the_checkout_has_may_be_run`, `test_this_repository_head_passes`.

**10. Only the first offence reported** — `FAILED (failures=1)`

    -            + "\n  ".join(offences)
    +            + "\n  ".join(offences[:1])

Red: `test_every_offending_line_is_named_not_only_the_first`.

**11. No command-start detection** — `FAILED (failures=1)`

    -                    or INVOKED_AS_COMMAND.search(before)
    +                    or False

Red: `test_a_path_that_starts_a_command_is_run`.

**12. No run-verb detection** — `FAILED (failures=1)`

    -                    or INVOKED_BY_VERB.search(before)
    +                    or False

Red: `test_the_readme_is_still_fenced`.

**13. No interpreter detection** — `FAILED (failures=13)`

    -                    or INVOKED_BY_INTERPRETER.search(before)
    +                    or False

Red: the same 13 as row 1 except `test_a_file_under_claude_that_is_not_markdown_is_fenced`, plus
`test_a_file_that_is_not_markdown_is_never_excused_as_history`
(`mutation-13-no-interpreter.txt` lists them).

**The control.** A mention that names the script without running it must stay green. Unmutated,
`test_the_history_that_replaced_each_imperative_passes` (which includes `git log --
scripts/stage-moba-art.py` in a code block and the KDoc naming excerpt) and the records test on
`docs/art-assets.md` and `moba/build.gradle.kts` all pass. Mutation 8, which treats every mention
as run, is what turns the records test red on exactly those two files.

## 4. `sh gradlew build`

    JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem sh gradlew build --console=plain

Run at `4266549` on a shared box (other developers' Gradle builds and a melon-merge xvfb run were
live; the lead said not to wait for quiet). `gradle-build-4266549.txt`:

    BUILD SUCCESSFUL in 1m 51s
    213 actionable tasks: 137 executed, 76 from cache
    Configuration cache entry stored.
    exit=0

Test totals summed from every `build/test-results/**/TEST-*.xml` on disk after that build
(`gradle-build-4266549-test-counts.txt`), which includes results restored from the build cache:

    tests 2597 failures 0 errors 0 skipped 37

No GL run under xvfb: the change is two Python files under `scripts/` and opens no context.

## 5. Images

None. Nothing in this ticket draws; the evidence is transcripts.

## 6. The issue, criterion by criterion

**Criterion 1 — re-introducing an imperative under `.claude/` makes the script exit non-zero,
naming file and line.** A temporary commit put the pre-#170 line back into
`.claude/agents/engineer.md` (`red-run-reintroduced-imperative.diff`):

    @@ -49,6 +49,8 @@ late contract change breaks several modules at once and the breakage is silent.
     
     ## There is no art step, and you type nothing
     
    +    python3 scripts/stage-moba-art.py
    +
     **Your worktree does not have the sprites, and it does not need them.**
    ...

The real script, on that commit (`red-run-verify-art-staging.txt`):

    repository: /srv/ssd1/workspace/Udea/.claude/worktrees/agent-a25c22d91af3595d0
    verifying commit: eb0de2a (a fresh checkout of HEAD, not the working tree)
    clean tree: /tmp/udea-art-verify-qwzlifst/clean
    documented step, from docs/art-assets.md:
        ./gradlew :moba:build

    [8/8] no agent document may tell its reader to run a missing script (run first: no build)

    FAILED: 1 instruction(s) to run a script this checkout does not have:
      .claude/agents/engineer.md:52: scripts/stage-moba-art.py
    An agent follows these literally. Delete the instruction, or, if the line is a record of what used to be run, write it in prose - not a code block - and say in the same paragraph that the script was deleted.
    exit=1

The temporary commits were then dropped with `git reset --hard 4266549`; they are not on the
branch. Also in the unit tests: the team-lead, engineer and WAVE imperatives from `05ab99d`, a
non-markdown file, a bare command path, and every-offence reporting.

**Criterion 2 — the existing past-tense mentions still pass.** The full script on `4266549`, all
eight steps (`green-run-verify-art-staging-4266549.txt`):

    verifying commit: 4266549 (a fresh checkout of HEAD, not the working tree)
    ...
    [8/8] no agent document may tell its reader to run a missing script (run first: no build)
      29 committed agent document(s) read, 6 naming a script; no instruction to run one that is missing

    [1/8] negative control: :moba:udeaValidateAssets must FAIL with -x udeaStageCharacterArt
      FAILED as required, 25 x UDEA0032
    ...
    [7/8] README.md must not name a staging script
      README.md names no script, consistent with docs/art-assets.md

    OK: a fresh clone builds :moba with no manual step, and the licence covers the art.
    exit=0

Step 8 reads `.claude/agents/team-lead.md:244` and `docs/art-assets.md:33/227/231`.
`moba/build.gradle.kts:158` is outside the scope, so
`test_every_committed_record_of_the_deleted_script_reads_as_history` runs the rule over it
directly; mutation 8 shows that test going red on it.

**Criterion 3 — no dependence on the shell's `grep`; a gitignored directory cannot hide a hit.**

- `test_the_shells_grep_is_not_what_finds_the_hit` puts a `grep` that finds nothing first on
  `PATH`; the fence still fails. Mutation 3 turns it red.
- `test_a_gitignored_directory_cannot_hide_a_tracked_file` gitignores `.claude/agents/` and
  force-adds the file; the fence still fails. Mutation 4 turns it red.
- End to end: a second temporary commit on top of the red one added `.claude/` to `.gitignore`.
  The real script on it (`red-run-with-claude-gitignored.txt`):

      verifying commit: db939db (a fresh checkout of HEAD, not the working tree)
      ...
      FAILED: 1 instruction(s) to run a script this checkout does not have:
        .claude/agents/engineer.md:52: scripts/stage-moba-art.py
      ...
      exit=1

What that does not show: I did not reproduce ugrep itself skipping the directory. A shell-`grep`
run with an explicit `.claude/agents` path found the line anyway, so I dropped that artefact rather
than claim a contrast it did not show.

**Scope note from the lead's decision** — `.claude/worktrees/` is untracked:
`test_an_untracked_file_under_claude_is_out_of_scope`.

## 7. Regenerated files

None. No change to `net-protocol.lock` or `expected-generated-hashes.txt`.

## Artefacts that are superseded

`superseded-red-run-at-0c48d20-older-message.txt` is an earlier red run whose failure message was
reworded in `4266549`. It is kept rather than deleted, and nothing above quotes it.
