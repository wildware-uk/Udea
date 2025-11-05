package dev.wildware.udea

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import dev.wildware.udea.assets.GameAssetManager
import dev.wildware.udea.assets.UdeaAssetManager

class UdeaStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        project.service<UdeaAssetManager>().reloadAssets()
        project.service<GameAssetManager>().reloadAssets()
    }
}
