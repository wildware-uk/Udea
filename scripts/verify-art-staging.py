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
6. **`README.md` does not send a reader to a different staging script.** It repeats the command
   for the fresh cloner who never opens the manifest, and a repeated instruction can drift from
   the one it repeats.

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


def check_licence_covers_destinations(clean, staged_files):
    tokens = set(PATH_TOKEN.findall(exclusion_section(read(clean, "LICENSE"))))
    uncovered = sorted(p for p in staged_files if not licence_covers(p, tokens))
    if uncovered:
        raise Failure(
            "LICENSE names no directory covering "
            + f"{len(uncovered)} file(s) the documented step created, e.g. "
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
    """`README.md` repeats the staging command; it must not drift from the documented one.

    Conditional by design: a README that names no script at all passes here, because the
    manifest is the authority and the README is allowed to link rather than repeat. What it
    cannot do is repeat a *different* script, which is the drift this exists to catch.
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

        # Read from the clean tree, not the working tree: every artefact under test - the
        # manifest, the staging script, LICENSE and README - must be the committed one.
        step = documented_step(read(clean, MANIFEST))
        print(f"documented step, from {MANIFEST}:")
        for line in step:
            print(f"    {line}")

        print(f"\n[1/6] negative control: {VALIDATE} must FAIL with no staged art")
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

        print("\n[2/6] running the documented step in the clean tree")
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

        print(f"\n[3/6] {VALIDATE} must now PASS")
        code, out = run(["sh", "gradlew", VALIDATE, "--console=plain"], clean)
        if code != 0:
            raise Failure(
                f"{VALIDATE} still fails after the documented step:\n{out[-3000:]}"
            )
        print("  PASSED")

        print("\n[4/6] LICENSE must exclude wherever the step put the art")
        check_licence_covers_destinations(clean, staged)

        print("\n[5/6] README.md's licence claim must match LICENSE")
        check_readme_matches_licence(clean)

        print("\n[6/6] README.md must not name a different staging script")
        check_readme_names_the_same_step(clean, step)
    finally:
        # Say so rather than leaving a stale worktree registration behind silently. The check's
        # verdict does not depend on cleanup, so this warns instead of raising.
        code, out = run(["git", "worktree", "remove", "--force", clean], ROOT)
        if code != 0:
            print(f"\nwarning: could not remove {clean}; `git worktree prune` will:\n{out}")
        shutil.rmtree(parent, ignore_errors=True)

    print("\nOK: a fresh clone plus the documented step builds, and the licence covers it.")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Failure as failure:
        print(f"\nFAILED: {failure}", file=sys.stderr)
        raise SystemExit(1)
