plugins {
    id("udea.kotlin-build-tool")
    // The KSP Gradle plugin is applied here only so that the processor can be run over this
    // module's own test fixtures (`kspTest` below). Nothing in `src/main` is KSP-processed.
    id("com.google.devtools.ksp") version libs.versions.ksp.get()
}

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
tasks.test {
    systemProperty(
        "udea.updateGeneratedHashes",
        providers.gradleProperty("udea.updateGeneratedHashes").getOrElse("false"),
    )
}
