# Tutorial: Make a Game

This walks you from nothing to a running Udea game you can drive, test and extend. It takes about
twenty minutes, and the result is a repository of its own — it does not live inside the engine's
checkout and does not need one.

The engine repository has the authoritative version of this: **`docs/new-game.md`**, and a working
game at **`templates/new-game/`** that `scripts/outside-game-proof.sh` builds and runs from outside
the tree on every run. Those are executed rather than described. This page is the friendlier walk
through the same ground, and where the two differ, `docs/new-game.md` is right.

## Before you start

- **JDK 21.** Gradle 8.13 does not support 25, and the error if you get it wrong is one line long
  with no mention of Java. Point `JAVA_HOME` at a 21.
- **Gradle 8.13** once, to make a wrapper. After that, the wrapper.

## Step 1: copy the template

```sh
cp -r <udea>/templates/new-game ../my-game
cd ../my-game
gradle wrapper --gradle-version 8.13
./gradlew build
./gradlew run
./gradlew runWindow
```

`run` simulates 600 ticks headless and prints where its three rovers ended up. Deterministic, so two
runs print the same thing. `runWindow` is the same game in a window: the three rovers driving east
east under a sky. There is no ground drawn yet - the models and the sky are all of it.

Then rename things. Grep for `new-game`, `NewGame` and `com.example.newgame` and you will find them
all; the ones that matter are `rootProject.name` in `settings.gradle.kts`, `group` in
`game/build.gradle.kts`, the package itself, the `udeaAgent { name, portRange, flagsPackage }` block,
the `udeaModule("NewGame")` call, and `udeaGates`' `packagePrefixes`.

## Step 2: which engine, and where from

**The engine version is pinned in exactly one place**, `gradle.properties`:

```properties
udeaVersion=0.1.0-SNAPSHOT
```

Everything reads it: the plugin ids in `settings.gradle.kts`, the module coordinates in
`game/build.gradle.kts`, and the K2 compiler plugin the convention puts on your compilations.
Upgrading the engine is that one line.

**That number changes when the owner publishes a new snapshot and says it is the one to build
against — and not before.** A snapshot coordinate is mutable, which makes it tempting to think a game
automatically gets the newest engine. It does not, and it should not: the engine's tip moves several
times a day, and a game that followed it would break on somebody else's half-finished refactor.

Two things have to happen for a game to move forward, and neither happens on its own:

1. The owner runs the engine's Release workflow with `kind = snapshot`, which pushes
   `X.Y.0-SNAPSHOT` to Central's snapshot repository. (`patch`, `minor` and `major` make a real
   release instead — those upload and stop, and a person presses publish separately, because a
   version on Central can never be deleted or replaced.)
2. The game edits `udeaVersion`, in a commit of its own, so "we moved to a new engine" appears in the
   game's history.

**Nothing has been published yet.** So today you publish the engine to your own machine first:

```sh
cd <udea>
./gradlew publishToMavenLocal                  # the engine's modules
./gradlew -p build-logic publishToMavenLocal   # the convention plugins and the version catalog
```

Two commands, because `build-logic` is an included build and the outer one does not reach it. It is
the half that carries `dev.wildware.udea.game-gates`, `dev.wildware.udea.kotlin-library` and `dev.wildware.udea.agent`, so
without it your game resolves every engine module and cannot apply a single plugin. `mavenLocal()` is
first in every repository list, so what you publish there wins.

One flag you will want once: **Gradle keeps a snapshot it has already resolved for 24 hours.** So
after a republish under the same version number you get the cached copy.

```sh
./gradlew build --refresh-dependencies
```

This surprises everybody exactly once.

## Step 3: the four files

### `settings.gradle.kts` — which engine, and where from

States `udeaVersion` once in `pluginManagement`, lists the repositories, and imports the engine's
version catalog as `libs`.

One line is load-bearing:

```kotlin
repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
```

