/**
 * Hollow's desktop launcher (epic #245, issue #249): the listen server with its local player, the
 * dedicated server, the networked client, and the task that photographs the clearing. JVM-only, like
 * `:moba:desktop`, and for the same reason: a launcher opens a Kool context, binds sockets and reads
 * system properties, and the game it launches must not be able to name any of them.
 *
 * Smaller than moba's launcher on purpose. There is no `agent` source set and no `runEditor` yet:
 * the agent surface and the editor are issue #255.
 */

plugins {
    id("udea.kotlin-library")
}

dependencies {
    // `api`: the launcher hands `HollowGame`'s definition to a `GameHost`.
    api(project(":hollow:game"))
    implementation(project(":udea-core"))
    implementation(project(":udea-render"))
    implementation(project(":udea-assets"))
    // The UDP transport the servers and clients talk over.
    implementation(project(":udea-net"))
}

/**
 * The game's asset root, which the models are read from. `loadModel` reads a `.glb` from disk, as
 * `moba`'s launcher does for its fox; the packed bundle names each model's file under this root.
 */
val gameAssetRoot: Directory = project(":hollow:game").layout.projectDirectory.dir("assets")

/** `-Plevel=<path>`, resolved against the repository root and forwarded to every run task. */
val launchLevel: Provider<String> = providers.gradleProperty("level")
    .map { rootProject.layout.projectDirectory.file(it).asFile.absolutePath }

tasks.withType<JavaExec>().configureEach {
    systemProperty("hollow.assets.root", gameAssetRoot.asFile.absolutePath)
    launchLevel.orNull?.let { systemProperty("hollow.level", it) }
}

tasks.register<JavaExec>("run") {
    group = ApplicationPlugin.APPLICATION_GROUP
    description = "hollow: a listen server on UDP 27025 and a window that plays on it (-Plevel=<path> for another level)."
    mainClass.set("dev.wildware.hollow.desktop.HollowDesktop")
    classpath = sourceSets.main.get().runtimeClasspath
}

tasks.register<JavaExec>("runServer") {
    group = ApplicationPlugin.APPLICATION_GROUP
    description = "hollow.server: the headless dedicated server on UDP 27025, or --args=\"<port>\"."
    mainClass.set("dev.wildware.hollow.desktop.HollowServerMain")
    classpath = sourceSets.main.get().runtimeClasspath
    providers.gradleProperty("hollow.server.ticks").orNull?.let { systemProperty("hollow.server.ticks", it) }
}

tasks.register<JavaExec>("runClient") {
    group = ApplicationPlugin.APPLICATION_GROUP
    description = "hollow.client: a window as a networked player. --args=\"host [port]\" or --args=\"join <host[:port]>\"."
    mainClass.set("dev.wildware.hollow.desktop.HollowClientMain")
    classpath = sourceSets.main.get().runtimeClasspath
}

/**
 * `runShot`: boots Offscreen, lets the clearing's models load, and writes one PNG. Run by name and
 * never by `check`, because it needs a GL driver and a gate that skips without one hides the very
 * failure it exists to show.
 */
tasks.register<JavaExec>("runShot") {
    group = ApplicationPlugin.APPLICATION_GROUP
    description = "hollow.shot: captures the lit clearing to -Pudea.shot.out=<png>."
    mainClass.set("dev.wildware.hollow.desktop.HollowShot")
    classpath = sourceSets.main.get().runtimeClasspath
    systemProperty("udea.render.mode", "Offscreen")
    systemProperty(
        "udea.shot.out",
        providers.gradleProperty("udea.shot.out").orNull
            ?: layout.buildDirectory.file("reports/udea/clearing.png").get().asFile.absolutePath,
    )
}

/**
 * Writes `hollow/game/levels/clearing.udealevel` from `ClearingLayout`, the layout in code the
 * clearing was first made from. An authoring tool, run by name: once the level is edited in the
 * editor (issue #255), the file is the source and this would overwrite the edits.
 */
tasks.register<JavaExec>("udeaWriteClearing") {
    group = "build"
    description = "Rewrites hollow/game/levels/clearing.udealevel from ClearingLayout."
    mainClass.set("dev.wildware.hollow.desktop.ClearingWriter")
    classpath = sourceSets.test.get().runtimeClasspath
    args(project(":hollow:game").layout.projectDirectory.file("levels/clearing.udealevel").asFile.absolutePath)
}
