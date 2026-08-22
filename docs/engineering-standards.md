# Udea Engineering Standards

Binding on every module in the `udea-*` tree and on `moba`. Agents and humans both.
This document is checkable on purpose: a standard you cannot fail a review against is a
platitude, not a standard.

The design spec (`docs/superpowers/specs/2026-08-22-udea-ai-native-rewrite-design.md`) says
*what* to build. This says *how it must be written*.

---

## 1. The rewrite exists to kill specific smells

The old engine is the worked example of what not to do. Every item here names the code it
is a reaction to. If you find yourself reproducing one of these, stop.

| Smell | Where it lives in the old tree | The rule |
|---|---|---|
| Global mutable state | `lateinit var gameScreen`, `lateinit var gameManager`, `object Assets` | No top-level `var`. No mutable `object` singletons. State reaches code by constructor injection. |
| God object | `GameScreen` owns viewport, camera, Box2D world, ray handler, sprite batch, shape renderer, stage, console, ECS world, network systems *and* level loading | One type, one reason to change. If you cannot name a class's single responsibility in one clause, split it. |
| Order-dependent implicit contracts | `PacketUtil.kt:122-129` streams components in bag order with no type tag | Every wire and disk format is self-describing and length-prefixed. Never rely on two sides iterating in the same order. |
| Stringly-typed domain | `Assets["character/orc"]`, string attribute lookup | Domain primitives are value classes: `Tick`, `NetId`, `AssetId`, `AttributeId`. Never a bare `Int`, `Long` or `String` for a domain concept. |
| Code built by string concatenation | `NetworkGenerator.kt` builds Kotlin source with `appendLine` and no escaping | Generated code is emitted with KotlinPoet. Never string concatenation. |
| Silent failure | `NetworkGenerator` swallows per-symbol exceptions in a `try/catch` that only logs; `logger.warn` for normal operation | Failures are loud and located. Report through `UdeaDiagnostic` with a stable rule id and a `SourceSpan`. Never log-and-continue past a real error. |
| Reflection on hot paths | `UdeaReflections`, classpath scanning for serializers | No reflection in simulation, replication or rendering. Discovery happens at build time via codegen and `ServiceLoader`. |
| Linear scans as lookups | `utils.kt:35-36` scans the family per inbound packet | Identity resolution is O(1). If a lookup is on a per-tick path, it is indexed. |
| Unbounded/fixed-magic buffers | `ByteBuffer.allocate(2048)`, then writing all 2048 regardless | No magic numbers. Sizes are named constants with a stated reason, or computed. |
| `TODO()` in a live path | `NetworkClientSystem.kt:75`, `// TODO validate the sender!` | No `TODO()` on a reachable path. Unimplemented means the type does not exist yet, or the call fails loudly with a typed error. |

---

## 2. Module and API boundaries

- **Public is a decision.** Default to `internal`. A declaration is `public` only if another
  module genuinely consumes it. The module's public surface should be readable in one sitting.
- **Interfaces at boundaries, implementations inside.** A module exposes an interface
  (`Transport`, `PhysicsWorld`, `Replicator<T>`, `AssetRegistry`); consumers depend on the
  interface. This is what lets `LoopbackTransport` and `SimulatedTransport` exist at all.
- **Dependency direction is enforced by the build**, not by discipline — see
  `udeaVerifyModuleGraph`. If you need something from a module you may not depend on, the
  design is wrong; do not add the dependency.
- **No cyclic packages.** If package `a` imports `b` and `b` imports `a`, extract the shared
  concept into a third package.

### `GameContext` is a context, not a service locator

The spec makes `GameContext` "the sole Fleks injectable". That is a real risk of recreating
`GameScreen` under a new name, and reviewers must treat it as such.

- It holds a **small, fixed** set of engine-wide services. It is not a bag you add to whenever
  something needs reaching.
- A system declares the specific dependencies it needs in its constructor. It does not take
  `GameContext` and reach through it for one field — that is service location, and it hides
  the real dependency graph.
- Adding a field to `GameContext` requires a justification in the PR/report. If it has more
  than a handful of members, it has already failed.

---

## 3. Patterns we use, and where

Use them because they fit, not to be seen using them. Named here so reviewers and implementers
share a vocabulary.

