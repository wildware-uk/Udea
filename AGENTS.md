# Udea — agent brief

A Kotlin/LibGDX/Fleks engine built so agents can do most of the work of making a game with it.
Package root `dev.wildware.udea`. Kotlin 2.2.10, KSP 2.2.10-2.0.2, Gradle 8.13, JDK 17.

Three documents, in order of authority:

1. **`docs/engineering-standards.md`** — the charter. Binding on every `udea-*` module and on
   `moba`. Section 8 is the reject list a reviewer works from. Read it before writing code.
2. **`docs/superpowers/specs/2026-08-22-udea-ai-native-rewrite-design.md`** — the design. What
   to build and why, in eight phases.
3. **This file** — orientation and rules. Not a tutorial, not API docs.

`docs/contracts/` holds the frozen contracts. **Frozen means frozen**: if your work needs one
to change, stop and say so. Do not change it and carry on.

---

## Do not

- **No `by net(...)` delegate.** Dead. Replication is capture-and-diff over a generated
  `Replicator<T>`.
- **No separate snapshot codec.** One `Replicator<T>` per component serves delta replication,
  snapshot capture, snapshot restore and the agent's field access. Four things that cannot
  disagree about what an entity is.
- **No setter instrumentation for dirty tracking.** In-place `Vector2` mutation defeats setters.
  Capture-and-diff, always.
- **No wall clock in simulation.** `System.currentTimeMillis`, `nanoTime`, `Instant.now` are
  forbidden inside `Simulation.step()`. Time is `SimClock`, denominated in `Tick`.
- **No unseeded randomness in simulation.** `Math.random` and `Random.Default` are forbidden.
  Use `RngService` and its named stream.
- **No new module depending on `common`.** A Gradle rule fails the build; it is not a
  convention. See "The old tree" below.
- **No reflection on a per-tick path**, no `TODO()` on a reachable path, no swallowed
  exception, no generated code built by string concatenation, no bare `Int`/`Long`/`String`
  for a domain concept.

---

## Modules

Arrows point downward only. A module may depend on modules below it in this table, never above.

| Module | Purpose |
|---|---|
| `udea-annotations` | Zero-dependency leaf: `@Net`, `@Sim`, `@Q`, `@Replicated`, `@AgentTool`, `@Arg` |
| `udea-diagnostics` | Zero-dependency leaf: `UdeaDiagnostic`, `Severity`, `SourceSpan`, `Fix`, rule ids, the JSON report |
| `udea-codegen` | The KSP2 processor and KotlinPoet emitters; owns id assignment |
| `udea-compiler-plugin` | The K2 FIR/IR plugin: checkers, KDoc propagation, gated declaration synthesis |
| `udea-core` | Headless kernel. **No GL on the compile classpath** |
| `udea-assets` | Runtime asset model and `.udeapak` reader |
| `udea-assets-compiler` | Build-time only. **Zero Gradle types** |
| `udea-gas` | Abilities, attributes, effects — tick-denominated |
| `udea-net` | Transports, baselines, relevancy, prediction, RPC |
| `udea-render` | **The only module that touches GL** |
| `udea-audio` | Drains `GameContext.cues` and plays sound. No GL and no `Gdx`: playback is an `AudioDevice` SPI, and `AudioDevice.Silent` is what Headless uses |
| `udea-agent` | MCP tool surface and test harness — the same code path |
| `udea-agent-host` | HTTP server. Debug-only, verified absent from release |
| `udea-gradle` | Tasks, verifiers, `gamebridge.json` emission |
| `moba` | The example game: a 5v5 three-lane MOBA |
| `common` | **Old tree.** Replaced module by module, deleted in Phase 6 |
| `gradle-plugin` | **Old tree.** Replaced by `udea-codegen` + `udea-gradle` |
| `example` | **Old tree.** Replaced by `moba` |
| `example:assets` | **Old tree.** Goes with `example` |

Three rules that are cheap to break and expensive to find:

- **No GL outside `udea-render`.** `udea-core` must compile and run with no GL context at all.
- **Presentation systems are not Fleks systems.** They implement `RenderSystem` (or
  `OverlaySystem`) and live in `udea-render`, so `world.update(dt)` is pure simulation *by
  construction* rather than by convention.
- **Nothing new depends on `common`.** `udeaVerifyNoLegacyDependencies` enforces it, applied
  automatically to every `udea-*` project and to `moba`.

Enforced by `./gradlew udeaVerifyModuleGraph udeaVerifyNoLegacyDependencies`. Rule ids and
rationale: `docs/module-graph.md`.

---

## The tick model

- **60Hz fixed simulation.** Every duration, deadline, ring slot, baseline and input stamp is a
  `Tick`. Never a float of seconds, never a wall-clock millisecond.
- **`SimClock.time` is derived** (`tick * dt`), never accumulated. Accumulating drifts.
- **`SimBarrier` is drained at the top of `Simulation.step()`**, before any system runs. Scene
  swaps, asset hot-reload deltas, agent tool mutations and snapshot application all queue on it.
  No system ever observes a torn world, and there is one place to reason about atomicity.
