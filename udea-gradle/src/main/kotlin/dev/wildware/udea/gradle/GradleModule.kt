package dev.wildware.udea.gradle

/**
 * The Gradle plugin: tasks, verifiers and gamebridge.json emission.
 *
 * gradleApi() is compileOnly (see the udea.gradle-plugin convention) and no game module
 * depends on this project, so the Gradle API cannot reach a game's runtime classpath the
 * way it did through the old gradle-plugin module.
 *
 * This object is a placeholder so the module has a source root and appears in an IDE
 * sync. Later Phase 0 waves replace it with the real declarations.
 */
internal object GradleModule
