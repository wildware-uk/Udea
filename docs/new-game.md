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

### A component that travels: `@Replicated` and `net-components.lock`

`@Replicated` on a component generates a `Replicator` for it, and that one codec serves four
things that must never disagree about what an entity is: a delta packet to a client, a snapshot
for `time.rewind`, a snapshot restore, and the agent's field access. The template's `Rover` has
it, and each of its fields carries `@Net`:

```kotlin
@Replicated
public class Rover(
    @Net public var x: Float = 0f,
    @Net public var y: Float = 0f,
    @Net public var speed: Float = 1f,
) : Component<Rover>
```

The id that codec stamps on the wire is **the position of the component's name in
`net-components.lock`**, a plain sorted list in your repository's root. That is the whole of the
id space: inserting a name renumbers every name after it, and every connected client and recorded
replay with it. It is a reviewed file rather than something a processor counts out, for exactly
that reason.

**You do not wire it up.** `dev.wildware.udea.kotlin-library` and the multiplatform conventions
read that file and hand the list to the processor, for every module of your build that runs KSP.
Nothing goes in a `ksp { }` block.

Your first `@Replicated` component does need the file to exist, and there is a task that writes
it:

```
./gradlew build                      # fails: this module emits a wire protocol and has no id space
./gradlew udeaWriteNetComponents     # writes net-components.lock from what the build compiled
git diff net-components.lock         # review it: a name's position is its id on the wire
./gradlew build
```

That works on the build that just failed, deliberately: the processor writes each module's list
of `@Replicated` names *before* it checks the id space, so the run that fails for want of the
file still leaves behind what the file needs. The task only ever **adds** - a name stays once it
is there, because a name may legitimately be listed before its component exists, and a module
that failed to compile reports nothing at all. Removing a name is a hand edit.

If you keep the file somewhere else, say so once, in the **root** build script:

```kotlin
udeaNetComponents {
    registry = layout.projectDirectory.file("wire/components.lock")
}
```

Root, because the id space is the whole build's - one file gives every `@Replicated` component in
your build its id, and a per-module answer could only disagree with itself.

One thing this does **not** do yet: merge your id space with the engine's. See "What the template
does not cover yet" below.

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

## Giving your game a look of its own

A **screen effect** is a small graphics program that runs over the finished frame: a palette, an
outline, a colour grade, a scanline. You write it in GLSL, in a `.frag` file of your own. Nothing
about it needs a graphics type from the engine's renderer, which is the point - `UDEA-MG-002`
refuses `de.fabmax.kool:*` on your project, so a shader API that took one would be an API you
could not call.

**A `.frag` is an asset**, like a model or a sound. You declare it, the build reads it, checks it
and packs it, and you name it in Kotlin by a typed accessor that the build generated:

```kotlin
// assets/shaders/shaders.udea.kts
shader(name = "scanlines", file = "shaders/scanlines.frag")
```

```glsl
// assets/shaders/scanlines.frag - no #version line: the engine writes it, per backend
uniform float uStrength;

vec4 udeaMain(vec2 uv) {
    vec3 colour = texture(uColor, uv).rgb;
    float line = mod(uv.y * uResolution.y, 2.0) < 1.0 ? 1.0 - uStrength : 1.0;
    return vec4(colour * line, 1.0);
}
```

```kotlin
lateinit var strength: FloatUniform
val scanlines = UdeaShader.fragment(GameAssets.shaders.scanlines, assets) {
    strength = float("uStrength", 0.25f)
}

registry.screenPass(scanlines)     // ordered: the first registered runs first
```

`GameAssets.shaders.scanlines` is generated from the declaration - `shaders` is the folder,
`scanlines` is the name - and `assets` is your `AssetRegistry`, the same one your sprites and
sounds come out of. There is no path in that Kotlin and no file reading anywhere in it, which is
the point of writing it this way:

- **It compiles in `commonMain`.** The obvious alternative,
  `javaClass.getResource("/shaders/scanlines.frag").readText()`, is a JVM class, a JVM URL and a
  JVM extension function, so it compiles on the desktop and nowhere else - you would be writing
  that line once per platform to load a file that is byte-identical on all of them. The GLSL
  travels inside the packed asset instead, and comes back out of the ordinary asset reader.
- **A misspelled name does not compile.** `GameAssets.shaders.scanlins` is an unresolved
  reference, not a `null` that becomes a blank screen.
- **A missing or broken `.frag` fails the build**, with `UDEA0041`, the line of the declaration
  and a did-you-mean over the `.frag` files you do have. The same rule catches an empty file, one
  that states its own `#version`, and one with no `udeaMain` in it - so a shader that cannot work
  never reaches a `.udeapak`.

`UdeaShader.fragment(path, source)` still exists, for a game that genuinely makes GLSL up at run
time - a material system, a graph editor, a permutation over a template. A shader somebody wrote
in a file is an asset.

You write one function, `vec4 udeaMain(vec2 uv)`. The engine writes everything around it.

**What the engine gives you, which you declare nothing for:**

| Name | What it is |
|---|---|
| `uColor` | the frame so far, as the previous effect in the list left it |
| `uDepth` | `.r` is how far away the 3D scene is, **in the backend's own direction** - see below |
| `uMask` | `.a` is `1` where an entity you marked was drawn, `0` everywhere else - see below |
| `uResolution` | the frame in pixels; `uTexel` is `1.0 / uResolution` |
| `uTime` | render seconds since the first frame. There is deliberately no way to read simulation time from here |
| `udeaMasked(uv)` | `1` where a marked entity was drawn, `0` where none was |
| `udeaOutline(uv, width)` | `1` on a pixel that is *not* marked but touches one that is |

