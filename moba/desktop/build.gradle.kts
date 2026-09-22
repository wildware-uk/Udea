import dev.wildware.udea.build.ReleaseRules
import dev.wildware.udea.build.UdeaModuleRegistry
import dev.wildware.udea.build.UdeaVerifyReleaseTask
import dev.wildware.udea.build.udeaRegistryModules
import dev.wildware.udea.gradle.UdeaAgentPlugin
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

/**
 * The desktop launchers: the server, the player's client, the agent's instance, and every task
 * that photographs or measures one (spec D12, issue #212).
 *
 * ## Why the launchers are a project of their own
 *
 * Because a launcher is the one thing in a game that is not portable. `MobaLaunch` opens a Kool
 * context, reads `-Dudea.render.mode`, starts a thread to end the render loop and blocks on
 * `awaitExit`; none of that means anything in a browser or on a phone, and none of it belongs in
 * `:moba:game`, which every platform compiles. The split is what makes that a fact about the
 * module graph rather than a convention: this project is JVM-only and the game cannot name a
 * single type in it.
 *
 * ## Where the old `:moba` tasks went
 *
 * Every `JavaExec` that used to be `:moba:<name>` is `:moba:desktop:<name>` now, with the same
 * name, the same main class and the same properties. `AGENTS.md` and `BRIEF-212.md` carry the
 * table; `gamebridge.json` is regenerated from this project's path, so the bridge follows.
 */

plugins {
    id("dev.wildware.udea.kotlin-library")

    // The plugin that creates the `agent` source set, generates the per-variant agent flag, wires
    // `-Dudea.agent.port` into `run`, and writes the launch declaration at the repository root.
    // It lives here and not with the game: a launch declaration names a task that starts a
    // process, and this is the project that has one.
    id("dev.wildware.udea.agent")

    // The editor source set's gizmos (issue #233). KSP runs on that one source set and no other in
    // this project: see `kspEditor` below.
    id("com.google.devtools.ksp") version libs.versions.ksp.get()
}

/**
 * What the live asset daemon compiles `.udea.kts` against, declared here rather than inherited.
 *
 * `UdeaAssetsPlugin` creates a configuration of this role called `udeaAssetScript`, and it is
 * applied to `:moba:game` - the project that owns `assets/` - not to this one. `run` still needs
 * the classpath, because the daemon it starts lives in the *launcher's* process (issue #193,
 * `MobaAssetTools`), so the choice is between reaching across into another project's
 * configurations - which does not survive the configuration cache - and declaring the same one
 * dependency locally. This is the local declaration, and it is exactly one line of duplication.
 *
 * It is resolvable and not consumable, and nothing puts it on a compile or runtime classpath:
 * it carries `kotlin-compiler-embeddable`, which `UDEA-MG-005` and `ReleaseRules.CLASSPATH_RULE`
 * both forbid there.
 */
val udeaAssetScript: Configuration by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
}

dependencies {
    // The game. `api` rather than `implementation` because the shot mains, the proofs and the
    // agent source set all name `MobaGame`, `MobaScene` and this game's components directly.
    api(project(":moba:game"))

    // Named again here, although the game exposes them, because this project composes them: a
    // launcher builds a `GameHost` over a `UdeaGameDef` and drives a `KoolBackend`.
    implementation(project(":udea-core"))
    implementation(project(":udea-render"))
    implementation(project(":udea-net"))

    // The ability system. `:moba:game` takes it as `implementation`, so it is not on this
    // project's classpath transitively - and the shot mains and the HUD tests here name
    // `Abilities`, `GasServices` and `AttributeId` directly.
    implementation(project(":udea-gas"))
    // `api`, and the recording seam lives here rather than in the game (issue #212).
    //
    // `udea-replay` runs KSP on its JVM target alone, by a decision its own build script states:
    // `ReplayToolset` is `jvmMain`, so its `UdeaReplayModuleRegistry` is generated there too and
    // exists on no other target. A game module that listed it would put a JVM-only symbol into
    // `MobaUdeaRegistry`, and `:moba:game:compileAndroidMain` fails on it -
    // `Unresolved reference 'UdeaReplayModuleRegistry'` - which is what moved `MobaReplay` here.
    // That is honest rather than a workaround: every consumer of it is in this project already,
    // and recording a match to a file on disk is a thing a desktop launcher does. It goes back to
    // the game when `udea-replay` is multiplatform (its build script names issue #208 as the
    // change that allows it).
    api(project(":udea-replay"))
    implementation(project(":udea-audio"))
    implementation(project(":udea-assets"))

    // The asset compiler on the **agent** source set, so `:moba:desktop:run` can hold a warm
    // `AssetDaemon` and serve `assets.*` over the live game (see `MobaAssetTools`). Deliberately
    // not `implementation`: this is the jar that carries `kotlin-compiler-embeddable`, and
    // `UDEA-MG-005` plus `ReleaseRules.CLASSPATH_RULE` both forbid it on `runtimeClasspath`. The
    // `agent` source set is the one classpath in this project allowed to resolve it, and `jar`
    // packages `main`, so it cannot reach the artifact either.
    "agentImplementation"(project(":udea-assets-compiler"))

    // The classpath `:moba:desktop:run` hands the live asset daemon to compile `.udea.kts`
    // against. Resolved into the `run` task's JVM arguments below, never onto a compile or
    // runtime classpath. See [udeaAssetScript] for why it is declared here by hand.
    udeaAssetScript(project(":udea-assets-compiler"))

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
    // Off the engine default of 7820-7839, and stated rather than inherited.
    //
    // The development box that runs this repository also runs `melon-merge`, whose
    // `game-bridge-mcp` scans `7777,7811-7829`. A bridge does not check whose game answered a
    // port - it lists whatever is listening in its range - so the overlapping half of the default
    // range means either project's bridge can enumerate, drive and `stop_instance` the other's
    // game. That is not a warning either side gets: it looks like an instance vanishing.
    // 7840-7859 is disjoint from every range that box scans, and `.mcp.json` here scans the same
    // twenty ports, so the two toolchains cannot see each other at all.
    portRange.set("7840-7859")
}

/**
 * The release gate bans **this game's** agent package too, not only the engine's.
 *
 * `ReleaseRules.DEFAULT_BANNED_PREFIXES` names `dev/wildware/udea/agent/` and
 * `dev/wildware/udea/agenthost/`, which is the engine's half. `MobaAgent` is in neither: it is in
 * `dev.wildware.moba.agent`, in a source set of its own, and it stays out of the jar today only
 * because `agentClasses` is not wired into `jar`. That is a true statement about the current
 * packaging and not a guarantee - the day somebody builds a fat jar, or adds
 * `from(sourceSets["agent"].output)`, the class that binds an HTTP surface onto the live
 * simulation ships and every rule in the default list is still satisfied.
 */
