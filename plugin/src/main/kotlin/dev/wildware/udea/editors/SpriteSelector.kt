package dev.wildware.udea.editors

import androidx.compose.runtime.*
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.project.Project
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import dev.wildware.udea.assets.Sprite
import dev.wildware.udea.compose.SelectBox
import dev.wildware.udea.pooledResult

object SpriteSelector : ComposeEditor<Sprite> {
    @Composable
    override fun CreateEditor(
        project: Project,
        type: EditorType<Sprite>,
        value: Sprite?,
        onValueChange: (Sprite) -> Unit
    ) {
        val files = remember {
            pooledResult {
                runReadAction {
                    getAllPngFiles(project)
                }
            }
        }

        var selectedFile by remember { mutableStateOf(value?.spritePath) }
        var open by remember { mutableStateOf(false) }

        SelectBox(
            files,
            selectedFile,
            open,
            onOpenChange = { open = it },
            onSelectChange = {
                selectedFile = it
                onValueChange(Sprite(it))
            })
    }

    fun getAllPngFiles(project: Project): List<String> {
        val pngFiles = FilenameIndex.getAllFilesByExt(project, "png", GlobalSearchScope.projectScope(project))
        return pngFiles.map { (it.canonicalPath ?: it.path).substringAfter("assets/") }
    }
}
