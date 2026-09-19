#!/usr/bin/env python3
"""Tests for the document fence in `scripts/verify-art-staging.py` (issue #177).

    python3 -m unittest scripts/test_verify_art_staging.py

The fence says: a document an agent reads must not tell its reader to run a script the checkout
does not have, and it must let a past-tense record of such a script stand. Every case here builds
a real git repository in a temporary directory and commits into it, because the fence reads
committed content through `git` and the thing under test is exactly which files `git` shows it.

The fixtures marked "before #170" are excerpts of what `git show 05ab99d:<path>` prints: three of
the imperatives #170's round 2 had to remove. The ones marked "history" are excerpts of the
past-tense records that replaced them. Each excerpt keeps the lines around the mention that
decide whether it is an instruction; longer paragraphs are cut. They are copied in rather than
read from git so the tests do not depend on this repository's history being fetched.
"""
import importlib.util
import os
import shutil
import stat
import subprocess
import tempfile
import textwrap
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)

_spec = importlib.util.spec_from_file_location(
    "verify_art_staging", os.path.join(HERE, "verify-art-staging.py")
)
verify = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(verify)

GONE = "scripts/stage-moba-art.py"

# `.claude/agents/team-lead.md:242` before #170: an imperative in bold, inside a numbered list.
TEAM_LEAD_IMPERATIVE = textwrap.dedent("""\
    3. Any decision you have already made on the ticket, stated as decided, not as a question.
    4. "Use superpowers:test-driven-development. Failing test first."
    5. **`python3 scripts/stage-moba-art.py` before the first build** — the sprites are gitignored, so a
       fresh worktree has none of them and `:moba:udeaValidateAssets` fails with 25 x `UDEA0032`. Nothing
       in `AGENTS.md` or `HANDOFF.md` says so, and every worktree hits it.
    6. The verification contract: `sh gradlew build` with no exclusions, the xvfb GL run if the ticket
       touches GL, one evidence command proved to go red when the feature is reverted, the images, and
    """)

# `.claude/agents/engineer.md:58` before #170: an indented code block under a heading.
ENGINEER_IMPERATIVE = textwrap.dedent("""\
    ## Stage the art first, or the build cannot pass

    **Your worktree does not have the sprites.** `moba/assets/sprites/` is gitignored — it is
    third-party licensed art from the Tiny RPG Character Asset Pack (`docs/art-assets.md`) that this
    repository has no right to sublicense — so a fresh worktree carries none of it.

        python3 scripts/stage-moba-art.py

    It copies 33 sheets out of `example-assets/sprites/`, where they already are.
    Idempotent, overwrites what it copies, deletes nothing. Run it once, before your first build.
    """)

# `.claude/WAVE.md:48` before #170: a fenced block with a command chained after `cd`.
WAVE_IMPERATIVE = textwrap.dedent("""\
    **Do this in every review checkout and every trial merge, before the build:**

    ```
    cd /tmp/<checkout> && python3 scripts/stage-moba-art.py
    ```

    Everything it copies is gitignored, so `git status` stays clean and the tracked tree remains
    exactly the branch. I did it on all three trial merges this wave. Put it in the reviewer prompt.
    """)

# `.claude/agents/team-lead.md:242-246` on `example` today: the same list item, as history.
TEAM_LEAD_HISTORY = textwrap.dedent("""\
    4. "Use superpowers:test-driven-development. Failing test first."
    5. **No art-staging step, and do not add one.** The sprites are gitignored, but `:moba`'s build
       stages them itself via `:moba:udeaStageCharacterArt` (#170), so a fresh worktree builds with
       nothing typed. Older prompts carried a `python3 scripts/stage-moba-art.py` line; that script no
       longer exists, and copying the line forward sends a developer to run a file that is not there.
       Tell the developer instead that a `UDEA0032` about a `spritePath` is a real defect in its change.
    6. The verification contract: `sh gradlew build` with no exclusions, the xvfb GL run if the ticket
    """)

