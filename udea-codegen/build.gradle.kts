import dev.wildware.udea.build.UdeaNetComponents
import dev.wildware.udea.build.registerNetProtocolLock

plugins {
    id("udea.kotlin-build-tool")
    // The KSP Gradle plugin is applied here only so that the processor can be run over this
    // module's own test fixtures (`kspTest` below). Nothing in `src/main` is KSP-processed.
    id("com.google.devtools.ksp") version libs.versions.ksp.get()
}

/** The name this module's fixture source set is processed as; half of every generated name. */
val MODULE_NAME = "CodegenFixtures"


dependencies {
    api(project(":udea-annotations"))

    // Diagnostics supplies the stable rule ids (UDEA0001..) that the KSP errors report under,
    // so a KSP error and the equivalent K2 FIR checker error key on the same id (spec 5).
    implementation(project(":udea-diagnostics"))

    implementation(libs.ksp.api)
    implementation(libs.kotlinpoet)

    // The processor is applied to its own test source set: `src/test/kotlin/.../fixtures`
    // carries the annotated components, and the generated Replicators are compiled and
    // round-tripped by the tests in the same source set. That keeps issue #29 inside one
    // module instead of adding a project to the shared settings script.
    kspTest(project(":udea-codegen"))

    testImplementation(project(":udea-core"))
    // ArrayFieldStore / ArrayBitWriter / ArrayBitReader: the executable reference the
    // Replicator contract was frozen against (udea-core issue #28).
    testImplementation(testFixtures(project(":udea-core")))

    // Test-only, and the seam that matters: the generated Replicators are written against
    // udea-core's frozen BitWriter/BitReader, and udea-net supplies the only production
    // implementation of them. GeneratedReplicatorNetRoundTripTest drives generated code
    // through the real BitBufferWriter over a real MTU-sized ByteArray, so "the generator
    // works end to end" is a claim about shipped code and not about a test fixture.
    // Nothing in src/main depends on udea-net, so this adds no edge to the module graph.
    testImplementation(project(":udea-net"))

    // KSP2's standalone runner. ProcessorLoggingTest and ProcessorFailureTest drive the
    // processor directly over throwaway sources, which is the only way to observe a build
    // that must FAIL. Version comes from the catalog, never a literal.
    testImplementation("com.google.devtools.ksp:symbol-processing-aa-embeddable:${libs.versions.ksp.get()}")
    testImplementation("com.google.devtools.ksp:symbol-processing-common-deps:${libs.versions.ksp.get()}")
}

// The module-level outputs — the `…NetProtocol` constant, `<Module>-net-protocol.lock` and the
// `ServiceLoader` index — are gated on these options, so a module that is only having its
// Replicators generated emits none of them. Setting them here is what makes the fixture source
// set exercise the aggregating path for real: the generated protocol object *and the generated
// index* are compiled by `compileTestKotlin` like any other output, under `-Werror`.
//
// `udea.netModuleService` used to be deliberately unset, and that was the hole: the emitted
// index was only ever substring-matched, so it went unnoticed that the service it named was an
// internal `object` and every generated index would have failed to compile. udea-net is already
// on this module's test compile *and* runtime classpath, so pointing the option at the real
// interface makes `GeneratedNetModuleServiceTest` load the index through `ServiceLoader` and
// get the replicators back — which is the only form of proof this mechanism accepts.
//
// `udea.projectComponents` is the third and it is not optional: a module that emits protocol
// identity must be numbered from the *project's* id space, or its `ComponentTypeId(0)` is also
// some other module's. The list is the reviewed `net-components.lock` in the repository root,
// read here rather than discovered, so an id is a promise made in a diff somebody read.
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
    arg("udea.moduleName", MODULE_NAME)
    arg("udea.netModuleService", "dev.wildware.udea.net.NetModule")
    arg(UdeaNetComponents.KSP_OPTION, projectComponents.get())
}

// The drift check, from `build-logic` so that it is the same gate for every module that emits
// a protocol rather than one module's private arrangement. `udeaCheckProtocolLock` runs on
// `check`; `udeaWriteProtocolLock` rewrites the reviewed file deliberately.
registerNetProtocolLock(
    generatedLock = layout.buildDirectory.file(
        "generated/ksp/test/resources/udea/$MODULE_NAME-net-protocol.lock",
    ),
    producingTask = "kspTestKotlin",
)

// Acceptance: generated sources compile with -Werror. The generated Replicators land in the
// test source set, so turning warnings into errors there is what actually enforces it.
kotlin {
    compilerOptions {
        allWarningsAsErrors = true
    }
}

// `gradlew :udea-codegen:test -Pudea.updateGeneratedHashes=true` rewrites the checked-in
// SHA-256 of every generated file. A normal run compares against it, so an unintended change to
// the emitter shows up as a failing test rather than as a silent change to the wire format.
//
// The wire contract itself is not updated by a system property: `udeaWriteProtocolLock` above
// owns that, so the same two task names work for every module rather than only for the one
// that happens to have a test source set watching its own generated resources.
tasks.test {
    systemProperty(
        "udea.updateGeneratedHashes",
        providers.gradleProperty("udea.updateGeneratedHashes").getOrElse("false"),
    )
}
