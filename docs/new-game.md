# A new game, in its own repository

A game built with Udea is a repository of its own, and it reaches the engine the way it reaches
any other library: `dev.wildware.udea:udea-core` and friends, resolved from a Maven repository,
at **one pinned version** it names in `gradle.properties`. Nothing in your build names a path to a
Udea checkout, and you do not need one.

Until Udea's first release that version is a snapshot, and it is pinned anyway - see "Where the
engine comes from, and which one" below, which is the section to read before the first build.

`templates/new-game/` in this repository is a working game of that shape - copy it, rename it, and
it builds. `scripts/outside-game-proof.sh` does exactly that on every run, from a directory
outside this tree, so the instructions below are executed rather than described.

```sh
cp -r <udea>/templates/new-game ../my-game
cd ../my-game
gradle wrapper --gradle-version 8.13      # a wrapper of your own, once
./gradlew build
./gradlew run
```

The engine needs **JDK 21** to run Gradle (`JAVA_HOME`), and the Kotlin toolchain it asks for is
provisioned by the foojay resolver the template's settings script applies.

### Where the engine comes from, and which one

**You build against a pinned snapshot.** Udea has no release yet, so the engine is published as
`X.Y.0-SNAPSHOT` to Central's snapshot repository, and the template names one version -
`udeaVersion` in `gradle.properties`:

```properties
udeaVersion=0.1.0-SNAPSHOT
```

**That number changes when the owner says so, and not before.** A snapshot coordinate is mutable,
which makes it tempting to think a game automatically gets the newest engine. It does not, and it
should not: the engine's tip moves several times a day, and a game that followed it would break
on somebody else's half-finished refactor. Pinning it means the only thing that changes what your
game compiles against is you editing that line.

The repositories the template declares, in the order it declares them:

| Repository | What it is for |
|---|---|
| `mavenLocal()` | an engine you published yourself, which wins over everything below |
| `mavenCentral()` | releases, once there are any, and every third-party dependency |
| `central.sonatype.com/repository/maven-snapshots/` | Udea's snapshots, and ComposeGL's |
| the two `oss.sonatype.org` hosts | Sonatype's older snapshot hosts, not yet retired |

`mavenLocal()` first is deliberate: it is how you try an engine change without publishing it.

```sh
cd <udea>
./gradlew publishToMavenLocal                  # the engine's modules
./gradlew -p build-logic publishToMavenLocal   # the convention plugins and the version catalog
```

Two commands, because `build-logic` is an included build of the engine and the outer one does not
reach it. It is the half that carries `dev.wildware.udea.game-gates`, `dev.wildware.udea.kotlin-library` and
`dev.wildware.udea.agent`, so without it a game can resolve every engine module and still not
apply a single plugin.

### Getting a newer engine

Two steps, both deliberate, and neither happens on its own:

1. **The owner publishes a snapshot.** In the Udea repository: Actions, "Release", "Run
   workflow", `kind = snapshot`. That builds the engine, signs it, and pushes
   `X.Y.0-SNAPSHOT` to Central's snapshot repository. No tag, nothing permanent - a snapshot can
   be overwritten. (`patch`, `minor` and `major` make a real release instead: those *upload and
   stop*, and a person presses publish in the separate `central-publish` workflow, because a
   version on Central can never be deleted or replaced.)
2. **The game bumps `udeaVersion`.** One line in `gradle.properties`, in a commit of its own, so
   "we moved to a new engine" is a thing that appears in the game's history.

**Gradle keeps a snapshot it has already resolved for 24 hours**, so step 2 is not always enough
when the version number has not changed - you ask for `0.1.0-SNAPSHOT`, and you get the copy in
the cache rather than the one published an hour ago. This surprises everybody once. The escape is
one flag:

```sh
./gradlew build --refresh-dependencies
```

The engine's own build meets exactly this with ComposeGL, and `gradle/libs.versions.toml` says so
where the ComposeGL version is declared.

---

## The four files that make it a Udea game

### `settings.gradle.kts` - which engine, and where from

```kotlin
pluginManagement {
    val udeaVersion: String = providers.gradleProperty("udeaVersion").get()
    repositories { mavenLocal(); mavenCentral(); gradlePluginPortal(); google() }
    plugins {
        id("dev.wildware.udea.kotlin-library") version udeaVersion
        id("dev.wildware.udea.game-gates") version udeaVersion
        id("dev.wildware.udea.agent") version udeaVersion
        // ...
    }
}
```

The version is stated **once**, in `gradle.properties` as `udeaVersion`, and everything else reads
it: the plugin ids here, the module coordinates in `game/build.gradle.kts`, and the K2 compiler
plugin the convention puts on your compilations. Upgrading the engine is that one line.