- **20Hz snapshots** — every 3rd tick. **30Hz input send.** **Render is decoupled** with an
  interpolation alpha.
- **Seconds exist only in `udea-render` and audio.** An `OverlaySystem` takes `dtSeconds` and
  cannot read simulation time; the signature is the enforcement.

---

## Frozen contracts

The cross-cutting agreements from spec section 5. Each is frozen early because a late change
breaks several modules at once.

| Contract | The rule |
|---|---|
| **Serialization** | One `Replicator<T>`, two masks (`@Net`/`@Sim`), frozen in Phase 0 behind golden tests. `docs/contracts/replicator.md` |
| **Dirty determination** | Capture-and-diff, never setter instrumentation |
| **Id assignment** | One generator, sorted FQNs, checked-in `net-protocol.lock`, `u16 protoHash` in packet byte 0, `ServiceLoader` discovery |
| **Between-tick mutation** | One `SimBarrier`, drained at the top of `step()` |
| **Entity identity** | `NetId` (dense `u16` + `u8` generation), never a Fleks `Entity`, across snapshots, packets and tool calls. `IntArray` for O(1) resolution |
| **Time** | `Tick` is universal. `SimClock.time` is derived, never accumulated |
| **Authority vocabulary** | One family on `@Net`: `authority = Server \| OwnerPredicted \| OwnerWritable`, `lifetime = OnCreate \| Always`, `visibility = All \| OwnerOnly`, `agentWritable = false` by default |
| **Diagnostics** | One `UdeaDiagnostic`: severity, stable rule id, message, repo-relative `SourceSpan`, `assetId`, optional `Fix`. Capped at 25, root-cause-first, mandatory did-you-mean. K2 checkers emit the *same* rule ids as the asset validator |
| **Randomness** | `RngService` with named streams (`Combat`, `Loot`, `AI`, `Spawn`, `Wave`), xoshiro256\*\* with explicit state in the snapshot. Presentation gets a separately typed, wall-seeded `PresentationRandom` |

`docs/contracts/replicator.md` carries one invariant worth repeating, because breaking it is
silent: **`fieldNames[i]` == `FieldMask` bit *i* == `FieldStore` field index *i*.**
`desync_report` names the differing *field* by indexing `fieldNames` with each set bit of a mask
diff, so a misalignment does not fail — it lies.

---

## Render modes

All three run the identical `Simulation` and differ only in whether a `Presentation` exists.
`/health` reports the mode, so an agent knows which toolsets are live before calling one.

| Mode | GL context | Window | Screenshots | Used by |
|---|---|---|---|---|
| `Headless` | none | none | typed `no_render_context` error | dedicated server, CI, `SimHarness`, fast-forward |
| `Offscreen` | real LWJGL3 | hidden | full | `moba.agent` default |
| `Windowed` | real LWJGL3 | visible | full | the player |

The agent activity overlay draws only in `Windowed`, and only onto `ScreenTarget`, which
`FrameCapture` never reads. An agent must not be able to see its own narration in a screenshot
it diffs. That exclusion is structural, not a flag somebody remembers to clear.

---

## Driving a running game

Every Udea game exposes an MCP tool surface automatically. There is no level editor and no IDE
plugin: **the tool surface is the editor.**

| Endpoint | Answers |
|---|---|
| `/health` | is it alive, which `RenderMode`, `completedCommandId` |
| `/state` | the world digest |
| `/command` | synthesised input |
| `/tools` | every generated tool, with the description a model actually reads |

`gamebridge.json`, emitted by `udea-gradle`, is what the unmodified `game-bridge-mcp` reads to
find the game. The bridge is not modified to suit Udea; Udea conforms to the bridge, and CI
asserts it against a vendored copy of the bridge's TS client.

---

## The old tree

`common`, `gradle-plugin` and `example` are the previous engine. They are deleted **as they are
replaced**, not left "for reference".

- Every old file has a row in **`docs/migration/ledger.md`** with a disposition, a destination
  and a phase. `./gradlew udeaLegacyReport` fails if one does not.
- Anything needed from the old tree is copied forward **file by file, with the copy reviewed**.
  `./gradlew udeaVerifyMigration` fails on an unreviewed copy, and on a copy whose source has
  changed since the review.
- `level-editor`, `idea-plugin` and `compose-ui` are gone (D6). They are not coming back.

---

## Before you say it works

```
./gradlew build
```

No `-x` exclusions. The whole repository is green; if it is not, that is your change.

- Tests assert **behaviour**. A test that cannot fail is a defect a reviewer will reject.
- Break the production code, watch the test go red, revert. A test you have not seen fail is
  unverified.
- Update this file in the same change whenever a contract or a module moves. A stale `AGENTS.md`
  is a correctness bug, not a docs nit — `./gradlew udeaVerifyAgentsMd` fails when its module
  table stops matching `settings.gradle.kts`.
