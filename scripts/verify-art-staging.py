#!/usr/bin/env python3
"""Prove that a fresh clone builds `:moba` with no manual step, and commits no paid-pack art.

    python3 scripts/verify-art-staging.py

This is the executable half of issue #154, rewritten for issue #170. The claim it checks is the
one a reader of `docs/art-assets.md` relies on and nothing else in the repository tests: the
pixels are **not** committed, and the build puts them in place anyway.

It checks a fresh checkout of `HEAD`, not the working tree, because that is what somebody cloning
actually receives. Uncommitted edits to the docs, to the build or to `LICENSE` are invisible to
it until they are committed.

## What it asserts, and why each one can fail

1. **The negative control.** `:moba:udeaValidateAssets` must FAIL on the clean tree when the
   staging task is excluded with `-x`. Two things would make it pass: the art being committed
   after all, or the pipeline no longer minding a `spritePath` it cannot resolve. Either would
   make every later assertion here pass for the wrong reason. This is the assertion #154 shipped;
   what #170 changed is that it now has to be *asked for*, because an unexcluded build stages the
   art itself.
2. **No paid-pack file is committed.** A fresh checkout's sprite tree must hold exactly the files
   listed in [COMMITTED_SPRITES] and nothing else. Making CI green by committing the pack is the
   fix #170 puts out of scope, and this is what would catch it.
3. **The documented step is the step that works.** The command is read out of
   `docs/art-assets.md` between the markers below rather than hardcoded here, so a document that
   names the wrong command fails this check instead of quietly misleading a reader.
4. **The build stages the art, packs it, and leaves the checkout clean.** Files must have
   appeared under the sprite tree during step 3, the `.udeapak` those pixels go into must exist
   afterwards - a staging step that ran and a bundle that did not are two different outcomes -
   and `git status` in the clean tree must be empty, which is what says the art landed somewhere
   git ignores rather than somewhere a contributor could commit it by accident.
5. **`LICENSE` covers wherever the build put the art.** The destination directories are taken
   from the files that appeared during step 3, not from a list written here, so moving the
   staging destination without extending `LICENSE` fails this check.
6. **`README.md`'s licence claim matches `LICENSE`.** One name for the licence, in both places.
7. **`README.md` does not send a reader to a staging script.** It repeats the build instruction
   for the fresh cloner who never opens the manifest, and a repeated instruction can drift from
   the one it repeats - which is exactly how `scripts/extract-art.py` came to be offered as an
   equivalent of a step it has never been able to perform.

Every step prints its own verdict, and exit status is 0 only if all of them hold.
"""
import os
import re
import shutil
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MANIFEST = os.path.join("docs", "art-assets.md")

# The fenced block in `docs/art-assets.md` between these two markers is the documented step.
# They are HTML comments, so they are invisible in rendered markdown.
BEGIN = "<!-- verify-art-staging: the documented step begins -->"
END = "<!-- verify-art-staging: the documented step ends -->"

VALIDATE = ":moba:udeaValidateAssets"

# The task the build runs to put the art in place, excluded in step 1 to make the control mean
# something. A rename fails step 1 as "task not found" rather than passing quietly.
STAGING_TASK = "udeaStageCharacterArt"

SPRITE_TREE = os.path.join("moba", "assets", "sprites")

# The bundle `:moba:build` packs those pixels into.
BUNDLE = os.path.join("moba", "build", "udea", "pack", "assets.udeapak")

# Every file a clone is supposed to carry under the sprite tree, repo-relative.
#
# `.gitignore` excludes the whole tree and then excepts these: `champion_idle.png` predates the
# rule, and the arrow is the free demo pack's 260-byte file plus the `.udea.kts` that has to sit
# beside it. Anything else appearing here means paid-pack art was committed, which is the one
# thing #170 was not allowed to do to make CI green.
COMMITTED_SPRITES = {
    os.path.join(SPRITE_TREE, "champion_idle.png"),
    os.path.join(SPRITE_TREE, "arrow", "arrow.png"),
    os.path.join(SPRITE_TREE, "arrow", "arrow.udea.kts"),
}

