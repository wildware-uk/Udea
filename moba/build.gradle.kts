import dev.wildware.udea.build.ReleaseRules
import dev.wildware.udea.build.UdeaNetComponents
import dev.wildware.udea.build.UdeaVerifyReleaseTask
import dev.wildware.udea.build.registerNetProtocolLock
import dev.wildware.udea.gradle.UdeaAgentPlugin

plugins {
    id("udea.kotlin-library")

    // `@Replicated` on `Position` is processed here, exactly as it is for any other game.
    // Before this, `PositionReplicator` was written out by hand - which `ReplicatorApiShapeTest`
    // correctly failed the build for, because a hand-written replicator stores a `FieldMask` in
    // game code and a `Long`-to-`LongArray` widening would then be a breaking change. Generated
    // replicators live under `build/` and are regenerated, so they are out of that rule's scope
    // by construction.
    id("com.google.devtools.ksp") version libs.versions.ksp.get()

    // The plugin that was unreachable until now: it had a class and no id, no project applied it,
    // and there was no `gamebridge.json`, so `launch_instance` had nothing to launch and the only
    // bootable thing in the tree was a task in `udea-agent-host`'s TEST sources. Applying it here
    // creates the `agent` source set, generates the per-variant agent flag, wires
    // `-Dudea.agent.port` into `run`, and writes the launch declaration at the repository root.
    id("dev.wildware.udea.agent")
}

dependencies {
    implementation(project(":udea-core"))

    // `@Replicated`, `@Net` and `@Sim` on `Position`. BINARY-retained, so they are on this
    // module's own bytecode and cannot be `compileOnly`.
    implementation(project(":udea-annotations"))

    // The processor over this module's main source set.
    ksp(project(":udea-codegen"))
    implementation(project(":udea-gas"))
    implementation(project(":udea-net"))
    implementation(project(":udea-assets"))
    implementation(project(":udea-render"))

    // `udea-render` declares gdx as `implementation`, so GL types do not leak onto a consumer's
    // compile classpath by default - a game that draws has to opt in, visibly, on this line.
    // `moba` draws (see `MobaScene`), so it opts in. Runtime already had gdx transitively; what
    // this adds is the ability to *name* `Texture` and `Batch`, which writing a `RenderSystem`
    // requires.
    implementation(libs.gdx)
}

/**
 * How this game names itself to `list_instances` and how a launcher starts it.
 *
 * Everything except `flagsPackage` is the plugin's default; it is written out because a launch
 * declaration is a contract with a process outside this build, and a reader looking for "what
 * command does the bridge run" should find it here rather than have to infer it from a convention.
 */
udeaAgent {
    name.set("moba")
    flagsPackage.set("dev.wildware.moba.agent")
}

/**
 * The release gate bans **this game's** agent package too, not only the engine's.
 *
 * `ReleaseRules.DEFAULT_BANNED_PREFIXES` names `dev/wildware/udea/agent/` and
 * `dev/wildware/udea/agenthost/`, which is the engine's half. `MobaAgent` is in neither: it is
 * in `dev.wildware.moba.agent`, in a source set of its own, and it stays out of the jar today
 * only because `agentClasses` is not wired into `jar`. That is a true statement about the
 * current packaging and not a guarantee - the day somebody builds a fat jar, or adds
 * `from(sourceSets["agent"].output)`, the class that binds an HTTP surface onto the live
 * simulation ships and every rule in the default list is still satisfied.
 *
 * So the gate is told the name that is already written down two lines above. Proven load-bearing
 * rather than asserted: adding `from(sourceSets["agent"].output)` to `jar` turns
 * `./gradlew :moba:assemble -Pudea.release=true` red on `UDEA-REL-001`.
 */
tasks.named<UdeaVerifyReleaseTask>("udeaVerifyRelease") {
    bannedPrefixes.set(ReleaseRules.DEFAULT_BANNED_PREFIXES + "dev/wildware/moba/agent/")
}

