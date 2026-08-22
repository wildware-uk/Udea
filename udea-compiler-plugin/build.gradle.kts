plugins {
    id("udea.kotlin-build-tool")
}

dependencies {
    implementation(project(":udea-annotations"))
    compileOnly(libs.kotlin.compiler.embeddable)

    // No `udea-diagnostics` yet. The scaffold reports through the compiler's own
    // KtDiagnosticFactory; the dependency belongs to whichever checker issue first emits a
    // shared `UdeaDiagnostic` rule id (spec 5), not to an empty scaffold.

    // compileOnly does not reach the test classpath, and the end-to-end test drives a real
    // K2JVMCompiler in-process to prove the plugin actually loads through -Xplugin.
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

tasks.test {
    val pluginJar = tasks.jar.flatMap { it.archiveFile }
    inputs.file(pluginJar).withPropertyName("compilerPluginJar")

    systemProperty("udea.pinnedKotlinVersion", pinnedKotlinVersion)
    doFirst {
        systemProperty("udea.pluginJar", pluginJar.get().asFile.absolutePath)
    }
}