# Directory paths in `LICENSE`, e.g. `moba/assets/sprites/`. Two constraints, and dropping
# either one lets a false pass through:
#
#   * a trailing slash, because a token without one names a file and a file is nobody's prefix;
#   * a lookahead for the end of the path, so `moba/assets/sprites/champion_idle.png` - which
#     the licence names in order to *exempt* it - does not read as a directory exclusion
#     covering everything beside it. That is precisely the false pass this check exists to
#     catch, and without the lookahead the exemption would satisfy the rule it is an exemption
#     from.
PATH_TOKEN = re.compile(r"[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*/(?![A-Za-z0-9_.-])")


class Failure(Exception):
    """An assertion this script makes about the repository did not hold."""


def run(argv, cwd):
    """Run a command and return (exit code, combined output)."""
    proc = subprocess.run(
        argv, cwd=cwd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True
    )
    return proc.returncode, (proc.stdout or "")


def documented_step(manifest_text):
    """The command `docs/art-assets.md` tells a fresh clone to run, verbatim."""
    if BEGIN not in manifest_text or END not in manifest_text:
        raise Failure(
            f"{MANIFEST} carries no documented step: it must fence the command a fresh clone "
            f"runs between {BEGIN!r} and {END!r}."
        )
    body = manifest_text.split(BEGIN, 1)[1].split(END, 1)[0]
    fenced = re.search(r"```[a-zA-Z]*\n(.*?)```", body, re.S)
    if not fenced:
        raise Failure(f"{MANIFEST} has the markers but no fenced code block between them.")
    lines = [ln.strip() for ln in fenced.group(1).splitlines() if ln.strip()]
    if not lines:
        raise Failure(f"{MANIFEST}'s documented step is an empty code block.")
    return lines


def tree_files(root):
    """Every file in the tree, repo-relative, ignoring `.git` and Gradle output."""
    found = set()
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in (".git", "build", ".gradle", ".kotlin")]
        for name in filenames:
            found.add(os.path.relpath(os.path.join(dirpath, name), root))
    return found


def licence_covers(path, tokens):
    """True if some path-like token in `LICENSE` is a directory prefix of `path`."""
    return any(path.startswith(token) for token in tokens)


def exclusion_section(licence_text):
    """The part of `LICENSE` that lists what the grant does not cover.

    Scoped deliberately: a path named in the MIT grant above, or in the closing paragraph
    below, must not count as an exclusion. What this cannot do is read English — a path named
    inside the list for some other reason would still satisfy the check, so the assertion this
    makes is "the exclusion list names a directory containing this file", not "the exclusion
    list excludes it". `check_readme_matches_licence` has the same shape and the same limit.
    """
    marker = "Specifically excluded, and NOT redistributable under this licence:"
    if marker not in licence_text:
        raise Failure(
            f"LICENSE has no exclusion list: it must carry the line {marker!r}, which is what "
            "makes the third-party art excluded rather than merely mentioned."
        )
    # The list is the indented block after the marker. It ends at the first line that starts in
    # column 0, which is the closing paragraph. No English is parsed to find that.
    kept = []
    for line in licence_text.split(marker, 1)[1].splitlines():
        if line.strip() and not line.startswith("  "):
            break
        kept.append(line)
    return "\n".join(kept)


def read(clean, name):
    """One repo-relative file out of the tree under test, or a Failure naming what is missing."""
    path = os.path.join(clean, name)
    if not os.path.isfile(path):
        raise Failure(f"a fresh checkout of HEAD has no {name}.")
    return open(path, encoding="utf-8").read()


def check_no_committed_pack_art(clean):
    """A fresh checkout's sprite tree must hold the excepted files and nothing else."""
    present = {
        os.path.join(SPRITE_TREE, path)
        for path in tree_files(os.path.join(clean, SPRITE_TREE))
    }
    unexpected = sorted(present - COMMITTED_SPRITES)
    if unexpected:
        raise Failure(
            f"a fresh checkout carries {len(unexpected)} file(s) under {SPRITE_TREE} that are "
            "not the documented exceptions: "
            + ", ".join(unexpected[:5])
            + ". Committing the paid pack is how this build would go green without the staging "
            "step, and docs/art-assets.md rules it out."
        )
    absent = sorted(COMMITTED_SPRITES - present)
    if absent:
        raise Failure(
            "a fresh checkout is missing " + ", ".join(absent) + ", which this check expects it "
            "to carry. Either the file was removed or this list is out of date; both are worth "
            "a decision rather than a silent pass."
        )
    print(f"  {len(present)} file(s) under {SPRITE_TREE}, all of them the documented exceptions")


