plugins {
    id("udea.kotlin-build-tool")
}

dependencies {
    implementation(project(":udea-annotations"))

    // The stable rule ids (UDEA0001..) the FIR checkers report under. `udea-codegen`'s KSP
    // errors report under the same constants, which is what spec 5's "the K2 checkers emit
    // the same rule ids as the asset validator" means in practice: neither producer mints an
    // id, both read this registry.
    implementation(project(":udea-diagnostics"))

    compileOnly(libs.kotlin.compiler.embeddable)

    // compileOnly does not reach the test classpath, and the compile-testing suite drives a
    // real K2JVMCompiler in-process to prove the plugin actually loads through -Xplugin.
    testImplementation(libs.kotlin.compiler.embeddable)
}

kotlin {
    compilerOptions {
        // FIR checkers override `check` with context parameters in Kotlin 2.2 (the older
        // (declaration, context, reporter) overload is the deprecated shape).
        freeCompilerArgs.add("-Xcontext-parameters")
    }
}

/**
 * The exact Kotlin version this module is pinned to, asserted against the compiler the
 * plugin will be loaded by (spec 7). Set by the `udea.kotlin-build-tool` convention.
 */
val pinnedKotlinVersion = extensions.extraProperties["udeaPinnedKotlinVersion"] as String

/**
 * What Gradle's `KotlinCompilerPluginSupportPlugin` puts behind `-Xplugin`: the plugin jar
 * *and* everything it needs at run time. The plugin reads `udea-diagnostics` for its rule
 * ids, so a bare `-Xplugin=<jar>` would load and then die with a `NoClassDefFoundError` on
 * the first diagnostic. The suite passes the same classpath the real build does.
 */
tasks.test {
    val pluginJar = tasks.jar.flatMap { it.archiveFile }
    val pluginRuntimeClasspath = files(tasks.jar, configurations.named("runtimeClasspath"))
    inputs.file(pluginJar).withPropertyName("compilerPluginJar")
    inputs.files(pluginRuntimeClasspath).withPropertyName("compilerPluginClasspath")

    systemProperty("udea.pinnedKotlinVersion", pinnedKotlinVersion)
    systemProperty("udea.repoRoot", rootDir.absolutePath)
    // Resolved in `doFirst`: reading an archive path or a resolved configuration at
    // configuration time would break the configuration cache this build has enabled.
    doFirst {
        systemProperty("udea.pluginJar", pluginJar.get().asFile.absolutePath)
        systemProperty("udea.pluginClasspath", pluginRuntimeClasspath.asPath)
    }
}

/**
 * Spec 7's "the plugin must never become load-bearing", as a check rather than a habit.
 *
 * It is a `Test` task rather than a `doLast` because the rule it enforces
 * ([dev.wildware.udea.compiler.PluginOptionalRule]) has branch-level tests of its own, and a
 * `doLast` block is not reachable from any of them. Running it as its own task means
 * `udeaVerifyPluginOptional` is a real, separately invocable gate that the plugin-disabled
 * CI leg can name, while `check` still runs it through the normal suite.
 */
val udeaVerifyPluginOptional by tasks.registering(Test::class) {
    group = "verification"
    description =
        "Fails if any production source outside udea-compiler-plugin references a " +
            "dev.wildware.udea.compiler type, which would make the K2 plugin required to compile."

    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter { includeTestsMatching("dev.wildware.udea.compiler.PluginOptionalTest") }
    systemProperty("udea.repoRoot", rootDir.absolutePath)
}

tasks.named("check") {
    dependsOn(udeaVerifyPluginOptional)
}
