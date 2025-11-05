package dev.wildware.udea.assets

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.util.messages.MessageBusConnection


@Service(Service.Level.PROJECT)
class UdeaAssetManager(private val project: Project) {
    private var connection: MessageBusConnection = project.messageBus.connect()

    init {
        connection.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                if (events.any { it.file?.name?.endsWith(".udea.kts") == true }) {
                    reloadAssets()
                }
            }
        })
    }

    fun reloadAssets() {
        Assets.clear()
        ProjectRootManager.getInstance(project).contentSourceRoots.forEach { root ->
//            VfsUtil.collectChildrenRecursively(root).forEach { file ->
//                if (file.isValid && file.name.endsWith(".udea.kts")) {
//                    println("Found asset file: ${file.path}")
//                    try {
//                        val assets = loadAssets(file.toNioPath().toFile())
//
//                        val path = file.path.substringAfter("assets/")
//
//                        asset.asset?.path = path
//                        asset.asset?.name = file.nameWithoutExtension
//
//                        asset.asset?.let {
//                            Assets[path] = it
//                        }
//                    } catch (e: Exception) {
//                        println("Failed to load asset file: ${file.path}")
//                        e.printStackTrace()
//                    }
//                }
//            }
        }

        println("Reloaded assets")
    }
}