The convention plugins come from `dev.wildware.udea:udea-build-logic`, which publishes a plugin
marker per published id - that is why `id("dev.wildware.udea.game-gates")` resolves at all. A
Gradle plugin has to be on the settings classpath before any build script is evaluated, which is
why the version is declared here rather than in the build script that applies it.

A marker's coordinates *are* the plugin id: the group is the id and the artifact is
`<id>.gradle.plugin`. Sonatype authorises a publisher per namespace, so only ids inside
`dev.wildware` can be published at all, and that is the whole difference between the two kinds of
convention plugin in the engine's `build-logic`. **`dev.wildware.udea.*` is published and you may
apply it; `udea.*` is internal to the engine's own build and you cannot.**
`templates/new-game/settings.gradle.kts` is the published set, and `docs/module-graph.md` lists
every convention with its id.

The catalog is the engine's, published as `dev.wildware.udea:udea-version-catalog` and imported as
`libs`: the conventions read Kotlin, `kotlin-test`, JUnit and the Android SDK levels out of it by
alias. Copying those numbers into a catalog of your own is a second Kotlin version waiting to
disagree with the compiler the engine was built with; add a *second* catalog for your game's own
dependencies instead.

```kotlin
versionCatalogs {
    create("libs") { from("dev.wildware.udea:udea-version-catalog:$udeaVersion") }
}
```

It carries the engine's versions and no `udea-*` aliases, deliberately: which engine release your
game is on is your decision, and an alias inside the engine's own catalog would pin it to whatever
that catalog was published from.

One line in the template is worth understanding before you delete it:

```kotlin
repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
```

The conventions declare `mavenCentral()` on each project they are applied to, and Gradle's default
(`PREFER_PROJECT`) then uses *that* and ignores the settings block - so `mavenLocal()` and the
snapshot repository declared in settings are never searched, and the failure names a missing
artifact rather than a repository mode. The template declares a superset, and needs every line of
it: `mavenLocal()` and the snapshot hosts are where an unreleased engine is, and ComposeGL, which
`udea-render` draws with, publishes only snapshots too. Any game that reaches `udea-render` -
including one that only uses the agent surface, since `udea-agent-host` depends on it - needs
those snapshot repositories or resolution fails.

### `build.gradle.kts` - what this build is

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

`dev.wildware.udea.game-gates` is the one plugin that carries Udea's build gates, and this repository's own
root build script applies it and writes the same block. It registers:

| Gate | What it refuses |
|---|---|
| `udeaVerifyModuleGraph` | a banned arrow on any project of the build - GL outside the renderer, the editor on a shipped classpath, a Kotlin scripting host or a classpath scanner in a shipped game (`UDEA-MG-005`), the FBX converter at run time (`UDEA-MG-013`). `docs/module-graph.md` has every id |
| `udeaVerifyDeterminism` | a wall-clock read, unseeded randomness, hash-ordered iteration or a device read in the packages `simulation(...)` declares |
| `udeaVerifyEditorAbsent` | an editor class or a `Gizmo` on a release classpath |
| `udeaVerifyRelease` | the agent surface inside the jar or on the runtime classpath of a `-Pudea.release=true` build of a project `ships(...)` names |
| `udeaVerifyKotlinPin`, `udeaVerifyCompilerPlugin` | a `kotlin-stdlib` other than the catalog's, and the K2 checkers silently not running |

Every one of them is on `check`, so `./gradlew build` runs them.

**`simulation(...)` is a declaration, not a guess.** Membership is never inferred from a module
name, and the `why` is read in review - a scope with a one-word reason is refused at configuration
time. Narrow with `packagePrefixes` so a HUD, a renderer or an audio mixer, where seconds and
wall-seeded randomness are *correct*, is outside the scan. A gate that fires on those is a gate
people switch off.

### `game/build.gradle.kts` - the module

```kotlin
plugins {
    id("dev.wildware.udea.kotlin-library")            // or dev.wildware.udea.kotlin-multiplatform-render, if it draws
    id("dev.wildware.udea.agent")        // gamebridge.json and the debug-only agent source set
    id("com.google.devtools.ksp") version libs.versions.ksp.get()
}

group = "com.example"

val udeaVersion: String = providers.gradleProperty("udeaVersion").get()

dependencies {
    implementation("dev.wildware.udea:udea-core:$udeaVersion")
    implementation("dev.wildware.udea:udea-annotations:$udeaVersion")
    "agentImplementation"("dev.wildware.udea:udea-agent-host:$udeaVersion")
    ksp("dev.wildware.udea:udea-codegen:$udeaVersion")
}

val udeaRegistry = udeaModule("NewGame")

ksp {
    arg(UdeaModuleRegistry.MODULE_NAME_OPTION, udeaRegistry.name)
    arg(UdeaModuleRegistry.REGISTRY_MODULES_OPTION, udeaRegistry.registryModules)
}
```

