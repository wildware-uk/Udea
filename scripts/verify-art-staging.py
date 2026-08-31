#!/usr/bin/env python3
"""Prove that a fresh clone plus the *documented* art step gives `:moba` a tree it can build.

    python3 scripts/verify-art-staging.py

This is the executable half of issue #154. The claim it checks is the one a reader of
`docs/art-assets.md` relies on and nothing else in the repository tests: the pixels are not
committed, so a fresh clone **cannot** build `:moba` until the documented step has been run, and
after it has been run the build accepts the tree.

It checks a fresh checkout of `HEAD`, not the working tree, because that is what somebody
cloning actually receives. Uncommitted edits to the docs or to the staging script are invisible
to it until they are committed.

## What it asserts, and why each one can fail

1. **The negative control.** `:moba:udeaValidateAssets` must FAIL on the clean tree. If it
   passes, the art is no longer absent — somebody committed the pack, or `.gitignore` stopped
   excluding it — and every later assertion here would be passing for the wrong reason.
2. **The documented step is the step that works.** The command is read out of
   `docs/art-assets.md` between the markers below rather than hardcoded here, so a document that
   names the wrong script fails this check instead of quietly misleading a reader. That is the
   exact defect #154 exists to remove: before it, the manifest offered
   `scripts/extract-art.py` as an equivalent, and that script unpacks two paid ZIPs from a
   Windows `~/Downloads` into `moba/src/main/resources/assets/sprites` — a path `:moba` has not
   read since the asset root moved.
3. **The build accepts the result.** `:moba:udeaValidateAssets` must PASS afterwards.
4. **`LICENSE` covers wherever the step put the art.** The destination directories are taken
   from the files that appeared during step 2, not from a list written here, so moving the
   staging destination without extending `LICENSE` fails this check.
5. **`README.md`'s licence claim matches `LICENSE`.** One name for the licence, in both places.

Exit status is 0 only if all five hold. Every step prints its own verdict.
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


def run(argv, cwd, capture=True):
    """Run a command and return (exit code, combined output)."""
    proc = subprocess.run(
        argv,
        cwd=cwd,
        stdout=subprocess.PIPE if capture else None,
        stderr=subprocess.STDOUT if capture else None,
        text=True,
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


def check_licence_covers_destinations(clean, staged_files):
    text = open(os.path.join(clean, "LICENSE"), encoding="utf-8").read()
    tokens = set(PATH_TOKEN.findall(text))
    uncovered = sorted(p for p in staged_files if not licence_covers(p, tokens))
    if uncovered:
        raise Failure(
            "LICENSE names no directory covering "
            + f"{len(uncovered)} file(s) the documented step created, e.g. "
            + ", ".join(uncovered[:3])
            + ". Third-party art landed at a path the licence exclusion does not mention."
        )
    print(f"  LICENSE covers all {len(staged_files)} staged file(s)")


def check_readme_matches_licence(clean):
    licence = open(os.path.join(clean, "LICENSE"), encoding="utf-8").read()
    readme = open(os.path.join(clean, "README.md"), encoding="utf-8").read()
    name = licence.strip().splitlines()[0].strip()
    if not name:
        raise Failure("LICENSE is empty: it has no licence name on its first line.")
    # README's own licence section, so a stray mention elsewhere in the file cannot satisfy this.
    section = re.search(r"^##+ +Licen[cs]e\s*$(.*?)(?=^##+ |\Z)", readme, re.S | re.M)
    if not section:
        raise Failure("README.md has no licence section for LICENSE's claim to match.")
    short = name.replace(" License", "").replace(" Licence", "")
    if short not in section.group(1):
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

        # Read from the clean tree, not the working tree: every artefact under test - the
        # manifest, the staging script, LICENSE and README - must be the committed one.
        step = documented_step(open(os.path.join(clean, MANIFEST), encoding="utf-8").read())
        print(f"documented step, from {MANIFEST}:")
        for line in step:
            print(f"    {line}")

        print(f"\n[1/5] negative control: {VALIDATE} must FAIL with no staged art")
        code, out = run(["sh", "gradlew", VALIDATE, "--console=plain"], clean)
        if code == 0:
            raise Failure(
                f"{VALIDATE} PASSED on a fresh checkout with nothing staged. The art is no "
                "longer absent from a clone, so this whole check would pass for the wrong "
                "reason. Investigate before trusting anything below."
            )
        diagnostics = out.count("UDEA0032")
        if diagnostics == 0:
            raise Failure(
                f"{VALIDATE} failed, but not for missing art — no UDEA0032 in its output. "
                f"The failure is something else:\n{out[-2000:]}"
            )
        print(f"  FAILED as required, {diagnostics} x UDEA0032")

        before = tree_files(clean)

        print(f"\n[2/5] running the documented step in the clean tree")
        for line in step:
            code, out = run(["sh", "-c", line], clean)
            print(("  " + out.strip()).replace("\n", "\n  "))
            if code != 0:
                raise Failure(
                    f"the step {MANIFEST} documents exited {code}: {line!r}. "
                    "The documentation names a command a fresh clone cannot use."
                )

        staged = sorted(tree_files(clean) - before)
        if not staged:
            raise Failure(
                f"the documented step succeeded but created no files. {MANIFEST} names a "
                "command that does not stage the art."
            )
        print(f"  {len(staged)} new file(s)")

        print(f"\n[3/5] {VALIDATE} must now PASS")
        code, out = run(["sh", "gradlew", VALIDATE, "--console=plain"], clean)
        if code != 0:
            raise Failure(
                f"{VALIDATE} still fails after the documented step:\n{out[-3000:]}"
            )
        print("  PASSED")

        print("\n[4/5] LICENSE must exclude wherever the step put the art")
        check_licence_covers_destinations(clean, staged)

        print("\n[5/5] README.md's licence claim must match LICENSE")
        check_readme_matches_licence(clean)
    finally:
        run(["git", "worktree", "remove", "--force", clean], ROOT)
        shutil.rmtree(parent, ignore_errors=True)

    print("\nOK: a fresh clone plus the documented step builds, and the licence covers it.")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Failure as failure:
        print(f"\nFAILED: {failure}", file=sys.stderr)
        raise SystemExit(1)
