# Handoff

A pointer, not a status report. This file used to carry a snapshot of the tree - commits, test
counts, what was red - and every snapshot went stale within a phase while still reading as
current. What was true of the tree is in `git log`; what is true of it now is in the documents
below, which a gate keeps honest, and in the build, which you run.

## Read, in this order

1. **`AGENTS.md`** - the brief: modules and their targets, the tick model, the frozen contracts,
   the do-not list, what the engine does today and how to drive a running game.
   `udeaVerifyAgentsMd` fails the build when its module table stops matching the tree.
2. **`docs/engineering-standards.md`** - binding. Section 8 is the list a reviewer rejects
   against.
3. **`docs/superpowers/specs/2026-09-16-kool-kmp-port-design.md`** - the Kool and Kotlin
   Multiplatform port (epic #199), and
   **`docs/superpowers/specs/2026-08-22-udea-ai-native-rewrite-design.md`** - the design it ports.
4. **`docs/contracts/`** - frozen. `udeaVerifyContracts` fails the build when one moves.
5. **`docs/module-graph.md`** - every module's convention, arrows and the rule ids that police
   them.

## Where the work lands

`master` is the integration branch. The port was built on `kmp` and merged into `master` in
issue #214; `kmp` is retired, as `example` was before it.

## Verify it

```
./gradlew build
```

No `-x` exclusions. On this project's development box the wrapper is `sh gradlew` and
`JAVA_HOME` must point at a JDK 21; `AGENTS.md` and the dev-team skill say why. What `check`
does not run, and where it runs instead:

- **The GL tests.** `udeaGlTest`, `udeaAgentGlTest` and `udeaEditorGlTest` skip with no display unless
  `-Pudea.render.requireGl=true`; the `gl tests (xvfb)` CI job runs them for real.
- **The wall-clock budgets.** `udeaLatencyBudgets`, in the `latency budgets` CI job, with the
  runner to itself.
- **Gates run by name**, each out of `check` for the reason in its own KDoc:
  `:moba:desktop:runUdpProof` (three OS processes over real UDP) and
  `:moba:desktop:runLaneShot` (lane PNGs on a real GL context). The verifiers
  (`udeaVerifyModuleGraph`, `udeaVerifyAgentsMd`, `udeaVerifyContracts`) are on `check`.

## What the port left for later, on purpose

Decisions rather than defects, each recorded on its issue:

- **Web** is shelved (#223, #226): Kool 0.19.0 publishes no wasmJs artifact, so nothing that
  draws has a wasmJs target.
- **iOS** has no renderer - Kool has no iOS backend (port spec D2) - so iOS is the headless
  modules only, tested by the `iOS simulator tests` CI job on macOS.
- **Android** launches `moba` headless, because `udea-render` has no Android Kool backend yet.
- **`udea-physics2d`** exists and `moba` does not install it.