# `.claude/WAVE.md:46-51` at `e69b01d`: history whose "it is deleted" is the *next* sentence.
WAVE_HISTORY = textwrap.dedent("""\
    **That was true for most of this wave, and #170 ended it.** Every review checkout and trial merge
    up to then ran `python3 scripts/stage-moba-art.py` by hand — I did it on all three trial merges,
    and the reviewer prompt carried it. That script is deleted. `:moba:udeaStageCharacterArt` stages
    the art on every build instead, so a detached checkout builds `moba` on its own: run nothing, and
    put no staging line in the reviewer prompt.
    """)

# `docs/art-assets.md:227-233` on `example` today: the path as a git pathspec in a transcript,
# and named in prose. Neither runs the script.
ART_ASSETS_HISTORY = textwrap.dedent("""\
    written in commit `3f962bb`, and `git show 3f962bb --name-only` does not list
    `scripts/stage-moba-art.py`, because that script did not exist yet. It arrived two commits later
    in `531bec1`, and it copied out of exactly the directories option 2 deletes:

    ```
    $ git log --oneline --diff-filter=A -- scripts/stage-moba-art.py
    531bec1 The game is playable again: 27 units fight with abilities, animation and art
    ```
    """)

# `moba/build.gradle.kts:158` on `example` today: KDoc naming the script, the hit the round-2
# reviewer found that a `-- '*.md'` filter had hidden.
BUILD_SCRIPT_HISTORY = textwrap.dedent("""\
    /**
     * A shell step. The step that fixed it was
     * `scripts/stage-moba-art.py`, named in `docs/art-assets.md` and nowhere the build could see.
     */
    """)

README = "# Udea\n\n## Licence\n\nMIT.\n"


def git(repo, *args):
    subprocess.run(
        ["git", *args], cwd=repo, check=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT
    )


def git_stdout(repo, *args):
    return subprocess.run(["git", *args], cwd=repo, check=True, stdout=subprocess.PIPE).stdout


class Repo:
    """A throwaway git repository whose committed files are the fence's whole input."""

    def __init__(self):
        self.path = tempfile.mkdtemp(prefix="udea-fence-test-")
        git(self.path, "init", "-q")
        git(self.path, "config", "user.email", "fence@test.invalid")
        git(self.path, "config", "user.name", "fence test")
        git(self.path, "config", "commit.gpgsign", "false")
        self.write("README.md", README)

    def write(self, name, text):
        full = os.path.join(self.path, name)
        os.makedirs(os.path.dirname(full), exist_ok=True)
        with open(full, "w", encoding="utf-8") as handle:
            handle.write(text)

    def commit(self, *force_add):
        git(self.path, "add", "-A")
        for name in force_add:
            git(self.path, "add", "-f", name)
        git(self.path, "commit", "-q", "--allow-empty", "-m", "fixture")

    def close(self):
        shutil.rmtree(self.path, ignore_errors=True)