tasks.named<UdeaVerifyReleaseTask>("udeaVerifyRelease") {
    bannedPrefixes.set(ReleaseRules.DEFAULT_BANNED_PREFIXES + "dev/wildware/moba/agent/" + "dev/wildware/moba/editor/")
}

/** The three entry points of spec 4, over the one `MobaGame.definition()`. */
val agentSources: SourceSet = sourceSets.named(UdeaAgentPlugin.DEFAULT_SOURCE_SET).get()

/** The asset root the dev daemon watches: the game's, which is where the assets live. */
val gameAssetRoot: Directory = project(":moba:game").layout.projectDirectory.dir("assets")

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

    // --- the dev asset daemon -----------------------------------------------------------------
    //
    // Three properties, and the game registers `assets.*` only when all three are usable. A
    // packaged game has none of them and correctly serves no asset tools at all; `MobaAssetTools`
    // says so on stderr rather than registering tools that would fail on first call.
    systemProperty("udea.assets.root", gameAssetRoot.asFile.absolutePath)
    systemProperty("udea.repoRoot", rootProject.layout.projectDirectory.asFile.absolutePath)

    // `-Peditor=true` starts this instance as a level editor: the `editor.*` tools are registered
    // and `/health` says `"editor":true` (issue #193). Absent is a normal run, which has neither.
    providers.gradleProperty("editor").orNull?.let { systemProperty("udea.editor", it) }

    // A `CommandLineArgumentProvider` and not a `systemProperty`, because resolving a
    // configuration during configuration is a configuration-cache failure and because the
    // resolved classpath must be this invocation's rather than whichever one stored the cache.
    val scriptClasspath: FileCollection = files(udeaAssetScript)
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf("-Dudea.assetsCompiler.classpath=" + scriptClasspath.asPath)
        },
    )
}

// --- the editor (issue #194) ---------------------------------------------------------------------
//
// `runEditor` opens `moba` in the editor window: docked panels, and the world drawn by Kool in its
// Scene and Game tabs, paused. Its entry point lives in a source set of its own, over the agent
// source set, so the editor reaches this project through `editorRuntimeClasspath` and nothing else:
// `runtimeClasspath` - what the release jar runs on - never resolves `udea-editor`, and `UDEA-MG-010`
// fails the build the day it does. `jar` packages `main` alone, so no editor class is in the artifact
// either, and `udeaVerifyRelease` bans the package besides (below).

/** `src/editor`: `MobaEditor`, compiled against the agent source set it opens a window over. */
val editorSources: SourceSet = sourceSets.create("editor") {
    compileClasspath += agentSources.output + agentSources.compileClasspath
    runtimeClasspath += agentSources.output + agentSources.runtimeClasspath
}

dependencies {
    // The window, and nothing else in this project names it: see `UDEA-MG-010`.
    "editorImplementation"(project(":udea-editor"))

    // The gizmos (issue #233), generated here and nowhere else. `:moba:game`'s own KSP run checks the
    // handle annotations on its components and lists them on `MobaModuleRegistry`'s `@HandleIndex`;
    // this run reads that index and writes one `Gizmo` per annotation, plus `MobaGizmoRegistry`
    // naming them and every hand-written `Gizmo` in `src/editor`. A gizmo implements a `udea-editor`
    // type, so it cannot live in the game: `UDEA-MG-012` fails the build if one reaches a release
    // classpath.
    "kspEditor"(project(":udea-codegen"))
}

ksp {
    arg(UdeaModuleRegistry.GIZMO_REGISTRY_OPTION, "Moba")
    // Every Udea module the editor source set runs on: the run reads each one's registry for handles.
    arg(UdeaModuleRegistry.REGISTRY_MODULES_OPTION, udeaRegistryModules("Moba", "editorRuntimeClasspath"))
}

// The editor calls `MobaAgent.runWithGl`, which is `internal`: one Kotlin module for the two
// source sets, as `test` is with `main`, rather than a public entry point nobody outside this
// project may use.
the<KotlinJvmProjectExtension>().target.compilations.named("editor") {
    associateWith(the<KotlinJvmProjectExtension>().target.compilations.getByName(UdeaAgentPlugin.DEFAULT_SOURCE_SET))
}

/**
 * `src/editorTest`: the editor's buttons against the real `EditorToolset` in a real `moba` world.
 *
 * A source set of its own rather than a corner of `test`, because `test`'s runtime classpath is
 * what the replay-equality entry points run on (see `replayEqualityClasspath` below), and a window
 * that never ships has no business there either.
 */
val editorTestSources: SourceSet = sourceSets.create("editorTest") {
    compileClasspath += editorSources.output + editorSources.compileClasspath
    runtimeClasspath += editorSources.output + editorSources.runtimeClasspath
}

dependencies {
    // `kotlin-test` alone resolves no annotations outside `test`: the Kotlin plugin picks the JUnit 5
    // variant for the source set named `test` only.
    "editorTestImplementation"(kotlin("test-junit5"))
    "editorTestImplementation"(libs.junit5.jupiter)
    // The test reads `editor.history`'s answer the way an agent does: as JSON.
    "editorTestImplementation"(libs.kotlinx.serialization.json)
    "editorTestRuntimeOnly"(libs.junit5.platform.launcher)

    // The Compose compiler for this one compilation. The test hosts the window in ComposeGL's
    // `uiTest`, whose content is `@Composable`; compiled without the plugin, that lambda is a
    // `Function0` and the call fails at run time with `NoSuchMethodError` on `uiTest$default`.
    // Not the `composeCompiler` plugin on the whole project: that would stamp `$stable` into every
    // class `main` ships, for a project with no composable in it.
    "kotlinCompilerPluginClasspathEditorTest"("org.jetbrains.kotlin:kotlin-compose-compiler-plugin-embeddable:${libs.versions.kotlin.get()}")
}

// `MobaEditor.session` and `MobaAgent.attach` are `internal`, as `main`'s are to `test`.
the<KotlinJvmProjectExtension>().target.compilations.named("editorTest") {
    associateWith(the<KotlinJvmProjectExtension>().target.compilations.getByName("editor"))
    associateWith(the<KotlinJvmProjectExtension>().target.compilations.getByName(UdeaAgentPlugin.DEFAULT_SOURCE_SET))
}