The conventions declare `mavenCentral()` on each project they apply to, and Gradle's default
(`PREFER_PROJECT`) then uses *that* and ignores your settings block — so `mavenLocal()` and the
snapshot repositories are never searched, and the failure names a missing artifact rather than a
repository mode. The template declares a superset and needs every line of it.

Add your own dependencies to a **second** catalog (`create("game") { ... }`) rather than editing the
engine's. Copying the engine's numbers into a catalog of your own is a second Kotlin version waiting
to disagree with the compiler the engine was built with.

### `build.gradle.kts` — what this build is

```kotlin
plugins { id("dev.wildware.udea.game-gates") }

udeaGates {
    ships(":game")
    simulation(
        project = ":game",
        packagePrefixes = listOf("com.example.newgame.sim"),
        why = "The game's own rules: what moves, what it collides with and what decides the " +
            "outcome of a tick.",
    )
}
```

`dev.wildware.udea.game-gates` is the plugin that gives your game Udea's build gates — the same ones the engine's
own root build script applies to itself:

| Gate | Refuses |
|---|---|
| `udeaVerifyModuleGraph` | a banned arrow: GL outside the renderer, the editor on a shipped classpath, a scripting host or classpath scanner in a shipped game |
| `udeaVerifyDeterminism` | a wall-clock read, unseeded randomness, hash-ordered iteration or a device read in the packages `simulation(...)` names |
| `udeaVerifyEditorAbsent` | an editor class or a `Gizmo` on a release classpath |
| `udeaVerifyRelease` | the agent surface in the jar or on the runtime classpath of a `-Pudea.release=true` build |
| `udeaVerifyKotlinPin`, `udeaVerifyCompilerPlugin` | a `kotlin-stdlib` other than the catalog's; the K2 checkers silently not running |

All of them run on `check`, so `./gradlew build` runs them.

**`simulation(...)` is a declaration, not a guess.** Membership is never inferred from a module name,
and a `why` of one word is refused at configuration time. Narrow it with `packagePrefixes` so a HUD,
a renderer or an audio mixer — where seconds and wall-seeded randomness are *correct* — is outside
the scan. A gate that fires on those is a gate people switch off.

### `game/build.gradle.kts` — the module

```kotlin
plugins {
    id("dev.wildware.udea.kotlin-library")
    id("dev.wildware.udea.agent")
    id("com.google.devtools.ksp") version libs.versions.ksp.get()
    id("dev.wildware.udea.assets")
}

group = "com.example"
version = "0.1.0"

val udeaVersion: String = providers.gradleProperty("udeaVersion").get()

dependencies {
    implementation("dev.wildware.udea:udea-core:$udeaVersion")
    implementation("dev.wildware.udea:udea-annotations:$udeaVersion")
    implementation("dev.wildware.udea:udea-assets:$udeaVersion")
    implementation("dev.wildware.udea:udea-render:$udeaVersion")
    "udeaAssetsCompiler"("dev.wildware.udea:udea-assets-compiler:$udeaVersion")
    "agentImplementation"("dev.wildware.udea:udea-agent-host:$udeaVersion")
    ksp("dev.wildware.udea:udea-codegen:$udeaVersion")
}

udea {
    assetRoots.from("assets")
    kotlinVersion.set(libs.versions.kotlin.get())
}

udeaAgent {
    name.set("new-game")
    portRange.set("7860-7879")
    flagsPackage.set("com.example.newgame.agent")
}

val udeaRegistry = udeaModule("NewGame")

ksp {
    arg(UdeaModuleRegistry.MODULE_NAME_OPTION, udeaRegistry.name)
    arg(UdeaModuleRegistry.REGISTRY_MODULES_OPTION, udeaRegistry.registryModules)
}
```

- **No version on any `dev.wildware.udea.*` plugin id.** Settings states it once, and the module coordinates read
  the same property, so the plugins and the modules cannot be two different releases of the engine.
- **`group` and `version` are yours.** The conventions deliberately set neither — a convention that
  named the engine's coordinates would publish your game as a module of Udea.
