package dev.wildware.udea.assets

import androidx.compose.ui.awt.ComposePanel
import com.intellij.icons.AllIcons
import com.intellij.ide.actions.CreateFileFromTemplateDialog
import com.intellij.ide.actions.CreateFromTemplateAction
import com.intellij.json.JsonLanguage
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.InputValidatorEx
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import dev.wildware.udea.*
import dev.wildware.udea.editors.CreateEditor
import io.kanro.compose.jetbrains.expui.theme.DarkTheme

class UdeaAssetAction :
    CreateFromTemplateAction<PsiFile>("Udea Asset", "Create a new Udea asset", AllIcons.FileTypes.Json) {
    override fun buildDialog(project: Project, directory: PsiDirectory, builder: CreateFileFromTemplateDialog.Builder) {
        val classes = findClassesOfType(project, AssetType::class.java.name)
            .map { it.parent as PsiClass }

        builder
            .setTitle("New Udea Asset")
            .setValidator(object : InputValidatorEx {
                override fun getErrorText(inputString: String?): String? {
                    if (inputString.isNullOrBlank()) return "Name cannot be empty"
                    return null
                }

                override fun checkInput(inputString: String?): Boolean = !inputString.isNullOrBlank()
                override fun canClose(inputString: String?): Boolean = !inputString.isNullOrBlank()
            })

        classes.forEach {
            builder.addKind(camelCaseToTitle(it.name!!), UdeaIcons.Blueprint, it.qualifiedName!!)
        }
    }

    override fun getActionName(directory: PsiDirectory?, newName: String, templateName: String?): String {
        return "Create Udea Asset $newName"
    }

    override fun createFile(name: String, templateName: String, directory: PsiDirectory): PsiFile {
        val fileName = "$name.json"
        val psiJavaFacade = JavaPsiFacade.getInstance(directory.project)

        val psiElementFactory = PsiElementFactory.getInstance(directory.project)

        val psiClass = psiJavaFacade
            .findClass(templateName, GlobalSearchScope.allScope(directory.project))

        val assetClass =
            psiJavaFacade.findClass("dev.wildware.udea.assets.Asset", GlobalSearchScope.allScope(directory.project))!!

        val fullType = psiElementFactory.createType(assetClass, psiElementFactory.createType(psiClass!!))

        val builder = fromPsiType(directory.project, fullType)

        val dialog = object : DialogWrapper(directory.project) {
            init {
                title = "Create $name Asset"
                init()
            }

            override fun createCenterPanel() = ComposePanel().apply {
                setContent {
                    DarkTheme {
                        builder.CreateEditor(directory.project)
                    }
                }
            }
        }

        if (!dialog.showAndGet()) {
            throw IllegalStateException("Dialog cancelled")
        }


        val json = Json
            .toJson(builder)

        val file = PsiFileFactory.getInstance(directory.project).createFileFromText(
            fileName,
            JsonLanguage.INSTANCE,
            json,
        )

        WriteCommandAction.runWriteCommandAction(directory.project) {
            directory.add(file)
        }

        return directory.findFile(fileName)
            ?: throw IllegalStateException("Failed to create file: $fileName")
    }
}