val editorTest = tasks.register<Test>("editorTest") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "The editor window's buttons against the real editor tools, headless."
    testClassesDirs = editorTestSources.output.classesDirs
    classpath = editorTestSources.runtimeClasspath

    // `MobaEditorSaveTest` (issue #195) saves into a copy of the game's asset scripts through the
    // real asset daemon, so it needs the tree to copy and the classpath the daemon compiles against -
    // the same one `run` and `runEditor` hand theirs. The tree is an input, so an edited script reruns it.
    systemProperty("udea.moba.gameAssets", gameAssetRoot.asFile.absolutePath)
    inputs.dir(gameAssetRoot).withPropertyName("gameAssets").withPathSensitivity(PathSensitivity.RELATIVE)
    val scriptClasspath: FileCollection = files(udeaAssetScript)
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf("-Dudea.assetsCompiler.classpath=" + scriptClasspath.asPath)
        },
    )
}

tasks.named("check") { dependsOn(editorTest) }

tasks.register<JavaExec>("runEditor") {
    group = ApplicationPlugin.APPLICATION_GROUP
    description = "moba in the editor window: Windowed by default, paused, with the agent HTTP surface on -PdebugPort=N."
    mainClass.set("dev.wildware.moba.editor.MobaEditor")
    classpath = editorSources.runtimeClasspath

    // The same asset daemon and repository root `run` hands its instance: the editor is that
    // instance with a window over it, so `assets.*` answers the same way in both.
    systemProperty("udea.assets.root", gameAssetRoot.asFile.absolutePath)
    systemProperty("udea.repoRoot", rootProject.layout.projectDirectory.asFile.absolutePath)
    // The Scene tab's snapping and axes (issue #236): this project's, kept beside it and ignored by
    // git, because a person's grid is an editor preference and not game data.
    systemProperty("udea.editor.preferences", layout.projectDirectory.file(".udea/editor-preferences.properties").asFile.absolutePath)
    val scriptClasspath: FileCollection = files(udeaAssetScript)
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf("-Dudea.assetsCompiler.classpath=" + scriptClasspath.asPath)
        },
    )
    // `JavaExec` forks, so the agent port and a render mode have to be forwarded by hand;
    // `UdeaAgentPlugin` wires them into the task named `run` alone. Windowed unless
    // `-Pudea.render.mode=Offscreen` says otherwise: the editor is a window a person looks at.
    val port = providers.gradleProperty("debugPort")
    val mode = providers.gradleProperty("udea.render.mode")
    // `-PeditorFox=true` puts an animated Fox beside the player when the editor opens (issue #243):
    // `moba`'s level has no animated entity for the Animation panel to show. See `MobaEditorModels`.
    val fox = providers.gradleProperty("editorFox")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOfNotNull(
                port.orNull?.let { "-Dudea.agent.port=$it" },
                mode.orNull?.let { "-Dudea.render.mode=$it" },
                fox.orNull?.let { "-Dmoba.editor.fox=$it" },
            )
        },
    )
}

tasks.register<JavaExec>("runServer") {
    group = ApplicationPlugin.APPLICATION_GROUP
    description = "moba.server: headless, no GL context, no agent surface."
    mainClass.set("dev.wildware.moba.entry.MobaServer")
    classpath = sourceSets.main.get().runtimeClasspath
}

/**
 * `moba.shot`: one Offscreen frame of the whole character roster, written as a PNG.
 *
 * Deliberately NOT wired into `check`. It creates a real Kool context, so on a machine with no
 * driver it is a failure rather than a skip - which is the right behaviour for a task that is
 * *asked for* and the wrong behaviour for `./gradlew build` on a headless container.
 */
tasks.register<JavaExec>("runShot") {
    group = ApplicationPlugin.APPLICATION_GROUP
    description = "moba.shot: captures one frame of the character roster to -Pudea.shot.out=<png>."
    mainClass.set("dev.wildware.moba.entry.MobaShot")
    classpath = sourceSets.main.get().runtimeClasspath
    systemProperty("udea.render.mode", "Offscreen")
    systemProperty(
        "udea.shot.out",
        providers.gradleProperty("udea.shot.out").orNull
            ?: layout.buildDirectory.file("reports/udea/roster.png").get().asFile.absolutePath,
    )
    systemProperty("udea.shot.tick", providers.gradleProperty("udea.shot.tick").orNull ?: "18")
}

// The evidence task. `MatchShot` lives in the test source set - it needs a GL driver, so wiring it
// into `check` would turn a missing driver into a skip, which is the failure mode this repository
// has already shipped once (see `MobaShot`). It is run by name, and it is a task rather than a
// hand-assembled `java -cp` so that the classpath it runs on is the one Gradle resolved.
tasks.register<JavaExec>("runMatchShot") {
    group = ApplicationPlugin.APPLICATION_GROUP
    description = "moba.matchshot: captures the melee, the HUD, the spin, the item bar and the result."
    mainClass.set("dev.wildware.moba.MatchShot")
    classpath = sourceSets.test.get().runtimeClasspath
    systemProperty("udea.render.mode", "Offscreen")
    systemProperty(
        "udea.matchshot.dir",
        providers.gradleProperty("udea.matchshot.dir").orNull
            ?: layout.buildDirectory.dir("reports/udea/match").get().asFile.absolutePath,
    )
}

// The imported models' evidence task (issue #244). `GameModelShot` lives in the test source set for
// the reason `MatchShot` does. It draws the game's FBX character from its committed `.glb`, as
// `:moba:game:udeaPackBundle` copies it under that project's `build/udea/converted` - the
// directory `UdeaAssetsPlugin` publishes each converted model to - and the fox from the asset root.
val convertedModels: Provider<Directory> = project(":moba:game").layout.buildDirectory.dir("udea/converted")

tasks.register<JavaExec>("runModelShot") {
    group = ApplicationPlugin.APPLICATION_GROUP
    description = "moba.modelshot: the game's FBX character, from its committed .glb, drawn with its texture."
    mainClass.set("dev.wildware.moba.GameModelShot")
    classpath = sourceSets.test.get().runtimeClasspath
    dependsOn(":moba:game:udeaPackBundle")
    systemProperty("udea.render.mode", "Offscreen")
    systemProperty("udea.moba.gameAssets", gameAssetRoot.asFile.absolutePath)
    systemProperty("udea.moba.convertedModels", convertedModels.get().asFile.absolutePath)
    systemProperty(
        "udea.modelshot.dir",
        providers.gradleProperty("udea.modelshot.dir").orNull
            ?: layout.buildDirectory.dir("reports/udea/model").get().asFile.absolutePath,
    )
}

