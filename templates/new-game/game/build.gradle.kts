import dev.wildware.udea.build.UdeaModuleRegistry
import dev.wildware.udea.build.udeaModule
import dev.wildware.udea.gradle.UdeaAgentPlugin

/**
 * The game: its components, its systems, its assets, what it draws, and the launchers that start
 * it - `run`, headless, and `runWindow`, in a window.
 *
 * One project, because one platform. Split it the way `moba` does - a `game` library and a
 * launcher per platform - as soon as there is a second platform, because a launcher is the one
 * part of a game that is not portable.
 */

plugins {
    // The engine's JVM convention: Kotlin, the JDK toolchain, explicit API, the `kotlin-stdlib`
    // pin and the K2 compiler plugin whose checkers hold `@Net`, `@Replicated` and the rest of
    // the annotations honest. A JVM project can draw: `udea-render` below brings the window with
    // it. `dev.wildware.udea.kotlin-multiplatform-render` is the one to apply instead when the
    // game ships on more than one platform.
    //
    // No version on any `dev.wildware.udea.*` id: `settings.gradle.kts` states it
    // once, from the `udeaVersion` property.
    id("dev.wildware.udea.kotlin-library")

    // `gamebridge.json`, the debug-only `agent` source set, and the `-PdebugPort=` wiring that
    // puts the agent's HTTP surface on a running instance.
    id("dev.wildware.udea.agent")

    // The registry the engine discovers this module's generated code through (issue #202). It
    // runs even for a game with no `@Replicated` component yet, because the registry is what a
    // `UdeaGameDef` is built from.
    id("com.google.devtools.ksp") version libs.versions.ksp.get()

    // The asset pipeline: `assets/` compiled into one packed bundle in the jar, and the generated
    // `GameAssets` accessors - `GameAssets.models.rover` - on this project's main source set.
    id("dev.wildware.udea.assets")
}

// This game's own coordinates. The engine's conventions deliberately set neither, so that a game
// that applies them is published as itself rather than as a module of Udea.
group = "com.example"
version = "0.1.0"

/** Which Udea to build against. One line in `gradle.properties` governs the whole repository. */
val udeaVersion: String = providers.gradleProperty("udeaVersion").get()

dependencies {
    // The kernel: `UdeaGameDef`, `GameHost`, `SimSystem`, `SimClock`, `NetId`.
    implementation("dev.wildware.udea:udea-core:$udeaVersion")

    // `@Replicated`, `@Net` and `@Sim` for this game's own components.
    implementation("dev.wildware.udea:udea-annotations:$udeaVersion")

    // The packed asset graph the generated accessors resolve in, and the bundle reader.
    implementation("dev.wildware.udea:udea-assets:$udeaVersion")

    // Drawing: the window, the models, the sky and the screen effects. The graphics library it
    // draws with stays inside it, so nothing in this game names one of its types.
    implementation("dev.wildware.udea:udea-render:$udeaVersion")

    // The asset compiler, which `dev.wildware.udea.assets` runs as a separate process at build
    // time. Its own configuration and never `implementation`: it carries the Kotlin compiler, which
    // the module-graph gate refuses on anything the game ships.
    "udeaAssetsCompiler"("dev.wildware.udea:udea-assets-compiler:$udeaVersion")

    // The MCP tool surface, on the debug-only `agent` source set and nowhere else. This is the
    // line `moba` does not need, because inside the engine's repository the plugin adds the
    // project dependency itself.
    "agentImplementation"("dev.wildware.udea:udea-agent-host:$udeaVersion")

    // The processor that writes `NewGameModuleRegistry` and `NewGameUdeaRegistry`.
    ksp("dev.wildware.udea:udea-codegen:$udeaVersion")
}

/**
 * How this game names itself to `game-bridge-mcp`, and which ports a launcher may claim.
 *
 * The range matters on a machine that runs more than one Udea game. A bridge does not check
 * whose game answered a port - it lists whatever is listening in its range - so two games
 * sharing a range means either one's bridge can enumerate, drive and stop the other's instance.
 * The engine's default is 7820-7839 and `moba` takes 7840-7859; a new game takes a range of its
 * own and says so here.
 */
udeaAgent {
    name.set("new-game")
    portRange.set("7860-7879")
    flagsPackage.set("com.example.newgame.agent")
}

/**
 * Where this game's assets are: one directory, compiled into `udea/assets.udeapak` in the jar.
 *
 * The folder a `.udea.kts` sits in names its accessor group: `assets/models/models.udea.kts`
 * declares `GameAssets.models.rover`, and a script at the top of `assets/` would declare into
 * `GameAssets.root`.
 */
udea {
    assetRoots.from("assets")
    kotlinVersion.set(libs.versions.kotlin.get())
}

/** This game's generated registry (issue #202), named after the game. */
val udeaRegistry = udeaModule("NewGame")

ksp {
    arg(UdeaModuleRegistry.MODULE_NAME_OPTION, udeaRegistry.name)
    arg(UdeaModuleRegistry.REGISTRY_MODULES_OPTION, udeaRegistry.registryModules)
}

/**
 * `run`: the game, with the agent surface when a port is asked for.
 *
 * Named `run` because that is the task `gamebridge.json` invokes and the task
 * `UdeaAgentPlugin` wires `-PdebugPort=` into. It runs off the **agent** source set's runtime
 * classpath, which is the only classpath here that resolves `udea-agent-host` - so the release
 * jar and `runtimeClasspath` carry none of it, which is what `udeaVerifyRelease` checks.
 */
tasks.register<JavaExec>("run") {
    group = ApplicationPlugin.APPLICATION_GROUP
    description = "Runs the game headless, or as an agent instance with -PdebugPort=N."
    mainClass.set("com.example.newgame.agent.NewGameAgent")
    classpath = sourceSets.named(UdeaAgentPlugin.DEFAULT_SOURCE_SET).get().runtimeClasspath
}

/**
 * `runWindow`: the game in a window, which is what a player runs.
 *
 * Off the **main** source set's runtime classpath - the one the jar ships - so a player gets what
 * this runs, with no agent surface in it. `-Pseconds=N` closes the window after N seconds of
 * drawing, for a script that wants the window up for a known length of time and then gone; the
 * launcher fails the run if the window stopped drawing before reaching it.
 */
tasks.register<JavaExec>("runWindow") {
    group = ApplicationPlugin.APPLICATION_GROUP
    description = "Runs the game in a window. -Pseconds=N closes it after N seconds of drawing."
    mainClass.set("com.example.newgame.NewGameWindow")
    classpath = sourceSets.main.get().runtimeClasspath
    // The model files: the packed bundle carries each model's record, and the file is read here.
    systemProperty("newgame.assets.root", layout.projectDirectory.dir("assets").asFile.absolutePath)
    providers.gradleProperty("seconds").orNull?.let { systemProperty("newgame.seconds", it) }
}