def check_licence_covers_destinations(clean, staged_files):
    tokens = set(PATH_TOKEN.findall(exclusion_section(read(clean, "LICENSE"))))
    uncovered = sorted(p for p in staged_files if not licence_covers(p, tokens))
    if uncovered:
        raise Failure(
            "LICENSE names no directory covering "
            + f"{len(uncovered)} file(s) the build created, e.g. "
            + ", ".join(uncovered[:3])
            + ". Third-party art landed at a path the licence exclusion does not mention."
        )
    print(f"  LICENSE covers all {len(staged_files)} staged file(s)")


def readme_licence_section(readme_text):
    """`README.md`'s own licence section, so a stray mention elsewhere cannot satisfy a check."""
    section = re.search(r"^##+ +Licen[cs]e\s*$(.*?)(?=^##+ |\Z)", readme_text, re.S | re.M)
    if not section:
        raise Failure("README.md has no licence section for LICENSE's claim to match.")
    return section.group(1)


def check_readme_names_the_same_step(clean, step):
    """`README.md` repeats the build instruction; it must not drift from the documented one.

    Conditional by design: a README that names no script at all passes here, because the
    manifest is the authority and the README is allowed to link rather than repeat. What it
    cannot do is repeat a *different* instruction, which is the drift this exists to catch.
    Since #170 the documented step names no script, so a README that names one is by definition
    sending a reader somewhere the manifest does not.
    """
    section = readme_licence_section(read(clean, "README.md"))
    named = set(re.findall(r"scripts/[A-Za-z0-9_.-]+\.py", section))
    documented = set(re.findall(r"scripts/[A-Za-z0-9_.-]+\.py", "\n".join(step)))
    drifted = sorted(named - documented)
    if drifted:
        raise Failure(
            f"README.md's licence section tells a reader to run {', '.join(drifted)}, which is "
            f"not what {MANIFEST} documents ({', '.join(sorted(documented)) or 'no script'}). "
            "Two front doors, two different instructions."
        )
    print(f"  README.md names {', '.join(sorted(named)) or 'no script'}, consistent with {MANIFEST}")


def check_readme_matches_licence(clean):
    licence = read(clean, "LICENSE")
    readme = read(clean, "README.md")
    heading = licence.strip().splitlines()
    if not heading:
        raise Failure("LICENSE is empty: it names no licence for README.md to match.")
    name = heading[0].strip()
    section = readme_licence_section(readme)
    short = name.replace(" License", "").replace(" Licence", "")
    if short not in section:
        raise Failure(
            f"README.md's licence section does not name {short!r}, "
            f"which is what LICENSE's first line says this project is: {name!r}."
        )
    print(f"  README.md's licence section and LICENSE agree on {short!r}")