**The mask is how an outline knows what a unit is.** Set `mask = true` on the `ModelRenderer` of
the things you want found - your units, not your ground - and the engine draws those, and only
those, into `uMask`. Without it an outline would have to guess from brightness or from depth
against the sky, and the ground would get one too.

```kotlin
it += ModelRenderer(mesh, material).apply { mask = true }
```

**`uDepth` does not run the way you expect, and that is why `uMask` exists.** Its direction is the
backend's: the desktop OpenGL backend draws reversed, so `0` is the far plane and a *nearer*
surface is a *larger* number - on a scene seven units deep the whole picture lands between `0` and
about `0.022`. Use `uMask` for "is anything here", because that means the same thing everywhere.
Use `uDepth` when you want to compare two distances in the same frame - fog, a depth-of-field blur,
a fade into the distance - and write it so that either direction still looks right, or read
`uDepth` on the platforms you ship on before you rely on which way round it is.

**Two are shipped ready-made**, and they are ordinary shaders registered the same way:

```kotlin
registry.screenPass(ScreenEffects.palette(listOf(ink, stone, sand, bone)))
registry.screenPass(ScreenEffects.outline(Rgba.BLACK))
```

Order matters and is yours: in that order the outline is drawn in its own colour over an
already-quantised picture; the other way round the outline is quantised too.

**Three rules worth knowing before you write one:**

- **Never write `#version`.** OpenGL and OpenGL ES disagree about that line and about which
  `precision` qualifiers a fragment stage needs, so the engine writes it, from the same setting
  Kool's own shaders use. `UdeaShader.fragment` refuses a source that states its own, with the
  reason, rather than letting it work on your desk and fail on a phone.
- **Every parameter is a plain value.** `float`, `int`, `vec2`, `color` and `texture`; each
  declaration hands back a handle whose `value` you write whenever you like, including per frame.
- **A shader that will not compile stops the game starting.** You get
  `ScreenShaderException` with a rule id (`UDEA0019`), your `.frag`'s path, *your* line - the
  engine subtracts its own header from what the driver reported - and the driver's message word
  for word. A shader that failed quietly would draw nothing, and "nothing" and "subtle" look the
  same in a screenshot.

Effects run at the render resolution, before the picture is fitted to the window, and before your
HUD is drawn - so a screenshot an agent takes shows exactly what a player sees, HUD unprocessed on
top. Turning one off is `shader.enabled = false`, which is the ordinary way to offer it as a
graphics setting.

Two worked examples, both in `moba/desktop/src/test/kotlin/dev/wildware/moba/shader/`, both in a
project the module graph refuses Kool on: `ShaderProof` registers the two built-ins and measures
what they did, and `ShaderAssetProof` does the same for `moba`'s own
`assets/shaders/scanlines.frag` - declared, packed, resolved through `MobaAssets.registry` and
built with `MobaScreenEffects.scanlines`, which is `commonMain` and compiles for every platform
the game ships on.

---

## What the template does not cover yet

Stated rather than implied, because each is a real piece of work and none of it is broken:

- **Sharing an id space with the engine's own components.** Your game's
  `net-components.lock` numbers the components *your build* compiles, from 0. The engine's
  components were numbered in the engine's build, from the engine's own lock, and those ids are
  already baked into the `Replicator`s inside the published jars - so the two id spaces sit side
  by side rather than being merged, and a game with more components than the engine's lowest id
  would eventually mint an id the engine has already used. Nothing merges them for you today.
  Where that is caught is worth knowing: `ComponentRegistry`'s constructor refuses two component
  types with one id - *"two component types share ComponentTypeId(N)"* - and a game builds one of
  those when it wires replication, the way `moba/game/src/commonMain/.../MobaGame.kt` does. A game
  that has not wired replication yet builds none, so nothing would say so.
- **The asset pipeline.** `dev.wildware.udea.assets` compiles a `.udea.kts` tree into a
  `.udeapak`. `moba/game/build.gradle.kts` is the worked example; the template has no assets, and
  whether that plugin needs anything extra outside this repository is untested.
- **An `.fbx` model** is published as the `.glb` committed beside it, never converted by the
  build: Assimp's Windows and Linux builds turn one `.fbx` into floats that differ in their last
  bits, which would give each platform a different asset hash. After adding or changing an
  `.fbx`, or a texture it names, run `./gradlew udeaWriteConvertedModels` on Linux x86_64 and
  commit the `.glb` it writes. `udeaVerifyConvertedModels` is on `check`: on Linux x86_64 it
  fails with `UDEA0039` when a `.glb` is missing, stale, or its `.fbx` does not convert, and on
  any other platform it prints that it skipped and why. The plugin registers both, so a game
  outside this repository gets them as `moba` does.
- **Drawing.** The template is headless. A game that draws applies
  `dev.wildware.udea.kotlin-multiplatform-render` and opens a `KoolBackend`; `moba` is the example, and the
  snapshot repository note above is the part that bites first.
- **`AGENTS.md` and the frozen contracts.** `udeaVerifyAgentsMd` holds *this* repository's module
  table against *this* repository's `settings.gradle.kts` and requires the nine spec section 5
  contracts to be named; `udeaVerifyContracts` freezes `docs/contracts/`. Both are statements
  about the engine's own documents, so `dev.wildware.udea.game-gates` does not apply either to a game. Your
  game's `AGENTS.md` is yours.