class FenceTest(unittest.TestCase):
    def setUp(self):
        self.repo = Repo()
        self.addCleanup(self.repo.close)

    def assertFails(self, *locations):
        with self.assertRaises(verify.Failure) as caught:
            verify.check_no_instruction_to_run_a_missing_script(self.repo.path)
        message = str(caught.exception)
        for location in locations:
            self.assertIn(location, message)
        return message

    def assertPasses(self):
        verify.check_no_instruction_to_run_a_missing_script(self.repo.path)

    # --- imperatives that must fail, each naming its file and line --------------------------

    def test_the_team_lead_imperative_from_before_170_fails(self):
        self.repo.write(".claude/agents/team-lead.md", TEAM_LEAD_IMPERATIVE)
        self.repo.commit()
        self.assertFails(f".claude/agents/team-lead.md:3: {GONE}")

    def test_the_engineer_code_block_from_before_170_fails(self):
        self.repo.write(".claude/agents/engineer.md", ENGINEER_IMPERATIVE)
        self.repo.commit()
        self.assertFails(f".claude/agents/engineer.md:7: {GONE}")

    def test_the_wave_fenced_command_from_before_170_fails(self):
        self.repo.write(".claude/WAVE.md", WAVE_IMPERATIVE)
        self.repo.commit()
        self.assertFails(f".claude/WAVE.md:4: {GONE}")

    def test_every_offending_line_is_named_not_only_the_first(self):
        self.repo.write(".claude/agents/team-lead.md", TEAM_LEAD_IMPERATIVE)
        self.repo.write(".claude/agents/engineer.md", ENGINEER_IMPERATIVE)
        self.repo.write(".claude/WAVE.md", WAVE_IMPERATIVE)
        self.repo.commit()
        self.assertFails(
            ".claude/WAVE.md:4:", ".claude/agents/engineer.md:7:", ".claude/agents/team-lead.md:3:"
        )

    def test_the_readme_is_still_fenced(self):
        self.repo.write("README.md", README + "\nBefore building, run `scripts/stage-moba-art.py`.\n")
        self.repo.commit()
        self.assertFails(f"README.md:7: {GONE}")

    def test_a_file_under_claude_that_is_not_markdown_is_fenced(self):
        # The round-2 grep that found `moba/build.gradle.kts:158` found it because it did not
        # filter to `*.md`. An agent reads a skill's shell helper as readily as its SKILL.md.
        self.repo.write(".claude/skills/dev-team/prepare.sh", "#!/bin/sh\nsh ./scripts/stage-moba-art.py\n")
        self.repo.commit()
        self.assertFails(f".claude/skills/dev-team/prepare.sh:2: {GONE}")

    def test_the_agent_briefs_at_the_root_are_fenced(self):
        for name in ("AGENTS.md", "CLAUDE.md", "HANDOFF.md", "docs/art-assets.md"):
            with self.subTest(name=name):
                repo = Repo()
                self.addCleanup(repo.close)
                repo.write(name, "Stage the art:\n\n    python3 scripts/stage-moba-art.py\n")
                repo.commit()
                with self.assertRaises(verify.Failure) as caught:
                    verify.check_no_instruction_to_run_a_missing_script(repo.path)
                self.assertIn(f"{name}:3: {GONE}", str(caught.exception))

    def test_a_gitignored_directory_cannot_hide_a_tracked_file(self):
        # A working-tree `grep` built on ugrep skips ignored directories and reports nothing.
        # `git` lists what is committed, ignored or not.
        self.repo.write(".gitignore", ".claude/agents/\n")
        self.repo.write(".claude/agents/engineer.md", ENGINEER_IMPERATIVE)
        self.repo.commit(".claude/agents/engineer.md")
        self.assertFails(f".claude/agents/engineer.md:7: {GONE}")

    def test_the_shells_grep_is_not_what_finds_the_hit(self):
        # Put a `grep` first on PATH that finds nothing and exits 1. A fence that shelled out to
        # `grep` would read that as "no mention" and pass.
        stub_dir = tempfile.mkdtemp(prefix="udea-fence-stub-")
        self.addCleanup(shutil.rmtree, stub_dir, True)
        stub = os.path.join(stub_dir, "grep")
        with open(stub, "w", encoding="utf-8") as handle:
            handle.write("#!/bin/sh\nexit 1\n")
        os.chmod(stub, os.stat(stub).st_mode | stat.S_IXUSR)
        self.repo.write(".claude/agents/engineer.md", ENGINEER_IMPERATIVE)
        self.repo.commit()
        saved = os.environ["PATH"]
        os.environ["PATH"] = stub_dir + os.pathsep + saved
        try:
            self.assertFails(f".claude/agents/engineer.md:7: {GONE}")
        finally:
            os.environ["PATH"] = saved

    # --- what must pass ----------------------------------------------------------------------

    def test_the_history_that_replaced_each_imperative_passes(self):
        self.repo.write(".claude/agents/team-lead.md", TEAM_LEAD_HISTORY)
        self.repo.write(".claude/WAVE.md", WAVE_HISTORY)
        self.repo.write("README.md", README + "\n" + ART_ASSETS_HISTORY)
        self.repo.write(".claude/notes.kts", BUILD_SCRIPT_HISTORY)
        self.repo.commit()
        self.assertPasses()

    def test_history_in_one_paragraph_does_not_excuse_a_command_in_the_next(self):
        self.repo.write(".claude/WAVE.md", WAVE_HISTORY + "\n" + WAVE_IMPERATIVE)
        self.repo.commit()
        self.assertFails(f".claude/WAVE.md:10: {GONE}")

    def test_prose_around_a_code_block_does_not_excuse_the_command_in_it(self):
        self.repo.write(
            ".claude/WAVE.md",
            "The old script is deleted now, but\n```\npython3 scripts/stage-moba-art.py\n```\n",
        )
        self.repo.commit()
        self.assertFails(f".claude/WAVE.md:3: {GONE}")

    def test_a_path_that_starts_a_command_is_run(self):
        self.repo.write(
            ".claude/agents/reviewer.md",
            "Before the build:\n\n"
            "    scripts/stage-moba-art.py --all\n"
            "    $ scripts/stage-moba-art.py\n"
            "    cd /tmp/checkout && scripts/stage-moba-art.py\n",
        )
        self.repo.commit()
        self.assertFails(
            f".claude/agents/reviewer.md:3: {GONE}",
            f".claude/agents/reviewer.md:4: {GONE}",
            f".claude/agents/reviewer.md:5: {GONE}",
        )

    def test_a_file_that_is_not_markdown_is_never_excused_as_history(self):
        # A shell helper is one code unit from its first line to its last, so a comment in it
        # that happens to say "deleted" must not turn the command it runs into a record.
        self.repo.write(
            ".claude/skills/dev-team/prepare.sh",
            "#!/bin/sh\n# Restores sprites a clean checkout deleted.\npython3 scripts/stage-moba-art.py\n",
        )
        self.repo.commit()
        self.assertFails(f".claude/skills/dev-team/prepare.sh:3: {GONE}")

    def test_a_script_the_checkout_has_may_be_run(self):
        self.repo.write("tools/collage.py", "print('ok')\n")
        self.repo.write(".claude/agents/engineer.md", "\n    tools/collage.py <dir-of-pngs> -o /tmp/x.png\n")
        self.repo.write("README.md", README + "\nRun `python3 tools/collage.py` to tile shots.\n")
        self.repo.commit()
        self.assertPasses()

    def test_an_untracked_file_under_claude_is_out_of_scope(self):
        # `.claude/worktrees/` holds other agents' checkouts and is untracked. What a clone
        # receives is the committed tree, and only that is under test.
        self.repo.commit()
        self.repo.write(".claude/worktrees/agent-x/NOTES.md", "\n    python3 scripts/stage-moba-art.py\n")
        self.assertPasses()

    def test_a_document_outside_the_scope_is_not_read(self):
        # The per-ticket briefs are records of what a developer ran at the time.
        self.repo.write("BRIEF-154.md", "\n    python3 scripts/stage-moba-art.py\n")
        self.repo.commit()
        self.assertPasses()