def main():
    print(f"repository: {ROOT}")
    _, sha = run(["git", "rev-parse", "--short", "HEAD"], ROOT)
    print(f"verifying commit: {sha.strip()} (a fresh checkout of HEAD, not the working tree)")

    parent = tempfile.mkdtemp(prefix="udea-art-verify-")
    clean = os.path.join(parent, "clean")
    code, out = run(["git", "worktree", "add", "--detach", clean, "HEAD"], ROOT)
    if code != 0:
        raise Failure(f"could not create a clean worktree:\n{out}")
    try:
        print(f"clean tree: {clean}")

        # The wrapper is checked in WITHOUT the executable bit - CI's first step after checkout
        # is `chmod +x ./gradlew` - so the documented step is given the same treatment here
        # rather than being rewritten into something a reader would not type.
        os.chmod(os.path.join(clean, "gradlew"), 0o755)

        # Read from the clean tree, not the working tree: every artefact under test - the
        # manifest, the build, LICENSE and README - must be the committed one.
        step = documented_step(read(clean, MANIFEST))
        print(f"documented step, from {MANIFEST}:")
        for line in step:
            print(f"    {line}")

        print(f"\n[1/7] negative control: {VALIDATE} must FAIL with -x {STAGING_TASK}")
        code, out = run(
            ["sh", "gradlew", VALIDATE, "-x", STAGING_TASK, "--console=plain"], clean
        )
        if code == 0:
            raise Failure(
                f"{VALIDATE} PASSED on a fresh checkout with the staging task excluded. The art "
                "is no longer absent from a clone, or the pipeline stopped minding an "
                "unresolvable spritePath. Either way this whole check would pass for the wrong "
                "reason. Investigate before trusting anything below."
            )
        # Distinguished from a plain "no UDEA0032" because it is the failure a reader most
        # needs named: with the staging task gone, `-x` refuses the invocation and the control
        # is answering a question about a task that does not exist.
        if "not found" in out and STAGING_TASK in out:
            raise Failure(
                f"there is no task called {STAGING_TASK} to exclude, so the control could not "
                "be run. Nothing in this build stages the art, which is the state issue #170 "
                f"was filed about:\n{out[-1500:]}"
            )
        diagnostics = out.count("UDEA0032")
        if diagnostics == 0:
            raise Failure(
                f"{VALIDATE} failed, but not for missing art — no UDEA0032 in its output. "
                f"The failure is something else:\n{out[-2000:]}"
            )
        print(f"  FAILED as required, {diagnostics} x UDEA0032")

        print(f"\n[2/7] a fresh checkout must carry no paid-pack art under {SPRITE_TREE}")
        check_no_committed_pack_art(clean)

        before = tree_files(clean)

        print("\n[3/7] running the documented step in the clean tree")
        for line in step:
            code, out = run(["sh", "-c", line], clean)
            print(("  " + out.strip()[-4000:]).replace("\n", "\n  "))
            if code != 0:
                raise Failure(
                    f"the step {MANIFEST} documents exited {code}: {line!r}. "
                    "The documentation names a command a fresh clone cannot use."
                )

        print("\n[4/7] the build must have staged the art, packed it, and left the tree clean")
        appeared = sorted(tree_files(clean) - before)
        staged = [path for path in appeared if path.startswith(SPRITE_TREE + os.sep)]
        if not staged:
            raise Failure(
                f"the documented step succeeded but created no file under {SPRITE_TREE}. "
                f"{MANIFEST} names a command that does not stage the art, and step 1 says the "
                "tree needs staging."
            )
        if not os.path.isfile(os.path.join(clean, BUNDLE)):
            raise Failure(
                f"{len(staged)} file(s) were staged but {BUNDLE} does not exist, so nothing "
                "proves the pipeline got as far as packing those pixels."
            )
        # `git status` and not a walk of the tree, because the question is not "what did the
        # build write" - it wrote a `build/` directory and a `gamebridge.json` and is meant to -
        # but "did any of it land somewhere git would track". A build that stages licensed art
        # into a tracked path is the failure this catches, and it is the same failure whether
        # the path is new or an overwrite of something committed.
        code, out = run(["git", "status", "--porcelain"], clean)
        if code != 0 or out.strip():
            raise Failure(
                "the build left the clean checkout dirty:\n" + out.strip()[:2000] + "\nEvery "
                "file it stages has to be one git ignores, or a clone becomes a checkout with "
                "uncommitted paid-pack art in it."
            )
        print(f"  {len(staged)} sheet(s) staged, {BUNDLE} packed, `git status` clean")

        print("\n[5/7] LICENSE must exclude wherever the build put the art")
        check_licence_covers_destinations(clean, staged)

        print("\n[6/7] README.md's licence claim must match LICENSE")
        check_readme_matches_licence(clean)

        print("\n[7/7] README.md must not name a staging script")
        check_readme_names_the_same_step(clean, step)
    finally:
        # Say so rather than leaving a stale worktree registration behind silently. The check's
        # verdict does not depend on cleanup, so this warns instead of raising.
        code, out = run(["git", "worktree", "remove", "--force", clean], ROOT)
        if code != 0:
            print(f"\nwarning: could not remove {clean}; `git worktree prune` will:\n{out}")
        shutil.rmtree(parent, ignore_errors=True)

    print("\nOK: a fresh clone builds :moba with no manual step, and the licence covers the art.")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Failure as failure:
        print(f"\nFAILED: {failure}", file=sys.stderr)
        raise SystemExit(1)
