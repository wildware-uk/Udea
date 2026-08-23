plugins {
    id("udea.kotlin-build-tool")
}

dependencies {
    api(project(":udea-diagnostics"))

    // The runtime asset model (#84), which landed after #85/#86/#87 did. It is here for exactly
    // one reason: the compile-time catalog this module has to produce keys every entry by the
    // **fully qualified name of the `AssetData` implementation** a declaration yields, and the
    // only way to write that name without it drifting is to take it off the `KClass` itself.
    // `AssetKind` does that; `AssetKindTest` fails if a name ever has to be spelled by hand.
    // See `docs/contracts/asset-index.md` for the three-party agreement this closes.
    api(project(":udea-assets"))

    // Pass 1 needs only the PSI half of this jar; pass 2 needs the compiler proper. It is one
    // artifact, so the honest declaration is `implementation` on the whole thing and a test
    // (`IsolatedScanTest`) proving pass 1 does not touch the resolution half.
    implementation(libs.kotlin.compiler.embeddable)
    implementation(libs.kotlin.scripting.common)
    implementation(libs.kotlin.scripting.jvm)
    implementation(libs.kotlin.scripting.jvm.host)
    // Not used by name anywhere in this module: it is the ScriptingCompilerConfigurationExtension
    // that teaches kotlin-compiler-embeddable how to compile a script at all. Without it on the
    // runtime classpath, every .udea.kts compile fails with "cannot find script definition".
    runtimeOnly(libs.kotlin.scripting.compiler.embeddable)
}

/**
 * The example asset tree, the compiler classpath and the repo root, handed to tests as
 * properties.
 *
 * Tests here drive real compilation of real files, and none of the three can be discovered
 * from inside a test JVM: `user.dir` is the module directory under Gradle but the daemon's
 * working directory under an IDE, and the "compile these scripts against this classpath"
 * API deliberately takes a classpath rather than reading its own.
 */
val repoRoot: String = rootProject.layout.projectDirectory.asFile.absolutePath
val exampleAssets: String =
    rootProject.layout.projectDirectory.dir("example/src/main/resources/assets").asFile.absolutePath

tasks.withType<Test>().configureEach {
    val runtime = sourceSets.test.map { it.runtimeClasspath }
    systemProperty("udea.repoRoot", repoRoot)
    // The Kotlin version the *catalog* declares, so ModuleContractTest can compare it against
    // the version kotlin-compiler-embeddable actually resolved. Comparing a constant in this
    // module to itself would prove nothing.
    systemProperty("udea.pinnedKotlinVersion", libs.versions.kotlin.get())
    systemProperty("udea.exampleAssets", exampleAssets)
    // The script classpath used by AssetCompilerTest, and the classpath the forked worker is
    // launched with. It is this module's own test runtime classpath, which is what makes the
    // fixture scripts able to see AssetScope.
    doFirst {
        systemProperty("udea.assetsCompiler.classpath", runtime.get().asPath)
    }
}
