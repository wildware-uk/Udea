package dev.wildware.udea.assets

import org.gradle.api.Plugin
import org.gradle.api.Project
import java.io.File

class AssetScanner : Plugin<Project> {
    override fun apply(project: Project) {
        // `task ->` rather than the receiver-lambda `register("scanAssets") { doLast { ... } }`.
        // That overload is an `org.gradle.kotlin.dsl` extension, and this module no longer
        // applies `kotlin-dsl` -- it pinned the language version to 1.8, which Kotlin 2.4
        // refuses outright (issue #186). `Action<? super Task>` is the plain Gradle Java API and
        // the behaviour is identical. This module is a `rewrite` row in
        // `docs/migration/ledger.md` destined for `udea-assets-compiler`, so the change is kept
        // to the one line the toolchain forced.
        project.tasks.register("scanAssets") { task ->
            task.doLast {
                val assetsDir = project.projectDir.resolve("assets")
                if (assetsDir.exists()) {
                    scanDirectory(assetsDir)
                } else {
                    println("Assets directory not found at: ${assetsDir.absolutePath}")
                }
            }
        }
    }

    private fun scanDirectory(directory: File) {
        directory.walk().forEach { file ->
            if (file.isFile) {
                println("Found asset: ${file.relativeTo(directory)}")
            }
        }
    }
}

