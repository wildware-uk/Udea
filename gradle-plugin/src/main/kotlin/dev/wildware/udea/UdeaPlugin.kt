package dev.wildware.udea

import org.gradle.api.Plugin
import org.gradle.api.Project
import dev.wildware.udea.assets.AssetScanner

class UdeaPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.plugins.apply(AssetScanner::class.java)
    }
}