/**
 * Names this module's generated manifest fragment, the way `:udea-agent` names its own.
 *
 * Without it two modules emit `udea/-agent-tools.json` and the second overwrites the first.
 */
/**
 * The project-wide `@Replicated` id space, read from the reviewed `net-components.lock`.
 *
 * Not optional for a module that emits protocol identity. A processor numbering only the symbols
 * in front of it hands out 0, 1, 2 per module, so two modules both mint `ComponentTypeId(0)` and
 * two peers decode each other's packets as the wrong component type - silently, because each
 * module's lock is internally consistent and `protoHash` therefore reports agreement.
 */
val projectComponents: Provider<String> =
    providers.fileContents(rootProject.layout.projectDirectory.file(UdeaNetComponents.FILE_NAME))
        .asText
        .map { text ->
            when (val parsed = UdeaNetComponents.parse(text)) {
                is UdeaNetComponents.Parse.Success -> UdeaNetComponents.optionValue(parsed.components)
                is UdeaNetComponents.Parse.Failure -> throw GradleException(parsed.problem)
            }
        }

ksp {
    arg("udea.moduleName", "Moba")
    arg(UdeaNetComponents.KSP_OPTION, projectComponents.get())
}

/**
 * `moba` emits protocol identity now, so it gets the same reviewed lock gate `:udea-codegen` has.
 *
 * Without this the module would generate a wire contract that nothing compares against a reviewed
 * file, which is the one arrangement the lock exists to prevent: a component id and a field width
 * would be free to change in a diff nobody read.
 */
registerNetProtocolLock(
    generatedLock = layout.buildDirectory.file("generated/ksp/main/resources/udea/Moba-net-protocol.lock"),
    producingTask = "kspKotlin",
)

/** The three entry points of spec 4, over the one `MobaGame.definition()`. */
val agentSources: SourceSet = sourceSets.named(UdeaAgentPlugin.DEFAULT_SOURCE_SET).get()

/**
 * `moba.agent` - and the task the launch declaration names.
 *
 * It runs off the **agent** source set's runtime classpath, which is the only classpath in this
 * project that resolves `:udea-agent-host`. `runtimeClasspath` does not, which is what makes
 * `ReleaseRules.CLASSPATH_RULE` pass for a reason rather than by omission, and `jar` packages
 * `main` alone, so no agent entry point is inside the artifact either.
 *
 * `run` rather than `runAgent` because `UdeaAgentPlugin` wires `-Dudea.agent.port` into the task
 * literally named `run`, and because that is the task the generated `launch.command` invokes.
 */
tasks.register<JavaExec>("run") {
    group = ApplicationPlugin.APPLICATION_GROUP
    description = "moba.agent: Offscreen by default, with the agent HTTP surface on -PdebugPort=N."
    mainClass.set("dev.wildware.moba.agent.MobaAgent")
    classpath = agentSources.runtimeClasspath
}

tasks.register<JavaExec>("runServer") {
    group = ApplicationPlugin.APPLICATION_GROUP
    description = "moba.server: headless, no GL context, no agent surface."
    mainClass.set("dev.wildware.moba.entry.MobaServer")
    classpath = sourceSets.main.get().runtimeClasspath
}

tasks.register<JavaExec>("runClient") {
    group = ApplicationPlugin.APPLICATION_GROUP
    description = "moba.client: a visible LWJGL3 window."
    mainClass.set("dev.wildware.moba.entry.MobaClient")
    classpath = sourceSets.main.get().runtimeClasspath
}

/**
 * The debug source set compiles as part of `check`, and not otherwise.
 *
 * Without this the only thing that ever compiles `src/agent` is somebody running `:moba:run`, so
 * a change to `udea-agent-host`'s API would leave the build green and the launch path broken -
 * discovered by the next person to try to launch an instance, which is the worst possible moment.
 * It is deliberately not on `assemble`: `assemble` is what a release build runs, and compiling the
 * agent entry point there would put its output where `udeaVerifyRelease` has to reason about it.
 */
tasks.named("check") {
    dependsOn(agentSources.classesTaskName)
}