- No plugin id carries a version: `settings.gradle.kts` states it once. The module coordinates
  read the same `udeaVersion` property, so the plugins and the modules cannot be two different
  releases of the engine.
- `group` and `version` are yours. The conventions deliberately set neither - a convention that
  named the engine's coordinates would publish your game as a module of Udea.
- `udeaModule("<Name>")` names this module's generated registry. KSP writes
  `<Name>ModuleRegistry` and `<Name>UdeaRegistry`; `UdeaGameDef` takes the second, and that is
  what a level file, a snapshot and the agent's field access all read a component's identity out
  of. There is no `ServiceLoader` anywhere near it.
- The agent surface is on a source set of its own. `jar` packages `main`, `runtimeClasspath` never
  resolves `udea-agent-host`, and `udeaVerifyRelease` checks both - so a debug HTTP surface over
  your live simulation cannot reach a player by being forgotten about.

### `game/src/main/kotlin/.../NewGame.kt` - the game

A module lists systems; a definition is modules plus the generated registry; a host runs it.

```kotlin
public class NewGameModule : UdeaModule {
    override fun simulation(registry: SimRegistry) {
        registry.add(SimPhase.Movement, { RoverSystem() })
    }
}

public object NewGame {
    public fun definition(): UdeaGameDef =
        UdeaGameDef(registry = NewGameUdeaRegistry, modules = listOf(NewGameModule()))
}
```

Every entry point builds the same definition, which is what makes "it happens on the server and
not in my client" a bug in a renderer rather than a difference between two simulations. A system
extends `SimSystem`, reads `ctx.clock`, and writes component state; presentation is **not** a
Fleks system - it implements `RenderSystem` in `udea-render`, so `world.update(dt)` is pure
simulation by construction.

---

## An agent can drive your game

`dev.wildware.udea.agent` writes `gamebridge.json` at the root of your repository on every
`assemble`, and that is the whole of what `game-bridge-mcp` needs to find and launch the game:

```json
{
  "name": "new-game",
  "launch": {
    "command": "./gradlew :game:run -PdebugPort={port} --console=plain",
    "cwd": ".",
    "portRange": "7860-7879",
    "readyTimeoutMs": 180000,
    "env": {}
  }
}
```

**Pick a port range of your own.** The engine's default is `7820-7839` and `moba` takes
`7840-7859`. A bridge does not check whose game answered a port - it lists whatever is listening
in its range - so two games sharing a range means either one's bridge can enumerate, drive and
stop the other's instance, and that looks like an instance vanishing rather than like a
misconfiguration:

```kotlin
udeaAgent {
    name.set("new-game")
    portRange.set("7860-7879")
}
```

`./gradlew run -PdebugPort=7861` then starts an instance with the MCP tool surface on 7861:
`/health`, `/state`, `/command` and a generated `/tools`. The template's instance is `Headless`,
so `world.*`, `time.*` and `events.*` answer and every `render.*` tool answers
`no_render_context` - which is the contract working. A game that draws gets the render tools by
running `Offscreen`; `moba/desktop/src/agent` is the worked example.

---

## What the template does not cover yet

Stated rather than implied, because each is a real piece of work and none of it is broken:

- **Replicated components and the wire id space.** A `@Replicated` component gets its
  `ComponentTypeId` from the position of its name in the build's `net-components.lock`, and that
  file is the *whole* build's id space. A game outside this repository therefore needs a lock that
  covers the engine's components as well as its own; the engine's is `net-components.lock` at the
  root of the Udea repository, and it is not published with the artifacts. Nothing merges the two
  for you today, so a game with replicated components has to carry a lock that starts from the
  engine's. The template declares no `@Replicated` component and so needs no lock at all.
- **The asset pipeline.** `dev.wildware.udea.assets` compiles a `.udea.kts` tree into a
  `.udeapak`. `moba/game/build.gradle.kts` is the worked example; the template has no assets, and
  whether that plugin needs anything extra outside this repository is untested.
- **Drawing.** The template is headless. A game that draws applies
  `dev.wildware.udea.kotlin-multiplatform-render` and opens a `KoolBackend`; `moba` is the example, and the
  snapshot repository note above is the part that bites first.
- **`AGENTS.md` and the frozen contracts.** `udeaVerifyAgentsMd` holds *this* repository's module
  table against *this* repository's `settings.gradle.kts` and requires the nine spec section 5
  contracts to be named; `udeaVerifyContracts` freezes `docs/contracts/`. Both are statements
  about the engine's own documents, so `dev.wildware.udea.game-gates` does not apply either to a game. Your
  game's `AGENTS.md` is yours.
