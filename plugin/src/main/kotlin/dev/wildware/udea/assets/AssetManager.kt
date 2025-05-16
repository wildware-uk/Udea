package dev.wildware.udea.assets

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.util.messages.MessageBusConnection
import dev.wildware.udea.Json
import dev.wildware.udea.ProjectClassLoaderManager
import dev.wildware.udea.editors.AssetFile


// TODO make this iterative?
@Service(Service.Level.PROJECT)
class AssetManager(private val project: Project) {
    private var connection: MessageBusConnection = project.messageBus.connect()

    init {
        connection.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                if (events.any { it.file?.extension == "udea" }) {
                    reloadAssets()
                }
            }
        })
    }

    fun reloadAssets() {
        Assets.clear()
        ProjectRootManager.getInstance(project).contentSourceRoots.forEach { root ->
            VfsUtil.collectChildrenRecursively(root).forEach { file ->
                if (file.isValid && file.extension == "udea") {
                    println("Found asset file: ${file.path}")
                    try {
                        val asset = Json
                            .withClassLoader(ProjectClassLoaderManager.getInstance(project).classLoader)
                            .fromJson<AssetFile>(file.inputStream)

                        asset.asset?.path = file.path
                        asset.asset?.name = file.nameWithoutExtension

                        Assets[file.path] = asset.asset!!
                    } catch (e: Exception) {
                        println("Failed to load asset file: ${file.path}")
                        e.printStackTrace()
                    }
                }
            }
        }

        println("Reloaded assets")
    }
}
