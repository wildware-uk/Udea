import org.gradle.api.attributes.java.TargetJvmEnvironment
import org.gradle.api.attributes.java.TargetJvmVersion
import dev.wildware.udea.build.UdeaVersions
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget

/**
 * `java-test-fixtures` for a multiplatform module's JVM target (issue #201).
 *
 * Gradle's `java-test-fixtures` cannot be applied beside the Kotlin multiplatform plugin, and
 * `udea-diagnostics` has JVM consumers that ask for its fixtures the ordinary way -
 * `testImplementation(testFixtures(project(":udea-diagnostics")))`. This recreates the part of
 * that plugin those consumers see, so none of them changes:
 *
 * - a `testFixtures` compilation of the `jvm` target, source set `jvmTestFixtures`, which sees
 *   `jvmMain` and is seen by `jvmTest`;
 * - its jar, `<name>-jvm-<version>-test-fixtures.jar`;
 * - two consumable variants carrying the `<group>:<name>-test-fixtures` capability, which is the
 *   capability `testFixtures(...)` requests. Their attributes are those of a JVM library, so a
 *   JVM classpath selects them exactly as it selects the module's `jvm` variant.
 *
 * JVM only, and deliberately: a fixture here exists to serve JVM consumers, and the one that
 * needed this reads the JVM's own management beans.
 *
 * Apply after `udea.kotlin-multiplatform`, which declares the `jvm` target.
 */

val kotlinExtension = extensions.getByType<KotlinMultiplatformExtension>()
val jvmTarget = kotlinExtension.targets.getByName("jvm") as KotlinJvmTarget
val mainCompilation = jvmTarget.compilations.getByName("main")
val fixturesCompilation = jvmTarget.compilations.create("testFixtures") {
    associateWith(mainCompilation)
}
jvmTarget.compilations.getByName("test").associateWith(fixturesCompilation)

val fixturesJar = tasks.register<Jar>("jvmTestFixturesJar") {
    description = "Assembles the JVM test fixtures jar."
    group = "build"
    archiveAppendix.set("jvm")
    archiveClassifier.set("test-fixtures")
    from(fixturesCompilation.output.allOutputs)
}

val capability = "$group:${project.name}-test-fixtures:$version"

/**
 * The module's own `jvm` variant, which both fixture variants expose as a dependency, as
 * `java-test-fixtures` makes a project's fixtures depend on its production code.
 *
 * A bucket of its own because a consumable configuration cannot declare dependencies directly,
 * and putting it on the fixture compilation's `api` would make that compilation resolve its own
 * project, which `associateWith` above already covers.
 */
val productionCode = configurations.dependencyScope("jvmTestFixturesProductionCode") {
    dependencies.add(project.dependencies.create(project))
}

/**
 * One consumable variant of the fixtures.
 *
 * @param usage `java-api` for compile classpaths, `java-runtime` for runtime classpaths.
 * @param dependencyBuckets the fixture compilation's own dependency buckets this variant exposes.
 */
fun fixturesVariant(name: String, usage: String, dependencyBuckets: List<String>) {
    configurations.consumable(name) {
        dependencyBuckets.forEach { extendsFrom(configurations.getByName(it)) }
        extendsFrom(productionCode.get())
        attributes {
            attribute(Usage.USAGE_ATTRIBUTE, objects.named(usage))
            attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
            attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
            attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
            attribute(TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE, objects.named(TargetJvmEnvironment.STANDARD_JVM))
            attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, UdeaVersions.JVM_TOOLCHAIN)
            attribute(KotlinPlatformType.attribute, KotlinPlatformType.jvm)
        }
        outgoing.capability(capability)
        outgoing.artifact(fixturesJar)
    }
}

fixturesVariant(
    name = "jvmTestFixturesApiElements",
    usage = Usage.JAVA_API,
    dependencyBuckets = listOf(fixturesCompilation.apiConfigurationName),
)
fixturesVariant(
    name = "jvmTestFixturesRuntimeElements",
    usage = Usage.JAVA_RUNTIME,
    dependencyBuckets = listOf(
        fixturesCompilation.apiConfigurationName,
        fixturesCompilation.implementationConfigurationName,
        fixturesCompilation.runtimeOnlyConfigurationName,
    ),
)