class RepositoryTest(unittest.TestCase):
    """This repository's own committed `HEAD`, not a fixture."""

    def test_this_repository_head_passes(self):
        verify.check_no_instruction_to_run_a_missing_script(ROOT)

    def test_every_committed_record_of_the_deleted_script_reads_as_history(self):
        # Wider than the fence's scope on purpose: `moba/game/build.gradle.kts`'s KDoc is one of the
        # past-tense records the issue says must keep saying so, and this holds the rule itself
        # to every record wherever it sits. Left out: the briefs, for the reason
        # `AGENT_DOCUMENTS` gives, and the fence and these tests, which quote instructions to run
        # a missing script on purpose.
        fence = {
            os.path.relpath(os.path.abspath(__file__), ROOT),
            os.path.relpath(os.path.join(HERE, "verify-art-staging.py"), ROOT),
        }
        listed = git_stdout(ROOT, "grep", "-l", "-z", "-F", "stage-moba-art", "HEAD")
        names = [hit.split(":", 1)[1] for hit in filter(None, listed.decode("utf-8").split("\0"))]
        records = [
            name for name in names
            if not os.path.basename(name).startswith("BRIEF") and name not in fence
        ]
        self.assertIn("docs/art-assets.md", records)
        # `moba/build.gradle.kts` until issue #212 split the project; the KDoc moved with the
        # staging task into `:moba:game`.
        self.assertIn("moba/game/build.gradle.kts", records)
        tracked = set(git_stdout(ROOT, "ls-tree", "-r", "--name-only", "HEAD").decode().splitlines())
        self.assertNotIn(GONE, tracked)
        for name in records:
            with self.subTest(name=name):
                text = git_stdout(ROOT, "show", f"HEAD:{name}").decode("utf-8")
                self.assertEqual([], verify.instructions_to_run_a_missing_script(name, text, tracked))


if __name__ == "__main__":
    unittest.main()
