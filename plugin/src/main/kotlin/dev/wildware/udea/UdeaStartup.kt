package dev.wildware.udea

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity
import dev.wildware.udea.assets.AssetManager

class UdeaStartup : StartupActivity {
    override fun runActivity(project: Project) {
        project.service<AssetManager>().reloadAssets()
    }
}
