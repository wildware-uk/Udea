package dev.wildware.udea.assets

import org.gradle.api.Plugin
import org.gradle.api.Project
import java.io.File

class AssetScanner : Plugin<Project> {
    override fun apply(project: Project) {
        project.tasks.register("scanAssets") {
            doLast {
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

