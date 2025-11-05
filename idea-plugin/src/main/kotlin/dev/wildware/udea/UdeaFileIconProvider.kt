package dev.wildware.udea

import com.intellij.ide.FileIconProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import javax.swing.Icon

/**
 * Provides a custom icon for UDEA asset files (e.g., *.udea.kts) across the IDE.
 */
class UdeaFileIconProvider : FileIconProvider, DumbAware {
    override fun getIcon(file: VirtualFile, flags: Int, project: Project?): Icon? {
        return if (file.name.endsWith(".udea.kts", ignoreCase = true)) {
            UdeaIcons.Blueprint
        } else null
    }
}