| Pattern | Where | Why |
|---|---|---|
| Strategy | `Transport` (loopback / simulated / UDP), `PhysicsWorld` | Swappable behaviour is the whole point; it is what makes socket-free tests possible. |
| Command | `SimBarrier` queue, agent tool dispatch | Mutations become data: queueable, orderable, replayable, testable. |
| Registry + ServiceLoader | `ComponentType`, `NetModule`, `ToolModule` | Cross-module discovery without reflection or a magic package name. |
| Value class | `Tick`, `NetId`, `AssetId`, field masks | Type safety at zero runtime cost. |
| Sealed hierarchy | packet kinds, `RenderMode`, diagnostics severity, tool results | Exhaustive `when` — the compiler catches the case you forgot. |
| Object pool | bit buffers, `FieldStore` scratch | Only on measured allocation hot paths. A pool anywhere else is premature and a smell. |

**Anti-patterns, explicitly:** no inheritance for code reuse (compose instead); no `Manager`,
`Util`, `Helper` or `Misc` grab-bag types; no static mutable registries populated by side effect
at class-load time; no "framework" abstraction with exactly one implementation and no second one
in sight.

---

## 4. Simulation code has extra rules

Anything that runs inside `Simulation.step()`:

- **No wall clock.** `System.currentTimeMillis`, `nanoTime`, `Instant.now` are forbidden. Time
  comes from `SimClock`, denominated in `Tick`. `SimClock.time` is derived (`tick * dt`), never
  accumulated.
- **No unseeded randomness.** `Math.random`, `Random.Default` are forbidden. Use `RngService`
  and its named stream.
- **No iteration-order-dependent collections** where the order affects output. `HashMap`/`HashSet`
  iteration order is not a contract.
- **Allocation-free in steady state** on per-tick paths. Budgets are CI gates, not aspirations.
- **No I/O, no logging on the hot path**, no blocking calls.

These are enforced by `udeaVerifyDeterminism` (an ASM scan) later, but the scan is a cheap
first filter. The real gate is the snapshot-equivalence hash test. Write code that would pass
both from the start; retrofitting determinism is what Phase 7 exists to avoid needing.

---

## 5. Tests

- A test asserts **behaviour**, not that a constructor did not throw. "It compiles and runs"
  is not a test.
- Test the **property the design claims**, not the implementation's shape. If named RNG streams
  exist so that adding a consumer does not perturb existing sequences, the test adds a consumer
  and asserts the sequences are unchanged. If the generation counter exists so a stale `NetId`
  is detectable, the test recycles a slot and asserts detection.
- **Round-trip and property tests** for anything with an encoding: quantisation, bit packing,
  snapshots, the wire format. Include boundaries and out-of-range input.
- **Golden files** for generated code and wire formats, with an explicit `--update-goldens` path.
- No sleeps, no wall-clock waits, no ordering-dependent flakes. Time in tests is a `ManualClock`.
- A test named `testFoo` that exercises three unrelated things is three tests.

---

## 6. Size and shape

These are review triggers, not hard limits — crossing one means justify it, not that it is banned.

- A file over ~400 lines is doing too much. `DebugInspector.kt` at 851 lines in the reference
  game is exactly the shape to avoid.
- A function over ~40 lines, or with more than ~3 levels of nesting, wants extracting.
- More than ~5 constructor parameters suggests a missing type.
- Duplication: two occurrences is a coincidence, three is a missing abstraction. But a wrong
  abstraction is worse than duplication — prefer duplication until the shape is obvious.

---

## 7. Kotlin specifics

- Immutable by default: `val` over `var`, `List` over `MutableList` in public signatures.
  Mutation points are explicit and few (this is what `SimBarrier` is for).
- Expression bodies and `when` over nested `if`. Exhaustive `when` on sealed types, with no
  `else` branch — so adding a case fails the build at every site that must handle it.
- Nullability is meaningful. `!!` is a code smell; `lateinit` on anything but a genuinely
  two-phase-initialised field is worse. Prefer a sealed `Uninitialised`/`Ready` state.
- `require`/`check` for precondition failures with a message naming the offending value.
- KDoc on every public declaration, saying *why* it exists, not restating the signature.
  Public API without KDoc is incomplete.
- No `!!`-laden interop shims around LibGDX — wrap the awkward API once, properly, at the
  boundary.

---

## 8. What a reviewer must reject

- Any rule in §1 reproduced in new code.
- A `public` declaration nobody outside the module uses.
- A test that cannot fail.
- Generated code produced by string concatenation.
- A new field on `GameContext` without justification.
- Wall-clock or unseeded randomness inside simulation code.
- A `TODO()`, a stubbed return, or a swallowed exception on a reachable path.
- Copy-pasted logic that differs only in a constant.
