# Architecture

Udea is a stack of Gradle modules where every dependency arrow points down. The bottom is a headless kernel (`udea-core`) that runs the simulation with no graphics at all; the GPU lives in exactly one module (`udea-render`) near the top. A game is a list of modules handed to a `UdeaGameDef`, which builds one world, one context and one fixed order of systems.

A plain picture: the kernel is an engine block that runs on a test bench. Rendering, audio, networking and the editor are parts you bolt on. None of them may reach down and change how the engine block works, and the engine block does not know they exist.

## The modules

The table below is in dependency order: a module may depend only on modules above it in the list, never below. `AGENTS.md` holds the authoritative copy of this table, and `udeaVerifyAgentsMd` fails the build when it stops matching `settings.gradle.kts`.

| Module | What it is |
|---|---|
| `udea-annotations` | Zero-dependency leaf: `@Net`, `@Sim`, `@Q`, `@Replicated`, `@AgentTool`, `@Arg`, and the gizmo handle annotations |
| `udea-diagnostics` | Zero-dependency leaf: `UdeaDiagnostic`, `Severity`, `SourceSpan`, `Fix`, the rule ids and the JSON report |
| `udea-codegen` | The KSP2 processor. Generates replicators, registries, the protocol lock, agent tools and gizmos. Owns id assignment |
| `udea-compiler-plugin` | The K2 compiler plugin: checkers that report `UDEAnnnn` errors, and a KDoc harvester |
| `udea-fleks` | Fleks 2.14, the entity system, vendored as source so it can target iOS. Third-party MIT code; not refactored |
| `udea-core` | The headless kernel: tick loop, clock, barrier, identity, snapshots, levels, movement, the physics interface. No GL on its classpath |
| `udea-assets` | The runtime asset model and the `.udeapak` reader |
| `udea-assets-compiler` | Build-time only: compiles `.udea.kts` into a `.udeapak`. Contains no Gradle types |
| `udea-gas` | The gameplay ability system: attributes, effects, abilities, cues, all counted in ticks |
| `udea-net` | Transports (UDP, WebSocket, loopback), replication, relevancy, prediction and RPC |
| `udea-physics2d` | Box2D 3 through `box2d-jni`, behind `udea-core`'s `PhysicsWorld`. `jvm` and `android` only |
| `udea-render` | The only module that touches the GPU. Kool, the ComposeGL interface host, and the Kool audio device live here |
| `udea-audio` | Drains cues and plays sound through an `AudioDevice` interface. No GL, no Kool |
| `udea-agent` | The MCP tool surface and the test harness, which are the same code |
| `udea-agent-host` | The debug-only HTTP server that exposes the tools. Verified absent from release builds |
| `udea-replay` | `.udearep` recording, headless replay, and the bisect tools |
| `udea-editor` | The editor window. Debug-only, JVM only, and only a game's `editor` source set may depend on it |
| `udea-gradle` | Gradle plugins: the asset pipeline tasks and `gamebridge.json` |
| `moba:game` | The example game as a library: components, systems, assets, and what it draws. No `main` |
| `moba:desktop` | The desktop launcher: `run`, `runServer`, `runClient`, `runEditor`, the screenshot mains and the proofs |
| `moba:android` | The Android launcher. It runs the simulation headless, because `udea-render` has no Android Kool backend yet |

Three rules matter more than the rest, because breaking them is easy and finding the break is not:

1. **No GL outside `udea-render`.** `udea-core` must compile and run with no graphics context.
2. **Presentation is not a Fleks system.** Drawing code implements `RenderSystem` or `OverlaySystem` in `udea-render`. So `world.update(dt)` is pure simulation by construction.
3. **No LibGDX.** The engine used to be built on it. Rule `UDEA-MG-009` now fails the build if any project resolves a `com.badlogicgames` artifact.

`sh gradlew udeaVerifyModuleGraph` enforces the arrows. Every rule id, with its reason, is in `docs/module-graph.md`. [Build and Verification](Build-and-Verification) lists the gates.

## Targets: which platforms each module builds for

Udea uses Kotlin Multiplatform. A module picks its targets by applying one of the convention plugins in `build-logic/src/main/kotlin/`:

