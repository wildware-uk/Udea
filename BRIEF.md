# BRIEF.md

A developer's brief for the ticket currently on a branch. It is the reviewer's second input after
the diff: what was built, the one evidence command, the mutation table with its diffs, and the
predictions that were frozen before anything ran.

**This file is a placeholder on `master` on purpose.** When a ticket merges, its brief is archived
as `BRIEF-<issue>.md` beside the others and this placeholder returns.

## Why it is not simply deleted between tickets

It was, once, for about an hour on 2026-09-20, and it cost a developer a silent wrong result.

`master` deleting `BRIEF.md` while a branch writes its own is exactly the shape git's **rename
detection** looks for. Rebasing across the deletion, git concluded the branch's brief *was* the
archived one moving, applied the developer's text onto `BRIEF-266.md`, and left no `BRIEF.md` at
all. **A clean rebase, no conflict, and the wrong result** - the developer caught it only by
listing the files afterwards. The same trap fires on a merge, so it would have reached `master`.

Keeping a file here means a branch's brief collides with this placeholder instead: an ordinary
modify/modify conflict, visible, resolved by taking the branch's version. A conflict you can see
beats a merge that quietly does something else, which is the same rule this repository keeps
relearning about checks that report success while measuring nothing.