// The lane's evidence task. `LaneShot` lives in the test source set for the same reason
// `MatchShot` does - it needs a GL driver, and wiring it into `check` would turn a missing driver
// into a skip, which is a failure mode this repository has already shipped once. Run by name.
tasks.register<JavaExec>("runLaneShot") {
    group = ApplicationPlugin.APPLICATION_GROUP
    description = "moba.laneshot: captures a creep wave, the clash under the towers, and the champion farming."
    mainClass.set("dev.wildware.moba.lane.LaneShot")
    classpath = sourceSets.test.get().runtimeClasspath
    systemProperty("udea.render.mode", "Offscreen")
    systemProperty(
        "udea.laneshot.dir",
        providers.gradleProperty("udea.laneshot.dir").orNull
            ?: layout.buildDirectory.dir("reports/udea/lane").get().asFile.absolutePath,
    )
}

// Issue #266's evidence, and issue #259's. `ShaderProof` registers a palette and a one-pixel
// outline as ordinary screen effects from *this* project - which `UDEA-MG-002` refuses Kool on, so
// "a game writes a shader with no Kool type in its source" is a build gate rather than a claim -
// captures the scene with each effect off and on, and fails unless every figure is what the effects
// have to be worth, the unprocessed control included. Needs a GL driver, so run by name, as
// `runMatchShot` is: wiring it into `check` would turn a missing driver into a skip.
tasks.register<JavaExec>("runShaderProof") {
    group = ApplicationPlugin.APPLICATION_GROUP
    description = "moba.shaderproof: a game's own palette and outline screen effects, measured against an unprocessed control."
    mainClass.set("dev.wildware.moba.shader.ShaderProof")
    classpath = sourceSets.test.get().runtimeClasspath
    systemProperty("udea.render.mode", "Offscreen")
    systemProperty(
        "udea.shaderproof.dir",
        providers.gradleProperty("udea.shaderproof.dir").orNull
            ?: layout.buildDirectory.dir("reports/udea/shader").get().asFile.absolutePath,
    )
}

// The shader-as-an-asset proof. Same shape as `runShaderProof`, and deliberately a second task
// rather than more assertions in the first: this one's shader is GLSL the *game* wrote, read out
// of the packed bundle, and its figures are about that path rather than about the built-ins.
// Needs a GL driver, so it is run by name, for the reason `runMatchShot` gives.
tasks.register<JavaExec>("runShaderAssetProof") {
    group = ApplicationPlugin.APPLICATION_GROUP
    description = "moba.shaderassetproof: a .frag the game declared as an asset, drawn and measured."
    mainClass.set("dev.wildware.moba.shader.ShaderAssetProof")
    classpath = sourceSets.test.get().runtimeClasspath
    systemProperty("udea.render.mode", "Offscreen")
    systemProperty(
        "udea.shaderassetproof.dir",
        providers.gradleProperty("udea.shaderassetproof.dir").orNull
            ?: layout.buildDirectory.dir("reports/udea/shader-asset").get().asFile.absolutePath,
    )
}

// Issue #267's evidence. `SkyProof` sets a sky from *this* project - which `UDEA-MG-002` refuses Kool
// on, so "a game sets its sky with no Kool type in its source" is a build gate - changes it while
// the game runs, takes it away again, and fails unless every open-sky pixel is the colour it has to
// be and the frame with no sky is byte-for-byte the one it started with. Needs a GL driver, so run
// by name, for the reason `runMatchShot` gives.
tasks.register<JavaExec>("runSkyProof") {
    group = ApplicationPlugin.APPLICATION_GROUP
    description = "moba.skyproof: a game's sky, flat and graded, set and changed while running, measured against no sky."
    mainClass.set("dev.wildware.moba.sky.SkyProof")
    classpath = sourceSets.test.get().runtimeClasspath
    systemProperty("udea.render.mode", "Offscreen")
    systemProperty("udea.moba.gameAssets", gameAssetRoot.asFile.absolutePath)
    systemProperty(
        "udea.skyproof.dir",
        providers.gradleProperty("udea.skyproof.dir").orNull
            ?: layout.buildDirectory.dir("reports/udea/sky").get().asFile.absolutePath,
    )
}

// Issue #191's pictures. `LevelShot` saves a real match to a level file in one JVM and loads it
// into a fresh game in a second, photographing both from the same camera; the second run fails
// unless the two pictures are pixel-identical. Two tasks because the two halves must not share a
// process. Needs a GL driver, so run by name, for the reason `runMatchShot` gives.
val levelShotDir: String = providers.gradleProperty("udea.levelshot.dir").orNull
    ?: layout.buildDirectory.dir("reports/udea/level").get().asFile.absolutePath

val runLevelShotSave = tasks.register<JavaExec>("runLevelShotSave") {
    group = ApplicationPlugin.APPLICATION_GROUP
    description = "moba.levelshot, first half: plays the real level, saves it mid-fight, photographs it."
    mainClass.set("dev.wildware.moba.level.LevelShot")
    classpath = sourceSets.test.get().runtimeClasspath
    systemProperty("udea.render.mode", "Offscreen")
    systemProperty("udea.levelshot.phase", "save")
    systemProperty("udea.levelshot.dir", levelShotDir)
}

tasks.register<JavaExec>("runLevelShot") {
    group = ApplicationPlugin.APPLICATION_GROUP
    description = "moba.levelshot: loads the saved match into a fresh process and checks the picture matches."
    dependsOn(runLevelShotSave)
    mainClass.set("dev.wildware.moba.level.LevelShot")
    classpath = sourceSets.test.get().runtimeClasspath
    systemProperty("udea.render.mode", "Offscreen")
    systemProperty("udea.levelshot.phase", "load")
    systemProperty("udea.levelshot.dir", levelShotDir)
}

// Issue #192's picture: the launch level as it boots - the bundled test level, or the file
// `-Plevel` names - photographed on the boot tick from the scene's default camera.
tasks.register<JavaExec>("runLevelShotBoot") {
    group = ApplicationPlugin.APPLICATION_GROUP
    description = "moba.levelshot, boot: photographs the launch level (-Plevel=<path> or the test level) as it loads."
    mainClass.set("dev.wildware.moba.level.LevelShot")
    classpath = sourceSets.test.get().runtimeClasspath
    systemProperty("udea.render.mode", "Offscreen")
    systemProperty("udea.levelshot.phase", "boot")
    systemProperty("udea.levelshot.dir", levelShotDir)
}

// Issue #192: `-Plevel=<path>` names the `.udealevel` every task in this project that launches the
// game boots, instead of the bundled `levels/test_level.udealevel`. A relative path is resolved
// against the repository root, which is where the wrapper is run. Forwarded as `-Dmoba.level`
// because `JavaExec` forks and a Gradle property stops at the daemon; `MobaLaunchLevel` reads it.
// Read through `providers` so the configuration cache records it as an input.
val launchLevel: Provider<String> = providers.gradleProperty("level")
    .map { rootProject.layout.projectDirectory.file(it).asFile.absolutePath }
tasks.withType<JavaExec>().configureEach {
    launchLevel.orNull?.let { systemProperty("moba.level", it) }
}

