import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "2.2.10"
    id("java")
    id("org.jetbrains.compose") version "1.7.3"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20"

    // Phase 0 build gates from the `build-logic` included build. Applied to the rewrite
    // subprojects below, never to the root or to the old tree.
    id("udea.legacy-dependency-check") apply false
    id("udea.module-graph-check") apply false
    id("udea.release-check") apply false
}

group = "dev.wildware.udea"
version = "1.0-SNAPSHOT"

allprojects {
    apply(plugin = "java")

    repositories {
        mavenCentral()
        mavenLocal()
        gradlePluginPortal()
        google()
        maven("https://oss.sonatype.org/content/repositories/snapshots/")
        maven("https://s01.oss.sonatype.org/content/repositories/snapshots/")
        maven("https://s01.oss.sonatype.org")
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
        maven("https://jitpack.io")
    }

    java {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
}

sourceSets {
    create("integrationTest") {
        compileClasspath += sourceSets.main.get().output
        runtimeClasspath += sourceSets.main.get().output
    }
}

val integrationTestImplementation by configurations.getting {
    extendsFrom(configurations.testImplementation.get())
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    implementation(kotlin("reflect"))
    implementation(compose.desktop.currentOs) {
        exclude("org.jetbrains.compose.material")
    }
    implementation("com.bybutter.compose:compose-jetbrains-expui-theme:2.0.0")
    implementation("org.jetbrains.skiko:skiko-awt:0.8.11")
    implementation(project(":level-editor"))
    implementation(project(":common"))

    integrationTestImplementation("org.junit.jupiter:junit-jupiter:5.7.1")
    integrationTestImplementation("org.kodein.di:kodein-di-jvm:7.20.2")
    integrationTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.10.1")
}

// --- Phase 0 build gates (spec 4, spec 6, spec 7) ------------------------------------
//
// Wired here rather than in each module's build script for two reasons: a gate a module opts
// into is a gate a new module forgets, and these files are owned by whoever owns the module,
// which is the wrong person to be able to switch off the rule that stops the old tree leaking
// into the new one.

/** Gradle paths of the rewrite tree: everything the Phase 0 gates apply to. */
val rewriteProjects = subprojects.filter { it.path.startsWith(":udea-") || it.path == ":moba" }

subprojects {
    if (this in rewriteProjects) {
        apply(plugin = "udea.legacy-dependency-check")
        apply(plugin = "udea.module-graph-check")
    }
    // The release gate lives on the one project that actually ships a jar.
    if (path == ":moba") {
        apply(plugin = "udea.release-check")
    }
}

/**
 * Aggregates, so a developer can run one gate over the whole tree. Each depends on the
 * per-project task by path; the per-project tasks are also on their own `check`, so a plain
 * `./gradlew build` cannot pass while a rule is broken.
 */
val udeaVerifyNoLegacyDependencies by tasks.registering {
    group = "verification"
    description = "Runs udeaVerifyNoLegacyDependencies on every udea-* project and on moba."
    dependsOn(rewriteProjects.map { "${it.path}:udeaVerifyNoLegacyDependencies" })
}

val udeaVerifyModuleGraph by tasks.registering {
    group = "verification"
    description = "Runs udeaVerifyModuleGraph on every udea-* project and on moba."
    dependsOn(rewriteProjects.map { "${it.path}:udeaVerifyModuleGraph" })
}

val udeaVerifyRelease by tasks.registering {
    group = "verification"
    description = "Runs the release artifact scan on the shipping project."
    dependsOn(":moba:udeaVerifyRelease")
}

/**
 * `assemble` for the rewrite tree only.
 *
 * The clean-build budget (spec 6, Phase 0 exit: <90s) is measured against this. Budgeting
 * `assemble` instead would measure `idea-plugin` and `compose-ui` downloading an IntelliJ
 * distribution, which would dominate the number and make the gate meaningless.
 */
val udeaAssemble by tasks.registering {
    group = "build"
    description = "Assembles every udea-* project and moba, and nothing from the old tree."
    dependsOn(rewriteProjects.map { "${it.path}:assemble" })
}

tasks.named("check") {
    dependsOn(udeaVerifyNoLegacyDependencies, udeaVerifyModuleGraph)
}