| Convention plugin | Targets | Used by |
|---|---|---|
| `dev.wildware.udea.kotlin-multiplatform` | `jvm`, `android`, `wasmJs`, `iosArm64`, `iosSimulatorArm64` | `udea-annotations`, `udea-diagnostics`, `udea-fleks`, `udea-core`, `udea-assets`, `udea-gas`, `udea-audio`, `udea-replay` |
| `udea.kotlin-multiplatform-no-ios` | `jvm`, `android`, `wasmJs` | `udea-net`, `udea-agent` (each has an `expect` with no iOS `actual` yet) |
| `udea.kotlin-multiplatform-jvm-android` | `jvm`, `android` | `udea-physics2d` (`box2d-jni` publishes nothing else) |
| `dev.wildware.udea.kotlin-multiplatform-render` | `jvm`, `android` | `udea-render` and `moba:game` (Kool has no iOS backend and no wasmJs artifact) |
| `dev.wildware.udea.kotlin-library` | JVM | `udea-agent-host`, `udea-editor`, `moba:desktop` |
| `udea.kotlin-build-tool` | JVM, build time only | `udea-codegen`, `udea-compiler-plugin`, `udea-assets-compiler` |

`udea-gradle` is a Gradle plugin (`udea.gradle-plugin`) and `moba:android` is an Android app (`udea.android-application`).

Some consequences worth knowing:

- `udea-fleks` exists because Fleks publishes no iOS artifact. Vendoring it as source is what lets `udea-core` build for iOS.
- Web is shelved: `udea-render` has no `wasmJs` target, so no game draws in a browser today. The headless modules still build and test on `wasmJs`.
- iOS cannot be built on Linux. `sh gradlew :<module>:allTests` skips iOS off macOS; the `ios-tests` CI job runs it.
- The Android SDK comes from `ANDROID_HOME` or an untracked `local.properties`.

## Headless and rendered: one simulation, three modes

The same simulation runs in three render modes. They differ only in whether a presentation exists.

