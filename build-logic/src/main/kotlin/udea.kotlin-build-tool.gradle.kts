import dev.wildware.udea.build.UdeaKotlinPin
import dev.wildware.udea.build.UdeaVersions
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.jetbrains.kotlin.gradle.plugin.getKotlinPluginVersion

/**
 * Convention for build-time-only modules — `udea-codegen`, `udea-compiler-plugin`,
 * `udea-assets-compiler`. Nothing on this convention ships in a game's runtime classpath.
 *
 * These modules are pinned to the exact project Kotlin version (spec 7): a K2 plugin or a
 * scripting host built against a different compiler than the one loading it fails at
 * class-load time.
 *
 * Two different things have to be pinned, and only one of them was:
 *
 * 1. the **Kotlin Gradle plugin** actually running the build. `getKotlinPluginVersion()`
 *    below covers that, and it is close to tautological — both sides come from the same
 *    catalog entry — but it is the cheap guard against someone applying a different KGP.
 * 2. the **kotlin-stdlib actually resolved**, which the catalog does *not* control.
 *    Gradle resolves the highest requested version, and KotlinPoet and Fleks each request
 *    a newer stdlib than this project's compiler. Since these jars are loaded *inside*
 *    that compiler, and stdlib class loading is parent-first, they would run against
 *    ${UdeaVersions.KOTLIN} while having been compiled against something newer — a green
 *    build and a `NoSuchMethodError` in someone else's. `udeaVerifyKotlinPin` is what
 *    turns that into a build failure instead of a mystery.
 */

plugins {
    id("udea.kotlin-library")
}

private val kotlinInUse = getKotlinPluginVersion()

check(kotlinInUse == UdeaVersions.KOTLIN) {
    "$path is pinned to Kotlin ${UdeaVersions.KOTLIN} but the build is running Kotlin " +
        "$kotlinInUse. Build-time-only modules must match the project Kotlin version exactly; " +
        "update gradle/libs.versions.toml and UdeaVersions.KOTLIN together."
}

extensions.extraProperties["udeaPinnedKotlinVersion"] = UdeaVersions.KOTLIN

/**
 * The two classpaths that decide what this jar is compiled against and what it publishes.
 *
 * Test classpaths are deliberately **not** pinned: `udea-codegen`'s tests run a real KSP
 * standalone compiler in the test JVM, and that compiler brings — and needs — its own
 * newer stdlib (`kotlin/jvm/internal/KotlinGenericDeclaration` does not exist in 2.2.10).
 * The test JVM is not the classloader the processor is loaded by, so pinning it would
 * break a harness to protect nothing.
 */
private val pinnedClasspaths = setOf("compileClasspath", "runtimeClasspath")

/**
 * Resolve `kotlin-stdlib` at the compiler's own version, whatever a transitive dependency
 * asks for. Downgrading a library's stdlib is normally the unsupported direction; here it
 * is the *only* honest one, because parent-first class loading already hands these jars
 * the compiler's stdlib at run time. Compiling against anything else only hides that.
 */
configurations.matching { it.name in pinnedClasspaths }.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.kotlin" && requested.name.startsWith("kotlin-stdlib")) {
            useVersion(UdeaVersions.KOTLIN)
            because(
                "loaded by the Kotlin ${UdeaVersions.KOTLIN} compiler (spec 7); stdlib class " +
                    "loading is parent-first, so this is the stdlib it will actually run against",
            )
        }
    }
}

/**
 * `<configuration> <module>:<version>` for every `kotlin-stdlib*` artifact this module
 * resolves, on both the classpath it compiles against and the one it runs on. Mapped to
 * plain strings at the provider level so the task holds nothing the configuration cache
 * cannot serialise.
 */
private val resolvedStdlibs: Provider<List<String>> =
    pinnedClasspaths.sorted()
        .map { configurationName ->
            configurations.named(configurationName).flatMap { configuration ->
                configuration.incoming.artifacts.resolvedArtifacts.map { artifacts ->
                    artifacts.mapNotNull { it.id.componentIdentifier as? ModuleComponentIdentifier }
                        .filter { it.group == "org.jetbrains.kotlin" && it.module.startsWith("kotlin-stdlib") }
                        .map { "$configurationName ${it.module}:${it.version}" }
                        .distinct()
                        .sorted()
                }
            }
        }
        .reduce { left, right -> left.zip(right) { a, b -> a + b } }

/**
 * Fails if this module resolves a `kotlin-stdlib` other than the pinned compiler version.
 *
 * The rule lives in `UdeaKotlinPin`, where `UdeaKotlinPinTest` executes its failure paths;
 * a `doLast` block is not reachable from any test. Deleting the `eachDependency` pin above
 * makes this task red on `udea-codegen`, which is how you can tell it is load-bearing
 * rather than decorative.
 */
val udeaVerifyKotlinPin by tasks.registering {
    group = "verification"
    description = "Fails if a build-time-only module resolves a kotlin-stdlib other than the pinned compiler version."

    val stdlibs = resolvedStdlibs
    val pinned = UdeaVersions.KOTLIN
    val projectPath = project.path
    val report = layout.buildDirectory.file("reports/udea/kotlin-stdlib-pin.txt")

    inputs.property("pinnedKotlinVersion", pinned)
    inputs.property("resolvedStdlibs", stdlibs)
    outputs.file(report)

    doLast {
        val resolved = stdlibs.get()
        report.get().asFile.apply {
            parentFile.mkdirs()
            writeText(resolved.joinToString(separator = "\n", postfix = "\n"))
        }
        UdeaKotlinPin.violation(projectPath, pinned, resolved)?.let { throw GradleException(it) }
    }
}

tasks.named("check") {
    dependsOn(udeaVerifyKotlinPin)
}