- **`udeaModule("NewGame")`** names the generated registry. KSP writes `NewGameModuleRegistry` and
  `NewGameUdeaRegistry`; `UdeaGameDef` takes the second. That is what a level file, a snapshot and
  the agent's field access all read a component's identity out of. There is no `ServiceLoader`
  anywhere near it.
- **Pick a port range of your own.** The engine default is `7820-7839` and `moba` takes `7840-7859`.
  A bridge does not check whose game answered a port, so two games sharing a range means either
  one's bridge can drive and stop the other's instance — and that looks like an instance vanishing
  rather than like a misconfiguration.
- **The asset lines are a set**: the `dev.wildware.udea.assets` plugin, `udea-assets`, the
  `udeaAssetsCompiler` configuration (the compiler the plugin runs in a process of its own - never
  `implementation`, because it carries the Kotlin compiler) and the `udea { assetRoots }` block.
- **`udea-render` is the window.** A JVM project draws by depending on it. Apply
  `dev.wildware.udea.kotlin-multiplatform-render` instead of `dev.wildware.udea.kotlin-library`
  when the game ships on more than one platform.

### `NewGame.kt` — the game

The template's copy is
`templates/new-game/game/src/main/kotlin/com/example/newgame/NewGame.kt`; in the repository you
copied it into, the same path with that `templates/new-game/` prefix dropped.

A module lists systems; a definition is modules plus the generated registry; a host runs it.

```kotlin
public class NewGameModule(private val assets: AssetRegistry) : UdeaModule {

    override val name: String get() = "new-game"

    override fun simulation(registry: SimRegistry) {
        registry.add(SimPhase.Movement, { RoverSystem(assets) })
    }
}

public object NewGame {

    public fun definition(assets: AssetRegistry = NewGameAssets.registry): UdeaGameDef = UdeaGameDef(
        registry = NewGameUdeaRegistry,
        modules = listOf(NewGameModule(assets), RenderModule()),
    )

    public fun host(mode: RenderMode): GameHost = GameHost(mode, definition())
}
```

`NewGameAssets.registry` is the game's packed assets, read off the classpath; `RenderModule` records
where each `Transform3D` stood at the end of every tick, which a window draws between ticks from. It
draws nothing itself, so a server runs it too, and runs the same simulation its clients do.

**Every entry point builds the same definition** — a dedicated server, a player's client, an agent's
instance, a replay. That is what makes "it happens on the server and not in my client" a bug in a
renderer rather than a difference between two simulations.

## Step 4: a component

A component is a plain Fleks component. Nothing else is required — the template's carries two
annotations because it travels, and the next paragraph is about those.

```kotlin
@Replicated
public class Rover(
    @Net public var x: Float = 0f,
    @Net public var y: Float = 0f,
    @Net public var speed: Float = 1f,
) : Component<Rover> {

    override fun type(): ComponentType<Rover> = Rover

    public companion object : ComponentType<Rover>()
}
```

`@Replicated` from `dev.wildware.udea.annotations` is what makes it travel to a client and into a
snapshot, and the template's `Rover` carries it with `@Net` on each field. It is a step with a wire
contract attached: the id a component takes on the wire is the position of its name in
`net-components.lock`, a sorted list in your repository's root, so inserting a name renumbers every
name after it. Nothing goes in a build script — the conventions read that file and hand the list to
the processor, and `./gradlew udeaWriteNetComponents` writes it the first time. "Replicated
components and the wire id space" in [docs/new-game.md](https://github.com/wildware-uk/Udea/blob/master/docs/new-game.md)
is the full account.

## Step 5: a system

The template's `RoverSystem` spawns three rovers once, then moves each one east by its own speed
every tick, round a field of a fixed width. Its tick is the shape every rule here has:

<!-- quoted from templates/new-game/game/src/main/kotlin/com/example/newgame/sim/RoverSystem.kt -->
```kotlin
override fun onTick() {
    val entities = rovers.entities
    val dt = ctx.clock.dt
    var index = 0
    while (index < entities.size) {
        val entity = entities[index]
        val rover = entity[Rover]
        rover.x += rover.speed * dt
        if (rover.x > FIELD_HALF_WIDTH) rover.x -= 2f * FIELD_HALF_WIDTH
        val at = entity[Transform3D]
        at.x = rover.x
        at.y = rover.y
        index++
        updates++
    }
}
```