| `RenderMode` | GPU context | Window | Screenshots | Used by |
|---|---|---|---|---|
| `Headless` | none | none | a typed `no_render_context` error | a dedicated server, CI, fast-forward |
| `Offscreen` | real | hidden | full | an agent driving the game (`moba`'s agent default) |
| `Windowed` | real | visible | full | a player, and the editor |

`GameHost` (in `udea-core/src/commonMain/kotlin/dev/wildware/udea/core/host/GameHost.kt`) ties a mode to a game. It takes a `RenderMode`, a `UdeaGameDef` and an optional `PresentationFactory`. In `Headless` the factory is never called. That is why a dedicated server has no GPU code on its path at all, rather than GPU code that happens to be switched off.

`moba` shows the pattern. `MobaGame.host(mode, presentation)` builds a fresh definition and wraps it in a `GameHost`. The server session (`MobaHostSession`) calls it with `RenderMode.Headless` and no presentation; the windowed client and the agent pass a Kool presentation. So a bug that shows in one mode and not another is a renderer bug, never a second copy of the game. The page [Rendering with Kool](Rendering-with-Kool) covers the render side.

## How a game is assembled

A game is a list of `UdeaModule`s. A module is ordinary code: you construct it, so it can take any dependencies it wants. It has three hooks, all optional:

```kotlin
// udea-core/src/commonMain/kotlin/dev/wildware/udea/core/module/UdeaModule.kt (abridged)
public interface UdeaModule {
    public val name: String
    public fun context(builder: GameContextBuilder) {}   // contribute services
    public fun simulation(registry: SimRegistry) {}      // declare systems, phases, order
    public fun level(hooks: LevelHooks) {}               // contribute to level files
}
```

`UdeaGameDef` takes the modules, the generated registry and the engine config, and `build()` turns them into a running `UdeaGame`. `build()` does this, in order:

1. It adds `CoreModule` in front of your modules. Never list it yourself; the constructor refuses it.
2. It calls every module's `simulation` hook. Each one registers system *factories* into a `SimRegistry`, with a `SimPhase` and optional `before`/`after` constraints.
3. It resolves the order: phase first, then a stable topological sort inside each phase. A cycle, a constraint that names a missing system, or a constraint that contradicts the phases throws a `SystemOrderException`.
4. It calls every module's `context` hook to build the `GameContext`. A later module may replace a service an earlier one supplied; that is how `Physics2DModule` swaps the kernel's `NoOpPhysicsWorld` for Box2D.
5. It builds the Fleks world and constructs each system by calling its factory with the built context. There is no reflection and no no-arg constructor requirement: a factory is a normal call the compiler checks.
6. It builds the snapshot ring, if a `TimeTravelFactory` was given, then collects each module's `level` hooks into a `LevelService`.

Here is how `moba` declares its systems. It is an excerpt of `MobaModule.simulation` in `moba/game/src/commonMain/kotlin/dev/wildware/moba/MobaModule.kt`:

```kotlin
override fun simulation(registry: SimRegistry) {
    registry.add(
        SimPhase.Intent,
        { ctx -> PlayerControlSystem(ctx[IntentState.KEY], combat.gas.activation) },
    ) { after(IntentSampleSystem::class) }
    registry.add(SimPhase.Movement, { PlayerMovementSystem() })
    registry.add(SimPhase.Gameplay, { UnitBattleSystem() })
    registry.add(SimPhase.Cleanup, { CharacterStateSystem(combat.effects) })
    registry.add(SimPhase.Cleanup, { CharacterAnimationSystem() }) {
        after(CharacterStateSystem::class)
    }
}
```

### The phases

`SimPhase` is the fixed running order of one tick. Every system in an earlier phase runs before every system in a later one:

`Intent` → `PreSimulation` → `Ability` → `Attribute` → `Movement` → `Physics` → `PostPhysics` → `Gameplay` → `Replication` → `Cleanup`

- `Intent`: input becomes intent components. Nothing else writes intent.
- `PreSimulation`: queued commands such as teleports, spawns and despawns.
- `Ability` and `Attribute`: `udea-gas` activates abilities, then recomputes attributes.
- `Movement`: `CharacterMover`, the authoritative movement model.
- `Physics` and `PostPhysics`: the solver steps one tick, then its results are read.
- `Gameplay`: game rules such as damage, scoring and objectives. Most game systems go here.
- `Replication`: capture and outbound replication, after every writer has run.
- `Cleanup`: destruction and per-tick resets.

### The context

`GameContext` is what every system is built against. It holds a small fixed set of engine services: `clock`, `config`, `role`, `rng`, `physics`, `scenes`, `cues` and `log`. Anything else is a typed service under a `ServiceKey`, contributed by a module's `context` hook and read with `ctx[KEY]`. For example, `GasServices.KEY` holds the ability system. Adding a field to `GameContext` itself needs a written justification (`docs/engineering-standards.md` section 8).

### Generated registries

Each module that runs `udea-codegen` gets one generated `object <Module>ModuleRegistry` in `dev.wildware.udea.generated`. Each launcher gets one `<Module>UdeaRegistry` that names every module registry on its runtime classpath, in sorted order. `moba` passes `MobaUdeaRegistry` to its `UdeaGameDef`.

A registry is how a module's generated parts reach the game without scanning the classpath at run time. The replicators, the saveable level components and the agent tools all travel this way. A module a game depends on cannot be left out quietly: the processor refuses a listed module whose registry does not exist, so the omission is a compile error. [ECS and Components](ECS-and-Components) covers what goes into a registry.

## The moba game, as a worked example

`MobaGame.definition()` in `moba/game/src/commonMain/kotlin/dev/wildware/moba/MobaGame.kt` is the one place the example game is assembled. Its module list is:

```kotlin
modules = listOf(
    InputModule(MobaControls.BINDINGS),
    module,                       // MobaModule: units, fighting, animation
    combat,                       // MobaAbilityModule: the game's GAS content
    MatchModule(combat.attributes),
    LaneModule(combat),
    ItemModule(ItemCatalog.read(MobaAssets.registry), combat),
    RenderModule(),
) + extraModules,
```

Every entry point builds this same list: the server, the client, the agent and every test. `RenderModule` is included even on the headless server, because it contributes one simulation system and the modes must run the same simulation. The order matters in two places: `LaneModule` comes after `MatchModule`, and `ItemModule` after `LaneModule`, because each declares an `after(...)` constraint on a system the earlier module contributed.

## See also

- [Tick Model and Determinism](Tick-Model-and-Determinism)
- [ECS and Components](ECS-and-Components)
- [Rendering with Kool](Rendering-with-Kool)
- [Build and Verification](Build-and-Verification)
- [Example Games](Example-Games)