/**
 * `moba.netproof`: one server, two clients, the real level, and three hashes that must agree. Run
 * by name rather than wired into `check`, because it prints a transcript that is the point of
 * running it; the assertion it embodies is covered by `MobaNetAgreementTest`.
 */
tasks.register<JavaExec>("runNetProof") {
    group = ApplicationPlugin.APPLICATION_GROUP
    description = "moba.netproof: server + 2 clients, perfect / 150ms+5% loss / TRELLO_8."
    mainClass.set("dev.wildware.moba.net.MobaNetProof")
    classpath = sourceSets.main.get().runtimeClasspath
}

tasks.register<JavaExec>("runClient") {
    group = ApplicationPlugin.APPLICATION_GROUP
    description = "moba.client: a visible window. Modes: local | listen | host [port] | join <host[:port]>."
    mainClass.set("dev.wildware.moba.entry.MobaClient")
    classpath = sourceSets.main.get().runtimeClasspath
    // Where `MobaDesktopAudio` reads the `.ogg` files from: `AssetPackCli` writes no blob sections,
    // so the sounds are not in the `.udeapak` and the device opens them from the game's own tree.
    systemProperty("udea.assets.root", gameAssetRoot.asFile.absolutePath)
    // `JavaExec` forks, so a `-D` on the Gradle command line reaches the daemon and stops there.
    // `MobaClient`'s knobs are how a two-window run is *checked* rather than watched - a bounded
    // frame count and a scripted axis are what turn two windows into two transcripts - so a task
    // that silently dropped them made the documented command a no-op. Read through `providers`
    // and not `System.getProperties()`: a configuration-time property read is exactly what the
    // configuration cache refuses to serialise.
    listOf(
        "udea.net.frames",
        "udea.net.walk",
        "udea.moba.fog",
        "udea.render.mode",
    ).forEach { name ->
        val value = providers.systemProperty(name)
        if (value.isPresent) systemProperty(name, value.get())
    }
}

// `runAudio` was here, and it is gone with LibGDX. It ran `MobaAudioProbe`, a second main whose
// only reason to exist was to build a `GdxAudioDevice`. `runClient` plays sound itself now, through
// `MobaDesktopAudio` and `udea-render`'s `koolAudioDevice` (issue #221), so there is nothing left
// for a separate probe to do.

/**
 * Where a test that reads this project's own tree finds it.
 *
 * `MobaReplayEqualityTest` reads **this** build script and the checked-in workflow, and
 * `MobaReplayFixturesCurrentTest` reads `src/test/resources/fixtures`, which is here because the
 * fixtures and the tasks that write them are. A relative path would resolve against the project
 * directory under Gradle and against the daemon's working directory under an IDE, and a source
 * scan that silently read nothing would pass.
 *
 * `:moba:game`'s test task sets the same property to its own directory, for `MobaAssetsTest` and
 * the source rules beside it, which scan the game's sources. Two projects, two trees, one property
 * name - and each test JVM is told about the tree its own scans are about.
 */
tasks.withType<Test>().configureEach {
    systemProperty("udea.moba.projectDir", layout.projectDirectory.asFile.absolutePath)
}

/**
 * The agent source set compiles as part of `check`, and not otherwise.
 *
 * Without this the only thing that ever compiles `src/agent` is somebody running
 * `:moba:desktop:run`, so a change to `udea-agent-host`'s API would leave the build green and the
 * launch path broken - discovered by the next person to try to launch an instance, which is the
 * worst possible moment. It is deliberately not on `assemble`: `assemble` is what a release build
 * runs, and compiling the agent entry point there would put its output where `udeaVerifyRelease`
 * has to reason about it.
 */
tasks.named("check") {
    dependsOn(agentSources.classesTaskName)
}

// --- udeaBenchStartup (issue #94) ---------------------------------------------------------
//
// The Phase 2 exit criterion "process start to first frame < 800ms", measured on a real `:moba`
// process rather than on a harness that resembles one.
//
// It is the criterion that proves the runtime script host is gone. The old path constructed a
// `BasicJvmScriptingHost` and compiled every `.udea.kts` during startup, mitigated only by an
// on-disk jar cache that a first run, a CI run or any changed script missed. There is no
// assertion that can prove a compiler is absent; a wall-clock gate on the whole boot is what
// notices if one creeps back, and `StartupClasspathTest` closes the same question from the other
// end by naming the artifacts.

/** JVM start to first presented frame, in millis. Spec 6's Phase 2 exit criterion. */
val startupBudgetMillis: Long =
    (providers.gradleProperty("udea.bench.budgetMillis").orNull ?: "800").toLong()

/** How many processes to launch. The median of these is what the gate compares. */
val startupRuns: Int = (providers.gradleProperty("udea.bench.runs").orNull ?: "5").toInt()

/**
 * Launches `moba.bench` N times and gates the median.
 *
 * A task class rather than a `doLast` because it has to fork a process per run and read the files
 * those processes write, and both of those need injected services to survive the configuration
 * cache.
 */
abstract class UdeaBenchStartupTask : DefaultTask() {

    @get:InputFiles
    abstract val runtimeClasspath: ConfigurableFileCollection

    @get:Input
    abstract val mainClass: Property<String>

    @get:Input
    abstract val runs: Property<Int>

    @get:Input
    abstract val budgetMillis: Property<Long>

    @get:OutputFile
    abstract val report: RegularFileProperty

    @get:Inject
    abstract val execOperations: ExecOperations

    @get:Inject
    abstract val layout: ProjectLayout

    @TaskAction
    fun bench() {
        val scratch = layout.buildDirectory.dir("tmp/udeaBenchStartup").get().asFile
        scratch.deleteRecursively()
        scratch.mkdirs()
        val samples = mutableListOf<Map<String, Double>>()
        for (run in 0 until runs.get()) {
            val out = scratch.resolve("run-$run.json")
            val result = execOperations.javaexec {
                mainClass.set(this@UdeaBenchStartupTask.mainClass)
                classpath = runtimeClasspath
                args("--exit-after-first-frame")
                systemProperty("udea.bench.out", out.absolutePath)
                systemProperty("udea.render.mode", "Offscreen")
                isIgnoreExitValue = true
            }
            if (result.exitValue != 0 || !out.isFile) {
                throw GradleException(
                    "moba.bench run $run exited ${result.exitValue} and wrote " +
                        (if (out.isFile) "a report" else "no report") +
                        ". A machine with no GL driver cannot run this gate; that is a red " +
                        "build and not a skip, because a silently skipped startup gate is how a " +
                        "regression ships.",
                )
            }
            samples += parse(out.readText())
        }
        val medians = summarise(samples)
        val median = medians.getValue("firstFrameMillis")
        val p95 = percentile(samples.map { it.getValue("firstFrameMillis") }, 0.95)
        val document = render(medians, median, p95, samples.size)
        val file = report.get().asFile
        file.parentFile.mkdirs()
        file.writeText(document)
        logger.lifecycle("[udeaBenchStartup] median ${fmt(median)}ms, p95 ${fmt(p95)}ms over ${samples.size} runs")
        logger.lifecycle(document)
        if (median > budgetMillis.get()) {
            throw GradleException(
                "process start to first frame is ${fmt(median)}ms over ${samples.size} runs, " +
                    "budget ${budgetMillis.get()}ms. The phase breakdown in ${file.absolutePath} " +
                    "names the phase that grew.",
            )
        }
    }

