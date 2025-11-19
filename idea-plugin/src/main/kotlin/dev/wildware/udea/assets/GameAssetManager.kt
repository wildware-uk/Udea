package dev.wildware.udea.assets

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent

@Service(Service.Level.PROJECT)
class GameAssetManager(private val project: Project) {
    private val _assetFiles = mutableListOf<VirtualFile>()
    val assetFiles: List<VirtualFile> = _assetFiles

    init {
        project.messageBus.connect().subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                for (event in events) {
                    val file = event.file
                    if (file != null && file.path.contains("/assets/")) {
                        when {
                            event.isFromRefresh -> reloadAssets()
                            file.isValid -> {
                                if (!assetFiles.contains(file)) {
                                    _assetFiles.add(file)
                                }
                            }

                            else -> _assetFiles.remove(file)
                        }
                    }
                }
            }
        })
    }

    fun reloadAssets() {
        _assetFiles.clear()
        project.baseDir.findChild("assets")?.let { assetsRoot ->
            collectAssetFiles(assetsRoot)
        }
    }

    private fun collectAssetFiles(directory: VirtualFile) {
        for (file in directory.children) {
            if (file.isDirectory) {
                collectAssetFiles(file)
            } else {
                _assetFiles.add(file)
                println("Loaded Asset file: $file")
            }
        }
    }

    companion object {
        fun getInstance(project: Project): GameAssetManager {
            return project.service<GameAssetManager>()
        }
    }
}
