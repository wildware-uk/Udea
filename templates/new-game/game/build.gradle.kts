import dev.wildware.udea.build.UdeaModuleRegistry
import dev.wildware.udea.build.udeaModule
import dev.wildware.udea.gradle.UdeaAgentPlugin

/**
 * The game: its components, its systems, and the launcher that starts them.
 *
 * One project, because one platform. Split it the way `moba` does - a `game` library and a
 * launcher per platform - as soon as there is a second platform, because a launcher is the one
 * part of a game that is not portable.
 */

plugins {
    // The engine's JVM convention: Kotlin, the JDK toolchain, explicit API, the `kotlin-stdlib`
    // pin and the K2 compiler plugin whose checkers hold `@Net`, `@Replicated` and the rest of
    // the annotations honest. `dev.wildware.udea.kotlin-multiplatform-render` is the one to apply instead when
    // the game draws and ships on more than one platform.
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