    private fun parse(json: String): Map<String, Double> =
        Regex("""["](\w+)["]\s*:\s*([0-9.]+)""").findAll(json)
            .associate { it.groupValues[1] to it.groupValues[2].toDouble() }

    /** The per-phase median. A median per phase, not the phases of the median run. */
    private fun summarise(samples: List<Map<String, Double>>): Map<String, Double> =
        samples.flatMap { it.keys }.distinct().sorted().associateWith { key ->
            percentile(samples.mapNotNull { it[key] }, 0.5)
        }

    /** Nearest-rank, so a five-run p95 is the slowest run rather than an interpolation of it. */
    private fun percentile(values: List<Double>, fraction: Double): Double {
        require(values.isNotEmpty()) { "no samples" }
        val sorted = values.sorted()
        val rank = Math.ceil(fraction * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }

    private fun render(medians: Map<String, Double>, median: Double, p95: Double, runs: Int): String =
        buildString {
            append("{\n")
            append("  \"runs\": ").append(runs).append(",\n")
            append("  \"budgetMillis\": ").append(budgetMillis.get()).append(",\n")
            append("  \"medianFirstFrameMillis\": ").append(fmt(median)).append(",\n")
            append("  \"p95FirstFrameMillis\": ").append(fmt(p95)).append(",\n")
            append("  \"phaseMedians\": {\n")
            val phases = listOf("jvmStartToMainMillis", "assetMillis", "glMillis", "worldMillis")
            phases.forEachIndexed { i, phase ->
                append("    \"").append(phase).append("\": ")
                append(fmt(medians[phase] ?: 0.0))
                append(if (i == phases.lastIndex) "\n" else ",\n")
            }
            append("  }\n")
            append("}\n")
        }

    private fun fmt(value: Double): String {
        val tenths = Math.round(value * 10.0)
        return "${tenths / 10}.${tenths % 10}"
    }
}

val udeaBenchStartup = tasks.register<UdeaBenchStartupTask>("udeaBenchStartup") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Fails when median moba process-start-to-first-frame exceeds the Phase 2 budget."
    runtimeClasspath.from(sourceSets.main.map { it.runtimeClasspath })
    mainClass.set("dev.wildware.moba.entry.MobaBench")
    runs.set(startupRuns)
    budgetMillis.set(startupBudgetMillis)
    report.set(layout.buildDirectory.file("reports/udea/startup.json"))
}

/**
 * The three-process UDP proof is asked for by name, and is not a gate on `check`.
 *
 * Same reasoning as `runShot` and `runMatchShot` above, for a different scarce resource. It forks
 * three JVMs that each seed the whole level and then tick against a **wall clock** at 60Hz over
 * real sockets. Under `./gradlew build` those three compete with every other Gradle worker on the
 * machine, miss their tick deadlines, and the reading drifts - so wiring it into `check` would buy
 * a proof that is red on a loaded laptop and green on an idle one, which is worth less than no
 * proof at all.
 *
 * The claim itself is not left unguarded. `MobaNetProof` and the loopback session run the same
 * replication path deterministically, on one thread with a manual clock, inside `check`. What this
 * adds is the part that only separate processes can show:
 *
 * ```
 * ./gradlew :moba:desktop:runUdpProof
 * ```
 */
tasks.register<Test>("runUdpProof") {
    group = ApplicationPlugin.APPLICATION_GROUP
    description = "moba: the whole battle over real UDP, three OS processes, perfect and lossy."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    filter { includeTestsMatching("dev.wildware.moba.net.MobaUdpTwoProcessTest") }
    testLogging { showStandardStreams = true }
    outputs.upToDateWhen { false }
}