Read the tick's delta off the clock, write component state, return. It never reads the wall clock
and never draws an unseeded random number, because `udeaVerifyDeterminism` scans this package and
fails the build on either — a simulation that does one of those cannot rewind, cannot replay and
cannot agree with a server.

**What a rover looks like is said here too.** Each one is spawned with the engine's `Drawn`, naming
the model it is drawn with, and a `Transform3D`, where it stands - the `at.x = rover.x` above:

<!-- quoted from templates/new-game/game/src/main/kotlin/com/example/newgame/sim/RoverSystem.kt -->
```kotlin
world.entity {
    it += rover
    it += Transform3D(x = rover.x, y = rover.y)
    it += Drawn(GameAssets.models.rover, assets)
}
```

`GameAssets.models.rover` is generated from `game/assets/models/models.udea.kts`, which declares
`model(name = "rover", file = "models/rover.glb")`. The `models` in the accessor is the folder the
**`.udea.kts`** is in: the same line in a script at the top of `game/assets/` would be
`GameAssets.root.rover`. Both components are plain data, so a server carries them and draws nothing,
and a window reads them and draws the model.

`SimPhase` decides ordering. `Movement` is one of them; see [Architecture](Architecture) for the set.

**Presentation is not a Fleks system.** It implements `RenderSystem` (or `OverlaySystem`) in
`udea-render`, so `world.update(dt)` is pure simulation *by construction* rather than by convention.
The template's is `NewGameScene`: a camera, a light, `ModelRenderSystem` over the model files, a sky
and a screen effect, declared on a `RenderRegistry` that `NewGameWindow` opens a window over. See
[Rendering with Kool](Rendering-with-Kool).

## Step 6: a test

No harness of your own. `GameHost` in `RenderMode.Headless` *is* the harness — the same simulation a
player's client runs, with nothing drawing it.

```kotlin
class RoverSystemTest {

    @Test
    fun `a rover moves east by its own speed, once per tick`() {
        val host = GameHost(RenderMode.Headless, NewGame.definition())

        host.run(TICKS)

        val rovers = host.world.system<RoverSystem>()
        assertEquals(RoverSystem.ROVERS, rovers.positions().size, "every rover is still there")
        val distances = rovers.positions()
        assertTrue(distances[0] < distances[1], "a faster rover has gone further: $distances")
        assertTrue(distances[0] > 0f, "the first rover moved at all: $distances")
    }

    private companion object {
        const val TICKS: Int = 120
    }
}
```

Note what it asserts: an *ordering*, not an exact float. The point is that speed decides distance.

Break the production code and watch the test go red before you believe it. A test you have not seen
fail is unverified.

## Step 7: let an agent drive it

`dev.wildware.udea.agent` writes `gamebridge.json` at the root of your repository on every
`assemble`, and that is the whole of what `game-bridge-mcp` needs:

```json
{
  "name": "new-game",
  "launch": {
    "command": "./gradlew <project path>:run -PdebugPort={port} --console=plain",
    "cwd": ".",
    "portRange": "7860-7879",
    "readyTimeoutMs": 180000,
    "env": {}
  }
}
```

`<project path>` is the Gradle path of whichever project applies the plugin — the `game` module
in the template — and `{port}` is the bridge's own placeholder, which it fills in when it launches.

Then:

```sh
./gradlew run -PdebugPort=7861
curl 'http://127.0.0.1:7861/health'
curl 'http://127.0.0.1:7861/tools'
```

The template's launcher is in `game/src/agent/`, a source set of its own, and it wires the toolsets
it has the pieces for:

```kotlin
val tools: ToolIndex = EngineToolModules
    .wireAll(
        ToolIndex.builder(),
        TimeToolset(host.time, host.ctx.clock, bridge),
        EventsToolset(bridge, host.ctx.clock),
        LifecycleToolset(bridge, shutdown),
    )
    .build()
```

