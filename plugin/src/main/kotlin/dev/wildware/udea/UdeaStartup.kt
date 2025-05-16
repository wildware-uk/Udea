package dev.wildware.udea

import com.fasterxml.jackson.databind.module.SimpleModule
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity
import dev.wildware.udea.assets.AssetManager
import dev.wildware.udea.assets.KClassDeserializer
import dev.wildware.udea.assets.KClassSerializer
import kotlin.reflect.KClass

class UdeaStartup : StartupActivity {
    override fun runActivity(project: Project) {
        Json.configure {
            registerModule(SimpleModule().apply {
                addSerializer(KClass::class.java, KClassSerializer())
                addDeserializer(
                    KClass::class.java, KClassDeserializer(
                        project.service<ProjectClassLoaderManager>().classLoader
                    )
                )
            })
        }

        project.service<AssetManager>().reloadAssets()
    }
}