tasks.test {
    // See `runUdpProof`: wall-clock timing across three forked JVMs, which `check` cannot give it.
    filter { excludeTestsMatching("dev.wildware.moba.net.MobaUdpTwoProcessTest") }

    // Issue #191: where `LevelSaveLoadProofTest` keeps the `.udealevel` it saved and the
    // transcript of what it measured. A Gradle property rather than `-D`, because `Test` forks and
    // a `-D` on the command line stops at the daemon; unset, the test writes nothing and still
    // asserts.
    providers.gradleProperty("udea.levelEvidenceDir").orNull?.let {
        systemProperty("udea.levelEvidenceDir", it)
    }

    /*
     * `--update-replay-fixtures`, forwarded to the JVM that reads it.
     *
     * A `Test` task forks its own JVM and does not inherit the launcher's system properties, so a
     * flag that is not passed here is a flag `MobaReplayFixturesCurrentTest` never sees - it would
     * report every fixture current and rebuild nothing, silently, and a reviewer would read a
     * green run beside a fixture that never moved.
     */
    systemProperty(
        "update.replay.fixtures",
        providers.systemProperty("update.replay.fixtures").orElse("false").get(),
    )

    // `MobaReplayEqualityTest` reads this project's build script and the workflow, and neither is
    // on any classpath. Declared so an edit to either makes the task rerun - found the same way
    // `udea-core`'s FieldMask scan was: a source rule that reads a tree it has not declared
    // reports whatever it last saw.
    inputs.file(layout.projectDirectory.file("build.gradle.kts"))
        .withPropertyName("mobaDesktopBuildScript")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(rootProject.layout.projectDirectory.file(".github/workflows/ci.yml"))
        .withPropertyName("ciWorkflow")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

tasks.test {
    // A *local* val, captured by the `doFirst` below. A script-level property here would make the
    // lambda hold a reference to the build script object, which the configuration cache refuses to
    // serialize - the build fails at store time rather than at run time, which is how this was
    // found.
    val names: Provider<String> = configurations.named("runtimeClasspath")
        .map { it.files.joinToString(File.pathSeparator) { file -> file.name } }
    inputs.property("runtimeClasspathNames", names)
    doFirst {
        systemProperty("udea.moba.runtimeClasspathNames", names.get())
    }
}

// Deliberately NOT wired into `check`. The gate forks a process that creates a real Kool context,
// so on a machine with no GL driver it is a red build rather than a skip (see the GradleException
// above) - which is the right behaviour for a gate that is *asked for*, and the wrong behaviour
// for `./gradlew build` on a developer's headless container. CI runs it explicitly, under xvfb,
// alongside `udeaGlTest`. `udeaBenchStartup` is referenced here so a configuration error in it is
// still a configuration error for everyone.
require(udeaBenchStartup.name == "udeaBenchStartup")

// --- the agent entry point is launchable, not merely compilable --------------------------------
//
// `check` compiles `src/agent` above, which catches an API break. It does not catch an entry point
// the JVM will not start: an `object`'s `@JvmStatic` detached from its `main` compiles cleanly and
// fails only at launch, with `gamebridge.json` pointing `game-bridge-mcp` straight at it. See
// `AgentEntryPointTest`, which loads the class from this directory and looks at nothing else.
tasks.test {
    dependsOn(agentSources.classesTaskName)
    // The whole agent runtime classpath, not just this module's output: reflecting on `MobaAgent`
    // resolves the signatures of every method it declares, and those name `udea-agent-host` types.
    // A loader given only the class output throws `NoClassDefFoundError` before it can look at
    // `main`.
    val agentClasses = agentSources.runtimeClasspath
    inputs.files(agentClasses).withPropertyName("agentClasses")
    doFirst {
        systemProperty("udea.moba.agentClasses", agentClasses.asPath)
    }
}

// --- the cross-OS replay-equality gate, pointed at this game (issue #172) ----------------------
//
// ## Why the logic is in tasks and classes rather than in the workflow YAML
//
// Nobody can run GitHub Actions locally, so anything expressed only in `ci.yml` is unverifiable
// until it has already gone wrong once on a branch somebody merged. Everything a leg does -
// replay, write the digest, compare two of them, render the divergence, choose the exit code - is
// a class with a `main` and a task that drives it, and `ci.yml` is a few `./gradlew` lines over the
// top. `udeaReplayEqualityProof` below runs the whole shape on one machine, including the half
// that has to fail.
//
// None of these is wired into `check`. A digest is a multi-second replay whose output is only
// meaningful beside another machine's, and the proof starts five JVMs - the same reason
// `runUdpProof` and `runLaneShot` are named tasks rather than `check` dependencies.

val replayEqualityDir: Provider<Directory> =
    layout.buildDirectory.dir("reports/udea/replay-equality")

/**
 * The base every relative path handed to these tasks is resolved against: the repository root.
 *
 * Issue #169, and it is not this project's to rediscover. A `JavaExec` with no `workingDir`
 * inherits the *project* directory, so `-Pudea.replay.out=digests/x.udeaeq` would write into
 * `moba/desktop/digests/` while `actions/upload-artifact` globs `digests/` under
 * `$GITHUB_WORKSPACE`. Both spellings are identical and both resolve somewhere else; the upload
 * fails, and because the join declares `needs:` the leg job, no verdict is ever produced.
 */
val replayWorkspaceRoot: String = rootProject.layout.projectDirectory.asFile.absolutePath

/**
 * The classpath every entry point below runs on.
 *
 * The **test** runtime classpath, and that is a release gate rather than a preference.
 * `ReleaseRules.CLASSPATH_RULE` (UDEA-REL-002) is about `runtimeClasspath`; a fixture pilot and a
 * digest entry point are CI machinery and have no business inside the shipped jar, so they live in
 * `src/test` beside `MatchShot` and `LaneShot` for the same reason those do.
 */
val replayEqualityClasspath: FileCollection = sourceSets.test.get().runtimeClasspath

/**
 * The launcher a digest runs on, honouring `-Pudea.replay.jvm=<feature version>`.
 *
 * The second axis of the matrix is a second JVM, and it is not decoration:
 * `determinism-audit.md` section 3.1 measured `Math.sin` disagreeing with `StrictMath.sin` on 3.4%
 * of sampled inputs on a single JVM, and two implementations are under no obligation to disagree
 * in the same places.
 */
val replayDigestLauncher: Provider<JavaLauncher> = providers.provider {
    val version = providers.gradleProperty("udea.replay.jvm").orNull
    val vendor = providers.gradleProperty("udea.replay.jvmVendor").orNull
    if (version == null && vendor == null) {
        javaToolchains.launcherFor(java.toolchain).get()
    } else {
        javaToolchains.launcherFor {
            languageVersion.set(
                version?.let { JavaLanguageVersion.of(it) } ?: java.toolchain.languageVersion.get(),
            )
            if (vendor != null) this.vendor.set(JvmVendorSpec.matching(vendor))
        }.get()
    }
}

/**
 * Replays a checked-in `moba` recording and writes this machine's digest stream.
 *
 * One of these runs per matrix leg in CI, each with its own `--label`. `-Pudea.replay.label` and
 * `-Pudea.replay.out` are how the workflow names them; the defaults describe a local run, so the
 * task is runnable by hand with no properties at all.
 */
tasks.register<JavaExec>("udeaReplayDigest") {
    group = "verification"
    description = "Replays a checked-in moba .udearep and writes this machine's .udeaeq digest."
    classpath = replayEqualityClasspath
    mainClass.set("dev.wildware.moba.replay.MobaDigestMain")
    javaLauncher.set(replayDigestLauncher)
    val label = providers.gradleProperty("udea.replay.label").orElse("local")
    val out = providers.gradleProperty("udea.replay.out")
        .orElse(replayEqualityDir.map { "${it.asFile}/local.udeaeq" })
    val plantAt = providers.gradleProperty("udea.replay.plantUlpAt")
    val fixture = providers.gradleProperty("udea.replay.fixture")
    val reports = replayEqualityDir
    val workspace = replayWorkspaceRoot
    argumentProviders.add {
        buildList {
            add("--workspace")
            add(workspace)
            add("--label")
            add(label.get())
            add("--out")
            add(out.get())
            add("--timing")
            add("${reports.get().asFile}/${label.get().replace('/', '-')}.timing.txt")
            if (fixture.isPresent) {
                add("--fixture")
                add(fixture.get())
            }
            if (plantAt.isPresent) {
                add("--plant-ulp-at")
                add(plantAt.get())
            }
        }
    }
}

/**
 * `--update-replay-fixtures` (issue #165): rewrites every checked-in `.udearep` of this game.
 *
 * ```
 * ./gradlew :moba:desktop:udeaWriteReplayFixture
 * ```
 *
 * Nothing depends on this and nothing in CI runs it: regenerating a fixture is how a gate gets
 * silenced, so it is a command somebody types on purpose.
 */
tasks.register<JavaExec>("udeaWriteReplayFixture") {
    group = "build"
    description = "Rebuilds moba's checked-in .udearep replay-equality fixtures."
    classpath = replayEqualityClasspath
    mainClass.set("dev.wildware.moba.replay.MobaFixturesMain")
    val fixturesDir = layout.projectDirectory.dir("src/test/resources/fixtures")
    argumentProviders.add { listOf("--fixtures-dir", fixturesDir.asFile.absolutePath) }
}

// --- the local proof --------------------------------------------------------------------------
//
// Five processes: three digests and two joins. The digests are separate JVMs on purpose - two runs
// inside one process share a warmed JIT, a loaded class hierarchy and one set of static
// initialisers, which is most of what a cross-process comparison is asking about.

val replayProofDir: Provider<Directory> =
    layout.buildDirectory.dir("reports/udea/replay-equality/proof")

/**
 * `MobaFixture.PLANT_TICK`, as a literal.
 *
 * A Gradle script cannot read a Kotlin constant out of a source set it is about to compile.
 * `MobaReplayEqualityTest` asserts this file and that constant agree, so the duplication fails a
 * test rather than drifting quietly.
 */
val replayPlantTick = "1200"

fun registerReplayProofDigest(name: String, label: String, file: String, plantAt: String?) =
    tasks.register<JavaExec>(name) {
        group = "verification"
        description = "replay-equality proof: writes $file"
        classpath = replayEqualityClasspath
        mainClass.set("dev.wildware.moba.replay.MobaDigestMain")
        javaLauncher.set(replayDigestLauncher)
        val root = replayProofDir
        argumentProviders.add {
            buildList {
                add("--label")
                add(label)
                add("--out")
                add("${root.get().asFile}/$file")
                if (plantAt != null) {
                    add("--plant-ulp-at")
                    add(plantAt)
                }
            }
        }
    }

fun registerReplayProofJoin(
    name: String,
    first: String,
    second: String,
    summary: String,
    after: List<Any>,
) = tasks.register<JavaExec>(name) {
    group = "verification"
    description = "replay-equality proof: joins $first and $second"
    dependsOn(after)
    classpath = replayEqualityClasspath
    mainClass.set("dev.wildware.udea.replay.equality.ReplayEqualsMain")
    // The planted half exists to exit non-zero, so the task has to survive that and let
    // `udeaReplayEqualityProof` decide what it meant.
    isIgnoreExitValue = true
    val root = replayProofDir
    argumentProviders.add {
        val dir = root.get().asFile
        listOf("--summary", "$dir/$summary", "$dir/$first", "$dir/$second")
    }
    // Written here rather than read through `executionResult` from the aggregate task: that
    // provider is only resolvable from inside the task that produced it, and querying it from a
    // sibling fails at execution time with "this provider has no value available".
    val exitFile = root.map { it.file("$summary.exit") }
    doLast {
        exitFile.get().asFile.writeText(executionResult.get().exitValue.toString())
    }
}

val replayProofDigestA =
    registerReplayProofDigest("udeaReplayProofDigestA", "proof/leg-a", "leg-a.udeaeq", null)
val replayProofDigestB =
    registerReplayProofDigest("udeaReplayProofDigestB", "proof/leg-b", "leg-b.udeaeq", null)
val replayProofDigestPlanted = registerReplayProofDigest(
    "udeaReplayProofDigestPlanted", "proof/leg-planted", "planted.udeaeq", replayPlantTick,
)

val replayProofJoinEqual = registerReplayProofJoin(
    "udeaReplayProofJoinEqual", "leg-a.udeaeq", "leg-b.udeaeq", "equal.txt",
    listOf(replayProofDigestA, replayProofDigestB),
)
val replayProofJoinPlanted = registerReplayProofJoin(
    "udeaReplayProofJoinPlanted", "leg-a.udeaeq", "planted.udeaeq", "planted.txt",
    listOf(replayProofDigestA, replayProofDigestPlanted),
)

/**
 * **The evidence command.** Two honest `moba` legs agree, and a one-ulp leg is caught and named.
 *
 * ```
 * ./gradlew :moba:desktop:udeaReplayEqualityProof
 * ```
 *
 * Both halves matter and the second is the one usually missing. A gate that has only ever been
 * seen to pass is a gate nobody has watched fail, and `docs/engineering-standards.md` section 8
 * lists "a test that cannot fail" as a rejection.
 */
tasks.register("udeaReplayEqualityProof") {
    group = "verification"
    description =
        "Proves the moba replay-equality gate both ways: two honest legs agree, and a one-ulp " +
            "leg fails with the tick, the entity, the component and the field named."
    dependsOn(replayProofJoinEqual, replayProofJoinPlanted)

    val equalSummary = replayProofDir.map { it.file("equal.txt") }
    val plantedSummary = replayProofDir.map { it.file("planted.txt") }
    val equalExitFile = replayProofDir.map { it.file("equal.txt.exit") }
    val plantedExitFile = replayProofDir.map { it.file("planted.txt.exit") }
    val expectedTick = replayPlantTick

    doLast {
        val equalReport = equalSummary.get().asFile.readText()
        val plantedReport = plantedSummary.get().asFile.readText()
        val equalExit = equalExitFile.get().asFile.readText().trim().toInt()
        val plantedExit = plantedExitFile.get().asFile.readText().trim().toInt()

        println("=== two honest moba legs, two separate JVM processes ===")
        println(equalReport)
        println("=== a third leg carrying a planted one-ulp divergence ===")
        println(plantedReport)

        check(equalExit == 0) {
            "two honest legs of the same moba fixture disagreed. That is either a real " +
                "determinism defect in this build or a broken gate.\n" + equalReport
        }
        check(plantedExit == 1) {
            "a leg with a deliberately planted one-ulp divergence was NOT caught (exit " +
                plantedExit + "). A gate that cannot fail proves nothing.\n" + plantedReport
        }
        // `Tick.toString()` renders `t1200`. Spec 7 asks a cross-OS failure to name the tick, the
        // entity, the component and field, and five ticks of that field's history; issue #165 adds
        // the block saying how to reproduce it on one machine.
        val required = listOf(
            "at t$expectedTick",
            "Position.x",
            "NetId(",
            "the preceding 5 tick(s)",
            "--- reproducing this locally ---",
            "replay.seek    {\"tick\": ${expectedTick.toInt() - 1}}",
        )
        for (needle in required) {
            check(plantedReport.contains(needle)) {
                "the planted divergence report does not contain '$needle', so it does not name " +
                    "what issue #152 requires it to name.\n" + plantedReport
            }
        }
        println(
            "moba replay-equality proof PASSED: two honest legs agree (exit 0); the planted leg " +
                "fails (exit 1) naming Position.x at t$expectedTick, with five ticks of history.",
        )
    }
}