`WorldToolset` is deliberately absent: it needs an `AgentComponentIndex` over `@Replicated`
components, and the template declares none.
`moba/desktop/src/agent/kotlin/dev/wildware/moba/agent/MobaAgent.kt` is the
worked example that wires it.

The template's instance is `Headless`, so `time.*` and `events.*` answer, and a render tool would
answer `no_render_context` — **which is the contract working**, not a fault. An agent instance that
draws gets the render tools by running `Offscreen`; `MobaAgent` is the worked example of that too.

**Why the agent code is in `src/agent` and not `src/main`:** `udea-agent-host` binds an HTTP port
onto the live simulation and can write any field of any entity. That is a thing a developer wants and
a player must never get. The `agent` source set is the only classpath that resolves it, `jar`
packages `main`, and `udeaVerifyRelease` checks both — so the guarantee is the module graph's rather
than a promise in a comment. A `compileOnly` dependency would not do it: that ships a `main` class
whose first statement throws `NoClassDefFoundError`, and calls it absence.

Full detail: [Agent Tool Surface](Agent-Tool-Surface).

## Step 8: prove your gates can fail

A gate nobody has seen fail is indistinguishable from one that cannot. Plant a
`System.currentTimeMillis()` in your simulation package and run `./gradlew build` — it must fail with
`DET001`. Put it back.

That is what `scripts/outside-game-proof.sh` does for the template, among its other legs: it plants
a wall-clock read and watches `udeaVerifyDeterminism` fail with `DET001`, and plants a Kotlin
scripting host and watches `udeaVerifyModuleGraph` fail with `UDEA-MG-005`. It also opens the
template's window on a virtual display, leaves it drawing for fifteen seconds, photographs it, and
requires the rover to be in the picture - then takes the rover's model away and requires the same
check to fail, and separately makes the window close itself early and requires the fifteen-second
wait to refuse that too.

## What this does not cover yet

Stated plainly, because each is real work and none of it is broken.

- **Sharing an id space with the engine's own components.** Your `net-components.lock` numbers the
  components *your* build compiles, from 0. The engine's were numbered in the engine's build and
  those ids are already inside the published jars, so the two sit side by side rather than being
  merged, and a game with more components than the engine's lowest id would eventually mint an id
  the engine has used. Nothing merges them for you today. A collision is caught where a game builds
  a `ComponentRegistry`, which is when it wires replication — before that, nothing would say so.
- **Levels.** `.udealevel` files and `-Plevel=<path>` work inside the engine's repository; the
  template has none. See [Levels](Levels).
- **More than one platform.** The template's window is a desktop window from one JVM project. A
  game that also ships on Android splits into a `game` library on
  `dev.wildware.udea.kotlin-multiplatform-render` and a launcher per platform; `moba` is that shape.
- **A shipped model file.** The packed bundle carries each model's record, not its bytes, so
  `runWindow` hands the renderer the `assets/` directory to read `rover.glb` from. A distribution
  has to carry that directory beside the jar.
- **`AGENTS.md` and the frozen contracts.** `udeaVerifyAgentsMd` and `udeaVerifyContracts` are
  statements about the *engine's* own documents, so `dev.wildware.udea.game-gates` applies neither to a game. Your
  game's `AGENTS.md` is yours.

## See also

- [Getting Started](Getting-Started) — building the engine repository itself
- [Example Games](Example-Games) — `moba` and Hollow, the two worked examples
- [Architecture](Architecture) — modules, `UdeaGameDef`, `SimPhase` and the registries
- [ECS and Components](ECS-and-Components) — components, `@Replicated`, and the generated code
- [Tick Model and Determinism](Tick-Model-and-Determinism) — why `udeaVerifyDeterminism` exists
- [Agent Tool Surface](Agent-Tool-Surface) — driving your game from a program
- [Build and Verification](Build-and-Verification) — every gate in detail
