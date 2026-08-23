# CLAUDE.md

@AGENTS.md

That file is the brief: module arrows, the tick model, the frozen contracts, the do-not list and
how to drive a running game. It is the same one every other agent toolchain reads, so there is
one source rather than two that drift.

Two things it cannot say for you:

- `docs/engineering-standards.md` is **binding**, not advisory. Section 8 is the list a reviewer
  rejects against.
- Verify with `./gradlew build` — no `-x` exclusions. The repository is green today.
